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

        // Ajustar pasos de reversa desde TUI (> / . / < / , / rev <n>)
        int revInicial = monitor.getPasosReversa();
        servidor.procesarComandoConsola(">");
        assertEquals(revInicial + 1, monitor.getPasosReversa());
        assertEquals(revInicial + 1, plan.getPasosReversa());
        servidor.procesarComandoConsola(",");
        assertEquals(revInicial, monitor.getPasosReversa());
        servidor.procesarComandoConsola("rev 9");
        assertEquals(9, monitor.getPasosReversa());
        assertEquals(9, plan.getPasosReversa());

        // Flag --reverse-steps en ConfiguracionServidor
        ConfiguracionServidor cfgRev = ConfiguracionServidor.desdeArgumentos(new String[]{"--reverse-steps", "8"});
        assertEquals(8, cfgRev.getPasosReversa());

        servidor.procesarComandoConsola("r");
        assertEquals(EstadoRobot.BUSQUEDA, monitor.getEstadoFsmActual());
    }

    @Test
    public void testModoEntrenamientoGuiadoWizardPasoAPaso(@TempDir Path tempDir) {
        ConfiguracionServidor cfg = new ConfiguracionServidor();
        cfg.setNoTuiSolicitado(true);
        cfg.setRutaArchivoEstomago(tempDir.resolve("estomago_wizard.txt").toString());
        cfg.setRutaDatosReales(tempDir.resolve("real_wizard").toString());

        ServidorCentral servidor = new ServidorCentral(cfg);
        EstadoMonitorTui monitor = servidor.getMonitorTui();
        GrabadorDatasetReal grabador = servidor.getGrabadorDataset();
        AsistenteEntrenamientoGuiado wizard = servidor.getAsistenteGuiado();

        // Si el robot estaba en marcha, abrir wizard con [w] lo detiene por seguridad
        servidor.procesarComandoConsola("k");
        assertTrue(monitor.isCorridaActiva());

        servidor.procesarComandoConsola("w");
        assertFalse(monitor.isCorridaActiva(), "El wizard debe pasar el robot a REPOSO");
        assertTrue(wizard.isActivo());
        assertEquals(23, wizard.getTotalPasos());
        assertEquals(1, monitor.getEstadoWizard().pasoActual());
        assertEquals("BLOQUEO", grabador.getEtiquetaActiva());
        assertFalse(monitor.isBarridoEnReposo(), "Los pasos HC-SR04 deben centrar servos en 0 deg");
        assertFalse(grabador.isFiltroImpactoCanicaActivo());

        // Apretar ESPACIO inicia la captura del paso 1 (meta 25 muestras)
        servidor.procesarComandoConsola("space");
        assertTrue(grabador.isGrabando());
        assertTrue(monitor.getEstadoWizard().capturando());

        VectorSensores sWall = new VectorSensores(new double[]{5.5, 5.2, 5.8, 40.0, 41.0, 0.0}, 1000L);
        for (int i = 0; i < 25; i++) {
            assertTrue(grabador.registrarTelemetria(sWall));
        }

        // Al completar 25 muestras: detiene grabacion automaticamente y avanza al Paso 2
        assertFalse(grabador.isGrabando(), "Al llegar a la meta debe pausar la grabacion");
        assertFalse(monitor.isGrabacionActiva(), "EstadoMonitorTui debe reflejar pausa automatica");
        assertEquals(2, monitor.getEstadoWizard().pasoActual());
        assertEquals(1, monitor.getEstadoWizard().pasosHechos());
        assertFalse(monitor.getEstadoWizard().capturando());
        assertEquals(25, grabador.obtenerConteosPorClase().get("BLOQUEO"));

        // Navegar adelante (n) hasta Paso 9 (NINGUNA 65 muestras sin filtro) y volver al 8
        for (int i = 0; i < 7; i++) {
            servidor.procesarComandoConsola("n");
        }
        assertEquals(9, monitor.getEstadoWizard().pasoActual());
        assertEquals(65, monitor.getEstadoWizard().metaPaso());
        assertEquals("CANICAS", monitor.getEstadoWizard().red());
        assertEquals("NINGUNA", grabador.getEtiquetaActiva());
        assertFalse(grabador.isFiltroImpactoCanicaActivo());
        assertTrue(monitor.isBarridoEnReposo(), "Los pasos de canicas deben activar barrido espejo");

        servidor.procesarComandoConsola("a");
        assertEquals(8, monitor.getEstadoWizard().pasoActual());
        assertEquals("SALIDA", grabador.getEtiquetaActiva());
        assertFalse(monitor.isBarridoEnReposo());

        // Avanzar hasta Paso 18 (LOBITO_CENTRO, filtro activo, meta 12 impactos)
        for (int i = 0; i < 10; i++) {
            servidor.procesarComandoConsola("n");
        }
        assertEquals(18, monitor.getEstadoWizard().pasoActual());
        assertEquals("LOBITO_CENTRO", grabador.getEtiquetaActiva());
        assertEquals(12, monitor.getEstadoWizard().metaPaso());
        assertTrue(grabador.isFiltroImpactoCanicaActivo());

        servidor.procesarComandoConsola("space");
        // Angulos mirando al vacio (sin caida laser) deben descartarse automaticamente
        VectorSensores sVacio1 = new VectorSensores(new double[]{60.0, 58.0, 62.0, 48.0, 51.0, 20.0}, 2000L);
        VectorSensores sVacio2 = new VectorSensores(new double[]{60.0, 58.0, 62.0, 15.0, 51.0, 40.0}, 2050L); // > +10 deg en centro
        assertFalse(grabador.registrarTelemetria(sVacio1));
        assertFalse(grabador.registrarTelemetria(sVacio2));
        assertEquals(0, monitor.getEstadoWizard().muestrasPaso());
        assertEquals(2, monitor.getEstadoWizard().descartadasPaso());

        // Impacto real de canica (LSR-DER cae a 28.5 cm vs US ~45 cm en angulo -36 deg)
        VectorSensores sImpacto = new VectorSensores(new double[]{45.0, 44.5, 45.2, 41.0, 28.5, -36.0}, 2100L);
        for (int i = 0; i < 12; i++) {
            assertTrue(grabador.registrarTelemetria(sImpacto));
        }
        assertFalse(grabador.isGrabando(), "Al juntar 12 impactos reales debe frenar y pasar al Paso 19");
        assertEquals(19, monitor.getEstadoWizard().pasoActual());
        assertEquals(12, grabador.obtenerConteosPorClase().get("LOBITO_CENTRO"));

        // Avanzar hasta finalizar los 23 pasos -> entra en estado completado
        for (int i = 0; i < 5; i++) {
            servidor.procesarComandoConsola("n");
        }
        assertTrue(wizard.isCompletado());
        assertTrue(monitor.getEstadoWizard().completado());

        // Salir del wizard con [w] y reabrir con [w]: archiva sesion previa y resetea conteos a 0
        servidor.procesarComandoConsola("w");
        assertFalse(wizard.isActivo());
        assertFalse(grabador.isFiltroImpactoCanicaActivo());
        assertEquals(null, monitor.getEstadoWizard());

        servidor.procesarComandoConsola("w");
        assertTrue(wizard.isActivo());
        assertEquals(0, grabador.getTotalMuestrasUs(), "Al abrir wizard se debe archivar sesion previa US");
        assertEquals(0, grabador.getTotalMuestrasCanicas(), "Al abrir wizard se debe archivar sesion previa Canicas");
        assertTrue(tempDir.resolve("real_wizard").resolve("archivo").toFile().list().length >= 1);

        servidor.procesarComandoConsola("w");
        assertFalse(wizard.isActivo());
    }
}
