package com.orquitas.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manejador concurrente de una sesion Bluetooth RFCOMM/SPP sobre streams serie.
 */
public class ManejadorCliente implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ManejadorCliente.class.getName());
    private static final int MAX_BYTES_TRAMA = 512;

    private final InputStream flujoEntrada;
    private final OutputStream flujoSalida;
    private final String descripcionOrigen;
    private final Closeable recursoCierre;
    private final GestorEstomago gestorEstomago;
    private final ClasificadorNeuroph clasificador;
    private final PlanificadorNavegacion planificador;
    private final long cooldownCapturaMs;
    private final long intervaloHeartbeatMs;
    private final boolean autoStartEnHello;
    private final EstadoMonitorTui monitorTui;
    private final boolean monitoreoPasivoEnReposo;
    private final GrabadorDatasetReal grabadorDataset;
    private final AtomicBoolean conectado = new AtomicBoolean(true);
    private final AtomicBoolean autoStartEnviado = new AtomicBoolean(false);
    private volatile boolean exigirTelemetria7Campos;
    private volatile int totalTramasValidas = 0;

    private BufferedWriter escritor;
    private final Object bloqueoEscritura = new Object();
    private volatile long ultimoEnvioMs = 0L;
    private long ultimaCapturaMs = 0L;

    public ManejadorCliente(InputStream flujoEntrada,
                            OutputStream flujoSalida,
                            String descripcionOrigen,
                            Closeable recursoCierre,
                            GestorEstomago gestorEstomago,
                            ClasificadorNeuroph clasificador,
                            PlanificadorNavegacion planificador,
                            long cooldownCapturaMs,
                            long intervaloHeartbeatMs) {
        this(flujoEntrada, flujoSalida, descripcionOrigen, recursoCierre,
                gestorEstomago, clasificador, planificador,
                cooldownCapturaMs, intervaloHeartbeatMs, false, null);
    }

    public ManejadorCliente(InputStream flujoEntrada,
                            OutputStream flujoSalida,
                            String descripcionOrigen,
                            Closeable recursoCierre,
                            GestorEstomago gestorEstomago,
                            ClasificadorNeuroph clasificador,
                            PlanificadorNavegacion planificador,
                            long cooldownCapturaMs,
                            long intervaloHeartbeatMs,
                            boolean autoStartEnHello) {
        this(flujoEntrada, flujoSalida, descripcionOrigen, recursoCierre,
                gestorEstomago, clasificador, planificador,
                cooldownCapturaMs, intervaloHeartbeatMs, autoStartEnHello, null);
    }

    public ManejadorCliente(InputStream flujoEntrada,
                            OutputStream flujoSalida,
                            String descripcionOrigen,
                            Closeable recursoCierre,
                            GestorEstomago gestorEstomago,
                            ClasificadorNeuroph clasificador,
                            PlanificadorNavegacion planificador,
                            long cooldownCapturaMs,
                            long intervaloHeartbeatMs,
                            boolean autoStartEnHello,
                            EstadoMonitorTui monitorTui) {
        this(flujoEntrada, flujoSalida, descripcionOrigen, recursoCierre,
                gestorEstomago, clasificador, planificador,
                cooldownCapturaMs, intervaloHeartbeatMs, autoStartEnHello, monitorTui, false);
    }

    public ManejadorCliente(InputStream flujoEntrada,
                            OutputStream flujoSalida,
                            String descripcionOrigen,
                            Closeable recursoCierre,
                            GestorEstomago gestorEstomago,
                            ClasificadorNeuroph clasificador,
                            PlanificadorNavegacion planificador,
                            long cooldownCapturaMs,
                            long intervaloHeartbeatMs,
                            boolean autoStartEnHello,
                            EstadoMonitorTui monitorTui,
                            boolean monitoreoPasivoEnReposo) {
        this(flujoEntrada, flujoSalida, descripcionOrigen, recursoCierre,
                gestorEstomago, clasificador, planificador,
                cooldownCapturaMs, intervaloHeartbeatMs, autoStartEnHello, monitorTui, monitoreoPasivoEnReposo, null);
    }

    public ManejadorCliente(InputStream flujoEntrada,
                            OutputStream flujoSalida,
                            String descripcionOrigen,
                            Closeable recursoCierre,
                            GestorEstomago gestorEstomago,
                            ClasificadorNeuroph clasificador,
                            PlanificadorNavegacion planificador,
                            long cooldownCapturaMs,
                            long intervaloHeartbeatMs,
                            boolean autoStartEnHello,
                            EstadoMonitorTui monitorTui,
                            boolean monitoreoPasivoEnReposo,
                            GrabadorDatasetReal grabadorDataset) {
        this.flujoEntrada = flujoEntrada;
        this.flujoSalida = flujoSalida;
        this.descripcionOrigen = descripcionOrigen;
        this.recursoCierre = recursoCierre;
        this.gestorEstomago = gestorEstomago;
        this.clasificador = clasificador;
        this.planificador = planificador;
        this.cooldownCapturaMs = cooldownCapturaMs;
        this.intervaloHeartbeatMs = intervaloHeartbeatMs;
        this.autoStartEnHello = autoStartEnHello;
        this.monitorTui = monitorTui;
        this.monitoreoPasivoEnReposo = monitoreoPasivoEnReposo;
        this.grabadorDataset = grabadorDataset;
        this.exigirTelemetria7Campos = monitoreoPasivoEnReposo;
    }

    @Override
    public void run() {
        LOGGER.info(() -> "[CLIENTE CONECTADO] Desde: " + descripcionOrigen);

        Thread hiloHeartbeat = null;
        try {
            InputStream in = flujoEntrada;
            OutputStream out = flujoSalida;
            escritor = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            ultimoEnvioMs = System.currentTimeMillis();

            if (intervaloHeartbeatMs > 0) {
                hiloHeartbeat = iniciarHiloHeartbeat();
            }

            ByteArrayOutputStream bufferLinea = new ByteArrayOutputStream(128);
            while (conectado.get()) {
                int b;
                try {
                    b = in.read();
                } catch (InterruptedIOException timeoutLectura) {
                    continue;
                }
                if (b == -1) {
                    break;
                }
                if (b == '\n' || b == '\r') {
                    if (bufferLinea.size() > 0) {
                        String linea = bufferLinea.toString(StandardCharsets.UTF_8).trim();
                        bufferLinea.reset();
                        if (!linea.isEmpty()) {
                            despacharLinea(linea);
                        }
                    }
                } else if (bufferLinea.size() < MAX_BYTES_TRAMA) {
                    bufferLinea.write(b);
                } else {
                    LOGGER.warning("[PROTOCOLO] Trama excesiva descartada defensivamente.");
                    bufferLinea.reset();
                }
            }
        } catch (IOException e) {
            if (conectado.get()) {
                LOGGER.info("[CLIENTE DESCONECTADO] " + descripcionOrigen + " (" + e.getMessage() + ")");
            }
        } finally {
            if (hiloHeartbeat != null) {
                hiloHeartbeat.interrupt();
            }
            cerrar();
        }
    }

    private Thread iniciarHiloHeartbeat() {
        Thread t = new Thread(() -> {
            long EsperaChequeo = Math.max(25L, intervaloHeartbeatMs / 2L);
            while (conectado.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(EsperaChequeo);
                    if (conectado.get() && (System.currentTimeMillis() - ultimoEnvioMs) >= intervaloHeartbeatMs) {
                        enviarMensaje(MensajeProtocolo.crearHeartbeat());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "Heartbeat-" + descripcionOrigen);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void despacharLinea(String linea) {
        if (linea.startsWith(MensajeProtocolo.PREFIX_TELEMETRY)) {
            totalTramasValidas++;
            procesarTelemetria(linea);
        } else if (linea.startsWith(MensajeProtocolo.PREFIX_EVENT)) {
            totalTramasValidas++;
            procesarEvento(linea);
        } else if (linea.startsWith(MensajeProtocolo.PREFIX_HELLO)) {
            totalTramasValidas++;
            procesarHello(linea);
        } else if (linea.equals(MensajeProtocolo.MSG_HEARTBEAT)) {
            totalTramasValidas++;
            if (monitorTui != null) {
                monitorTui.registrarHeartbeat(System.currentTimeMillis());
            }
            enviarMensaje(MensajeProtocolo.crearHeartbeat());
        } else if (linea.startsWith(MensajeProtocolo.PREFIX_DBG)) {
            totalTramasValidas++;
            if (monitorTui != null) {
                monitorTui.registrarDebugCliente(linea);
            }
            LOGGER.fine("[DEBUG CLIENTE] " + linea);
        } else if (linea.startsWith(MensajeProtocolo.PREFIX_ERROR)) {
            totalTramasValidas++;
            if (monitorTui != null) {
                monitorTui.registrarErrorCliente(linea);
            }
            LOGGER.warning("[CLIENTE] " + linea);
        } else {
            LOGGER.warning("[PROTOCOLO] Mensaje no reconocido: " + linea);
        }
    }

    private void procesarHello(String linea) {
        try {
            MensajeProtocolo.InfoHandshake hs = MensajeProtocolo.parsearHello(linea);
            boolean versionOk = MensajeProtocolo.esCompatibleMajorMinor(
                    ConfiguracionServidor.VERSION_SISTEMA, hs.version());
            boolean rigOk = ConfiguracionServidor.RIG_HARDWARE.equals(hs.rig());
            if (rigOk) {
                this.exigirTelemetria7Campos = true;
            }
            if (versionOk && rigOk) {
                LOGGER.info(String.format("[HANDSHAKE OK] Cliente=%s | Version=%s | Rig=%s",
                        descripcionOrigen, hs.version(), hs.rig()));
            } else {
                LOGGER.warning(String.format(
                        "[VERSION MISMATCH] Cliente=%s reporta Version=%s Rig=%s | Esperado Version=%s.* Rig=%s",
                        descripcionOrigen, hs.version(), hs.rig(),
                        ConfiguracionServidor.VERSION_SISTEMA, ConfiguracionServidor.RIG_HARDWARE));
            }
            if (monitorTui != null) {
                monitorTui.registrarHandshake(descripcionOrigen, hs.version(), hs.rig(), versionOk && rigOk);
            }
            if (autoStartEnHello && autoStartEnviado.compareAndSet(false, true)) {
                LOGGER.info("[AUTO-START] Enviando CMD:START tras handshake a " + descripcionOrigen);
                if (monitorTui != null) {
                    monitorTui.setCorridaActiva(true);
                    monitorTui.registrarComandoControl("CMD:START (auto)");
                    if (monitorTui.getPwmMin() != ConfiguracionServidor.PWM_MIN_PREDETERMINADO
                            || monitorTui.getPwmMax() != ConfiguracionServidor.PWM_MAX_PREDETERMINADO) {
                        enviarMensaje(MensajeProtocolo.crearCmdPwm(monitorTui.getPwmMin(), monitorTui.getPwmMax()));
                    }
                }
                enviarMensaje(MensajeProtocolo.crearCmdStart());
            } else if (monitorTui != null && !autoStartEnHello && monitoreoPasivoEnReposo) {
                monitorTui.setCorridaActiva(false);
                if (monitorTui.getPwmMin() != ConfiguracionServidor.PWM_MIN_PREDETERMINADO
                        || monitorTui.getPwmMax() != ConfiguracionServidor.PWM_MAX_PREDETERMINADO) {
                    enviarMensaje(MensajeProtocolo.crearCmdPwm(monitorTui.getPwmMin(), monitorTui.getPwmMax()));
                }
            }
        } catch (Exception e) {
            LOGGER.warning("[HANDSHAKE] Error al parsear HELLO: " + e.getMessage());
        }
    }

    private void procesarTelemetria(String linea) {
        try {
            VectorSensores sensores = exigirTelemetria7Campos
                    ? MensajeProtocolo.parsearTelemetria(linea, 6, true)
                    : MensajeProtocolo.parsearTelemetria(linea);
            if (!sensores.isValido()) {
                LOGGER.warning("[TELEMETRIA] Vector fuera de rango fisico: " + linea);
                return;
            }
            if (grabadorDataset != null && grabadorDataset.isGrabando()) {
                // [SOLO PRUEBAS / ENTRENAMIENTO] Captura muestra real en marcha o en reposo
                if (grabadorDataset.registrarTelemetria(sensores) && monitorTui != null) {
                    monitorTui.actualizarGrabacionDataset(
                            grabadorDataset.isGrabando(),
                            grabadorDataset.getRedObjetivo().name(),
                            grabadorDataset.getEtiquetaActiva(),
                            grabadorDataset.obtenerConteosPorClase());
                }
            }
            EstadoRobot estadoActual = planificador.getEstado();
            boolean evaluarCanicas = (estadoActual != EstadoRobot.ESCAPE && estadoActual != EstadoRobot.FINALIZACION);
            String[] pares = clasificador.clasificarPares(sensores, evaluarCanicas);
            boolean corridaActiva = !monitoreoPasivoEnReposo || (monitorTui != null && monitorTui.isCorridaActiva());
            if (!corridaActiva) {
                if (monitorTui != null) {
                    ComandoRobot detenido = new ComandoRobot(0.0, 0.0, planificador.getEstado());
                    monitorTui.registrarTelemetria(
                            sensores, pares,
                            clasificador.getUltimasConfianzasUs(),
                            clasificador.getUltimaClaseCanica(),
                            clasificador.getUltimaConfianzaCanica(),
                            detenido,
                            planificador.getContadorCiclos(),
                            System.currentTimeMillis());
                }
                return;
            }

            ComandoRobot baseCmd = planificador.evaluarPares(sensores, pares);
            double escala = (monitorTui != null) ? monitorTui.getEscalaVelocidad() : 1.0;
            ComandoRobot cmd = (Math.abs(escala - 1.0) > 1e-4)
                    ? new ComandoRobot(baseCmd.getVLeft() * escala, baseCmd.getVRight() * escala, baseCmd.getEstado())
                    : baseCmd;
            if (monitorTui != null) {
                monitorTui.registrarTelemetria(
                        sensores, pares,
                        clasificador.getUltimasConfianzasUs(),
                        clasificador.getUltimaClaseCanica(),
                        clasificador.getUltimaConfianzaCanica(),
                        cmd,
                        planificador.getContadorCiclos(),
                        System.currentTimeMillis());
            }
            LOGGER.info(String.format("[NAV] Terna=%s, Cmd=(%.1f, %.1f, %s), US=[%.1f, %.1f, %.1f], L=[%.1f, %.1f, %.1f°]",
                    java.util.Arrays.toString(pares), cmd.getVLeft(), cmd.getVRight(), cmd.getEstado(),
                    sensores.getUsLeft(), sensores.getUsCenter(), sensores.getUsRight(),
                    sensores.getLaserLeft(), sensores.getLaserRight(), sensores.getAnguloServo()));
            enviarMensaje(MensajeProtocolo.crearComando(cmd));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[TELEMETRIA] Error al procesar: " + e.getMessage());
        }
    }

    private synchronized void procesarEvento(String linea) {
        try {
            String evento = linea.substring(MensajeProtocolo.PREFIX_EVENT.length()).trim();
            LOGGER.info("[EVENTO RECIBIDO] " + evento);
            if (monitorTui != null) {
                monitorTui.registrarEvento(evento);
            }

            if (evento.contains("CAPTURA")) {
                if (planificador != null && planificador.getEstado() == EstadoRobot.ESCAPE) {
                    LOGGER.info("[CAPTURA IGNORADA] El sistema ya se encuentra en MODO ESCAPE.");
                    return;
                }
                if (gestorEstomago.contarCapturasValidas() >= ConfiguracionServidor.CAPTURAS_REQUERIDAS_ESCAPE) {
                    LOGGER.info("[CAPTURA IGNORADA] Ya existen 3 capturas fisicas registradas en estomago.txt.");
                    return;
                }
                long ahora = System.currentTimeMillis();
                if (cooldownCapturaMs > 0 && ultimaCapturaMs > 0 && (ahora - ultimaCapturaMs) < cooldownCapturaMs) {
                    LOGGER.info("[CAPTURA IGNORADA] Rebote descartado dentro de ventana anti-rebote (" + (ahora - ultimaCapturaMs) + " ms).");
                    return;
                }
                ultimaCapturaMs = ahora;
                int total = gestorEstomago.registrarCaptura(evento);
                if (monitorTui != null) {
                    monitorTui.actualizarEstomago(
                            total,
                            gestorEstomago.obtenerCapturasValidas(),
                            total >= ConfiguracionServidor.CAPTURAS_REQUERIDAS_ESCAPE);
                    monitorTui.agregarLog("EVENT", String.format("Canica recolectada (%d/%d): %s",
                            total, ConfiguracionServidor.CAPTURAS_REQUERIDAS_ESCAPE, evento));
                } else {
                    System.out.printf("[ESTOMAGO] Canica recolectada! Total persistido en estomago.txt: %d/%d (%s)%n",
                            total, ConfiguracionServidor.CAPTURAS_REQUERIDAS_ESCAPE, evento);
                }
                if (planificador != null) {
                    planificador.notificarCaptura();
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[EVENTO] Error al registrar captura: " + e.getMessage(), e);
        }
    }

    public void enviarMensaje(String mensaje) {
        synchronized (bloqueoEscritura) {
            if (!conectado.get() || escritor == null) return;
            try {
                escritor.write(mensaje);
                escritor.flush();
                ultimoEnvioMs = System.currentTimeMillis();
            } catch (IOException e) {
                LOGGER.warning("[ENVIO] Error al enviar a " + descripcionOrigen + ": " + e.getMessage());
                cerrar();
            }
        }
    }

    public void cerrar() {
        if (conectado.compareAndSet(true, false)) {
            try {
                if (recursoCierre != null) recursoCierre.close();
            } catch (IOException ignored) {}
            if (monitorTui != null) {
                monitorTui.registrarDesconexionCliente(descripcionOrigen);
            }
            LOGGER.info("[CLIENT HANDLER] Sesion cerrada: " + descripcionOrigen);
        }
    }

    public boolean isConectado() {
        return conectado.get();
    }

    public int getTotalTramasValidas() {
        return totalTramasValidas;
    }

    /**
     * Marca que el usuario ya envio CMD:START manualmente para evitar duplicar
     * un auto-start posterior cuando llegue HELLO en la misma sesion.
     */
    public void marcarStartManual() {
        autoStartEnviado.set(true);
    }
}
