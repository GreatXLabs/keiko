package com.orquitas.server;

import java.util.List;

/**
 * [SOLO PRUEBAS / ENTRENAMIENTO] Maquina de pasos para el Asistente Guiado en TUI.
 * Indica al operador donde colocar el robot/canica en pista paso a paso, configura
 * la red/etiqueta y el modo de servo (0° o barrido), y en pasos LOBITO_* filtra
 * automaticamente los angulos que miran al vacio para evitar falsos positivos.
 */
public class AsistenteEntrenamientoGuiado {

    public record PasoWizard(
            int numero,
            GrabadorDatasetReal.RedObjetivo red,
            String etiqueta,
            GrabadorDatasetReal.CanalUs canalUs,
            boolean barridoLaser,
            boolean filtrarImpactosCanica,
            int metaMuestras,
            String titulo,
            String instruccion
    ) {
        public PasoWizard(
                int numero,
                GrabadorDatasetReal.RedObjetivo red,
                String etiqueta,
                boolean barridoLaser,
                boolean filtrarImpactosCanica,
                int metaMuestras,
                String titulo,
                String instruccion) {
            this(numero, red, etiqueta, GrabadorDatasetReal.CanalUs.CEN, barridoLaser, filtrarImpactosCanica,
                    metaMuestras, titulo, instruccion);
        }
    }

    public record ResultadoMuestra(
            boolean pasoCompletado,
            PasoWizard pasoTerminado,
            PasoWizard siguientePaso
    ) {}

    // Red 1 (US-CEN + US-IZQ/US-DER angulados): 25 muestras (~2.5 s) con servos fijos en 0°.
    // Red 2 NINGUNA (barrido -45°..+60° a 2°/28ms): 50 muestras (~5.0 s) cubre >1.6 ciclos completos.
    // Red 2 LOBITO_* (barrido con filtro): 12 impactos reales donde el laser efectivamente cae sobre la canica.
    private static final List<PasoWizard> PASOS_POR_DEFECTO = List.of(
            new PasoWizard(1, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "BLOQUEO", GrabadorDatasetReal.CanalUs.CEN, false, false, 25,
                    "Pared pegada ~5 cm",
                    "[US-CEN] Frente pegado (~5 cm) a PARED recta. Listo -> [ESPACIO]"),
            new PasoWizard(2, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "BLOQUEO", GrabadorDatasetReal.CanalUs.CEN, false, false, 25,
                    "Pared recta 12 cm",
                    "[US-CEN] Frente a 12 cm de PARED recta. Listo -> [ESPACIO]"),
            new PasoWizard(3, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "BLOQUEO", GrabadorDatasetReal.CanalUs.CEN, false, false, 25,
                    "Pared limite ~20 cm",
                    "[US-CEN] Frente a ~20 cm de PARED recta (limite bloqueo). Listo -> [ESPACIO]"),
            new PasoWizard(4, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "BLOQUEO", GrabadorDatasetReal.CanalUs.CEN, false, false, 25,
                    "Cubo 6x6 a 12 cm",
                    "[US-CEN] Cubo obstaculo 6x6 cm centrado a 12 cm del frente. Listo -> [ESPACIO]"),
            new PasoWizard(5, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "ESPACIO_LIBRE", GrabadorDatasetReal.CanalUs.CEN, false, false, 25,
                    "Libre corto ~35 cm",
                    "[US-CEN] Mirando a pared a ~35 cm (espacio libre cercano). Listo -> [ESPACIO]"),
            new PasoWizard(6, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "ESPACIO_LIBRE", GrabadorDatasetReal.CanalUs.CEN, false, false, 25,
                    "Libre medio ~75 cm",
                    "[US-CEN] Mirando interior libre de la bahia (~65-85 cm). Listo -> [ESPACIO]"),
            new PasoWizard(7, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "ESPACIO_LIBRE", GrabadorDatasetReal.CanalUs.CEN, false, false, 25,
                    "Libre largo ~115 cm",
                    "[US-CEN] Pared lejana a ~110-120 cm (NO es salida aun!). Listo -> [ESPACIO]"),
            new PasoWizard(8, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "SALIDA", GrabadorDatasetReal.CanalUs.CEN, false, false, 25,
                    "Salida > 135 cm",
                    "[US-CEN] Derecho por SALIDA abierta de 30 cm (> 135 cm / vacio). -> [ESPACIO]"),
            new PasoWizard(9, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "BLOQUEO", GrabadorDatasetReal.CanalUs.IZQ, false, false, 25,
                    "US-IZQ angulado ~12 cm",
                    "[US-IZQ] En diagonal (~45 deg): pared a ~12 cm frente al US-IZQ curvado. -> [ESP]"),
            new PasoWizard(10, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "BLOQUEO", GrabadorDatasetReal.CanalUs.DER, false, false, 25,
                    "US-DER angulado ~12 cm",
                    "[US-DER] En diagonal (~45 deg): pared a ~12 cm frente al US-DER curvado. -> [ESP]"),
            new PasoWizard(11, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "ESPACIO_LIBRE", GrabadorDatasetReal.CanalUs.IZQ, false, false, 25,
                    "US-IZQ angulado libre",
                    "[US-IZQ] Zona abierta: US-IZQ curvado mirando ~40-60 cm libre. -> [ESPACIO]"),
            new PasoWizard(12, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "ESPACIO_LIBRE", GrabadorDatasetReal.CanalUs.DER, false, false, 25,
                    "US-DER angulado libre",
                    "[US-DER] Zona abierta: US-DER curvado mirando ~40-60 cm libre. -> [ESPACIO]"),
            new PasoWizard(13, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, false, 50,
                    "Pista vacia (>60 cm)",
                    "Pista VACIA sin canicas (> 60 cm libre). Barrido 5.0s. -> [ESPACIO]"),
            new PasoWizard(14, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, false, 50,
                    "Espacio abierto ~40 cm",
                    "Pista abierta con pared frontal a ~40-50 cm SIN canica. -> [ESPACIO]"),
            new PasoWizard(15, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, false, 50,
                    "Pared recta ~12 cm",
                    "Frente a PARED recta a ~12 cm SIN canica (anti falso lobito). -> [ESPACIO]"),
            new PasoWizard(16, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, false, 50,
                    "Pared paralela IZQ",
                    "Auto PARALELO a pared IZQ (~10 cm del costado izq) SIN canica. -> [ESPACIO]"),
            new PasoWizard(17, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, false, 50,
                    "Pared paralela DER",
                    "Auto PARALELO a pared DER (~10 cm del costado der) SIN canica. -> [ESPACIO]"),
            new PasoWizard(18, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, false, 50,
                    "Diagonal IZQ ~15 cm",
                    "Pared en DIAGONAL hacia IZQ (~35 deg, 12-20 cm), SIN canica. -> [ESPACIO]"),
            new PasoWizard(19, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, false, 50,
                    "Diagonal DER ~15 cm",
                    "Pared en DIAGONAL hacia DER (~35 deg, 12-20 cm), SIN canica. -> [ESPACIO]"),
            new PasoWizard(20, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, false, 50,
                    "Esquina sin canica",
                    "Frente a una ESQUINA de dos paredes (~15-25 cm) SIN canica. -> [ESPACIO]"),
            new PasoWizard(21, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, false, 50,
                    "Cubo cerca sin canica",
                    "Cubo 6x6 cm a ~12-18 cm delante SIN canica (anti falso +). -> [ESPACIO]"),
            new PasoWizard(22, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, false, 50,
                    "Paralela pegada IZQ",
                    "Casi PEGADO paralelo IZQ (3-6 cm: US lejos, laser IZQ corto) SIN canica. -> [ESP]"),
            new PasoWizard(23, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, false, 50,
                    "Paralela pegada DER",
                    "Casi PEGADO paralelo DER (3-6 cm: US lejos, laser DER corto) SIN canica. -> [ESP]"),
            new PasoWizard(24, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, false, 50,
                    "Diagonal extrema IZQ",
                    "Pared DIAGONAL cerrada IZQ (~60 deg, US-CEN rebota lejos) SIN canica. -> [ESP]"),
            new PasoWizard(25, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, false, 50,
                    "Diagonal extrema DER",
                    "Pared DIAGONAL cerrada DER (~60 deg, US-CEN rebota lejos) SIN canica. -> [ESP]"),
            new PasoWizard(26, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, false, 50,
                    "Frente a Salida vacia",
                    "Frente a abertura SALIDA (cartones izq/der a ~15 cm) SIN canica. -> [ESPACIO]"),
            new PasoWizard(27, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_CENTRO", true, true, 12,
                    "Canica CENTRO ~9 cm",
                    "1 CANICA centrada a ~9-11 cm (fondo libre >35 cm; filtra vacio). -> [ESPACIO]"),
            new PasoWizard(28, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_CENTRO", true, true, 12,
                    "Canica CENTRO ~14 cm",
                    "1 CANICA centrada a ~13-15 cm (fondo libre >35 cm; filtra vacio). -> [ESPACIO]"),
            new PasoWizard(29, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_IZQ", true, true, 12,
                    "Canica IZQ ~8 cm",
                    "CANICA a la IZQUIERDA (8-10 cm adelante, ~4 cm izq, fondo >35 cm). -> [ESPACIO]"),
            new PasoWizard(30, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_IZQ", true, true, 12,
                    "Canica IZQ ~13 cm",
                    "CANICA a la IZQUIERDA (12-15 cm adelante, ~4 cm izq, fondo >35 cm). -> [ESPACIO]"),
            new PasoWizard(31, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_DER", true, true, 12,
                    "Canica DER ~8 cm",
                    "CANICA a la DERECHA (8-10 cm adelante, ~4 cm der, fondo >35 cm). -> [ESPACIO]"),
            new PasoWizard(32, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_DER", true, true, 12,
                    "Canica DER ~13 cm",
                    "CANICA a la DERECHA (12-15 cm adelante, ~4 cm der, fondo >35 cm). -> [ESPACIO]")
    );

    private final List<PasoWizard> pasos;
    private final boolean[] pasosCompletados;
    private boolean activo = false;
    private int indicePaso = 0;
    private boolean capturando = false;
    private int muestrasEnPaso = 0;
    private int descartadasEnPaso = 0;
    private boolean completado = false;

    public AsistenteEntrenamientoGuiado() {
        this(PASOS_POR_DEFECTO);
    }

    public AsistenteEntrenamientoGuiado(List<PasoWizard> pasos) {
        if (pasos == null || pasos.isEmpty()) {
            throw new IllegalArgumentException("El asistente guiado requiere al menos un paso");
        }
        this.pasos = List.copyOf(pasos);
        this.pasosCompletados = new boolean[this.pasos.size()];
    }

    public synchronized PasoWizard activar() {
        this.activo = true;
        this.indicePaso = 0;
        this.capturando = false;
        this.muestrasEnPaso = 0;
        this.descartadasEnPaso = 0;
        this.completado = false;
        java.util.Arrays.fill(this.pasosCompletados, false);
        return getPasoActual();
    }

    public synchronized void desactivar() {
        this.activo = false;
        this.capturando = false;
        this.muestrasEnPaso = 0;
        this.descartadasEnPaso = 0;
    }

    public synchronized boolean isActivo() {
        return activo;
    }

    public synchronized boolean isCapturando() {
        return activo && !completado && capturando;
    }

    public synchronized boolean isCompletado() {
        return activo && completado;
    }

    public synchronized PasoWizard getPasoActual() {
        return pasos.get(Math.min(indicePaso, pasos.size() - 1));
    }

    public synchronized List<PasoWizard> getPasos() {
        return pasos;
    }

    public synchronized int getTotalPasos() {
        return pasos.size();
    }

    /** Inicia o reanuda la captura de la rafaga para el paso actual. */
    public synchronized boolean alternarCapturaPaso() {
        if (!activo || completado) {
            return false;
        }
        capturando = !capturando;
        return capturando;
    }

    public synchronized void registrarDescartadaFiltro() {
        if (activo && !completado && capturando) {
            descartadasEnPaso++;
        }
    }

    /** Registra 1 muestra grabada; si alcanza la meta del paso, detiene la rafaga y avanza. */
    public synchronized ResultadoMuestra registrarMuestraCapturada() {
        if (!activo || completado || !capturando) {
            return new ResultadoMuestra(false, null, null);
        }
        PasoWizard actual = getPasoActual();
        muestrasEnPaso++;
        if (muestrasEnPaso < actual.metaMuestras()) {
            return new ResultadoMuestra(false, null, null);
        }
        pasosCompletados[indicePaso] = true;
        capturando = false;
        muestrasEnPaso = 0;
        descartadasEnPaso = 0;
        if (indicePaso + 1 < pasos.size()) {
            indicePaso++;
            return new ResultadoMuestra(true, actual, getPasoActual());
        }
        completado = true;
        return new ResultadoMuestra(true, actual, null);
    }

    /** Avanza manualmente al siguiente paso (o a COMPLETADO en el ultimo). */
    public synchronized PasoWizard siguientePaso() {
        if (!activo) {
            return null;
        }
        capturando = false;
        muestrasEnPaso = 0;
        descartadasEnPaso = 0;
        if (completado) {
            return null;
        }
        if (indicePaso + 1 < pasos.size()) {
            indicePaso++;
            return getPasoActual();
        }
        completado = true;
        return null;
    }

    /** Vuelve al paso anterior (si estaba COMPLETADO, reabre el ultimo paso). */
    public synchronized PasoWizard anteriorPaso() {
        if (!activo) {
            return null;
        }
        capturando = false;
        muestrasEnPaso = 0;
        descartadasEnPaso = 0;
        if (completado) {
            completado = false;
            return getPasoActual();
        }
        if (indicePaso > 0) {
            indicePaso--;
        }
        return getPasoActual();
    }

    public synchronized EstadoMonitorTui.EstadoWizardSnapshot getSnapshot() {
        if (!activo) {
            return null;
        }
        PasoWizard paso = getPasoActual();
        int hechos = 0;
        for (boolean ok : pasosCompletados) {
            if (ok) hechos++;
        }
        return new EstadoMonitorTui.EstadoWizardSnapshot(
                true,
                completado,
                indicePaso + 1,
                pasos.size(),
                paso.red().name(),
                paso.etiqueta(),
                paso.barridoLaser(),
                paso.filtrarImpactosCanica(),
                capturando,
                muestrasEnPaso,
                paso.metaMuestras(),
                descartadasEnPaso,
                hechos,
                paso.titulo(),
                paso.instruccion());
    }
}
