package com.orquitas.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

/**
 * Punto de entrada oficial del Servidor Central (Cerebro de la Orquita).
 * Opera exclusivamente sobre enlace serie Bluetooth RFCOMM/SPP (sin servidor TCP).
 */
public class ServidorCentral implements OyenteAuditor {
    private static final Logger LOGGER = Logger.getLogger(ServidorCentral.class.getName());

    private final ConfiguracionServidor configuracion;
    private final GestorEstomago gestorEstomago;
    private final ClasificadorNeuroph clasificador;
    private final PlanificadorNavegacion planificador;
    private final ReceptorSerie receptorSerie;
    private final AuditorEstomago auditor;
    private final EstadoMonitorTui monitorTui;
    private final ConsolaTui consolaTui;

    public ServidorCentral(ConfiguracionServidor configuracion) {
        this.configuracion = configuracion;
        this.gestorEstomago = new GestorEstomago(configuracion.getArchivoEstomago());
        this.clasificador = new ClasificadorNeuroph(configuracion.resolverArchivoModelo());
        this.planificador = new PlanificadorNavegacion(gestorEstomago);
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
            monitorTui.agregarLog("OK", "Servidor listo esperando telemetria de la Orquita por Bluetooth.");
        } else {
            imprimirBanner();
            imprimirPuertosSerie(puertos);
            System.out.printf("[PERSISTENCIA] Archivo: %s | Capturas fisicas recuperadas: %d/%d%n",
                    configuracion.getArchivoEstomago().getAbsolutePath(), iniciales, configuracion.getCapturasRequeridasEscape());
            if (iniciales >= configuracion.getCapturasRequeridasEscape()) {
                System.out.println("[ESTADO RESTAURADO] El estomago persistente ya tiene "
                        + configuracion.getCapturasRequeridasEscape() + " capturas. Iniciando en MODO_ESCAPE.");
            }
            System.out.println("[SISTEMA LISTO] Servidor operativo por Bluetooth y a la espera de telemetria de la Orquita...");
            System.out.println("[CONTROL] Escribi 'start' (CMD:START), 'stop' (CMD:STOP) o 'reset' en consola, o usa --auto-start.\n");
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
            System.out.println("[SHUTDOWN] Servidor Central detenido limpiamente.");
        }));

        iniciarConsolaControl();
    }

    private void iniciarConsolaControl() {
        Thread consola = new Thread(() -> {
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
                // Sin stdin interactivo en ejecucion daemon/headless
            }
        }, "ConsolaControl-Keiko");
        consola.setDaemon(true);
        consola.start();
    }

    void procesarComandoConsola(String cmd) {
        if ("start".equals(cmd) || "k".equals(cmd)) {
            monitorTui.setCorridaActiva(true);
            enviarComandoControl(MensajeProtocolo.crearCmdPwm(monitorTui.getPwmMin(), monitorTui.getPwmMax()));
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
        } else if ("+".equals(cmd) || "-".equals(cmd) || cmd.startsWith("speed ") || cmd.startsWith("vel ")) {
            // [SOLO PRUEBAS / DEBUG - ajuste de velocidad en vivo]
            double actual = monitorTui.getEscalaVelocidad();
            double nueva = actual;
            if ("+".equals(cmd)) {
                nueva = actual + 0.10;
            } else if ("-".equals(cmd)) {
                nueva = actual - 0.10;
            } else {
                String[] partes = cmd.split("\\s+");
                if (partes.length >= 2) {
                    try {
                        double val = Double.parseDouble(partes[1].replace("%", ""));
                        nueva = (val > 2.0) ? (val / 100.0) : val;
                    } catch (NumberFormatException ignored) {}
                }
            }
            monitorTui.setEscalaVelocidad(nueva);
            int pct = (int) Math.round(monitorTui.getEscalaVelocidad() * 100.0);
            monitorTui.registrarComandoControl("SPEED=" + pct + "%");
            if (consolaTui == null) {
                System.out.println("[CONTROL] Escala de velocidad ajustada a " + pct + "%.");
            }
        } else if (cmd.startsWith("pwm ")) {
            // [SOLO PRUEBAS / DEBUG - ajuste de PWM_ZONA_MUERTA y tope en Mega]
            String[] partes = cmd.split("\\s+");
            if (partes.length >= 2) {
                try {
                    int minPwm = Integer.parseInt(partes[1]);
                    int maxPwm = (partes.length >= 3) ? Integer.parseInt(partes[2]) : monitorTui.getPwmMax();
                    monitorTui.setRangoPwm(minPwm, maxPwm);
                    enviarComandoControl(MensajeProtocolo.crearCmdPwm(monitorTui.getPwmMin(), monitorTui.getPwmMax()));
                    monitorTui.registrarComandoControl("CMD:PWM:" + monitorTui.getPwmMin() + "," + monitorTui.getPwmMax());
                    if (consolaTui == null) {
                        System.out.printf("[CONTROL] Enviado CMD:PWM:%d,%d%n", monitorTui.getPwmMin(), monitorTui.getPwmMax());
                    }
                } catch (NumberFormatException ignored) {}
            }
        } else if ("sweep".equals(cmd) || "hold".equals(cmd)) {
            // [SOLO PRUEBAS / DEBUG - activar o pausar barrido servo en reposo]
            boolean sweep = "sweep".equals(cmd);
            monitorTui.setBarridoEnReposo(sweep);
            String modo = sweep ? "SWEEP" : "HOLD";
            enviarComandoControl(MensajeProtocolo.crearCmdLaser(modo));
            monitorTui.registrarComandoControl("LASER:" + modo);
            if (consolaTui == null) {
                System.out.println("[CONTROL] Enviado LASER:" + modo);
            }
        } else if ("reset".equals(cmd)) {
            gestorEstomago.vaciarEstomago();
            planificador.reiniciarParaPrueba();
            monitorTui.registrarResetPrueba();
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
