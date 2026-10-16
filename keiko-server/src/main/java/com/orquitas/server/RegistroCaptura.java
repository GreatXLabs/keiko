package com.orquitas.server;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.zip.CRC32;

/**
 * Modelo inmutable de una captura biológica almacenada en disco.
 */
public class RegistroCaptura {
    private int numeroCaptura;
    private final String idCaptura;
    private final long timestamp;
    private final String metadata;

    // Crea un registro con identificador de presa y marca temporal actual.
    public RegistroCaptura(String idCaptura) {
        this(idCaptura, System.currentTimeMillis(), "N/A");
    }

    // Crea un registro con identificador de presa, metadatos descriptivos y marca temporal actual.
    public RegistroCaptura(String idCaptura, String metadata) {
        this(idCaptura, System.currentTimeMillis(), metadata);
    }

    // Crea un registro indicando identificador de presa, marca temporal exacta y metadatos.
    public RegistroCaptura(String idCaptura, long timestamp, String metadata) {
        this.idCaptura = idCaptura;
        this.timestamp = timestamp;
        this.metadata = metadata != null ? metadata : "N/A";
    }

    // Retorna el numero correlativo de la captura.
    public int getNumeroCaptura() {
        return numeroCaptura;
    }

    // Asigna el numero correlativo de la captura.
    public void setNumeroCaptura(int numeroCaptura) {
        this.numeroCaptura = numeroCaptura;
    }

    // Retorna el identificador asignado a la captura.
    public String getIdCaptura() {
        return idCaptura;
    }

    // Retorna la marca de tiempo en milisegundos de la captura.
    public long getTimestamp() {
        return timestamp;
    }

    // Retorna los metadatos descriptivos de la captura.
    public String getMetadata() {
        return metadata;
    }

    // Formatea el registro en una linea de texto persistente con checksum CRC32.
    public String toLineaArchivo() {
        String base = String.format("%s | %s%d | id=%s | METADATA: %s",
                Instant.ofEpochMilli(timestamp).toString(),
                GestorEstomago.CAPTURA_PREFIX,
                numeroCaptura,
                idCaptura,
                metadata);
        CRC32 crc = new CRC32();
        crc.update(base.getBytes(StandardCharsets.UTF_8));
        return String.format("%s | CHECKSUM=%08X\n", base, crc.getValue());
    }

    // Parsea una linea de texto del estomago reconstruyendo el objeto RegistroCaptura.
    public static RegistroCaptura fromLinea(String linea) {
        String id = "lobito";
        long ts = System.currentTimeMillis();
        String meta = "N/A";
        int num = 0;
        try {
            if (linea.contains("id=")) {
                int start = linea.indexOf("id=") + 3;
                int end = linea.indexOf(" |", start);
                id = (end != -1) ? linea.substring(start, end).trim() : linea.substring(start).trim();
            }
            if (linea.contains(GestorEstomago.CAPTURA_PREFIX)) {
                int start = linea.indexOf(GestorEstomago.CAPTURA_PREFIX) + GestorEstomago.CAPTURA_PREFIX.length();
                int end = linea.indexOf(" |", start);
                String numStr = (end != -1) ? linea.substring(start, end).trim() : linea.substring(start).trim();
                num = Integer.parseInt(numStr);
            }
            if (linea.contains("METADATA:")) {
                int start = linea.indexOf("METADATA:") + 9;
                int end = linea.indexOf(" | CHECKSUM=", start);
                meta = (end != -1) ? linea.substring(start, end).trim() : linea.substring(start).trim();
            }
        } catch (Exception ignored) {}
        RegistroCaptura r = new RegistroCaptura(id, ts, meta);
        r.setNumeroCaptura(num);
        return r;
    }

    // Retorna la representacion resumida del registro en formato texto.
    @Override
    public String toString() {
        return String.format("RegistroCaptura[#%d, id=%s, ts=%d]", numeroCaptura, idCaptura, timestamp);
    }
}
