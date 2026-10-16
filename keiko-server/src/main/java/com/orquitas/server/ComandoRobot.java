package com.orquitas.server;

import java.util.Locale;

/**
 * Orden motriz diferencial emitida por el Servidor Central hacia los motores del cliente.
 */
public class ComandoRobot {
    private final double vLeft;
    private final double vRight;
    private final EstadoRobot estado;

    // Crea un comando motriz indicando velocidades diferenciales y estado FSM.
    public ComandoRobot(double vLeft, double vRight, EstadoRobot estado) {
        this.vLeft = vLeft;
        this.vRight = vRight;
        this.estado = estado;
    }

    // Retorna la velocidad angular o lineal del motor izquierdo.
    public double getVLeft() { return vLeft; }

    // Retorna la velocidad angular o lineal del motor derecho.
    public double getVRight() { return vRight; }

    // Retorna el estado conductual asociado al comando.
    public EstadoRobot getEstado() { return estado; }

    // Retorna la representacion textual del comando con formato decimal estadounidense.
    @Override
    public String toString() {
        return String.format(Locale.US, "ComandoRobot[vLeft=%.2f, vRight=%.2f, estado=%s]", vLeft, vRight, estado);
    }
}
