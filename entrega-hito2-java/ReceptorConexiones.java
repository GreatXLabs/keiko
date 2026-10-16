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
    private final List<ManejadorCliente> clientesConectados = Collections.synchronizedList(new ArrayList<>());

    private ServerSocket serverSocket;
    private volatile boolean corriendo = false;

    // Inicializa el receptor asignando puerto y servicios de clasificacion y persistencia.
    public ReceptorConexiones(int puerto, GestorEstomago gestorEstomago,
                              ClasificadorNeuroph clasificador,
                              PlanificadorNavegacion planificador) {
        this.puerto = puerto;
        this.gestorEstomago = gestorEstomago;
        this.clasificador = clasificador;
        this.planificador = planificador;
    }

    // Inicia el hilo receptor de conexiones de red.
    public void iniciar() {
        Thread thread = new Thread(this, "ReceptorConexiones-Puerto-" + puerto);
        thread.start();
    }

    // Bucle principal de aceptacion de sockets entrantes y despacho de sesiones concurrentes.
    @Override
    public void run() {
        corriendo = true;
        try {
            serverSocket = new ServerSocket(puerto);
            LOGGER.info(() -> "[SERVIDOR INICIADO] Escuchando conexiones TCP en puerto: " + puerto);

            while (corriendo && !serverSocket.isClosed()) {
                Socket clientSocket = serverSocket.accept();
                ManejadorCliente manejador = new ManejadorCliente(
                        clientSocket, gestorEstomago, this, clasificador, planificador);
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

    // Transmite un mensaje a todas las sesiones de clientes conectadas activamente.
    public void transmitirATodos(String mensaje) {
        synchronized (clientesConectados) {
            for (ManejadorCliente client : clientesConectados) {
                client.enviarMensaje(mensaje);
            }
        }
    }

    // Alias compatible para difusion de tramas a clientes conectados.
    public void broadcast(String mensaje) {
        transmitirATodos(mensaje);
    }

    // Remueve una sesion de cliente cerrada de la coleccion activa.
    public void removerCliente(ManejadorCliente handler) {
        clientesConectados.remove(handler);
    }

    // Cierra el socket del servidor y finaliza todas las sesiones activas de clientes.
    public void detener() {
        corriendo = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        synchronized (clientesConectados) {
            for (ManejadorCliente c : clientesConectados) {
                c.cerrar();
            }
            clientesConectados.clear();
        }
        LOGGER.info("[SERVIDOR DETENIDO]");
    }
}
