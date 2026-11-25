package com.orquitas.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class ConfiguracionServidorTest {

    @TempDir
    Path tempDir;

    @Test
    public void testRutaEstomagoRelativaAlArchivoEnv() throws Exception {
        assumeTrue(System.getenv("ESTOMAGO_FILE_PATH") == null);
        Path env = tempDir.resolve(".env");
        Files.writeString(env, "ESTOMAGO_FILE_PATH=datos/estomago.txt\n", StandardCharsets.UTF_8);
        System.setProperty("env.file", env.toString());
        try {
            ConfiguracionServidor config = new ConfiguracionServidor();
            assertEquals(tempDir.resolve("datos/estomago.txt").toFile().getAbsolutePath(),
                    config.getArchivoEstomago().getAbsolutePath());
        } finally {
            System.clearProperty("env.file");
        }
    }

    @Test
    public void testRutaEstomagoAbsolutaNoSeAncla() throws Exception {
        assumeTrue(System.getenv("ESTOMAGO_FILE_PATH") == null);
        Path absoluta = tempDir.resolve("otro/estomago.txt");
        Path env = tempDir.resolve(".env");
        Files.writeString(env, "ESTOMAGO_FILE_PATH=" + absoluta + "\n", StandardCharsets.UTF_8);
        System.setProperty("env.file", env.toString());
        try {
            ConfiguracionServidor config = new ConfiguracionServidor();
            assertEquals(absoluta.toFile().getAbsolutePath(),
                    config.getArchivoEstomago().getAbsolutePath());
        } finally {
            System.clearProperty("env.file");
        }
    }

    @Test
    public void testBuscarEnvAscendenteDesdeDirectorioDeClases() throws Exception {
        Path modulo = tempDir.resolve("keiko-server");
        Path classes = Files.createDirectories(modulo.resolve("target/classes"));
        Path env = modulo.resolve(".env");
        Files.writeString(env, "ESTOMAGO_FILE_PATH=estomago.txt\n", StandardCharsets.UTF_8);

        assertEquals(env.toFile().getAbsolutePath(),
                ConfiguracionServidor.buscarEnvAscendente(classes.toFile()).getAbsolutePath());
    }

    @Test
    public void testCliAutoStartFlag() {
        ConfiguracionServidor defaultCfg = ConfiguracionServidor.desdeArgumentos(new String[]{});
        ConfiguracionServidor autoCfg = ConfiguracionServidor.desdeArgumentos(new String[]{"--auto-start"});
        assertEquals(false, defaultCfg.isAutoStartSolicitado());
        assertEquals(true, autoCfg.isAutoStartSolicitado());
    }

    @Test
    public void testResolucionModoTui() {
        ConfiguracionServidor defaultCfg = ConfiguracionServidor.desdeArgumentos(new String[]{});
        ConfiguracionServidor noTuiCfg = ConfiguracionServidor.desdeArgumentos(new String[]{"--no-tui"});
        ConfiguracionServidor forceTuiCfg = ConfiguracionServidor.desdeArgumentos(new String[]{"--tui"});

        assertEquals(false, defaultCfg.resolverModoTui(false));
        assertEquals(true, defaultCfg.resolverModoTui(true));
        assertEquals(false, noTuiCfg.resolverModoTui(true));
        assertEquals(true, forceTuiCfg.resolverModoTui(false));
    }

    @Test
    public void testConstantesOperativasFijasEnEntregaFinal() {
        ConfiguracionServidor cfg = ConfiguracionServidor.desdeArgumentos(new String[]{});
        assertEquals(0.40, ConfiguracionServidor.ESCALA_VELOCIDAD_FIJA, 1e-4);
        assertEquals(105, ConfiguracionServidor.PWM_MIN_FIJO);
        assertEquals(255, ConfiguracionServidor.PWM_MAX_FIJO);
        assertEquals(4, ConfiguracionServidor.PASOS_REVERSA_FIJO);
        assertEquals(25, cfg.getAuditorSondeoMs());
        assertEquals(3, cfg.getCapturasRequeridasEscape());
    }
}
