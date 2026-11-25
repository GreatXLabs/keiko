package com.orquitas.server;

import java.util.Locale;

/**
 * Protocolo de mensajes ASCII delimitados por salto de linea ('\n') sobre Bluetooth RFCOMM/SPP.
 * Implementa las tramas requeridas por RF-01, RF-03, RF-04, RF-05 y RF-08 del DCU.
 */
public class MensajeProtocolo {
    public static final String PREFIX_TELEMETRY = "TELEMETRY:";
    public static final String PREFIX_COMMAND = "COMMAND:";
    public static final String PREFIX_EVENT = "EVENT:";
    public static final String PREFIX_STATE_CHANGE = "STATE_CHANGE:";
    public static final String MSG_HEARTBEAT = "HEARTBEAT";
    public static final String PREFIX_ERROR = "ERROR:";
    public static final String PREFIX_HELLO = "HELLO:";
    public static final String CMD_START = "CMD:START";
    public static final String CMD_STOP = "CMD:STOP";

    public record InfoHandshake(String version, String rig) {}

    public static InfoHandshake parsearHello(String linea) {
        if (linea == null || !linea.startsWith(PREFIX_HELLO)) {
            throw new IllegalArgumentException("Prefijo HELLO invalido");
        }
        String payload = linea.substring(PREFIX_HELLO.length()).trim();
        if (payload.isEmpty()) {
            throw new IllegalArgumentException("Payload HELLO vacio");
        }
        String[] partes = payload.split(",", 2);
        String version = partes[0].trim();
        if (version.isEmpty()) {
            throw new IllegalArgumentException("Version vacia en HELLO");
        }
        String rig = (partes.length > 1 && !partes[1].trim().isEmpty()) ? partes[1].trim() : "desconocido";
        return new InfoHandshake(version, rig);
    }

    public static String crearHello(String version, String rig) {
        return String.format(Locale.US, "%s%s,%s\n", PREFIX_HELLO, version, rig);
    }

    public static boolean esCompatibleMajorMinor(String versionA, String versionB) {
        if (versionA == null || versionB == null) return false;
        String[] a = versionA.split("-")[0].split("\\.");
        String[] b = versionB.split("-")[0].split("\\.");
        if (a.length < 2 || b.length < 2) return false;
        return a[0].equals(b[0]) && a[1].equals(b[1]);
    }

    /** RF-01: Parsea y valida el vector de distancias sensoriales del cliente (4, 6, 8 o 10 canales). */
    public static VectorSensores parsearTelemetria(String linea) {
        return parsearTelemetria(linea, 0, false);
    }

    /** RF-01: Parsea telemetria exigiendo opcionalmente canales exactos y timestamp (7 tokens para mega-3us-2laser). */
    public static VectorSensores parsearTelemetria(String linea, int canalesRequeridos, boolean exigirTimestamp) {
        if (linea == null || !linea.startsWith(PREFIX_TELEMETRY)) {
            throw new IllegalArgumentException("Prefijo de telemetria invalido");
        }
        String data = linea.substring(PREFIX_TELEMETRY.length()).trim();
        String[] tokens = data.split(",");
        if (tokens.length < 4) {
            throw new IllegalArgumentException("Telemetria incompleta: se esperan al menos 4 distancias");
        }
        try {
            int total = tokens.length;
            int numSensores;
            boolean hasTimestamp = false;

            if (total == 4 || total == 6 || total == 8 || total == 10) {
                numSensores = total;
            } else if (total == 5 || total == 7 || total == 9 || total == 11) {
                numSensores = total - 1;
                hasTimestamp = true;
            } else {
                throw new IllegalArgumentException("Cantidad de campos de telemetria no soportada: " + total);
            }

            if (canalesRequeridos > 0 && numSensores != canalesRequeridos) {
                throw new IllegalArgumentException(
                        "Trama truncada para rig actual: esperados " + canalesRequeridos + " sensores, recibidos " + numSensores);
            }
            if (exigirTimestamp && !hasTimestamp) {
                throw new IllegalArgumentException(
                        "Trama truncada: se requieren " + (numSensores + 1) + " campos incluyendo timestamp (recibidos " + total + ")");
            }

            double[] canales = new double[numSensores];
            for (int i = 0; i < numSensores; i++) {
                canales[i] = Double.parseDouble(tokens[i].trim());
            }

            long ts = hasTimestamp ? Long.parseLong(tokens[numSensores].trim()) : System.currentTimeMillis();
            return new VectorSensores(canales, ts);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Formato numerico corrupto en telemetria", e);
        }
    }

    /** RF-03 / RF-04: Serializa la indicacion de movimiento diferencial y estado. */
    public static String crearComando(ComandoRobot cmd) {
        return String.format(Locale.US, "%s%.2f,%.2f,%s\n",
                PREFIX_COMMAND, cmd.getVLeft(), cmd.getVRight(), cmd.getEstado().name());
    }

    /** RF-08: Crea trama de notificacion de cambio formal de estado (ej. ESCAPE, BUSQUEDA, ERROR_SEGURO). */
    public static String crearEstadoCambio(EstadoRobot estado) {
        EstadoRobot efectivo = (estado != null) ? estado : EstadoRobot.BUSQUEDA;
        return String.format("%s%s\n", PREFIX_STATE_CHANGE, efectivo.name());
    }

    /** RF-08: Crea trama de notificacion de transicion al Modo Escape. */
    public static String crearModoEscape() {
        return crearEstadoCambio(EstadoRobot.ESCAPE);
    }

    /** Genera trama de latido para el control de timeout Fail-Safe (RF-04). */
    public static String crearHeartbeat() {
        return MSG_HEARTBEAT + "\n";
    }

    /** Construye un mensaje de error formal para el enlace de comunicaciones. */
    public static String crearError(String detalle) {
        return String.format("%s%s\n", PREFIX_ERROR, detalle);
    }

    /** Inicia la corrida autonoma en firmware Mega (CMD:START). */
    public static String crearCmdStart() {
        return CMD_START + "\n";
    }

    /** Detiene la corrida autonoma y pasa el firmware Mega a reposo (CMD:STOP). */
    public static String crearCmdStop() {
        return CMD_STOP + "\n";
    }
}
