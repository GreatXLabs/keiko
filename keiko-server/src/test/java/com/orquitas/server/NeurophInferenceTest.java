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
        // Bloqueo cerrado (<= 10 cm): pared u obstáculo en el radio mínimo de rotación
        assertEquals("BLOQUEO", classifier.clasificarPar(8.0, 8.5));
        assertEquals("BLOQUEO", classifier.clasificarPar(9.5, 10.0));

        // A partir de 11.5 cm ya es ESPACIO_LIBRE para permitir navegar pegado a las paredes
        assertEquals("ESPACIO_LIBRE", classifier.clasificarPar(15.0, 15.5));
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
        // Caso planteado: el par izquierdo ve SALIDA (> 210 cm), pero centro y derecha ven pared cerca (<= 10 cm)
        VectorSensores vector = new VectorSensores(215.0, 9.0, 8.5, 218.0, 9.5, 9.0);
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
        assertTrue(cmd.getVLeft() < 0.0 && cmd.getVRight() < 0.0,
                "Con [SALIDA, BLOQUEO, BLOQUEO] en ESCAPE debe retroceder en arco para alinear la salida sin barrer la cola");
    }

    @Test
    public void testFullFrontBlockageTriggersCommittedReverseAndTurn() throws Exception {
        // Pared frontal tapando los 3 sectores a <= 10 cm: [BLOQUEO, BLOQUEO, BLOQUEO]
        VectorSensores muroFrontal = new VectorSensores(8.0, 7.5, 8.2, 8.5, 8.0, 8.8);
        String[] pares = classifier.clasificarPares(muroFrontal);
        assertArrayEquals(new String[]{"BLOQUEO", "BLOQUEO", "BLOQUEO"}, pares);

        File tempEstomago = File.createTempFile("estomago_rev_test_", ".txt");
        tempEstomago.deleteOnExit();
        GestorEstomago gestor = new GestorEstomago(tempEstomago);
        PlanificadorNavegacion planner = new PlanificadorNavegacion(gestor);

        // Pasos 1..10: retroceso en arco para separar la trompa y la cola del muro
        for (int i = 0; i < 10; i++) {
            ComandoRobot c = planner.evaluarPares(muroFrontal, pares);
            assertTrue(c.getVLeft() < 0.0 && c.getVRight() < 0.0,
                    "Debe retroceder en el paso " + i + " para despegarse del muro frontal");
        }

        // Paso 11: una vez completado el retroceso, ejecuta el giro comprometido
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
