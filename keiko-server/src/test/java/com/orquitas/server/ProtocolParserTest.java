package com.orquitas.server;

import com.orquitas.server.model.RobotCommand;
import com.orquitas.server.model.RobotState;
import com.orquitas.server.model.SensorVector;
import com.orquitas.server.network.Protocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de parseo defensivo del protocolo serie/Bluetooth.
 */
public class ProtocolParserTest {

    @Test
    public void testParseValidTelemetry() {
        String msg = "TELEMETRY:23.45,12.80,150.20,145.10,1725984000\n";
        SensorVector sv = Protocol.parseTelemetry(msg);

        assertEquals(23.45, sv.distLowLeft(), 1e-4);
        assertEquals(12.80, sv.distLowRight(), 1e-4);
        assertEquals(150.20, sv.distHighLeft(), 1e-4);
        assertEquals(145.10, sv.distHighRight(), 1e-4);
        assertTrue(sv.isValid());
    }

    @Test
    public void testParseValid6SensorTelemetry() {
        String msg = "TELEMETRY:23.45,12.80,150.20,145.10,80.50,75.30,1725984000,0.15,0.25,1.57\n";
        SensorVector sv = Protocol.parseTelemetry(msg);

        assertEquals(23.45, sv.distLowLeft(), 1e-4);
        assertEquals(12.80, sv.distLowRight(), 1e-4);
        assertEquals(150.20, sv.distHighLeft(), 1e-4);
        assertEquals(145.10, sv.distHighRight(), 1e-4);
        assertEquals(80.50, sv.distSideLeft(), 1e-4);
        assertEquals(75.30, sv.distSideRight(), 1e-4);
        assertEquals(6, sv.sensorCount());
        assertTrue(sv.hasPose());
        assertEquals(0.15, sv.posX(), 1e-4);
        assertEquals(0.25, sv.posY(), 1e-4);
        assertTrue(sv.isValid());
    }

    @Test
    public void testParseValid8SensorTelemetry() {
        String msg = "TELEMETRY:23.45,12.80,150.20,145.10,80.50,75.30,95.0,92.0,1725984000,0.15,0.25,1.57\n";
        SensorVector sv = Protocol.parseTelemetry(msg);

        assertEquals(80.50, sv.distSideLeft(), 1e-4);
        assertEquals(75.30, sv.distSideRight(), 1e-4);
        assertEquals(95.0, sv.distRearLeft(), 1e-4);
        assertEquals(92.0, sv.distRearRight(), 1e-4);
        assertEquals(8, sv.sensorCount());
        assertTrue(sv.isValid());
    }

    @Test
    public void testParseMalformedTelemetryThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> Protocol.parseTelemetry("CORRUPTED_DATA\n"));
        assertThrows(IllegalArgumentException.class, () -> Protocol.parseTelemetry("TELEMETRY:12.3,45.6\n"));
        assertThrows(IllegalArgumentException.class, () -> Protocol.parseTelemetry("TELEMETRY:abc,def,ghi,jkl\n"));
    }

    @Test
    public void testFormatCommand() {
        RobotCommand cmd = new RobotCommand(5.0, 4.5, RobotState.BUSQUEDA, "CRUISE");
        String formatted = Protocol.formatCommand(cmd);

        assertEquals("COMMAND:5.00,4.50,BUSQUEDA\n", formatted);
    }

    @Test
    public void testFormatHeartbeat() {
        assertEquals("HEARTBEAT\n", Protocol.formatHeartbeat());
    }
}
