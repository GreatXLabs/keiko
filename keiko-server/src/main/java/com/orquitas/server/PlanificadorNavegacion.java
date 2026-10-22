package com.orquitas.server;

import java.util.logging.Logger;

/**
 * Planificador y FSM de toma de decisiones de navegacion de la Orquita.
 * Opera sobre el vector neuronal de los 3 pares coaxiales [IZQ, CENTRO, DER]
 * (donde cada par produce BLOQUEO, ESPACIO_LIBRE, LOBITO o SALIDA) y mantiene
 * memoria de maniobras comprometidas (retroceso + giro) y deteccion anti-bucle.
 */
public class PlanificadorNavegacion {
    private static final Logger LOGGER = Logger.getLogger(PlanificadorNavegacion.class.getName());

    private final GestorEstomago gestorEstomago;
    private volatile EstadoRobot estado = EstadoRobot.BUSQUEDA;

    // Estado interno para maniobras comprometidas (marcha atras, giros y anti-bucle)
    private int pasosRetrocesoRestantes = 0;
    private int pasosGiroComprometido = 0;
    private int direccionGiroComprometido = 1; // +1 = girar a derecha (vL > vR), -1 = girar a izquierda (vL < vR)
    private int ultimoLadoBloqueado = 0;       // -1 = izquierda, +1 = derecha
    private int ciclosMismoLadoBloqueado = 0;
    private int ciclosDesdeUltimoBloqueo = 100;
    private int contadorOscilaciones = 0;
    private int contadorCiclos = 0;

    // Inicializa el planificador evaluando si el estomago ya posee capturas suficientes para escape.
    public PlanificadorNavegacion(GestorEstomago gestorEstomago) {
        this.gestorEstomago = gestorEstomago;
        if (gestorEstomago != null && gestorEstomago.contarCapturasValidas() >= ConfiguracionServidor.CAPTURAS_REQUERIDAS_ESCAPE) {
            this.estado = EstadoRobot.ESCAPE;
        }
    }

    // RF-07: Evalua la accion motriz a partir de las 3 clasificaciones neuronales [IZQ, CENTRO, DER].
    public synchronized ComandoRobot evaluarPares(VectorSensores sensores, String[] pares) {
        if (pares == null || pares.length < 3) {
            return new ComandoRobot(0.0, 0.0, estado);
        }
        contadorCiclos++;
        ciclosDesdeUltimoBloqueo++;

        String izq = pares[0] != null ? pares[0] : "ESPACIO_LIBRE";
        String cen = pares[1] != null ? pares[1] : "ESPACIO_LIBRE";
        String der = pares[2] != null ? pares[2] : "ESPACIO_LIBRE";

        boolean bIzq = "BLOQUEO".equals(izq);
        boolean bCen = "BLOQUEO".equals(cen);
        boolean bDer = "BLOQUEO".equals(der);

        if (estado == EstadoRobot.FINALIZACION || estado == EstadoRobot.ERROR_SEGURO) {
            return new ComandoRobot(0.0, 0.0, estado);
        }

        // ---------------------------------------------------------------------
        // 1. INTERRUPCION OPORTUNISTA DE GIRO COMPROMETIDO
        // Si durante un giro comprometido (post-retroceso) el robot ya tiene el frente
        // despejado de bloqueos y engancha un LOBITO (en BUSQUEDA) o la SALIDA (en ESCAPE),
        // cancela el resto del giro para aprovechar el objetivo inmediatamente.
        // ---------------------------------------------------------------------
        if (pasosRetrocesoRestantes == 0 && pasosGiroComprometido > 0) {
            if (estado != EstadoRobot.ESCAPE && !bCen && ("LOBITO".equals(cen) || "LOBITO".equals(izq) || "LOBITO".equals(der))) {
                pasosGiroComprometido = 0;
            } else if (estado == EstadoRobot.ESCAPE && !bCen && ("SALIDA".equals(cen) || "SALIDA".equals(izq) || "SALIDA".equals(der))) {
                pasosGiroComprometido = 0;
            }
        }

        // ---------------------------------------------------------------------
        // 2. EJECUCION DE MANIOBRA COMPROMETIDA ACTIVA (MARCHA ATRAS O GIRO)
        // ---------------------------------------------------------------------
        if (pasosRetrocesoRestantes > 0) {
            pasosRetrocesoRestantes--;
            EstadoRobot est = (estado == EstadoRobot.ESCAPE) ? EstadoRobot.ESCAPE : EstadoRobot.BUSQUEDA;
            return new ComandoRobot(-4.4, -4.4, est);
        }
        if (pasosGiroComprometido > 0) {
            pasosGiroComprometido--;
            EstadoRobot est = (estado == EstadoRobot.ESCAPE) ? EstadoRobot.ESCAPE : EstadoRobot.BUSQUEDA;
            double vGiro = 4.6;
            if (direccionGiroComprometido >= 0) {
                return new ComandoRobot(vGiro, -vGiro, est);
            } else {
                return new ComandoRobot(-vGiro, vGiro, est);
            }
        }

        // ---------------------------------------------------------------------
        // 3. DETECCION DE ENCIERRO FRONTAL TOTAL ([BLOQUEO, BLOQUEO, BLOQUEO] o [BLOQUEO, *, BLOQUEO])
        // Todos los sensores ven pared/obstaculo <= 18 cm o esta encerrado en esquina:
        // retroceder para despegar la trompa y luego girar amplio sin chocar.
        // ---------------------------------------------------------------------
        if ((bIzq && bCen && bDer) || (bIzq && bDer)) {
            contadorOscilaciones++;
            boolean bucle = contadorOscilaciones >= 3;
            pasosRetrocesoRestantes = bucle ? 10 : 6;
            pasosGiroComprometido = bucle ? 14 : 9;
            if (bucle) {
                contadorOscilaciones = 0;
            }
            // En ESCAPE preferir sentido consistente de barrido perimetral (+1 horario);
            // en BUSQUEDA alejarse del ultimo lado bloqueado.
            direccionGiroComprometido = (estado == EstadoRobot.ESCAPE) ? 1 : (ultimoLadoBloqueado <= 0 ? 1 : -1);
            ciclosDesdeUltimoBloqueo = 0;
            EstadoRobot est = (estado == EstadoRobot.ESCAPE) ? EstadoRobot.ESCAPE : EstadoRobot.BUSQUEDA;
            return new ComandoRobot(-4.4, -4.4, est);
        }

        // ---------------------------------------------------------------------
        // 4. LOGICA ESPECIFICA DE MODO ESCAPE (3 CAPTURAS AUDITADAS EN ESTOMAGO.TXT)
        // ---------------------------------------------------------------------
        if (estado == EstadoRobot.ESCAPE) {
            // En ESCAPE cualquier LOBITO se ignora y se trata como ESPACIO_LIBRE
            boolean sIzq = "SALIDA".equals(izq);
            boolean sCen = "SALIDA".equals(cen);
            boolean sDer = "SALIDA".equals(der);

            // Caso 4a: El sensor central ya enfila el hueco de salida de 30 cm
            if (sCen) {
                if (bIzq && !bDer) {
                    return new ComandoRobot(4.6, 3.6, EstadoRobot.ESCAPE);
                } else if (bDer && !bIzq) {
                    return new ComandoRobot(3.6, 4.6, EstadoRobot.ESCAPE);
                } else {
                    return new ComandoRobot(4.4, 4.4, EstadoRobot.ESCAPE);
                }
            }

            // Caso 4b: El sensor izquierdo ve la SALIDA (>185 cm), aunque centro o derecha vean pared
            if (sIzq) {
                if (bCen || bDer) {
                    return new ComandoRobot(-4.2, 4.2, EstadoRobot.ESCAPE);
                } else {
                    return new ComandoRobot(2.4, 4.6, EstadoRobot.ESCAPE);
                }
            }

            // Caso 4c: El sensor derecho ve la SALIDA (>185 cm), aunque centro o izquierda vean pared
            if (sDer) {
                if (bCen || bIzq) {
                    return new ComandoRobot(4.2, -4.2, EstadoRobot.ESCAPE);
                } else {
                    return new ComandoRobot(4.6, 2.4, EstadoRobot.ESCAPE);
                }
            }

            // Caso 4d: Aún no ve SALIDA; manejo de bloqueos en ESCAPE siguiendo el perimetro
            if (bIzq && bCen) {
                registrarBloqueoLateral(-1);
                pasosGiroComprometido = 6;
                direccionGiroComprometido = 1;
                return new ComandoRobot(4.6, -4.6, EstadoRobot.ESCAPE);
            }
            if (bCen && bDer) {
                registrarBloqueoLateral(1);
                pasosGiroComprometido = 6;
                direccionGiroComprometido = -1;
                return new ComandoRobot(-4.6, 4.6, EstadoRobot.ESCAPE);
            }
            if (bCen) {
                pasosGiroComprometido = 7;
                direccionGiroComprometido = 1;
                return new ComandoRobot(4.6, -4.6, EstadoRobot.ESCAPE);
            }
            if (bIzq) {
                registrarBloqueoLateral(-1);
                return new ComandoRobot(4.4, 2.6, EstadoRobot.ESCAPE);
            }
            if (bDer) {
                registrarBloqueoLateral(1);
                return new ComandoRobot(2.6, 4.4, EstadoRobot.ESCAPE);
            }

            double bias = ((contadorCiclos / 45) % 2 == 0) ? 0.35 : -0.35;
            return new ComandoRobot(4.8 + bias, 4.8 - bias, EstadoRobot.ESCAPE);
        }

        // ---------------------------------------------------------------------
        // 5. LOGICA DE BUSQUEDA Y APROXIMACION (CAPTURAS < 3)
        // ---------------------------------------------------------------------
        // Prioridad 5a: Evitar salir prematuramente por el hueco de 30 cm antes de tiempo
        boolean sIzq = "SALIDA".equals(izq);
        boolean sCen = "SALIDA".equals(cen);
        boolean sDer = "SALIDA".equals(der);

        if (sCen || (sIzq && sDer)) {
            pasosGiroComprometido = 9;
            direccionGiroComprometido = (ultimoLadoBloqueado <= 0) ? 1 : -1;
            return new ComandoRobot(4.6 * direccionGiroComprometido, -4.6 * direccionGiroComprometido, EstadoRobot.BUSQUEDA);
        }
        if (sIzq) {
            pasosGiroComprometido = 6;
            direccionGiroComprometido = 1;
            return new ComandoRobot(4.6, -4.6, EstadoRobot.BUSQUEDA);
        }
        if (sDer) {
            pasosGiroComprometido = 6;
            direccionGiroComprometido = -1;
            return new ComandoRobot(-4.6, 4.6, EstadoRobot.BUSQUEDA);
        }

        // Prioridad 5b: Deteccion y aproximacion de presas (LOBITO)
        boolean lIzq = "LOBITO".equals(izq);
        boolean lCen = "LOBITO".equals(cen);
        boolean lDer = "LOBITO".equals(der);

        if (lCen) {
            if (bIzq && !bDer) {
                return new ComandoRobot(3.6, 3.0, EstadoRobot.APROXIMACION);
            } else if (bDer && !bIzq) {
                return new ComandoRobot(3.0, 3.6, EstadoRobot.APROXIMACION);
            } else {
                return new ComandoRobot(3.8, 3.8, EstadoRobot.APROXIMACION);
            }
        }

        if (lIzq) {
            if (bCen) {
                return new ComandoRobot(-3.4, 3.4, EstadoRobot.APROXIMACION);
            } else {
                return new ComandoRobot(1.2, 4.2, EstadoRobot.APROXIMACION);
            }
        }

        if (lDer) {
            if (bCen) {
                return new ComandoRobot(3.4, -3.4, EstadoRobot.APROXIMACION);
            } else {
                return new ComandoRobot(4.2, 1.2, EstadoRobot.APROXIMACION);
            }
        }

        // Prioridad 5c: Esquive y deflexion de bloqueos (<= 22 cm)
        if (bIzq && bCen) {
            registrarBloqueoLateral(-1);
            if (ciclosMismoLadoBloqueado >= 5 || contadorOscilaciones >= 3) {
                iniciarDesatascoAntiBucle(1);
                return new ComandoRobot(-4.4, -4.4, EstadoRobot.BUSQUEDA);
            }
            pasosGiroComprometido = 6;
            direccionGiroComprometido = 1;
            return new ComandoRobot(4.8, -4.8, EstadoRobot.BUSQUEDA);
        }

        if (bCen && bDer) {
            registrarBloqueoLateral(1);
            if (ciclosMismoLadoBloqueado >= 5 || contadorOscilaciones >= 3) {
                iniciarDesatascoAntiBucle(-1);
                return new ComandoRobot(-4.4, -4.4, EstadoRobot.BUSQUEDA);
            }
            pasosGiroComprometido = 6;
            direccionGiroComprometido = -1;
            return new ComandoRobot(-4.8, 4.8, EstadoRobot.BUSQUEDA);
        }

        if (bCen) {
            int dir = (ultimoLadoBloqueado <= 0) ? 1 : -1;
            pasosGiroComprometido = 14;
            direccionGiroComprometido = dir;
            return new ComandoRobot(4.8 * dir, -4.8 * dir, EstadoRobot.BUSQUEDA);
        }

        if (bIzq) {
            registrarBloqueoLateral(-1);
            if (ciclosMismoLadoBloqueado >= 4 || contadorOscilaciones >= 3) {
                iniciarDesatascoAntiBucle(1);
                return new ComandoRobot(-4.4, -4.4, EstadoRobot.BUSQUEDA);
            }
            pasosGiroComprometido = 12;
            direccionGiroComprometido = 1;
            return new ComandoRobot(4.8, -4.8, EstadoRobot.BUSQUEDA);
        }

        if (bDer) {
            registrarBloqueoLateral(1);
            if (ciclosMismoLadoBloqueado >= 4 || contadorOscilaciones >= 3) {
                iniciarDesatascoAntiBucle(-1);
                return new ComandoRobot(-4.4, -4.4, EstadoRobot.BUSQUEDA);
            }
            pasosGiroComprometido = 12;
            direccionGiroComprometido = -1;
            return new ComandoRobot(-4.8, 4.8, EstadoRobot.BUSQUEDA);
        }

        if (ciclosDesdeUltimoBloqueo > 30) {
            contadorOscilaciones = 0;
            ciclosMismoLadoBloqueado = 0;
        }
        double sweep = ((contadorCiclos / 50) % 2 == 0) ? 0.55 : -0.55;
        return new ComandoRobot(4.8 + sweep, 4.8 - sweep, EstadoRobot.BUSQUEDA);
    }

    private void registrarBloqueoLateral(int lado) {
        if (ultimoLadoBloqueado == lado) {
            ciclosMismoLadoBloqueado++;
        } else {
            ciclosMismoLadoBloqueado = 1;
            if (ultimoLadoBloqueado != 0 && ciclosDesdeUltimoBloqueo < 22) {
                contadorOscilaciones++;
            }
        }
        if (ciclosDesdeUltimoBloqueo >= 30) {
            contadorOscilaciones = 0;
        }
        ultimoLadoBloqueado = lado;
        ciclosDesdeUltimoBloqueo = 0;
    }

    private void iniciarDesatascoAntiBucle(int dirPreferida) {
        contadorOscilaciones = 0;
        ciclosMismoLadoBloqueado = 0;
        pasosRetrocesoRestantes = 9;
        pasosGiroComprometido = 13;
        direccionGiroComprometido = dirPreferida;
    }

    // RF-07: Sobrecarga compatible que traduce una etiqueta global a terna [IZQ, CENTRO, DER] y delega.
    public synchronized ComandoRobot evaluar(VectorSensores sensores, String claseDetectada) {
        if (claseDetectada == null) {
            claseDetectada = "ESPACIO_LIBRE";
        }
        String[] pares;
        switch (claseDetectada) {
            case "LOBITO_IZQ":
                pares = new String[]{"LOBITO", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
                break;
            case "LOBITO_DER":
                pares = new String[]{"ESPACIO_LIBRE", "ESPACIO_LIBRE", "LOBITO"};
                break;
            case "LOBITO_CENTRO":
            case "LOBITO":
                pares = new String[]{"ESPACIO_LIBRE", "LOBITO", "ESPACIO_LIBRE"};
                break;
            case "OBSTACULO_IZQ":
                pares = new String[]{"BLOQUEO", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
                break;
            case "OBSTACULO_DER":
                pares = new String[]{"ESPACIO_LIBRE", "ESPACIO_LIBRE", "BLOQUEO"};
                break;
            case "OBSTACULO_CENTRO":
            case "OBSTACULO":
                pares = new String[]{"ESPACIO_LIBRE", "BLOQUEO", "ESPACIO_LIBRE"};
                break;
            case "MURO":
            case "BLOQUEO":
                pares = new String[]{"BLOQUEO", "BLOQUEO", "BLOQUEO"};
                break;
            case "SALIDA":
                pares = new String[]{"ESPACIO_LIBRE", "SALIDA", "ESPACIO_LIBRE"};
                break;
            default:
                pares = new String[]{"ESPACIO_LIBRE", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
                break;
        }
        return evaluarPares(sensores, pares);
    }

    // Activa la transicion formal hacia el modo escape.
    public synchronized void activarModoEscape() {
        this.estado = EstadoRobot.ESCAPE;
        this.pasosRetrocesoRestantes = 0;
        this.pasosGiroComprometido = 0;
        LOGGER.info("MUTACION CONDUCTUAL ACTIVADA: Modo ESCAPE.");
    }

    // Retorna el estado actual del automata finito del robot.
    public EstadoRobot getEstado() {
        return estado;
    }

    // Establece explicitamente el estado del automata finito.
    public synchronized void setEstado(EstadoRobot nuevoEstado) {
        this.estado = nuevoEstado;
        this.pasosRetrocesoRestantes = 0;
        this.pasosGiroComprometido = 0;
    }
}
