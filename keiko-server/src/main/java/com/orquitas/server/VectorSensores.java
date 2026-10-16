package com.orquitas.server;

/**
 * Representacion del vector sensorial muestreado por la Orquita (doble plano ultrasonico HC-SR04).
 */
public class VectorSensores {
    private final double dLowLeft;
    private final double dLowRight;
    private final double dHighLeft;
    private final double dHighRight;
    private final long timestamp;

    // Crea vector con lecturas de sensores y marca temporal actual.
    public VectorSensores(double dLowLeft, double dLowRight, double dHighLeft, double dHighRight) {
        this(dLowLeft, dLowRight, dHighLeft, dHighRight, System.currentTimeMillis());
    }

    // Crea vector indicando lecturas de sensores y marca temporal explicita.
    public VectorSensores(double dLowLeft, double dLowRight, double dHighLeft, double dHighRight, long timestamp) {
        this.dLowLeft = dLowLeft;
        this.dLowRight = dLowRight;
        this.dHighLeft = dHighLeft;
        this.dHighRight = dHighRight;
        this.timestamp = timestamp;
    }

    // Retorna distancia medida por sensor plano bajo izquierdo.
    public double getDLowLeft() { return dLowLeft; }

    // Retorna distancia medida por sensor plano bajo derecho.
    public double getDLowRight() { return dLowRight; }

    // Retorna distancia medida por sensor plano alto izquierdo.
    public double getDHighLeft() { return dHighLeft; }

    // Retorna distancia medida por sensor plano alto derecho.
    public double getDHighRight() { return dHighRight; }

    // Retorna marca de tiempo de la medicion.
    public long getTimestamp() { return timestamp; }

    // Retorna menor distancia de los sensores de plano bajo.
    public double getMinLow() { return Math.min(dLowLeft, dLowRight); }

    // Retorna menor distancia de los sensores de plano alto.
    public double getMinHigh() { return Math.min(dHighLeft, dHighRight); }

    // Valida que las distancias esten dentro del rango fisico valido.
    public boolean isValido() {
        return dLowLeft >= 0 && dLowLeft <= 400 &&
               dLowRight >= 0 && dLowRight <= 400 &&
               dHighLeft >= 0 && dHighLeft <= 400 &&
               dHighRight >= 0 && dHighRight <= 400;
    }

    // Normaliza las distancias en un vector de valores entre 0.0 y 1.0.
    public double[] toArrayNormalizado(double maxRangoM) {
        return new double[] {
            Math.min(1.0, Math.max(0.0, (dLowLeft / 100.0) / maxRangoM)),
            Math.min(1.0, Math.max(0.0, (dLowRight / 100.0) / maxRangoM)),
            Math.min(1.0, Math.max(0.0, (dHighLeft / 100.0) / maxRangoM)),
            Math.min(1.0, Math.max(0.0, (dHighRight / 100.0) / maxRangoM))
        };
    }

    // Retorna representacion textual legible del vector sensorial.
    @Override
    public String toString() {
        return String.format("VectorSensores[LL=%.1f, LR=%.1f, HL=%.1f, HR=%.1f cm]",
                dLowLeft, dLowRight, dHighLeft, dHighRight);
    }
}
