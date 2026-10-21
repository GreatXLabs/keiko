package com.orquitas.server;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de inferencia neuronal con Neuroph 2.98 sobre la red entrenada de 6 sensores frontales.
 */
public class NeurophInferenceTest {

    private static ClasificadorNeuroph classifier;

    @BeforeAll
    public static void setUp() {
        File modelFile = new ConfiguracionServidor().resolverArchivoModelo();
        assertTrue(modelFile.exists(), "El modelo .nnet debe existir en " + modelFile.getAbsolutePath());
        classifier = new ClasificadorNeuroph(modelFile);
        assertTrue(classifier.isCargado(), "La red Neuroph 2.98 debe estar cargada en memoria");
    }

    @Test
    public void testInferenceEspacioLibre6Sensores() {
        // Lecturas de espacio abierto dentro de la bahía (~90-135 cm en los 6 sensores frontales)
        VectorSensores vector = new VectorSensores(110.0, 118.0, 112.0, 111.0, 119.0, 113.0);
        String detected = classifier.clasificar(vector);

        assertNotNull(detected);
        assertEquals("ESPACIO_LIBRE", detected,
                "Debe clasificar como ESPACIO_LIBRE cuando los 6 sensores reportan agua interior despejada");
        assertTrue(classifier.getUltimaConfianza() > 0.50, "La confianza debe ser > 50%");
    }

    @Test
    public void testInferenceSalida6Sensores() {
        // Lecturas mirando por el estrecho de salida de 30 cm (> 200 cm hacia el exterior)
        VectorSensores vector = new VectorSensores(85.0, 242.0, 238.0, 86.0, 244.0, 239.0);
        String detected = classifier.clasificar(vector);

        assertNotNull(detected);
        assertEquals("SALIDA", detected,
                "Debe clasificar como SALIDA cuando un sector frontal ve > 200 cm hacia afuera de la bahía");
    }

    @Test
    public void testInferenceLobito6Sensores() {
        // Lecturas típicas de lobito en sector central: bajo centro detecta canica (~16 cm), alto centro ve lejos (~125 cm)
        VectorSensores vector = new VectorSensores(120.0, 16.0, 118.0, 121.0, 122.0, 119.0);
        String detected = classifier.clasificar(vector);

        assertNotNull(detected);
        assertTrue(detected.startsWith("LOBITO"),
                "Debe detectar LOBITO (o LOBITO_CENTRO) cuando el plano bajo detecta canica y el alto está despejado, obtuvo: " + detected);
        assertTrue(classifier.getUltimaConfianza() > 0.50);
    }

    @Test
    public void testInferenceMuro6Sensores() {
        // Lecturas de muro frontal continuo: tanto plano bajo como alto bloqueados en múltiples sectores
        VectorSensores vector = new VectorSensores(22.0, 20.0, 23.0, 22.5, 20.2, 23.1);
        String detected = classifier.clasificar(vector);

        assertNotNull(detected);
        assertTrue(detected.equals("MURO") || detected.startsWith("OBSTACULO"),
                "Debe clasificar como MURO u OBSTACULO cuando ambos planos están obstruidos, obtuvo: " + detected);
    }

    @Test
    public void testDefensiveHandlingOfInvalidVector() {
        VectorSensores invalid = new VectorSensores(-5.0, Double.NaN, 500.0, 20.0, 0);
        String detected = classifier.clasificar(invalid);
        assertNotNull(detected);
        assertEquals("ESPACIO_LIBRE", detected);
    }
}
