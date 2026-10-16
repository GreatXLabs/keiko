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
    public static final String PREFIX_ACK = "ACK:";
    public static final String MSG_HEARTBEAT = "HEARTBEAT";
    public static final String PREFIX_ERROR = "ERROR:";

    private final String tipo;
    private final String contenido;

    // Crea un mensaje de protocolo con tipo y contenido.
    public MensajeProtocolo(String tipo, String contenido) {
        this.tipo = tipo;
        this.contenido = contenido;
    }

    // Retorna el tipo de mensaje de protocolo.
    public String getTipo() { return tipo; }

    // Retorna el cuerpo o contenido util del mensaje.
    public String getContenido() { return contenido; }

    // RF-05: Parsea y valida el vector de distancias sensoriales del cliente.
    public static VectorSensores parsearTelemetria(String linea) {
        if (!linea.startsWith(PREFIX_TELEMETRY)) {
            throw new IllegalArgumentException("Prefijo de telemetria invalido");
        }
        String data = linea.substring(PREFIX_TELEMETRY.length()).trim();
        String[] tokens = data.split(",");
        if (tokens.length < 4) {
            throw new IllegalArgumentException("Telemetria incompleta: se esperan al menos 4 distancias");
        }
        try {
            double ll = Double.parseDouble(tokens[0].trim());
            double lr = Double.parseDouble(tokens[1].trim());
            double hl = Double.parseDouble(tokens[2].trim());
            double hr = Double.parseDouble(tokens[3].trim());
            long ts = (tokens.length >= 5) ? Long.parseLong(tokens[4].trim()) : System.currentTimeMillis();
            return new VectorSensores(ll, lr, hl, hr, ts);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Formato numerico corrupto en telemetria", e);
        }
    }

    // RF-08: Serializa la indicacion de movimiento diferencial y estado.
    public static String crearComando(ComandoRobot cmd) {
        return String.format(Locale.US, "%s%.2f,%.2f,%s\n",
                PREFIX_COMMAND, cmd.getVLeft(), cmd.getVRight(), cmd.getEstado().name());
    }

    // Confirma la persistencia en disco de la captura recibida (RF-02/RF-03).
    public static String crearAckIngesta(int numeroCaptura) {
        return String.format("%sCAPTURA_%d\n", PREFIX_ACK, numeroCaptura);
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
