package com.orquitas.server;

import java.util.Arrays;
import java.util.Locale;

/**
 * Representacion del vector sensorial muestreado por la Orquita (doble plano ultrasonico HC-SR04).
 * Soporta configuracion principal de 6 sensores frontales (3 bajos + 3 altos: L, C, R)
 * asi como configuraciones de 4, 8 o 10 sensores.
 */
public class VectorSensores {
    public static final double MIN_RANGO_CM = 2.0;
    public static final double MAX_RANGO_CM = 250.0;

    private final double[] canales;
    private final long timestamp;
    private final boolean hasPose;
    private final double posX;
    private final double posY;
    private final double theta;

    // Crea vector de 4 sensores con marca temporal actual.
    public VectorSensores(double dLowLeft, double dLowRight, double dHighLeft, double dHighRight) {
        this(dLowLeft, dLowRight, dHighLeft, dHighRight, System.currentTimeMillis());
    }

    // Crea vector de 4 sensores indicando marca temporal explicita.
    public VectorSensores(double dLowLeft, double dLowRight, double dHighLeft, double dHighRight, long timestamp) {
        this(new double[]{dLowLeft, dLowRight, dHighLeft, dHighRight}, timestamp, false, 0.0, 0.0, 0.0);
    }

    // Crea vector de 6 sensores frontales (LL, LC, LR, HL, HC, HR) con marca temporal actual.
    public VectorSensores(double dLowLeft, double dLowCenter, double dLowRight,
                          double dHighLeft, double dHighCenter, double dHighRight) {
        this(new double[]{dLowLeft, dLowCenter, dLowRight, dHighLeft, dHighCenter, dHighRight},
                System.currentTimeMillis(), false, 0.0, 0.0, 0.0);
    }

    // Crea vector de N sensores con marca temporal explicita.
    public VectorSensores(double[] canales, long timestamp) {
        this(canales, timestamp, false, 0.0, 0.0, 0.0);
    }

    // Crea vector de N sensores con marca temporal y pose opcional de telemetria.
    public VectorSensores(double[] canales, long timestamp, boolean hasPose, double posX, double posY, double theta) {
        if (canales == null || canales.length < 4) {
            throw new IllegalArgumentException("Se requieren al menos 4 canales ultrasonicos");
        }
        this.canales = Arrays.copyOf(canales, canales.length);
        this.timestamp = timestamp;
        this.hasPose = hasPose;
        this.posX = posX;
        this.posY = posY;
        this.theta = theta;
    }

    // Retorna copia de todos los canales crudos en centimetros.
    public double[] getCanales() {
        return Arrays.copyOf(canales, canales.length);
    }

    // Retorna la cantidad de sensores en el vector (4, 6, 8, 10).
    public int getCantidadSensores() {
        return canales.length;
    }

    // Retorna distancia medida por sensor plano bajo izquierdo.
    public double getDLowLeft() {
        return canales[0];
    }

    // Retorna distancia medida por sensor plano bajo central (o promedio bajo si hay 4 sensores).
    public double getDLowCenter() {
        if (canales.length >= 6) {
            return canales[1];
        }
        return 0.5 * (canales[0] + canales[1]);
    }

    // Retorna distancia medida por sensor plano bajo derecho.
    public double getDLowRight() {
        if (canales.length >= 6) {
            return canales[2];
        }
        return canales[1];
    }

    // Retorna distancia medida por sensor plano alto izquierdo.
    public double getDHighLeft() {
        if (canales.length >= 6) {
            return canales[3];
        }
        return canales[2];
    }

    // Retorna distancia medida por sensor plano alto central (o promedio alto si hay 4 sensores).
    public double getDHighCenter() {
        if (canales.length >= 6) {
            return canales[4];
        }
        return 0.5 * (canales[2] + canales[3]);
    }

    // Retorna distancia medida por sensor plano alto derecho.
    public double getDHighRight() {
        if (canales.length >= 6) {
            return canales[5];
        }
        return canales[3];
    }

    // Retorna marca de tiempo de la medicion.
    public long getTimestamp() { return timestamp; }

    public boolean hasPose() { return hasPose; }
    public double getPosX() { return posX; }
    public double getPosY() { return posY; }
    public double getTheta() { return theta; }

    // Retorna menor distancia de los sensores de plano bajo.
    public double getMinLow() {
        if (canales.length >= 6) {
            return Math.min(canales[0], Math.min(canales[1], canales[2]));
        }
        return Math.min(canales[0], canales[1]);
    }

    // Retorna menor distancia de los sensores de plano alto.
    public double getMinHigh() {
        if (canales.length >= 6) {
            return Math.min(canales[3], Math.min(canales[4], canales[5]));
        }
        return Math.min(canales[2], canales[3]);
    }

    // Valida que todas las distancias esten dentro del rango fisico valido [0, 400] cm y sin NaN.
    public boolean isValido() {
        for (double c : canales) {
            if (Double.isNaN(c) || Double.isInfinite(c) || c < 0.0 || c > 400.0) {
                return false;
            }
        }
        return true;
    }

    // Normaliza las distancias en [0.0, 1.0] usando la escala Min-Max [2.0, 250.0] cm de entrenamiento Neuroph.
    public double[] toArrayNormalizado() {
        double[] norm = new double[canales.length];
        double span = MAX_RANGO_CM - MIN_RANGO_CM;
        for (int i = 0; i < canales.length; i++) {
            double v = (canales[i] - MIN_RANGO_CM) / span;
            norm[i] = Math.min(1.0, Math.max(0.0, v));
        }
        return norm;
    }

    // Sobrecarga de compatibilidad que delega en la normalizacion estandar [2.0, 250.0] cm.
    public double[] toArrayNormalizado(double maxRangoM) {
        return toArrayNormalizado();
    }

    // Retorna representacion textual legible del vector sensorial.
    @Override
    public String toString() {
        if (canales.length == 6) {
            return String.format(Locale.US,
                    "VectorSensores6[LL=%.1f, LC=%.1f, LR=%.1f, HL=%.1f, HC=%.1f, HR=%.1f cm]",
                    canales[0], canales[1], canales[2], canales[3], canales[4], canales[5]);
        }
        return "VectorSensores" + Arrays.toString(canales);
    }
}
