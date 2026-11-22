package com.orquitas.server;

/**
 * Vector sensorial de la Orquita: 3 ultrasonidos superiores fijos [Izq, Centro, Der]
 * y barrido inferior de 2 laseres ToF con el angulo actual del servomotor (RF-01 / RF-02).
 */
public class VectorSensores {
    private final double usLeft;
    private final double usCenter;
    private final double usRight;
    private final double laserLeft;
    private final double laserRight;
    private final double anguloServo;
    private final long timestamp;

    public VectorSensores(double usLeft, double usCenter, double usRight,
                          double laserLeft, double laserRight, double anguloServo,
                          long timestamp) {
        this.usLeft = usLeft;
        this.usCenter = usCenter;
        this.usRight = usRight;
        this.laserLeft = laserLeft;
        this.laserRight = laserRight;
        this.anguloServo = anguloServo;
        this.timestamp = timestamp;
    }

    public VectorSensores(double usLeft, double usCenter, double usRight, long timestamp) {
        this(usLeft, usCenter, usRight, -1.0, -1.0, 0.0, timestamp);
    }

    public boolean isValido() {
        boolean usValidos = Double.isFinite(usLeft) && usLeft >= 2.0 && usLeft <= 400.0
                && Double.isFinite(usCenter) && usCenter >= 2.0 && usCenter <= 400.0
                && Double.isFinite(usRight) && usRight >= 2.0 && usRight <= 400.0;
        boolean servoValido = Double.isFinite(anguloServo) && anguloServo >= -180.0 && anguloServo <= 180.0;
        boolean soloUltrasonico = laserLeft == -1.0 && laserRight == -1.0;
        boolean barridoLaserValido = Double.isFinite(laserLeft) && laserLeft >= 2.0 && laserLeft <= 1000.0
                && Double.isFinite(laserRight) && laserRight >= 2.0 && laserRight <= 1000.0;
        return timestamp >= 0 && usValidos && servoValido && (soloUltrasonico || barridoLaserValido);
    }

    public double[] toArrayNormalizado() {
        return new double[]{
                Math.min(1.0, Math.max(0.0, (usLeft - 2.0) / 248.0)),
                Math.min(1.0, Math.max(0.0, (usCenter - 2.0) / 248.0)),
                Math.min(1.0, Math.max(0.0, (usRight - 2.0) / 248.0)),
                Math.min(1.0, Math.max(0.0, (laserLeft - 2.0) / 40.0)),
                Math.min(1.0, Math.max(0.0, (laserRight - 2.0) / 40.0)),
                Math.min(1.0, Math.max(0.0, (anguloServo - (-45.0)) / 105.0))
        };
    }

    public double getUsLeft() {
        return usLeft;
    }

    public double getUsCenter() {
        return usCenter;
    }

    public double getUsRight() {
        return usRight;
    }

    public double getLaserLeft() {
        return laserLeft;
    }

    public double getLaserRight() {
        return laserRight;
    }

    public double getAnguloServo() {
        return anguloServo;
    }
}
