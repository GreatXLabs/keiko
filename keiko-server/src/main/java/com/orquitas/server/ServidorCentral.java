package com.orquitas.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Logger;

/**
 * Punto de entrada oficial del Servidor Central (Cerebro de la Orquita).
 */
public class ServidorCentral implements OyenteAuditor {
    private static final Logger LOGGER = Logger.getLogger(ServidorCentral.class.getName());

    private final ConfiguracionServidor configuracion;
    private final GestorEstomago gestorEstomago;
    private final ClasificadorNeuroph clasificador;
    private final PlanificadorNavegacion planificador;
    private final ReceptorConexiones receptorConexiones;
    private final ReceptorSerie receptorSerie;
    private final AuditorEstomago auditor;

    public ServidorCentral(ConfiguracionServidor configuracion) {
        this.configuracion = configuracion;
        this.gestorEstomago = new GestorEstomago(configuracion.getArchivoEstomago());
        this.clasificador = new ClasificadorNeuroph(configuracion.resolverArchivoModelo());
        this.planificador = new PlanificadorNavegacion(gestorEstomago);
        this.receptorConexiones = new ReceptorConexiones(
                configuracion.getPuerto(), gestorEstomago, clasificador, planificador,
                configuracion.getCooldownCapturaMs(), configuracion.getIntervaloHeartbeatMs(),
                configuracion.isAutoStartSolicitado());

        if (configuracion.getPuertoSerie() != null && !configuracion.getPuertoSerie().isBlank()) {
            this.receptorSerie = new ReceptorSerie(
                    configuracion.getPuertoSerie(), configuracion.getBaudiosSerie(),
                    gestorEstomago, clasificador, planificador,
                    configuracion.getCooldownCapturaMs(), configuracion.getIntervaloHeartbeatMs(),
                    configuracion.isAutoStartSolicitado());
        } else {
            this.receptorSerie = null;
        }

        this.auditor = new AuditorEstomago(
                gestorEstomago, configuracion.getAuditorSondeoMs(), configuracion.getCapturasRequeridasEscape());
        this.auditor.agregarOyente(this);
    }

    public void iniciar() {
        imprimirBanner();
        imprimirDireccionesRed();
        imprimirPuertosSerie();

        int iniciales = gestorEstomago.contarCapturasValidas();
        System.out.printf("[PERSISTENCIA] Archivo: %s | Capturas fisicas recuperadas: %d/%d%n",
                configuracion.getArchivoEstomago().getAbsolutePath(), iniciales, configuracion.getCapturasRequeridasEscape());

        if (iniciales >= configuracion.getCapturasRequeridasEscape()) {
            System.out.println("[ESTADO RESTAURADO] El estomago persistente ya tiene "
                    + configuracion.getCapturasRequeridasEscape() + " capturas. Iniciando en MODO_ESCAPE.");
            planificador.activarModoEscape();
        }

        auditor.iniciar();
        receptorConexiones.iniciar();
        if (receptorSerie != null) {
            receptorSerie.iniciar();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[SHUTDOWN] Deteniendo componentes concurrentes...");
            auditor.detener();
            if (receptorSerie != null) {
                receptorSerie.detener();
            }
            receptorConexiones.detener();
            System.out.println("[SHUTDOWN] Servidor Central detenido limpiamente.");
        }));

        System.out.println("[SISTEMA LISTO] Servidor operativo y a la espera de telemetria de la Orquita...");
        System.out.println("[CONTROL] Escribi 'start' (CMD:START) o 'stop' (CMD:STOP) en consola, o usa --auto-start.\n");
        iniciarConsolaControl();
    }

    private void iniciarConsolaControl() {
        Thread consola = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    String cmd = linea.trim().toLowerCase();
                    if ("start".equals(cmd) || "k".equals(cmd)) {
                        enviarComandoControl(MensajeProtocolo.crearCmdStart());
                        System.out.println("[CONTROL] Enviado CMD:START a clientes conectados.");
                    } else if ("stop".equals(cmd) || "x".equals(cmd)) {
                        enviarComandoControl(MensajeProtocolo.crearCmdStop());
                        System.out.println("[CONTROL] Enviado CMD:STOP a clientes conectados.");
                    }
                }
            } catch (Exception ignored) {
                // Sin stdin interactivo en ejecucion daemon/headless
            }
        }, "ConsolaControl-Keiko");
        consola.setDaemon(true);
        consola.start();
    }

    public void enviarComandoControl(String mensaje) {
        receptorConexiones.transmitirATodos(mensaje);
        if (receptorSerie != null) {
            receptorSerie.transmitirATodos(mensaje);
        }
    }

    private void imprimirBanner() {
        System.out.println("______ __    ___________        ");
        System.out.println("___  //_/_______(_)__  /_______ ");
        System.out.println("__  ,<  _  _ \\_  /__  //_/  __ \\");
        System.out.println("_  /| | /  __/  / _  ,<  / /_/ /");
        System.out.println("/_/ |_| \\___//_/  /_/|_| \\____/ ");
        System.out.println();
        System.out.println("==================================================================");
        System.out.println("  PROYECTO ORQUITAS (KEIKO) - SERVIDOR CENTRAL (CEREBRO)");
        System.out.printf("  Version: %s | Rig: %s%n",
                ConfiguracionServidor.VERSION_SISTEMA, ConfiguracionServidor.RIG_HARDWARE);
        System.out.println("  Programacion Sobre Redes & Desarrollo de Sistemas - Ciclo 2026");
        System.out.println("  Neuroph 2.98 (2 Redes: 3US + 2Laser) + Bluetooth SPP / TCP");
        System.out.println("==================================================================\n");
    }

    private void imprimirDireccionesRed() {
        try {
            System.out.println("[INTERFACES TCP DISPONIBLES]:");
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        System.out.printf("   - %s: %s (Puerto TCP: %d)%n",
                                iface.getName(), addr.getHostAddress(), configuracion.getPuerto());
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void imprimirPuertosSerie() {
        List<String> puertos = ReceptorSerie.listarPuertosDisponibles();
        System.out.println("[PUERTOS SERIE / BLUETOOTH SPP DETECTADOS]:");
        if (puertos.isEmpty()) {
            System.out.println("   (ninguno detectado actualmente; podes usar --serial COMx o /dev/rfcomm0)");
        } else {
            for (String p : puertos) {
                System.out.println("   - " + p);
            }
        }
        if (configuracion.getPuertoSerie() != null) {
            System.out.printf("[MODO HARDWARE ACTIVO] Escuchando puerto serie %s @ %d bps%n",
                    configuracion.getPuertoSerie(), configuracion.getBaudiosSerie());
        }
    }

    @Override
    public void onCapturaAuditada(int total) {
        System.out.printf("[AUDITOR] Capturas confirmadas en estomago.txt: %d/%d%n",
                total, configuracion.getCapturasRequeridasEscape());
    }

    @Override
    public void onModoEscapeActivado(List<RegistroCaptura> caps) {
        planificador.activarModoEscape();
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
