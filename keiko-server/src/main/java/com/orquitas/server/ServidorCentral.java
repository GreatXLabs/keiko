package com.orquitas.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Punto de entrada oficial del Servidor Central (Cerebro - Actor A-02).
 * Opera exclusivamente sobre enlace serie Bluetooth RFCOMM/SPP e integra:
 * ClasificadorNeuroph (RF-02), PlanificadorNavegacion (RF-03), GestorEstomago (RF-06),
 * AuditorEstomago (RF-07 / RF-08) y TUI de solo lectura operativa.
 */
public class ServidorCentral implements OyenteAuditor {
    private static final Logger LOGGER = Logger.getLogger(ServidorCentral.class.getName());
    // Referencia fuerte: si el Logger se recolecta, pierde el FileHandler.
    private static final Logger LOGGER_PAQUETE = Logger.getLogger("com.orquitas.server");

    private final ConfiguracionServidor configuracion;
    private final GestorEstomago gestorEstomago;
    private final ClasificadorNeuroph clasificador;
    private final PlanificadorNavegacion planificador;
    private final ReceptorSerie receptorSerie;
    private final AuditorEstomago auditor;
    private final EstadoMonitorTui monitorTui;
    private final ConsolaTui consolaTui;
    private volatile FileHandler fileHandlerLogs;
    private volatile String rutaLogArchivo;

    public ServidorCentral(ConfiguracionServidor configuracion) {
        this.configuracion = configuracion;
        this.gestorEstomago = new GestorEstomago(configuracion.getArchivoEstomago());
        this.clasificador = new ClasificadorNeuroph(configuracion.resolverArchivoModelo());
        this.planificador = new PlanificadorNavegacion(gestorEstomago, ConfiguracionServidor.PASOS_REVERSA_FIJO);
        this.monitorTui = new EstadoMonitorTui(
                configuracion,
                clasificador,
                gestorEstomago.contarCapturasValidas(),
                gestorEstomago.obtenerCapturasValidas());
        boolean tuiActiva = configuracion.resolverModoTui(System.console() != null);
        this.consolaTui = tuiActiva ? new ConsolaTui(monitorTui) : null;

        this.receptorSerie = new ReceptorSerie(
                configuracion.getPuertoSerie(), configuracion.getBaudiosSerie(),
                gestorEstomago, clasificador, planificador,
                configuracion.getCooldownCapturaMs(), configuracion.getIntervaloHeartbeatMs(),
                configuracion.isAutoStartSolicitado(), monitorTui);

        this.auditor = new AuditorEstomago(
                gestorEstomago, configuracion.getAuditorSondeoMs(), configuracion.getCapturasRequeridasEscape());
        this.auditor.agregarOyente(this);
    }

    public void iniciar() {
        configurarLogArchivo();
        List<String> puertos = ReceptorSerie.listarPuertosDisponibles();
        monitorTui.actualizarPuertosSerie(puertos);

        int iniciales = gestorEstomago.contarCapturasValidas();
        if (iniciales >= configuracion.getCapturasRequeridasEscape()) {
            planificador.activarModoEscape();
        }

        if (consolaTui != null) {
            consolaTui.iniciar();
            monitorTui.agregarLog("INFO", String.format("Persistencia: %s (%d/%d capturas)",
                    configuracion.getArchivoEstomago().getName(), iniciales, configuracion.getCapturasRequeridasEscape()));
            monitorTui.agregarLog("INFO", String.format("Bluetooth SPP: %s @ %d bps",
                    configuracion.getPuertoSerie(), configuracion.getBaudiosSerie()));
            if (rutaLogArchivo != null) {
                monitorTui.agregarLog("INFO", "Log de corrida: " + rutaLogArchivo);
            }
            monitorTui.agregarLog("OK", "Servidor listo esperando telemetria de la Orquita por Bluetooth.");
        } else {
            imprimirBanner();
            imprimirPuertosSerie(puertos);
            if (rutaLogArchivo != null) {
                System.out.println("[LOG] Guardando corrida en: " + rutaLogArchivo);
            }
            System.out.printf("[PERSISTENCIA] Archivo: %s | Capturas fisicas recuperadas: %d/%d%n",
                    configuracion.getArchivoEstomago().getAbsolutePath(), iniciales, configuracion.getCapturasRequeridasEscape());
            if (iniciales >= configuracion.getCapturasRequeridasEscape()) {
                System.out.println("[ESTADO RESTAURADO] El estomago persistente ya tiene "
                        + configuracion.getCapturasRequeridasEscape() + " capturas. Iniciando en MODO_ESCAPE.");
            }
            System.out.println("[SISTEMA LISTO] Servidor operativo por Bluetooth y a la espera de telemetria de la Orquita...");
            System.out.println("[CONTROL] Escribi 'start' (CMD:START), 'stop' (CMD:STOP) o 'reset' en reposo, o usa --auto-start.\n");
        }

        auditor.iniciar();
        receptorSerie.iniciar();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (consolaTui != null) {
                consolaTui.detener();
            }
            System.out.println("\n[SHUTDOWN] Deteniendo componentes concurrentes...");
            auditor.detener();
            receptorSerie.detener();
            cerrarLogArchivo();
            System.out.println("[SHUTDOWN] Servidor Central detenido limpiamente.");
        }));

        iniciarConsolaControl();
    }

    private void configurarLogArchivo() {
        try {
            File dirLogs = new File("logs");
            if (!dirLogs.exists() && !dirLogs.mkdirs()) {
                return;
            }
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            File archivo = new File(dirLogs, "corrida_" + ts + ".log");
            FileHandler fh = new FileHandler(archivo.getPath(), false);
            fh.setFormatter(new SimpleFormatter());
            // Al logger del paquete y no al raiz: ConsolaTui corta la propagacion al raiz (setUseParentHandlers(false)).
            LOGGER_PAQUETE.addHandler(fh);
            this.fileHandlerLogs = fh;
            this.rutaLogArchivo = archivo.getPath();
        } catch (Exception e) {
            LOGGER.warning("[LOG] No se pudo habilitar archivo automatico de corrida: " + e.getMessage());
        }
    }

    private void cerrarLogArchivo() {
        FileHandler fh = this.fileHandlerLogs;
        if (fh != null) {
            try {
                LOGGER_PAQUETE.removeHandler(fh);
                fh.close();
            } catch (Exception ignored) {
            }
            this.fileHandlerLogs = null;
        }
    }

    private void iniciarConsolaControl() {
        Thread consola = new Thread(() -> {
            if (consolaTui != null && consolaTui.isEntradaInstantaneaActiva()) {
                ejecutarBucleTeclaInstantanea();
                return;
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    String cmd = linea.trim().toLowerCase();
                    procesarComandoConsola(cmd);
                    if (consolaTui != null) {
                        consolaTui.solicitarLimpiezaCompleta();
                    }
                }
            } catch (Exception ignored) {
            }
        }, "ConsolaControl-Keiko");
        consola.setDaemon(true);
        consola.start();
    }

    private void ejecutarBucleTeclaInstantanea() {
        try {
            int b;
            while ((b = System.in.read()) != -1) {
                if (b == 27) {
                    descartarSecuenciaEscape();
                    continue;
                }
                if (b == '\n' || b == '\r') {
                    if (consolaTui != null) {
                        consolaTui.solicitarLimpiezaCompleta();
                    }
                    continue;
                }
                char c = Character.toLowerCase((char) b);
                if (c == 'q') {
                    auditor.detener();
                    receptorSerie.detener();
                    if (consolaTui != null) {
                        consolaTui.detener();
                    }
                    System.exit(0);
                    return;
                }
                procesarComandoConsola(String.valueOf(c));
            }
        } catch (Exception ignored) {
        }
    }

    private void descartarSecuenciaEscape() {
        try {
            if (System.in.available() <= 0) {
                return;
            }
            int next = System.in.read();
            if (next == '[') {
                while (System.in.available() > 0) {
                    int c = System.in.read();
                    if (c >= 64 && c <= 126) {
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    void procesarComandoConsola(String cmd) {
        if (cmd == null || cmd.isBlank()) {
            return;
        }
        if ("start".equals(cmd) || "k".equals(cmd)) {
            receptorSerie.marcarStartManual();
            monitorTui.setCorridaActiva(true);
            enviarComandoControl(MensajeProtocolo.crearCmdStart());
            monitorTui.registrarComandoControl("CMD:START");
            if (consolaTui == null) {
                System.out.println("[CONTROL] Enviado CMD:START a clientes conectados.");
            }
        } else if ("stop".equals(cmd) || "x".equals(cmd)) {
            monitorTui.setCorridaActiva(false);
            enviarComandoControl(MensajeProtocolo.crearCmdStop());
            monitorTui.registrarComandoControl("CMD:STOP");
            if (consolaTui == null) {
                System.out.println("[CONTROL] Enviado CMD:STOP a clientes conectados.");
            }
        } else if ("r".equals(cmd) || "reset".equals(cmd)) {
            if (monitorTui.isCorridaActiva()) {
                monitorTui.agregarLog("WARN", "Reset bloqueado durante la corrida: detener antes con [x]/stop.");
                return;
            }
            gestorEstomago.vaciarEstomago();
            planificador.reiniciarEstado();
            monitorTui.registrarReset();
            if (consolaTui == null) {
                System.out.println("[CONTROL] Estomago vaciado y planificador reiniciado a BUSQUEDA.");
            }
        } else if ("view".equals(cmd) || "v".equals(cmd)) {
            if (consolaTui != null) {
                boolean compacto = consolaTui.alternarModoCompacto();
                monitorTui.agregarLog("CTRL", "Vista cambiada a modo " + (compacto ? "COMPACTO" : "DETALLADO"));
            }
        } else if ("clear".equals(cmd) || "c".equals(cmd)) {
            monitorTui.limpiarLogs();
        }
    }

    EstadoMonitorTui getMonitorTui() {
        return monitorTui;
    }

    PlanificadorNavegacion getPlanificador() {
        return planificador;
    }

    GestorEstomago getGestorEstomago() {
        return gestorEstomago;
    }

    public void enviarComandoControl(String mensaje) {
        receptorSerie.transmitirATodos(mensaje);
    }

    private void imprimirBanner() {
        ConsolaTui.imprimirBannerPlano(
                System.out, ConfiguracionServidor.VERSION_SISTEMA, ConfiguracionServidor.RIG_HARDWARE);
    }

    private void imprimirPuertosSerie(List<String> puertos) {
        System.out.println("[PUERTOS SERIE / BLUETOOTH SPP DETECTADOS]:");
        if (puertos.isEmpty()) {
            System.out.println("   (ninguno detectado actualmente; podes usar --serial COMx o /dev/rfcomm0)");
        } else {
            for (String p : puertos) {
                System.out.println("   - " + p);
            }
        }
        System.out.printf("[MODO BLUETOOTH ACTIVO] Escuchando puerto serie %s @ %d bps%n",
                configuracion.getPuertoSerie(), configuracion.getBaudiosSerie());
    }

    @Override
    public void onCapturaAuditada(int total) {
        monitorTui.actualizarEstomago(
                total,
                gestorEstomago.obtenerCapturasValidas(),
                total >= configuracion.getCapturasRequeridasEscape());
        if (consolaTui != null) {
            monitorTui.agregarLog("INFO", String.format("[AUDITOR] Capturas en estomago.txt: %d/%d",
                    total, configuracion.getCapturasRequeridasEscape()));
        } else {
            System.out.printf("[AUDITOR] Capturas confirmadas en estomago.txt: %d/%d%n",
                    total, configuracion.getCapturasRequeridasEscape());
        }
    }

    /** RF-07 / RF-08: Al confirmar 3 capturas fisicas, activa ESCAPE y ordena STATE_CHANGE:ESCAPE. */
    @Override
    public void onModoEscapeActivado(List<RegistroCaptura> caps) {
        planificador.activarModoEscape();
        monitorTui.actualizarEstomago(
                caps != null ? caps.size() : gestorEstomago.contarCapturasValidas(),
                caps,
                true);
        monitorTui.agregarLog("EVENT", "AUDITOR ACTIVO MODO ESCAPE (3/3 capturas confirmadas)");
        enviarComandoControl(MensajeProtocolo.crearModoEscape());
    }

    public static void main(String[] args) {
        ConfiguracionServidor cfg = ConfiguracionServidor.desdeArgumentos(args);

        if (cfg.isListarPuertosSolicitado()) {
            List<String> puertos = ReceptorSerie.listarPuertosDisponibles();
            System.out.println("Puertos serie disponibles (" + puertos.size() + "):");
            for (String p : puertos) {
                System.out.println("  " + p);
            }
            return;
        }

        if (cfg.isResetSolicitado()) {
            new GestorEstomago(cfg.getArchivoEstomago()).vaciarEstomago();
        }

        ServidorCentral server = new ServidorCentral(cfg);
        server.iniciar();
    }
}
