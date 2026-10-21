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
        // Orden trisector_front_6: LL, LC, LR, HL, HC, HR + ts + x, y, theta
        String msg = "TELEMETRY:23.45,18.10,12.80,150.20,148.00,145.10,1725984000,0.15,0.25,1.57\n";
        VectorSensores sv = MensajeProtocolo.parsearTelemetria(msg);

        assertEquals(6, sv.getCantidadSensores());
        assertEquals(23.45, sv.getDLowLeft(), 1e-4);
        assertEquals(18.10, sv.getDLowCenter(), 1e-4);
        assertEquals(12.80, sv.getDLowRight(), 1e-4);
        assertEquals(150.20, sv.getDHighLeft(), 1e-4);
        assertEquals(148.00, sv.getDHighCenter(), 1e-4);
        assertEquals(145.10, sv.getDHighRight(), 1e-4);
        assertTrue(sv.hasPose());
        assertEquals(0.15, sv.getPosX(), 1e-4);
        assertEquals(0.25, sv.getPosY(), 1e-4);
        assertTrue(sv.isValido());
    }

    @Test
    public void testParseValid8SensorTelemetry() {
        String msg = "TELEMETRY:23.45,12.80,150.20,145.10,80.50,75.30,95.0,92.0,1725984000,0.15,0.25,1.57\n";
        VectorSensores sv = MensajeProtocolo.parsearTelemetria(msg);

        assertEquals(8, sv.getCantidadSensores());
        assertTrue(sv.isValido());
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
}
