package com.orquitas.server;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ConsolaTuiTest {

    private EstadoMonitorTui crearEstadoBase() {
        ConfiguracionServidor cfg = ConfiguracionServidor.desdeArgumentos(new String[]{"--serial", "/dev/rfcomm0", "--baud", "9600"});
        return new EstadoMonitorTui(cfg, null, 0, List.of());
    }

    @Test
    public void testBannerFusionadoTieneKeikoArribaDeLaOrca() {
        String[] banner = ConsolaTui.construirBannerFusionadoPlano();
        assertEquals(18, banner.length);
        for (String linea : banner) {
            assertEquals(64, linea.length(), "Cada linea del banner fusionado debe medir exactamente 64 columnas");
        }
        assertTrue(banner[0].contains("______ __    ___________"));
        assertTrue(banner[0].contains("⣸⣧"));
        assertTrue(banner[4].contains("/_/ |_|"));
        assertTrue(banner[13].contains("⣰⣿⠁"));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ConsolaTui.imprimirBannerPlano(new PrintStream(baos, true, StandardCharsets.UTF_8),
                ConfiguracionServidor.VERSION_SISTEMA, ConfiguracionServidor.RIG_HARDWARE);
        String salida = baos.toString(StandardCharsets.UTF_8);
        assertTrue(salida.contains("PROYECTO ORQUITAS (KEIKO)"));
        assertTrue(salida.contains("mega-3us-2laser"));
        assertTrue(salida.contains("Bluetooth"));
    }

    @Test
    public void testCalculoFrecuenciaHzYFailSafe() {
        EstadoMonitorTui estado = crearEstadoBase();
        assertEquals(EstadoMonitorTui.EstadoFailSafe.SIN_ENLACE, estado.obtenerEstadoFailSafe(1000L));
        assertEquals(0.0, estado.calcularFrecuenciaHz(1000L), 0.01);

        estado.registrarConexionCliente("Serie:/dev/rfcomm0@9600", 1);
        VectorSensores s = new VectorSensores(new double[]{50.0, 120.0, 30.0, 22.0, 999.0, -15.0}, 1000L);
        String[] terna = new String[]{"ESPACIO_LIBRE", "SALIDA", "BLOQUEO"};
        ComandoRobot cmd = new ComandoRobot(4.2, 2.0, EstadoRobot.BUSQUEDA);

        for (int i = 0; i <= 10; i++) {
            estado.registrarTelemetria(s, terna, new double[]{0.9, 0.85, 0.95}, "NINGUNA", 0.98, cmd, i + 1, 1000L + i * 50L);
        }

        assertEquals(20.0, estado.calcularFrecuenciaHz(1520L), 0.1);
        assertEquals(EstadoMonitorTui.EstadoFailSafe.OK, estado.obtenerEstadoFailSafe(1700L));
        assertEquals(EstadoMonitorTui.EstadoFailSafe.ALERTA, estado.obtenerEstadoFailSafe(1860L));
        assertEquals(EstadoMonitorTui.EstadoFailSafe.TIMEOUT, estado.obtenerEstadoFailSafe(2000L));
        assertEquals(0.0, estado.calcularFrecuenciaHz(3500L), 0.01);
    }

    @Test
    public void testRenderizadoDetalladoYCompactoSoloLecturaOperativa() {
        EstadoMonitorTui estado = crearEstadoBase();
        estado.registrarConexionCliente("Serie:/dev/rfcomm0@9600", 1);
        estado.registrarHandshake("Serie:/dev/rfcomm0@9600", ConfiguracionServidor.VERSION_SISTEMA, "mega-3us-2laser", true);
        estado.registrarEvento("CAPTURA_LOBITO_1");
        estado.actualizarEstomago(1, List.of(new RegistroCaptura("lobito_ingesta", "CAPTURA_LOBITO_1")), false);

        VectorSensores s = new VectorSensores(new double[]{14.2, 130.5, 65.0, 18.4, 999.0, 12.5}, 5000L);
        estado.registrarTelemetria(
                s,
                new String[]{"BLOQUEO", "SALIDA", "LOBITO"},
                new double[]{0.97, 0.91, 0.88},
                "LOBITO_DER",
                0.93,
                new ComandoRobot(1.5, 0.7, EstadoRobot.APROXIMACION),
                42,
                5000L);

        String detallado = ConsolaTui.renderizarFrame(estado, 5040L, false);
        String plano = detallado.replaceAll("\033\\[[0-9;]*m", "");
        assertFalse(plano.contains("TCP:"), "No debe mostrar transporte TCP en modo Bluetooth exclusivo");
        assertFalse(plano.contains("ASISTENTE GUIADO"), "La entrega final no debe incluir panel de entrenamiento");
        assertFalse(plano.contains("CAPTURA REAL"), "La entrega final no debe incluir grabador de dataset");
        assertTrue(plano.contains("BT /dev/rfcomm0 @ 9600"));
        assertTrue(plano.contains("Vel: 40%"));
        assertTrue(plano.contains("PWM:105..255"));
        assertTrue(plano.contains("US-IZQ"));
        assertTrue(plano.contains("14.2cm"));
        assertTrue(plano.contains("97%"));
        assertTrue(plano.contains("LSR-IZQ"));
        assertTrue(plano.contains("18.4 cm"));
        assertTrue(plano.contains("TIMEOUT"));
        assertTrue(plano.contains("+12.5"));
        assertTrue(plano.contains("LOBITO_DER"));
        assertTrue(plano.contains("TERNA: [BLOQUEO|SALIDA|LOBITO]"));
        assertTrue(plano.contains("APROXIMACION"));
        assertTrue(plano.contains("CAPTURA_LOBITO_1"));
        assertTrue(plano.contains("FS:OK"));

        String[] lineas = detallado.split("\n");
        for (int i = 18; i < lineas.length - 1; i++) {
            assertEquals(106, ConsolaTui.longitudVisual(lineas[i]),
                    "Ancho visual desalineado en linea " + i + ": " + lineas[i]);
        }

        String compacto = ConsolaTui.renderizarFrame(estado, 5040L, true);
        assertTrue(compacto.contains("KEIKO TUI (COMPACTA)"));
        assertTrue(compacto.contains("BT:/dev/rfcomm0@9600"));
        assertFalse(compacto.contains("TCP:"));
        assertTrue(compacto.contains("LOBITO_DER"));
        String[] lineasCompactas = compacto.split("\n");
        for (int i = 0; i < lineasCompactas.length - 1; i++) {
            assertEquals(92, ConsolaTui.longitudVisual(lineasCompactas[i]),
                    "Ancho visual compacto desalineado en linea " + i);
        }
    }

    @Test
    public void testResetYLimpiezaDeLogs() {
        EstadoMonitorTui estado = crearEstadoBase();
        estado.agregarLog("INFO", "Prueba 1");
        estado.agregarLog("WARN", "Prueba 2");
        assertEquals(2, estado.obtenerLogsRecientes(10).size());

        estado.limpiarLogs();
        assertTrue(estado.obtenerLogsRecientes(10).isEmpty());

        estado.actualizarEstomago(3, List.of(), true);
        assertTrue(estado.isModoEscapeActivo());
        estado.registrarReset();
        assertFalse(estado.isModoEscapeActivo());
        assertEquals(0, estado.getCapturasEstomago());
        assertEquals(EstadoRobot.BUSQUEDA, estado.getEstadoFsmActual());
    }

    @Test
    public void testRenderizadoFluidoAnchoCompletoYParseoStty() {
        int[] tam = ConsolaTui.parsearTamanoStty("42 150");
        assertArrayEquals(new int[]{150, 42}, tam);
        assertNull(ConsolaTui.parsearTamanoStty("invalido"));

        EstadoMonitorTui estado = crearEstadoBase();
        estado.registrarConexionCliente("Serie:/dev/rfcomm0@9600", 1);
        for (int i = 0; i < 12; i++) {
            estado.agregarLog("INFO", "Evento numero " + i);
        }
        VectorSensores s = new VectorSensores(new double[]{25.0, 95.0, 45.0, 14.0, 999.0, -5.0}, 5000L);
        estado.registrarTelemetria(
                s,
                new String[]{"ESPACIO_LIBRE", "ESPACIO_LIBRE", "ESPACIO_LIBRE"},
                new double[]{0.95, 0.96, 0.92},
                "LOBITO_CENTRO",
                0.91,
                new ComandoRobot(1.2, 1.2, EstadoRobot.APROXIMACION),
                10,
                5000L);

        String ancho = ConsolaTui.renderizarFrame(estado, 5050L, false, 150, 44);
        String[] lineas = ancho.split("\n");
        for (int i = 18; i < lineas.length - 1; i++) {
            assertEquals(150, ConsolaTui.longitudVisual(lineas[i]),
                    "Linea expandida debe medir 150 cols en " + i);
        }
        assertTrue(ancho.contains("Evento numero 11"));
        assertTrue(ancho.contains("Evento numero 2"));

        String compactoAncho = ConsolaTui.renderizarFrame(estado, 5050L, true, 136, 30);
        String[] lineasComp = compactoAncho.split("\n");
        for (int i = 0; i < lineasComp.length - 1; i++) {
            assertEquals(136, ConsolaTui.longitudVisual(lineasComp[i]),
                    "Linea compacta expandida debe medir 136 cols en " + i);
        }

        for (int[] dim : new int[][]{{106, 34}, {120, 39}, {150, 44}, {95, 28}, {84, 24}}) {
            String f = ConsolaTui.renderizarFrame(estado, 5050L, false, dim[0], dim[1]);
            String[] rows = f.split("\n", -1);
            assertTrue(rows.length <= dim[1] - 1,
                    String.format("Desborde vertical en %dx%d: %d filas (max %d)", dim[0], dim[1], rows.length, dim[1] - 1));
            for (int r = 0; r < rows.length; r++) {
                assertTrue(ConsolaTui.longitudVisual(rows[r]) <= dim[0],
                        String.format("Desborde horizontal en %dx%d fila %d: %d cols", dim[0], dim[1], r, ConsolaTui.longitudVisual(rows[r])));
            }
        }
    }
}
