package com.orquitas.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Gestiona la persistencia sincronizada de capturas en estomago.txt con verificacion CRC32 (RF-06 / RF-07).
 */
public class GestorEstomago {
    private final File archivoEstomago;

    public GestorEstomago(File archivoEstomago) {
        this.archivoEstomago = archivoEstomago;
    }

    public synchronized int registrarCaptura(String evento) throws IOException {
        File directorioPadre = archivoEstomago.getParentFile();
        if (directorioPadre != null && !directorioPadre.exists()) {
            directorioPadre.mkdirs();
        }
        int numeroCaptura = contarCapturasSinLock() + 1;
        String detalle = evento == null ? "CAPTURA" : evento.trim();
        String cuerpo = String.format(Locale.US, "CAPTURA #%d | %d | %s",
                numeroCaptura, System.currentTimeMillis(), detalle);
        String lineaConCRC32 = cuerpo + " | CRC32=" + calcularCRC32(cuerpo) + System.lineSeparator();

        try (FileOutputStream fos = new FileOutputStream(archivoEstomago, true);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write(lineaConCRC32);
            writer.flush();
            fos.getFD().sync();
        }
        return numeroCaptura;
    }

    public synchronized int contarCapturasRegistradas() {
        return contarCapturasSinLock();
    }

    private int contarCapturasSinLock() {
        if (!archivoEstomago.exists()) {
            return 0;
        }
        int validas = 0;
        try (FileInputStream fis = new FileInputStream(archivoEstomago);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = reader.readLine()) != null) {
                if (validarLineaCRC(linea)) {
                    validas++;
                }
            }
        } catch (IOException e) {
            System.err.println("[ESTOMAGO] Error al auditar registros en disco: " + e.getMessage());
            return validas;
        }
        return validas;
    }

    private boolean validarLineaCRC(String linea) {
        if (linea == null || !linea.startsWith("CAPTURA #")) {
            return false;
        }
        int sep = linea.lastIndexOf(" | CRC32=");
        if (sep < 0) {
            return false;
        }
        String cuerpo = linea.substring(0, sep);
        String crcGuardado = linea.substring(sep + " | CRC32=".length()).trim();
        return calcularCRC32(cuerpo).equalsIgnoreCase(crcGuardado);
    }

    private String calcularCRC32(String texto) {
        CRC32 crc = new CRC32();
        crc.update(texto.getBytes(StandardCharsets.UTF_8));
        return String.format(Locale.US, "%08X", crc.getValue());
    }
}
