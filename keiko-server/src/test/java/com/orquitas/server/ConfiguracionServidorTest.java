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
}
