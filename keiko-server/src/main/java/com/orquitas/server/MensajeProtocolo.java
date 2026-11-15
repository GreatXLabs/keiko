package com.orquitas.server;

import java.util.Locale;

/**
 * Protocolo de mensajes ASCII delimitados por salto de linea ('\n') sobre sockets TCP / RFCOMM.
 */
public class MensajeProtocolo {
    public static final String PREFIX_TELEMETRY = "TELEMETRY:";
    public static final String PREFIX_COMMAND = "COMMAND:";
    public static final String PREFIX_EVENT = "EVENT:";
    public static final String PREFIX_STATE_CHANGE = "STATE_CHANGE:";
    public static final String MSG_HEARTBEAT = "HEARTBEAT";
    public static final String PREFIX_ERROR = "ERROR:";

    // RF-05: Parsea y valida el vector de distancias sensoriales del cliente (4, 6, 8 o 10 canales).
    public static VectorSensores parsearTelemetria(String linea) {
        if (linea == null || !linea.startsWith(PREFIX_TELEMETRY)) {
            throw new IllegalArgumentException("Prefijo de telemetria invalido");
        }
        String data = linea.substring(PREFIX_TELEMETRY.length()).trim();
        String[] tokens = data.split(",");
        if (tokens.length < 4) {
            throw new IllegalArgumentException("Telemetria incompleta: se esperan al menos 4 distancias");
        }
        try {
            // Determinar cantidad de sensores y si incluye timestamp y pose (x, y, theta)
            int total = tokens.length;
            int numSensores;
            boolean hasTimestamp = false;
            boolean hasPose = false;

            if (total == 4 || total == 6 || total == 8 || total == 10) {
                numSensores = total;
            } else if (total == 5 || total == 7 || total == 9 || total == 11) {
                numSensores = total - 1;
                hasTimestamp = true;
            } else if (total == 12 || total == 14) {
                // 8 sensores + ts + pose (12) o 10 sensores + ts + pose (14).
                numSensores = total - 4;
                hasTimestamp = true;
                hasPose = true;
            } else {
                throw new IllegalArgumentException("Cantidad de campos de telemetria no soportada: " + total);
            }

            // Desambiguacion de 10 campos: 6 sensores + ts + 3 pose vs 10 sensores sin ts.
            if (total == 10) {
                double t6 = Double.parseDouble(tokens[6].trim());
                double t7 = Double.parseDouble(tokens[7].trim());
                double t8 = Double.parseDouble(tokens[8].trim());
                if (t6 > 500.0 || (Math.abs(t7) < 15.0 && Math.abs(t8) < 15.0)) {
                    numSensores = 6;
                    hasTimestamp = true;
                    hasPose = true;
                }
            }

            double[] canales = new double[numSensores];
            for (int i = 0; i < numSensores; i++) {
                canales[i] = Double.parseDouble(tokens[i].trim());
            }

            long ts = hasTimestamp ? Long.parseLong(tokens[numSensores].trim()) : System.currentTimeMillis();
            double posX = 0.0, posY = 0.0, theta = 0.0;
            if (hasPose) {
                posX = Double.parseDouble(tokens[numSensores + 1].trim());
                posY = Double.parseDouble(tokens[numSensores + 2].trim());
                theta = Double.parseDouble(tokens[numSensores + 3].trim());
            }
            return new VectorSensores(canales, ts, hasPose, posX, posY, theta);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Formato numerico corrupto en telemetria", e);
        }
    }

    // RF-08: Serializa la indicacion de movimiento diferencial y estado.
    public static String crearComando(ComandoRobot cmd) {
        return String.format(Locale.US, "%s%.2f,%.2f,%s\n",
                PREFIX_COMMAND, cmd.getVLeft(), cmd.getVRight(), cmd.getEstado().name());
    }

    // Crea trama de notificacion de transicion al Modo Escape.
    public static String crearModoEscape() {
        return String.format("%s%s\n", PREFIX_STATE_CHANGE, EstadoRobot.ESCAPE.name());
    }

    // Genera trama de latido para el control de timeout fail-safe.
    public static String crearHeartbeat() {
        return MSG_HEARTBEAT + "\n";
    }

    // Construye un mensaje de error formal para el enlace de comunicaciones.
    public static String crearError(String detalle) {
        return String.format("%s%s\n", PREFIX_ERROR, detalle);
    }
}
