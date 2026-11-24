package com.orquitas.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de parseo defensivo del protocolo serie/Bluetooth.
 */
public class ProtocolParserTest {

    @Test
    public void testParseValidTelemetry4Sensors() {
        String msg = "TELEMETRY:23.45,12.80,150.20,145.10,1725984000\n";
        VectorSensores sv = MensajeProtocolo.parsearTelemetria(msg);

        assertEquals(4, sv.getCantidadSensores());
        assertEquals(23.45, sv.getDLowLeft(), 1e-4);
        assertEquals(12.80, sv.getDLowRight(), 1e-4);
        assertEquals(150.20, sv.getDHighLeft(), 1e-4);
        assertEquals(145.10, sv.getDHighRight(), 1e-4);
        assertTrue(sv.isValido());
    }

    @Test
    public void testParseValid6SensorTelemetry() {
        // 6 sensores + ts (sin pose)
        String msg = "TELEMETRY:23.45,18.10,12.80,150.20,148.00,145.10,1725984000\n";
        VectorSensores sv = MensajeProtocolo.parsearTelemetria(msg);

        assertEquals(6, sv.getCantidadSensores());
        assertEquals(23.45, sv.getDLowLeft(), 1e-4);
        assertEquals(18.10, sv.getDLowCenter(), 1e-4);
        assertEquals(12.80, sv.getDLowRight(), 1e-4);
        assertEquals(150.20, sv.getDHighLeft(), 1e-4);
        assertEquals(148.00, sv.getDHighCenter(), 1e-4);
        assertEquals(145.10, sv.getDHighRight(), 1e-4);
        assertEquals(1725984000L, sv.getTimestamp());
        assertTrue(sv.isValido());
    }

    @Test
    public void testParseValid8SensorTelemetry() {
        // 8 sensores + ts (sin pose)
        String msg = "TELEMETRY:23.45,12.80,150.20,145.10,80.50,75.30,95.0,92.0,1725984000\n";
        VectorSensores sv = MensajeProtocolo.parsearTelemetria(msg);

        assertEquals(8, sv.getCantidadSensores());
        assertEquals(1725984000L, sv.getTimestamp());
        assertTrue(sv.isValido());
    }

    @Test
    public void testRejectsTelemetryWithLegacyPosePayload() {
        // 12 campos: 8 sensores + ts + pose (x, y, theta)
        String msg12 = "TELEMETRY:23.45,12.80,150.20,145.10,80.50,75.30,95.0,92.0,1725984000,0.15,0.25,1.57\n";
        // 14 campos: 10 sensores + ts + pose (x, y, theta)
        String msg14 = "TELEMETRY:23.45,12.80,150.20,145.10,80.50,75.30,95.0,92.0,60.0,65.0,1725984000,0.15,0.25,1.57\n";

        assertThrows(IllegalArgumentException.class, () -> MensajeProtocolo.parsearTelemetria(msg12));
        assertThrows(IllegalArgumentException.class, () -> MensajeProtocolo.parsearTelemetria(msg14));
    }

    @Test
    public void testParseMalformedTelemetryThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> MensajeProtocolo.parsearTelemetria("CORRUPTED_DATA\n"));
        assertThrows(IllegalArgumentException.class, () -> MensajeProtocolo.parsearTelemetria("TELEMETRY:12.3,45.6\n"));
        assertThrows(IllegalArgumentException.class, () -> MensajeProtocolo.parsearTelemetria("TELEMETRY:abc,def,ghi,jkl\n"));
    }

    @Test
    public void testFormatCommand() {
        ComandoRobot cmd = new ComandoRobot(5.0, 4.5, EstadoRobot.BUSQUEDA);
        String formatted = MensajeProtocolo.crearComando(cmd);

        assertEquals("COMMAND:5.00,4.50,BUSQUEDA\n", formatted);
    }

    @Test
    public void testFormatHeartbeat() {
        assertEquals("HEARTBEAT\n", MensajeProtocolo.crearHeartbeat());
    }

    @Test
    public void parsearHelloValidoYCompatibilidad() {
        MensajeProtocolo.InfoHandshake info = MensajeProtocolo.parsearHello("HELLO:2.0.0-audit.1,mega-3us-2laser");
        assertEquals("2.0.0-audit.1", info.version());
        assertEquals("mega-3us-2laser", info.rig());
        assertEquals("HELLO:2.0.0-audit.1,mega-3us-2laser\n", MensajeProtocolo.crearHello("2.0.0-audit.1", "mega-3us-2laser"));
        assertTrue(MensajeProtocolo.esCompatibleMajorMinor("2.0.0-audit.1", "2.0.3"));
        assertFalse(MensajeProtocolo.esCompatibleMajorMinor("2.0.0-audit.1", "2.1.0"));
        assertFalse(MensajeProtocolo.esCompatibleMajorMinor("2.0.0-audit.1", "1.0.0"));
        assertFalse(MensajeProtocolo.esCompatibleMajorMinor(null, "2.0.0"));
    }

    @Test
    public void parsearHelloInvalidoLanzaExcepcion() {
        assertThrows(IllegalArgumentException.class, () -> MensajeProtocolo.parsearHello("HELLO:"));
        assertThrows(IllegalArgumentException.class, () -> MensajeProtocolo.parsearHello("TELEMETRY:1,2,3,4"));
    }

    @Test
    public void formatControlCommandsStartStopYPrefijoDbg() {
        assertEquals("CMD:START\n", MensajeProtocolo.crearCmdStart());
        assertEquals("CMD:STOP\n", MensajeProtocolo.crearCmdStop());
        assertEquals("DBG:", MensajeProtocolo.PREFIX_DBG);
        assertEquals("CMD:PWM:85,200\n", MensajeProtocolo.crearCmdPwm(85, 200));
        assertEquals("CMD:PWM:0,255\n", MensajeProtocolo.crearCmdPwm(-10, 300));
        assertEquals("LASER:SWEEP\n", MensajeProtocolo.crearCmdLaser("SWEEP"));
        assertEquals("STATE_CHANGE:BUSQUEDA\n", MensajeProtocolo.crearEstadoCambio(EstadoRobot.BUSQUEDA));
        assertEquals("STATE_CHANGE:CAPTURA\n", MensajeProtocolo.crearEstadoCambio(EstadoRobot.CAPTURA));
        assertEquals("STATE_CHANGE:ESCAPE\n", MensajeProtocolo.crearModoEscape());
        assertEquals("PICKER:ON\n", MensajeProtocolo.crearPicker(true));
        assertEquals("PICKER:OFF\n", MensajeProtocolo.crearPicker(false));

        MensajeProtocolo.InfoDbgEstomago dbg =
                MensajeProtocolo.parsearDbgEstomago("DBG:estomago=42 base=45 det=2 cambios=1");
        assertNotNull(dbg);
        assertEquals(42, dbg.estomagoMm());
        assertEquals(45, dbg.baseMm());
        assertEquals(2, dbg.detector());
        assertEquals(1, dbg.cambios());
        assertNull(MensajeProtocolo.parsearDbgEstomago("DBG:otro=1"));

        // Trama truncada a 6 campos (faltando 1 sensor) debe rechazarse en validacion estricta mega-3us-2laser
        assertThrows(IllegalArgumentException.class,
                () -> MensajeProtocolo.parsearTelemetria("TELEMETRY:20.7,24.7,3,38.8,0,130284", 6, true));
        VectorSensores estricto = MensajeProtocolo.parsearTelemetria("TELEMETRY:20.7,24.7,35.0,999.0,38.8,0,130284", 6, true);
        assertEquals(6, estricto.getCantidadSensores());
        assertEquals(130284L, estricto.getTimestamp());
    }
}
