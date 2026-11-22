package com.orquitas.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Servidor de sockets TCP concurrente que acepta y gestiona clientes en tiempo de ejecucion.
 */
public class ReceptorConexiones implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ReceptorConexiones.class.getName());

    private final int puerto;
    private final GestorEstomago gestorEstomago;
    private final ClasificadorNeuroph clasificador;
    private final PlanificadorNavegacion planificador;
    private final long cooldownCapturaMs;
    private final long intervaloHeartbeatMs;
    private final boolean autoStartEnHello;
    private final List<ManejadorCliente> clientesConectados = Collections.synchronizedList(new ArrayList<>());

    private ServerSocket serverSocket;
    private volatile boolean corriendo = false;

    public ReceptorConexiones(int puerto, GestorEstomago gestorEstomago,
                              ClasificadorNeuroph clasificador,
                              PlanificadorNavegacion planificador) {
        this(puerto, gestorEstomago, clasificador, planificador,
                ConfiguracionServidor.COOLDOWN_CAPTURA_MS, ConfiguracionServidor.INTERVALO_HEARTBEAT_MS);
    }

    public ReceptorConexiones(int puerto, GestorEstomago gestorEstomago,
                              ClasificadorNeuroph clasificador,
                              PlanificadorNavegacion planificador,
                              long cooldownCapturaMs,
                              long intervaloHeartbeatMs) {
        this(puerto, gestorEstomago, clasificador, planificador,
                cooldownCapturaMs, intervaloHeartbeatMs, false);
    }

    public ReceptorConexiones(int puerto, GestorEstomago gestorEstomago,
                              ClasificadorNeuroph clasificador,
                              PlanificadorNavegacion planificador,
                              long cooldownCapturaMs,
                              long intervaloHeartbeatMs,
                              boolean autoStartEnHello) {
        this.puerto = puerto;
        this.gestorEstomago = gestorEstomago;
        this.clasificador = clasificador;
        this.planificador = planificador;
        this.cooldownCapturaMs = cooldownCapturaMs;
        this.intervaloHeartbeatMs = intervaloHeartbeatMs;
        this.autoStartEnHello = autoStartEnHello;
    }

    public void iniciar() {
        Thread thread = new Thread(this, "ReceptorConexiones-Puerto-" + puerto);
        thread.start();
    }

    @Override
    public void run() {
        corriendo = true;
        try {
            serverSocket = new ServerSocket(puerto);
            LOGGER.info(() -> "[SERVIDOR INICIADO] Escuchando conexiones TCP en puerto: " + puerto);

            while (corriendo && !serverSocket.isClosed()) {
                Socket clientSocket = serverSocket.accept();
                ManejadorCliente manejador = new ManejadorCliente(
                        clientSocket, gestorEstomago, this, clasificador, planificador,
                        cooldownCapturaMs, intervaloHeartbeatMs, autoStartEnHello);
                clientesConectados.add(manejador);
                Thread clientThread = new Thread(manejador, "SesionCliente-" + clientSocket.getPort());
                clientThread.start();
            }
        } catch (IOException e) {
            if (corriendo) {
                LOGGER.log(Level.SEVERE, "[SERVIDOR ERROR] Fallo en ServerSocket: " + e.getMessage(), e);
            }
        } finally {
            detener();
        }
    }

    public void transmitirATodos(String mensaje) {
        List<ManejadorCliente> copiaClientes;
        synchronized (clientesConectados) {
            copiaClientes = new ArrayList<>(clientesConectados);
        }
        for (ManejadorCliente client : copiaClientes) {
            client.enviarMensaje(mensaje);
        }
    }

    public void removerCliente(ManejadorCliente handler) {
        clientesConectados.remove(handler);
    }

    public void detener() {
        corriendo = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        List<ManejadorCliente> copiaClientes;
        synchronized (clientesConectados) {
            copiaClientes = new ArrayList<>(clientesConectados);
            clientesConectados.clear();
        }
        for (ManejadorCliente c : copiaClientes) {
            c.cerrar();
        }
        LOGGER.info("[SERVIDOR DETENIDO]");
    }
}
