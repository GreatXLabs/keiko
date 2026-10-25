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

    // 150 ciclos a 32 ms por ciclo ~ 4.8 s: cubre el cruce completo de la bahia.
    private static final int HORIZONTE_OLVIDO_OSCILACIONES = 150;
    private static final int UMBRAL_OSCILACIONES_ANTIBUCLE = 2;
    private static final int TAM_HISTORIAL_LADOS = 6;

    private static final double VEL_AVANCE_ARCO = 4.8;
    private static final double VEL_ARCO_LENTA = 2.0;
    private static final double VEL_RETRO_ARCO_RAPIDA = -4.2;
    private static final double VEL_RETRO_ARCO_NORMAL = -4.2;
    private static final double VEL_RETRO_ARCO_BLOQUEO = -4.2;
    private static final double VEL_RETRO_ARCO_ANTIBUCLE = -4.2;
    private static final double VEL_GIRO_COMPROMETIDO = 4.6;
    private static final double VEL_GIRO_AMPLIO = 5.0;
    private static final double SESGO_GIRO_NORMAL = -1.6;
    private static final double SESGO_GIRO_ANTIBUCLE = -2.0;

    // Alineacion proactiva al estrecho de salida (apertura de 30 cm).
    private static final double VEL_RETRO_ALINEACION_SALIDA = -3.8;
    private static final double VEL_RETRO_PINZADO_SALIDA = -4.0;
    private static final double VEL_GIRO_ALINEACION_SALIDA = 4.4;

    private static final int PASOS_RETRO_ARCO_NORMAL = 6;
    private static final int PASOS_RETRO_ARCO_TOTAL = 10;
    private static final int PASOS_RETRO_ARCO_ANTIBUCLE = 12;
    private static final int PASOS_GIRO_BLOQUEO = 14;
    private static final int PASOS_GIRO_ANTIBUCLE = 16;
    private static final int PASOS_RETRO_ALINEACION_SALIDA = 5;
    private static final int PASOS_RETRO_PINZADO_SALIDA = 8;
    private static final int PASOS_GIRO_ALINEACION_SALIDA = 6;

    private final GestorEstomago gestorEstomago;
    private volatile EstadoRobot estado = EstadoRobot.BUSQUEDA;

    // Estado interno para maniobras comprometidas (marcha atras, giros y anti-bucle)
    private int pasosRetrocesoRestantes = 0;
    private double velRetrocesoIzq = VEL_RETRO_ARCO_RAPIDA;
    private double velRetrocesoDer = VEL_RETRO_ARCO_RAPIDA;
    private int pasosGiroComprometido = 0;
    private int direccionGiroComprometido = 1; // +1 = girar a derecha (vL > vR), -1 = girar a izquierda (vL < vR)
    private double velGiroComprometido = VEL_GIRO_COMPROMETIDO;
    private double sesgoGiroComprometido = 0.0;
    private boolean giroAlineacionSalida = false;
    private int ultimoLadoBloqueado = 0;       // -1 = izquierda, +1 = derecha
    private int ciclosMismoLadoBloqueado = 0;
    private int ciclosDesdeUltimoBloqueo = HORIZONTE_OLVIDO_OSCILACIONES;
    private int contadorOscilaciones = 0;
    private int contadorCiclos = 0;
    private int contadorReversosConsecutivos = 0;
    private int ciclosDesdeUltimoReverso = 200;
    private int ultimoSentidoAntiBucle = 1;
    private final int[] historialLados = new int[TAM_HISTORIAL_LADOS];
    private int indiceHistorialLados = 0;
    private int entradasHistorialLados = 0;

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
        ciclosDesdeUltimoReverso++;
        if (ciclosDesdeUltimoReverso > 100) {
            contadorReversosConsecutivos = 0;
        }

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
        if (pasosRetrocesoRestantes == 0 && pasosGiroComprometido > 0 && !giroAlineacionSalida) {
            if (estado != EstadoRobot.ESCAPE && !bCen && ("LOBITO".equals(cen) || "LOBITO".equals(izq) || "LOBITO".equals(der))) {
                pasosGiroComprometido = 0;
            } else if (estado == EstadoRobot.ESCAPE && !bCen && ("SALIDA".equals(cen) || "SALIDA".equals(izq) || "SALIDA".equals(der))) {
                pasosGiroComprometido = 0;
            }
        }

        // ---------------------------------------------------------------------
        // 2. EJECUCION DE MANIOBRA COMPROMETIDA ACTIVA (MARCHA ATRAS O GIRO)
        // El retroceso puede ser en arco asimetrico para que la cola siga la estela
        // libre en lugar de barrer lateralmente contra un obstaculo.
        // ---------------------------------------------------------------------
        if (pasosRetrocesoRestantes > 0) {
            pasosRetrocesoRestantes--;
            EstadoRobot est = (estado == EstadoRobot.ESCAPE) ? EstadoRobot.ESCAPE : EstadoRobot.BUSQUEDA;
            return new ComandoRobot(velRetrocesoIzq, velRetrocesoDer, est);
        }
        if (pasosGiroComprometido > 0) {
            pasosGiroComprometido--;
            EstadoRobot est = (estado == EstadoRobot.ESCAPE) ? EstadoRobot.ESCAPE : EstadoRobot.BUSQUEDA;
            if (direccionGiroComprometido >= 0) {
                return new ComandoRobot(velGiroComprometido + sesgoGiroComprometido, -velGiroComprometido, est);
            }
            return new ComandoRobot(-velGiroComprometido, velGiroComprometido + sesgoGiroComprometido, est);
        }

        // ---------------------------------------------------------------------
        // 3. DETECCION DE ENCIERRO FRONTAL TOTAL ([BLOQUEO, BLOQUEO, BLOQUEO] o [BLOQUEO, *, BLOQUEO])
        // Todos los sensores ven pared/obstaculo <= 18 cm o esta encerrado en esquina:
        // retroceder en arco asimetrico para despegar trompa y cola antes de girar amplio.
        // ---------------------------------------------------------------------
        // En ESCAPE, si el centro ve SALIDA el robot ya enfila el hueco: avanzar aunque
        // las jambas laterales aparezcan como BLOQUEO (apertura de solo 30 cm).
        boolean salidaFrontalEnEscape = estado == EstadoRobot.ESCAPE && "SALIDA".equals(cen);
        if (!salidaFrontalEnEscape && ((bIzq && bCen && bDer) || (bIzq && bDer))) {
            contadorOscilaciones++;
            boolean bucle = contadorOscilaciones >= UMBRAL_OSCILACIONES_ANTIBUCLE || esPatronAlternante();
            int pasosRetroceso = bucle ? PASOS_RETRO_ARCO_ANTIBUCLE : PASOS_RETRO_ARCO_TOTAL;
            iniciarRetrocesoArco(VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_RAPIDA, pasosRetroceso);
            // En ESCAPE preferir sentido consistente de barrido perimetral (+1 horario);
            // en BUSQUEDA alejarse del ultimo lado bloqueado o girar hacia mayor despeje.
            int fallbackDir = (estado == EstadoRobot.ESCAPE) ? 1 : (ultimoLadoBloqueado <= 0 ? 1 : -1);
            int dir = calcularDireccionEvasiva(sensores, fallbackDir);
            if (bucle) {
                contadorOscilaciones = 0;
                reiniciarHistorialLados();
                iniciarGiroComprometido(dir, PASOS_GIRO_ANTIBUCLE, VEL_GIRO_AMPLIO, SESGO_GIRO_ANTIBUCLE);
            } else {
                iniciarGiroComprometido(dir, PASOS_GIRO_BLOQUEO, VEL_GIRO_AMPLIO, SESGO_GIRO_NORMAL);
            }
            ciclosDesdeUltimoBloqueo = 0;
            EstadoRobot est = (estado == EstadoRobot.ESCAPE) ? EstadoRobot.ESCAPE : EstadoRobot.BUSQUEDA;
            return new ComandoRobot(VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_RAPIDA, est);
        }

        // ---------------------------------------------------------------------
        // 4. LOGICA ESPECIFICA DE MODO ESCAPE (3 CAPTURAS AUDITADAS EN ESTOMAGO.TXT)
        // ---------------------------------------------------------------------
        if (estado == EstadoRobot.ESCAPE) {
            // En ESCAPE cualquier LOBITO se ignora y se trata como ESPACIO_LIBRE
            boolean sIzq = "SALIDA".equals(izq);
            boolean sCen = "SALIDA".equals(cen);
            boolean sDer = "SALIDA".equals(der);

            // Caso 4a: El sensor central ya enfila el hueco de salida de 30 cm.
            // Protocolo de alineacion proactiva: centrar el chasis y desenganchar
            // las jambas con micro-retrocesos en lugar de empujar contra el borde.
            if (sCen) {
                if (bIzq && bDer) {
                    iniciarRetrocesoArco(VEL_RETRO_PINZADO_SALIDA, VEL_RETRO_PINZADO_SALIDA, PASOS_RETRO_PINZADO_SALIDA);
                    return new ComandoRobot(VEL_RETRO_PINZADO_SALIDA, VEL_RETRO_PINZADO_SALIDA, EstadoRobot.ESCAPE);
                }
                if (bDer) {
                    iniciarRetrocesoArco(VEL_RETRO_ALINEACION_SALIDA, VEL_RETRO_ALINEACION_SALIDA, PASOS_RETRO_ALINEACION_SALIDA);
                    iniciarGiroComprometido(-1, PASOS_GIRO_ALINEACION_SALIDA, VEL_GIRO_ALINEACION_SALIDA, 0.0);
                    giroAlineacionSalida = true;
                    return new ComandoRobot(VEL_RETRO_ALINEACION_SALIDA, VEL_RETRO_ALINEACION_SALIDA, EstadoRobot.ESCAPE);
                }
                if (bIzq) {
                    iniciarRetrocesoArco(VEL_RETRO_ALINEACION_SALIDA, VEL_RETRO_ALINEACION_SALIDA, PASOS_RETRO_ALINEACION_SALIDA);
                    iniciarGiroComprometido(1, PASOS_GIRO_ALINEACION_SALIDA, VEL_GIRO_ALINEACION_SALIDA, 0.0);
                    giroAlineacionSalida = true;
                    return new ComandoRobot(VEL_RETRO_ALINEACION_SALIDA, VEL_RETRO_ALINEACION_SALIDA, EstadoRobot.ESCAPE);
                }
                boolean cercaDeJambas = sensores != null && (sensores.getDLowLeft() < 42.0 || sensores.getDLowRight() < 42.0);
                if (cercaDeJambas) {
                    boolean masAbiertoIzq = (sIzq && !sDer) || (sensores.getDLowLeft() - sensores.getDLowRight() > 15.0);
                    boolean masAbiertoDer = (sDer && !sIzq) || (sensores.getDLowRight() - sensores.getDLowLeft() > 15.0);

                    if (masAbiertoIzq) {
                        return new ComandoRobot(2.4, 3.6, EstadoRobot.ESCAPE);
                    }
                    if (masAbiertoDer) {
                        return new ComandoRobot(3.6, 2.4, EstadoRobot.ESCAPE);
                    }
                    if (sensores.getDLowLeft() < 28.0) {
                        iniciarRetrocesoArco(VEL_RETRO_ALINEACION_SALIDA, VEL_RETRO_ALINEACION_SALIDA, 5);
                        iniciarGiroComprometido(1, 4, 3.2, 0.0);
                        giroAlineacionSalida = true;
                        return new ComandoRobot(VEL_RETRO_ALINEACION_SALIDA, VEL_RETRO_ALINEACION_SALIDA, EstadoRobot.ESCAPE);
                    }
                    if (sensores.getDLowRight() < 28.0) {
                        iniciarRetrocesoArco(VEL_RETRO_ALINEACION_SALIDA, VEL_RETRO_ALINEACION_SALIDA, 5);
                        iniciarGiroComprometido(-1, 4, 3.2, 0.0);
                        giroAlineacionSalida = true;
                        return new ComandoRobot(VEL_RETRO_ALINEACION_SALIDA, VEL_RETRO_ALINEACION_SALIDA, EstadoRobot.ESCAPE);
                    }
                    if (sensores.getDLowLeft() < 35.0 && sensores.getDLowRight() >= 45.0) {
                        return new ComandoRobot(3.6, 2.8, EstadoRobot.ESCAPE);
                    }
                    if (sensores.getDLowRight() < 35.0 && sensores.getDLowLeft() >= 45.0) {
                        return new ComandoRobot(2.8, 3.6, EstadoRobot.ESCAPE);
                    }
                    return new ComandoRobot(3.4, 3.4, EstadoRobot.ESCAPE);
                }
                return new ComandoRobot(VEL_AVANCE_ARCO, VEL_AVANCE_ARCO, EstadoRobot.ESCAPE);
            }

            // Caso 4b: El sensor izquierdo ve la SALIDA (>185 cm), aunque centro o derecha vean pared
            if (sIzq) {
                if (bCen) {
                    return iniciarRetrocesoArcoYPivote(-1, 7, EstadoRobot.ESCAPE);
                }
                if (bDer) {
                    registrarBloqueoLateral(1);
                    if (debeActivarAntiBucle()) {
                        return activarManiobraAntiBucle(-1, EstadoRobot.ESCAPE);
                    }
                    return new ComandoRobot(VEL_ARCO_LENTA, VEL_AVANCE_ARCO, EstadoRobot.ESCAPE);
                }
                return new ComandoRobot(2.4, 4.6, EstadoRobot.ESCAPE);
            }

            // Caso 4c: El sensor derecho ve la SALIDA (>185 cm), aunque centro o izquierda vean pared
            if (sDer) {
                if (bCen) {
                    return iniciarRetrocesoArcoYPivote(1, 7, EstadoRobot.ESCAPE);
                }
                if (bIzq) {
                    registrarBloqueoLateral(-1);
                    if (debeActivarAntiBucle()) {
                        return activarManiobraAntiBucle(1, EstadoRobot.ESCAPE);
                    }
                    return new ComandoRobot(VEL_AVANCE_ARCO, VEL_ARCO_LENTA, EstadoRobot.ESCAPE);
                }
                return new ComandoRobot(4.6, 2.4, EstadoRobot.ESCAPE);
            }

            // Caso 4d: Aún no ve SALIDA; manejo de bloqueos en ESCAPE siguiendo el perimetro.
            // Los bloqueos laterales se resuelven con arco hacia adelante; centro bloqueado
            // o encierro exigen retroceso en arco antes de pivotar.
            if (bIzq && bCen) {
                registrarBloqueoLateral(-1);
                if (debeActivarAntiBucle()) {
                    return activarManiobraAntiBucle(1, EstadoRobot.ESCAPE);
                }
                return iniciarRetrocesoArcoYPivote(1, 7, EstadoRobot.ESCAPE);
            }
            if (bCen && bDer) {
                registrarBloqueoLateral(1);
                if (debeActivarAntiBucle()) {
                    return activarManiobraAntiBucle(-1, EstadoRobot.ESCAPE);
                }
                return iniciarRetrocesoArcoYPivote(-1, 7, EstadoRobot.ESCAPE);
            }
            if (bCen) {
                int dir = calcularDireccionEvasiva(sensores, 1);
                return iniciarRetrocesoArcoYPivote(dir, 7, EstadoRobot.ESCAPE);
            }
            if (bIzq) {
                registrarBloqueoLateral(-1);
                if (debeActivarAntiBucle()) {
                    return activarManiobraAntiBucle(1, EstadoRobot.ESCAPE);
                }
                return new ComandoRobot(VEL_AVANCE_ARCO, VEL_ARCO_LENTA, EstadoRobot.ESCAPE);
            }
            if (bDer) {
                registrarBloqueoLateral(1);
                if (debeActivarAntiBucle()) {
                    return activarManiobraAntiBucle(-1, EstadoRobot.ESCAPE);
                }
                return new ComandoRobot(VEL_ARCO_LENTA, VEL_AVANCE_ARCO, EstadoRobot.ESCAPE);
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
            int dir = (ultimoLadoBloqueado <= 0) ? 1 : -1;
            iniciarGiroComprometido(dir, 9);
            return new ComandoRobot(VEL_GIRO_COMPROMETIDO * dir, -VEL_GIRO_COMPROMETIDO * dir, EstadoRobot.BUSQUEDA);
        }
        if (sIzq) {
            iniciarGiroComprometido(1, 6);
            return new ComandoRobot(VEL_GIRO_COMPROMETIDO, -VEL_GIRO_COMPROMETIDO, EstadoRobot.BUSQUEDA);
        }
        if (sDer) {
            iniciarGiroComprometido(-1, 6);
            return new ComandoRobot(-VEL_GIRO_COMPROMETIDO, VEL_GIRO_COMPROMETIDO, EstadoRobot.BUSQUEDA);
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

        // Prioridad 5c: Esquive y deflexion de bloqueos
        boolean obsIzq = bIzq || (sensores != null && (sensores.getDLowLeft() <= 18.0 || sensores.getDHighLeft() <= 20.0));
        boolean obsDer = bDer || (sensores != null && (sensores.getDLowRight() <= 18.0 || sensores.getDHighRight() <= 20.0));
        boolean obsCen = bCen || (sensores != null && (sensores.getDLowCenter() <= 23.0 || sensores.getDHighCenter() <= 25.0));

        // Flanqueo proactivo hacia espacio abierto: si un lateral esta encajonado
        // contra un muro (<22 cm) y el lado opuesto se abre ampliamente a la bahia (>50 cm),
        // virar decididamente hacia el sector despejado para escapar de pasillos ciegos.
        if (sensores != null && !obsCen && !obsIzq && !obsDer && !lIzq && !lDer && !lCen) {
            double dL = sensores.getDLowLeft();
            double dR = sensores.getDLowRight();
            if (dL < 22.0 && dR > 50.0) {
                return new ComandoRobot(4.6, 2.2, EstadoRobot.BUSQUEDA);
            }
            if (dR < 22.0 && dL > 50.0) {
                return new ComandoRobot(2.2, 4.6, EstadoRobot.BUSQUEDA);
            }
        }

        if (obsIzq && obsCen) {
            registrarBloqueoLateral(-1);
            if (ciclosMismoLadoBloqueado >= 5 || debeActivarAntiBucle()) {
                return activarManiobraAntiBucle(1, EstadoRobot.BUSQUEDA);
            }
            int dir = calcularDireccionEvasiva(sensores, 1);
            return iniciarRetrocesoArcoYPivote(dir, 7, EstadoRobot.BUSQUEDA);
        }

        if (obsCen && obsDer) {
            registrarBloqueoLateral(1);
            if (ciclosMismoLadoBloqueado >= 5 || debeActivarAntiBucle()) {
                return activarManiobraAntiBucle(-1, EstadoRobot.BUSQUEDA);
            }
            int dir = calcularDireccionEvasiva(sensores, -1);
            return iniciarRetrocesoArcoYPivote(dir, 7, EstadoRobot.BUSQUEDA);
        }

        if (obsCen) {
            int dir = calcularDireccionEvasiva(sensores, (ultimoLadoBloqueado <= 0) ? 1 : -1);
            return iniciarRetrocesoArcoYPivote(dir, 14, EstadoRobot.BUSQUEDA);
        }

        if (obsIzq) {
            registrarBloqueoLateral(-1);
            if (ciclosMismoLadoBloqueado >= 4 || debeActivarAntiBucle()) {
                return activarManiobraAntiBucle(1, EstadoRobot.BUSQUEDA);
            }
            if (sensores != null && sensores.getDLowLeft() <= 12.0) {
                int dir = calcularDireccionEvasiva(sensores, 1);
                return iniciarRetrocesoArcoYPivote(dir, 10, EstadoRobot.BUSQUEDA);
            }
            return new ComandoRobot(VEL_AVANCE_ARCO, VEL_ARCO_LENTA, EstadoRobot.BUSQUEDA);
        }

        if (obsDer) {
            registrarBloqueoLateral(1);
            if (ciclosMismoLadoBloqueado >= 4 || debeActivarAntiBucle()) {
                return activarManiobraAntiBucle(-1, EstadoRobot.BUSQUEDA);
            }
            if (sensores != null && sensores.getDLowRight() <= 12.0) {
                int dir = calcularDireccionEvasiva(sensores, -1);
                return iniciarRetrocesoArcoYPivote(dir, 10, EstadoRobot.BUSQUEDA);
            }
            return new ComandoRobot(VEL_ARCO_LENTA, VEL_AVANCE_ARCO, EstadoRobot.BUSQUEDA);
        }

        if (ciclosDesdeUltimoBloqueo > HORIZONTE_OLVIDO_OSCILACIONES) {
            contadorOscilaciones = 0;
            ciclosMismoLadoBloqueado = 0;
            ultimoLadoBloqueado = 0;
            reiniciarHistorialLados();
        }
        double sweep = ((contadorCiclos / 50) % 2 == 0) ? 0.55 : -0.55;
        return new ComandoRobot(4.8 + sweep, 4.8 - sweep, EstadoRobot.BUSQUEDA);
    }

    // Registra un bloqueo lateral y alimenta la memoria anti-bucle compartida.
    private void registrarBloqueoLateral(int lado) {
        if (ciclosDesdeUltimoBloqueo >= HORIZONTE_OLVIDO_OSCILACIONES) {
            contadorOscilaciones = 0;
            ciclosMismoLadoBloqueado = 0;
            ultimoLadoBloqueado = 0;
            reiniciarHistorialLados();
        }
        if (ultimoLadoBloqueado == lado) {
            ciclosMismoLadoBloqueado++;
        } else {
            ciclosMismoLadoBloqueado = 1;
            if (ultimoLadoBloqueado != 0) {
                contadorOscilaciones++;
            }
            registrarEnHistorial(lado);
        }
        ultimoLadoBloqueado = lado;
        ciclosDesdeUltimoBloqueo = 0;
    }

    private void reiniciarHistorialLados() {
        indiceHistorialLados = 0;
        entradasHistorialLados = 0;
    }

    private void registrarEnHistorial(int lado) {
        historialLados[indiceHistorialLados] = lado;
        indiceHistorialLados = (indiceHistorialLados + 1) % historialLados.length;
        if (entradasHistorialLados < historialLados.length) {
            entradasHistorialLados++;
        }
    }

    // Un historial que alterna izquierda/derecha cada pocos ciclos es un limit cycle.
    private boolean esPatronAlternante() {
        if (entradasHistorialLados < 3) {
            return false;
        }
        int anterior = 0;
        for (int i = 0; i < entradasHistorialLados; i++) {
            int posicion = (indiceHistorialLados - 1 - i + historialLados.length * 2) % historialLados.length;
            int actual = historialLados[posicion];
            if (actual == 0 || actual == anterior) {
                return false;
            }
            anterior = actual;
        }
        return true;
    }

    private boolean debeActivarAntiBucle() {
        return contadorOscilaciones >= UMBRAL_OSCILACIONES_ANTIBUCLE || esPatronAlternante();
    }

    private void iniciarRetrocesoArco(double vIzq, double vDer, int pasos) {
        // El ciclo actual ya devuelve el primer retroceso, por eso se descuenta uno.
        pasosRetrocesoRestantes = pasos - 1;
        velRetrocesoIzq = vIzq;
        velRetrocesoDer = vDer;
    }

    private void iniciarGiroComprometido(int direccion, int pasos) {
        iniciarGiroComprometido(direccion, pasos, VEL_GIRO_COMPROMETIDO, 0.0);
    }

    private void iniciarGiroComprometido(int direccion, int pasos, double velocidad, double sesgo) {
        pasosGiroComprometido = pasos;
        direccionGiroComprometido = direccion;
        velGiroComprometido = velocidad;
        sesgoGiroComprometido = sesgo;
        giroAlineacionSalida = false;
    }

    // Retroceso recto normal (6 ciclos) seguido de pivote asimetrico, para despejar trompa y cola.
    private ComandoRobot iniciarRetrocesoArcoYPivote(int direccionGiro, int pasosGiro, EstadoRobot estadoComando) {
        if (ciclosDesdeUltimoReverso < 70) {
            contadorReversosConsecutivos++;
        }
        ciclosDesdeUltimoReverso = 0;
        if (contadorReversosConsecutivos >= 2) {
            contadorReversosConsecutivos = 0;
            ultimoSentidoAntiBucle = -ultimoSentidoAntiBucle;
            return activarManiobraAntiBucle(ultimoSentidoAntiBucle, estadoComando);
        }
        iniciarRetrocesoArco(VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_RAPIDA, PASOS_RETRO_ARCO_NORMAL);
        iniciarGiroComprometido(direccionGiro, pasosGiro, VEL_GIRO_COMPROMETIDO, SESGO_GIRO_NORMAL);
        return new ComandoRobot(VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_RAPIDA, estadoComando);
    }

    // Maniobra anti-bucle en dos fases: retroceso recto profundo y giro amplio asimetrico.
    private ComandoRobot activarManiobraAntiBucle(int direccion, EstadoRobot estadoComando) {
        contadorOscilaciones = 0;
        ciclosMismoLadoBloqueado = 0;
        reiniciarHistorialLados();
        iniciarRetrocesoArco(VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_RAPIDA, PASOS_RETRO_ARCO_ANTIBUCLE);
        iniciarGiroComprometido(direccion, PASOS_GIRO_ANTIBUCLE, VEL_GIRO_AMPLIO, SESGO_GIRO_ANTIBUCLE);
        return new ComandoRobot(VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_RAPIDA, estadoComando);
    }

    // Calcula la direccion de giro evasivo priorizando el lado con mayor despeje fisico.
    private int calcularDireccionEvasiva(VectorSensores sensores, int fallback) {
        if (sensores != null) {
            double dL = sensores.getDLowLeft();
            double dR = sensores.getDLowRight();
            if (dL < 18.0 && dR >= 18.0) {
                return 1; // Obstaculo a la izquierda: girar hacia la derecha
            }
            if (dR < 18.0 && dL >= 18.0) {
                return -1; // Obstaculo a la derecha: girar hacia la izquierda
            }
            if (Math.abs(dL - dR) > 3.0) {
                return (dL > dR) ? -1 : 1; // Girar hacia el sector con mayor despeje libre
            }
        }
        return fallback;
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
        this.giroAlineacionSalida = false;
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
        this.giroAlineacionSalida = false;
    }
}
