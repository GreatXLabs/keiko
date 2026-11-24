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
    public void testParametrosVelocidadYPwmConAcotado() {
        ConfiguracionServidor defaultCfg = ConfiguracionServidor.desdeArgumentos(new String[]{});
        assertEquals(1.0, defaultCfg.getEscalaVelocidad(), 1e-4);
        assertEquals(105, defaultCfg.getPwmMin());
        assertEquals(255, defaultCfg.getPwmMax());

        ConfiguracionServidor customCfg = ConfiguracionServidor.desdeArgumentos(
                new String[]{"--speed-scale", "0.55", "--pwm-min", "80", "--pwm-max", "210"});
        assertEquals(0.55, customCfg.getEscalaVelocidad(), 1e-4);
        assertEquals(80, customCfg.getPwmMin());
        assertEquals(210, customCfg.getPwmMax());

        ConfiguracionServidor clampedCfg = ConfiguracionServidor.desdeArgumentos(
                new String[]{"--speed-scale", "0.05", "--pwm-min", "220", "--pwm-max", "180"});
        assertEquals(0.20, clampedCfg.getEscalaVelocidad(), 1e-4);
        assertEquals(220, clampedCfg.getPwmMin());
        assertEquals(220, clampedCfg.getPwmMax());
    }
}
