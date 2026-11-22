package com.orquitas.server;

/**
 * Planificador conductual que convierte el vector sensorial y la terna clasificada
 * [IZQUIERDA, CENTRO, DERECHA] en velocidades diferenciales segun el estado actual (RF-03 / RF-08).
 */
public class PlanificadorNavegacion {
    private volatile EstadoRobot estado;
    private final GestorEstomago gestorEstomago;

    public PlanificadorNavegacion(GestorEstomago gestorEstomago) {
        this.gestorEstomago = gestorEstomago;
        this.estado = (this.gestorEstomago != null && this.gestorEstomago.contarCapturasRegistradas() >= 3)
                ? EstadoRobot.ESCAPE
                : EstadoRobot.BUSQUEDA;
    }

    public synchronized ComandoRobot evaluar(VectorSensores sensores, String[] ternaPercepcion) {
        EstadoRobot estadoActual = getEstado();
        double usLeft = sensores.getUsLeft();
        double usCenter = sensores.getUsCenter();
        double usRight = sensores.getUsRight();
        double laserLeft = sensores.getLaserLeft();
        double laserRight = sensores.getLaserRight();
        double anguloServo = sensores.getAnguloServo();

        String izq = ternaPercepcion[0];
        String cen = ternaPercepcion[1];
        String der = ternaPercepcion[2];

        boolean encierroTotal = "BLOQUEO".equals(izq) && "BLOQUEO".equals(cen) && "BLOQUEO".equals(der);
        if (encierroTotal || usCenter < 15.0) {
            double vL = usLeft >= usRight ? -4.2 : -2.8;
            double vR = usLeft >= usRight ? -2.8 : -4.2;
            return new ComandoRobot(vL, vR, estadoActual);
        }

        if (estadoActual == EstadoRobot.ESCAPE) {
            if ("LOBITO".equals(izq)) {
                izq = "ESPACIO_LIBRE";
            }
            if ("LOBITO".equals(cen)) {
                cen = "ESPACIO_LIBRE";
            }
            if ("LOBITO".equals(der)) {
                der = "ESPACIO_LIBRE";
            }

            if ("SALIDA".equals(cen)) {
                return new ComandoRobot(5.0, 5.0, EstadoRobot.ESCAPE);
            }
            if ("SALIDA".equals(izq)) {
                return "BLOQUEO".equals(cen)
                        ? new ComandoRobot(-3.5, -1.5, EstadoRobot.ESCAPE)
                        : new ComandoRobot(2.6, 4.8, EstadoRobot.ESCAPE);
            }
            if ("SALIDA".equals(der)) {
                return "BLOQUEO".equals(cen)
                        ? new ComandoRobot(-1.5, -3.5, EstadoRobot.ESCAPE)
                        : new ComandoRobot(4.8, 2.6, EstadoRobot.ESCAPE);
            }
            if ("BLOQUEO".equals(cen)) {
                return new ComandoRobot(usLeft >= usRight ? -4.0 : 4.0,
                        usLeft >= usRight ? 4.0 : -4.0, EstadoRobot.ESCAPE);
            }
            if ("BLOQUEO".equals(izq)) {
                return new ComandoRobot(4.6, 2.8, EstadoRobot.ESCAPE);
            }
            if ("BLOQUEO".equals(der)) {
                return new ComandoRobot(2.8, 4.6, EstadoRobot.ESCAPE);
            }
            return new ComandoRobot(4.8, 4.8, EstadoRobot.ESCAPE);
        }

        if ("LOBITO".equals(cen) && !"BLOQUEO".equals(cen)) {
            boolean enZonaBucal = (laserLeft > 0.0 && laserLeft <= 18.0)
                    || (laserRight > 0.0 && laserRight <= 18.0);
            double v = enZonaBucal ? 2.4 : 3.6;
            double difLaser = (laserLeft > 0.0 && laserRight > 0.0)
                    ? (Math.min(40.0, laserLeft) - Math.min(40.0, laserRight)) * 0.02
                    : 0.0;
            double steer = Math.max(-0.6, Math.min(0.6, difLaser + anguloServo * 0.01));
            return new ComandoRobot(v + steer, v - steer, EstadoRobot.APROXIMACION);
        }
        if ("LOBITO".equals(izq)) {
            return new ComandoRobot(2.0, 3.4, EstadoRobot.APROXIMACION);
        }
        if ("LOBITO".equals(der)) {
            return new ComandoRobot(3.4, 2.0, EstadoRobot.APROXIMACION);
        }

        if ("SALIDA".equals(cen)) {
            return new ComandoRobot(4.2, -4.2, EstadoRobot.BUSQUEDA);
        }
        if ("SALIDA".equals(izq) && !"BLOQUEO".equals(der)) {
            return new ComandoRobot(4.2, 2.6, EstadoRobot.BUSQUEDA);
        }
        if ("SALIDA".equals(der) && !"BLOQUEO".equals(izq)) {
            return new ComandoRobot(2.6, 4.2, EstadoRobot.BUSQUEDA);
        }

        if ("BLOQUEO".equals(cen)) {
            return new ComandoRobot(usLeft >= usRight ? -4.0 : 4.0,
                    usLeft >= usRight ? 4.0 : -4.0, EstadoRobot.BUSQUEDA);
        }
        if ("BLOQUEO".equals(izq)) {
            return new ComandoRobot(4.6, 2.8, EstadoRobot.BUSQUEDA);
        }
        if ("BLOQUEO".equals(der)) {
            return new ComandoRobot(2.8, 4.6, EstadoRobot.BUSQUEDA);
        }
        return new ComandoRobot(4.8, 4.8, EstadoRobot.BUSQUEDA);
    }

    public synchronized void activarModoEscape() {
        this.estado = EstadoRobot.ESCAPE;
    }

    public EstadoRobot getEstado() {
        return estado;
    }
}
