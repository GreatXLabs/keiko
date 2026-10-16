package com.orquitas.server;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manejador concurrente de una sesion de cliente conectado por Socket TCP / RFCOMM.
 */
public class ManejadorCliente implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ManejadorCliente.class.getName());

    private final Socket socket;
    private final GestorEstomago gestorEstomago;
    private final ReceptorConexiones receptor;
    private final ClasificadorNeuroph clasificador;
    private final PlanificadorNavegacion planificador;
    private final AtomicBoolean conectado = new AtomicBoolean(true);

    private BufferedReader lector;
    private BufferedWriter escritor;
    private final Object bloqueoEscritura = new Object();

    // Asigna el socket del cliente y las dependencias de logica y persistencia.
    public ManejadorCliente(Socket socket, GestorEstomago gestorEstomago,
                            ReceptorConexiones receptor,
                            ClasificadorNeuroph clasificador,
                            PlanificadorNavegacion planificador) {
        this.socket = socket;
        this.gestorEstomago = gestorEstomago;
        this.receptor = receptor;
        this.clasificador = clasificador;
        this.planificador = planificador;
    }

    // Bucle concurrente de lectura de tramas entrantes del cliente.
    @Override
    public void run() {
        LOGGER.info(() -> "[CLIENTE CONECTADO] Desde: " + socket.getRemoteSocketAddress());
        try {
            lector = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            escritor = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String linea;
            while (conectado.get() && (linea = lector.readLine()) != null) {
                linea = linea.trim();
                if (linea.isEmpty()) continue;

                if (linea.startsWith(MensajeProtocolo.PREFIX_TELEMETRY)) {
                    procesarTelemetria(linea);
                } else if (linea.startsWith(MensajeProtocolo.PREFIX_EVENT)) {
                    procesarEvento(linea);
                } else if (linea.equals(MensajeProtocolo.MSG_HEARTBEAT)) {
                    enviarMensaje(MensajeProtocolo.crearHeartbeat());
                } else {
                    LOGGER.warning("[PROTOCOLO] Mensaje no reconocido: " + linea);
                }
            }
        } catch (IOException e) {
            if (conectado.get()) {
                LOGGER.info("[CLIENTE DESCONECTADO] " + e.getMessage());
            }
        } finally {
            cerrar();
        }
    }

    // RF-05: Recibir datos de sensores del cliente Orquita y coordinar RF-06, RF-07 y RF-08.
    private void procesarTelemetria(String linea) {
        try {
            VectorSensores sensores = MensajeProtocolo.parsearTelemetria(linea);
            if (!sensores.isValido()) {
                LOGGER.warning("[TELEMETRIA] Vector fuera de rango fisico.");
                return;
            }
            String clase = clasificador.clasificar(sensores);
            ComandoRobot cmd = planificador.evaluar(sensores, clase);
            enviarMensaje(MensajeProtocolo.crearComando(cmd));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[TELEMETRIA] Error al procesar: " + e.getMessage());
        }
    }

    // Recibe senal de ingesta (RF-02) y registra la captura en disco (RF-03).
    private void procesarEvento(String linea) {
        try {
            String evento = linea.substring(MensajeProtocolo.PREFIX_EVENT.length()).trim();
            LOGGER.info("[EVENTO RECIBIDO] " + evento);

            if (evento.contains("CAPTURA")) {
                int total = gestorEstomago.registrarCaptura(evento);
                enviarMensaje(MensajeProtocolo.crearAckIngesta(total));
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[EVENTO] Error al registrar captura: " + e.getMessage(), e);
        }
    }

    // RF-08: Enviar indicacion al cliente (comando motriz u orden).
    public void enviarMensaje(String mensaje) {
        synchronized (bloqueoEscritura) {
            if (!conectado.get() || escritor == null) return;
            try {
                escritor.write(mensaje);
                escritor.flush();
            } catch (IOException e) {
                LOGGER.warning("[ENVIO] Error al enviar: " + e.getMessage());
                cerrar();
            }
        }
    }

    // Cierra el socket y remueve este manejador del registro de clientes activos.
    public void cerrar() {
        if (conectado.compareAndSet(true, false)) {
            try {
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException ignored) {}
            if (receptor != null) {
                receptor.removerCliente(this);
            }
            LOGGER.info("[CLIENT HANDLER] Sesion cerrada.");
        }
    }

    // Retorna si la sesion de comunicacion continua activa.
    public boolean isConectado() {
        return conectado.get();
    }
}
