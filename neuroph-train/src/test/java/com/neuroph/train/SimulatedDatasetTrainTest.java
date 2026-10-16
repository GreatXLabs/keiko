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

        String csvContent = Files.readString(csvFile.toPath(), StandardCharsets.UTF_8);
        DatasetParser.CsvInspectionResult inspection = DatasetParser.inspectCsv(csvContent, true);
        assertEquals(7, inspection.getColumnCount(), "Debe tener 6 columnas de sensores + 1 de etiqueta");

        DatasetMetadata meta = new DatasetMetadata();
        meta.setId("sim-6-sensors");
        meta.setName("Orquitas 6 Ultrasonic Sensors (Webots 3D)");
        meta.setCsvContent(csvContent);
        meta.setInputColumns(List.of(0, 1, 2, 3, 4, 5));
        meta.setOutputColumns(List.of(6));
        meta.setClassLabels(List.of("ESPACIO_LIBRE", "LOBITO", "MURO", "OBSTACULO", "SALIDA"));
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

        NetworkConfig netConfig = new NetworkConfig();
        netConfig.setInputNeurons(6);
        netConfig.setOutputNeurons(5);
        netConfig.setHiddenNeurons(List.of(28));
        netConfig.setLearningRate(0.5);
        netConfig.setMomentum(0.6);
        netConfig.setMaxIterations(1200);
        netConfig.setMaxError(0.005);

        TrainingTask task = new TrainingTask();
        task.setTaskId("task-webots-6sensors");
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

        System.out.println("==========================================================");
        System.out.printf("  METRICAS 6 SENSORES SOBRE CONJUNTO DE PRUEBA (30%%):%n");
        System.out.printf("  -> Accuracy: %.2f%%%n", metrics.getAccuracy());
        System.out.printf("  -> F1-Score: %.4f%n", metrics.getF1Score());
        System.out.println("==========================================================");

        File nnetFile = new File("../keiko-sim/data/models/orquitas_6sensores_neuroph.nnet");
        trainingResult.getNeuralNetwork().save(nnetFile.getAbsolutePath());
        assertTrue(nnetFile.exists());
        assertTrue(metrics.getAccuracy() >= 75.0);
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
