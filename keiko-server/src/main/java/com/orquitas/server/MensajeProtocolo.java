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
    public static final String PREFIX_HELLO = "HELLO:";
    public static final String PREFIX_DBG = "DBG:";
    public static final String CMD_START = "CMD:START";
    public static final String CMD_STOP = "CMD:STOP";
    // [SOLO PRUEBAS / DEBUG - retirar cuando se fijen constantes PWM, barrido y picker finales]
    public static final String PREFIX_CMD_PWM = "CMD:PWM:";
    public static final String PREFIX_LASER = "LASER:";
    public static final String MSG_PICKER_ON = "PICKER:ON";
    public static final String MSG_PICKER_OFF = "PICKER:OFF";

    public record InfoHandshake(String version, String rig) {}

    public record InfoDbgEstomago(int estomagoMm, int baseMm, int detector, int cambios,
                                  double senalMcps, double baseMcps, boolean modoSenal) {
        public InfoDbgEstomago(int estomagoMm, int baseMm, int detector, int cambios) {
            this(estomagoMm, baseMm, detector, cambios, -1.0, -1.0, false);
        }
    }

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

    // RF-05: Parsea y valida el vector de distancias sensoriales del cliente (4, 6, 8 o 10 canales).
    public static VectorSensores parsearTelemetria(String linea) {
        return parsearTelemetria(linea, 0, false);
    }

    /** Parsea telemetria exigiendo opcionalmente cantidad exacta de canales sensoriales y timestamp (ej. 6 + ts = 7 tokens para mega-3us-2laser). */
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
            // Determinar cantidad de sensores y si incluye timestamp (sin pose de simulacion).
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

    // RF-08: Serializa la indicacion de movimiento diferencial y estado.
    public static String crearComando(ComandoRobot cmd) {
        return String.format(Locale.US, "%s%.2f,%.2f,%s\n",
                PREFIX_COMMAND, cmd.getVLeft(), cmd.getVRight(), cmd.getEstado().name());
    }

    // Crea trama de notificacion de cambio formal de estado (ej. ESCAPE, BUSQUEDA, CAPTURA).
    public static String crearEstadoCambio(EstadoRobot estado) {
        EstadoRobot efectivo = (estado != null) ? estado : EstadoRobot.BUSQUEDA;
        return String.format("%s%s\n", PREFIX_STATE_CHANGE, efectivo.name());
    }

    // Crea trama de notificacion de transicion al Modo Escape.
    public static String crearModoEscape() {
        return crearEstadoCambio(EstadoRobot.ESCAPE);
    }

    // Genera trama de latido para el control de timeout fail-safe.
    public static String crearHeartbeat() {
        return MSG_HEARTBEAT + "\n";
    }

    // Construye un mensaje de error formal para el enlace de comunicaciones.
    public static String crearError(String detalle) {
        return String.format("%s%s\n", PREFIX_ERROR, detalle);
    }

    // Inicia la corrida autonoma en firmware Mega (CMD:START).
    public static String crearCmdStart() {
        return CMD_START + "\n";
    }

    // Detiene la corrida autonoma y pasa el firmware Mega a reposo (CMD:STOP).
    public static String crearCmdStop() {
        return CMD_STOP + "\n";
    }

    // [SOLO PRUEBAS / DEBUG] Ajusta PWM_ZONA_MUERTA y PWM_TOPE en el Mega en vivo.
    public static String crearCmdPwm(int minPwm, int maxPwm) {
        int clampedMin = Math.max(0, Math.min(255, minPwm));
        int clampedMax = Math.max(clampedMin, Math.min(255, maxPwm));
        return String.format(Locale.US, "%s%d,%d\n", PREFIX_CMD_PWM, clampedMin, clampedMax);
    }

    // [SOLO PRUEBAS / DEBUG] Controla el barrido de los servos laser en reposo (SWEEP / HOLD / grados).
    public static String crearCmdLaser(String modo) {
        String limpio = (modo != null && !modo.isBlank()) ? modo.trim() : "SWEEP";
        return PREFIX_LASER + limpio + "\n";
    }

    // [SOLO PRUEBAS / DEBUG] Enciende o apaga manualmente el picker en pruebas de banco.
    public static String crearPicker(boolean encender) {
        return (encender ? MSG_PICKER_ON : MSG_PICKER_OFF) + "\n";
    }

    /**
     * Parsea tramas DBG:estomago=<mm> base=<mm> ... (legacy) o DBG:senal=<mcps> base=<mcps> ... (v2.0.7).
     */
    public static InfoDbgEstomago parsearDbgEstomago(String linea) {
        if (linea == null || !linea.startsWith(PREFIX_DBG)) {
            return null;
        }
        String cuerpo = linea.substring(PREFIX_DBG.length()).trim();
        Integer estomago = null;
        int baseMm = 0;
        Double senalMcps = null;
        double baseMcps = 0.0;
        int det = -1;
        int cambios = 0;
        for (String token : cuerpo.split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq <= 0 || eq >= token.length() - 1) {
                continue;
            }
            String clave = token.substring(0, eq).trim();
            String valor = token.substring(eq + 1).trim();
            try {
                switch (clave) {
                    case "estomago" -> estomago = Integer.parseInt(valor);
                    case "senal" -> senalMcps = Double.parseDouble(valor);
                    case "base" -> {
                        if (senalMcps != null || valor.contains(".")) {
                            baseMcps = Double.parseDouble(valor);
                        } else {
                            baseMm = Integer.parseInt(valor);
                            baseMcps = baseMm;
                        }
                    }
                    case "det" -> det = Integer.parseInt(valor);
                    case "cambios" -> cambios = Integer.parseInt(valor);
                    default -> {}
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (senalMcps != null) {
            return new InfoDbgEstomago(-1, -1, det, cambios, senalMcps, baseMcps, true);
        }
        return (estomago != null) ? new InfoDbgEstomago(estomago, baseMm, det, cambios) : null;
    }
}
