package com.orquitas.server;

import java.util.Locale;

/**
 * Parsea tramas de telemetria entrantes y formatea comandos, cambios de estado y heartbeats (RF-01..RF-08).
 */
public class MensajeProtocolo {
    private MensajeProtocolo() {
    }

    public static VectorSensores parsearTelemetria(String linea) {
        if (linea == null || !linea.startsWith("TELEMETRY:")) {
            throw new IllegalArgumentException("Prefijo TELEMETRY invalido: " + linea);
        }
        String carga = linea.substring("TELEMETRY:".length()).trim();
        if (carga.isEmpty()) {
            throw new IllegalArgumentException("Carga util TELEMETRY vacia: " + linea);
        }
        String[] p = carga.split(",", -1);
        try {
            if (p.length == 3 || p.length == 4) {
                double usL = Double.parseDouble(p[0].trim());
                double usC = Double.parseDouble(p[1].trim());
                double usR = Double.parseDouble(p[2].trim());
                long ts = (p.length == 4) ? Long.parseLong(p[3].trim()) : System.currentTimeMillis();
                return new VectorSensores(usL, usC, usR, ts);
            }
            if (p.length == 6 || p.length == 7) {
                double usL = Double.parseDouble(p[0].trim());
                double usC = Double.parseDouble(p[1].trim());
                double usR = Double.parseDouble(p[2].trim());
                double laserL = Double.parseDouble(p[3].trim());
                double laserR = Double.parseDouble(p[4].trim());
                double ang = Double.parseDouble(p[5].trim());
                long ts = (p.length == 7) ? Long.parseLong(p[6].trim()) : System.currentTimeMillis();
                return new VectorSensores(usL, usC, usR, laserL, laserR, ang, ts);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Dato numerico corrupto en TELEMETRY: " + linea, e);
        }
        throw new IllegalArgumentException("Cantidad de canales TELEMETRY invalida (" + p.length + "): " + linea);
    }

    public static String crearComando(ComandoRobot cmd) {
        if (cmd == null) {
            throw new IllegalArgumentException("El comando motriz no puede ser nulo");
        }
        return String.format(Locale.US, "COMMAND:%.2f,%.2f,%s",
                cmd.getVLeft(), cmd.getVRight(), cmd.getEstado().name());
    }

    public static String crearModoEscape() {
        return "STATE_CHANGE:" + EstadoRobot.ESCAPE.name();
    }

    public static String crearHeartbeat() {
        return "HEARTBEAT";
    }
}
