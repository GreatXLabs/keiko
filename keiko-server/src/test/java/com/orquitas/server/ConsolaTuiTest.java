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
        // Las letras de KEIKO deben aparecer en las primeras 5 filas junto a la aleta dorsal
        assertTrue(banner[0].contains("______ __    ___________"));
        assertTrue(banner[0].contains("⣸⣧"));
        assertTrue(banner[4].contains("/_/ |_|"));
        // El cuerpo de la orca aparece debajo de las letras
        assertTrue(banner[13].contains("⣰⣿⠁"));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ConsolaTui.imprimirBannerPlano(new PrintStream(baos, true, StandardCharsets.UTF_8), "2.0.0", "mega-3us-2laser");
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

        // 11 paquetes espaciados por 50ms -> exactamente 20.0 Hz
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
    public void testRenderizadoDetalladoYCompactoConSensoresYLogs() {
        EstadoMonitorTui estado = crearEstadoBase();
        estado.registrarConexionCliente("Serie:/dev/rfcomm0@9600", 1);
        estado.registrarHandshake("Serie:/dev/rfcomm0@9600", "2.0.0", "mega-3us-2laser", true);
        estado.setEscalaVelocidad(0.60);
        estado.setRangoPwm(90, 210);
        estado.registrarDebugCliente("DBG:estomago=42 base=45 det=2 cambios=1");
        estado.registrarEvento("CAPTURA_LOBITO_1");
        estado.actualizarEstomago(1, List.of(new RegistroCaptura("lobito_ingesta", "CAPTURA_LOBITO_1")), false);

        VectorSensores s = new VectorSensores(new double[]{14.2, 130.5, 65.0, 18.4, 999.0, 12.5}, 5000L);
        estado.registrarTelemetria(
                s,
                new String[]{"BLOQUEO", "SALIDA", "LOBITO"},
                new double[]{0.97, 0.91, 0.88},
                "LOBITO_DER",
                0.93,
                new ComandoRobot(3.8, 1.8, EstadoRobot.APROXIMACION),
                42,
                5000L);

        String detallado = ConsolaTui.renderizarFrame(estado, 5040L, false);
        String plano = detallado.replaceAll("\033\\[[0-9;]*m", "");
        assertFalse(plano.contains("TCP:"), "No debe mostrar transporte TCP en modo Bluetooth exclusivo");
        assertTrue(plano.contains("BT /dev/rfcomm0 @ 9600"));
        assertTrue(plano.contains("Vel: 60%"));
        assertTrue(plano.contains("PWM: 90..210"));
        assertTrue(plano.contains("LSR-EST"));
        assertTrue(plano.contains("42mm"));
        assertTrue(plano.contains("ARM"));
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

        // Verificar que las lineas de las cajas inferiores tengan ancho visual consistente de 112 columnas
        String[] lineas = detallado.split("\n");
        for (int i = 18; i < lineas.length - 1; i++) {
            assertEquals(112, ConsolaTui.longitudVisual(lineas[i]),
                    "Ancho visual desalineado en linea " + i + ": " + lineas[i]);
        }

        String compacto = ConsolaTui.renderizarFrame(estado, 5040L, true);
        assertTrue(compacto.contains("KEIKO TUI (COMPACTA)"));
        assertTrue(compacto.contains("BT:/dev/rfcomm0@9600"));
        assertFalse(compacto.contains("TCP:"), "La vista compacta tampoco debe mostrar TCP");
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
        estado.registrarResetPrueba();
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
            estado.agregarLog("INFO", "Evento de prueba numero " + i);
        }
        VectorSensores s = new VectorSensores(new double[]{25.0, 95.0, 45.0, 14.0, 999.0, -5.0}, 5000L);
        estado.registrarTelemetria(
                s,
                new String[]{"ESPACIO_LIBRE", "ESPACIO_LIBRE", "ESPACIO_LIBRE"},
                new double[]{0.95, 0.96, 0.92},
                "LOBITO_CENTRO",
                0.91,
                new ComandoRobot(3.0, 3.0, EstadoRobot.APROXIMACION),
                10,
                5000L);

        String ancho = ConsolaTui.renderizarFrame(estado, 5050L, false, 150, 44);
        String[] lineas = ancho.split("\n");
        for (int i = 18; i < lineas.length - 1; i++) {
            assertEquals(150, ConsolaTui.longitudVisual(lineas[i]),
                    "Linea expandida debe medir 150 cols en " + i);
        }
        assertTrue(ancho.contains("Evento de prueba numero 11"));
        assertTrue(ancho.contains("Evento de prueba numero 2"), "Con terminal alta debe mostrar mas filas de logs");

        String compactoAncho = ConsolaTui.renderizarFrame(estado, 5050L, true, 136, 30);
        String[] lineasComp = compactoAncho.split("\n");
        for (int i = 0; i < lineasComp.length - 1; i++) {
            assertEquals(136, ConsolaTui.longitudVisual(lineasComp[i]),
                    "Linea compacta expandida debe medir 136 cols en " + i);
        }
    }
}
