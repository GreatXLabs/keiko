package com.orquitas.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias de controles 1-tecla y modos forzados en ServidorCentral.
 */
public class ServidorCentralControlTest {

    @Test
    public void testControlesTeclaUnicaYModosForzados(@TempDir Path tempDir) {
        ConfiguracionServidor cfg = new ConfiguracionServidor();
        cfg.setNoTuiSolicitado(true);
        cfg.setRutaArchivoEstomago(tempDir.resolve("estomago_test.txt").toString());

        ServidorCentral servidor = new ServidorCentral(cfg);
        EstadoMonitorTui monitor = servidor.getMonitorTui();
        PlanificadorNavegacion plan = servidor.getPlanificador();

        // Velocidad con + / = / - / _
        double base = monitor.getEscalaVelocidad();
        servidor.procesarComandoConsola("+");
        assertEquals(base + 0.10, monitor.getEscalaVelocidad(), 0.001);
        servidor.procesarComandoConsola("=");
        assertEquals(base + 0.20, monitor.getEscalaVelocidad(), 0.001);
        servidor.procesarComandoConsola("_");
        assertEquals(base + 0.10, monitor.getEscalaVelocidad(), 0.001);

        // PWM min con ] / [
        int pwmMinInicial = monitor.getPwmMin();
        servidor.procesarComandoConsola("]");
        assertEquals(pwmMinInicial + 5, monitor.getPwmMin());
        servidor.procesarComandoConsola("[");
        assertEquals(pwmMinInicial, monitor.getPwmMin());

        // Toggle barrido laser en reposo con b
        boolean sweepInicial = monitor.isBarridoEnReposo();
        servidor.procesarComandoConsola("b");
        assertEquals(!sweepInicial, monitor.isBarridoEnReposo());

        // Modos forzados 2 (ESCAPE) -> 3 (PICKER ON en reposo) -> k (START) + 3 (FORZAR CAPTURA) -> 1 (BUSQUEDA)
        servidor.procesarComandoConsola("2");
        assertAll(
                () -> assertEquals(EstadoRobot.ESCAPE, plan.getEstado()),
                () -> assertEquals(EstadoRobot.ESCAPE, monitor.getEstadoFsmActual()),
                () -> assertTrue(monitor.isModoEscapeActivo())
        );

        servidor.procesarComandoConsola("3"); // En reposo togglear PICKER
        assertTrue(monitor.isPickerManualActivo());
        assertEquals(EstadoRobot.CAPTURA, monitor.getEstadoFsmActual());

        servidor.procesarComandoConsola("p"); // Apagar picker con p
        assertFalse(monitor.isPickerManualActivo());

        servidor.procesarComandoConsola("k"); // Start corrida
        assertTrue(monitor.isCorridaActiva());
        servidor.procesarComandoConsola("3"); // En corrida fuerza CAPTURA en FSM
        assertAll(
                () -> assertEquals(EstadoRobot.CAPTURA, plan.getEstado()),
                () -> assertTrue(plan.isModoCapturaForzado()),
                () -> assertEquals(EstadoRobot.CAPTURA, monitor.getEstadoFsmActual())
        );

        servidor.procesarComandoConsola("1");
        assertAll(
                () -> assertEquals(EstadoRobot.BUSQUEDA, plan.getEstado()),
                () -> assertFalse(plan.isModoCapturaForzado()),
                () -> assertEquals(EstadoRobot.BUSQUEDA, monitor.getEstadoFsmActual()),
                () -> assertFalse(monitor.isModoEscapeActivo())
        );

        servidor.procesarComandoConsola("r");
        assertEquals(EstadoRobot.BUSQUEDA, monitor.getEstadoFsmActual());
    }
}
