package com.orquitas.server;

import org.neuroph.core.NeuralNetwork;
import org.neuroph.core.data.DataSet;
import org.neuroph.core.data.DataSetRow;
import org.neuroph.core.events.LearningEvent;
import org.neuroph.nnet.MultiLayerPerceptron;
import org.neuroph.nnet.learning.MomentumBackpropagation;
import org.neuroph.util.TransferFunctionType;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * [SOLO PRUEBAS / ENTRENAMIENTO - DESACTIVAR O RETIRAR EN CODIGO FINAL]
 * Gestor integrado de reentrenamiento con Neuroph 2.98, historial inmutable y rollback
 * en caliente de modelo_ultrasonico.nnet y modelo_canicas.nnet sin dependencias externas.
 */
public class GestorModelosYEntrenamiento {
    private static final Logger LOGGER = Logger.getLogger(GestorModelosYEntrenamiento.class.getName());
    private static final DateTimeFormatter FORMATO_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    private static final AtomicInteger SECUENCIA_ID = new AtomicInteger(0);

    private static final int MAX_BASE_POR_CLASE_US = 250;
    private static final int MAX_BASE_POR_CLASE_CAN = 300;
    private static final int PESO_MINIMO_REAL_POR_CLASE = 180;

    public record EntradaHistorialModelo(
            String id,
            String fechaIso,
            double accuracyRed1,
            double accuracyRed2,
            int muestrasUsReal,
            int muestrasCanReal,
            String shaUs,
            String shaCan,
            String nota
    ) {}

    public record ResultadoRed(
            boolean entrenada,
            double accuracyPct,
            int muestrasTrain,
            int muestrasTest,
            int epocas,
            double errorFinal,
            int[][] matrizConfusion,
            String[] etiquetas
    ) {}

    public record ResultadoReentrenamiento(
            String versionId,
            ResultadoRed redUltrasonico,
            ResultadoRed redCanicas,
            int muestrasUsReal,
            int muestrasCanReal,
            String shaUs,
            String shaCan
    ) {}

    private record Muestra(double[] entradas, int claseIdx, boolean esReal) {}

    private final File directorioModelos;
    private final File directorioHistorial;
    private final File archivoModeloUs;
    private final File archivoModeloCan;
    private final File archivoUsBase;
    private final File archivoCanBase;
    private final File archivoUsReal;
    private final File archivoCanReal;
    private final int maxEpocasUs;
    private final int maxEpocasCanicas;

    public GestorModelosYEntrenamiento(File directorioModelos,
                                       File archivoUsBase,
                                       File archivoCanBase,
                                       File archivoUsReal,
                                       File archivoCanReal) {
        this(directorioModelos, archivoUsBase, archivoCanBase, archivoUsReal, archivoCanReal, 450, 550);
    }

    public GestorModelosYEntrenamiento(File directorioModelos,
                                       File archivoUsBase,
                                       File archivoCanBase,
                                       File archivoUsReal,
                                       File archivoCanReal,
                                       int maxEpocasUs,
                                       int maxEpocasCanicas) {
        this.directorioModelos = directorioModelos;
        this.directorioHistorial = new File(directorioModelos, "historial");
        this.archivoModeloUs = new File(directorioModelos, "modelo_ultrasonico.nnet");
        this.archivoModeloCan = new File(directorioModelos, "modelo_canicas.nnet");
        this.archivoUsBase = archivoUsBase;
        this.archivoCanBase = archivoCanBase;
        this.archivoUsReal = archivoUsReal;
        this.archivoCanReal = archivoCanReal;
        this.maxEpocasUs = Math.max(20, maxEpocasUs);
        this.maxEpocasCanicas = Math.max(20, maxEpocasCanicas);
    }

    /** Busca automáticamente los datasets sintéticos base de keiko-sim cuando están presentes. */
    public static File resolverDatasetBasePorDefecto(String nombreArchivo) {
        String[] candidatos = {
                "keiko-sim/data/datasets/" + nombreArchivo,
                "../keiko-sim/data/datasets/" + nombreArchivo,
                "data/datasets/" + nombreArchivo
        };
        for (String ruta : candidatos) {
            File f = new File(ruta);
            if (f.exists() && f.isFile()) {
                return f;
            }
        }
        return null;
    }

    /** Garantiza una copia inicial inmutable de los modelos activos antes de cualquier sobreescritura. */
    public synchronized void asegurarSnapshotInicial() {
        if (!directorioModelos.exists()) {
            directorioModelos.mkdirs();
        }
        if (!directorioHistorial.exists()) {
            directorioHistorial.mkdirs();
        }
        File index = new File(directorioHistorial, "index.tsv");
        if (index.exists() || (!archivoModeloUs.exists() && !archivoModeloCan.exists())) {
            return;
        }
        String baseId = FORMATO_ID.format(Instant.now()) + "-baseline";
        File carpetaBase = new File(directorioHistorial, baseId);
        carpetaBase.mkdirs();
        copiarSiExiste(archivoModeloUs, new File(carpetaBase, "modelo_ultrasonico.nnet"));
        copiarSiExiste(archivoModeloCan, new File(carpetaBase, "modelo_canicas.nnet"));
        agregarEntradaIndice(new EntradaHistorialModelo(
                baseId, Instant.now().toString(), -1.0, -1.0, 0, 0,
                sha256Corto(archivoModeloUs), sha256Corto(archivoModeloCan),
                "Snapshot inicial previo a reentrenamiento real"));
    }

    public synchronized ResultadoReentrenamiento reentrenarYActivar(String nota, ClasificadorNeuroph clasificador) {
        asegurarSnapshotInicial();

        List<Muestra> realesUs = cargarMuestrasUs(archivoUsReal, true);
        List<Muestra> realesCan = cargarMuestrasCanicas(archivoCanReal, true);
        if (realesUs.isEmpty() && realesCan.isEmpty()) {
            throw new IllegalStateException(
                    "No hay muestras reales grabadas en " + directorioModelos.getParentFile() + "/data/real/");
        }

        String versionId = generarVersionId();
        File carpetaVersion = new File(directorioHistorial, versionId);
        carpetaVersion.mkdirs();

        // Copia preventiva por si alguna red no se reentrena en esta corrida
        File destinoUsHist = new File(carpetaVersion, "modelo_ultrasonico.nnet");
        File destinoCanHist = new File(carpetaVersion, "modelo_canicas.nnet");
        copiarSiExiste(archivoModeloUs, destinoUsHist);
        copiarSiExiste(archivoModeloCan, destinoCanHist);

        ResultadoRed resUs = new ResultadoRed(false, -1.0, 0, 0, 0, 0.0, null, ClasificadorNeuroph.CLASES_US_3);
        if (!realesUs.isEmpty()) {
            List<Muestra> baseUs = cargarMuestrasUs(archivoUsBase, false);
            List<Muestra> datasetCombinado = combinarBalanceado(
                    baseUs, realesUs, ClasificadorNeuroph.CLASES_US_3.length, MAX_BASE_POR_CLASE_US);
            EntrenamientoSalida ent = entrenarYGuardarMlp(
                    datasetCombinado, new int[]{1, 8, 3}, 0.5, 0.6, maxEpocasUs,
                    ClasificadorNeuroph.CLASES_US_3, destinoUsHist);
            resUs = ent.resultado();
        }

        ResultadoRed resCan = new ResultadoRed(false, -1.0, 0, 0, 0, 0.0, null, ClasificadorNeuroph.CLASES_CANICAS_4);
        if (!realesCan.isEmpty()) {
            List<Muestra> baseCan = cargarMuestrasCanicas(archivoCanBase, false);
            List<Muestra> datasetCombinado = combinarBalanceado(
                    baseCan, realesCan, ClasificadorNeuroph.CLASES_CANICAS_4.length, MAX_BASE_POR_CLASE_CAN);
            EntrenamientoSalida ent = entrenarYGuardarMlp(
                    datasetCombinado, new int[]{6, 32, 16, 4}, 0.4, 0.6, maxEpocasCanicas,
                    ClasificadorNeuroph.CLASES_CANICAS_4, destinoCanHist);
            resCan = ent.resultado();
        }

        // Activar nuevos modelos en directorio oficial y recargar en memoria
        copiarSiExiste(destinoUsHist, archivoModeloUs);
        copiarSiExiste(destinoCanHist, archivoModeloCan);

        String shaUs = sha256Corto(archivoModeloUs);
        String shaCan = sha256Corto(archivoModeloCan);
        String notaLimpia = (nota == null || nota.isBlank()) ? "Reentrenamiento mixto real+base" : nota.replace('\t', ' ').trim();
        agregarEntradaIndice(new EntradaHistorialModelo(
                versionId, Instant.now().toString(),
                resUs.accuracyPct(), resCan.accuracyPct(),
                realesUs.size(), realesCan.size(),
                shaUs, shaCan, notaLimpia));

        if (clasificador != null) {
            clasificador.recargarDesdeArchivos(archivoModeloUs, archivoModeloCan);
        }

        return new ResultadoReentrenamiento(
                versionId, resUs, resCan, realesUs.size(), realesCan.size(), shaUs, shaCan);
    }

    public synchronized List<EntradaHistorialModelo> listarHistorial() {
        File index = new File(directorioHistorial, "index.tsv");
        if (!index.exists()) {
            return Collections.emptyList();
        }
        List<EntradaHistorialModelo> lista = new ArrayList<>();
        try {
            List<String> lineas = Files.readAllLines(index.toPath(), StandardCharsets.UTF_8);
            for (String linea : lineas) {
                if (linea.isBlank() || linea.startsWith("id\t")) {
                    continue;
                }
                String[] c = linea.split("\t", -1);
                if (c.length < 9) {
                    continue;
                }
                lista.add(new EntradaHistorialModelo(
                        c[0], c[1],
                        parseDoubleSeguro(c[2], -1.0),
                        parseDoubleSeguro(c[3], -1.0),
                        parseIntSeguro(c[4], 0),
                        parseIntSeguro(c[5], 0),
                        c[6], c[7], c[8]));
            }
        } catch (Exception e) {
            LOGGER.warning("[HISTORIAL] No se pudo leer index.tsv: " + e.getMessage());
        }
        Collections.reverse(lista);
        return lista;
    }

    /** Restaura una versión guardada del historial y recarga ClasificadorNeuroph en caliente. */
    public synchronized boolean activarVersion(String versionId, ClasificadorNeuroph clasificador) {
        if (versionId == null || versionId.isBlank()) {
            return false;
        }
        File carpeta = new File(directorioHistorial, versionId.trim());
        if (!carpeta.exists() || !carpeta.isDirectory()) {
            return false;
        }
        asegurarSnapshotInicial();
        File srcUs = new File(carpeta, "modelo_ultrasonico.nnet");
        File srcCan = new File(carpeta, "modelo_canicas.nnet");
        if (!srcUs.exists() && !srcCan.exists()) {
            return false;
        }
        copiarSiExiste(srcUs, archivoModeloUs);
        copiarSiExiste(srcCan, archivoModeloCan);
        if (clasificador != null) {
            clasificador.recargarDesdeArchivos(archivoModeloUs, archivoModeloCan);
        }
        LOGGER.info("[MODELOS] Activada version de historial: " + versionId);
        return true;
    }

    private record EntrenamientoSalida(ResultadoRed resultado) {}

    private EntrenamientoSalida entrenarYGuardarMlp(List<Muestra> muestras,
                                                    int[] capas,
                                                    double lr,
                                                    double momentum,
                                                    int maxEpocas,
                                                    String[] etiquetas,
                                                    File archivoDestino) {
        List<Muestra> barajadas = new ArrayList<>(muestras);
        Collections.shuffle(barajadas, new Random(42L));

        int corte = Math.max(1, (int) Math.round(barajadas.size() * 0.70));
        if (corte >= barajadas.size() && barajadas.size() > 1) {
            corte = barajadas.size() - 1;
        }
        List<Muestra> trainList = barajadas.subList(0, corte);
        List<Muestra> testList = (corte < barajadas.size()) ? barajadas.subList(corte, barajadas.size()) : barajadas;

        int inCount = capas[0];
        int outCount = capas[capas.length - 1];
        DataSet trainSet = construirDataSet(trainList, inCount, outCount);

        List<Integer> listaCapas = new ArrayList<>();
        for (int n : capas) {
            listaCapas.add(n);
        }
        MultiLayerPerceptron mlp = new MultiLayerPerceptron(listaCapas, TransferFunctionType.SIGMOID);
        MomentumBackpropagation rule = (MomentumBackpropagation) mlp.getLearningRule();
        rule.setLearningRate(lr);
        rule.setMomentum(momentum);
        rule.setMaxIterations(maxEpocas);
        rule.setMaxError(0.003);

        final class MejorEstado {
            double mejorError = Double.POSITIVE_INFINITY;
            double[] mejoresPesos = null;
            int sinMejora = 0;
        }
        MejorEstado mejor = new MejorEstado();
        rule.addListener(event -> {
            if (event.getEventType() == LearningEvent.Type.EPOCH_ENDED) {
                double err = rule.getTotalNetworkError();
                if (err < mejor.mejorError - 1e-6) {
                    mejor.mejorError = err;
                    mejor.mejoresPesos = copiarPesos(mlp);
                    mejor.sinMejora = 0;
                } else if (++mejor.sinMejora >= 120) {
                    rule.stopLearning();
                }
            }
        });

        mlp.learn(trainSet);
        if (mejor.mejoresPesos != null) {
            mlp.setWeights(mejor.mejoresPesos);
        }

        int[][] matriz = new int[outCount][outCount];
        int aciertos = 0;
        for (Muestra m : testList) {
            mlp.setInput(m.entradas());
            mlp.calculate();
            int pred = argmax(mlp.getOutput());
            matriz[m.claseIdx()][pred]++;
            if (pred == m.claseIdx()) {
                aciertos++;
            }
        }
        double acc = testList.isEmpty() ? 0.0 : (aciertos * 100.0 / testList.size());
        mlp.save(archivoDestino.getAbsolutePath());

        double errorFinal = (mejor.mejoresPesos != null) ? mejor.mejorError : rule.getTotalNetworkError();
        return new EntrenamientoSalida(new ResultadoRed(
                true, acc, trainList.size(), testList.size(),
                rule.getCurrentIteration(), errorFinal, matriz, etiquetas));
    }

    private List<Muestra> combinarBalanceado(List<Muestra> base,
                                             List<Muestra> reales,
                                             int numClases,
                                             int maxBasePorClase) {
        List<Muestra> resultado = new ArrayList<>();
        if (base != null && !base.isEmpty()) {
            List<Muestra> barajada = new ArrayList<>(base);
            Collections.shuffle(barajada, new Random(42L));
            int[] contBase = new int[numClases];
            for (Muestra m : barajada) {
                if (contBase[m.claseIdx()] < maxBasePorClase) {
                    resultado.add(m);
                    contBase[m.claseIdx()]++;
                }
            }
        }
        if (reales != null && !reales.isEmpty()) {
            Map<Integer, List<Muestra>> porClase = new LinkedHashMap<>();
            for (Muestra m : reales) {
                porClase.computeIfAbsent(m.claseIdx(), k -> new ArrayList<>()).add(m);
            }
            for (List<Muestra> grupo : porClase.values()) {
                int reps = Math.max(1, (int) Math.ceil((double) PESO_MINIMO_REAL_POR_CLASE / grupo.size()));
                for (int r = 0; r < reps; r++) {
                    resultado.addAll(grupo);
                }
            }
        }
        return resultado;
    }

    private List<Muestra> cargarMuestrasUs(File csv, boolean esReal) {
        List<Muestra> lista = new ArrayList<>();
        if (csv == null || !csv.exists()) {
            return lista;
        }
        try {
            List<String> lineas = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
            for (String linea : lineas) {
                String t = linea.trim();
                if (t.isEmpty() || t.startsWith("dist_us")) {
                    continue;
                }
                String[] c = t.split(",");
                if (c.length < 2) {
                    continue;
                }
                int idx = indiceClase(ClasificadorNeuroph.CLASES_US_3, c[1]);
                if (idx < 0) {
                    continue;
                }
                double d = Double.parseDouble(c[0].trim());
                lista.add(new Muestra(new double[]{ClasificadorNeuroph.normalizarUs(d)}, idx, esReal));
            }
        } catch (Exception e) {
            LOGGER.warning("[DATASET US] Error leyendo " + csv.getName() + ": " + e.getMessage());
        }
        return lista;
    }

    private List<Muestra> cargarMuestrasCanicas(File csv, boolean esReal) {
        List<Muestra> lista = new ArrayList<>();
        if (csv == null || !csv.exists()) {
            return lista;
        }
        try {
            List<String> lineas = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
            for (String linea : lineas) {
                String t = linea.trim();
                if (t.isEmpty() || t.startsWith("d_us_izq")) {
                    continue;
                }
                String[] c = t.split(",");
                if (c.length < 7) {
                    continue;
                }
                int idx = indiceClase(ClasificadorNeuroph.CLASES_CANICAS_4, c[6]);
                if (idx < 0) {
                    continue;
                }
                double[] in = {
                        ClasificadorNeuroph.normalizarUs(Double.parseDouble(c[0].trim())),
                        ClasificadorNeuroph.normalizarUs(Double.parseDouble(c[1].trim())),
                        ClasificadorNeuroph.normalizarUs(Double.parseDouble(c[2].trim())),
                        ClasificadorNeuroph.normalizarLaser(Double.parseDouble(c[3].trim())),
                        ClasificadorNeuroph.normalizarLaser(Double.parseDouble(c[4].trim())),
                        ClasificadorNeuroph.normalizarAnguloServo(Double.parseDouble(c[5].trim()))
                };
                lista.add(new Muestra(in, idx, esReal));
            }
        } catch (Exception e) {
            LOGGER.warning("[DATASET CAN] Error leyendo " + csv.getName() + ": " + e.getMessage());
        }
        return lista;
    }

    private static DataSet construirDataSet(List<Muestra> muestras, int inCount, int outCount) {
        DataSet ds = new DataSet(inCount, outCount);
        for (Muestra m : muestras) {
            double[] out = new double[outCount];
            out[m.claseIdx()] = 1.0;
            ds.add(new DataSetRow(m.entradas(), out));
        }
        return ds;
    }

    private void agregarEntradaIndice(EntradaHistorialModelo entry) {
        File index = new File(directorioHistorial, "index.tsv");
        boolean nuevo = !index.exists() || index.length() == 0L;
        StringBuilder sb = new StringBuilder();
        if (nuevo) {
            sb.append("id\tfecha\tred1_acc\tred2_acc\tus_real\tcan_real\tsha_us\tsha_can\tnota\n");
        }
        sb.append(String.format(Locale.US, "%s\t%s\t%.2f\t%.2f\t%d\t%d\t%s\t%s\t%s%n",
                entry.id(), entry.fechaIso(), entry.accuracyRed1(), entry.accuracyRed2(),
                entry.muestrasUsReal(), entry.muestrasCanReal(), entry.shaUs(), entry.shaCan(), entry.nota()));
        try {
            Files.writeString(index.toPath(), sb.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOGGER.warning("[HISTORIAL] Error actualizando index.tsv: " + e.getMessage());
        }
    }

    private String generarVersionId() {
        String base = FORMATO_ID.format(Instant.now());
        File candidata = new File(directorioHistorial, base);
        if (!candidata.exists()) {
            return base;
        }
        return base + "-" + SECUENCIA_ID.incrementAndGet();
    }

    private static int indiceClase(String[] clases, String etiqueta) {
        if (etiqueta == null) {
            return -1;
        }
        String norm = etiqueta.trim().toUpperCase(Locale.ROOT);
        for (int i = 0; i < clases.length; i++) {
            if (clases[i].equals(norm)) {
                return i;
            }
        }
        return -1;
    }

    private static int argmax(double[] vals) {
        int idx = 0;
        for (int i = 1; i < vals.length; i++) {
            if (vals[i] > vals[idx]) {
                idx = i;
            }
        }
        return idx;
    }

    private static double[] copiarPesos(MultiLayerPerceptron mlp) {
        Double[] boxed = mlp.getWeights();
        if (boxed == null) {
            return null;
        }
        double[] res = new double[boxed.length];
        for (int i = 0; i < boxed.length; i++) {
            res[i] = boxed[i] != null ? boxed[i] : 0.0;
        }
        return res;
    }

    private static void copiarSiExiste(File origen, File destino) {
        if (origen == null || !origen.exists()) {
            return;
        }
        try {
            if (destino.getParentFile() != null && !destino.getParentFile().exists()) {
                destino.getParentFile().mkdirs();
            }
            Files.copy(origen.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warning("[MODELOS] Error copiando " + origen.getName() + ": " + e.getMessage());
        }
    }

    public static String sha256Corto(File archivo) {
        if (archivo == null || !archivo.exists()) {
            return "---";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(archivo.toPath()));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6 && i < digest.length; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "err";
        }
    }

    private static double parseDoubleSeguro(String s, double def) {
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }

    private static int parseIntSeguro(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
}
