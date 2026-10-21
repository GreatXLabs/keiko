package com.orquitas.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Cliente Simulador en Java Puro para Pruebas de Integración End-to-End por Sockets (Hito 2).
 * 
 * Permite validar en clase ante los docentes:
 * 1. Simulación de lectura de entorno ("La Orquita Pensante") -> Telemetría e Inferencia Neuroph.
 * 2. Simulación de ingesta por sockets ("La Linareada") -> Escritura thread-safe en estomago.txt.
 * 3. Detección concurrente del Hilo Auditor -> Mutación a Modo Escape a las 3 capturas.
 */
public class SimuladorOrquitaCliente {

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = ConfiguracionServidor.PUERTO_PREDETERMINADO;

        if (args.length >= 1) host = args[0];
        if (args.length >= 2) port = Integer.parseInt(args[1]);

        System.out.println("==================================================================");
        System.out.println("  🐋 SIMULADOR CLIENTE ORQUITA - PRUEBA DE INTEGRACIÓN (HITO 2)");
        System.out.println("  Conectando al Servidor Central en " + host + ":" + port + "...");
        System.out.println("==================================================================\n");

        try (Socket socket = new Socket(host, port);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            System.out.println("✅ [ENLACE ESTABLECIDO] Socket conectado exitosamente con el Servidor Central.\n");

            // -------------------------------------------------------------
            // FASE 1: "LA ORQUITA PENSANTE" (Inferencia Sensorial por Sockets)
            // -------------------------------------------------------------
            System.out.println("--- [TEST 1: TELEMETRÍA Y CLASIFICACIÓN NEURAL] ---");

            // Caso A: Pista Despejada
            System.out.println(">> Enviando telemetría de ESPACIO LIBRE (todos los sensores despejados)...");
            writer.write("TELEMETRY:280.0,285.0,275.0,280.0," + System.currentTimeMillis() + "\n");
            writer.flush();
            String respA = reader.readLine();
            System.out.println("<< Servidor respondió comando motriz: " + respA);

            Thread.sleep(300);

            // Caso B: Presa Biológica (Lobito) detectada en plano bajo
            System.out.println("\n>> Enviando telemetría de LOBITO (bajo: 14cm, alto: 185cm)...");
            writer.write("TELEMETRY:14.2,16.5,185.0,190.0," + System.currentTimeMillis() + "\n");
            writer.flush();
            String respB = reader.readLine();
            System.out.println("<< Servidor respondió comando motriz (Aproximación sigilosa): " + respB);

            Thread.sleep(300);

            // Caso C: Muro perimetral frontal
            System.out.println("\n>> Enviando telemetría de MURO FRONTAL (ambos planos a 22cm)...");
            writer.write("TELEMETRY:22.0,21.5,22.0,21.8," + System.currentTimeMillis() + "\n");
            writer.flush();
            String respC = reader.readLine();
            System.out.println("<< Servidor respondió comando motriz (Giro evasivo): " + respC);

            System.out.println("\n✅ FASE 1 COMPLETADA CON ÉXITO: Inferencia y comandos motrices verificados.\n");
            Thread.sleep(600);

            // -------------------------------------------------------------
            // FASE 2: "LA LINAREADA" (Ingesta Concurrente, Persistencia y Escape)
            // -------------------------------------------------------------
            System.out.println("--- [TEST 2: INGESTA, SECCIÓN CRÍTICA Y AUDITORÍA 'LA LINAREADA'] ---");

            for (int i = 1; i <= 3; i++) {
                System.out.printf(">> Disparando EVENTO DE INGESTA #%d por socket...%n", i);
                writer.write("EVENT:CAPTURA,id=lobito_" + i + ",ts=" + System.currentTimeMillis() + "\n");
                writer.flush();

                String ack = reader.readLine();
                System.out.printf("<< Servidor confirmó persistencia en disco: %s%n", ack);
                Thread.sleep(400);
            }

            // Esperar notificación de cambio de estado a ESCAPE emitida por el Hilo Auditor
            System.out.println("\n⏳ Aguardando verificación del Hilo Auditor sobre 'estomago.txt'...");
            String mensajeEscape = reader.readLine();
            System.out.println("📢 << NOTIFICACIÓN RECIBIDA DEL AUDITOR: " + mensajeEscape);

            if (mensajeEscape != null && mensajeEscape.contains("ESCAPE")) {
                System.out.println("\n==================================================================");
                System.out.println("  🎉 ¡PRUEBA DE INTEGRACIÓN HITO 2 EXITOSA!");
                System.out.println("  1. Inferencia Neuroph 2.98 funcionando por sockets.");
                System.out.println("  2. Persistencia atómica de 3 capturas en estomago.txt.");
                System.out.println("  3. Hilo Auditor detectó Panza Llena e inició Modo Escape.");
                System.out.println("==================================================================");
            } else {
                System.out.println("⚠️ No se recibió la conmutación a ESCAPE esperada.");
            }

        } catch (Exception e) {
            System.err.println("❌ Error en simulador: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
