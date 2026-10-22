package com.orquitas.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
                () -> assertEquals(-2.0, desatasco.getVRight(), 1e-9)
        );

        // Fase 1 completa: 12 ciclos de retroceso contando el ciclo que disparo la maniobra.
        for (int ciclo = 0; ciclo < 11; ciclo++) {
            ComandoRobot retroceso = planner.evaluarPares(null, LIBRE);
            assertTrue(retroceso.getVLeft() < 0.0 && retroceso.getVRight() < 0.0,
                    "El retroceso anti-bucle debe continuar en el ciclo " + ciclo);
        }

        // Fase 2: 16 ciclos de giro amplio asimetrico para romper la simetria del bucle.
        for (int ciclo = 0; ciclo < 16; ciclo++) {
            ComandoRobot giro = planner.evaluarPares(null, LIBRE);
            assertAll(
                    () -> assertEquals(5.0, giro.getVLeft(), 1e-9),
                    () -> assertEquals(-4.6, giro.getVRight(), 1e-9)
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
    public void testCenterBlockedBacksUpSixStepsBeforePivoting() {
        assertSeisPasosRetrocesoYPivote(new String[]{"BLOQUEO", "BLOQUEO", "ESPACIO_LIBRE"}, 1);
        assertSeisPasosRetrocesoYPivote(new String[]{"ESPACIO_LIBRE", "BLOQUEO", "BLOQUEO"}, -1);
        assertSeisPasosRetrocesoYPivote(new String[]{"ESPACIO_LIBRE", "BLOQUEO", "ESPACIO_LIBRE"}, 1);
    }

    @Test
    public void testEscapeDoorwayWithBlockedJambsKeepsForwardProgress() {
        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);
        planner.activarModoEscape();

        // El hueco de 30 cm puede verse como SALIDA al centro y jambas bloqueadas a los lados.
        String[] huecoDeSalida = {"BLOQUEO", "SALIDA", "BLOQUEO"};
        for (int ciclo = 0; ciclo < 40; ciclo++) {
            ComandoRobot cmd = planner.evaluarPares(null, huecoDeSalida);
            assertTrue(cmd.getVLeft() > 0.0 && cmd.getVRight() > 0.0,
                    "En ESCAPE debe cruzar el hueco sin retroceder, ciclo " + ciclo);
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

    private static void assertForwardArc(ComandoRobot comando, String contexto) {
        assertTrue(comando.getVLeft() > 0.0 && comando.getVRight() > 0.0,
                contexto + " debe resolverse con arco hacia adelante");
    }

    private static void assertSeisPasosRetrocesoYPivote(String[] pares, int direccionEsperada) {
        PlanificadorNavegacion planner = new PlanificadorNavegacion(null);
        for (int ciclo = 0; ciclo < 6; ciclo++) {
            ComandoRobot retroceso = planner.evaluarPares(null, pares);
            assertAll(
                    () -> assertEquals(-4.2, retroceso.getVLeft(), 1e-9),
                    () -> assertEquals(-2.6, retroceso.getVRight(), 1e-9)
            );
        }
        ComandoRobot giro = planner.evaluarPares(null, LIBRE);
        assertTrue(giro.getVLeft() * giro.getVRight() < 0.0, "Debe pivotar tras el retroceso");
        assertTrue(giro.getVLeft() * direccionEsperada > 0.0,
                "El pivote debe alejarse del lado bloqueado");
    }

    private static void avanzarLibres(PlanificadorNavegacion planner, int ciclos) {
        for (int ciclo = 0; ciclo < ciclos; ciclo++) {
            planner.evaluarPares(null, LIBRE);
        }
    }
}
