package com.orquitas.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Parametros de configuracion del Servidor Central cargados desde CLI, archivo .env o variables de entorno.
 */
public final class ConfiguracionServidor {
    private static final Logger LOGGER = Logger.getLogger(ConfiguracionServidor.class.getName());

    public static final String VERSION_SISTEMA = "2.0.0";
    public static final String RIG_HARDWARE = "mega-3us-2laser";

    public static final int PUERTO_PREDETERMINADO = 5000;
    public static final int BAUDIOS_SERIE_PREDETERMINADOS = 9600;
    public static final String NOMBRE_ARCHIVO_ESTOMAGO = "estomago.txt";
    public static final int AUDITOR_SONDEO_MS = 25;
    public static final int CAPTURAS_REQUERIDAS_ESCAPE = 3;
    public static final long COOLDOWN_CAPTURA_MS = 1500L;
    public static final long INTERVALO_HEARTBEAT_MS = 200L;
    public static final String RUTA_MODELO_PREDETERMINADA = "models/modelo_ultrasonico.nnet";

    private int puerto;
    private String puertoSerie;
    private int baudiosSerie;
    private String rutaArchivoEstomago;
    private String rutaArchivoModelo;
    private int auditorSondeoMs;
    private int capturasRequeridasEscape;
    private long cooldownCapturaMs;
    private long intervaloHeartbeatMs;
    private boolean resetSolicitado;
    private boolean listarPuertosSolicitado;
    private boolean autoStartSolicitado;
    private boolean noTuiSolicitado;
    private boolean forzarTuiSolicitado;
    private String directorioBaseEnv;

    public ConfiguracionServidor() {
        Map<String, String> envMap = cargarArchivoEnv();

        this.puerto = obtenerInt(envMap, "PORT", PUERTO_PREDETERMINADO);
        this.puertoSerie = obtenerString(envMap, "SERIAL_PORT", null);
        this.baudiosSerie = obtenerInt(envMap, "SERIAL_BAUD", BAUDIOS_SERIE_PREDETERMINADOS);
        this.rutaArchivoEstomago = obtenerString(envMap, "ESTOMAGO_FILE_PATH",
                obtenerString(envMap, "ESTOMAGO_FILE_NAME", NOMBRE_ARCHIVO_ESTOMAGO));
        this.rutaArchivoModelo = obtenerString(envMap, "MODEL_FILE_PATH", RUTA_MODELO_PREDETERMINADA);
        this.auditorSondeoMs = Math.min(obtenerInt(envMap, "AUDITOR_POLLING_MS", AUDITOR_SONDEO_MS), 25);
        this.capturasRequeridasEscape = obtenerInt(envMap, "CAPTURAS_REQUERIDAS_ESCAPE", CAPTURAS_REQUERIDAS_ESCAPE);
        this.cooldownCapturaMs = obtenerLong(envMap, "CAPTURE_COOLDOWN_MS", COOLDOWN_CAPTURA_MS);
        this.intervaloHeartbeatMs = obtenerLong(envMap, "HEARTBEAT_INTERVAL_MS", INTERVALO_HEARTBEAT_MS);
        this.autoStartSolicitado = Boolean.parseBoolean(obtenerString(envMap, "AUTO_START", "false"));
        this.noTuiSolicitado = Boolean.parseBoolean(obtenerString(envMap, "NO_TUI", "false"));
    }

    public static ConfiguracionServidor desdeArgumentos(String[] args) {
        ConfiguracionServidor cfg = new ConfiguracionServidor();
        if (args == null) return cfg;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--port".equalsIgnoreCase(arg) && i + 1 < args.length) {
                cfg.setPuerto(Integer.parseInt(args[++i]));
            } else if ("--serial".equalsIgnoreCase(arg) && i + 1 < args.length) {
                cfg.setPuertoSerie(args[++i]);
            } else if ("--baud".equalsIgnoreCase(arg) && i + 1 < args.length) {
                cfg.setBaudiosSerie(Integer.parseInt(args[++i]));
            } else if ("--model".equalsIgnoreCase(arg) && i + 1 < args.length) {
                cfg.setRutaArchivoModelo(args[++i]);
            } else if ("--estomago".equalsIgnoreCase(arg) && i + 1 < args.length) {
                cfg.setRutaArchivoEstomago(args[++i]);
            } else if ("--reset".equalsIgnoreCase(arg)) {
                cfg.setResetSolicitado(true);
            } else if ("--list-ports".equalsIgnoreCase(arg)) {
                cfg.setListarPuertosSolicitado(true);
            } else if ("--auto-start".equalsIgnoreCase(arg)) {
                cfg.setAutoStartSolicitado(true);
            } else if ("--no-tui".equalsIgnoreCase(arg)) {
                cfg.setNoTuiSolicitado(true);
            } else if ("--tui".equalsIgnoreCase(arg)) {
                cfg.setForzarTuiSolicitado(true);
            }
        }
        return cfg;
    }

    private Map<String, String> cargarArchivoEnv() {
        Map<String, String> mapa = new HashMap<>();
        String[] candidatos = {
            System.getProperty("env.file"),
            rutaEnvJuntoAlCodigo(),
            ".env",
            "../.env",
            "../../.env"
        };

        File archivoEncontrado = null;
        for (String c : candidatos) {
            if (c != null) {
                File f = new File(c);
                if (f.exists() && f.isFile()) {
                    archivoEncontrado = f;
                    break;
                }
            }
        }

        if (archivoEncontrado != null) {
            this.directorioBaseEnv = archivoEncontrado.getAbsoluteFile().getParent();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(archivoEncontrado), StandardCharsets.UTF_8))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    linea = linea.trim();
                    if (linea.isEmpty() || linea.startsWith("#")) continue;
                    int idx = linea.indexOf('=');
                    if (idx > 0) {
                        String clave = linea.substring(0, idx).trim();
                        String valor = linea.substring(idx + 1).trim();
                        if (valor.startsWith("\"") && valor.endsWith("\"") && valor.length() >= 2) {
                            valor = valor.substring(1, valor.length() - 1);
                        } else if (valor.startsWith("'") && valor.endsWith("'") && valor.length() >= 2) {
                            valor = valor.substring(1, valor.length() - 1);
                        }
                        mapa.put(clave, valor);
                    }
                }
                LOGGER.info("[CONFIG] Archivo .env cargado exitosamente desde: " + archivoEncontrado.getPath());
            } catch (Exception e) {
                LOGGER.warning("[CONFIG] No se pudo leer archivo .env: " + e.getMessage());
            }
        }
        return mapa;
    }

    private static String rutaEnvJuntoAlCodigo() {
        try {
            File code = new File(ConfiguracionServidor.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            File env = buscarEnvAscendente(code.isFile() ? code.getParentFile() : code);
            return env != null ? env.getPath() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static File buscarEnvAscendente(File inicio) {
        File dir = inicio;
        for (int i = 0; i < 3 && dir != null; i++, dir = dir.getParentFile()) {
            File env = new File(dir, ".env");
            if (env.isFile()) return env;
        }
        return null;
    }

    private static String obtenerString(Map<String, String> envMap, String key, String defaultValue) {
        String val = System.getenv(key);
        if (val != null && !val.trim().isEmpty()) return val.trim();
        if (envMap.containsKey(key)) {
            val = envMap.get(key);
            if (val != null && !val.trim().isEmpty()) return val.trim();
        }
        return defaultValue;
    }

    private static int obtenerInt(Map<String, String> envMap, String key, int defaultValue) {
        String val = obtenerString(envMap, key, null);
        if (val != null) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static long obtenerLong(Map<String, String> envMap, String key, long defaultValue) {
        String val = obtenerString(envMap, key, null);
        if (val != null) {
            try {
                return Long.parseLong(val.trim());
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    public int getPuerto() { return puerto; }
    public void setPuerto(int puerto) { this.puerto = puerto; }

    public String getPuertoSerie() { return puertoSerie; }
    public void setPuertoSerie(String puertoSerie) { this.puertoSerie = puertoSerie; }

    public int getBaudiosSerie() { return baudiosSerie; }
    public void setBaudiosSerie(int baudiosSerie) { this.baudiosSerie = baudiosSerie; }

    public String getRutaArchivoEstomago() { return rutaArchivoEstomago; }
    public void setRutaArchivoEstomago(String rutaArchivoEstomago) { this.rutaArchivoEstomago = rutaArchivoEstomago; }

    public String getRutaArchivoModelo() { return rutaArchivoModelo; }
    public void setRutaArchivoModelo(String rutaArchivoModelo) { this.rutaArchivoModelo = rutaArchivoModelo; }

    public int getAuditorSondeoMs() { return auditorSondeoMs; }
    public void setAuditorSondeoMs(int ms) { this.auditorSondeoMs = ms; }

    public int getCapturasRequeridasEscape() { return capturasRequeridasEscape; }
    public void setCapturasRequeridasEscape(int c) { this.capturasRequeridasEscape = c; }

    public long getCooldownCapturaMs() { return cooldownCapturaMs; }
    public void setCooldownCapturaMs(long cooldownCapturaMs) { this.cooldownCapturaMs = cooldownCapturaMs; }

    public long getIntervaloHeartbeatMs() { return intervaloHeartbeatMs; }
    public void setIntervaloHeartbeatMs(long intervaloHeartbeatMs) { this.intervaloHeartbeatMs = intervaloHeartbeatMs; }

    public boolean isResetSolicitado() { return resetSolicitado; }
    public void setResetSolicitado(boolean resetSolicitado) { this.resetSolicitado = resetSolicitado; }

    public boolean isListarPuertosSolicitado() { return listarPuertosSolicitado; }
    public void setListarPuertosSolicitado(boolean listarPuertosSolicitado) { this.listarPuertosSolicitado = listarPuertosSolicitado; }

    public boolean isAutoStartSolicitado() { return autoStartSolicitado; }
    public void setAutoStartSolicitado(boolean autoStartSolicitado) { this.autoStartSolicitado = autoStartSolicitado; }

    public boolean isNoTuiSolicitado() { return noTuiSolicitado; }
    public void setNoTuiSolicitado(boolean noTuiSolicitado) { this.noTuiSolicitado = noTuiSolicitado; }

    public boolean isForzarTuiSolicitado() { return forzarTuiSolicitado; }
    public void setForzarTuiSolicitado(boolean forzarTuiSolicitado) { this.forzarTuiSolicitado = forzarTuiSolicitado; }

    /** Determina si la TUI ANSI debe activarse segun flags CLI/env y disponibilidad de consola interactiva. */
    public boolean resolverModoTui(boolean tieneConsolaInteractiva) {
        if (noTuiSolicitado) {
            return false;
        }
        if (forzarTuiSolicitado) {
            return true;
        }
        return tieneConsolaInteractiva;
    }

    public File getArchivoEstomago() {
        File ruta = new File(rutaArchivoEstomago);
        if (ruta.isAbsolute() || directorioBaseEnv == null) {
            return ruta;
        }
        return new File(directorioBaseEnv, rutaArchivoEstomago);
    }

    public File resolverArchivoModelo() {
        File file = new File(rutaArchivoModelo);
        if (file.exists()) return file;
        if (directorioBaseEnv != null) {
            File juntoAEnv = new File(directorioBaseEnv, rutaArchivoModelo);
            if (juntoAEnv.exists()) return juntoAEnv;
        }
        String[] candidatos = {
            "models/modelo_ultrasonico.nnet",
            "keiko-server/models/modelo_ultrasonico.nnet",
            "../keiko-sim/data/models/modelo_ultrasonico.nnet",
            "keiko-sim/data/models/modelo_ultrasonico.nnet"
        };
        for (String c : candidatos) {
            File alt = new File(c);
            if (alt.exists()) return alt;
        }
        return file;
    }
}
