package com.orquitas.server;

import com.fazecast.jSerialComm.SerialPort;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Conector concurrente de puerto serie para enlace Bluetooth RFCOMM/SPP (HC-05: COMx / /dev/rfcomm0)
 * o cable USB directo (/dev/ttyACM0, /dev/ttyUSB0), con reconexion automatica ante cortes de enlace.
 */
public class ReceptorSerie implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ReceptorSerie.class.getName());
    private static final long REINTENTO_CONEXION_MS = 1500L;

    private final String nombrePuerto;
    private final int baudios;
    private final GestorEstomago gestorEstomago;
    private final ClasificadorNeuroph clasificador;
    private final PlanificadorNavegacion planificador;
    private final long cooldownCapturaMs;
    private final long intervaloHeartbeatMs;
    private final boolean autoStartEnHello;
    private final EstadoMonitorTui monitorTui;

    private volatile boolean corriendo = false;
    private volatile ManejadorCliente sesionActiva;
    private volatile SerialPort puertoActivo;
    private Thread hiloSerie;

    public ReceptorSerie(String nombrePuerto,
                         int baudios,
                         GestorEstomago gestorEstomago,
                         ClasificadorNeuroph clasificador,
                         PlanificadorNavegacion planificador,
                         long cooldownCapturaMs,
                         long intervaloHeartbeatMs) {
        this(nombrePuerto, baudios, gestorEstomago, clasificador, planificador,
                cooldownCapturaMs, intervaloHeartbeatMs, false);
    }

    public ReceptorSerie(String nombrePuerto,
                         int baudios,
                         GestorEstomago gestorEstomago,
                         ClasificadorNeuroph clasificador,
                         PlanificadorNavegacion planificador,
                         long cooldownCapturaMs,
                         long intervaloHeartbeatMs,
                         boolean autoStartEnHello) {
        this(nombrePuerto, baudios, gestorEstomago, clasificador, planificador,
                cooldownCapturaMs, intervaloHeartbeatMs, autoStartEnHello, null);
    }

    public ReceptorSerie(String nombrePuerto,
                         int baudios,
                         GestorEstomago gestorEstomago,
                         ClasificadorNeuroph clasificador,
                         PlanificadorNavegacion planificador,
                         long cooldownCapturaMs,
                         long intervaloHeartbeatMs,
                         boolean autoStartEnHello,
                         EstadoMonitorTui monitorTui) {
        this.nombrePuerto = nombrePuerto;
        this.baudios = baudios;
        this.gestorEstomago = gestorEstomago;
        this.clasificador = clasificador;
        this.planificador = planificador;
        this.cooldownCapturaMs = cooldownCapturaMs;
        this.intervaloHeartbeatMs = intervaloHeartbeatMs;
        this.autoStartEnHello = autoStartEnHello;
        this.monitorTui = monitorTui;
    }

    public static List<String> listarPuertosDisponibles() {
        List<String> resultado = new ArrayList<>();
        try {
            SerialPort[] puertos = SerialPort.getCommPorts();
            for (SerialPort p : puertos) {
                resultado.add(String.format("%s (%s)", p.getSystemPortName(), p.getDescriptivePortName()));
            }
        } catch (Throwable t) {
            LOGGER.warning("[SERIE] No se pudieron enumerar puertos serie: " + t.getMessage());
        }
        return resultado;
    }

    public void iniciar() {
        hiloSerie = new Thread(this, "ReceptorSerie-" + nombrePuerto);
        hiloSerie.start();
    }

    @Override
    public void run() {
        corriendo = true;
        LOGGER.info(() -> String.format("[SERIE] Iniciando escucha sobre %s a %d baudios (8N1)...", nombrePuerto, baudios));

        while (corriendo && !Thread.currentThread().isInterrupted()) {
            SerialPort port = SerialPort.getCommPort(nombrePuerto);
            port.setComPortParameters(baudios, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 250, 0);

            if (!port.openPort()) {
                LOGGER.warning(() -> String.format("[SERIE] No se pudo abrir %s. Reintentando en %d ms...",
                        nombrePuerto, REINTENTO_CONEXION_MS));
                dormirSilencioso(REINTENTO_CONEXION_MS);
                continue;
            }

            puertoActivo = port;
            String origenSerie = "Serie:" + nombrePuerto + "@" + baudios;
            if (monitorTui != null) {
                monitorTui.registrarConexionCliente(origenSerie, 1);
                monitorTui.agregarLog("OK", String.format("Enlace serie abierto en %s @ %d bps", nombrePuerto, baudios));
            } else {
                System.out.printf("[BLUETOOTH/SERIE CONECTADO] Enlace abierto en %s @ %d bps%n", nombrePuerto, baudios);
            }

            try (InputStream in = port.getInputStream();
                 OutputStream out = port.getOutputStream()) {
                ManejadorCliente manejador = new ManejadorCliente(
                        in, out, origenSerie,
                        port::closePort, gestorEstomago, clasificador, planificador,
                        cooldownCapturaMs, intervaloHeartbeatMs, autoStartEnHello, monitorTui);
                sesionActiva = manejador;
                manejador.run();
            } catch (Exception e) {
                if (corriendo) {
                    LOGGER.log(Level.WARNING, "[SERIE] Conexion interrumpida en " + nombrePuerto + ": " + e.getMessage());
                }
            } finally {
                sesionActiva = null;
                if (port.isOpen()) {
                    port.closePort();
                }
                puertoActivo = null;
            }

            if (corriendo) {
                dormirSilencioso(REINTENTO_CONEXION_MS);
            }
        }
        LOGGER.info("[SERIE] Receptor serie detenido.");
    }

    public void transmitirATodos(String mensaje) {
        ManejadorCliente activa = sesionActiva;
        if (activa != null && activa.isConectado()) {
            activa.enviarMensaje(mensaje);
        }
    }

    public void detener() {
        corriendo = false;
        ManejadorCliente activa = sesionActiva;
        if (activa != null) {
            activa.cerrar();
        }
        SerialPort port = puertoActivo;
        if (port != null && port.isOpen()) {
            port.closePort();
        }
        if (hiloSerie != null) {
            hiloSerie.interrupt();
        }
    }

    private void dormirSilencioso(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
