package com.orquitas.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class Hito2IntegrationTest {

    private File tempEstomago;
    private EstomagoManager estomagoManager;

    @BeforeEach
    public void setUp() throws IOException {
        tempEstomago = File.createTempFile("estomago_hito2_test_", ".txt");
        if (tempEstomago.exists()) tempEstomago.delete();
        estomagoManager = new EstomagoManager(tempEstomago);
    }

    @AfterEach
    public void tearDown() {
        if (tempEstomago != null && tempEstomago.exists()) {
            tempEstomago.delete();
        }
    }

    @Test
    public void testPersistenciaConcurrenteYAuditoriaEscape() throws Exception {
        CountDownLatch escapeLatch = new CountDownLatch(1);

        AuditorEstomago auditor = new AuditorEstomago(estomagoManager, 50, 3);
        auditor.agregarListener(new AuditorListener() {
            @Override
            public void onCapturaAuditada(int total) {}

            @Override
            public void onModoEscapeActivado(List<RegistroCaptura> capturas) {
                escapeLatch.countDown();
            }
        });
        auditor.iniciar();

        assertEquals(0, estomagoManager.contarCapturasValidas());

        // Grabar 1
        estomagoManager.registrarCaptura("test_1");
        assertEquals(1, estomagoManager.contarCapturasValidas());

        // Grabar 2
        estomagoManager.registrarCaptura("test_2");
        assertEquals(2, estomagoManager.contarCapturasValidas());

        assertFalse(escapeLatch.await(150, TimeUnit.MILLISECONDS), "No debe activar escape antes de 3 capturas");

        // Grabar 3
        estomagoManager.registrarCaptura("test_3");
        assertEquals(3, estomagoManager.contarCapturasValidas());

        // Verificar que el auditor detecta el cambio de estado físicamente
        boolean activado = escapeLatch.await(1, TimeUnit.SECONDS);
        assertTrue(activado, "El AuditorEstomago debe activar Modo Escape tras verificar 3 registros físicos en disco");
        assertTrue(auditor.isModoEscapeActivado());

        auditor.detener();
    }

    @Test
    public void testParseoTelemetriaYComandos() {
        String trama = "TELEMETRY:15.0,16.0,180.0,190.0,1000\n";
        VectorSensores s = MensajeProtocolo.parsearTelemetria(trama);
        assertEquals(15.0, s.getDLowLeft());
        assertEquals(16.0, s.getDLowRight());
        assertEquals(180.0, s.getDHighLeft());
        assertEquals(190.0, s.getDHighRight());
        assertTrue(s.isValido());

        String clase = ClasificadorNeuroph.inferenciaReglas(s);
        assertEquals("LOBITO", clase);

        PlanificadorNavegacion planner = new PlanificadorNavegacion(estomagoManager);
        ComandoRobot cmd = planner.evaluar(s, clase);
        assertEquals(EstadoRobot.APROXIMACION, cmd.getEstado());

        String cmdStr = MensajeProtocolo.crearComando(cmd);
        assertTrue(cmdStr.startsWith("COMMAND:"));
        assertTrue(cmdStr.contains("APROXIMACION"));
    }
}
