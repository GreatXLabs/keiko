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
    // [SOLO PRUEBAS / ENTRENAMIENTO - grabacion real y reentrenamiento integrado]
    private final GrabadorDatasetReal grabadorDataset;
    private final GestorModelosYEntrenamiento gestorModelos;

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
        this.grabadorDataset = new GrabadorDatasetReal(configuracion.getDirectorioDatosReales());
        this.gestorModelos = new GestorModelosYEntrenamiento(
                configuracion.getDirectorioModelos(),
                GestorModelosYEntrenamiento.resolverDatasetBasePorDefecto("dataset_ultrasonico.csv"),
                GestorModelosYEntrenamiento.resolverDatasetBasePorDefecto("dataset_canicas.csv"),
                grabadorDataset.getArchivoUltrasonico(),
                grabadorDataset.getArchivoCanicas());
        this.monitorTui.actualizarGrabacionDataset(
                false,
                grabadorDataset.getRedObjetivo().name(),
                grabadorDataset.getEtiquetaActiva(),
                grabadorDataset.obtenerConteosPorClase());
        this.receptorSerie.setGrabadorDataset(grabadorDataset);

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
                // Sin stdin interactivo en ejecucion daemon/headless
            }
        }, "ConsolaControl-Keiko");
        consola.setDaemon(true);
        consola.start();
    }

    /**
     * Lee pulsaciones simples sin esperar Enter cuando ConsolaTui activo raw TTY via stty.
     */
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
            // Stdin cerrado
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
        } else if ("+".equals(cmd) || "=".equals(cmd) || "-".equals(cmd) || "_".equals(cmd)
                || cmd.startsWith("speed ") || cmd.startsWith("vel ")) {
            // [SOLO PRUEBAS / DEBUG - ajuste de velocidad en vivo]
            double actual = monitorTui.getEscalaVelocidad();
            double nueva = actual;
            if ("+".equals(cmd) || "=".equals(cmd)) {
                nueva = actual + 0.10;
            } else if ("-".equals(cmd) || "_".equals(cmd)) {
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
        } else if ("]".equals(cmd) || "[".equals(cmd) || cmd.startsWith("pwm ")) {
            // [SOLO PRUEBAS / DEBUG - ajuste de PWM_ZONA_MUERTA y tope en Mega]
            if ("]".equals(cmd) || "[".equals(cmd)) {
                int delta = "]".equals(cmd) ? 5 : -5;
                int minPwm = Math.max(0, Math.min(monitorTui.getPwmMax(), monitorTui.getPwmMin() + delta));
                monitorTui.setRangoPwm(minPwm, monitorTui.getPwmMax());
                enviarComandoControl(MensajeProtocolo.crearCmdPwm(monitorTui.getPwmMin(), monitorTui.getPwmMax()));
                monitorTui.registrarComandoControl("CMD:PWM:" + monitorTui.getPwmMin() + "," + monitorTui.getPwmMax());
            } else {
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
            }
        } else if ("b".equals(cmd) || "sweep".equals(cmd) || "hold".equals(cmd)) {
            // [SOLO PRUEBAS / DEBUG - activar o pausar barrido servo en reposo]
            boolean sweep = "b".equals(cmd) ? !monitorTui.isBarridoEnReposo() : "sweep".equals(cmd);
            monitorTui.setBarridoEnReposo(sweep);
            String modo = sweep ? "SWEEP" : "HOLD";
            enviarComandoControl(MensajeProtocolo.crearCmdLaser(modo));
            monitorTui.registrarComandoControl("LASER:" + modo);
            if (consolaTui == null) {
                System.out.println("[CONTROL] Enviado LASER:" + modo);
            }
        } else if ("1".equals(cmd) || "busqueda".equals(cmd)) {
            // [SOLO PRUEBAS / DEBUG - forzar modo BUSQUEDA]
            planificador.forzarModoBusqueda();
            monitorTui.setEstadoFsmActual(EstadoRobot.BUSQUEDA);
            enviarComandoControl(MensajeProtocolo.crearEstadoCambio(EstadoRobot.BUSQUEDA));
            monitorTui.registrarComandoControl("FORZAR:BUSQUEDA");
            if (consolaTui == null) {
                System.out.println("[CONTROL] Forzado modo BUSQUEDA.");
            }
        } else if ("2".equals(cmd) || "escape".equals(cmd)) {
            // [SOLO PRUEBAS / DEBUG - forzar modo ESCAPE sin esperar 3 canicas]
            planificador.forzarModoEscape();
            monitorTui.setEstadoFsmActual(EstadoRobot.ESCAPE);
            enviarComandoControl(MensajeProtocolo.crearEstadoCambio(EstadoRobot.ESCAPE));
            monitorTui.registrarComandoControl("FORZAR:ESCAPE");
            if (consolaTui == null) {
                System.out.println("[CONTROL] Forzado modo ESCAPE.");
            }
        } else if ("3".equals(cmd) || "captura".equals(cmd)) {
            // [SOLO PRUEBAS / DEBUG - forzar CAPTURA en corrida o toggle PICKER en reposo]
            if (monitorTui.isCorridaActiva()) {
                planificador.forzarModoCaptura();
                monitorTui.setEstadoFsmActual(EstadoRobot.CAPTURA);
                enviarComandoControl(MensajeProtocolo.crearEstadoCambio(EstadoRobot.CAPTURA));
                monitorTui.registrarComandoControl("FORZAR:CAPTURA");
            } else {
                boolean activar = !monitorTui.isPickerManualActivo();
                monitorTui.setPickerManualActivo(activar);
                monitorTui.setEstadoFsmActual(activar ? EstadoRobot.CAPTURA : EstadoRobot.BUSQUEDA);
                enviarComandoControl(MensajeProtocolo.crearPicker(activar));
                monitorTui.registrarComandoControl(activar ? "PICKER:ON (CAPTURA)" : "PICKER:OFF");
            }
        } else if ("p".equals(cmd) || "picker".equals(cmd) || "picker on".equals(cmd) || "picker off".equals(cmd)) {
            // [SOLO PRUEBAS / DEBUG - prueba directa de picker en banco]
            boolean activar;
            if ("picker on".equals(cmd)) {
                activar = true;
            } else if ("picker off".equals(cmd)) {
                activar = false;
            } else {
                activar = !monitorTui.isPickerManualActivo();
            }
            monitorTui.setPickerManualActivo(activar);
            String msg = MensajeProtocolo.crearPicker(activar);
            enviarComandoControl(msg);
            monitorTui.registrarComandoControl(msg);
            if (consolaTui == null) {
                System.out.println("[CONTROL] Enviado " + msg);
            }
        } else if ("u".equals(cmd)) {
            // [SOLO PRUEBAS / ENTRENAMIENTO] Seleccionar modo US y ciclar etiqueta objetivo
            String etiqueta = grabadorDataset.ciclarModoUs();
            sincronizarEstadoGrabacionTui();
            monitorTui.registrarComandoControl("Etiq US=" + etiqueta);
        } else if ("m".equals(cmd)) {
            // [SOLO PRUEBAS / ENTRENAMIENTO] Seleccionar modo CANICAS y ciclar etiqueta objetivo
            String etiqueta = grabadorDataset.ciclarModoCanicas();
            sincronizarEstadoGrabacionTui();
            monitorTui.registrarComandoControl("Etiq CAN=" + etiqueta);
        } else if ("g".equals(cmd) || "rec on".equals(cmd) || "rec off".equals(cmd)) {
            // [SOLO PRUEBAS / ENTRENAMIENTO] Iniciar/pausar grabacion de rafagas CSV reales
            boolean activar = "g".equals(cmd) ? grabadorDataset.alternarGrabacion() : "rec on".equals(cmd);
            if (!"g".equals(cmd)) {
                grabadorDataset.setGrabando(activar);
            }
            sincronizarEstadoGrabacionTui();
            monitorTui.registrarComandoControl(activar
                    ? "REC:ON (" + grabadorDataset.getEtiquetaActiva() + ")"
                    : "REC:PAUSA");
        } else if (cmd.startsWith("label ")) {
            // [SOLO PRUEBAS / ENTRENAMIENTO] Fijar etiqueta explicita por nombre
            if (grabadorDataset.seleccionarEtiqueta(cmd.substring(6))) {
                sincronizarEstadoGrabacionTui();
                monitorTui.registrarComandoControl("Etiq=" + grabadorDataset.getEtiquetaActiva());
            }
        } else if ("t".equals(cmd) || "retrain".equals(cmd) || cmd.startsWith("retrain ")) {
            // [SOLO PRUEBAS / ENTRENAMIENTO] Reentrenar redes Neuroph 2.98 en segundo plano + hot-reload
            if (monitorTui.isReentrenando()) {
                monitorTui.agregarLog("WARN", "Ya hay un reentrenamiento en curso.");
                return;
            }
            String nota = cmd.startsWith("retrain ") ? cmd.substring(8).trim() : "TUI";
            Thread hiloTrain = new Thread(() -> ejecutarRetrainSincrono(nota), "RetrainNeuroph-Keiko");
            hiloTrain.setDaemon(true);
            hiloTrain.start();
        } else if ("models".equals(cmd)) {
            List<GestorModelosYEntrenamiento.EntradaHistorialModelo> hist = gestorModelos.listarHistorial();
            if (hist.isEmpty()) {
                monitorTui.agregarLog("INFO", "Historial vacio (sin versiones guardadas aun).");
            } else {
                for (int i = 0; i < Math.min(5, hist.size()); i++) {
                    GestorModelosYEntrenamiento.EntradaHistorialModelo e = hist.get(i);
                    monitorTui.agregarLog("INFO", String.format(java.util.Locale.US,
                            "Model %s | US:%.1f%% CAN:%.1f%% | %s",
                            e.id(), e.accuracyRed1(), e.accuracyRed2(), e.nota()));
                }
            }
        } else if (cmd.startsWith("use-model ")) {
            String id = cmd.substring(10).trim();
            if (gestorModelos.activarVersion(id, clasificador)) {
                monitorTui.registrarComandoControl("USE-MODEL:" + id);
            } else {
                monitorTui.agregarLog("WARN", "No se encontro la version de modelo: " + id);
            }
        } else if ("r".equals(cmd) || "reset".equals(cmd)) {
            gestorEstomago.vaciarEstomago();
            planificador.reiniciarParaPrueba();
            monitorTui.setPickerManualActivo(false);
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

    private void sincronizarEstadoGrabacionTui() {
        monitorTui.actualizarGrabacionDataset(
                grabadorDataset.isGrabando(),
                grabadorDataset.getRedObjetivo().name(),
                grabadorDataset.getEtiquetaActiva(),
                grabadorDataset.obtenerConteosPorClase());
    }

    GestorModelosYEntrenamiento.ResultadoReentrenamiento ejecutarRetrainSincrono(String nota) {
        monitorTui.registrarEstadoEntrenamiento(true, "Entrenando...");
        monitorTui.agregarLog("INFO", "Iniciando reentrenamiento Neuroph 2.98 (backup + real + base)...");
        try {
            GestorModelosYEntrenamiento.ResultadoReentrenamiento res =
                    gestorModelos.reentrenarYActivar(nota, clasificador);
            StringBuilder resumen = new StringBuilder();
            if (res.redUltrasonico().entrenada()) {
                resumen.append(String.format(java.util.Locale.US, "US:%.1f%% ", res.redUltrasonico().accuracyPct()));
            }
            if (res.redCanicas().entrenada()) {
                resumen.append(String.format(java.util.Locale.US, "CAN:%.1f%% ", res.redCanicas().accuracyPct()));
            }
            String txt = resumen.toString().trim() + " [" + res.versionId() + "]";
            monitorTui.registrarEstadoEntrenamiento(false, txt);
            monitorTui.agregarLog("OK", "Redes actualizadas y recargadas en vivo: " + txt);
            return res;
        } catch (Exception e) {
            monitorTui.registrarEstadoEntrenamiento(false, "ERROR");
            monitorTui.agregarLog("WARN", "Reentrenamiento omitido: " + e.getMessage());
            return null;
        }
    }

    GrabadorDatasetReal getGrabadorDataset() {
        return grabadorDataset;
    }

    GestorModelosYEntrenamiento getGestorModelos() {
        return gestorModelos;
    }

    EstadoMonitorTui getMonitorTui() {
        return monitorTui;
    }

    PlanificadorNavegacion getPlanificador() {
        return planificador;
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

        if (cfg.isListarModelosSolicitado()) {
            GestorModelosYEntrenamiento gestor = crearGestorModelosCli(cfg);
            List<GestorModelosYEntrenamiento.EntradaHistorialModelo> hist = gestor.listarHistorial();
            System.out.println("Historial de modelos (.nnet) (" + hist.size() + "):");
            for (GestorModelosYEntrenamiento.EntradaHistorialModelo e : hist) {
                System.out.printf(java.util.Locale.US,
                        "  %-22s | US:%5.1f%% (%d) | CAN:%5.1f%% (%d) | sha=(%s,%s) | %s%n",
                        e.id(), e.accuracyRed1(), e.muestrasUsReal(),
                        e.accuracyRed2(), e.muestrasCanReal(), e.shaUs(), e.shaCan(), e.nota());
            }
            return;
        }

        if (cfg.getVersionModeloSolicitada() != null && !cfg.getVersionModeloSolicitada().isBlank()) {
            GestorModelosYEntrenamiento gestor = crearGestorModelosCli(cfg);
            boolean ok = gestor.activarVersion(cfg.getVersionModeloSolicitada(), null);
            System.out.println(ok
                    ? "[OK] Version activada: " + cfg.getVersionModeloSolicitada()
                    : "[ERROR] Version no encontrada en historial: " + cfg.getVersionModeloSolicitada());
            return;
        }

        if (cfg.isRetrainRealSolicitado()) {
            GestorModelosYEntrenamiento gestor = crearGestorModelosCli(cfg);
            GestorModelosYEntrenamiento.ResultadoReentrenamiento res =
                    gestor.reentrenarYActivar(cfg.getNotaModelo(), null);
            imprimirResumenEntrenamiento(res);
            return;
        }

        if (cfg.isResetSolicitado()) {
            new GestorEstomago(cfg.getArchivoEstomago()).vaciarEstomago();
        }

        ServidorCentral server = new ServidorCentral(cfg);
        server.iniciar();
    }

    private static GestorModelosYEntrenamiento crearGestorModelosCli(ConfiguracionServidor cfg) {
        GrabadorDatasetReal grab = new GrabadorDatasetReal(cfg.getDirectorioDatosReales());
        return new GestorModelosYEntrenamiento(
                cfg.getDirectorioModelos(),
                GestorModelosYEntrenamiento.resolverDatasetBasePorDefecto("dataset_ultrasonico.csv"),
                GestorModelosYEntrenamiento.resolverDatasetBasePorDefecto("dataset_canicas.csv"),
                grab.getArchivoUltrasonico(),
                grab.getArchivoCanicas());
    }

    private static void imprimirResumenEntrenamiento(GestorModelosYEntrenamiento.ResultadoReentrenamiento res) {
        System.out.println("=== REENTRENAMIENTO NEUROPH 2.98 COMPLETADO ===");
        System.out.println("Version ID : " + res.versionId());
        System.out.println("SHA-256    : US=" + res.shaUs() + " | CAN=" + res.shaCan());
        if (res.redUltrasonico().entrenada()) {
            System.out.printf(java.util.Locale.US,
                    "Red 1 (US) : Acc=%.2f%% (train=%d, test=%d, reales=%d)%n",
                    res.redUltrasonico().accuracyPct(), res.redUltrasonico().muestrasTrain(),
                    res.redUltrasonico().muestrasTest(), res.muestrasUsReal());
            imprimirMatrizConfusion(res.redUltrasonico());
        }
        if (res.redCanicas().entrenada()) {
            System.out.printf(java.util.Locale.US,
                    "Red 2 (CAN): Acc=%.2f%% (train=%d, test=%d, reales=%d)%n",
                    res.redCanicas().accuracyPct(), res.redCanicas().muestrasTrain(),
                    res.redCanicas().muestrasTest(), res.muestrasCanReal());
            imprimirMatrizConfusion(res.redCanicas());
        }
    }

    private static void imprimirMatrizConfusion(GestorModelosYEntrenamiento.ResultadoRed red) {
        int[][] m = red.matrizConfusion();
        if (m == null) return;
        for (int i = 0; i < m.length; i++) {
            System.out.printf("  %-14s: %s%n", red.etiquetas()[i], java.util.Arrays.toString(m[i]));
        }
    }
}
