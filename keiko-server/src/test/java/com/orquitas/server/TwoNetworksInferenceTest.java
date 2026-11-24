package com.orquitas.server;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas unitarias de integración de la nueva arquitectura dual Neuroph 2.98:
 * - Red 1: modelo_ultrasonico.nnet (3 ejecuciones sectoriales por ciclo).
 * - Red 2: modelo_canicas.nnet (1 ejecución multimodal por ciclo).
 * - Fusión sensorial estricta y conmutación al Modo ESCAPE.
 */
public class TwoNetworksInferenceTest {

    private static ClasificadorNeuroph classifier;

    @BeforeAll
    public static void setUp() {
        File usModel = new File("../keiko-sim/data/models/modelo_ultrasonico.nnet");
        File marbleModel = new File("../keiko-sim/data/models/modelo_canicas.nnet");

        assertTrue(usModel.exists(), "modelo_ultrasonico.nnet debe existir");
        assertTrue(marbleModel.exists(), "modelo_canicas.nnet debe existir");

        classifier = new ClasificadorNeuroph(usModel, marbleModel);
        assertNotNull(classifier.getNeuralNetUltrasonico(), "Red 1 (ultrasonico) debe estar cargada");
        assertNotNull(classifier.getNeuralNetCanicas(), "Red 2 (canicas) debe estar cargada");
    }

    @Test
    public void testRed1UltrasonicClassification() {
        // Distancia corta (<= 16 cm) -> BLOQUEO
        assertEquals("BLOQUEO", classifier.clasificarSectorUltrasonico(5.0));
        assertEquals("BLOQUEO", classifier.clasificarSectorUltrasonico(10.0));
        assertEquals("BLOQUEO", classifier.clasificarSectorUltrasonico(15.0));

        // Distancia media (18 - 165 cm) -> ESPACIO_LIBRE
        assertEquals("ESPACIO_LIBRE", classifier.clasificarSectorUltrasonico(25.0));
        assertEquals("ESPACIO_LIBRE", classifier.clasificarSectorUltrasonico(35.0));
        assertEquals("ESPACIO_LIBRE", classifier.clasificarSectorUltrasonico(80.0));
        assertEquals("ESPACIO_LIBRE", classifier.clasificarSectorUltrasonico(150.0));

        // Distancia larga (> 185 cm) a través de la salida de 30 cm -> SALIDA
        assertEquals("SALIDA", classifier.clasificarSectorUltrasonico(195.0));
        assertEquals("SALIDA", classifier.clasificarSectorUltrasonico(220.0));
        assertEquals("SALIDA", classifier.clasificarSectorUltrasonico(245.0));
    }

    @Test
    public void testRed2MarbleDetection() {
        // 1. Escenario sin canica (piso limpio: reposo láser ~39 cm, US despejado)
        VectorSensores vPiso = new VectorSensores(
                new double[]{90.0, 95.0, 85.0, 39.5, 39.8, 0.0},
                System.currentTimeMillis()
        );
        assertEquals("NINGUNA", classifier.clasificarCanicas(vPiso));

        // 2. Canica en centro: ambos láseres leen distancia baja (~11 cm) con ángulo cerca de 0°
        VectorSensores vCen = new VectorSensores(
                new double[]{90.0, 95.0, 85.0, 11.2, 11.5, 0.0},
                System.currentTimeMillis()
        );
        assertEquals("LOBITO_CENTRO", classifier.clasificarCanicas(vCen));

        // 3. Canica en izquierda: láser izquierdo en cono (~12 cm), servo en ángulo frontal (-35° a +15°)
        VectorSensores vIzq = new VectorSensores(
                new double[]{90.0, 95.0, 85.0, 12.0, 39.0, 5.0},
                System.currentTimeMillis()
        );
        assertEquals("LOBITO_IZQ", classifier.clasificarCanicas(vIzq));

        // 4. Canica en derecha: láser derecho en cono (~12 cm), servo en ángulo frontal (-35° a +15°)
        VectorSensores vDer = new VectorSensores(
                new double[]{90.0, 95.0, 85.0, 39.0, 12.0, 5.0},
                System.currentTimeMillis()
        );
        assertEquals("LOBITO_DER", classifier.clasificarCanicas(vDer));

        // 5. Muro lateral: láser lee 12 cm pero servo apunta a lateral (> 45°) -> NINGUNA canica
        VectorSensores vMuroLat = new VectorSensores(
                new double[]{90.0, 95.0, 85.0, 12.0, 39.0, 55.0},
                System.currentTimeMillis()
        );
        assertEquals("NINGUNA", classifier.clasificarCanicas(vMuroLat));

        // 6. Muro u obstáculo alto: láser lee bajo (15 cm) pero ultrasonido también lee obstáculo (< 25 cm) -> NINGUNA canica
        VectorSensores vObst = new VectorSensores(
                new double[]{15.0, 14.0, 85.0, 14.5, 39.0, 10.0},
                System.currentTimeMillis()
        );
        assertEquals("NINGUNA", classifier.clasificarCanicas(vObst));

        VectorSensores vSalidaCanica = new VectorSensores(
                new double[]{217.7, 247.1, 246.3, 13.9, 39.2, -21.0},
                System.currentTimeMillis()
        );
        assertEquals("LOBITO_IZQ", classifier.clasificarCanicas(vSalidaCanica));

        VectorSensores vCanicaNearWall = new VectorSensores(
                new double[]{44.0, 46.0, 48.0, 14.3, 39.0, 14.0},
                System.currentTimeMillis()
        );
        assertEquals("LOBITO_IZQ", classifier.clasificarCanicas(vCanicaNearWall));

        VectorSensores vDiagnose1 = new VectorSensores(
                new double[]{34.9, 34.8, 32.8, 31.1, 31.2, -7.0},
                System.currentTimeMillis()
        );
        assertEquals("NINGUNA", classifier.clasificarCanicas(vDiagnose1));

        // Muro/obstáculo frontal donde tanto el US como el láser miden el mismo obstáculo (~18 cm)
        VectorSensores vMuroConfirmado = new VectorSensores(
                new double[]{18.5, 18.0, 18.2, 18.5, 18.2, 0.0},
                System.currentTimeMillis()
        );
        assertEquals("NINGUNA", classifier.clasificarCanicas(vMuroConfirmado));

        // Canica a distancia muy corta (5.0 cm, aproximación final al picker): US despejado
        VectorSensores vCanicaCorta = new VectorSensores(
                new double[]{80.0, 85.0, 90.0, 4.8, 5.1, -5.0},
                System.currentTimeMillis()
        );
        assertEquals("LOBITO_CENTRO", classifier.clasificarCanicas(vCanicaCorta));

        // Muro lateral a 35° con lectura ToF baja en pared este: debe ser NINGUNA
        VectorSensores vMuroEsteColision = new VectorSensores(
                new double[]{21.8, 30.3, 27.8, 10.9, 26.6, 35.0},
                System.currentTimeMillis()
        );
        assertEquals("NINGUNA", classifier.clasificarCanicas(vMuroEsteColision));

        // Muro lateral con lectura ToF baja y US que confirma obstaculo: debe ser NINGUNA
        VectorSensores vMuroInward = new VectorSensores(
                new double[]{11.0, 15.0, 45.0, 9.5, 38.0, -7.0},
                System.currentTimeMillis()
        );
        assertEquals("NINGUNA", classifier.clasificarCanicas(vMuroInward));

        // Muro oblicuo en ángulo inward: láser bajo (15.2 cm) y US bajo (24.6 cm) -> NINGUNA
        VectorSensores vMuroOblicuoInward = new VectorSensores(
                new double[]{24.6, 32.3, 38.9, 15.2, 38.1, -21.0},
                System.currentTimeMillis()
        );
        assertEquals("NINGUNA", classifier.clasificarCanicas(vMuroOblicuoInward));
    }

    @Test
    public void testRed2MatchesRawNeurophArgmax() {
        org.neuroph.core.NeuralNetwork<?> net = classifier.getNeuralNetCanicas();
        VectorSensores[] vectors = new VectorSensores[]{
                // Vector that previously triggered HRD-01 (servoDeg >= 18.0 with US < 38.0)
                new VectorSensores(new double[]{21.8, 30.3, 27.8, 10.9, 26.6, 35.0}, System.currentTimeMillis()),
                // Vector with servoDeg >= 18.0 and centered lasers
                new VectorSensores(new double[]{80.0, 90.0, 85.0, 12.0, 12.0, 25.0}, System.currentTimeMillis()),
                // Vector that previously triggered HRD-02 binocular override (servoDeg <= -10.0)
                new VectorSensores(new double[]{80.0, 90.0, 85.0, 15.0, 18.0, -15.0}, System.currentTimeMillis()),
                // Vector with asymmetric lasers at 0 deg
                new VectorSensores(new double[]{80.0, 90.0, 85.0, 11.0, 16.0, 0.0}, System.currentTimeMillis()),
                // Standard open-floor vector
                new VectorSensores(new double[]{90.0, 95.0, 85.0, 39.5, 39.8, 0.0}, System.currentTimeMillis())
        };

        for (VectorSensores v : vectors) {
            double nUsIzq = Math.min(1.0, Math.max(0.0, (v.getUsLeft() - 2.0) / 248.0));
            double nUsCen = Math.min(1.0, Math.max(0.0, (v.getUsCenter() - 2.0) / 248.0));
            double nUsDer = Math.min(1.0, Math.max(0.0, (v.getUsRight() - 2.0) / 248.0));
            double nLaserIzq = Math.min(1.0, Math.max(0.0, (v.getLaserLeft() - 2.0) / 41.0));
            double nLaserDer = Math.min(1.0, Math.max(0.0, (v.getLaserRight() - 2.0) / 41.0));
            double nServo = Math.min(1.0, Math.max(0.0, (v.getAnguloServo() - (-45.0)) / 105.0));

            net.setInput(nUsIzq, nUsCen, nUsDer, nLaserIzq, nLaserDer, nServo);
            net.calculate();
            double[] out = net.getOutput();
            int maxIdx = 0;
            for (int i = 1; i < out.length; i++) {
                if (out[i] > out[maxIdx]) {
                    maxIdx = i;
                }
            }
            String expectedRaw = ClasificadorNeuroph.CLASES_CANICAS_4[maxIdx];
            assertEquals(expectedRaw, classifier.clasificarCanicas(v),
                    "clasificarCanicas debe devolver el argmax crudo de Neuroph sin filtros post-inferencia");
        }
    }

    @Test
    public void testDualFusionInBusquedaVsEscape() {
        // Canica centrada detectada en BÚSQUEDA
        VectorSensores vCen = new VectorSensores(
                new double[]{80.0, 90.0, 85.0, 12.0, 12.0, 0.0},
                System.currentTimeMillis()
        );
        String[] pares = classifier.clasificarPares(vCen);
        assertEquals("ESPACIO_LIBRE", pares[0]);
        assertEquals("LOBITO", pares[1], "El sector central debe quedar pisado con LOBITO por fusión neuronal");
        assertEquals("ESPACIO_LIBRE", pares[2]);

        // En PlanificadorNavegacion, verificar aproximación centrada suave
        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);
        ComandoRobot cmd = planner.evaluarPares(vCen, pares);
        assertEquals(EstadoRobot.APROXIMACION, cmd.getEstado());
        assertTrue(cmd.getVLeft() > 0 && cmd.getVRight() > 0, "Debe avanzar hacia la canica centrada");

        // Al conmutar al Modo ESCAPE, Red 2 no debe reemplazar sectores con LOBITO
        String[] paresSoloUs = classifier.clasificarPares(vCen, false);
        assertEquals("ESPACIO_LIBRE", paresSoloUs[0]);
        assertEquals("ESPACIO_LIBRE", paresSoloUs[1], "En modo ESCAPE no debe inyectar LOBITO en la terna");
        assertEquals("ESPACIO_LIBRE", paresSoloUs[2]);

        planner.activarModoEscape();
        ComandoRobot cmdEscape = planner.evaluarPares(vCen, paresSoloUs);
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
