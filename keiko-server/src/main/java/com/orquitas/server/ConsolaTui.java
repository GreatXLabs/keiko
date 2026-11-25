package com.orquitas.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Renderizador TUI en ANSI puro para el Servidor Central de Keiko.
 * Combina el banner fusionado (KEIKO sobre la orca), visualizacion en vivo de los sensores
 * (3x HC-SR04, 2x VL53L0X + servo, inferencia Neuroph 2.98), estado FSM/motores,
 * persistencia en estomago.txt y buffer de logs sin dependencias externas.
 */
public class ConsolaTui {
    private static final int ANCHO_DETALLADO = 106;
    private static final int ANCHO_COMPACTO = 92;
    private static final int ANCHO_ARTE = 64;
    private static final int ANCHO_PANEL_DER = 41;
    private static final int COL_INICIO_KEIKO = 4;
    private static final int FILAS_LOGS_DETALLADO = 5;
    private static final int FILAS_LOGS_COMPACTO = 3;
    private static final int ALTO_DEFECTO = 34;
    private static final long INTERVALO_REFRESCO_MS = 100L;
    private static final long INTERVALO_TAMANO_MS = 1000L;
    private static final double DISTANCIA_MAX_US_CM = 250.0;
    private static final double DISTANCIA_MAX_LASER_CM = 200.0;
    private static final double LASER_TIMEOUT_UMBRAL_CM = 900.0;
    private static final double SERVO_MIN_DEG = -45.0;
    private static final double SERVO_MAX_DEG = 60.0;

    private static final Pattern PATRON_ANSI = Pattern.compile("\033\\[[0-9;]*m");

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String ROJO = "\033[91m";
    private static final String VERDE = "\033[92m";
    private static final String AMARILLO = "\033[93m";
    private static final String AZUL = "\033[94m";
    private static final String MAGENTA = "\033[95m";
    private static final String CIAN = "\033[96m";
    private static final String BLANCO = "\033[97m";
    private static final String GRIS = "\033[90m";

    private static final String[] KEIKO_LETRAS = {
            "______ __    ___________        ",
            "___  //_/_______(_)__  /_______ ",
            "__  ,<  _  _ \\_  /__  //_/  __ \\",
            "_  /| | /  __/  / _  ,<  / /_/ /",
            "/_/ |_| \\___//_/  /_/|_| \\____/ "
    };

    private static final String[] ORCA_BRAILLE = {
            "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣸⣧⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
            "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢠⣿⣿⣧⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
            "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢠⣿⣿⣿⣿⡆⠀⠀⠀⠀⠀⢀⣀⣄⡀⠰⠴⣶⣶⣤⣤⡀⠀",
            "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣠⣿⣿⣿⣿⣿⡇⠀⢀⣤⣶⣻⣾⣿⣴⣴⣾⣿⣿⣿⣿⣿⣿⡆",
            "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣴⣿⣿⣿⣿⣿⣿⣥⣾⠿⢿⣿⣽⣾⣿⣿⣿⣿⣿⣿⣿⠿⢿⡿⣧",
            "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣠⣾⣿⣿⣿⣿⣿⠟⠉⠀⠀⠀⣸⣿⣿⣿⣿⡿⠟⠛⠋⠉⠐⠊⠡⢹⢚",
            "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⣀⣠⣤⣤⡴⠂⠐⠒⢨⣿⣿⣿⣿⣿⣿⣤⣆⣤⣠⣴⣾⣿⣷⡿⠋⠁⠀⠀⠀⠀⠀⠐⣁⠎⠀⡘",
            "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢐⣠⣤⣶⣾⣿⣿⣿⣿⣿⣆⡀⡀⣀⣨⣿⣿⣿⣿⣿⣿⣿⣿⣿⠟⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⡜⠀⠀⡐⠀",
            "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢠⣴⣾⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠛⠁⠀⠀⠀⠀⠀⠀⠀⠄⠀⠄⠀⠀⠀⠀⠀⠂⠀⠀",
            "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⣠⣴⡶⠿⠿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡟⡀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⡠⠂⠀⠀⠀",
            "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢀⣵⣿⣿⣅⠀⠀⠀⠀⢈⠙⢿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⠖⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣀⠂⠀⠀⠀⠀⠀",
            "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣠⣾⣿⣿⣿⣿⣿⣿⣿⣶⣦⣌⠁⠀⠉⢿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡏⡞⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣀⠜⠁⠀⠀⠀⠀⠀⠀",
            "⠀⠀⠀⣀⣀⣤⢤⢤⡴⢶⣾⡿⠿⣛⠩⠀⠉⠉⠙⠛⠻⠿⢏⡀⠀⠀⠀⠙⠻⠿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡿⢈⡷⠀⠀⠀⠀⠀⠀⠀⠀⣠⣷⣿⡀⠀⠀⠀⠀⠀⠀⠀",
            "⢠⠖⠋⠉⠀⢀⠀⠂⣌⢇⠀⣰⣿⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⠳⣄⠀⡀⠀⠀⢀⣽⣿⣿⣿⣿⣿⣿⣿⣿⡿⠋⣐⠰⠂⠀⠀⠀⠀⡀⣠⣴⣾⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⠀",
            "⠛⠓⠒⠲⢤⣀⣐⣤⡞⣸⢊⠥⣿⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠙⠀⢀⣤⣿⣿⣿⣿⣿⣿⣿⡿⠟⠋⢄⣀⠀⠠⠤⠴⠂⠈⠁⢰⣿⣿⣿⣿⣿⣿⡇⠀⠀⠀⠀⠀⠀⠀",
            "⠀⠀⠀⠀⠀⠀⠀⠀⢿⠃⠀⠀⠸⡄⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⠉⠉⠉⠉⠉⠋⠉⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠐⣿⣿⣿⣿⣿⣿⠀⠀⠀⠀⠀⠀⠀⠀",
            "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠙⢖⣦⣀⢻⡀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⢻⣿⣿⣿⣿⠃⠀⠀⠀⠀⠀⠀⠀⠀",
            "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⠛⠾⠉⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠸⣿⡿⠛⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀"
    };

    private static final String[] BANNER_FUSIONADO_PLANO = construirBannerFusionadoPlano();

    private final EstadoMonitorTui estado;
    private final PrintStream salida;
    private final AtomicBoolean ejecutando = new AtomicBoolean(false);
    private final AtomicBoolean modoCompacto = new AtomicBoolean(false);
    private final AtomicBoolean limpiarPantallaPendiente = new AtomicBoolean(true);

    private Thread hiloRender;
    private Handler handlerLogInstalado;
    private Logger loggerPaquete;
    private boolean usabaParentHandlersPrevio = true;
    private long ultimaVersionRenderizada = -1L;
    private long ultimoTimestampRenderMs = 0L;
    private long ultimoChequeoTamanoMs = 0L;
    private int ultimoAnchoTerminal = ANCHO_DETALLADO;
    private int ultimoAltoTerminal = ALTO_DEFECTO;
    private int ultimaCantidadLineas = 0;
    private volatile boolean entradaInstantaneaActiva = false;
    private String sttyOriginal = null;

    public ConsolaTui(EstadoMonitorTui estado) {
        this(estado, System.out);
    }

    public ConsolaTui(EstadoMonitorTui estado, PrintStream salida) {
        this.estado = estado;
        this.salida = salida;
    }

    /** Construye las 18 lineas del banner fusionado colocando KEIKO arriba de la orca y junto a la aleta dorsal. */
    static String[] construirBannerFusionadoPlano() {
        String[] resultado = new String[ORCA_BRAILLE.length];
        for (int r = 0; r < ORCA_BRAILLE.length; r++) {
            char[] fila = new char[ANCHO_ARTE];
            String lineaOrca = ORCA_BRAILLE[r];
            for (int c = 0; c < ANCHO_ARTE; c++) {
                char ch = (c < lineaOrca.length()) ? lineaOrca.charAt(c) : ' ';
                fila[c] = (ch == '\u2800') ? ' ' : ch;
            }
            if (r < KEIKO_LETRAS.length) {
                String letras = KEIKO_LETRAS[r];
                for (int c = 0; c < letras.length() && (COL_INICIO_KEIKO + c) < ANCHO_ARTE; c++) {
                    char ch = letras.charAt(c);
                    if (ch != ' ') {
                        fila[COL_INICIO_KEIKO + c] = ch;
                    }
                }
            }
            resultado[r] = new String(fila);
        }
        return resultado;
    }

    /** Imprime el banner fusionado plano (usado cuando la TUI interactiva esta desactivada). */
    public static void imprimirBannerPlano(PrintStream out, String version, String rig) {
        for (String linea : BANNER_FUSIONADO_PLANO) {
            out.println(linea);
        }
        out.println("==================================================================");
        out.println("  PROYECTO ORQUITAS (KEIKO) - SERVIDOR CENTRAL (CEREBRO)");
        out.printf("  Version: %s | Rig: %s%n", version, rig);
        out.println("  Programacion Sobre Redes & Desarrollo de Sistemas - Ciclo 2026");
        out.println("  Neuroph 2.98 (2 Redes: 3US + 2Laser) + Bluetooth RFCOMM/SPP");
        out.println("==================================================================\n");
    }

    public void iniciar() {
        if (!ejecutando.compareAndSet(false, true)) {
            return;
        }
        activarModoTeclaInstantanea();
        actualizarTamanoTerminal();
        instalarInterceptorLogs();
        salida.print("\033[?1049h\033[2J\033[H");
        salida.flush();

        hiloRender = new Thread(this::bucleRender, "TUI-Keiko");
        hiloRender.setDaemon(true);
        hiloRender.start();
    }

    public void detener() {
        if (!ejecutando.compareAndSet(true, false)) {
            return;
        }
        if (hiloRender != null) {
            hiloRender.interrupt();
        }
        desinstalarInterceptorLogs();
        restaurarModoTty();
        salida.print("\033[?25h\033[?1049l");
        salida.flush();
    }

    public boolean isEntradaInstantaneaActiva() {
        return entradaInstantaneaActiva;
    }

    static int[] parsearTamanoStty(String salida) {
        if (salida == null || salida.isBlank()) {
            return null;
        }
        String[] partes = salida.trim().split("\\s+");
        if (partes.length < 2) {
            return null;
        }
        try {
            int filas = Integer.parseInt(partes[0]);
            int cols = Integer.parseInt(partes[1]);
            if (filas > 0 && cols > 0) {
                return new int[]{cols, filas};
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private void actualizarTamanoTerminal() {
        int[] tam = detectarTamanoTerminal();
        if (tam != null) {
            if (tam[0] != ultimoAnchoTerminal || tam[1] != ultimoAltoTerminal) {
                ultimoAnchoTerminal = tam[0];
                ultimoAltoTerminal = tam[1];
                limpiarPantallaPendiente.set(true);
            }
        }
    }

    private static int[] detectarTamanoTerminal() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return null;
        }
        int[] tam = parsearTamanoStty(ejecutarSh("stty size </dev/tty 2>/dev/null"));
        if (tam != null) return tam;
        tam = parsearTamanoStty(ejecutarSh("stty size 2>/dev/null"));
        if (tam != null) return tam;
        String tputOut = ejecutarSh("sh -c 'L=$(tput lines 2>/dev/null); C=$(tput cols 2>/dev/null); [ -n \"$L\" ] && [ -n \"$C\" ] && echo \"$L $C\"'");
        tam = parsearTamanoStty(tputOut);
        if (tam != null) return tam;
        String colsEnv = System.getenv("COLUMNS");
        String linesEnv = System.getenv("LINES");
        if (colsEnv != null && linesEnv != null) {
            return parsearTamanoStty(linesEnv + " " + colsEnv);
        }
        return null;
    }

    private void activarModoTeclaInstantanea() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return;
        }
        String saved = ejecutarSh("stty -g </dev/tty 2>/dev/null");
        if (saved == null || saved.isBlank()) {
            return;
        }
        String res = ejecutarSh("stty -icanon -echo min 1 time 0 </dev/tty 2>/dev/null && echo OK");
        if (res != null && res.contains("OK")) {
            this.sttyOriginal = saved.trim();
            this.entradaInstantaneaActiva = true;
        }
    }

    private void restaurarModoTty() {
        if (sttyOriginal != null) {
            String estadoPrevio = sttyOriginal.replaceAll("[^a-zA-Z0-9:=._-]", "");
            if (!estadoPrevio.isEmpty()) {
                ejecutarSh("stty " + estadoPrevio + " </dev/tty 2>/dev/null");
            }
            sttyOriginal = null;
        }
        entradaInstantaneaActiva = false;
    }

    private static String ejecutarSh(String comando) {
        try {
            Process p = new ProcessBuilder("sh", "-c", comando).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
            }
            p.waitFor(500, TimeUnit.MILLISECONDS);
            return sb.toString().trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean alternarModoCompacto() {
        boolean nuevo = !modoCompacto.get();
        modoCompacto.set(nuevo);
        solicitarLimpiezaCompleta();
        return nuevo;
    }

    public boolean isModoCompacto() {
        return modoCompacto.get();
    }

    public void solicitarLimpiezaCompleta() {
        limpiarPantallaPendiente.set(true);
        ultimaVersionRenderizada = -1L;
    }

    private void bucleRender() {
        while (ejecutando.get() && !Thread.currentThread().isInterrupted()) {
            try {
                long ahoraMs = System.currentTimeMillis();
                if ((ahoraMs - ultimoChequeoTamanoMs) >= INTERVALO_TAMANO_MS) {
                    ultimoChequeoTamanoMs = ahoraMs;
                    actualizarTamanoTerminal();
                }
                long versionActual = estado.getVersionRender();
                boolean debeRefrescar = limpiarPantallaPendiente.get()
                        || versionActual != ultimaVersionRenderizada
                        || (ahoraMs - ultimoTimestampRenderMs) >= 250L;

                if (debeRefrescar) {
                    pintarFrame(ahoraMs);
                    ultimaVersionRenderizada = versionActual;
                    ultimoTimestampRenderMs = ahoraMs;
                }
                Thread.sleep(INTERVALO_REFRESCO_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
                // Evitar que un fallo transitorio de consola detenga el servidor
            }
        }
    }

    private synchronized void pintarFrame(long ahoraMs) {
        if (!ejecutando.get()) {
            return;
        }
        String frame = renderizarFrame(estado, ahoraMs, modoCompacto.get(), ultimoAnchoTerminal, ultimoAltoTerminal);
        String[] lineas = frame.split("\n", -1);
        boolean limpiezaCompleta = limpiarPantallaPendiente.getAndSet(false);
        String promptBase = entradaInstantaneaActiva ? "Tecla directa activa (sin Enter) " : "keiko> ";
        String prompt = recortar(promptBase, Math.max(8, ultimoAnchoTerminal - 1));
        int filaPrompt = Math.min(ultimoAltoTerminal, lineas.length + 1);

        StringBuilder out = new StringBuilder(4096);
        if (limpiezaCompleta || lineas.length != ultimaCantidadLineas) {
            out.append("\033[?25l\033[2J");
            for (int i = 0; i < lineas.length; i++) {
                out.append("\033[").append(i + 1).append(";1H").append(lineas[i]).append("\033[K");
            }
            out.append("\033[").append(filaPrompt).append(";1H\033[K").append(prompt).append("\033[?25h");
            ultimaCantidadLineas = lineas.length;
        } else {
            out.append("\033[?25l");
            for (int i = 0; i < lineas.length; i++) {
                out.append("\033[").append(i + 1).append(";1H").append(lineas[i]).append("\033[K");
            }
            out.append("\033[").append(filaPrompt).append(";1H\033[K").append(prompt).append("\033[?25h");
        }
        salida.print(out);
        salida.flush();
    }

    /** Renderiza el frame completo de la TUI con dimensiones por defecto (para pruebas deterministas). */
    public static String renderizarFrame(EstadoMonitorTui estado, long ahoraMs, boolean compacto) {
        int ancho = compacto ? ANCHO_COMPACTO : ANCHO_DETALLADO;
        return renderizarFrame(estado, ahoraMs, compacto, ancho, ALTO_DEFECTO);
    }

    /** Renderiza el frame completo adaptando anchos y filas de logs al tamano de terminal indicado. */
    public static String renderizarFrame(EstadoMonitorTui estado, long ahoraMs, boolean compacto, int anchoTerm, int altoTerm) {
        if (compacto || anchoTerm < ANCHO_DETALLADO || altoTerm < 32) {
            return renderizarModoCompacto(estado, ahoraMs, anchoTerm, altoTerm);
        }
        return renderizarModoDetallado(estado, ahoraMs, anchoTerm, altoTerm);
    }

    private static String renderizarModoDetallado(EstadoMonitorTui estado, long ahoraMs, int anchoTerm, int altoTerm) {
        int ancho = Math.max(ANCHO_DETALLADO, anchoTerm);
        int anchoPanelDer = ancho - ANCHO_ARTE - 1;
        int alto = Math.max(32, altoTerm);

        List<String> lineas = new ArrayList<>(alto);
        List<String> panelDerecho = construirPanelDerecho(estado, ahoraMs, anchoPanelDer);

        for (int i = 0; i < BANNER_FUSIONADO_PLANO.length; i++) {
            String arteColoreado = colorearFilaBanner(i);
            String der = (i < panelDerecho.size()) ? panelDerecho.get(i) : "";
            lineas.add(arteColoreado + " " + der);
        }

        agregarPanelSensoresDetallado(lineas, estado, ancho);
        // 4 filas fijas restantes: borde sup logs (1) + borde inf logs (1) + Teclas (1) + fila prompt (1)
        int filasLogs = Math.max(1, alto - lineas.size() - 4);
        agregarPanelLogs(lineas, estado, ancho, filasLogs);
        lineas.add(formatearFooterTeclas(ancho));
        return String.join("\n", lineas);
    }

    private static String renderizarModoCompacto(EstadoMonitorTui estado, long ahoraMs, int anchoTerm, int altoTerm) {
        int ancho = Math.max(60, anchoTerm);
        int alto = Math.max(14, altoTerm);

        List<String> lineas = new ArrayList<>(alto);
        String puerto = estado.getPuertoSerie() != null ? estado.getPuertoSerie() : "---";
        String header = String.format(Locale.US,
                "%sKEIKO // ORQUITAS%s  %s | %s | BT:%s@%d | Vel:%d%%",
                BOLD + CIAN, RESET,
                estado.getVersion(), estado.getRig(),
                puerto, estado.getBaudiosSerie(),
                Math.round(estado.getEscalaVelocidad() * 100.0));
        lineas.add(cajaSuperior("KEIKO TUI (COMPACTA)", ancho));
        lineas.add(filaCaja(header, ancho));

        EstadoMonitorTui.EstadoFailSafe fs = estado.obtenerEstadoFailSafe(ahoraMs);
        double hz = estado.calcularFrecuenciaHz(ahoraMs);
        long silencio = estado.obtenerSilencioMs(ahoraMs);
        String estadoFsm = formatearBadgeFsm(estado.getEstadoFsmActual());
        String estomago = formatearIndicadorEstomago(estado.getCapturasEstomago(), estado.getCapturasRequeridas());
        String enlace = String.format(Locale.US,
                "Cli: %s | %s | %.1fHz (%s) | FSM: %s | Estomago: %s",
                recortar(estado.getClienteActivo(), 18),
                formatearBadgeFailSafe(fs),
                hz,
                silencio >= 0 ? silencio + "ms" : "---",
                estadoFsm,
                estomago);
        lineas.add(filaCaja(enlace, ancho));

        EstadoMonitorTui.SnapshotTelemetria snap = estado.getUltimaTelemetria();
        if (snap == null || snap.sensores() == null) {
            lineas.add(filaCaja(GRIS + "Esperando telemetria de la Orquita (US / Laser / Servo)..." + RESET, ancho));
        } else {
            VectorSensores s = snap.sensores();
            String[] terna = snap.terna();
            lineas.add(filaCaja(String.format(Locale.US,
                    "US  IZQ:%5.1fcm(%s)  CEN:%5.1fcm(%s)  DER:%5.1fcm(%s)",
                    s.getUsLeft(), terna[0], s.getUsCenter(), terna[1], s.getUsRight(), terna[2]), ancho));
            lineas.add(filaCaja(String.format(Locale.US,
                    "LSR IZQ:%-9s DER:%-9s  Servo:%+5.1f°  Red2:%-13s  Cmd:(%+.1f,%+.1f)",
                    formatearDistanciaLaser(s.getLaserLeft()),
                    formatearDistanciaLaser(s.getLaserRight()),
                    s.getAnguloServo(),
                    snap.claseCanica(),
                    snap.comando() != null ? snap.comando().getVLeft() : 0.0,
                    snap.comando() != null ? snap.comando().getVRight() : 0.0), ancho));
            lineas.add(filaCaja(String.format(Locale.US,
                    "Vel:%d%% | PWM:%d..%d | Rev:%dp | [%s]",
                    Math.round(estado.getEscalaVelocidad() * 100.0),
                    estado.getPwmMin(), estado.getPwmMax(), estado.getPasosReversa(),
                    estado.isCorridaActiva() ? "RUN" : "REPOSO"), ancho));
        }
        lineas.add(cajaInferior(ancho));

        int filasLogs = Math.max(1, alto - lineas.size() - 4);
        agregarPanelLogs(lineas, estado, ancho, filasLogs);
        lineas.add(formatearFooterTeclas(ancho));
        return String.join("\n", lineas);
    }

    private static String formatearFooterTeclas(int anchoMax) {
        String[] tokens = {
                BOLD + "[k]" + RESET + "Start ",
                BOLD + "[x]" + RESET + "Stop ",
                BOLD + "[r]" + RESET + "Reset(rep) ",
                BOLD + "[v]" + RESET + "Vista ",
                BOLD + "[c]" + RESET + "Log ",
                BOLD + "[q]" + RESET + "Salir"
        };
        StringBuilder sb = new StringBuilder(DIM + " Ctrl: " + RESET);
        for (String tok : tokens) {
            if (longitudVisual(sb.toString()) + longitudVisual(tok) > anchoMax) {
                break;
            }
            sb.append(tok);
        }
        return sb.toString().stripTrailing();
    }

    private static String colorearFilaBanner(int filaIdx) {
        String base = BANNER_FUSIONADO_PLANO[filaIdx];
        if (filaIdx >= KEIKO_LETRAS.length) {
            return BLANCO + base + RESET;
        }
        int finKeiko = COL_INICIO_KEIKO + KEIKO_LETRAS[filaIdx].length();
        String prefijo = base.substring(0, COL_INICIO_KEIKO);
        String letras = base.substring(COL_INICIO_KEIKO, Math.min(finKeiko, base.length()));
        String aleta = (finKeiko < base.length()) ? base.substring(finKeiko) : "";
        return prefijo + BOLD + CIAN + letras + RESET + BLANCO + aleta + RESET;
    }

    private static List<String> construirPanelDerecho(EstadoMonitorTui estado, long ahoraMs, int w) {
        List<String> der = new ArrayList<>(18);
        int extraPanel = Math.max(0, w - ANCHO_PANEL_DER);

        der.add(cajaSuperior("SISTEMA & ENLACE", w));
        der.add(filaCaja(String.format("Ver: %s%s%s | Rig: %s%s%s",
                CIAN, estado.getVersion(), RESET, BLANCO, estado.getRig(), RESET), w));

        String puerto = estado.getPuertoSerie() != null ? estado.getPuertoSerie() : "---";
        String enlaceBt = String.format(Locale.US, "BT %s @ %d", puerto, estado.getBaudiosSerie());
        der.add(filaCaja("Enlace  : " + recortar(enlaceBt, w - 14), w));
        der.add(filaCaja(String.format("Cliente : %s (%d)",
                recortar(estado.getClienteActivo(), 21 + extraPanel), estado.getClientesConectados()), w));

        String badgeHello;
        if (!estado.isHandshakeRecibido()) {
            badgeHello = GRIS + "[ESPERANDO HELLO]" + RESET;
        } else if (estado.isHandshakeOk()) {
            badgeHello = VERDE + "[OK]" + RESET + " v" + recortar(estado.getHandshakeVersion(), 12 + extraPanel);
        } else {
            badgeHello = ROJO + "[MISMATCH]" + RESET + " v" + recortar(estado.getHandshakeVersion(), 10 + extraPanel);
        }
        der.add(filaCaja("Hello   : " + badgeHello, w));

        double hz = estado.calcularFrecuenciaHz(ahoraMs);
        long silencioMs = estado.obtenerSilencioMs(ahoraMs);
        EstadoMonitorTui.EstadoFailSafe fs = estado.obtenerEstadoFailSafe(ahoraMs);
        String dtTxt = (silencioMs >= 0) ? String.format("%3dms", Math.min(9999L, silencioMs)) : " --- ";
        der.add(filaCaja(String.format(Locale.US, "Enlace  : %4.1fHz | dt:%s | %s",
                hz, dtTxt, formatearBadgeFailSafe(fs)), w));

        String redes = String.format("Redes   : US[%s] CAN[%s] Pkts:%d",
                estado.isRedUltrasonicoCargada() ? VERDE + "OK" + RESET : ROJO + "NO" + RESET,
                estado.isRedCanicasCargada() ? VERDE + "OK" + RESET : AMARILLO + "--" + RESET,
                estado.getTotalTelemetrias());
        der.add(filaCaja(redes, w));

        der.add(cajaSeparador("CEREBRO (FSM) & MOTORES", w));
        EstadoMonitorTui.SnapshotTelemetria snap = estado.getUltimaTelemetria();
        int ciclo = snap != null ? snap.ciclosFsm() : 0;
        String modoRun = estado.isCorridaActiva() ? VERDE + "RUN" + RESET : AMARILLO + "REP" + RESET;
        der.add(filaCaja(String.format("Estado  : %-21s %s #%d",
                formatearBadgeFsm(estado.getEstadoFsmActual()), modoRun, ciclo), w));

        double vl = (snap != null && snap.comando() != null) ? snap.comando().getVLeft() : 0.0;
        double vr = (snap != null && snap.comando() != null) ? snap.comando().getVRight() : 0.0;
        der.add(filaCaja(String.format(Locale.US, "Vel:%3d%% | PWM:%3d..%3d | Rev:%dp",
                Math.round(estado.getEscalaVelocidad() * 100.0), estado.getPwmMin(), estado.getPwmMax(),
                estado.getPasosReversa()), w));
        int anchoBarraMotor = 10 + Math.min(20, extraPanel);
        der.add(filaCaja("Motor L : " + barraMotor(vl, anchoBarraMotor), w));
        der.add(filaCaja("Motor R : " + barraMotor(vr, anchoBarraMotor), w));

        der.add(cajaSeparador("ESTOMAGO (estomago.txt)", w));
        String ind = formatearIndicadorEstomago(estado.getCapturasEstomago(), estado.getCapturasRequeridas());
        String escTxt = estado.isModoEscapeActivo() ? VERDE + "ESCAPE: SI" + RESET : GRIS + "ESCAPE: NO" + RESET;
        der.add(filaCaja(String.format("Capturas: %s   %s", ind, escTxt), w));

        List<RegistroCaptura> caps = estado.getRegistrosEstomago();
        der.add(filaCaja(formatearLineaCaptura(caps, 0, w - 4), w));
        der.add(filaCaja(formatearLineaCaptura(caps, 1, w - 4), w));
        String cap3 = formatearLineaCaptura(caps, 2, Math.max(10, w - 16));
        der.add(filaCaja(String.format("%s | Err:%d",
                padRightVisual(cap3, Math.max(10, w - 14)), estado.getTotalErroresCliente()), w));
        der.add(cajaInferior(w));
        return der;
    }

    private static void agregarPanelSensoresDetallado(List<String> lineas, EstadoMonitorTui estado, int w) {
        int extra = Math.max(0, w - ANCHO_DETALLADO);
        int extraIzq = extra / 2;
        int colUs = 49 + extraIzq;
        int anchoBarraUs = 7 + Math.min(24, extraIzq);
        int maxResumenDer = 34 + (extra - extraIzq);

        lineas.add(cajaSuperior("SENSORES EN VIVO & INFERENCIA NEUROPH 2.98", w));

        EstadoMonitorTui.SnapshotTelemetria snap = estado.getUltimaTelemetria();
        if (snap == null || snap.sensores() == null) {
            lineas.add(filaCaja(AMARILLO + " [ESPERANDO TELEMETRIA]" + RESET
                    + " Conecta Mega por Bluetooth HC-05 (TELEMETRY:usL,usC,usR,lL,lR,servo,ts).", w));
            lineas.add(filaCaja(" Fila Superior : 3x HC-SR04 (IZQ, CEN, DER) -> Red 1 (BLOQUEO / ESPACIO_LIBRE / SALIDA)", w));
            lineas.add(filaCaja(" Fila Inferior : 2x VL53L0X + Servo (-45°..+60°) -> Red 2 (LOBITO_IZQ/CEN/DER/NINGUNA)", w));
            lineas.add(cajaInferior(w));
            return;
        }

        VectorSensores s = snap.sensores();
        String[] terna = snap.terna();
        double[] confUs = snap.confianzasUs();

        String filaIzq = formatearFilaUltrasonido("US-IZQ", s.getUsLeft(), terna[0], confUs[0], anchoBarraUs);
        String filaCen = formatearFilaUltrasonido("US-CEN", s.getUsCenter(), terna[1], confUs[1], anchoBarraUs);
        String filaDer = formatearFilaUltrasonido("US-DER", s.getUsRight(), terna[2], confUs[2], anchoBarraUs);

        String ternaResumen = String.format("TERNA: [%s|%s|%s]",
                colorearEtiquetaSinPad(terna[0]),
                colorearEtiquetaSinPad(terna[1]),
                colorearEtiquetaSinPad(terna[2]));
        String ctrlResumen = "Ult. Ctrl: " + CIAN + recortar(estado.getUltimoComandoControl(), maxResumenDer) + RESET;
        String errResumen = estado.getTotalErroresCliente() > 0
                ? ROJO + "Ult. Err : " + recortar(estado.getUltimoErrorCliente(), maxResumenDer) + RESET
                : GRIS + "Sin errores de cliente" + RESET;

        lineas.add(filaCaja(padRightVisual(filaIzq, colUs) + " │ " + ternaResumen, w));
        lineas.add(filaCaja(padRightVisual(filaCen, colUs) + " │ " + ctrlResumen, w));
        lineas.add(filaCaja(padRightVisual(filaDer, colUs) + " │ " + errResumen, w));

        lineas.add(cajaSeparador("FILA INFERIOR (2x VL53L0X ToF + Servo SG90 -> Red 2 Canicas)", w));

        int extraLas = Math.min(18, extra / 3);
        int anchoBarraLaser = 9 + extraLas;
        int colLaser = 31 + extraLas;
        String lsrIzq = String.format(Locale.US, "LSR-IZQ: %s %s",
                padRightVisual(formatearDistanciaLaser(s.getLaserLeft()), 9),
                barraLaser(s.getLaserLeft(), anchoBarraLaser));
        String lsrDer = String.format(Locale.US, "LSR-DER: %s %s",
                padRightVisual(formatearDistanciaLaser(s.getLaserRight()), 9),
                barraLaser(s.getLaserRight(), anchoBarraLaser));
        String red2 = String.format(Locale.US, "Red 2: %s (%2.0f%%)",
                colorearEtiquetaCanica(snap.claseCanica()), snap.confianzaCanica() * 100.0);

        lineas.add(filaCaja(" " + padRightVisual(lsrIzq, colLaser - 1) + " │ " + padRightVisual(lsrDer, colLaser - 1) + " │ " + red2, w));

        int anchoBarraServo = 26 + Math.min(50, extra);
        String barraServo = barraServo(s.getAnguloServo(), anchoBarraServo);
        lineas.add(filaCaja(String.format(Locale.US,
                " SERVO  : %+5.1f°  [-45° %s +60°]   Barrido espejo L/R",
                s.getAnguloServo(), barraServo), w));
        lineas.add(cajaInferior(w));
    }

    private static void agregarPanelLogs(List<String> lineas, EstadoMonitorTui estado, int ancho, int maxFilas) {
        lineas.add(cajaSuperior("EVENTOS Y LOGS RECIENTES", ancho));
        List<EstadoMonitorTui.EntradaLog> recientes = estado.obtenerLogsRecientes(maxFilas);
        if (recientes.isEmpty()) {
            lineas.add(filaCaja(GRIS + "(sin eventos recientes)" + RESET, ancho));
            for (int i = 1; i < maxFilas; i++) {
                lineas.add(filaCaja("", ancho));
            }
        } else {
            for (EstadoMonitorTui.EntradaLog log : recientes) {
                lineas.add(filaCaja(colorearLog(log, ancho - 4), ancho));
            }
            for (int i = recientes.size(); i < maxFilas; i++) {
                lineas.add(filaCaja("", ancho));
            }
        }
        lineas.add(cajaInferior(ancho));
    }

    private static String formatearFilaUltrasonido(String nombre, double distCm, String etiqueta, double conf, int anchoBarra) {
        String color = colorPorEtiqueta(etiqueta);
        String barra = barraDistancia(distCm, DISTANCIA_MAX_US_CM, anchoBarra, color);
        return String.format(Locale.US, "%s │ %5.1fcm │ %s │ %-13s %2.0f%%",
                nombre, distCm, barra, colorearEtiqueta(etiqueta), conf * 100.0);
    }

    private static String barraLaser(double distCm, int ancho) {
        if (distCm >= LASER_TIMEOUT_UMBRAL_CM) {
            return "[" + GRIS + "·".repeat(ancho) + RESET + "]";
        }
        return barraDistancia(distCm, DISTANCIA_MAX_LASER_CM, ancho, distCm < 35.0 ? VERDE : CIAN);
    }

    private static String formatearDistanciaLaser(double distCm) {
        if (distCm >= LASER_TIMEOUT_UMBRAL_CM) {
            return GRIS + "TIMEOUT" + RESET;
        }
        return String.format(Locale.US, "%5.1f cm", distCm);
    }

    private static String barraDistancia(double valor, double max, int ancho, String color) {
        double ratio = Math.max(0.0, Math.min(1.0, valor / max));
        int llenos = (int) Math.round(ratio * ancho);
        StringBuilder sb = new StringBuilder(ancho + 16);
        sb.append('[').append(color);
        for (int i = 0; i < ancho; i++) {
            sb.append(i < llenos ? '█' : '░');
        }
        sb.append(RESET).append(']');
        return sb.toString();
    }

    private static String barraServo(double angulo, int ancho) {
        double ratio = Math.max(0.0, Math.min(1.0, (angulo - SERVO_MIN_DEG) / (SERVO_MAX_DEG - SERVO_MIN_DEG)));
        int pos = (int) Math.round(ratio * (ancho - 1));
        int centro = (int) Math.round(((0.0 - SERVO_MIN_DEG) / (SERVO_MAX_DEG - SERVO_MIN_DEG)) * (ancho - 1));
        StringBuilder sb = new StringBuilder(ancho + 16);
        for (int i = 0; i < ancho; i++) {
            if (i == pos) {
                sb.append(BOLD).append(AMARILLO).append('●').append(RESET);
            } else if (i == centro) {
                sb.append(GRIS).append('┼').append(RESET);
            } else {
                sb.append(DIM).append('─').append(RESET);
            }
        }
        return sb.toString();
    }

    private static String barraMotor(double vel, int ancho) {
        double maxVel = 6.0;
        double ratio = Math.min(1.0, Math.abs(vel) / maxVel);
        int llenos = (int) Math.round(ratio * ancho);
        String color = vel > 0.05 ? VERDE : (vel < -0.05 ? ROJO : GRIS);
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(color);
        for (int i = 0; i < ancho; i++) {
            sb.append(i < llenos ? '█' : '░');
        }
        sb.append(RESET).append(String.format(Locale.US, "] %+5.2f", vel));
        return sb.toString();
    }

    private static String describirAccionMotriz(double vl, double vr) {
        if (Math.abs(vl) < 0.05 && Math.abs(vr) < 0.05) {
            return GRIS + "■ DETENIDO" + RESET;
        }
        if (vl < -0.05 && vr < -0.05) {
            return ROJO + "▼ RETROCESO" + RESET;
        }
        double diff = vl - vr;
        if (Math.abs(diff) <= 0.6) {
            return VERDE + "▲ AVANCE RECTO" + RESET;
        }
        return (diff > 0)
                ? AMARILLO + "► GIRO DERECHA" + RESET
                : AMARILLO + "◄ GIRO IZQUIERDA" + RESET;
    }

    private static String formatearBadgeFsm(EstadoRobot estado) {
        if (estado == null) return GRIS + "[---]" + RESET;
        return switch (estado) {
            case BUSQUEDA -> CIAN + "[BUSQUEDA]" + RESET;
            case APROXIMACION -> VERDE + BOLD + "[APROXIMACION]" + RESET;
            case CAPTURA -> MAGENTA + BOLD + "[CAPTURA]" + RESET;
            case ESCAPE -> AMARILLO + BOLD + "[ESCAPE]" + RESET;
            case FINALIZACION -> AZUL + BOLD + "[FINALIZACION]" + RESET;
            case ERROR_SEGURO -> ROJO + BOLD + "[ERROR_SEGURO]" + RESET;
        };
    }

    private static String formatearBadgeFailSafe(EstadoMonitorTui.EstadoFailSafe fs) {
        return switch (fs) {
            case SIN_ENLACE -> GRIS + "FS:---" + RESET;
            case OK -> VERDE + "FS:OK" + RESET;
            case ALERTA -> AMARILLO + BOLD + "FS:WARN" + RESET;
            case TIMEOUT -> ROJO + BOLD + "FS:TIMEOUT" + RESET;
        };
    }

    private static String formatearIndicadorEstomago(int capturas, int requeridas) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < requeridas; i++) {
            if (i < capturas) {
                sb.append(VERDE).append("●").append(RESET).append(' ');
            } else {
                sb.append(GRIS).append("○").append(RESET).append(' ');
            }
        }
        sb.append(String.format("(%d/%d)", capturas, requeridas));
        return sb.toString();
    }

    private static String formatearLineaCaptura(List<RegistroCaptura> caps, int idx, int maxAncho) {
        if (caps == null || idx >= caps.size()) {
            return GRIS + String.format("#%d: ---", idx + 1) + RESET;
        }
        RegistroCaptura c = caps.get(idx);
        int num = (c.getNumeroCaptura() > 0) ? c.getNumeroCaptura() : (idx + 1);
        String texto = String.format("#%d: %s", num, c.getMetadata());
        return CIAN + recortar(texto, maxAncho) + RESET;
    }

    private static String colorPorEtiqueta(String etiqueta) {
        if (etiqueta == null) return GRIS;
        return switch (etiqueta) {
            case "BLOQUEO" -> ROJO;
            case "SALIDA" -> AMARILLO + BOLD;
            case "LOBITO" -> VERDE + BOLD;
            case "ESPACIO_LIBRE" -> CIAN;
            default -> GRIS;
        };
    }

    private static String colorearEtiqueta(String etiqueta) {
        String e = etiqueta != null ? etiqueta : "---";
        return colorPorEtiqueta(e) + String.format("%-13s", e) + RESET;
    }

    private static String colorearEtiquetaSinPad(String etiqueta) {
        String e = etiqueta != null ? etiqueta : "---";
        return colorPorEtiqueta(e) + e + RESET;
    }

    private static String colorearEtiquetaCanica(String claseCanica) {
        if (claseCanica == null || "NINGUNA".equals(claseCanica)) {
            return GRIS + "NINGUNA" + RESET;
        }
        return VERDE + BOLD + claseCanica + RESET;
    }

    private static String colorearLog(EstadoMonitorTui.EntradaLog log, int maxAncho) {
        String texto = recortar(log.formatear(), maxAncho);
        return switch (log.nivel()) {
            case "ERROR", "SEVERE" -> ROJO + texto + RESET;
            case "WARN", "WARNING" -> AMARILLO + texto + RESET;
            case "EVENT", "OK" -> VERDE + texto + RESET;
            case "CTRL" -> CIAN + texto + RESET;
            default -> texto;
        };
    }

    private static String cajaSuperior(String titulo, int ancho) {
        String prefijo = "╭─ " + titulo + " ";
        int relleno = Math.max(0, ancho - 1 - prefijo.length());
        return prefijo + "─".repeat(relleno) + "╮";
    }

    private static String cajaSeparador(String titulo, int ancho) {
        String prefijo = "├─ " + titulo + " ";
        int relleno = Math.max(0, ancho - 1 - prefijo.length());
        return prefijo + "─".repeat(relleno) + "┤";
    }

    private static String cajaInferior(int ancho) {
        return "╰" + "─".repeat(Math.max(0, ancho - 2)) + "╯";
    }

    private static String filaCaja(String contenido, int ancho) {
        return "│ " + padRightVisual(contenido, ancho - 4) + " │";
    }

    static int longitudVisual(String s) {
        if (s == null) return 0;
        return PATRON_ANSI.matcher(s).replaceAll("").length();
    }

    static String padRightVisual(String s, int ancho) {
        String texto = (s != null) ? s : "";
        int vis = longitudVisual(texto);
        if (vis == ancho) return texto;
        if (vis < ancho) {
            return texto + " ".repeat(ancho - vis);
        }
        // Si excede por contenido plano, truncar preservando RESET final si habia ANSI
        String plano = PATRON_ANSI.matcher(texto).replaceAll("");
        if (ancho <= 3) return plano.substring(0, Math.max(0, ancho));
        return plano.substring(0, ancho - 3) + "...";
    }

    private static String recortar(String s, int max) {
        if (s == null) return "---";
        if (s.length() <= max) return s;
        if (max <= 3) return s.substring(0, Math.max(0, max));
        return s.substring(0, max - 3) + "...";
    }

    private void instalarInterceptorLogs() {
        loggerPaquete = Logger.getLogger("com.orquitas.server");
        usabaParentHandlersPrevio = loggerPaquete.getUseParentHandlers();
        loggerPaquete.setUseParentHandlers(false);
        handlerLogInstalado = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record == null || record.getMessage() == null) return;
                if (record.getLevel().intValue() < Level.INFO.intValue()) return;
                String msg = record.getMessage();
                if (msg.startsWith("[NAV] ")) return;
                String nivel = record.getLevel() == Level.SEVERE ? "ERROR"
                        : (record.getLevel() == Level.WARNING ? "WARN" : "INFO");
                estado.agregarLog(nivel, msg);
            }

            @Override public void flush() {}
            @Override public void close() throws SecurityException {}
        };
        loggerPaquete.addHandler(handlerLogInstalado);
    }

    private void desinstalarInterceptorLogs() {
        if (loggerPaquete != null && handlerLogInstalado != null) {
            loggerPaquete.removeHandler(handlerLogInstalado);
            loggerPaquete.setUseParentHandlers(usabaParentHandlersPrevio);
            handlerLogInstalado = null;
        }
    }
}
