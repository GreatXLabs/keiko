package com.orquitas.server;

import com.orquitas.server.ai.NeurophClassifier;
import com.orquitas.server.config.ServerConfig;
import com.orquitas.server.model.ClassificationResult;
import com.orquitas.server.model.EnvironmentClass;
import com.orquitas.server.model.SensorVector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de inferencia neuronal con Neuroph 2.98 sobre la red entrenada.
 */
public class NeurophInferenceTest {

    private static NeurophClassifier classifier;

    @BeforeAll
    public static void setUp() throws Exception {
        File modelFile = new ServerConfig().resolveModelFile();
        assertTrue(modelFile.exists(), "El modelo .nnet debe existir en " + modelFile.getAbsolutePath());
        classifier = new NeurophClassifier(modelFile);
    }

    @Test
    public void testInferenceEspacioLibre() {
        // Lecturas de espacio abierto (> 150 cm en todos los sensores)
        SensorVector vector = new SensorVector(200.0, 210.0, 220.0, 215.0, System.currentTimeMillis());
        ClassificationResult result = classifier.classify(vector);

        assertNotNull(result);
        assertEquals(EnvironmentClass.ESPACIO_LIBRE, result.detectedClass(),
                "Debe clasificar como ESPACIO_LIBRE cuando todos los sensores reportan distancias lejanas");
        assertTrue(result.confidence() > 0.50, "La confianza debe ser > 50%");
    }

    @Test
    public void testInferenceLobito() {
        // Lecturas típicas de lobito: bajo detecta canica (< 35 cm), alto ve agua abierta (> 120 cm)
        SensorVector vector = new SensorVector(25.0, 28.0, 150.0, 160.0, System.currentTimeMillis());
        ClassificationResult result = classifier.classify(vector);

        assertNotNull(result);
        assertEquals(EnvironmentClass.LOBITO, result.detectedClass(),
                "Debe detectar LOBITO cuando el plano inferior detecta objeto pequeño y el plano superior está despejado");
        assertTrue(result.confidence() > 0.50);
    }

    @Test
    public void testInferenceMuro() {
        // Lecturas de muro frontal: tanto plano bajo como alto detectan obstáculo cercano a similar distancia
        SensorVector vector = new SensorVector(15.0, 15.0, 16.0, 15.0, System.currentTimeMillis());
        ClassificationResult result = classifier.classify(vector);

        assertNotNull(result);
        assertTrue(result.detectedClass() == EnvironmentClass.MURO || result.detectedClass() == EnvironmentClass.OBSTACULO,
                "Debe clasificar como MURO u OBSTACULO cuando ambos planos están obstruidos");
    }

    @Test
    public void testDefensiveHandlingOfInvalidVector() {
        SensorVector invalid = new SensorVector(-5.0, Double.NaN, 500.0, 20.0, 0);
        ClassificationResult result = classifier.classify(invalid);
        assertNotNull(result);
        assertEquals(EnvironmentClass.ESPACIO_LIBRE, result.detectedClass());
    }
}
