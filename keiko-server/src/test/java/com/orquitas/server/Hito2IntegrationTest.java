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
    private GestorEstomago estomagoManager;

    @BeforeEach
    public void setUp() throws IOException {
        tempEstomago = File.createTempFile("estomago_hito2_test_", ".txt");
        if (tempEstomago.exists()) tempEstomago.delete();
        estomagoManager = new GestorEstomago(tempEstomago);
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
        auditor.agregarOyente(new OyenteAuditor() {
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

        // Verificar que el auditor detecta el cambio de estado fisicamente
        boolean activado = escapeLatch.await(1, TimeUnit.SECONDS);
        assertTrue(activado, "El AuditorEstomago debe activar Modo Escape tras verificar 3 registros fisicos en disco");
        assertTrue(auditor.isModoEscapeActivado());

        auditor.detener();
    }

    @Test
    public void testParseoTelemetriaInferenciaNeurophYComandos() {
        String trama = "TELEMETRY:115.0,16.0,118.0,116.0,122.0,119.0,1000\n";
        VectorSensores s = MensajeProtocolo.parsearTelemetria(trama);
        assertEquals(6, s.getCantidadSensores());
        assertEquals(16.0, s.getDLowCenter(), 1e-4);
        assertEquals(122.0, s.getDHighCenter(), 1e-4);
        assertTrue(s.isValido());

        ClasificadorNeuroph clasificador = new ClasificadorNeuroph(new ConfiguracionServidor().resolverArchivoModelo());
        String clase = clasificador.clasificar(s);
        assertTrue(clase.startsWith("LOBITO"), "La red Neuroph 2.98 debe clasificar como LOBITO, obtuvo: " + clase);

        PlanificadorNavegacion planner = new PlanificadorNavegacion(estomagoManager);
        ComandoRobot cmd = planner.evaluar(s, clase);
        assertEquals(EstadoRobot.APROXIMACION, cmd.getEstado());

        String cmdStr = MensajeProtocolo.crearComando(cmd);
        assertTrue(cmdStr.startsWith("COMMAND:"));
        assertTrue(cmdStr.contains("APROXIMACION"));
    }
}
