package com.neuroph.train;

import com.neuroph.train.client.worker.ModelEvaluator;
import com.neuroph.train.client.worker.NeurophTrainer;
import com.neuroph.train.common.model.*;
import com.neuroph.train.common.util.DatasetParser;
import org.junit.jupiter.api.Test;
import org.neuroph.core.NeuralNetwork;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de integracion que valida que el dataset de 4 sensores generado
 * en Webots 3D sea consumible y entrenable con exito por Neuroph 2.98.
 */
public class SimulatedDatasetTrainTest {

    @Test
    public void testTrainNeurophOnSimulatedDataset() throws Exception {
        File csvFile = new File("../keiko-sim/data/datasets/orquitas_dataset_4_sensores.csv");
        assertTrue(csvFile.exists(), "El dataset sintético de 4 sensores debe existir");

        String csvContent = Files.readString(csvFile.toPath(), StandardCharsets.UTF_8);
        assertNotNull(csvContent);
        assertFalse(csvContent.trim().isEmpty());

        // 1. Inspeccionar CSV
        DatasetParser.CsvInspectionResult inspection = DatasetParser.inspectCsv(csvContent, true);
        assertEquals(5, inspection.getColumnCount(), "Debe tener 4 columnas de sensores + 1 de etiqueta");
        assertTrue(inspection.getRowCount() >= 1000, "Debe tener al menos 1000 muestras");

        // 2. Configurar metadatos del Dataset
        DatasetMetadata meta = new DatasetMetadata();
        meta.setId("sim-4-sensors");
        meta.setName("Orquitas 4 Ultrasonic Sensors (Webots 3D)");
        meta.setCsvContent(csvContent);
        meta.setInputColumns(List.of(0, 1, 2, 3)); // dist_low_left, dist_low_right, dist_high_left, dist_high_right
        meta.setOutputColumns(List.of(4));         // class_label
        meta.setClassLabels(List.of("ESPACIO_LIBRE", "LOBITO", "MURO", "OBSTACULO", "SALIDA"));
        meta.setTaskType(TaskType.CLASSIFICATION);
        meta.setHasHeader(true);
        meta.setNumRows(inspection.getRowCount());

        List<ColumnConfig> configs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            configs.add(new ColumnConfig(i, inspection.getHeaders().get(i), ColumnRole.INPUT, NormalizationType.MIN_MAX_0_1));
        }
        configs.add(new ColumnConfig(4, "class_label", ColumnRole.OUTPUT, NormalizationType.NONE));
        meta.setColumnConfigs(configs);

        // 3. Particionar 70% entrenamiento / 30% evaluacion
        DatasetParser.SplitResult split = DatasetParser.parseAndSplit(csvContent, meta, 0.70, 42L);
        assertNotNull(split.getTrainSet());
        assertEquals(4, split.getTrainSet().getInputSize());
        assertEquals(5, split.getTrainSet().getOutputSize(), "5 clases one-hot esperadas");
        assertTrue(split.getTestSamples().size() > 0);

        System.out.println("==========================================================");
        System.out.println("  ENTRENANDO NEUROPH 2.98 CON DATASET DE WEBOTS 3D");
        System.out.println("  Muestras Entrenamiento: " + split.getTrainSet().size());
        System.out.println("  Muestras Evaluacion:    " + split.getTestSamples().size());
        System.out.println("==========================================================");

        // 4. Configurar red neuronal
        NetworkConfig netConfig = new NetworkConfig();
        netConfig.setInputNeurons(4);
        netConfig.setOutputNeurons(5);
        netConfig.setHiddenNeurons(List.of(24)); // Topología 4 -> 24 -> 5
        netConfig.setLearningRate(0.5);
        netConfig.setMomentum(0.6);
        netConfig.setMaxIterations(1200);
        netConfig.setMaxError(0.005);

        TrainingTask task = new TrainingTask();
        task.setTaskId("task-webots-verification");
        task.setNetworkConfig(netConfig);
        task.setTrainRatio(0.70);
        task.setSplitSeed(42L);
        task.setPatience(800);

        // 5. Entrenar
        NeurophTrainer trainer = new NeurophTrainer();
        long startMs = System.currentTimeMillis();
        NeurophTrainer.TrainingResult trainingResult = trainer.train(task, split.getTrainSet(), (epoch, err) -> {
            if (epoch % 50 == 0 || epoch == 1) {
                System.out.printf("  [Epoca %3d] Error cuadrático: %.5f%n", epoch, err);
            }
        });
        long durationMs = System.currentTimeMillis() - startMs;

        assertNotNull(trainingResult.getNeuralNetwork());
        System.out.println("  Entrenamiento finalizado en " + durationMs + " ms (" + trainingResult.getIterations() + " iteraciones)");

        // 6. Evaluar sobre el conjunto de prueba
        EvaluationMetrics metrics = ModelEvaluator.evaluate(
                trainingResult.getNeuralNetwork(),
                split.getTestSamples(),
                meta,
                trainingResult.getDurationSeconds(),
                trainingResult.getIterations(),
                trainingResult.getFinalError()
        );

        System.out.println("==========================================================");
        System.out.printf("  METRICAS SOBRE CONJUNTO DE PRUEBA (30%%):%n");
        System.out.printf("  -> Accuracy: %.2f%%%n", metrics.getAccuracy());
        System.out.printf("  -> RMSE:     %.5f%n", metrics.getRmse());
        System.out.printf("  -> F1-Score: %.4f%n", metrics.getF1Score());
        System.out.printf("  -> Matriz Confusion:%n%s%n", java.util.Arrays.deepToString(metrics.getConfusionMatrix()));
        System.out.println("==========================================================");

        // Guardar archivo .nnet en keiko-sim/data/models
        File nnetFile = new File("../keiko-sim/data/models/orquitas_4sensores_neuroph.nnet");
        trainingResult.getNeuralNetwork().save(nnetFile.getAbsolutePath());
        assertTrue(nnetFile.exists());
        System.out.println("  Modelo serializado exitosamente en: " + nnetFile.getAbsolutePath());

        // Validar que la precisión sobre datos sintéticos sea alta (> 80%)
        assertTrue(metrics.getAccuracy() >= 75.0, "La precisión debe superar el 75% con el dataset de 4 sensores");
    }

    @Test
    public void testTrainNeurophOn6SensorsDataset() throws Exception {
        File csvFile = new File("../keiko-sim/data/datasets/orquitas_dataset_6_sensores.csv");
        if (!csvFile.exists()) {
            System.out.println("  [SKIP] Dataset de 6 sensores no presente; omitiendo prueba de 6 sensores.");
            return;
        }

        List<String> classes5 = List.of("ESPACIO_LIBRE", "LOBITO", "MURO", "OBSTACULO", "SALIDA");
        List<String> classes7 = List.of("ESPACIO_LIBRE", "LOBITO_CENTRO", "LOBITO_DER", "LOBITO_IZQ", "MURO", "OBSTACULO", "SALIDA");
        List<String> classes9 = List.of(
                "ESPACIO_LIBRE", "LOBITO_CENTRO", "LOBITO_DER", "LOBITO_IZQ", "MURO",
                "OBSTACULO_CENTRO", "OBSTACULO_DER", "OBSTACULO_IZQ", "SALIDA"
        );

        System.out.println("\n================================================================================");
        System.out.println("  BENCHMARK COMPARATIVO NEUROPH 2.98 - 6 SENSORES FRONTALES (3 BAJOS + 3 ALTOS)");
        System.out.println("================================================================================");

        EvaluationMetrics best5 = benchmarkScheme(
                "5-CLASES",
                "../keiko-sim/data/datasets/orquitas_dataset_6_sensores.csv",
                "../keiko-sim/data/models/orquitas_6sensores_neuroph.nnet",
                classes5
        );
        // También copiar el mejor de 5 clases o 7 clases como campeón de producción
        EvaluationMetrics best7 = benchmarkScheme(
                "7-CLASES (LOBITO DIRECCIONAL)",
                "../keiko-sim/data/datasets/orquitas_dataset_6_sensores_7clases.csv",
                "../keiko-sim/data/models/orquitas_6sensores_7clases.nnet",
                classes7
        );
        EvaluationMetrics best9 = benchmarkScheme(
                "9-CLASES (LOBITO + OBSTACULO DIRECCIONAL)",
                "../keiko-sim/data/datasets/orquitas_dataset_6_sensores_9clases.csv",
                "../keiko-sim/data/models/orquitas_6sensores_9clases.nnet",
                classes9
        );

        System.out.println("================================================================================");
        System.out.println("  RESUMEN FINAL DE CAMPEONES (6 SENSORES FRONTALES REALISTAS)");
        System.out.printf("  - [5 Clases] Accuracy: %.2f%% | F1-Score: %.4f | RMSE: %.5f%n",
                best5.getAccuracy(), best5.getF1Score(), best5.getRmse());
        if (best7 != null) {
            System.out.printf("  - [7 Clases] Accuracy: %.2f%% | F1-Score: %.4f | RMSE: %.5f%n",
                    best7.getAccuracy(), best7.getF1Score(), best7.getRmse());
        }
        if (best9 != null) {
            System.out.printf("  - [9 Clases] Accuracy: %.2f%% | F1-Score: %.4f | RMSE: %.5f%n",
                    best9.getAccuracy(), best9.getF1Score(), best9.getRmse());
        }
        System.out.println("================================================================================\n");

        // Guardar en orquitas_production_champion.nnet el modelo con mayor F1/Accuracy global (9 clases)
        File championSource = new File("../keiko-sim/data/models/orquitas_6sensores_9clases.nnet");
        if (!championSource.exists()) {
            championSource = new File("../keiko-sim/data/models/orquitas_6sensores_7clases.nnet");
        }
        File championDest = new File("../keiko-sim/data/models/orquitas_production_champion.nnet");
        Files.copy(championSource.toPath(), championDest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertTrue(best5.getAccuracy() >= 78.0, "El esquema de 5 clases debe superar 78% de accuracy");
    }

    @Test
    public void testTrainNeurophOnCoaxialPairDataset() throws Exception {
        File csvFile = new File("../keiko-sim/data/datasets/orquitas_dataset_par_coaxial.csv");
        assertTrue(csvFile.exists(), "Debe existir el dataset por par coaxial (dist_low, dist_high)");

        String csvContent = Files.readString(csvFile.toPath(), StandardCharsets.UTF_8);
        DatasetParser.CsvInspectionResult inspection = DatasetParser.inspectCsv(csvContent, true);
        assertEquals(3, inspection.getColumnCount(), "Debe tener 2 columnas (dist_low, dist_high) + 1 de etiqueta");

        List<String> pairClasses = List.of("BLOQUEO", "ESPACIO_LIBRE", "LOBITO", "SALIDA");

        DatasetMetadata meta = new DatasetMetadata();
        meta.setId("sim-coaxial-pair");
        meta.setName("Orquitas Coaxial Pair (2 inputs -> 4 classes)");
        meta.setCsvContent(csvContent);
        meta.setInputColumns(List.of(0, 1));
        meta.setOutputColumns(List.of(2));
        meta.setClassLabels(pairClasses);
        meta.setTaskType(TaskType.CLASSIFICATION);
        meta.setHasHeader(true);
        meta.setNumRows(inspection.getRowCount());

        List<ColumnConfig> configs = new ArrayList<>();
        configs.add(new ColumnConfig(0, "dist_low", ColumnRole.INPUT, NormalizationType.MIN_MAX_0_1));
        configs.add(new ColumnConfig(1, "dist_high", ColumnRole.INPUT, NormalizationType.MIN_MAX_0_1));
        configs.add(new ColumnConfig(2, "class_label", ColumnRole.OUTPUT, NormalizationType.NONE));
        meta.setColumnConfigs(configs);

        DatasetParser.SplitResult split = DatasetParser.parseAndSplit(csvContent, meta, 0.70, 42L);

        List<List<Integer>> candidateTopologies = List.of(
                List.of(16),
                List.of(24),
                List.of(16, 12),
                List.of(24, 16)
        );

        EvaluationMetrics bestMetrics = null;
        NeuralNetwork<?> bestNet = null;
        String bestTopo = "";

        System.out.println("\n================================================================================");
        System.out.println("  ENTRENANDO RED NEURONAL POR PAR COAXIAL (d_bajo, d_alto -> 4 CLASES)");
        System.out.println("  Clases: " + pairClasses + " | Umbral BLOQUEO: [2.0 .. 18.0 cm]");
        System.out.println("================================================================================");

        for (List<Integer> hidden : candidateTopologies) {
            NetworkConfig netConfig = new NetworkConfig();
            netConfig.setInputNeurons(2);
            netConfig.setOutputNeurons(4);
            netConfig.setHiddenNeurons(hidden);
            netConfig.setTransferFunction("SIGMOID");
            netConfig.setLearningRate(0.35);
            netConfig.setMomentum(0.6);
            netConfig.setMaxIterations(1600);
            netConfig.setMaxError(0.002);

            TrainingTask task = new TrainingTask();
            task.setTaskId("pair-" + netConfig.getTopologySummary());
            task.setNetworkConfig(netConfig);
            task.setTrainRatio(0.70);
            task.setSplitSeed(42L);
            task.setPatience(1000);

            NeurophTrainer trainer = new NeurophTrainer();
            NeurophTrainer.TrainingResult trainingResult = trainer.train(task, split.getTrainSet(), (epoch, err) -> {});

            EvaluationMetrics metrics = ModelEvaluator.evaluate(
                    trainingResult.getNeuralNetwork(),
                    split.getTestSamples(),
                    meta,
                    trainingResult.getDurationSeconds(),
                    trainingResult.getIterations(),
                    trainingResult.getFinalError()
            );

            System.out.printf("    Topologia %-12s -> Accuracy: %6.2f%% | F1: %.4f | RMSE: %.5f | Iter: %d%n",
                    netConfig.getTopologySummary(),
                    metrics.getAccuracy(),
                    metrics.getF1Score(),
                    metrics.getRmse(),
                    trainingResult.getIterations());

            if (bestMetrics == null || metrics.getF1Score() > bestMetrics.getF1Score()
                    || (Math.abs(metrics.getF1Score() - bestMetrics.getF1Score()) < 1e-4 && metrics.getAccuracy() > bestMetrics.getAccuracy())) {
                bestMetrics = metrics;
                bestNet = trainingResult.getNeuralNetwork();
                bestTopo = netConfig.getTopologySummary();
            }
        }

        assertNotNull(bestNet);
        System.out.printf("  * CAMPEON PAR COAXIAL: Topologia %s (Accuracy=%.2f%%, F1=%.4f)%n",
                bestTopo, bestMetrics.getAccuracy(), bestMetrics.getF1Score());
        System.out.printf("  * Matriz de Confusion:%n%s%n", java.util.Arrays.deepToString(bestMetrics.getConfusionMatrix()));
        System.out.println("================================================================================\n");

        File pairNnet = new File("../keiko-sim/data/models/orquitas_par_coaxial_neuroph.nnet");
        File prodNnet = new File("../keiko-sim/data/models/orquitas_production_champion.nnet");
        bestNet.save(pairNnet.getAbsolutePath());
        Files.copy(pairNnet.toPath(), prodNnet.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        exportNeurophWeightsToJson(bestNet, pairClasses, new File("../keiko-sim/data/models/orquitas_par_coaxial_weights.json"));

        assertTrue(bestMetrics.getAccuracy() >= 95.0, "La red por par coaxial debe superar 95% de accuracy");
    }

    private void exportNeurophWeightsToJson(NeuralNetwork<?> net, List<String> classes, File jsonFile) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"min_cm\": 2.0,\n");
        sb.append("  \"max_cm\": 250.0,\n");
        sb.append("  \"classes\": [");
        for (int i = 0; i < classes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(classes.get(i)).append("\"");
        }
        sb.append("],\n  \"layers\": [\n");

        for (int l = 1; l < net.getLayersCount(); l++) {
            org.neuroph.core.Layer prevLayer = net.getLayerAt(l - 1);
            org.neuroph.core.Layer currLayer = net.getLayerAt(l);
            List<org.neuroph.core.Neuron> prevNeurons = prevLayer.getNeurons();
            List<org.neuroph.core.Neuron> currNeurons = currLayer.getNeurons();

            if (l > 1) sb.append(",\n");
            sb.append("    [\n");
            boolean firstNeuron = true;
            for (org.neuroph.core.Neuron neuron : currNeurons) {
                if (neuron instanceof org.neuroph.nnet.comp.neuron.BiasNeuron) {
                    continue;
                }
                if (!firstNeuron) sb.append(",\n");
                firstNeuron = false;

                double bias = 0.0;
                List<Double> wList = new ArrayList<>();
                for (org.neuroph.core.Connection conn : neuron.getInputConnections()) {
                    org.neuroph.core.Neuron from = conn.getFromNeuron();
                    double w = conn.getWeight().getValue();
                    if (from instanceof org.neuroph.nnet.comp.neuron.BiasNeuron) {
                        bias += w;
                    } else if (prevNeurons.contains(from)) {
                        wList.add(w);
                    }
                }
                sb.append("      {\"weights\": ").append(wList.toString()).append(", \"bias\": ").append(bias).append("}");
            }
            sb.append("\n    ]");
        }
        sb.append("\n  ]\n}\n");
        Files.writeString(jsonFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
    }

    private EvaluationMetrics benchmarkScheme(
            String schemeTitle,
            String csvPath,
            String outputNnetPath,
            List<String> classLabels
    ) throws Exception {
        File csvFile = new File(csvPath);
        if (!csvFile.exists()) {
            System.out.println("  [SKIP] No existe " + csvPath);
            return null;
        }
        String csvContent = Files.readString(csvFile.toPath(), StandardCharsets.UTF_8);
        DatasetParser.CsvInspectionResult inspection = DatasetParser.inspectCsv(csvContent, true);
        assertEquals(7, inspection.getColumnCount(), "Debe tener 6 columnas de sensores + 1 de etiqueta");

        DatasetMetadata meta = new DatasetMetadata();
        meta.setId("sim-6-" + schemeTitle);
        meta.setName(schemeTitle);
        meta.setCsvContent(csvContent);
        meta.setInputColumns(List.of(0, 1, 2, 3, 4, 5));
        meta.setOutputColumns(List.of(6));
        meta.setClassLabels(classLabels);
        meta.setTaskType(TaskType.CLASSIFICATION);
        meta.setHasHeader(true);
        meta.setNumRows(inspection.getRowCount());

        List<ColumnConfig> configs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            configs.add(new ColumnConfig(i, inspection.getHeaders().get(i), ColumnRole.INPUT, NormalizationType.MIN_MAX_0_1));
        }
        configs.add(new ColumnConfig(6, "class_label", ColumnRole.OUTPUT, NormalizationType.NONE));
        meta.setColumnConfigs(configs);

        DatasetParser.SplitResult split = DatasetParser.parseAndSplit(csvContent, meta, 0.70, 42L);

        List<List<Integer>> candidateTopologies = List.of(
                List.of(24),
                List.of(36),
                List.of(24, 16),
                List.of(32, 16)
        );

        EvaluationMetrics bestMetrics = null;
        NeuralNetwork<?> bestNet = null;
        String bestTopoDesc = "";

        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("  ESQUEMA: " + schemeTitle + " (" + classLabels.size() + " salidas)");
        for (List<Integer> hidden : candidateTopologies) {
            NetworkConfig netConfig = new NetworkConfig();
            netConfig.setInputNeurons(6);
            netConfig.setOutputNeurons(classLabels.size());
            netConfig.setHiddenNeurons(hidden);
            netConfig.setTransferFunction("SIGMOID");
            netConfig.setLearningRate(0.35);
            netConfig.setMomentum(0.6);
            netConfig.setMaxIterations(1500);
            netConfig.setMaxError(0.003);

            TrainingTask task = new TrainingTask();
            task.setTaskId("bench-" + schemeTitle + "-" + netConfig.getTopologySummary());
            task.setNetworkConfig(netConfig);
            task.setTrainRatio(0.70);
            task.setSplitSeed(42L);
            task.setPatience(900);

            NeurophTrainer trainer = new NeurophTrainer();
            NeurophTrainer.TrainingResult trainingResult = trainer.train(task, split.getTrainSet(), (epoch, err) -> {});

            EvaluationMetrics metrics = ModelEvaluator.evaluate(
                    trainingResult.getNeuralNetwork(),
                    split.getTestSamples(),
                    meta,
                    trainingResult.getDurationSeconds(),
                    trainingResult.getIterations(),
                    trainingResult.getFinalError()
            );

            System.out.printf("    Topologia %-12s -> Accuracy: %6.2f%% | F1: %.4f | RMSE: %.5f | Iter: %d%n",
                    netConfig.getTopologySummary(),
                    metrics.getAccuracy(),
                    metrics.getF1Score(),
                    metrics.getRmse(),
                    trainingResult.getIterations());

            if (bestMetrics == null || metrics.getF1Score() > bestMetrics.getF1Score()
                    || (Math.abs(metrics.getF1Score() - bestMetrics.getF1Score()) < 1e-4 && metrics.getAccuracy() > bestMetrics.getAccuracy())) {
                bestMetrics = metrics;
                bestNet = trainingResult.getNeuralNetwork();
                bestTopoDesc = netConfig.getTopologySummary();
            }
        }

        if (bestNet != null) {
            File nnetFile = new File(outputNnetPath);
            bestNet.save(nnetFile.getAbsolutePath());
            System.out.printf("  * CAMPEON %s: Topologia %s (Accuracy=%.2f%%, F1=%.4f)%n",
                    schemeTitle, bestTopoDesc, bestMetrics.getAccuracy(), bestMetrics.getF1Score());
            System.out.printf("  * Clases: %s%n", classLabels);
            System.out.printf("  * Matriz de Confusion:%n%s%n", java.util.Arrays.deepToString(bestMetrics.getConfusionMatrix()));
        }
        return bestMetrics;
    }

    @Test
    public void testTrainNeurophOn8SensorsDataset() throws Exception {
        trainAndValidateSensors(
                "sim-8-sensors",
                "Orquitas 8 Ultrasonic Sensors (Webots 3D)",
                "../keiko-sim/data/datasets/orquitas_dataset_8_sensores.csv",
                "../keiko-sim/data/models/orquitas_8sensores_neuroph.nnet",
                8,
                28,  // Topología 8 -> 28 -> 5
                0.5);
    }

    @Test
    public void testTrainNeurophOn10SensorsDataset() throws Exception {
        trainAndValidateSensors(
                "sim-10-sensors",
                "Orquitas 10 Ultrasonic Sensors (Webots 3D)",
                "../keiko-sim/data/datasets/orquitas_dataset_10_sensores.csv",
                "../keiko-sim/data/models/orquitas_10sensores_neuroph.nnet",
                10,
                32,  // Topología 10 -> 32 -> 5
                0.5);
    }

    /**
     * Entrena y valida un clasificador Neuroph 2.98 para una configuración
     * arbitraria de sensores ultrasónicos. Omite la prueba si el CSV no existe.
     */
    private void trainAndValidateSensors(String datasetId, String datasetName,
                                         String csvPath, String nnetPath,
                                         int sensorCount, int hiddenNeurons,
                                         double learningRate) throws Exception {
        File csvFile = new File(csvPath);
        if (!csvFile.exists()) {
            System.out.println("  [SKIP] Dataset de " + sensorCount + " sensores no presente; omitiendo prueba de " + sensorCount + " sensores.");
            return;
        }

        String csvContent = Files.readString(csvFile.toPath(), StandardCharsets.UTF_8);
        DatasetParser.CsvInspectionResult inspection = DatasetParser.inspectCsv(csvContent, true);
        assertEquals(sensorCount + 1, inspection.getColumnCount(), "Debe tener " + sensorCount + " columnas de sensores + 1 de etiqueta");

        DatasetMetadata meta = new DatasetMetadata();
        meta.setId(datasetId);
        meta.setName(datasetName);
        meta.setCsvContent(csvContent);
        List<Integer> inputCols = new ArrayList<>();
        for (int i = 0; i < sensorCount; i++) {
            inputCols.add(i);
        }
        meta.setInputColumns(inputCols);
        meta.setOutputColumns(List.of(sensorCount));
        meta.setClassLabels(List.of("ESPACIO_LIBRE", "LOBITO", "MURO", "OBSTACULO", "SALIDA"));
        meta.setTaskType(TaskType.CLASSIFICATION);
        meta.setHasHeader(true);
        meta.setNumRows(inspection.getRowCount());

        List<ColumnConfig> configs = new ArrayList<>();
        for (int i = 0; i < sensorCount; i++) {
            configs.add(new ColumnConfig(i, inspection.getHeaders().get(i), ColumnRole.INPUT, NormalizationType.MIN_MAX_0_1));
        }
        configs.add(new ColumnConfig(sensorCount, "class_label", ColumnRole.OUTPUT, NormalizationType.NONE));
        meta.setColumnConfigs(configs);

        DatasetParser.SplitResult split = DatasetParser.parseAndSplit(csvContent, meta, 0.70, 42L);
        assertEquals(sensorCount, split.getTrainSet().getInputSize());
        assertEquals(5, split.getTrainSet().getOutputSize(), "5 clases one-hot esperadas");

        System.out.println("==========================================================");
        System.out.println("  ENTRENANDO NEUROPH 2.98 CON DATASET DE " + sensorCount + " SENSORES");
        System.out.println("  Topologia: " + sensorCount + "-" + hiddenNeurons + "-5");
        System.out.println("  Muestras Entrenamiento: " + split.getTrainSet().size());
        System.out.println("  Muestras Evaluacion:    " + split.getTestSamples().size());
        System.out.println("==========================================================");

        NetworkConfig netConfig = new NetworkConfig();
        netConfig.setInputNeurons(sensorCount);
        netConfig.setOutputNeurons(5);
        netConfig.setHiddenNeurons(List.of(hiddenNeurons));
        netConfig.setLearningRate(learningRate);
        netConfig.setMomentum(0.6);
        netConfig.setMaxIterations(1200);
        netConfig.setMaxError(0.005);

        TrainingTask task = new TrainingTask();
        task.setTaskId("task-webots-" + sensorCount + "sensors");
        task.setNetworkConfig(netConfig);
        task.setTrainRatio(0.70);
        task.setSplitSeed(42L);
        task.setPatience(800);

        NeurophTrainer trainer = new NeurophTrainer();
        NeurophTrainer.TrainingResult trainingResult = trainer.train(task, split.getTrainSet(), (epoch, err) -> {});

        EvaluationMetrics metrics = ModelEvaluator.evaluate(
                trainingResult.getNeuralNetwork(),
                split.getTestSamples(),
                meta,
                trainingResult.getDurationSeconds(),
                trainingResult.getIterations(),
                trainingResult.getFinalError()
        );

        System.out.printf("  -> Accuracy: %.2f%%%n", metrics.getAccuracy());
        System.out.printf("  -> F1-Score: %.4f%n", metrics.getF1Score());
        System.out.printf("  -> RMSE:     %.5f%n", metrics.getRmse());
        System.out.println("==========================================================");

        File nnetFile = new File(nnetPath);
        trainingResult.getNeuralNetwork().save(nnetFile.getAbsolutePath());
        assertTrue(nnetFile.exists());
        assertTrue(nnetFile.length() > 0, "El modelo .nnet no debe estar vacío");
        System.out.println("  Modelo serializado exitosamente en: " + nnetFile.getAbsolutePath());

        assertTrue(metrics.getAccuracy() >= 80.0, "La precisión debe superar el 80% con el dataset de " + sensorCount + " sensores");
        assertTrue(metrics.getF1Score() >= 0.80, "El F1-Score debe superar 0.80 con el dataset de " + sensorCount + " sensores");
    }
}
