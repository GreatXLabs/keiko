package com.orquitas.server;

/**
 * Representación del vector sensorial muestreado por la Orquita (doble plano ultrasónico HC-SR04).
 */
public class VectorSensores {
    private final double dLowLeft;
    private final double dLowRight;
    private final double dHighLeft;
    private final double dHighRight;
    private final long timestamp;

    public VectorSensores(double dLowLeft, double dLowRight, double dHighLeft, double dHighRight) {
        this(dLowLeft, dLowRight, dHighLeft, dHighRight, System.currentTimeMillis());
    }

    public VectorSensores(double dLowLeft, double dLowRight, double dHighLeft, double dHighRight, long timestamp) {
        this.dLowLeft = dLowLeft;
        this.dLowRight = dLowRight;
        this.dHighLeft = dHighLeft;
        this.dHighRight = dHighRight;
        this.timestamp = timestamp;
    }

    public double getDLowLeft() { return dLowLeft; }
    public double getDLowRight() { return dLowRight; }
    public double getDHighLeft() { return dHighLeft; }
    public double getDHighRight() { return dHighRight; }
    public long getTimestamp() { return timestamp; }

    public double getMinLow() { return Math.min(dLowLeft, dLowRight); }
    public double getMinHigh() { return Math.min(dHighLeft, dHighRight); }

    public boolean isValido() {
        return dLowLeft >= 0 && dLowLeft <= 400 &&
               dLowRight >= 0 && dLowRight <= 400 &&
               dHighLeft >= 0 && dHighLeft <= 400 &&
               dHighRight >= 0 && dHighRight <= 400;
    }

    public double[] toArrayNormalizado(double maxRangoM) {
        return new double[] {
            Math.min(1.0, Math.max(0.0, (dLowLeft / 100.0) / maxRangoM)),
            Math.min(1.0, Math.max(0.0, (dLowRight / 100.0) / maxRangoM)),
            Math.min(1.0, Math.max(0.0, (dHighLeft / 100.0) / maxRangoM)),
            Math.min(1.0, Math.max(0.0, (dHighRight / 100.0) / maxRangoM))
        };
    }

    @Override
    public String toString() {
        return String.format("VectorSensores[LL=%.1f, LR=%.1f, HL=%.1f, HR=%.1f cm]",
                dLowLeft, dLowRight, dHighLeft, dHighRight);
    }
}
