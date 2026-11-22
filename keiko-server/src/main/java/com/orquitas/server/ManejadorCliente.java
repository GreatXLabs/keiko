package com.orquitas.server;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manejador concurrente de una sesion de cliente conectado por Socket TCP o Puerto Serie (Bluetooth RFCOMM/SPP / USB).
 */
public class ManejadorCliente implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ManejadorCliente.class.getName());
    private static final int MAX_BYTES_TRAMA = 512;

    private final Socket socket;
    private final InputStream flujoEntrada;
    private final OutputStream flujoSalida;
    private final String descripcionOrigen;
    private final Closeable recursoCierre;
    private final GestorEstomago gestorEstomago;
    private final ReceptorConexiones receptor;
    private final ClasificadorNeuroph clasificador;
    private final PlanificadorNavegacion planificador;
    private final long cooldownCapturaMs;
    private final long intervaloHeartbeatMs;
    private final boolean autoStartEnHello;
    private final AtomicBoolean conectado = new AtomicBoolean(true);
    private final AtomicBoolean autoStartEnviado = new AtomicBoolean(false);

    private BufferedWriter escritor;
    private final Object bloqueoEscritura = new Object();
    private volatile long ultimoEnvioMs = 0L;
    private long ultimaCapturaMs = 0L;

    public ManejadorCliente(Socket socket, GestorEstomago gestorEstomago,
                            ReceptorConexiones receptor,
                            ClasificadorNeuroph clasificador,
                            PlanificadorNavegacion planificador) {
        this(socket, gestorEstomago, receptor, clasificador, planificador,
                ConfiguracionServidor.COOLDOWN_CAPTURA_MS, ConfiguracionServidor.INTERVALO_HEARTBEAT_MS);
    }

    public ManejadorCliente(Socket socket, GestorEstomago gestorEstomago,
                            ReceptorConexiones receptor,
                            ClasificadorNeuroph clasificador,
                            PlanificadorNavegacion planificador,
                            long cooldownCapturaMs,
                            long intervaloHeartbeatMs) {
        this(socket, gestorEstomago, receptor, clasificador, planificador,
                cooldownCapturaMs, intervaloHeartbeatMs, false);
    }

    public ManejadorCliente(Socket socket, GestorEstomago gestorEstomago,
                            ReceptorConexiones receptor,
                            ClasificadorNeuroph clasificador,
                            PlanificadorNavegacion planificador,
                            long cooldownCapturaMs,
                            long intervaloHeartbeatMs,
                            boolean autoStartEnHello) {
        this.socket = socket;
        this.flujoEntrada = null;
        this.flujoSalida = null;
        this.descripcionOrigen = String.valueOf(socket.getRemoteSocketAddress());
        this.recursoCierre = socket;
        this.gestorEstomago = gestorEstomago;
        this.receptor = receptor;
        this.clasificador = clasificador;
        this.planificador = planificador;
        this.cooldownCapturaMs = cooldownCapturaMs;
        this.intervaloHeartbeatMs = intervaloHeartbeatMs;
        this.autoStartEnHello = autoStartEnHello;
    }

    public ManejadorCliente(InputStream flujoEntrada,
                            OutputStream flujoSalida,
                            String descripcionOrigen,
                            Closeable recursoCierre,
                            GestorEstomago gestorEstomago,
                            ReceptorConexiones receptor,
                            ClasificadorNeuroph clasificador,
                            PlanificadorNavegacion planificador,
                            long cooldownCapturaMs,
                            long intervaloHeartbeatMs) {
        this(flujoEntrada, flujoSalida, descripcionOrigen, recursoCierre,
                gestorEstomago, receptor, clasificador, planificador,
                cooldownCapturaMs, intervaloHeartbeatMs, false);
    }

    public ManejadorCliente(InputStream flujoEntrada,
                            OutputStream flujoSalida,
                            String descripcionOrigen,
                            Closeable recursoCierre,
                            GestorEstomago gestorEstomago,
                            ReceptorConexiones receptor,
                            ClasificadorNeuroph clasificador,
                            PlanificadorNavegacion planificador,
                            long cooldownCapturaMs,
                            long intervaloHeartbeatMs,
                            boolean autoStartEnHello) {
        this.socket = null;
        this.flujoEntrada = flujoEntrada;
        this.flujoSalida = flujoSalida;
        this.descripcionOrigen = descripcionOrigen;
        this.recursoCierre = recursoCierre;
        this.gestorEstomago = gestorEstomago;
        this.receptor = receptor;
        this.clasificador = clasificador;
        this.planificador = planificador;
        this.cooldownCapturaMs = cooldownCapturaMs;
        this.intervaloHeartbeatMs = intervaloHeartbeatMs;
        this.autoStartEnHello = autoStartEnHello;
    }

    @Override
    public void run() {
        LOGGER.info(() -> "[CLIENTE CONECTADO] Desde: " + descripcionOrigen);
        if (socket != null) {
            try {
                socket.setTcpNoDelay(true);
            } catch (SocketException e) {
                LOGGER.warning("[RED] No se pudo habilitar TCP_NODELAY: " + e.getMessage());
            }
        }

        Thread hiloHeartbeat = null;
        try {
            InputStream in = (socket != null) ? socket.getInputStream() : flujoEntrada;
            OutputStream out = (socket != null) ? socket.getOutputStream() : flujoSalida;
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
            procesarTelemetria(linea);
        } else if (linea.startsWith(MensajeProtocolo.PREFIX_EVENT)) {
            procesarEvento(linea);
        } else if (linea.startsWith(MensajeProtocolo.PREFIX_HELLO)) {
            procesarHello(linea);
        } else if (linea.equals(MensajeProtocolo.MSG_HEARTBEAT)) {
            enviarMensaje(MensajeProtocolo.crearHeartbeat());
        } else if (linea.startsWith(MensajeProtocolo.PREFIX_DBG)) {
            LOGGER.fine("[DEBUG CLIENTE] " + linea);
        } else if (linea.startsWith(MensajeProtocolo.PREFIX_ERROR)) {
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
            if (versionOk && rigOk) {
                LOGGER.info(String.format("[HANDSHAKE OK] Cliente=%s | Version=%s | Rig=%s",
                        descripcionOrigen, hs.version(), hs.rig()));
            } else {
                LOGGER.warning(String.format(
                        "[VERSION MISMATCH] Cliente=%s reporta Version=%s Rig=%s | Esperado Version=%s.* Rig=%s",
                        descripcionOrigen, hs.version(), hs.rig(),
                        ConfiguracionServidor.VERSION_SISTEMA, ConfiguracionServidor.RIG_HARDWARE));
            }
            if (autoStartEnHello && autoStartEnviado.compareAndSet(false, true)) {
                LOGGER.info("[AUTO-START] Enviando CMD:START tras handshake a " + descripcionOrigen);
                enviarMensaje(MensajeProtocolo.crearCmdStart());
            }
        } catch (Exception e) {
            LOGGER.warning("[HANDSHAKE] Error al parsear HELLO: " + e.getMessage());
        }
    }

    private void procesarTelemetria(String linea) {
        try {
            VectorSensores sensores = MensajeProtocolo.parsearTelemetria(linea);
            if (!sensores.isValido()) {
                LOGGER.warning("[TELEMETRIA] Vector fuera de rango fisico: " + linea);
                return;
            }
            String[] pares = clasificador.clasificarPares(sensores);
            ComandoRobot cmd = planificador.evaluarPares(sensores, pares);
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
                System.out.printf("[ESTOMAGO] Canica recolectada! Total persistido en estomago.txt: %d/%d (%s)%n",
                        total, ConfiguracionServidor.CAPTURAS_REQUERIDAS_ESCAPE, evento);
                if (planificador != null) {
                    planificador.notificarCaptura();
                }
            } else if (evento.contains("COLISION")) {
                if (planificador != null) {
                    String lado = "unknown";
                    if (evento.contains("COLISION_")) {
                        lado = evento.substring(evento.indexOf("COLISION_") + "COLISION_".length()).trim().toLowerCase();
                    } else if (evento.contains("lado=")) {
                        try {
                            lado = evento.split("lado=")[1].split("[,\\s]")[0].trim().toLowerCase();
                        } catch (Exception ignored) {}
                    }
                    planificador.notificarColision(lado);
                }
            } else if (evento.startsWith("SALIDA_META:")) {
                if (planificador != null) {
                    try {
                        String data = evento.substring("SALIDA_META:".length()).trim();
                        String[] parts = data.split(",");
                        double ex = Double.parseDouble(parts[0].trim());
                        double ey = Double.parseDouble(parts[1].trim());
                        planificador.establecerSalidaMeta(ex, ey);
                        LOGGER.info(String.format("[META SALIDA] Registrada meta de escape en (%.2f, %.2f)", ex, ey));
                    } catch (Exception ignored) {}
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
            if (receptor != null) {
                receptor.removerCliente(this);
            }
            LOGGER.info("[CLIENT HANDLER] Sesion cerrada: " + descripcionOrigen);
        }
    }

    public boolean isConectado() {
        return conectado.get();
    }
}
