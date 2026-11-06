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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Entrenamiento, evaluación y exportación de las 2 redes neuronales oficiales en Neuroph 2.98:
 * 1. modelo_ultrasonico.nnet:
 *    - 1 entrada (distancia ultrasonido normalizada [2.0, 250.0] cm).
 *    - 3 salidas one-hot: {BLOQUEO, ESPACIO_LIBRE, SALIDA}.
 *    - Se ejecuta 3 veces por ciclo (us_left, us_center, us_right).
 * 
 * 2. modelo_canicas.nnet:
 *    - 6 entradas: [d_us_izq, d_us_cen, d_us_der, d_laser_izq, d_laser_der, angulo_servo].
 *    - 4 salidas one-hot: {LOBITO_CENTRO, LOBITO_DER, LOBITO_IZQ, NINGUNA}.
 *    - Se ejecuta 1 vez por ciclo para clasificar presencia y sector de canicas.
 */
public class TrainTwoNetworksTest {

    @Test
    public void trainModeloUltrasonico() throws Exception {
        File csvFile = new File("../keiko-sim/data/datasets/dataset_ultrasonico.csv");
        assertTrue(csvFile.exists(), "El dataset ultrasonico debe existir: " + csvFile.getAbsolutePath());

        String csvContent = Files.readString(csvFile.toPath(), StandardCharsets.UTF_8);
        assertNotNull(csvContent);

        DatasetParser.CsvInspectionResult inspection = DatasetParser.inspectCsv(csvContent, true);
        assertEquals(2, inspection.getColumnCount(), "Debe tener 1 columna de entrada + 1 de salida");

        DatasetMetadata meta = new DatasetMetadata();
        meta.setId("modelo-ultrasonico");
        meta.setName("Modelo Ultrasonico Sectorial (HC-SR04)");
        meta.setCsvContent(csvContent);
        meta.setInputColumns(List.of(0)); // dist_us
        meta.setOutputColumns(List.of(1)); // class_label
        List<String> classes = List.of("BLOQUEO", "ESPACIO_LIBRE", "SALIDA");
        meta.setClassLabels(classes);
        meta.setTaskType(TaskType.CLASSIFICATION);
        meta.setHasHeader(true);
        meta.setNumRows(inspection.getRowCount());

        List<ColumnConfig> configs = new ArrayList<>();
        configs.add(new ColumnConfig(0, "dist_us", ColumnRole.INPUT, NormalizationType.MIN_MAX_0_1));
        configs.add(new ColumnConfig(1, "class_label", ColumnRole.OUTPUT, NormalizationType.NONE));
        meta.setColumnConfigs(configs);

        DatasetParser.SplitResult split = DatasetParser.parseAndSplit(csvContent, meta, 0.70, 42L);
        assertNotNull(split.getTrainSet());
        assertEquals(1, split.getTrainSet().getInputSize());
        assertEquals(3, split.getTrainSet().getOutputSize());

        System.out.println("==========================================================");
        System.out.println("  ENTRENANDO RED 1: modelo_ultrasonico.nnet (Neuroph 2.98)");
        System.out.println("  Entradas: 1 (dist_us normalizada [2.0, 250.0] cm)");
        System.out.println("  Clases:   3 " + classes);
        System.out.println("  Muestras Train: " + split.getTrainSet().size() + " | Test: " + split.getTestSamples().size());
        System.out.println("==========================================================");

        // Topología 1 -> 8 -> 3
        NetworkConfig netConfig = new NetworkConfig();
        netConfig.setInputNeurons(1);
        netConfig.setOutputNeurons(3);
        netConfig.setHiddenNeurons(List.of(8));
        netConfig.setLearningRate(0.5);
        netConfig.setMomentum(0.6);
        netConfig.setMaxIterations(1000);
        netConfig.setMaxError(0.003);

        TrainingTask task = new TrainingTask();
        task.setTaskId("train-ultrasonic-model");
        task.setNetworkConfig(netConfig);
        task.setTrainRatio(0.70);
        task.setSplitSeed(42L);
        task.setPatience(500);

        NeurophTrainer trainer = new NeurophTrainer();
        long startMs = System.currentTimeMillis();
        NeurophTrainer.TrainingResult trainingResult = trainer.train(task, split.getTrainSet(), (epoch, err) -> {
            if (epoch % 50 == 0 || epoch == 1) {
                System.out.printf("  [Epoca %3d] Error cuadrático: %.6f%n", epoch, err);
            }
        });
        long durationMs = System.currentTimeMillis() - startMs;

        NeuralNetwork<?> nnet = trainingResult.getNeuralNetwork();
        assertNotNull(nnet);

        EvaluationMetrics metrics = ModelEvaluator.evaluate(
                nnet,
                split.getTestSamples(),
                meta,
                trainingResult.getDurationSeconds(),
                trainingResult.getIterations(),
                trainingResult.getFinalError()
        );

        System.out.println("----------------------------------------------------------");
        System.out.printf("  RESULTADOS EVALUACION (modelo_ultrasonico):%n");
        System.out.printf("  -> Iteraciones: %d en %d ms%n", trainingResult.getIterations(), durationMs);
        System.out.printf("  -> Accuracy:    %.2f%%%n", metrics.getAccuracy());
        System.out.printf("  -> F1-Score:    %.4f%n", metrics.getF1Score());
        System.out.printf("  -> RMSE:        %.5f%n", metrics.getRmse());
        System.out.println("  -> Matriz de Confusión:");
        int[][] cm = metrics.getConfusionMatrix();
        if (cm != null) {
            for (int r = 0; r < cm.length; r++) {
                System.out.printf("     %14s: %s%n", classes.get(r), Arrays.toString(cm[r]));
            }
        }
        System.out.println("----------------------------------------------------------");

        assertTrue(metrics.getAccuracy() >= 95.0, "Accuracy de modelo_ultrasonico debe ser >= 95%");

        // Guardar modelo en keiko-sim/data/models/ y en keiko-server/src/main/resources/ si aplica
        File modelFile = new File("../keiko-sim/data/models/modelo_ultrasonico.nnet");
        nnet.save(modelFile.getAbsolutePath());
        assertTrue(modelFile.exists());
        System.out.println("  [GUARDADO] " + modelFile.getAbsolutePath());
    }

    @Test
    public void trainModeloCanicas() throws Exception {
        File csvFile = new File("../keiko-sim/data/datasets/dataset_canicas.csv");
        assertTrue(csvFile.exists(), "El dataset de canicas debe existir: " + csvFile.getAbsolutePath());

        String csvContent = Files.readString(csvFile.toPath(), StandardCharsets.UTF_8);
        assertNotNull(csvContent);

        DatasetParser.CsvInspectionResult inspection = DatasetParser.inspectCsv(csvContent, true);
        assertEquals(7, inspection.getColumnCount(), "Debe tener 6 columnas de entrada + 1 de salida");

        DatasetMetadata meta = new DatasetMetadata();
        meta.setId("modelo-canicas");
        meta.setName("Modelo Deteccion Canicas Multimodal (Lasers + Servos + US)");
        meta.setCsvContent(csvContent);
        meta.setInputColumns(List.of(0, 1, 2, 3, 4, 5));
        meta.setOutputColumns(List.of(6));
        // Orden alfabetico exacto para one-hot encoding
        List<String> classes = List.of("LOBITO_CENTRO", "LOBITO_DER", "LOBITO_IZQ", "NINGUNA");
        meta.setClassLabels(classes);
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
        assertNotNull(split.getTrainSet());
        assertEquals(6, split.getTrainSet().getInputSize());
        assertEquals(4, split.getTrainSet().getOutputSize());

        System.out.println("==========================================================");
        System.out.println("  ENTRENANDO RED 2: modelo_canicas.nnet (Neuroph 2.98)");
        System.out.println("  Entradas: 6 [us_izq, us_cen, us_der, laser_izq, laser_der, servo_deg]");
        System.out.println("  Clases:   4 " + classes);
        System.out.println("  Muestras Train: " + split.getTrainSet().size() + " | Test: " + split.getTestSamples().size());
        System.out.println("==========================================================");

        // Topología 6 -> 32 -> 16 -> 4
        NetworkConfig netConfig = new NetworkConfig();
        netConfig.setInputNeurons(6);
        netConfig.setOutputNeurons(4);
        netConfig.setHiddenNeurons(List.of(32, 16));
        netConfig.setLearningRate(0.4);
        netConfig.setMomentum(0.6);
        netConfig.setMaxIterations(1500);
        netConfig.setMaxError(0.003);

        TrainingTask task = new TrainingTask();
        task.setTaskId("train-marble-model");
        task.setNetworkConfig(netConfig);
        task.setTrainRatio(0.70);
        task.setSplitSeed(42L);
        task.setPatience(1200);

        NeurophTrainer trainer = new NeurophTrainer();
        long startMs = System.currentTimeMillis();
        NeurophTrainer.TrainingResult trainingResult = trainer.train(task, split.getTrainSet(), (epoch, err) -> {
            if (epoch % 50 == 0 || epoch == 1) {
                System.out.printf("  [Epoca %3d] Error cuadrático: %.6f%n", epoch, err);
            }
        });
        long durationMs = System.currentTimeMillis() - startMs;

        NeuralNetwork<?> nnet = trainingResult.getNeuralNetwork();
        assertNotNull(nnet);

        EvaluationMetrics metrics = ModelEvaluator.evaluate(
                nnet,
                split.getTestSamples(),
                meta,
                trainingResult.getDurationSeconds(),
                trainingResult.getIterations(),
                trainingResult.getFinalError()
        );

        System.out.println("----------------------------------------------------------");
        System.out.printf("  RESULTADOS EVALUACION (modelo_canicas):%n");
        System.out.printf("  -> Iteraciones: %d en %d ms%n", trainingResult.getIterations(), durationMs);
        System.out.printf("  -> Accuracy:    %.2f%%%n", metrics.getAccuracy());
        System.out.printf("  -> F1-Score:    %.4f%n", metrics.getF1Score());
        System.out.printf("  -> RMSE:        %.5f%n", metrics.getRmse());
        System.out.println("  -> Matriz de Confusión:");
        int[][] cm = metrics.getConfusionMatrix();
        if (cm != null) {
            for (int r = 0; r < cm.length; r++) {
                System.out.printf("     %14s: %s%n", classes.get(r), Arrays.toString(cm[r]));
            }
        }
        System.out.println("----------------------------------------------------------");

        assertTrue(metrics.getAccuracy() >= 95.0, "Accuracy de modelo_canicas debe ser >= 95%");

        File modelFile = new File("../keiko-sim/data/models/modelo_canicas.nnet");
        nnet.save(modelFile.getAbsolutePath());
        assertTrue(modelFile.exists());
        System.out.println("  [GUARDADO] " + modelFile.getAbsolutePath());
    }
}
