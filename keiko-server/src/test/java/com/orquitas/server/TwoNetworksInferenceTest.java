package com.orquitas.server;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias de integración de la arquitectura dual Neuroph 2.98:
 * - Red 1: modelo_ultrasonico.nnet (3 ejecuciones sectoriales por ciclo).
 * - Red 2: modelo_canicas.nnet (1 ejecución multimodal por ciclo).
 * - Carga automática por defecto desde keiko-server/models/ o recursos del JAR.
 */
public class TwoNetworksInferenceTest {

    private static ClasificadorNeuroph classifier;

    @BeforeAll
    public static void setUp() {
        ConfiguracionServidor cfg = new ConfiguracionServidor();
        classifier = new ClasificadorNeuroph(cfg.resolverArchivoModelo());
        assertNotNull(classifier.getNeuralNetUltrasonico(), "Red 1 (ultrasonico) debe cargarse por defecto");
        assertNotNull(classifier.getNeuralNetCanicas(), "Red 2 (canicas) debe cargarse por defecto");
    }

    @Test
    public void testClasspathFallbackLoadsEmbeddedModels() {
        ClasificadorNeuroph fromJar = new ClasificadorNeuroph(new File("/no-existe/modelo_ultrasonico.nnet"));
        assertNotNull(fromJar.getNeuralNetUltrasonico(), "Debe cargar modelo_ultrasonico.nnet desde classpath si no existe en disco");
        assertNotNull(fromJar.getNeuralNetCanicas(), "Debe cargar modelo_canicas.nnet desde classpath si no existe en disco");
        assertEquals("BLOQUEO", fromJar.clasificarSectorUltrasonico(12.0));
    }

    @Test
    public void testRed1UltrasonicClassification() {
        assertEquals("BLOQUEO", classifier.clasificarSectorUltrasonico(5.0));
        assertEquals("BLOQUEO", classifier.clasificarSectorUltrasonico(15.0));
        assertEquals("BLOQUEO", classifier.clasificarSectorUltrasonico(25.0));

        assertEquals("ESPACIO_LIBRE", classifier.clasificarSectorUltrasonico(35.0));
        assertEquals("ESPACIO_LIBRE", classifier.clasificarSectorUltrasonico(80.0));
        assertEquals("ESPACIO_LIBRE", classifier.clasificarSectorUltrasonico(150.0));

        assertEquals("SALIDA", classifier.clasificarSectorUltrasonico(195.0));
        assertEquals("SALIDA", classifier.clasificarSectorUltrasonico(220.0));
        assertEquals("SALIDA", classifier.clasificarSectorUltrasonico(245.0));
    }

    @Test
    public void testRed2MarbleDetectionAndLaserTimeout999() {
        VectorSensores vPiso = new VectorSensores(
                new double[]{90.0, 95.0, 85.0, 39.5, 39.8, 0.0},
                System.currentTimeMillis()
        );
        assertEquals("NINGUNA", classifier.clasificarCanicas(vPiso));

        // VL53L0X sin rebote (999.0 cm, como en detector-mixto.ino) debe ser válido y dar NINGUNA
        VectorSensores vTimeout = new VectorSensores(
                new double[]{120.0, 130.0, 115.0, 999.0, 999.0, -10.0},
                System.currentTimeMillis()
        );
        assertTrue(vTimeout.isValido(), "999.0 cm de timeout ToF debe aceptarse como válido");
        assertEquals("NINGUNA", classifier.clasificarCanicas(vTimeout));

        VectorSensores vCen = new VectorSensores(
                new double[]{90.0, 95.0, 85.0, 11.2, 11.5, 0.0},
                System.currentTimeMillis()
        );
        assertEquals("LOBITO_CENTRO", classifier.clasificarCanicas(vCen));

        VectorSensores vIzq = new VectorSensores(
                new double[]{90.0, 95.0, 85.0, 12.0, 39.0, 5.0},
                System.currentTimeMillis()
        );
        assertEquals("LOBITO_IZQ", classifier.clasificarCanicas(vIzq));

        VectorSensores vDer = new VectorSensores(
                new double[]{90.0, 95.0, 85.0, 39.0, 12.0, 5.0},
                System.currentTimeMillis()
        );
        assertEquals("LOBITO_DER", classifier.clasificarCanicas(vDer));

        VectorSensores vMuroLat = new VectorSensores(
                new double[]{90.0, 95.0, 85.0, 12.0, 39.0, 35.0},
                System.currentTimeMillis()
        );
        assertEquals("NINGUNA", classifier.clasificarCanicas(vMuroLat));

        VectorSensores vObst = new VectorSensores(
                new double[]{15.0, 14.0, 85.0, 14.5, 39.0, 10.0},
                System.currentTimeMillis()
        );
        assertEquals("NINGUNA", classifier.clasificarCanicas(vObst));
    }

    @Test
    public void testDualFusionInBusquedaVsEscape() {
        VectorSensores vCen = new VectorSensores(
                new double[]{80.0, 90.0, 85.0, 12.0, 12.0, 0.0},
                System.currentTimeMillis()
        );
        String[] pares = classifier.clasificarPares(vCen);
        assertArrayEquals(new String[]{"ESPACIO_LIBRE", "LOBITO", "ESPACIO_LIBRE"}, pares);

        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);
        ComandoRobot cmd = planner.evaluarPares(vCen, pares);
        assertEquals(EstadoRobot.APROXIMACION, cmd.getEstado());
        assertTrue(cmd.getVLeft() > 0 && cmd.getVRight() > 0);

        planner.activarModoEscape();
        ComandoRobot cmdEscape = planner.evaluarPares(vCen, pares);
        assertEquals(EstadoRobot.ESCAPE, cmdEscape.getEstado());
    }

    @Test
    public void testTelemetryParser7Tokens() {
        String telemetria = "TELEMETRY:45.50,110.25,82.00,38.5,14.2,-15.0,1720000000000\n";
        VectorSensores v = MensajeProtocolo.parsearTelemetria(telemetria);

        assertEquals(6, v.getCantidadSensores());
        assertEquals(45.50, v.getUsLeft(), 0.01);
        assertEquals(110.25, v.getUsCenter(), 0.01);
        assertEquals(82.00, v.getUsRight(), 0.01);
        assertEquals(38.5, v.getLaserLeft(), 0.01);
        assertEquals(14.2, v.getLaserRight(), 0.01);
        assertEquals(-15.0, v.getAnguloServo(), 0.01);
        assertEquals(1720000000000L, v.getTimestamp());
        assertTrue(v.isValido());
    }
}
