package com.orquitas.server;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de inferencia neuronal con Neuroph 2.98 sobre la red por par coaxial (d_bajo, d_alto -> 4 clases)
 * aplicada a los 3 pares frontales [IZQ, CENTRO, DER] y su integracion con el algoritmo de PlanificadorNavegacion.
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
    public void testCoaxialPairClassificationTightBlockageThreshold() {
        // Bloqueo cerrado (<= 18 cm): pared u obstáculo en el radio mínimo de rotación
        assertEquals("BLOQUEO", classifier.clasificarPar(10.0, 10.5));
        assertEquals("BLOQUEO", classifier.clasificarPar(16.5, 17.0));

        // A 26 cm (> 18 cm) ya es ESPACIO_LIBRE para permitir acercarse a las paredes y escanear
        assertEquals("ESPACIO_LIBRE", classifier.clasificarPar(26.0, 26.5));
        assertEquals("ESPACIO_LIBRE", classifier.clasificarPar(95.0, 96.0));

        // Lobito: sensor bajo corto (<= 32 cm) y sensor alto despejado (incluso con pared a 32 cm detrás)
        assertEquals("LOBITO", classifier.clasificarPar(14.0, 120.0));
        assertEquals("LOBITO", classifier.clasificarPar(12.0, 32.0));

        // Salida: ambos sensores atravesando el hueco de 30 cm (> 185 cm)
        assertEquals("SALIDA", classifier.clasificarPar(220.0, 225.0));
    }

    @Test
    public void testMixed3PairPerceptionAndPlannerEscapeCase() throws Exception {
        // Caso planteado: el par izquierdo ve SALIDA (> 210 cm), pero centro y derecha ven pared cerca (12 cm)
        VectorSensores vector = new VectorSensores(215.0, 12.0, 11.5, 218.0, 12.5, 12.0);
        String[] pares = classifier.clasificarPares(vector);

        assertArrayEquals(new String[]{"SALIDA", "BLOQUEO", "BLOQUEO"}, pares,
                "Cada par debe clasificar independientemente su sector: [SALIDA, BLOQUEO, BLOQUEO]");

        File tempEstomago = File.createTempFile("estomago_planner_test_", ".txt");
        tempEstomago.deleteOnExit();
        GestorEstomago gestor = new GestorEstomago(tempEstomago);
        PlanificadorNavegacion planner = new PlanificadorNavegacion(gestor);
        planner.activarModoEscape();

        ComandoRobot cmd = planner.evaluarPares(vector, pares);
        assertEquals(EstadoRobot.ESCAPE, cmd.getEstado());
        assertTrue(cmd.getVLeft() < 0.0 && cmd.getVRight() > 0.0,
                "Con [SALIDA, BLOQUEO, BLOQUEO] en ESCAPE debe rotar en el lugar hacia la izquierda sin avanzar contra la pared");
    }

    @Test
    public void testFullFrontBlockageTriggersCommittedReverseAndTurn() throws Exception {
        // Pared frontal tapando los 3 sectores a <= 18 cm: [BLOQUEO, BLOQUEO, BLOQUEO]
        VectorSensores muroFrontal = new VectorSensores(11.0, 10.0, 11.5, 11.2, 10.5, 11.8);
        String[] pares = classifier.clasificarPares(muroFrontal);
        assertArrayEquals(new String[]{"BLOQUEO", "BLOQUEO", "BLOQUEO"}, pares);

        File tempEstomago = File.createTempFile("estomago_rev_test_", ".txt");
        tempEstomago.deleteOnExit();
        GestorEstomago gestor = new GestorEstomago(tempEstomago);
        PlanificadorNavegacion planner = new PlanificadorNavegacion(gestor);

        // Paso 1..7: retroceso comprometido para despegar la trompa del muro
        for (int i = 0; i < 7; i++) {
            ComandoRobot c = planner.evaluarPares(muroFrontal, pares);
            assertTrue(c.getVLeft() < 0.0 && c.getVRight() < 0.0,
                    "Debe retroceder en el paso " + i + " para despegarse del muro frontal");
        }

        // Paso 8: una vez completado el retroceso, ejecuta el giro comprometido
        VectorSensores despejado = new VectorSensores(35.0, 35.0, 35.0, 35.0, 35.0, 35.0);
        String[] paresLibres = classifier.clasificarPares(despejado);
        ComandoRobot giro = planner.evaluarPares(despejado, paresLibres);
        assertTrue(giro.getVLeft() * giro.getVRight() < 0.0,
                "Tras retroceder debe girar sobre su eje para no volver a encarar la misma pared");
    }

    @Test
    public void testInferenceLobito6Sensores() {
        VectorSensores vector = new VectorSensores(120.0, 16.0, 118.0, 121.0, 122.0, 119.0);
        String[] pares = classifier.clasificarPares(vector);
        assertArrayEquals(new String[]{"ESPACIO_LIBRE", "LOBITO", "ESPACIO_LIBRE"}, pares);
        assertTrue(classifier.clasificar(vector).startsWith("LOBITO"));
    }

    @Test
    public void testDefensiveHandlingOfInvalidVector() {
        VectorSensores invalid = new VectorSensores(-5.0, Double.NaN, 500.0, 20.0, 0);
        String detected = classifier.clasificar(invalid);
        assertNotNull(detected);
        assertEquals("ESPACIO_LIBRE", detected);
    }
}
