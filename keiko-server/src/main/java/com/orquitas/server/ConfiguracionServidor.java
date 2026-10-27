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
 * Parametros de configuracion del Servidor Central cargados desde archivo .env o variables de entorno.
 */
public final class ConfiguracionServidor {
    private static final Logger LOGGER = Logger.getLogger(ConfiguracionServidor.class.getName());

    public static final int PUERTO_PREDETERMINADO = 5000;
    public static final String NOMBRE_ARCHIVO_ESTOMAGO = "estomago.txt";
    public static final int AUDITOR_SONDEO_MS = 25;
    public static final int CAPTURAS_REQUERIDAS_ESCAPE = 3;
    public static final String RUTA_MODELO_PREDETERMINADA = "../keiko-sim/data/models/orquitas_production_champion.nnet";

    private int puerto;
    private String rutaArchivoEstomago;
    private String rutaArchivoModelo;
    private int auditorSondeoMs;
    private int capturasRequeridasEscape;
    private String directorioBaseEnv;

    // Inicializa la configuracion cargando variables desde .env y entorno de ejecucion.
    public ConfiguracionServidor() {
        Map<String, String> envMap = cargarArchivoEnv();

        this.puerto = obtenerInt(envMap, "PORT", PUERTO_PREDETERMINADO);
        this.rutaArchivoEstomago = obtenerString(envMap, "ESTOMAGO_FILE_PATH",
                obtenerString(envMap, "ESTOMAGO_FILE_NAME", NOMBRE_ARCHIVO_ESTOMAGO));
        this.rutaArchivoModelo = obtenerString(envMap, "MODEL_FILE_PATH", RUTA_MODELO_PREDETERMINADA);
        this.auditorSondeoMs = Math.min(obtenerInt(envMap, "AUDITOR_POLLING_MS", AUDITOR_SONDEO_MS), 25);
        this.capturasRequeridasEscape = obtenerInt(envMap, "CAPTURAS_REQUERIDAS_ESCAPE", CAPTURAS_REQUERIDAS_ESCAPE);
    }

    // Lee pares clave-valor de archivo .env buscando en directorio actual y superiores.
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

    // Ubica el .env junto al modulo compilado (target/classes o el jar), sin depender del CWD.
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

    // Sube hasta tres niveles buscando un .env.
    static File buscarEnvAscendente(File inicio) {
        File dir = inicio;
        for (int i = 0; i < 3 && dir != null; i++, dir = dir.getParentFile()) {
            File env = new File(dir, ".env");
            if (env.isFile()) return env;
        }
        return null;
    }

    // Obtiene una cadena priorizando entorno de sistema, mapa .env y valor por defecto.
    private static String obtenerString(Map<String, String> envMap, String key, String defaultValue) {
        String val = System.getenv(key);
        if (val != null && !val.trim().isEmpty()) return val.trim();
        if (envMap.containsKey(key)) {
            val = envMap.get(key);
            if (val != null && !val.trim().isEmpty()) return val.trim();
        }
        return defaultValue;
    }

    // Obtiene un entero priorizando entorno de sistema, mapa .env y valor por defecto.
    private static int obtenerInt(Map<String, String> envMap, String key, int defaultValue) {
        String val = obtenerString(envMap, key, null);
        if (val != null) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    // Retorna el puerto TCP configurado.
    public int getPuerto() { return puerto; }

    // Asigna el puerto TCP del servidor.
    public void setPuerto(int puerto) { this.puerto = puerto; }

    // Retorna la ruta al archivo de persistencia del estomago.
    public String getRutaArchivoEstomago() { return rutaArchivoEstomago; }

    // Asigna la ruta al archivo de persistencia del estomago.
    public void setRutaArchivoEstomago(String rutaArchivoEstomago) { this.rutaArchivoEstomago = rutaArchivoEstomago; }

    // Retorna la ruta al archivo de red neuronal.
    public String getRutaArchivoModelo() { return rutaArchivoModelo; }

    // Asigna la ruta al archivo de red neuronal.
    public void setRutaArchivoModelo(String rutaArchivoModelo) { this.rutaArchivoModelo = rutaArchivoModelo; }

    // Retorna el intervalo de sondeo del hilo auditor en milisegundos.
    public int getAuditorSondeoMs() { return auditorSondeoMs; }

    // Asigna el intervalo de sondeo del hilo auditor en milisegundos.
    public void setAuditorSondeoMs(int ms) { this.auditorSondeoMs = ms; }

    // Retorna el numero de capturas requeridas para conmutar al modo escape.
    public int getCapturasRequeridasEscape() { return capturasRequeridasEscape; }

    // Asigna el numero de capturas requeridas para conmutar al modo escape.
    public void setCapturasRequeridasEscape(int c) { this.capturasRequeridasEscape = c; }

    // Retorna el objeto File asociado a la ruta del estomago.
    public File getArchivoEstomago() {
        File ruta = new File(rutaArchivoEstomago);
        // Las rutas relativas de la configuracion se anclan al .env para no depender del CWD.
        if (ruta.isAbsolute() || directorioBaseEnv == null) {
            return ruta;
        }
        return new File(directorioBaseEnv, rutaArchivoEstomago);
    }

    // Resuelve la ubicacion real del archivo de red neuronal buscando rutas relativas habituales.
    public File resolverArchivoModelo() {
        File file = new File(rutaArchivoModelo);
        if (file.exists()) return file;
        File alt1 = new File("keiko-sim/data/models/orquitas_production_champion.nnet");
        if (alt1.exists()) return alt1;
        File alt2 = new File("../keiko-sim/data/models/orquitas_production_champion.nnet");
        if (alt2.exists()) return alt2;
        File alt3 = new File("../../keiko-sim/data/models/orquitas_production_champion.nnet");
        if (alt3.exists()) return alt3;
        return file;
    }
}
