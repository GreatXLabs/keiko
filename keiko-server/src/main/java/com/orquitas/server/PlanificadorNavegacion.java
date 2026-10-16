package com.orquitas.server;

import java.util.logging.Logger;

/**
 * Planificador y FSM de toma de decisiones de navegacion de la Orquita.
 */
public class PlanificadorNavegacion {
    private static final Logger LOGGER = Logger.getLogger(PlanificadorNavegacion.class.getName());

    private final GestorEstomago gestorEstomago;
    private volatile EstadoRobot estado = EstadoRobot.BUSQUEDA;

    // Inicializa el planificador evaluando si el estomago ya posee capturas suficientes para escape.
    public PlanificadorNavegacion(GestorEstomago gestorEstomago) {
        this.gestorEstomago = gestorEstomago;
        if (gestorEstomago.contarCapturasValidas() >= ConfiguracionServidor.CAPTURAS_REQUERIDAS_ESCAPE) {
            this.estado = EstadoRobot.ESCAPE;
        }
    }

    // RF-07: Generar indicacion de movimiento segun deteccion neural y estado FSM.
    public synchronized ComandoRobot evaluar(VectorSensores sensores, String claseDetectada) {
        if (estado == EstadoRobot.BUSQUEDA) {
            if ("LOBITO".equals(claseDetectada)) {
                double vLeft = 1.8;
                double vRight = 1.8;
                if (sensores.getDLowLeft() < sensores.getDLowRight()) {
                    vRight += 0.4;
                } else {
                    vLeft += 0.4;
                }
                return new ComandoRobot(vLeft, vRight, EstadoRobot.APROXIMACION);
            } else if ("MURO".equals(claseDetectada) || "OBSTACULO".equals(claseDetectada)) {
                return new ComandoRobot(-5.0, 5.0, EstadoRobot.BUSQUEDA);
            } else {
                return new ComandoRobot(5.0, 5.0, EstadoRobot.BUSQUEDA);
            }
        } else if (estado == EstadoRobot.ESCAPE) {
            if ("MURO".equals(claseDetectada) || "OBSTACULO".equals(claseDetectada)) {
                return new ComandoRobot(-5.0, 5.0, EstadoRobot.ESCAPE);
            } else if ("SALIDA".equals(claseDetectada)) {
                return new ComandoRobot(6.0, 6.0, EstadoRobot.ESCAPE);
            } else {
                return new ComandoRobot(5.0, 5.0, EstadoRobot.ESCAPE);
            }
        } else {
            return new ComandoRobot(0.0, 0.0, estado);
        }
    }

    // Activa la transicion formal hacia el modo escape.
    public synchronized void activarModoEscape() {
        this.estado = EstadoRobot.ESCAPE;
        LOGGER.info("MUTACION CONDUCTUAL ACTIVADA: Modo ESCAPE.");
    }

    // Retorna el estado actual del automata finito del robot.
    public EstadoRobot getEstado() {
        return estado;
    }

    // Establece explicitamente el estado del automata finito.
    public void setEstado(EstadoRobot nuevoEstado) {
        this.estado = nuevoEstado;
    }
}
