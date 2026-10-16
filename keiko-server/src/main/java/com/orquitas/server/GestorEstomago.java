package com.orquitas.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gestor de Persistencia Critica de la Orquita ("El Estomago").
 * 
 * Cumple estrictamente con las reglas no negociables de la catedra (PSR / DS 2026):
 * 1. Seccion Critica con bloque synchronized(bloqueoArchivo) sobre el recurso estomago.txt.
 * 2. Vaciado obligatorio a disco por hardware mediante flush() y FileDescriptor.sync().
 * 3. Prohibicion de estado volatil: las capturas se cuentan fisicamente desde el archivo.
 */
public class GestorEstomago {

    private static final Logger LOGGER = Logger.getLogger(GestorEstomago.class.getName());
    public static final String CAPTURA_PREFIX = "CAPTURA #";

    private final File archivoEstomago;
    private final Object bloqueoArchivo = new Object();

    // Inicializa el gestor utilizando la ruta predeterminada del estomago.
    public GestorEstomago() {
        this(new File(ConfiguracionServidor.NOMBRE_ARCHIVO_ESTOMAGO));
    }

    // Inicializa el gestor con un archivo especifico y asegura su cabecera inicial.
    public GestorEstomago(File archivo) {
        this.archivoEstomago = archivo;
        asegurarExistenciaArchivo();
    }

    // Crea el archivo de persistencia con su cabecera si aun no existe en disco.
    private void asegurarExistenciaArchivo() {
        synchronized (bloqueoArchivo) {
            if (!archivoEstomago.exists()) {
                File parent = archivoEstomago.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                try (FileOutputStream fos = new FileOutputStream(archivoEstomago);
                     OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                     BufferedWriter bw = new BufferedWriter(osw)) {
                    bw.write("# ========================================================\n");
                    bw.write("# ESTOMAGO PERSISTENTE - PROYECTO ORQUITAS (PSR/DS 2026)\n");
                    bw.write("# FUENTE UNICA DE VERDAD BIOLOGICA (NO VOLATIL)\n");
                    bw.write("# ========================================================\n");
                    bw.flush();
                    fos.getFD().sync();
                    LOGGER.info(() -> "Archivo estomago.txt inicializado en: " + archivoEstomago.getAbsolutePath());
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Error al inicializar estomago.txt", e);
                }
            }
        }
    }

    // RF-03: Registrar captura en estomago.txt con forzado fisico por hardware sync.
    public int registrarCaptura(RegistroCaptura c) throws IOException {
        synchronized (bloqueoArchivo) {
            int currentCount = contarCapturasSinLock();
            int nuevoNumero = currentCount + 1;
            c.setNumeroCaptura(nuevoNumero);

            String linea = c.toLineaArchivo();

            try (FileOutputStream fos = new FileOutputStream(archivoEstomago, true);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                 BufferedWriter bw = new BufferedWriter(osw)) {
                bw.write(linea);
                bw.flush();
                fos.getFD().sync();
            }

            LOGGER.info(() -> String.format("[SECCION CRITICA: ESCRITOR] Captura #%d persistida en %s (sync verificado)",
                    nuevoNumero, archivoEstomago.getName()));
            return nuevoNumero;
        }
    }

    // RF-03: Registra una captura generando un registro con metadatos descriptivos.
    public int registrarCaptura(String metadata) throws IOException {
        return registrarCaptura(new RegistroCaptura("lobito_ingesta", metadata));
    }

    // RF-04: Contar capturas registradas en almacenamiento fisico.
    public int contarCapturasRegistradas() {
        return contarCapturasValidas();
    }

    // RF-04: Cuenta concurrentemente los registros validos presentes en disco.
    public int contarCapturasValidas() {
        synchronized (bloqueoArchivo) {
            return contarCapturasSinLock();
        }
    }

    // Lee linea por linea el archivo fisico para contabilizar capturas sin lock adicional.
    private int contarCapturasSinLock() {
        if (!archivoEstomago.exists()) return 0;
        int count = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(archivoEstomago), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && line.contains(CAPTURA_PREFIX)) {
                    count++;
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error al leer estomago.txt para conteo", e);
        }
        return count;
    }

    // Retorna la coleccion inmutable de capturas leidas directamente del archivo fisico.
    public List<RegistroCaptura> obtenerCapturasValidas() {
        synchronized (bloqueoArchivo) {
            if (!archivoEstomago.exists()) return Collections.emptyList();
            List<RegistroCaptura> lista = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(archivoEstomago), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#") && line.contains(CAPTURA_PREFIX)) {
                        lista.add(RegistroCaptura.fromLinea(line));
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error al obtener registros de estomago.txt", e);
            }
            return Collections.unmodifiableList(lista);
        }
    }

    // Reinicia el estomago borrando el archivo existente y recreando la cabecera.
    public void vaciarEstomago() {
        synchronized (bloqueoArchivo) {
            if (archivoEstomago.exists()) {
                archivoEstomago.delete();
            }
            asegurarExistenciaArchivo();
            LOGGER.info("Estomago reiniciado a cero para nueva prueba.");
        }
    }

    // Retorna la referencia al archivo File del estomago.
    public File getArchivoEstomago() {
        return archivoEstomago;
    }
}
