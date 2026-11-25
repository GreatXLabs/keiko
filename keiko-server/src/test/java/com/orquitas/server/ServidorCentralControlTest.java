package com.orquitas.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias del control operativo permitido en competencia (start/stop/reset seguro/clear).
 */
public class ServidorCentralControlTest {

    @Test
    public void testControlesDeCompetenciaYBloqueoDeDebugYResetEnCorrida(@TempDir Path tempDir) throws Exception {
        ConfiguracionServidor cfg = new ConfiguracionServidor();
        cfg.setNoTuiSolicitado(true);
        cfg.setRutaArchivoEstomago(tempDir.resolve("estomago_test.txt").toString());

        ServidorCentral servidor = new ServidorCentral(cfg);
        EstadoMonitorTui monitor = servidor.getMonitorTui();
        PlanificadorNavegacion plan = servidor.getPlanificador();
        GestorEstomago gestor = servidor.getGestorEstomago();

        // Iniciar y detener corrida con k / x
        assertFalse(monitor.isCorridaActiva());
        servidor.procesarComandoConsola("k");
        assertTrue(monitor.isCorridaActiva());

        // Teclas de debug/forzado de estados no existen ni modifican estado
        servidor.procesarComandoConsola("2");
        servidor.procesarComandoConsola("3");
        servidor.procesarComandoConsola("w");
        servidor.procesarComandoConsola("p");
        assertEquals(EstadoRobot.BUSQUEDA, plan.getEstado());

        // Reset bloqueado mientras la corrida esta activa
        gestor.registrarCaptura("CAP_TEST");
        assertEquals(1, gestor.contarCapturasValidas());
        servidor.procesarComandoConsola("r");
        assertEquals(1, gestor.contarCapturasValidas(), "No debe vaciar estomago.txt durante corrida activa");

        // Detener con x y reiniciar en reposo con r
        servidor.procesarComandoConsola("x");
        assertFalse(monitor.isCorridaActiva());
        servidor.procesarComandoConsola("r");
        assertEquals(0, gestor.contarCapturasValidas());
        assertEquals(EstadoRobot.BUSQUEDA, monitor.getEstadoFsmActual());
    }
}
