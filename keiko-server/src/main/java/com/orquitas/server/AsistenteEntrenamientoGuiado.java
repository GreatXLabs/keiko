package com.orquitas.server;

import java.util.List;

/**
 * [SOLO PRUEBAS / ENTRENAMIENTO] Maquina de pasos para el Asistente Guiado en TUI.
 * Indica al operador donde colocar el robot/canica en pista paso a paso, configura
 * la red/etiqueta y el modo de servo (0° o barrido completo 6.5 s), y detiene la
 * rafaga automaticamente al alcanzar la meta de cada pose.
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

    // Red 1 (US-CEN): 25 muestras (~2.5 s) con servos fijos en 0°.
    // Red 2 (Canicas + barrido -45°..+60° a 1°/30ms): 65 muestras (~6.5 s) cubre ida y vuelta completa.
    private static final List<PasoWizard> PASOS_POR_DEFECTO = List.of(
            new PasoWizard(1, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "BLOQUEO", false, 25,
                    "Pared pegada ~5 cm",
                    "Pone el frente del auto muy cerca (~5 cm) de una PARED recta. Listo -> [ESPACIO]"),
            new PasoWizard(2, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "BLOQUEO", false, 25,
                    "Pared recta 12 cm",
                    "Aleja el frente del auto a 12 cm de la PARED recta. Listo -> [ESPACIO]"),
            new PasoWizard(3, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "BLOQUEO", false, 25,
                    "Pared limite ~20 cm",
                    "Pone el frente a ~20 cm de la PARED recta (zona limite bloqueo). Listo -> [ESPACIO]"),
            new PasoWizard(4, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "BLOQUEO", false, 25,
                    "Cubo 6x6 a 12 cm",
                    "Pone un cubo obstaculo 6x6 cm centrado a 12 cm del sensor US. Listo -> [ESPACIO]"),
            new PasoWizard(5, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "ESPACIO_LIBRE", false, 25,
                    "Libre corto ~35 cm",
                    "Pone el frente mirando a pared a ~35 cm (espacio libre cercano). Listo -> [ESPACIO]"),
            new PasoWizard(6, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "ESPACIO_LIBRE", false, 25,
                    "Libre medio ~75 cm",
                    "Apunta al interior libre de la bahia (~65-85 cm hasta pared). Listo -> [ESPACIO]"),
            new PasoWizard(7, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "ESPACIO_LIBRE", false, 25,
                    "Libre largo ~115 cm",
                    "Apunta a pared lejana a ~110-120 cm (NO es salida aun!). Listo -> [ESPACIO]"),
            new PasoWizard(8, GrabadorDatasetReal.RedObjetivo.ULTRASONICO, "SALIDA", false, 25,
                    "Salida > 135 cm",
                    "Apunta derecho por la SALIDA abierta de 30 cm (> 135 cm o vacio). Listo -> [ESPACIO]"),
            new PasoWizard(9, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, 65,
                    "Pista vacia (barrido)",
                    "Pista VACIA sin canicas (> 50 cm libre). Barrido completo 6.5s. Listo -> [ESPACIO]"),
            new PasoWizard(10, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, 65,
                    "Pared recta ~12 cm",
                    "Frente a PARED recta a ~12 cm SIN canica (anti falso lobito). Listo -> [ESPACIO]"),
            new PasoWizard(11, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, 65,
                    "Diagonal IZQ ~15 cm",
                    "Pared en DIAGONAL hacia la IZQ (~35 deg, 12-20 cm), SIN canica. -> [ESPACIO]"),
            new PasoWizard(12, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, 65,
                    "Diagonal DER ~15 cm",
                    "Pared en DIAGONAL hacia la DER (~35 deg, 12-20 cm), SIN canica. -> [ESPACIO]"),
            new PasoWizard(13, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, 65,
                    "Esquina sin canica",
                    "Frente a una ESQUINA de dos paredes (~15-25 cm) SIN canica. Listo -> [ESPACIO]"),
            new PasoWizard(14, GrabadorDatasetReal.RedObjetivo.CANICAS, "NINGUNA", true, 65,
                    "Cubo cerca sin canica",
                    "Cubo 6x6 cm a ~12-18 cm delante SIN canica (anti falso +). Listo -> [ESPACIO]"),
            new PasoWizard(15, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_CENTRO", true, 65,
                    "Canica CENTRO ~6 cm",
                    "1 CANICA de vidrio centrada a ~6 cm (entrada del picker). Listo -> [ESPACIO]"),
            new PasoWizard(16, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_CENTRO", true, 65,
                    "Canica CENTRO ~13 cm",
                    "CANICA centrada a ~13 cm (puedes poner pared atras a ~45 cm). Listo -> [ESPACIO]"),
            new PasoWizard(17, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_CENTRO", true, 65,
                    "Canica CENTRO ~21 cm",
                    "CANICA centrada lejos (~20-22 cm, ideal con fondo real pista). Listo -> [ESPACIO]"),
            new PasoWizard(18, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_IZQ", true, 65,
                    "Canica IZQ ~8-12 cm",
                    "CANICA a la IZQUIERDA (8-12 cm adelante, ~4 cm corrida a izq). Listo -> [ESPACIO]"),
            new PasoWizard(19, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_IZQ", true, 65,
                    "Canica IZQ ~16-20 cm",
                    "CANICA a la IZQUIERDA mas lejos (16-20 cm adelante, ~5 cm izq). Listo -> [ESPACIO]"),
            new PasoWizard(20, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_DER", true, 65,
                    "Canica DER ~8-12 cm",
                    "CANICA a la DERECHA (8-12 cm adelante, ~4 cm corrida a der). Listo -> [ESPACIO]"),
            new PasoWizard(21, GrabadorDatasetReal.RedObjetivo.CANICAS, "LOBITO_DER", true, 65,
                    "Canica DER ~16-20 cm",
                    "CANICA a la DERECHA mas lejos (16-20 cm adelante, ~5 cm der). Listo -> [ESPACIO]")
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
