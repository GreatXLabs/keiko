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

    // RF-07: Generar indicacion de movimiento exclusivamente segun la clase neuronal y el estado FSM.
    public synchronized ComandoRobot evaluar(VectorSensores sensores, String claseDetectada) {
        if (claseDetectada == null) {
            claseDetectada = "ESPACIO_LIBRE";
        }
        if (estado == EstadoRobot.BUSQUEDA || estado == EstadoRobot.APROXIMACION) {
            if ("LOBITO_IZQ".equals(claseDetectada)) {
                return new ComandoRobot(1.5, 2.4, EstadoRobot.APROXIMACION);
            } else if ("LOBITO_DER".equals(claseDetectada)) {
                return new ComandoRobot(2.4, 1.5, EstadoRobot.APROXIMACION);
            } else if ("LOBITO_CENTRO".equals(claseDetectada) || "LOBITO".equals(claseDetectada)) {
                return new ComandoRobot(2.2, 2.2, EstadoRobot.APROXIMACION);
            } else if ("OBSTACULO_IZQ".equals(claseDetectada)) {
                // Obstaculo a la izquierda -> girar a la derecha
                return new ComandoRobot(4.5, -4.5, EstadoRobot.BUSQUEDA);
            } else if ("OBSTACULO_DER".equals(claseDetectada)) {
                // Obstaculo a la derecha -> girar a la izquierda
                return new ComandoRobot(-4.5, 4.5, EstadoRobot.BUSQUEDA);
            } else if ("MURO".equals(claseDetectada) || "OBSTACULO_CENTRO".equals(claseDetectada) || "OBSTACULO".equals(claseDetectada)) {
                return new ComandoRobot(-5.0, 5.0, EstadoRobot.BUSQUEDA);
            } else {
                return new ComandoRobot(5.0, 5.0, EstadoRobot.BUSQUEDA);
            }
        } else if (estado == EstadoRobot.ESCAPE) {
            if ("SALIDA".equals(claseDetectada)) {
                return new ComandoRobot(6.0, 6.0, EstadoRobot.ESCAPE);
            } else if ("OBSTACULO_IZQ".equals(claseDetectada)) {
                return new ComandoRobot(4.5, -4.5, EstadoRobot.ESCAPE);
            } else if ("OBSTACULO_DER".equals(claseDetectada)) {
                return new ComandoRobot(-4.5, 4.5, EstadoRobot.ESCAPE);
            } else if ("MURO".equals(claseDetectada) || "OBSTACULO_CENTRO".equals(claseDetectada) || "OBSTACULO".equals(claseDetectada)) {
                return new ComandoRobot(-5.0, 5.0, EstadoRobot.ESCAPE);
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
