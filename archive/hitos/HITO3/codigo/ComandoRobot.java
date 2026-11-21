package com.orquitas.server;

/**
 * Orden motriz diferencial emitida hacia los motores de la Orquita (RF-03 / RF-04).
 */
public class ComandoRobot {
    private final double vLeft;
    private final double vRight;
    private final EstadoRobot estado;

    public ComandoRobot(double vLeft, double vRight, EstadoRobot estado) {
        this.vLeft = vLeft;
        this.vRight = vRight;
        this.estado = estado;
    }

    public double getVLeft() {
        return vLeft;
    }

    public double getVRight() {
        return vRight;
    }

    public EstadoRobot getEstado() {
        return estado;
    }
}
