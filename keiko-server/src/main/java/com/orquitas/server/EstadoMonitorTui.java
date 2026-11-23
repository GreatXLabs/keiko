package com.orquitas.server;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Almacen concurrente del estado en vivo del Servidor Central para renderizado TUI.
 * Centraliza telemetria, inferencias de Neuroph, estado FSM, persistencia y logs recientes
 * sin bloquear el lazo de control ni la recepcion de tramas.
 */
public class EstadoMonitorTui {
    public static final long FAILSAFE_TIMEOUT_MS = 500L;
    public static final long FAILSAFE_ALERTA_MS = 350L;
    public static final long VENTANA_ESTALIDAD_HZ_MS = 1500L;
    private static final int MAX_MUESTRAS_HZ = 24;
    private static final int MAX_LOGS = 60;
    private static final DateTimeFormatter HORA_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public enum EstadoFailSafe {
        SIN_ENLACE,
        OK,
        ALERTA,
        TIMEOUT
    }

    public record SnapshotTelemetria(
            VectorSensores sensores,
            String[] terna,
            double[] confianzasUs,
            String claseCanica,
            double confianzaCanica,
            ComandoRobot comando,
            int ciclosFsm,
            long timestampRecepcionMs
    ) {}

    public record EntradaLog(long timestampMs, String nivel, String mensaje) {
        public String formatear() {
            return String.format("%s [%s] %s", HORA_FMT.format(Instant.ofEpochMilli(timestampMs)), nivel, mensaje);
        }
    }

    private final String version;
    private final String rig;
    private final String puertoSerie;
    private final int baudiosSerie;
    private final String rutaEstomago;
    private final int capturasRequeridas;
    private final boolean redUltrasonicoCargada;
    private final boolean redCanicasCargada;
    private final boolean redLegacyCargada;

    private final AtomicLong versionRender = new AtomicLong(0L);
    private final Deque<EntradaLog> logs = new ArrayDeque<>(MAX_LOGS);
    private final long[] ventanaTimestampsTelemetria = new long[MAX_MUESTRAS_HZ];
    private int idxVentanaHz = 0;
    private int cantVentanaHz = 0;

    private volatile List<String> puertosSerieDetectados = Collections.emptyList();
    private volatile int clientesConectados = 0;
    private volatile String clienteActivo = "---";
    private volatile boolean handshakeRecibido = false;
    private volatile boolean handshakeOk = false;
    private volatile String handshakeVersion = "---";
    private volatile String handshakeRig = "---";

    private volatile long ultimoPaqueteMs = 0L;
    private volatile long ultimaTelemetriaMs = 0L;
    private volatile long ultimoHeartbeatMs = 0L;
    private volatile long totalTelemetrias = 0L;
    private volatile long totalErroresCliente = 0L;
    private volatile String ultimoErrorCliente = "---";
    private volatile String ultimoDbgEstomago = "---";
    private volatile String ultimoComandoControl = "---";

    private volatile SnapshotTelemetria ultimaTelemetria = null;
    private volatile int capturasEstomago = 0;
    private volatile List<RegistroCaptura> registrosEstomago = Collections.emptyList();
    private volatile boolean modoEscapeActivo = false;
    private volatile EstadoRobot estadoFsmActual = EstadoRobot.BUSQUEDA;

    public EstadoMonitorTui(ConfiguracionServidor cfg,
                            ClasificadorNeuroph clasificador,
                            int capturasIniciales,
                            List<RegistroCaptura> registrosIniciales) {
        this.version = ConfiguracionServidor.VERSION_SISTEMA;
        this.rig = ConfiguracionServidor.RIG_HARDWARE;
        this.puertoSerie = cfg.getPuertoSerie();
        this.baudiosSerie = cfg.getBaudiosSerie();
        this.rutaEstomago = cfg.getArchivoEstomago().getPath();
        this.capturasRequeridas = cfg.getCapturasRequeridasEscape();
        this.redUltrasonicoCargada = clasificador != null && clasificador.isRedUltrasonicoCargada();
        this.redCanicasCargada = clasificador != null && clasificador.isRedCanicasCargada();
        this.redLegacyCargada = clasificador != null && clasificador.isRedLegacyCargada();
        this.capturasEstomago = capturasIniciales;
        this.registrosEstomago = registrosIniciales != null ? List.copyOf(registrosIniciales) : Collections.emptyList();
        this.modoEscapeActivo = capturasIniciales >= capturasRequeridas;
        if (this.modoEscapeActivo) {
            this.estadoFsmActual = EstadoRobot.ESCAPE;
        }
    }

    public void actualizarPuertosSerie(List<String> puertos) {
        this.puertosSerieDetectados = puertos != null ? List.copyOf(puertos) : Collections.emptyList();
        marcarCambio();
    }

    public synchronized void registrarConexionCliente(String origen, int totalConectados) {
        this.clienteActivo = origen != null ? origen : "---";
        this.clientesConectados = Math.max(totalConectados, 1);
        this.handshakeRecibido = false;
        this.handshakeOk = false;
        this.handshakeVersion = "---";
        this.handshakeRig = "---";
        this.ultimoPaqueteMs = System.currentTimeMillis();
        marcarCambio();
    }

    public synchronized void actualizarCantidadClientes(int totalConectados) {
        this.clientesConectados = Math.max(0, totalConectados);
        if (this.clientesConectados == 0) {
            this.clienteActivo = "---";
        }
        marcarCambio();
    }

    public synchronized void registrarDesconexionCliente(String origen) {
        if (this.clientesConectados > 0) {
            this.clientesConectados--;
        }
        if (this.clientesConectados == 0) {
            this.clienteActivo = "---";
            this.handshakeRecibido = false;
            this.handshakeOk = false;
            this.handshakeVersion = "---";
            this.handshakeRig = "---";
        }
        agregarLog("INFO", "Cliente desconectado: " + origen);
        marcarCambio();
    }

    public synchronized void registrarHandshake(String origen, String versionCliente, String rigCliente, boolean compatible) {
        this.clienteActivo = origen != null ? origen : this.clienteActivo;
        this.handshakeRecibido = true;
        this.handshakeOk = compatible;
        this.handshakeVersion = versionCliente != null ? versionCliente : "---";
        this.handshakeRig = rigCliente != null ? rigCliente : "---";
        this.ultimoPaqueteMs = System.currentTimeMillis();
        agregarLog(compatible ? "OK" : "WARN",
                String.format("HELLO %s | ver=%s rig=%s (%s)",
                        this.clienteActivo, this.handshakeVersion, this.handshakeRig,
                        compatible ? "COMPATIBLE" : "MISMATCH"));
        marcarCambio();
    }

    public synchronized void registrarHeartbeat(long ahoraMs) {
        this.ultimoHeartbeatMs = ahoraMs;
        this.ultimoPaqueteMs = ahoraMs;
        marcarCambio();
    }

    public synchronized void registrarTelemetria(VectorSensores sensores,
                                                  String[] terna,
                                                  double[] confianzasUs,
                                                  String claseCanica,
                                                  double confianzaCanica,
                                                  ComandoRobot comando,
                                                  int ciclosFsm,
                                                  long ahoraMs) {
        String[] ternaCopia = terna != null ? Arrays.copyOf(terna, terna.length) : new String[]{"---", "---", "---"};
        double[] confCopia = confianzasUs != null ? Arrays.copyOf(confianzasUs, confianzasUs.length) : new double[]{0, 0, 0};

        this.ultimaTelemetria = new SnapshotTelemetria(
                sensores, ternaCopia, confCopia,
                claseCanica != null ? claseCanica : "NINGUNA",
                confianzaCanica, comando, ciclosFsm, ahoraMs);
        this.ultimaTelemetriaMs = ahoraMs;
        this.ultimoPaqueteMs = ahoraMs;
        this.totalTelemetrias++;
        if (comando != null) {
            this.estadoFsmActual = comando.getEstado();
        }
        registrarMuestraHz(ahoraMs);
        marcarCambio();
    }

    private void registrarMuestraHz(long ahoraMs) {
        ventanaTimestampsTelemetria[idxVentanaHz] = ahoraMs;
        idxVentanaHz = (idxVentanaHz + 1) % MAX_MUESTRAS_HZ;
        if (cantVentanaHz < MAX_MUESTRAS_HZ) {
            cantVentanaHz++;
        }
    }

    public synchronized double calcularFrecuenciaHz(long ahoraMs) {
        if (cantVentanaHz < 2 || ultimaTelemetriaMs <= 0L || (ahoraMs - ultimaTelemetriaMs) > VENTANA_ESTALIDAD_HZ_MS) {
            return 0.0;
        }
        int inicio = (idxVentanaHz - cantVentanaHz + MAX_MUESTRAS_HZ) % MAX_MUESTRAS_HZ;
        long masAntiguo = ventanaTimestampsTelemetria[inicio];
        long masReciente = ventanaTimestampsTelemetria[(idxVentanaHz - 1 + MAX_MUESTRAS_HZ) % MAX_MUESTRAS_HZ];
        long spanMs = masReciente - masAntiguo;
        if (spanMs <= 0L) {
            return 0.0;
        }
        return (cantVentanaHz - 1) * 1000.0 / spanMs;
    }

    public EstadoFailSafe obtenerEstadoFailSafe(long ahoraMs) {
        if (clientesConectados <= 0 || ultimoPaqueteMs <= 0L) {
            return EstadoFailSafe.SIN_ENLACE;
        }
        long silencioMs = Math.max(0L, ahoraMs - ultimoPaqueteMs);
        if (silencioMs >= FAILSAFE_TIMEOUT_MS) {
            return EstadoFailSafe.TIMEOUT;
        }
        if (silencioMs >= FAILSAFE_ALERTA_MS) {
            return EstadoFailSafe.ALERTA;
        }
        return EstadoFailSafe.OK;
    }

    public long obtenerSilencioMs(long ahoraMs) {
        if (ultimoPaqueteMs <= 0L) {
            return -1L;
        }
        return Math.max(0L, ahoraMs - ultimoPaqueteMs);
    }

    public synchronized void registrarEvento(String evento) {
        this.ultimoPaqueteMs = System.currentTimeMillis();
        agregarLog("EVENT", evento);
        marcarCambio();
    }

    public synchronized void registrarErrorCliente(String mensaje) {
        this.totalErroresCliente++;
        this.ultimoErrorCliente = mensaje != null ? mensaje : "---";
        this.ultimoPaqueteMs = System.currentTimeMillis();
        agregarLog("ERROR", this.ultimoErrorCliente);
        marcarCambio();
    }

    public synchronized void registrarDebugCliente(String lineaDbg) {
        this.ultimoPaqueteMs = System.currentTimeMillis();
        if (lineaDbg != null && lineaDbg.contains("estomago=")) {
            this.ultimoDbgEstomago = lineaDbg.substring(MensajeProtocolo.PREFIX_DBG.length()).trim();
        } else {
            agregarLog("DBG", lineaDbg);
        }
        marcarCambio();
    }

    public synchronized void registrarComandoControl(String cmd) {
        this.ultimoComandoControl = cmd != null ? cmd : "---";
        agregarLog("CTRL", "Enviado " + this.ultimoComandoControl);
        marcarCambio();
    }

    public synchronized void actualizarEstomago(int capturas, List<RegistroCaptura> registros, boolean escapeActivo) {
        this.capturasEstomago = capturas;
        this.registrosEstomago = registros != null ? List.copyOf(registros) : Collections.emptyList();
        this.modoEscapeActivo = escapeActivo;
        if (escapeActivo) {
            this.estadoFsmActual = EstadoRobot.ESCAPE;
        }
        marcarCambio();
    }

    public synchronized void registrarResetPrueba() {
        this.capturasEstomago = 0;
        this.registrosEstomago = Collections.emptyList();
        this.modoEscapeActivo = false;
        this.estadoFsmActual = EstadoRobot.BUSQUEDA;
        this.ultimaTelemetria = null;
        this.cantVentanaHz = 0;
        this.idxVentanaHz = 0;
        agregarLog("CTRL", "Estomago y FSM reiniciados para prueba.");
        marcarCambio();
    }

    public synchronized void agregarLog(String nivel, String mensaje) {
        if (mensaje == null || mensaje.isBlank()) {
            return;
        }
        String limpio = mensaje.replace("\r", "").replace("\n", " ").trim();
        if (logs.size() >= MAX_LOGS) {
            logs.removeFirst();
        }
        logs.addLast(new EntradaLog(System.currentTimeMillis(), nivel != null ? nivel : "INFO", limpio));
        marcarCambio();
    }

    public synchronized void limpiarLogs() {
        logs.clear();
        marcarCambio();
    }

    public synchronized List<EntradaLog> obtenerLogsRecientes(int limite) {
        if (limite <= 0 || logs.isEmpty()) {
            return Collections.emptyList();
        }
        List<EntradaLog> todos = new ArrayList<>(logs);
        int desde = Math.max(0, todos.size() - limite);
        return todos.subList(desde, todos.size());
    }

    private void marcarCambio() {
        versionRender.incrementAndGet();
    }

    public long getVersionRender() { return versionRender.get(); }
    public String getVersion() { return version; }
    public String getRig() { return rig; }
    public String getPuertoSerie() { return puertoSerie; }
    public int getBaudiosSerie() { return baudiosSerie; }
    public String getRutaEstomago() { return rutaEstomago; }
    public int getCapturasRequeridas() { return capturasRequeridas; }
    public boolean isRedUltrasonicoCargada() { return redUltrasonicoCargada; }
    public boolean isRedCanicasCargada() { return redCanicasCargada; }
    public boolean isRedLegacyCargada() { return redLegacyCargada; }
    public List<String> getPuertosSerieDetectados() { return puertosSerieDetectados; }
    public int getClientesConectados() { return clientesConectados; }
    public String getClienteActivo() { return clienteActivo; }
    public boolean isHandshakeRecibido() { return handshakeRecibido; }
    public boolean isHandshakeOk() { return handshakeOk; }
    public String getHandshakeVersion() { return handshakeVersion; }
    public String getHandshakeRig() { return handshakeRig; }
    public long getTotalTelemetrias() { return totalTelemetrias; }
    public long getTotalErroresCliente() { return totalErroresCliente; }
    public String getUltimoErrorCliente() { return ultimoErrorCliente; }
    public String getUltimoDbgEstomago() { return ultimoDbgEstomago; }
    public String getUltimoComandoControl() { return ultimoComandoControl; }
    public SnapshotTelemetria getUltimaTelemetria() { return ultimaTelemetria; }
    public int getCapturasEstomago() { return capturasEstomago; }
    public List<RegistroCaptura> getRegistrosEstomago() { return registrosEstomago; }
    public boolean isModoEscapeActivo() { return modoEscapeActivo; }
    public EstadoRobot getEstadoFsmActual() { return estadoFsmActual; }
}
