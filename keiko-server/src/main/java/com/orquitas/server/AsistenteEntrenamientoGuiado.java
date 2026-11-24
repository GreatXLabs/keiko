package com.orquitas.server;

import java.util.List;

/**
 * [SOLO PRUEBAS / ENTRENAMIENTO] Maquina de pasos para el Asistente Guiado en TUI.
 * Indica al operador donde colocar el robot/canica en pista paso a paso, configura
 * la red/etiqueta y el modo de servo (0° o barrido), y detiene la rafaga al alcanzar la meta.
 */
public class AsistenteEntrenamientoGuiado {

    public record PasoWizard(
            int numero,
            GrabadorDatasetReal.RedObjetivo red,
            String etiqueta,
            boolean barridoLaser,
            int metaMuestras,
            String titulo,
            String instruccion
    ) {}

    public record ResultadoMuestra(
            boolean pasoCompletado,
            PasoWizard pasoTerminado,
            PasoWizard siguientePaso
    ) {}

    private static final List<PasoWizard> PASOS_POR_DEFECTO = List.of(
            new PasoWizard(1, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "BLOQUEO", false, 25,
                    "Pared recta 10 cm",
                    "Pone el frente del auto a 10 cm de una PARED recta. Listo -> [ESPACIO]"),
            new PasoWizard(2, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "BLOQUEO", false, 25,
                    "Pared recta 18 cm",
                    "Alejalo un poco: frente del auto a 18 cm de una PARED recta. Listo -> [ESPACIO]"),
            new PasoWizard(3, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "BLOQUEO", false, 25,
                    "Obstaculo 6x6 a 12 cm",
                    "Pone un cubo 6x6 cm centrado a 12 cm frente al sensor US. Listo -> [ESPACIO]"),
            new PasoWizard(4, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "ESPACIO_LIBRE", false, 25,
                    "Interior libre 45-90cm",
                    "Apunta el auto hacia el interior libre (pared a 45-90 cm). Listo -> [ESPACIO]"),
            new PasoWizard(5, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "SALIDA", false, 25,
                    "Salida > 130 cm",
                    "Apunta derecho por la SALIDA abierta de 30 cm (> 130 cm). Listo -> [ESPACIO]"),
            new PasoWizard(6, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, 30,
                    "Pista vacia (barrido)",
                    "Pista VACIA sin canica (> 40 cm libre). Lasers barriendo. Listo -> [ESPACIO]"),
            new PasoWizard(7, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, 30,
                    "Pared en DIAGONAL",
                    "Auto en DIAGONAL (30-45 deg) a ~15-20 cm de pared, SIN canica. -> [ESPACIO]"),
            new PasoWizard(8, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, 30,
                    "Cubo cerca sin canica",
                    "Cubo 6x6 cm a ~12-18 cm delante SIN canica (anti falso +). -> [ESPACIO]"),
            new PasoWizard(9, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_CENTRO", true, 30,
                    "Canica CENTRO ~7 cm",
                    "Pone 1 canica de vidrio centrada a ~7 cm frente al picker. Listo -> [ESPACIO]"),
            new PasoWizard(10, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_CENTRO", true, 30,
                    "Canica CENTRO ~14 cm",
                    "Aleja la canica centrada a ~14 cm delante del auto. Listo -> [ESPACIO]"),
            new PasoWizard(11, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_IZQ", true, 30,
                    "Canica IZQ ~10-15 cm",
                    "Pone la canica a la IZQUIERDA (10-15 cm adelante, ~4 cm izq). -> [ESPACIO]"),
            new PasoWizard(12, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_DER", true, 30,
                    "Canica DER ~10-15 cm",
                    "Pone la canica a la DERECHA (10-15 cm adelante, ~4 cm der). -> [ESPACIO]")
    );

    private final List<PasoWizard> pasos;
    private final boolean[] pasosCompletados;
    private boolean activo = false;
    private int indicePaso = 0;
    private boolean capturando = false;
    private int muestrasEnPaso = 0;
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
        this.completado = false;
        java.util.Arrays.fill(this.pasosCompletados, false);
        return getPasoActual();
    }

    public synchronized void desactivar() {
        this.activo = false;
        this.capturando = false;
        this.muestrasEnPaso = 0;
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
                capturando,
                muestrasEnPaso,
                paso.metaMuestras(),
                hechos,
                paso.titulo(),
                paso.instruccion());
    }
}
