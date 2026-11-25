package com.orquitas.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * [SOLO PRUEBAS / ENTRENAMIENTO - DESACTIVAR O RETIRAR EN CODIGO FINAL]
 * Grabador en vivo de ráfagas de telemetría real hacia CSV compatibles con Neuroph 2.98:
 * - dataset_ultrasonico_real.csv: dist_us,class_label
 * - dataset_canicas_real.csv: d_us_izq,d_us_cen,d_us_der,d_laser_izq,d_laser_der,angulo_servo,class_label
 * Preserva explícitamente valores reales 999.0 ("sin eco") para reflejar sensores HC-SR04/VL53L0X reales.
 */
public class GrabadorDatasetReal {
    private static final Logger LOGGER = Logger.getLogger(GrabadorDatasetReal.class.getName());

    public enum RedObjetivo {
        ULTRASONICO,
        CANICAS
    }

    public static final String HEADER_US = "dist_us,class_label";
    public static final String HEADER_CANICAS = "d_us_izq,d_us_cen,d_us_der,d_laser_izq,d_laser_der,angulo_servo,class_label";

    public static final List<String> ETIQUETAS_US = List.of("BLOQUEO", "ESPACIO_LIBRE", "SALIDA");
    public static final List<String> ETIQUETAS_CANICAS = List.of("LOBITO_IZQ", "LOBITO_CENTRO", "LOBITO_DER", "NINGUNA");

    private static final DateTimeFormatter FORMATO_ARCHIVO =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    private static final AtomicInteger SEQ_ARCHIVO = new AtomicInteger(0);

    private final File directorioSalida;
    private final File archivoUltrasonico;
    private final File archivoCanicas;
    private final Object bloqueo = new Object();

    private final Map<String, Integer> conteos = new LinkedHashMap<>();
    private RedObjetivo redObjetivo = RedObjetivo.CANICAS;
    private int idxEtiquetaUs = 0;
    private int idxEtiquetaCanica = 0;
    private volatile boolean grabando = false;
    private volatile int muestrasEnRafaga = 0;
    private volatile boolean filtroImpactoCanicaActivo = false;
    private volatile int muestrasDescartadasFiltro = 0;
    private volatile Consumer<Boolean> onMuestraProcesada = null;

    public GrabadorDatasetReal(File directorioSalida) {
        this.directorioSalida = directorioSalida;
        if (!directorioSalida.exists()) {
            directorioSalida.mkdirs();
        }
        this.archivoUltrasonico = new File(directorioSalida, "dataset_ultrasonico_real.csv");
        this.archivoCanicas = new File(directorioSalida, "dataset_canicas_real.csv");
        inicializarConteos();
        cargarConteosExistentes();
    }

    private void inicializarConteos() {
        for (String cls : ETIQUETAS_US) {
            conteos.put(cls, 0);
        }
        for (String cls : ETIQUETAS_CANICAS) {
            conteos.put(cls, 0);
        }
    }

    private void cargarConteosExistentes() {
        contarArchivo(archivoUltrasonico, 1);
        contarArchivo(archivoCanicas, 6);
    }

    private void contarArchivo(File archivo, int colEtiqueta) {
        if (!archivo.exists()) {
            return;
        }
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(archivo), StandardCharsets.UTF_8))) {
            String linea;
            boolean primera = true;
            while ((linea = br.readLine()) != null) {
                String trimmed = linea.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (primera && (trimmed.startsWith("dist_us") || trimmed.startsWith("d_us_izq"))) {
                    primera = false;
                    continue;
                }
                primera = false;
                String[] p = trimmed.split(",");
                if (p.length > colEtiqueta) {
                    String etiqueta = p[colEtiqueta].trim().toUpperCase(Locale.ROOT);
                    conteos.merge(etiqueta, 1, Integer::sum);
                }
            }
        } catch (Exception e) {
            LOGGER.warning("[GRABADOR] No se pudo contar " + archivo.getName() + ": " + e.getMessage());
        }
    }

    /** Alterna al modo Red 1 (HC-SR04 centro) y cicla BLOQUEO -> ESPACIO_LIBRE -> SALIDA. */
    public synchronized String ciclarModoUs() {
        if (redObjetivo != RedObjetivo.ULTRASONICO) {
            redObjetivo = RedObjetivo.ULTRASONICO;
        } else {
            idxEtiquetaUs = (idxEtiquetaUs + 1) % ETIQUETAS_US.size();
        }
        return getEtiquetaActiva();
    }

    /** Alterna al modo Red 2 (Canicas) y cicla LOBITO_IZQ -> LOBITO_CENTRO -> LOBITO_DER -> NINGUNA. */
    public synchronized String ciclarModoCanicas() {
        if (redObjetivo != RedObjetivo.CANICAS) {
            redObjetivo = RedObjetivo.CANICAS;
        } else {
            idxEtiquetaCanica = (idxEtiquetaCanica + 1) % ETIQUETAS_CANICAS.size();
        }
        return getEtiquetaActiva();
    }

    /** Selecciona explícitamente una etiqueta de Red 1 o Red 2 por nombre. */
    public synchronized boolean seleccionarEtiqueta(String etiqueta) {
        if (etiqueta == null || etiqueta.isBlank()) {
            return false;
        }
        String norm = etiqueta.trim().toUpperCase(Locale.ROOT);
        int usIdx = ETIQUETAS_US.indexOf(norm);
        if (usIdx >= 0) {
            redObjetivo = RedObjetivo.ULTRASONICO;
            idxEtiquetaUs = usIdx;
            return true;
        }
        int canIdx = ETIQUETAS_CANICAS.indexOf(norm);
        if (canIdx >= 0) {
            redObjetivo = RedObjetivo.CANICAS;
            idxEtiquetaCanica = canIdx;
            return true;
        }
        return false;
    }

    public synchronized boolean alternarGrabacion() {
        setGrabando(!grabando);
        return grabando;
    }

    public synchronized void setGrabando(boolean grabando) {
        if (grabando && !this.grabando) {
            this.muestrasEnRafaga = 0;
        }
        this.grabando = grabando;
    }

    public int getMuestrasEnRafaga() {
        return muestrasEnRafaga;
    }

    public boolean isGrabando() {
        return grabando;
    }

    public synchronized RedObjetivo getRedObjetivo() {
        return redObjetivo;
    }

    public synchronized String getEtiquetaActiva() {
        return redObjetivo == RedObjetivo.ULTRASONICO
                ? ETIQUETAS_US.get(idxEtiquetaUs)
                : ETIQUETAS_CANICAS.get(idxEtiquetaCanica);
    }

    public void setOnMuestraRegistrada(Runnable callback) {
        this.onMuestraProcesada = (callback == null) ? null : (guardada) -> {
            if (Boolean.TRUE.equals(guardada)) {
                callback.run();
            }
        };
    }

    public void setOnMuestraProcesada(Consumer<Boolean> callback) {
        this.onMuestraProcesada = callback;
    }

    public synchronized void setFiltroImpactoCanicaActivo(boolean activo) {
        this.filtroImpactoCanicaActivo = activo;
        this.muestrasDescartadasFiltro = 0;
    }

    public boolean isFiltroImpactoCanicaActivo() {
        return filtroImpactoCanicaActivo;
    }

    public int getMuestrasDescartadasFiltro() {
        return muestrasDescartadasFiltro;
    }

    /**
     * Archiva los CSV reales previos en data/real/archivo/<id>/ y reinicia conteos en 0
     * para iniciar una sesion limpia del asistente guiado sin mezclar grabaciones viejas.
     */
    public String archivarYReiniciarSiTieneDatos() {
        synchronized (bloqueo) {
            synchronized (this) {
                grabando = false;
                muestrasEnRafaga = 0;
                muestrasDescartadasFiltro = 0;
                if (getTotalMuestrasUs() == 0 && getTotalMuestrasCanicas() == 0
                        && !archivoUltrasonico.exists() && !archivoCanicas.exists()) {
                    return null;
                }
                File raizArchivo = new File(directorioSalida, "archivo");
                String id = FORMATO_ARCHIVO.format(Instant.now());
                File destinoDir = new File(raizArchivo, id);
                if (destinoDir.exists()) {
                    destinoDir = new File(raizArchivo, id + "-" + SEQ_ARCHIVO.incrementAndGet());
                }
                if (!destinoDir.exists()) {
                    destinoDir.mkdirs();
                }
                try {
                    if (archivoUltrasonico.exists()) {
                        Files.move(archivoUltrasonico.toPath(),
                                new File(destinoDir, archivoUltrasonico.getName()).toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (archivoCanicas.exists()) {
                        Files.move(archivoCanicas.toPath(),
                                new File(destinoDir, archivoCanicas.getName()).toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    LOGGER.warning("[GRABADOR] No se pudo archivar sesion previa: " + e.getMessage());
                }
                inicializarConteos();
                return destinoDir.getName();
            }
        }
    }

    /**
     * Filtro exclusivo para grabacion guiada de pasos LOBITO_*: descarta angulos del barrido
     * donde el laser mira al fondo vacio sin caer sobre la canica real, evitando falsos positivos.
     */
    static boolean esImpactoCanicaEntrenamiento(VectorSensores s, String etiqueta) {
        if (s == null || etiqueta == null || "NINGUNA".equals(etiqueta)) {
            return true;
        }
        double usMin = Math.min(s.getUsLeft(), Math.min(s.getUsCenter(), s.getUsRight()));
        double fondoUs = (usMin >= 900.0) ? 55.0 : usMin;
        double umbral = Math.min(fondoUs - 5.0, 37.0);
        double ang = s.getAnguloServo();
        return switch (etiqueta) {
            case "LOBITO_IZQ" -> s.getLaserLeft() < umbral && ang <= 25.0;
            case "LOBITO_DER" -> s.getLaserRight() < umbral && ang <= 25.0;
            case "LOBITO_CENTRO" -> (s.getLaserLeft() < umbral || s.getLaserRight() < umbral) && ang <= 10.0;
            default -> true;
        };
    }

    /**
     * Registra la lectura actual si la grabación está activa (llamado desde el receptor Bluetooth).
     * Mantiene intacto el valor 999.0 cuando el sensor real reporta sin eco.
     */
    public boolean registrarTelemetria(VectorSensores sensores) {
        if (!grabando || sensores == null || !sensores.isValido()) {
            return false;
        }
        boolean escrita = false;
        boolean descartadaPorFiltro = false;
        synchronized (bloqueo) {
            RedObjetivo modo;
            String etiqueta;
            boolean filtrarCanica;
            synchronized (this) {
                modo = this.redObjetivo;
                etiqueta = getEtiquetaActiva();
                filtrarCanica = this.filtroImpactoCanicaActivo && modo == RedObjetivo.CANICAS;
            }
            if (filtrarCanica && !esImpactoCanicaEntrenamiento(sensores, etiqueta)) {
                synchronized (this) {
                    muestrasDescartadasFiltro++;
                }
                descartadaPorFiltro = true;
            } else {
                File destino = (modo == RedObjetivo.ULTRASONICO) ? archivoUltrasonico : archivoCanicas;
                String header = (modo == RedObjetivo.ULTRASONICO) ? HEADER_US : HEADER_CANICAS;
                boolean escribirHeader = !destino.exists() || destino.length() == 0L;

                String fila;
                if (modo == RedObjetivo.ULTRASONICO) {
                    fila = String.format(Locale.US, "%.2f,%s", sensores.getUsCenter(), etiqueta);
                } else {
                    fila = String.format(Locale.US, "%.2f,%.2f,%.2f,%.1f,%.1f,%.1f,%s",
                            sensores.getUsLeft(),
                            sensores.getUsCenter(),
                            sensores.getUsRight(),
                            sensores.getLaserLeft(),
                            sensores.getLaserRight(),
                            sensores.getAnguloServo(),
                            etiqueta);
                }

                try (BufferedWriter bw = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(destino, true), StandardCharsets.UTF_8))) {
                    if (escribirHeader) {
                        bw.write(header);
                        bw.newLine();
                    }
                    bw.write(fila);
                    bw.newLine();
                    bw.flush();
                    synchronized (this) {
                        conteos.merge(etiqueta, 1, Integer::sum);
                        muestrasEnRafaga++;
                    }
                    escrita = true;
                } catch (Exception e) {
                    LOGGER.warning("[GRABADOR] Error escribiendo muestra real: " + e.getMessage());
                    return false;
                }
            }
        }
        Consumer<Boolean> cb = onMuestraProcesada;
        if (cb != null && (escrita || descartadaPorFiltro)) {
            cb.accept(escrita);
        }
        return escrita;
    }

    public synchronized Map<String, Integer> obtenerConteosPorClase() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(conteos));
    }

    public synchronized int getTotalMuestrasUs() {
        int total = 0;
        for (String cls : ETIQUETAS_US) {
            total += conteos.getOrDefault(cls, 0);
        }
        return total;
    }

    public synchronized int getTotalMuestrasCanicas() {
        int total = 0;
        for (String cls : ETIQUETAS_CANICAS) {
            total += conteos.getOrDefault(cls, 0);
        }
        return total;
    }

    public File getDirectorioSalida() {
        return directorioSalida;
    }

    public File getArchivoUltrasonico() {
        return archivoUltrasonico;
    }

    public File getArchivoCanicas() {
        return archivoCanicas;
    }
}
