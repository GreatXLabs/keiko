package com.orquitas.server;

import java.io.File;
import java.io.IOException;
import java.net.Socket;

/**
 * Punto de entrada por consola del Servidor Central (Hito 3 - Proyecto Orquitas).
 * Coordina el arranque de las 2 redes Neuroph 2.98, persistencia, Hilo Auditor y sesiones TCP;
 * queda fuera de la Vista de Clases de dominio UML.
 */
public final class ServidorCentral {
    public static final int PUERTO_PREDETERMINADO = 5000;
    public static final String NOMBRE_ARCHIVO_ESTOMAGO = "estomago.txt";
    public static final String MODELO_US_PREDETERMINADO = "modelo_ultrasonico.nnet";
    public static final String MODELO_CANICAS_PREDETERMINADO = "modelo_canicas.nnet";
    public static final int AUDITOR_SONDEO_MS = 200;
    public static final int CAPTURAS_REQUERIDAS_ESCAPE = 3;

    private final GestorEstomago gestorEstomago;
    private final ClasificadorNeuroph clasificador;
    private final PlanificadorNavegacion planificador;
    private final ReceptorConexiones receptor;
    private final AuditorEstomago auditor;
    private volatile boolean corriendo = true;

    public ServidorCentral(int puerto, File archivoEstomago, File modeloUltrasonico, File modeloCanicas) {
        this.gestorEstomago = new GestorEstomago(archivoEstomago);
        this.clasificador = new ClasificadorNeuroph(modeloUltrasonico, modeloCanicas);
        this.planificador = new PlanificadorNavegacion(gestorEstomago);
        this.receptor = new ReceptorConexiones(puerto);
        this.auditor = new AuditorEstomago(
                gestorEstomago,
                planificador,
                receptor,
                AUDITOR_SONDEO_MS,
                CAPTURAS_REQUERIDAS_ESCAPE
        );
    }

    public void iniciar() {
        auditor.iniciar();
        while (corriendo) {
            try {
                Socket socketCliente = receptor.aceptarCliente();
                ManejadorCliente manejador = new ManejadorCliente(
                        socketCliente,
                        gestorEstomago,
                        clasificador,
                        planificador
                );
                receptor.registrarCliente(manejador);
                Thread hiloCliente = new Thread(manejador, "ManejadorCliente");
                hiloCliente.setDaemon(true);
                hiloCliente.start();
            } catch (IOException e) {
                if (corriendo) {
                    System.err.println("[SERVIDOR] Error aceptando conexion: " + e.getMessage());
                }
            }
        }
    }

    public void detener() {
        corriendo = false;
        auditor.detener();
        receptor.cerrar();
    }

    private static File resolverArchivoModelo(String rutaIndicada, String nombrePorDefecto) {
        File directo = new File(rutaIndicada);
        if (directo.isFile()) {
            return directo;
        }
        String[] respaldos = {
                "../keiko-sim/data/models",
                "keiko-sim/data/models",
                "../../keiko-sim/data/models",
                "data/models"
        };
        String nombreBase = directo.getName().isEmpty() ? nombrePorDefecto : directo.getName();
        for (String dir : respaldos) {
            File candidato = new File(dir, nombreBase);
            if (candidato.isFile()) {
                return candidato;
            }
        }
        return directo;
    }

    public static void main(String[] args) {
        int puerto = PUERTO_PREDETERMINADO;
        File archivoEstomago = new File(NOMBRE_ARCHIVO_ESTOMAGO);
        String rutaModeloUs = MODELO_US_PREDETERMINADO;
        String rutaModeloCanicas = MODELO_CANICAS_PREDETERMINADO;

        for (int i = 0; i < args.length; i++) {
            if ("--port".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                puerto = Integer.parseInt(args[++i]);
            } else if ("--estomago".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                archivoEstomago = new File(args[++i]);
            } else if ("--model-us".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                rutaModeloUs = args[++i];
            } else if ("--model-canicas".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                rutaModeloCanicas = args[++i];
            }
        }

        File modeloUs = resolverArchivoModelo(rutaModeloUs, MODELO_US_PREDETERMINADO);
        File modeloCanicas = resolverArchivoModelo(rutaModeloCanicas, MODELO_CANICAS_PREDETERMINADO);

        System.out.println("=== KEIKO SERVIDOR CENTRAL - HITO 3 ===");
        System.out.printf("Puerto TCP: %d | Estomago: %s | Red US: %s | Red Canicas: %s%n",
                puerto, archivoEstomago.getPath(), modeloUs.getPath(), modeloCanicas.getPath());

        ServidorCentral servidor = new ServidorCentral(puerto, archivoEstomago, modeloUs, modeloCanicas);
        Runtime.getRuntime().addShutdownHook(new Thread(servidor::detener, "Shutdown-Hito3"));
        servidor.iniciar();
    }
}
