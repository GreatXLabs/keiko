package com.orquitas.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConfiguracionServidorTest {

    @TempDir
    Path tempDir;

    @Test
    public void testRutaEstomagoRelativaAlArchivoEnv() throws Exception {
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
}
