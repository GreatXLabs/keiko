package com.orquitas.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas de la cinematica anti-barrido de cola y de la memoria anti-bucle del planificador.
 * Los vectores se expresan como la terna neuronal [IZQ, CENTRO, DER].
 */
public class PlanificadorNavegacionTest {

    private static final String[] LIBRE = {"ESPACIO_LIBRE", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
    private static final String[] BLOQUEO_IZQ = {"BLOQUEO", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
    private static final String[] BLOQUEO_DER = {"ESPACIO_LIBRE", "ESPACIO_LIBRE", "BLOQUEO"};
    private static final String[] SALIDA_IZQ = {"SALIDA", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
    private static final String[] SALIDA_CEN = {"ESPACIO_LIBRE", "SALIDA", "ESPACIO_LIBRE"};
    private static final String[] SALIDA_DER = {"ESPACIO_LIBRE", "ESPACIO_LIBRE", "SALIDA"};

    @Test
    public void testLateralEvasionUsesForwardArc() {
        ComandoRobot evasivaIzquierda = new PlanificadorNavegacion(null).evaluarPares(null, BLOQUEO_IZQ);
        assertAll(
                () -> assertTrue(evasivaIzquierda.getVLeft() > 0.0, "La rueda izquierda debe avanzar"),
                () -> assertTrue(evasivaIzquierda.getVRight() > 0.0, "La rueda derecha debe avanzar"),
                () -> assertEquals(4.8, evasivaIzquierda.getVLeft(), 1e-9),
                () -> assertEquals(2.0, evasivaIzquierda.getVRight(), 1e-9)
        );

        ComandoRobot evasivaDerecha = new PlanificadorNavegacion(null).evaluarPares(null, BLOQUEO_DER);
        assertAll(
                () -> assertTrue(evasivaDerecha.getVLeft() > 0.0, "La rueda izquierda debe avanzar"),
                () -> assertTrue(evasivaDerecha.getVRight() > 0.0, "La rueda derecha debe avanzar"),
                () -> assertEquals(2.0, evasivaDerecha.getVLeft(), 1e-9),
                () -> assertEquals(4.8, evasivaDerecha.getVRight(), 1e-9)
        );
    }

    @Test
    public void testOscillationsSeparatedBySixtyCyclesTriggerAntiLoopEscape() {
        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);

        assertForwardArc(planner.evaluarPares(null, BLOQUEO_IZQ), "primer bloqueo lateral");

        avanzarLibres(planner, 60);
        assertForwardArc(planner.evaluarPares(null, BLOQUEO_DER), "segundo bloqueo lateral");

        avanzarLibres(planner, 60);
        ComandoRobot desatasco = planner.evaluarPares(null, BLOQUEO_IZQ);
        assertAll(
                () -> assertTrue(desatasco.getVLeft() < 0.0, "Fase 1 debe retroceder con la rueda izquierda"),
                () -> assertTrue(desatasco.getVRight() < 0.0, "Fase 1 debe retroceder con la rueda derecha"),
                () -> assertEquals(-4.2, desatasco.getVLeft(), 1e-9),
                () -> assertEquals(-4.2, desatasco.getVRight(), 1e-9)
        );

        // Fase 1 completa: 6 ciclos de retroceso (4 + 2 anti-bucle) contando el ciclo que disparo la maniobra.
        for (int ciclo = 0; ciclo < 5; ciclo++) {
            ComandoRobot retroceso = planner.evaluarPares(null, LIBRE);
            assertTrue(retroceso.getVLeft() < 0.0 && retroceso.getVRight() < 0.0,
                    "El retroceso anti-bucle debe continuar en el ciclo " + ciclo);
        }

        // Fase 2: 16 ciclos de giro amplio con sesgo hacia adelante para romper la simetria del bucle.
        for (int ciclo = 0; ciclo < 16; ciclo++) {
            ComandoRobot giro = planner.evaluarPares(null, LIBRE);
            assertAll(
                    () -> assertEquals(4.2, giro.getVLeft(), 1e-9),
                    () -> assertEquals(-2.4, giro.getVRight(), 1e-9)
            );
        }

        ComandoRobot posterior = planner.evaluarPares(null, LIBRE);
        assertTrue(posterior.getVLeft() > 0.0 && posterior.getVRight() > 0.0,
                "Tras la maniobra anti-bucle debe reanudar la exploracion");
    }

    @Test
    public void testStaleSideMemoryIsForgottenAfterHorizon() {
        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);
        planner.evaluarPares(null, BLOQUEO_IZQ);

        // 160 ciclos (> 150) sin bloqueos deben borrar el historial de lados.
        avanzarLibres(planner, 160);
        planner.evaluarPares(null, BLOQUEO_DER);

        ComandoRobot trasReaparicion = planner.evaluarPares(null, BLOQUEO_IZQ);
        assertTrue(trasReaparicion.getVLeft() > 0.0 && trasReaparicion.getVRight() > 0.0,
                "Un bloqueo aislado tras 160 ciclos no debe reutilizar el historial olvidado");
    }

    @Test
    public void testCenterBlockedBacksUpDefaultStepsBeforePivoting() {
        assertPasosRetrocesoYPivote(new String[]{"BLOQUEO", "BLOQUEO", "ESPACIO_LIBRE"}, 1,
                ConfiguracionServidor.PASOS_REVERSA_PREDETERMINADO);
        assertPasosRetrocesoYPivote(new String[]{"ESPACIO_LIBRE", "BLOQUEO", "BLOQUEO"}, -1,
                ConfiguracionServidor.PASOS_REVERSA_PREDETERMINADO);
        assertPasosRetrocesoYPivote(new String[]{"ESPACIO_LIBRE", "BLOQUEO", "ESPACIO_LIBRE"}, 1,
                ConfiguracionServidor.PASOS_REVERSA_PREDETERMINADO);
    }

    @Test
    public void testEscapeDoorwayCenteredShootsStraightThroughOpening() {
        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);
        planner.activarModoEscape();

        ComandoRobot centrado = planner.evaluarPares(null, SALIDA_CEN);
        assertAll(
                () -> assertEquals(EstadoRobot.ESCAPE, centrado.getEstado()),
                () -> assertEquals(3.4, centrado.getVLeft(), 1e-9),
                () -> assertEquals(3.4, centrado.getVRight(), 1e-9)
        );
    }

    @Test
    public void testEscapeDoorwayRightJambMicroReversesThenSquaresUpLeft() {
        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);
        planner.activarModoEscape();

        // Jamba derecha bloqueada con centro en salida: desviar a la izquierda suavemente.
        String[] jambaDerecha = {"ESPACIO_LIBRE", "SALIDA", "BLOQUEO"};
        ComandoRobot escape = planner.evaluarPares(null, jambaDerecha);
        assertAll(
                () -> assertEquals(EstadoRobot.ESCAPE, escape.getEstado()),
                () -> assertEquals(1.6, escape.getVLeft(), 1e-9),
                () -> assertEquals(3.2, escape.getVRight(), 1e-9)
        );
    }

    @Test
    public void testEscapeDoorwayLeftJambMicroReversesThenSquaresUpRight() {
        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);
        planner.activarModoEscape();

        // Jamba izquierda bloqueada con centro en salida: desviar a la derecha suavemente.
        String[] jambaIzquierda = {"BLOQUEO", "SALIDA", "ESPACIO_LIBRE"};
        ComandoRobot escape = planner.evaluarPares(null, jambaIzquierda);
        assertAll(
                () -> assertEquals(EstadoRobot.ESCAPE, escape.getEstado()),
                () -> assertEquals(3.2, escape.getVLeft(), 1e-9),
                () -> assertEquals(1.6, escape.getVRight(), 1e-9)
        );
    }

    @Test
    public void testEscapeDoorwayPinchedBetweenJambsBacksStraightOut() {
        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);
        planner.activarModoEscape();

        // Pinzado entre ambas jambas: retroceder recto suave para volver a la bahia antes de recentrar.
        String[] pinzado = {"BLOQUEO", "SALIDA", "BLOQUEO"};
        for (int ciclo = 0; ciclo < 6; ciclo++) {
            ComandoRobot retroceso = planner.evaluarPares(null, pinzado);
            assertAll(
                    () -> assertEquals(EstadoRobot.ESCAPE, retroceso.getEstado()),
                    () -> assertEquals(-2.4, retroceso.getVLeft(), 1e-9),
                    () -> assertEquals(-2.4, retroceso.getVRight(), 1e-9)
            );
        }
    }

    @Test
    public void testEscapeModePreservesForwardProgressAndAlignsToExit() {
        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);
        planner.activarModoEscape();

        ComandoRobot salidaIzquierda = planner.evaluarPares(null, SALIDA_IZQ);
        assertAll(
                () -> assertEquals(EstadoRobot.ESCAPE, salidaIzquierda.getEstado()),
                () -> assertTrue(salidaIzquierda.getVLeft() > 0.0 && salidaIzquierda.getVRight() > 0.0,
                        "Debe avanzar mientras alinea la salida izquierda"),
                () -> assertTrue(salidaIzquierda.getVRight() > salidaIzquierda.getVLeft(),
                        "Debe virar hacia la izquierda")
        );

        ComandoRobot salidaCentro = planner.evaluarPares(null, SALIDA_CEN);
        assertTrue(salidaCentro.getVLeft() > 0.0 && salidaCentro.getVRight() > 0.0,
                "Con la salida al centro debe avanzar recto");

        ComandoRobot salidaDerecha = planner.evaluarPares(null, SALIDA_DER);
        assertAll(
                () -> assertTrue(salidaDerecha.getVLeft() > 0.0 && salidaDerecha.getVRight() > 0.0,
                        "Debe avanzar mientras alinea la salida derecha"),
                () -> assertTrue(salidaDerecha.getVLeft() > salidaDerecha.getVRight(),
                        "Debe virar hacia la derecha")
        );

        String[] salidaIzquierdaConMuroDerecho = {"SALIDA", "ESPACIO_LIBRE", "BLOQUEO"};
        ComandoRobot esquivandoMuroDerecho = planner.evaluarPares(null, salidaIzquierdaConMuroDerecho);
        assertTrue(esquivandoMuroDerecho.getVLeft() > 0.0 && esquivandoMuroDerecho.getVRight() > 0.0,
                "El esquive lateral en ESCAPE tambien debe usar arco hacia adelante");
    }

    @Test
    public void testDecisionesDependenSoloDeTernaNeuronalYNoDeDistanciasCrudas() {
        PlanificadorNavegacion sinSensores = new PlanificadorNavegacion(null);
        PlanificadorNavegacion conSensoresExtremos = new PlanificadorNavegacion(null);
        VectorSensores sensoresExtremosCerca = new VectorSensores(
                new double[]{2.5, 2.5, 2.5, 2.5, 2.5, 30.0}, 1000L);
        VectorSensores sensoresExtremosLejos = new VectorSensores(
                new double[]{240.0, 240.0, 240.0, 240.0, 240.0, -30.0}, 2000L);

        String[][] secuenciaBusqueda = {
                LIBRE,
                {"ESPACIO_LIBRE", "LOBITO", "ESPACIO_LIBRE"},
                {"LOBITO", "ESPACIO_LIBRE", "ESPACIO_LIBRE"},
                {"ESPACIO_LIBRE", "ESPACIO_LIBRE", "LOBITO"},
                LIBRE,
                BLOQUEO_IZQ,
                BLOQUEO_DER,
                {"BLOQUEO", "BLOQUEO", "BLOQUEO"},
                LIBRE,
                LIBRE,
                SALIDA_CEN,
                {"SALIDA", "BLOQUEO", "ESPACIO_LIBRE"},
                {"ESPACIO_LIBRE", "BLOQUEO", "SALIDA"},
                {"SALIDA", "ESPACIO_LIBRE", "BLOQUEO"}
        };

        for (int i = 0; i < secuenciaBusqueda.length; i++) {
            String[] terna = secuenciaBusqueda[i];
            VectorSensores dummy = (i % 2 == 0) ? sensoresExtremosCerca : sensoresExtremosLejos;
            ComandoRobot esperado = sinSensores.evaluarPares(null, terna);
            ComandoRobot actual = conSensoresExtremos.evaluarPares(dummy, terna);
            assertComandoIgual("BUSQUEDA/APROXIMACION/BLOQUEO paso " + i, esperado, actual);
        }

        sinSensores.notificarCaptura();
        conSensoresExtremos.notificarCaptura();
        for (int i = 0; i < 4; i++) {
            VectorSensores dummy = (i % 2 == 0) ? sensoresExtremosCerca : sensoresExtremosLejos;
            assertComandoIgual("post-captura paso " + i,
                    sinSensores.evaluarPares(null, LIBRE),
                    conSensoresExtremos.evaluarPares(dummy, LIBRE));
        }

        sinSensores.activarModoEscape();
        conSensoresExtremos.activarModoEscape();

        String[][] secuenciaEscape = {
                LIBRE,
                BLOQUEO_IZQ,
                SALIDA_IZQ,
                SALIDA_DER,
                SALIDA_CEN,
                {"BLOQUEO", "SALIDA", "BLOQUEO"},
                LIBRE
        };

        for (int i = 0; i < secuenciaEscape.length; i++) {
            String[] terna = secuenciaEscape[i];
            VectorSensores dummy = (i % 2 == 0) ? sensoresExtremosCerca : sensoresExtremosLejos;
            ComandoRobot esperado = sinSensores.evaluarPares(null, terna);
            ComandoRobot actual = conSensoresExtremos.evaluarPares(dummy, terna);
            assertComandoIgual("ESCAPE paso " + i, esperado, actual);
        }

        sinSensores.finalizar();
        conSensoresExtremos.finalizar();
        ComandoRobot finSin = sinSensores.evaluarPares(null, LIBRE);
        ComandoRobot finCon = conSensoresExtremos.evaluarPares(sensoresExtremosCerca, LIBRE);
        assertComandoIgual("FINALIZACION", finSin, finCon);
        assertEquals(EstadoRobot.FINALIZACION, finCon.getEstado());
        assertEquals(0.0, finCon.getVLeft(), 1e-9);
        assertEquals(0.0, finCon.getVRight(), 1e-9);
    }

    @Test
    public void testSameSidePerimeterLoopTriggersInwardBreakoutAfterFourEpisodes() {
        assertSameSideBreakout(BLOQUEO_IZQ, 1);
        assertSameSideBreakout(BLOQUEO_DER, -1);
    }

    @Test
    public void testContinuousSameSideGrazeCountsAsSingleEpisode() {
        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);
        for (int ciclo = 0; ciclo < 15; ciclo++) {
            ComandoRobot cmd = planner.evaluarPares(null, BLOQUEO_IZQ);
            assertAll(
                    () -> assertEquals(EstadoRobot.BUSQUEDA, cmd.getEstado()),
                    () -> assertEquals(4.8, cmd.getVLeft(), 1e-9),
                    () -> assertEquals(2.0, cmd.getVRight(), 1e-9)
            );
        }
    }

    @Test
    public void testSameSideEpisodeMemoryIsForgottenAfterHorizon() {
        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);
        for (int ep = 0; ep < 3; ep++) {
            assertForwardArc(planner.evaluarPares(null, BLOQUEO_IZQ), "episodio previo " + ep);
            avanzarLibres(planner, 10);
        }

        // > 150 ciclos libres deben olvidar los episodios previos del mismo lado.
        avanzarLibres(planner, 160);
        ComandoRobot trasOlvido = planner.evaluarPares(null, BLOQUEO_IZQ);
        assertAll(
                () -> assertEquals(4.8, trasOlvido.getVLeft(), 1e-9),
                () -> assertEquals(2.0, trasOlvido.getVRight(), 1e-9)
        );
    }

    @Test
    public void testSearchModeLateralSalidaWithCenterBloqueoBacksUpAndPivotsAway() {
        assertPasosRetrocesoYPivote(new String[]{"SALIDA", "BLOQUEO", "ESPACIO_LIBRE"}, 1,
                ConfiguracionServidor.PASOS_REVERSA_PREDETERMINADO);
        assertPasosRetrocesoYPivote(new String[]{"ESPACIO_LIBRE", "BLOQUEO", "SALIDA"}, -1,
                ConfiguracionServidor.PASOS_REVERSA_PREDETERMINADO);

        PlanificadorNavegacion libreDer = new PlanificadorNavegacion(null);
        ComandoRobot arcIzq = libreDer.evaluarPares(null, SALIDA_IZQ);
        assertAll(
                () -> assertEquals(EstadoRobot.BUSQUEDA, arcIzq.getEstado()),
                () -> assertEquals(3.8, arcIzq.getVLeft(), 1e-9),
                () -> assertEquals(2.2, arcIzq.getVRight(), 1e-9)
        );

        PlanificadorNavegacion bloqueadoDer = new PlanificadorNavegacion(null);
        ComandoRobot evadeDer = bloqueadoDer.evaluarPares(null, new String[]{"SALIDA", "ESPACIO_LIBRE", "BLOQUEO"});
        assertAll(
                () -> assertEquals(EstadoRobot.BUSQUEDA, evadeDer.getEstado()),
                () -> assertEquals(2.0, evadeDer.getVLeft(), 1e-9),
                () -> assertEquals(4.8, evadeDer.getVRight(), 1e-9)
        );
    }

    private static void assertSameSideBreakout(String[] bloqueoLateral, int direccionPivoteInward) {
        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);
        PlanificadorNavegacion conSensores = new PlanificadorNavegacion(null);
        VectorSensores dummyCerca = new VectorSensores(new double[]{2.5, 2.5, 2.5, 2.5, 2.5, 0.0}, 100L);

        for (int ep = 0; ep < 3; ep++) {
            ComandoRobot c1 = planner.evaluarPares(null, bloqueoLateral);
            ComandoRobot c2 = conSensores.evaluarPares(dummyCerca, bloqueoLateral);
            assertForwardArc(c1, "episodio " + (ep + 1));
            assertComandoIgual("same-side ep " + (ep + 1), c1, c2);
            avanzarLibres(planner, 10);
            avanzarLibres(conSensores, 10);
        }

        // 4to episodio distinto del mismo lado dentro del horizonte debe activar breakout anti-bucle.
        ComandoRobot breakout = planner.evaluarPares(null, bloqueoLateral);
        ComandoRobot breakoutSens = conSensores.evaluarPares(dummyCerca, bloqueoLateral);
        assertComandoIgual("same-side breakout", breakout, breakoutSens);
        assertAll(
                () -> assertEquals(EstadoRobot.BUSQUEDA, breakout.getEstado()),
                () -> assertEquals(-4.2, breakout.getVLeft(), 1e-9),
                () -> assertEquals(-4.2, breakout.getVRight(), 1e-9)
        );

        for (int ciclo = 0; ciclo < 5; ciclo++) {
            ComandoRobot retroceso = planner.evaluarPares(null, LIBRE);
            assertAll(
                    () -> assertEquals(-4.2, retroceso.getVLeft(), 1e-9),
                    () -> assertEquals(-4.2, retroceso.getVRight(), 1e-9)
            );
        }

        double esperadoIzq = (direccionPivoteInward > 0) ? 4.2 : -2.4;
        double esperadoDer = (direccionPivoteInward > 0) ? -2.4 : 4.2;
        for (int ciclo = 0; ciclo < 16; ciclo++) {
            ComandoRobot giro = planner.evaluarPares(null, LIBRE);
            assertAll(
                    () -> assertEquals(esperadoIzq, giro.getVLeft(), 1e-9),
                    () -> assertEquals(esperadoDer, giro.getVRight(), 1e-9)
            );
        }

        ComandoRobot posterior = planner.evaluarPares(null, LIBRE);
        assertForwardArc(posterior, "reanudacion tras breakout");
    }

    private static void assertComandoIgual(String contexto, ComandoRobot esperado, ComandoRobot actual) {
        assertAll(
                () -> assertEquals(esperado.getEstado(), actual.getEstado(), contexto + " estado"),
                () -> assertEquals(esperado.getVLeft(), actual.getVLeft(), 1e-9, contexto + " vLeft"),
                () -> assertEquals(esperado.getVRight(), actual.getVRight(), 1e-9, contexto + " vRight")
        );
    }

    private static void assertForwardArc(ComandoRobot comando, String contexto) {
        assertTrue(comando.getVLeft() > 0.0 && comando.getVRight() > 0.0,
                contexto + " debe resolverse con arco hacia adelante");
    }

    private static void assertPasosRetrocesoYPivote(String[] pares, int direccionEsperada, int pasosEsperados) {
        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);
        for (int ciclo = 0; ciclo < pasosEsperados; ciclo++) {
            ComandoRobot retroceso = planner.evaluarPares(null, pares);
            assertAll(
                    () -> assertEquals(-3.6, retroceso.getVLeft(), 1e-9),
                    () -> assertEquals(-3.6, retroceso.getVRight(), 1e-9)
            );
        }
        ComandoRobot giro = planner.evaluarPares(null, LIBRE);
        assertTrue(giro.getVLeft() * giro.getVRight() < 0.0, "Debe pivotar tras el retroceso");
        assertTrue(giro.getVLeft() * direccionEsperada > 0.0,
                "El pivote debe alejarse del lado bloqueado");
    }

    @Test
    public void testSalidaEnBusquedaNoHaceReversaYPivotaEnElLugar() {
        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);
        // Ante SALIDA central sin BLOQUEO, nunca debe retroceder ni 1 paso: pivote directo (vL*vR < 0)
        ComandoRobot pivote = planner.evaluarPares(null, SALIDA_CEN);
        assertEquals(EstadoRobot.BUSQUEDA, pivote.getEstado());
        assertTrue(pivote.getVLeft() * pivote.getVRight() < 0.0,
                "Frente a SALIDA sin bloqueo debe pivotar en el lugar sin dar reversa");

        // Si en medio del pivote por SALIDA aparece BLOQUEO frontal, aborta pivote y reacciona al bloqueo
        ComandoRobot reaccionBloqueo = planner.evaluarPares(null, new String[]{"BLOQUEO", "BLOQUEO", "BLOQUEO"});
        assertTrue(reaccionBloqueo.getVLeft() < 0.0 && reaccionBloqueo.getVRight() < 0.0,
                "Debe abortar el pivote de SALIDA si aparece BLOQUEO frontal");
    }

    @Test
    public void testPasosReversaAjustableCeroYSiete() {
        PlanificadorNavegacion ceroRev = new PlanificadorNavegacion(null, 0);
        ComandoRobot pivDirecto = ceroRev.evaluarPares(null, new String[]{"BLOQUEO", "BLOQUEO", "ESPACIO_LIBRE"});
        assertTrue(pivDirecto.getVLeft() * pivDirecto.getVRight() < 0.0,
                "Con 0 pasos de reversa debe pivotar directamente");

        PlanificadorNavegacion sieteRev = new PlanificadorNavegacion(null);
        sieteRev.setPasosReversa(7);
        for (int i = 0; i < 7; i++) {
            ComandoRobot rev = sieteRev.evaluarPares(null, new String[]{"ESPACIO_LIBRE", "BLOQUEO", "ESPACIO_LIBRE"});
            assertEquals(-3.6, rev.getVLeft(), 1e-9);
            assertEquals(-3.6, rev.getVRight(), 1e-9);
        }
        ComandoRobot piv = sieteRev.evaluarPares(null, LIBRE);
        assertTrue(piv.getVLeft() * piv.getVRight() < 0.0, "Despues de 7 pasos debe iniciar pivote");
    }

    private static void avanzarLibres(PlanificadorNavegacion planner, int ciclos) {
        for (int ciclo = 0; ciclo < ciclos; ciclo++) {
            planner.evaluarPares(null, LIBRE);
        }
    }

    @Test
    public void testModosForzadosBusquedaEscapeYCaptura() {
        PlanificadorNavegacion p = new PlanificadorNavegacion(null);

        // Forzar CAPTURA mantiene el estado CAPTURA aunque no haya deteccion neuronal de canica
        p.forzarModoCaptura();
        assertEquals(EstadoRobot.CAPTURA, p.getEstado());
        assertTrue(p.isModoCapturaForzado());
        ComandoRobot cmdCap = p.evaluarPares(null, LIBRE);
        assertEquals(EstadoRobot.CAPTURA, cmdCap.getEstado());
        assertTrue(cmdCap.getVLeft() > 0.0 && cmdCap.getVRight() > 0.0);

        // Forzar ESCAPE limpia modo captura forzado y activa ESCAPE
        p.forzarModoEscape();
        assertEquals(EstadoRobot.ESCAPE, p.getEstado());
        assertFalse(p.isModoCapturaForzado());
        ComandoRobot cmdEsc = p.evaluarPares(null, LIBRE);
        assertEquals(EstadoRobot.ESCAPE, cmdEsc.getEstado());

        // Forzar BUSQUEDA vuelve limpiamente a BUSQUEDA
        p.forzarModoBusqueda();
        assertEquals(EstadoRobot.BUSQUEDA, p.getEstado());
        ComandoRobot cmdBusq = p.evaluarPares(null, LIBRE);
        assertEquals(EstadoRobot.BUSQUEDA, cmdBusq.getEstado());
    }

    @Test
    public void testBloqueoYSalidaSuperanALobitoYPersistenciaEsCorta() {
        // 1. BLOQUEO frontal con LOBITO lateral: debe retroceder en BUSQUEDA, nunca avanzar hacia el muro
        PlanificadorNavegacion p1 = new PlanificadorNavegacion(null);
        ComandoRobot revMuro = p1.evaluarPares(null, new String[]{"LOBITO", "BLOQUEO", "ESPACIO_LIBRE"});
        assertEquals(EstadoRobot.BUSQUEDA, revMuro.getEstado());
        assertTrue(revMuro.getVLeft() < 0.0 && revMuro.getVRight() < 0.0,
                "Con BLOQUEO frontal + LOBITO lateral debe dar reversa segura");

        // 2. BLOQUEO lateral con LOBITO en otro sector: debe evadir muro primero (no atropellar la pared)
        PlanificadorNavegacion p2 = new PlanificadorNavegacion(null);
        ComandoRobot evadeLat = p2.evaluarPares(null, new String[]{"BLOQUEO", "LOBITO", "ESPACIO_LIBRE"});
        assertEquals(EstadoRobot.BUSQUEDA, evadeLat.getEstado());
        assertEquals(4.8, evadeLat.getVLeft(), 1e-9);
        assertEquals(2.0, evadeLat.getVRight(), 1e-9);

        // 3. SALIDA lateral con LOBITO en otro sector: prioridad SALIDA para evitar egreso accidental
        PlanificadorNavegacion p3 = new PlanificadorNavegacion(null);
        ComandoRobot evitaSalida = p3.evaluarPares(null, new String[]{"SALIDA", "ESPACIO_LIBRE", "LOBITO"});
        assertEquals(EstadoRobot.BUSQUEDA, evitaSalida.getEstado());
        assertEquals(3.8, evitaSalida.getVLeft(), 1e-9);
        assertEquals(2.2, evitaSalida.getVRight(), 1e-9);

        // 4. Persistencia de LOBITO dura 5 ciclos (0.5 s) y se corta de inmediato si aparece BLOQUEO
        PlanificadorNavegacion p4 = new PlanificadorNavegacion(null);
        assertEquals(EstadoRobot.APROXIMACION,
                p4.evaluarPares(null, new String[]{"ESPACIO_LIBRE", "LOBITO", "ESPACIO_LIBRE"}).getEstado());
        for (int i = 0; i < 5; i++) {
            assertEquals(EstadoRobot.APROXIMACION, p4.evaluarPares(null, LIBRE).getEstado(),
                    "Persistencia debe mantenerse en ciclo " + i);
        }
        assertEquals(EstadoRobot.BUSQUEDA, p4.evaluarPares(null, LIBRE).getEstado(),
                "Tras 5 ciclos sin ver la canica debe volver a BUSQUEDA");

        PlanificadorNavegacion p5 = new PlanificadorNavegacion(null);
        assertEquals(EstadoRobot.APROXIMACION,
                p5.evaluarPares(null, new String[]{"LOBITO", "ESPACIO_LIBRE", "ESPACIO_LIBRE"}).getEstado());
        ComandoRobot corte = p5.evaluarPares(null, BLOQUEO_IZQ);
        assertEquals(EstadoRobot.BUSQUEDA, corte.getEstado());
        assertEquals(EstadoRobot.BUSQUEDA, p5.evaluarPares(null, LIBRE).getEstado(),
                "Un BLOQUEO debe limpiar la persistencia de LOBITO de inmediato");
    }
}
