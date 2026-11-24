package com.orquitas.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias de PR-C: captura de datasets reales (incluyendo 999.0 sin eco),
 * reentrenamiento Neuroph 2.98, historial inmutable y rollback en caliente.
 */
public class RealDatasetAndRetrainTest {

    private static VectorSensores v(double usL, double usC, double usR, double lL, double lR, double ang, long ts) {
        return new VectorSensores(new double[]{usL, usC, usR, lL, lR, ang}, ts);
    }

    @Test
    public void testGrabadorDatasetRealPreserva999YConteos(@TempDir Path tempDir) throws Exception {
        GrabadorDatasetReal grabador = new GrabadorDatasetReal(tempDir.toFile());
        assertEquals("CANICAS", grabador.getRedObjetivo().name());
        assertEquals("LOBITO_IZQ", grabador.getEtiquetaActiva());

        // No graba mientras esta en pausa
        VectorSensores vSinEco = v(25.0, 220.0, 999.0, 999.0, 14.5, -12.0, 1000L);
        assertFalse(grabador.registrarTelemetria(vSinEco));

        // Activar grabacion Canicas con 999.0 sin eco en laser izquierdo
        assertTrue(grabador.alternarGrabacion());
        assertTrue(grabador.registrarTelemetria(vSinEco));

        assertEquals("LOBITO_CENTRO", grabador.ciclarModoCanicas());
        assertTrue(grabador.registrarTelemetria(v(60.0, 62.0, 64.0, 18.0, 18.2, 2.0, 1100L)));

        // Cambiar a modo US y registrar SALIDA con 999.0 sin eco en centro
        assertEquals("BLOQUEO", grabador.ciclarModoUs());
        assertEquals("ESPACIO_LIBRE", grabador.ciclarModoUs());
        assertEquals("SALIDA", grabador.ciclarModoUs());
        assertTrue(grabador.registrarTelemetria(vSinEco));

        List<String> lineasUs = Files.readAllLines(grabador.getArchivoUltrasonico().toPath(), StandardCharsets.UTF_8);
        List<String> lineasCan = Files.readAllLines(grabador.getArchivoCanicas().toPath(), StandardCharsets.UTF_8);

        assertEquals(GrabadorDatasetReal.HEADER_US, lineasUs.get(0));
        assertTrue(lineasUs.get(1).contains("220.00,SALIDA"));

        assertEquals(GrabadorDatasetReal.HEADER_CANICAS, lineasCan.get(0));
        assertTrue(lineasCan.get(1).contains("999.00,999.0,14.5,-12.0,LOBITO_IZQ"));

        // Verificar persistencia de conteos al reabrir
        GrabadorDatasetReal recargado = new GrabadorDatasetReal(tempDir.toFile());
        Map<String, Integer> conteos = recargado.obtenerConteosPorClase();
        assertAll(
                () -> assertEquals(1, conteos.get("LOBITO_IZQ")),
                () -> assertEquals(1, conteos.get("LOBITO_CENTRO")),
                () -> assertEquals(1, conteos.get("SALIDA")),
                () -> assertEquals(1, recargado.getTotalMuestrasUs()),
                () -> assertEquals(2, recargado.getTotalMuestrasCanicas())
        );
    }

    @Test
    public void testReentrenamientoHistorialYRollbackEnCaliente(@TempDir Path tempDir) throws Exception {
        Path dirModelos = tempDir.resolve("models");
        Path dirReal = tempDir.resolve("real");
        Files.createDirectories(dirModelos);
        Files.createDirectories(dirReal);

        // Copiar modelos iniciales reales como baseline
        File srcUs = new File("models/modelo_ultrasonico.nnet");
        File srcCan = new File("models/modelo_canicas.nnet");
        assertTrue(srcUs.exists() && srcCan.exists());
        Files.copy(srcUs.toPath(), dirModelos.resolve("modelo_ultrasonico.nnet"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(srcCan.toPath(), dirModelos.resolve("modelo_canicas.nnet"), StandardCopyOption.REPLACE_EXISTING);

        String shaOriginalUs = GestorModelosYEntrenamiento.sha256Corto(dirModelos.resolve("modelo_ultrasonico.nnet").toFile());
        String shaOriginalCan = GestorModelosYEntrenamiento.sha256Corto(dirModelos.resolve("modelo_canicas.nnet").toFile());

        // Grabar muestras reales representativas (incluyendo paredes en diagonal 26 cm y 999.0 sin eco)
        GrabadorDatasetReal grab = new GrabadorDatasetReal(dirReal.toFile());
        grab.setGrabando(true);
        for (int i = 0; i < 6; i++) {
            grab.seleccionarEtiqueta("BLOQUEO");
            grab.registrarTelemetria(v(24.0 + i * 0.5, 25.0 + i * 0.5, 26.0, 20.0, 22.0, 0.0, 100L + i));
            grab.seleccionarEtiqueta("ESPACIO_LIBRE");
            grab.registrarTelemetria(v(65.0 + i * 5.0, 70.0 + i * 5.0, 68.0, 35.0, 38.0, 5.0, 200L + i));
            grab.seleccionarEtiqueta("SALIDA");
            grab.registrarTelemetria(v(205.0 + i * 5.0, 210.0 + i * 5.0, 200.0, 999.0, 999.0, 0.0, 300L + i));

            grab.seleccionarEtiqueta("LOBITO_IZQ");
            grab.registrarTelemetria(v(60.0, 70.0, 65.0, 14.0 + i, 999.0, -15.0 + i, 400L + i));
            grab.seleccionarEtiqueta("LOBITO_CENTRO");
            grab.registrarTelemetria(v(60.0, 70.0, 65.0, 15.0 + i, 15.5 + i, 0.0, 500L + i));
            grab.seleccionarEtiqueta("LOBITO_DER");
            grab.registrarTelemetria(v(60.0, 70.0, 65.0, 999.0, 14.0 + i, 18.0 + i, 600L + i));
            grab.seleccionarEtiqueta("NINGUNA");
            grab.registrarTelemetria(v(60.0, 70.0, 65.0, 999.0, 999.0, -35.0 + i * 10.0, 700L + i));
        }

        File baseUs = GestorModelosYEntrenamiento.resolverDatasetBasePorDefecto("dataset_ultrasonico.csv");
        File baseCan = GestorModelosYEntrenamiento.resolverDatasetBasePorDefecto("dataset_canicas.csv");
        GestorModelosYEntrenamiento gestor = new GestorModelosYEntrenamiento(
                dirModelos.toFile(), baseUs, baseCan,
                grab.getArchivoUltrasonico(), grab.getArchivoCanicas(),
                80, 80);

        ClasificadorNeuroph clasificador = new ClasificadorNeuroph(
                dirModelos.resolve("modelo_ultrasonico.nnet").toFile(),
                dirModelos.resolve("modelo_canicas.nnet").toFile());

        GestorModelosYEntrenamiento.ResultadoReentrenamiento res =
                gestor.reentrenarYActivar("test-diagonal", clasificador);

        assertAll(
                () -> assertNotNull(res.versionId()),
                () -> assertTrue(res.redUltrasonico().entrenada()),
                () -> assertTrue(res.redCanicas().entrenada()),
                () -> assertEquals(18, res.muestrasUsReal()),
                () -> assertEquals(24, res.muestrasCanReal()),
                () -> assertTrue(res.redUltrasonico().accuracyPct() > 60.0),
                () -> assertTrue(res.redCanicas().accuracyPct() > 50.0)
        );

        // Verificar historial: nueva version + baseline inicial inmutable
        List<GestorModelosYEntrenamiento.EntradaHistorialModelo> historial = gestor.listarHistorial();
        assertEquals(2, historial.size());
        assertEquals(res.versionId(), historial.get(0).id());
        String baselineId = historial.get(1).id();
        assertTrue(baselineId.endsWith("-baseline"));

        // Rollback a baseline restaura hashes originales y recarga ClasificadorNeuroph
        assertTrue(gestor.activarVersion(baselineId, clasificador));
        assertEquals(shaOriginalUs,
                GestorModelosYEntrenamiento.sha256Corto(dirModelos.resolve("modelo_ultrasonico.nnet").toFile()));
        assertEquals(shaOriginalCan,
                GestorModelosYEntrenamiento.sha256Corto(dirModelos.resolve("modelo_canicas.nnet").toFile()));
    }

    @Test
    public void testCapturaEnReposoDesdeManejadorClienteYComandosConsola(@TempDir Path tempDir) throws Exception {
        GrabadorDatasetReal grab = new GrabadorDatasetReal(tempDir.resolve("real").toFile());
        grab.seleccionarEtiqueta("SALIDA");
        grab.alternarGrabacion();

        String tramas = "HELLO:2.0.0,mega-3us-2laser\n"
                + "TELEMETRY:210.0,225.5,218.0,999.0,999.0,0.0,5000\n";
        ByteArrayInputStream in = new ByteArrayInputStream(tramas.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ConfiguracionServidor cfg = new ConfiguracionServidor();
        cfg.setNoTuiSolicitado(true);
        cfg.setRutaArchivoEstomago(tempDir.resolve("estomago.txt").toString());
        cfg.setRutaDatosReales(tempDir.resolve("real_srv").toString());

        GestorEstomago gestorEstomago = new GestorEstomago(cfg.getArchivoEstomago());
        ClasificadorNeuroph clasificador = new ClasificadorNeuroph(cfg.resolverArchivoModelo());
        PlanificadorNavegacion planificador = new PlanificadorNavegacion(gestorEstomago);
        EstadoMonitorTui monitor = new EstadoMonitorTui(cfg, clasificador, 0, List.of());

        ManejadorCliente cliente = new ManejadorCliente(
                in, out, "TestBT", () -> {},
                gestorEstomago, clasificador, planificador,
                0L, 0L, false, monitor, true, grab);
        cliente.run();

        // Aunque estaba en reposo (sin CMD:START), la muestra real se grabo
        assertEquals(1, grab.obtenerConteosPorClase().get("SALIDA"));

        // Probar atajos CLI / TUI en ServidorCentral: u, m, g, label
        ServidorCentral servidor = new ServidorCentral(cfg);
        servidor.procesarComandoConsola("u");
        assertEquals("ULTRASONICO", servidor.getMonitorTui().getGrabacionRed());
        servidor.procesarComandoConsola("m");
        assertEquals("CANICAS", servidor.getMonitorTui().getGrabacionRed());
        servidor.procesarComandoConsola("g");
        assertTrue(servidor.getMonitorTui().isGrabacionActiva());
        servidor.procesarComandoConsola("label NINGUNA");
        assertEquals("NINGUNA", servidor.getMonitorTui().getGrabacionEtiqueta());

        // Probar flags CLI de ConfiguracionServidor
        ConfiguracionServidor cliCfg = ConfiguracionServidor.desdeArgumentos(new String[]{
                "--retrain-real", "--list-models", "--use-model", "20261123-baseline",
                "--real-dir", "data/custom", "--note", "prueba cancha"
        });
        assertAll(
                () -> assertTrue(cliCfg.isRetrainRealSolicitado()),
                () -> assertTrue(cliCfg.isListarModelosSolicitado()),
                () -> assertEquals("20261123-baseline", cliCfg.getVersionModeloSolicitada()),
                () -> assertEquals("data/custom", cliCfg.getRutaDatosReales()),
                () -> assertEquals("prueba cancha", cliCfg.getNotaModelo())
        );
    }
}
