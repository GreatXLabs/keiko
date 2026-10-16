package com.orquitas.server;

/**
 * Estados del autómata finito de control conductual de la Orquita.
 */
public enum EstadoRobot {
    BUSQUEDA,
    APROXIMACION,
    CAPTURA,
    ESCAPE,
    FINALIZACION,
    ERROR_SEGURO
}
