package com.orquitas.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Gestiona el ServerSocket de escucha y la difusion de mensajes hacia las sesiones de Orquita activas.
 */
public class ReceptorConexiones {
    private final int puerto;
    private ServerSocket serverSocket;
    private final List<ManejadorCliente> clientesConectados = Collections.synchronizedList(new ArrayList<>());

    public ReceptorConexiones(int puerto) {
        this.puerto = puerto;
    }

    public Socket aceptarCliente() throws IOException {
        if (serverSocket == null) {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(puerto));
        }
        return serverSocket.accept();
    }

    public void registrarCliente(ManejadorCliente cliente) {
        clientesConectados.add(cliente);
    }

    public void transmitirATodos(String mensaje) {
        synchronized (clientesConectados) {
            for (ManejadorCliente cliente : clientesConectados) {
                cliente.enviarMensaje(mensaje);
            }
        }
    }

    public void cerrar() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                System.err.println("[RECEPTOR] Error al cerrar ServerSocket: " + e.getMessage());
            }
        }
        synchronized (clientesConectados) {
            for (ManejadorCliente cliente : clientesConectados) {
                cliente.cerrar();
            }
            clientesConectados.clear();
        }
    }
}
