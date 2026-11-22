package com.orquitas.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Hilo de atencion por sesion (Hilo Escritor): despacha telemetria (RF-01..RF-04),
 * eventos de ingesta (RF-05..RF-06) y latidos HEARTBEAT.
 */
public class ManejadorCliente implements Runnable {
    private final Socket socket;
    private BufferedReader lector;
    private BufferedWriter escritor;
    private final GestorEstomago gestorEstomago;
    private final ClasificadorNeuroph clasificador;
    private final PlanificadorNavegacion planificador;

    public ManejadorCliente(Socket socket,
                            GestorEstomago gestorEstomago,
                            ClasificadorNeuroph clasificador,
                            PlanificadorNavegacion planificador) {
        this.socket = socket;
        this.gestorEstomago = gestorEstomago;
        this.clasificador = clasificador;
        this.planificador = planificador;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            lector = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            escritor = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String linea;
            while ((linea = lector.readLine()) != null) {
                String trama = linea.trim();
                if (trama.isEmpty()) {
                    continue;
                }
                if (trama.startsWith("TELEMETRY:")) {
                    procesarTelemetria(trama);
                } else if (trama.startsWith("EVENT:")) {
                    procesarEvento(trama);
                } else if ("HEARTBEAT".equals(trama)) {
                    enviarMensaje(MensajeProtocolo.crearHeartbeat());
                }
            }
        } catch (IOException e) {
            if (!socket.isClosed()) {
                System.err.println("[SESION] Enlace interrumpido con cliente: " + e.getMessage());
            }
        } finally {
            cerrar();
        }
    }

    private void procesarTelemetria(String linea) {
        VectorSensores sensores;
        try {
            sensores = MensajeProtocolo.parsearTelemetria(linea);
        } catch (IllegalArgumentException e) {
            System.err.println("[TELEMETRIA] Trama corrupta descartada: " + e.getMessage());
            return;
        }
        if (!sensores.isValido()) {
            System.err.println("[TELEMETRIA] Lectura fuera de rango fisico descartada: " + linea);
            return;
        }
        String[] ternaPercepcion = clasificador.clasificar(sensores);
        ComandoRobot cmd = planificador.evaluar(sensores, ternaPercepcion);
        enviarMensaje(MensajeProtocolo.crearComando(cmd));
    }

    private void procesarEvento(String linea) {
        String evento = linea.substring("EVENT:".length()).trim();
        if (evento.toUpperCase(Locale.ROOT).contains("CAPTURA")) {
            try {
                int numeroCaptura = gestorEstomago.registrarCaptura(evento);
                enviarMensaje("ACK:CAPTURA_" + numeroCaptura);
            } catch (IOException e) {
                System.err.println("[ESTOMAGO] Fallo de I/O al registrar captura (" + evento + "): " + e.getMessage());
            }
        }
    }

    public synchronized void enviarMensaje(String mensaje) {
        if (escritor == null || socket.isClosed()) {
            return;
        }
        try {
            escritor.write(mensaje);
            escritor.write('\n');
            escritor.flush();
        } catch (IOException e) {
            cerrar();
        }
    }

    public synchronized void cerrar() {
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("[SESION] Error al cerrar socket de cliente: " + e.getMessage());
        }
    }
}
