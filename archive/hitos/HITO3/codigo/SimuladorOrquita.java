package com.orquitas.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Cliente TCP autonomo de verificacion integral de Hito 3 (RF-01 a RF-08).
 * Ejercita HEARTBEAT, telemetria de 6 canales (corredor libre, canica en boca,
 * bloqueo frontal y descarte de trama corrupta), 3 ingestas persistentes y
 * telemetria de 3 canales ultrasonicos en modo ESCAPE rumbo a la SALIDA.
 */
public final class SimuladorOrquita {
    private static final String HOST_PREDETERMINADO = "127.0.0.1";

    private SimuladorOrquita() {
    }

    public static void main(String[] args) throws Exception {
        String host = HOST_PREDETERMINADO;
        int puerto = ServidorCentral.PUERTO_PREDETERMINADO;

        for (int i = 0; i < args.length; i++) {
            if ("--host".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                host = args[++i];
            } else if ("--port".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                puerto = Integer.parseInt(args[++i]);
            }
        }

        System.out.printf("[SIMULADOR ORQUITA - HITO 3] Conectando a %s:%d...%n", host, puerto);
        CountDownLatch heartbeatRecibido = new CountDownLatch(1);
        CountDownLatch escapeRecibido = new CountDownLatch(1);
        List<String> comandosRecibidos = new CopyOnWriteArrayList<>();

        try (Socket socket = new Socket(host, puerto);
             BufferedReader lector = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter escritor = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            socket.setTcpNoDelay(true);
            Thread receptor = new Thread(
                    () -> escucharServidor(lector, heartbeatRecibido, escapeRecibido, comandosRecibidos),
                    "Simulador-Receptor-H3"
            );
            receptor.setDaemon(true);
            receptor.start();

            enviarLinea(escritor, "Verificacion liveness", "HEARTBEAT\n");
            if (!heartbeatRecibido.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("No se recibio respuesta HEARTBEAT del servidor.");
            }

            enviarTelemetria6Canales(escritor, "Corredor libre en BUSQUEDA",
                    85.0, 95.0, 82.0, 999.0, 999.0, 0.0);
            enviarTelemetria6Canales(escritor, "Canica detectada por laser en zona bucal",
                    90.0, 92.0, 88.0, 14.5, 14.0, 2.0);
            enviarTelemetria6Canales(escritor, "Bloqueo frontal inminente (RF-03 Alt. 3.1)",
                    12.0, 9.5, 8.5, 10.0, 9.0, 0.0);
            enviarLinea(escritor, "Trama corrupta descartada (RF-01 Alt. 7.1)",
                    "TELEMETRY:CORRUPTA,NaN,###\n");
            enviarTelemetria6Canales(escritor, "Recuperacion tras trama corrupta",
                    80.0, 90.0, 78.0, 999.0, 999.0, -10.0);

            for (int i = 1; i <= 3; i++) {
                enviarLinea(escritor, "Ingesta #" + i + " (RF-05 / RF-06)",
                        "EVENT:CAPTURA_LOBITO_" + i + "\n");
                Thread.sleep(120);
            }

            if (!escapeRecibido.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("No se recibio STATE_CHANGE:ESCAPE tras auditar 3 capturas.");
            }

            enviarTelemetriaEscape3US(escritor, "Modo ESCAPE - salida lateral izquierda (RF-01 Alt. 2.3)",
                    225.0, 62.0, 42.0);
            enviarTelemetriaEscape3US(escritor, "Modo ESCAPE - estrecho de salida centrado",
                    38.0, 235.0, 36.0);

            Thread.sleep(200);
            if (comandosRecibidos.size() < 6) {
                throw new IllegalStateException("Cantidad insuficiente de comandos recibidos: " + comandosRecibidos.size());
            }
            System.out.println("[SIMULADOR ORQUITA - HITO 3] Verificacion integral RF-01..RF-08 completada con exito.");
        }
    }

    private static void enviarLinea(BufferedWriter escritor, String etiqueta, String lineaConSalto)
            throws IOException, InterruptedException {
        System.out.printf("[ORQUITA -> SERVIDOR] (%s) %s", etiqueta, lineaConSalto);
        escritor.write(lineaConSalto);
        escritor.flush();
        Thread.sleep(150);
    }

    private static void enviarTelemetria6Canales(BufferedWriter escritor, String etiqueta,
                                                 double usL, double usC, double usR,
                                                 double laserL, double laserR, double anguloServo)
            throws IOException, InterruptedException {
        String trama = String.format(Locale.US, "TELEMETRY:%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%d%n",
                usL, usC, usR, laserL, laserR, anguloServo, System.currentTimeMillis());
        enviarLinea(escritor, etiqueta, trama);
    }

    private static void enviarTelemetriaEscape3US(BufferedWriter escritor, String etiqueta,
                                                  double usL, double usC, double usR)
            throws IOException, InterruptedException {
        String trama = String.format(Locale.US, "TELEMETRY:%.1f,%.1f,%.1f,%d%n",
                usL, usC, usR, System.currentTimeMillis());
        enviarLinea(escritor, etiqueta, trama);
    }

    private static void escucharServidor(BufferedReader lector,
                                         CountDownLatch heartbeatRecibido,
                                         CountDownLatch escapeRecibido,
                                         List<String> comandosRecibidos) {
        try {
            String respuesta;
            while ((respuesta = lector.readLine()) != null) {
                String limpia = respuesta.trim();
                System.out.println("[SERVIDOR -> ORQUITA] " + limpia);
                if (MensajeProtocolo.crearHeartbeat().equals(limpia)) {
                    heartbeatRecibido.countDown();
                } else if (MensajeProtocolo.crearModoEscape().equals(limpia)) {
                    escapeRecibido.countDown();
                } else if (limpia.startsWith("COMMAND:")) {
                    comandosRecibidos.add(limpia);
                }
            }
        } catch (IOException ignored) {
        }
    }
}
