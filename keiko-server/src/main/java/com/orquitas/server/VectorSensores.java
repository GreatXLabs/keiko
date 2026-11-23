package com.orquitas.server;

import java.util.Arrays;
import java.util.Locale;

/**
 * Representación del vector sensorial muestreado por la Orquita.
 * Configuración oficial 3US + 2Laser:
 * - Canales 0, 1, 2: Ultrasonidos frontales fijos HC-SR04 (Izquierda, Centro, Derecha) a 5.5 cm de altura.
 * - Canales 3, 4: Láseres ToF VL53L0X (Izquierda, Derecha) a 1.8 cm de altura sobre servos SG90.
 * - Canal 5: Ángulo del barrido de servos en grados (-45.0 a +60.0).
 * 
 * También mantiene compatibilidad con esquemas de 4, 6 (coaxial legacy), 8 o 10 canales.
 */
public class VectorSensores {
    public static final double MIN_RANGO_CM = 2.0;
    public static final double MAX_RANGO_CM = 250.0;

    private final double[] canales;
    private final long timestamp;

    public VectorSensores(double dLowLeft, double dLowRight, double dHighLeft, double dHighRight, long timestamp) {
        this(new double[]{dLowLeft, dLowRight, dHighLeft, dHighRight}, timestamp);
    }

    public VectorSensores(double dLowLeft, double dLowCenter, double dLowRight,
                          double dHighLeft, double dHighCenter, double dHighRight) {
        this(new double[]{dLowLeft, dLowCenter, dLowRight, dHighLeft, dHighCenter, dHighRight},
                System.currentTimeMillis());
    }

    public VectorSensores(double[] canales, long timestamp) {
        if (canales == null || canales.length < 4) {
            throw new IllegalArgumentException("Se requieren al menos 4 canales sensoriales");
        }
        this.canales = Arrays.copyOf(canales, canales.length);
        this.timestamp = timestamp;
    }

    public int getCantidadSensores() {
        return canales.length;
    }

    public double[] getCanales() {
        return Arrays.copyOf(canales, canales.length);
    }

    // --- Getters específicos para la configuración 3US + 2Laser ---
    public double getUsLeft() {
        return canales[0];
    }

    public double getUsCenter() {
        if (canales.length >= 6) {
            return canales[1];
        }
        return 0.5 * (canales[0] + canales[1]);
    }

    public double getUsRight() {
        if (canales.length >= 6) {
            return canales[2];
        }
        return canales[1];
    }

    public double getLaserLeft() {
        if (canales.length >= 6) {
            return canales[3];
        }
        return 200.0;
    }

    public double getLaserRight() {
        if (canales.length >= 6) {
            return canales[4];
        }
        return 200.0;
    }

    public double getAnguloServo() {
        if (canales.length >= 6) {
            return canales[5];
        }
        return 0.0;
    }

    // --- Getters de compatibilidad con modelos y tests legacy ---
    public double getDLowLeft() {
        return canales[0];
    }

    public double getDLowCenter() {
        if (canales.length >= 6) {
            return canales[1];
        }
        return 0.5 * (canales[0] + canales[1]);
    }

    public double getDLowRight() {
        if (canales.length >= 6) {
            return canales[2];
        }
        return canales[1];
    }

    public double getDHighLeft() {
        if (canales.length >= 6) {
            return canales[3];
        }
        return canales[2];
    }

    public double getDHighCenter() {
        if (canales.length >= 6) {
            return canales[4];
        }
        return 0.5 * (canales[2] + canales[3]);
    }

    public double getDHighRight() {
        if (canales.length >= 6) {
            return canales[5];
        }
        return canales[3];
    }

    public long getTimestamp() { return timestamp; }

    // Valida distancias en [0, 1000] cm (incluye 999.0 cm de timeout VL53L0X) y angulo en [-180, 180] sin NaN ni Infinitos
    public boolean isValido() {
        for (int i = 0; i < canales.length; i++) {
            double c = canales[i];
            if (Double.isNaN(c) || Double.isInfinite(c)) {
                return false;
            }
            if (i == 5 && canales.length == 6) {
                // angulo_servo (grados)
                if (c < -180.0 || c > 180.0) return false;
            } else {
                // distancia (cm)
                if (c < 0.0 || c > 1000.0) return false;
            }
        }
        return true;
    }

    public double[] toArrayNormalizado() {
        double[] norm = new double[canales.length];
        double span = MAX_RANGO_CM - MIN_RANGO_CM;
        for (int i = 0; i < canales.length; i++) {
            if (i == 5 && canales.length == 6) {
                // Normalización de angulo_servo [-45.0, 60.0] -> [0.0, 1.0]
                double v = (canales[i] - (-45.0)) / (60.0 - (-45.0));
                norm[i] = Math.min(1.0, Math.max(0.0, v));
            } else {
                double v = (canales[i] - MIN_RANGO_CM) / span;
                norm[i] = Math.min(1.0, Math.max(0.0, v));
            }
        }
        return norm;
    }

    @Override
    public String toString() {
        if (canales.length == 6) {
            return String.format(Locale.US,
                    "VectorSensores[US_L=%.1f, US_C=%.1f, US_R=%.1f, LSR_L=%.1f, LSR_R=%.1f cm, Servo=%.1f°]",
                    canales[0], canales[1], canales[2], canales[3], canales[4], canales[5]);
        }
        return "VectorSensores" + Arrays.toString(canales);
    }
}
