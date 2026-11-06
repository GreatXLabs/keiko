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
    private static final double SESGO_GIRO_NORMAL = 0.0;
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
    private boolean giroEsEvasivo = false;
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
    private int ciclosPersistenciaLobito = 0;
    private int ciclosAlineacionLobito = 0;
    private int ladoSeguimientoEscape = 0;     // -1 = pared a la izquierda, +1 = pared a la derecha, 0 = no definido
    private String ultimoLobito = null;
    private VectorSensores ultimoSensores = null;

    // Inicializa el planificador evaluando si el estomago ya posee capturas suficientes para escape.
    public PlanificadorNavegacion(GestorEstomago gestorEstomago) {
        this.gestorEstomago = gestorEstomago;
        if (gestorEstomago != null && gestorEstomago.contarCapturasValidas() >= ConfiguracionServidor.CAPTURAS_REQUERIDAS_ESCAPE) {
            this.estado = EstadoRobot.ESCAPE;
        }
    }

    // RF-07: Evalua la accion motriz a partir de las 3 clasificaciones neuronales [IZQ, CENTRO, DER].
    public synchronized ComandoRobot evaluarPares(VectorSensores sensores, String[] pares) {
        this.ultimoSensores = sensores;
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

        if (estado == EstadoRobot.ESCAPE) {
            if ("LOBITO".equals(izq)) izq = "ESPACIO_LIBRE";
            if ("LOBITO".equals(cen)) cen = "ESPACIO_LIBRE";
            if ("LOBITO".equals(der)) der = "ESPACIO_LIBRE";
        }

        boolean bIzq = "BLOQUEO".equals(izq);
        boolean bCen = "BLOQUEO".equals(cen);
        boolean bDer = "BLOQUEO".equals(der);

        if (estado == EstadoRobot.FINALIZACION || estado == EstadoRobot.ERROR_SEGURO) {
            return new ComandoRobot(0.0, 0.0, estado);
        }

        // ---------------------------------------------------------------------
        // 1. EJECUCION DE MANIOBRA COMPROMETIDA ACTIVA (MARCHA ATRAS O GIRO)
        // El retroceso puede ser en arco asimetrico para que la cola siga la estela
        // libre en lugar de barrer lateralmente contra un obstaculo.
        // ---------------------------------------------------------------------
        if (pasosRetrocesoRestantes > 0) {
            pasosRetrocesoRestantes--;
            EstadoRobot est = (estado == EstadoRobot.ESCAPE) ? EstadoRobot.ESCAPE : EstadoRobot.BUSQUEDA;
            if (velRetrocesoIzq > 0 && velRetrocesoDer > 0) {
                // Maniobra de avance (para despegar cola de colisión trasera o post-captura)
                if (bCen && pasosRetrocesoRestantes <= 4 && (sensores == null || sensores.getUsCenter() < 10.0)) {
                    pasosRetrocesoRestantes = 0;
                } else {
                    return new ComandoRobot(velRetrocesoIzq, velRetrocesoDer, est);
                }
            } else {
                return new ComandoRobot(velRetrocesoIzq, velRetrocesoDer, est);
            }
        }

        // Si detecta una canica real en BUSQUEDA (clasificada por Red 2), fijar el blanco inmediatamente
        boolean veCanicaL = "LOBITO".equals(izq) && !bIzq;
        boolean veCanicaC = "LOBITO".equals(cen) && (!bCen || (sensores != null && sensores.getUsCenter() >= 6.0));
        boolean veCanicaR = "LOBITO".equals(der) && !bDer;
        boolean veCanica = veCanicaL || veCanicaC || veCanicaR;
        if (veCanica && estado == EstadoRobot.BUSQUEDA) {
            pasosGiroComprometido = 0;
            giroEsEvasivo = false;
            if (sensores == null || sensores.getUsCenter() >= 8.0) {
                pasosRetrocesoRestantes = 0;
            }
        }

        // Si durante un giro el sector hacia el que se gira (o el centro) detecta BLOQUEO,
        // cancelar el giro comprometido para no estrellarse contra la pared u obstaculo
        if (pasosGiroComprometido > 0 && !giroEsEvasivo) {
            if (bCen) {
                pasosGiroComprometido = 0;
            }
        }

        if (pasosGiroComprometido > 0) {
            pasosGiroComprometido--;
            if (pasosGiroComprometido == 0) {
                giroEsEvasivo = false;
                giroAlineacionSalida = false;
            }
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
        // En APROXIMACION de canica, no abortar si aún hay >= 7 cm de despeje frontal al muro.
        boolean salidaFrontalEnEscape = estado == EstadoRobot.ESCAPE && "SALIDA".equals(cen);
        boolean enAproximacionValida = veCanica && (sensores == null || sensores.getUsCenter() >= 7.0);
        if (!salidaFrontalEnEscape && !enAproximacionValida && (bIzq && bCen && bDer)) {
            int fallbackDir = (estado == EstadoRobot.ESCAPE) ? 1 : (ultimoLadoBloqueado <= 0 ? 1 : -1);
            int dir = calcularDireccionEvasiva(sensores, fallbackDir);
            boolean bucle = debeActivarAntiBucle();
            int pasosGiro = bucle ? 28 : 20;
            int pasosRetroceso = bucle ? PASOS_RETRO_ARCO_ANTIBUCLE : PASOS_RETRO_ARCO_TOTAL;
            EstadoRobot est = (estado == EstadoRobot.ESCAPE) ? EstadoRobot.ESCAPE : EstadoRobot.BUSQUEDA;
            double vRetroL = (sensores == null) ? VEL_RETRO_ARCO_RAPIDA : -2.6;
            double vRetroR = (sensores == null) ? VEL_RETRO_ARCO_RAPIDA : -2.6;
            if (pasosRetroceso > 0) {
                iniciarRetrocesoArco(vRetroL, vRetroR, pasosRetroceso);
            }
            double velG = (sensores == null) ? 2.4 : 3.4;
            double sesgoG = (sensores == null) ? 1.8 : 0.0;
            iniciarGiroComprometido(dir, pasosGiro, velG, sesgoG);
            giroEsEvasivo = true;
            ciclosDesdeUltimoBloqueo = 0;
            if (pasosRetroceso > 0) {
                return new ComandoRobot(vRetroL, vRetroR, est);
            }
            double vGL = (dir >= 0) ? velG + sesgoG : -velG;
            double vGR = (dir >= 0) ? -velG : velG + sesgoG;
            return new ComandoRobot(vGL, vGR, est);
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
                    iniciarRetrocesoArco(-2.4, -2.4, 6);
                    return new ComandoRobot(-2.4, -2.4, EstadoRobot.ESCAPE);
                }
                if (bDer) {
                    return new ComandoRobot(1.6, 3.2, EstadoRobot.ESCAPE); // Despegar jamba derecha suavemente
                }
                if (bIzq) {
                    return new ComandoRobot(3.2, 1.6, EstadoRobot.ESCAPE); // Despegar jamba izquierda suavemente
                }
                double dl = (sensores != null) ? sensores.getUsLeft() : 250.0;
                double dr = (sensores != null) ? sensores.getUsRight() : 250.0;
                if (sensores != null) {
                    if (sensores.getLaserLeft() < 25.0) {
                        dl = Math.min(dl, sensores.getLaserLeft());
                    }
                    if (sensores.getLaserRight() < 25.0) {
                        dr = Math.min(dr, sensores.getLaserRight());
                    }
                }
                boolean cercaDeJambas = (dl < 40.0 || dr < 40.0);
                if (cercaDeJambas) {
                    // Si un sensor ve la pared de la jamba (<=35 cm) y el otro ve el hueco (>45 cm)
                    if (dr <= 35.0 && dl > 45.0) {
                        return new ComandoRobot(1.6, 3.2, EstadoRobot.ESCAPE); // Muy pegado a jamba der: virar a izq
                    } else if (dl <= 35.0 && dr > 45.0) {
                        return new ComandoRobot(3.2, 1.6, EstadoRobot.ESCAPE); // Muy pegado a jamba izq: virar a der
                    }
                    double diff = dl - dr;
                    if (diff < -2.0) {
                        return new ComandoRobot(2.8, 1.8, EstadoRobot.ESCAPE); // Centrar alejándose de jamba izq
                    } else if (diff > 2.0) {
                        return new ComandoRobot(1.8, 2.8, EstadoRobot.ESCAPE); // Centrar alejándose de jamba der
                    }
                    return new ComandoRobot(2.4, 2.4, EstadoRobot.ESCAPE); // Cruzar centro de la salida de 30 cm
                }
                return new ComandoRobot(3.4, 3.4, EstadoRobot.ESCAPE);
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

            // Caso 4d: Aún no ve SALIDA; navegación en bahía para localizar el estrecho de salida
            boolean frenteObstruido = bCen || (sensores != null && sensores.getUsCenter() < 20.0);
            boolean flancoIzqCerca = bIzq || (sensores != null && (sensores.getUsLeft() < 22.0 || sensores.getLaserLeft() < 16.0));
            boolean flancoDerCerca = bDer || (sensores != null && (sensores.getUsRight() < 22.0 || sensores.getLaserRight() < 16.0));

            if (frenteObstruido || (flancoIzqCerca && flancoDerCerca)) {
                int dir = calcularDireccionEvasiva(sensores, (bIzq ? 1 : -1));
                return iniciarRetrocesoArcoYPivote(dir, 18, EstadoRobot.ESCAPE);
            }
            if (flancoIzqCerca) {
                if (sensores != null && sensores.getLaserLeft() < 10.0) {
                    return new ComandoRobot(2.8, -2.8, EstadoRobot.ESCAPE);
                }
                return new ComandoRobot(3.6, 1.2, EstadoRobot.ESCAPE);
            }
            if (flancoDerCerca) {
                if (sensores != null && sensores.getLaserRight() < 10.0) {
                    return new ComandoRobot(-2.8, 2.8, EstadoRobot.ESCAPE);
                }
                return new ComandoRobot(1.2, 3.6, EstadoRobot.ESCAPE);
            }

            // Espacio libre al frente y a los flancos: avanzar con oscilación suave
            double bias = ((contadorCiclos / 35) % 2 == 0) ? 0.35 : -0.35;
            return new ComandoRobot(3.6 + bias, 3.6 - bias, EstadoRobot.ESCAPE);
        }

        // ---------------------------------------------------------------------
        // 5. LOGICA DE BUSQUEDA Y APROXIMACION (CAPTURAS < 3)
        // ---------------------------------------------------------------------
        // Prioridad 5a: Deteccion y aproximacion de presas (LOBITO) con embocado fino a boca de 7.0 cm
        boolean lIzq = "LOBITO".equals(izq);
        boolean lCen = "LOBITO".equals(cen);
        boolean lDer = "LOBITO".equals(der);

        boolean veCanicaCen = lCen && (!bCen || (sensores != null && sensores.getUsCenter() >= 6.0));
        boolean veCanicaIzq = lIzq && !bIzq;
        boolean veCanicaDer = lDer && !bDer;

        if (veCanicaCen) {
            pasosGiroComprometido = 0;
            giroEsEvasivo = false;
            ultimoLobito = "LOBITO_CENTRO";
            ciclosPersistenciaLobito = 50;
            // Desaceleración suave al 40-50% en los últimos 10-15 cm para embocar sin rebote
            boolean enZonaBucal = sensores != null && (sensores.getLaserLeft() <= 18.0 || sensores.getLaserRight() <= 18.0);
            double vApproach = enZonaBucal ? 2.4 : 3.4;

            double diff = 0.0;
            if (sensores != null) {
                double dl = sensores.getLaserLeft();
                double dr = sensores.getLaserRight();
                if (dl < 25.0 && dr < 25.0) {
                    diff = (dl - dr) * 1.5;
                } else if (dr < 25.0 && dl >= 25.0) {
                    diff = (25.0 - dr) + 3.0;
                } else if (dl < 25.0 && dr >= 25.0) {
                    diff = -((25.0 - dl) + 3.0);
                }
            }
            double steer = Math.max(-0.8, Math.min(0.8, diff * 0.12));

            if (bIzq && !bDer) {
                return new ComandoRobot(vApproach + 0.2, vApproach - 0.2, EstadoRobot.APROXIMACION);
            } else if (bDer && !bIzq) {
                return new ComandoRobot(vApproach - 0.2, vApproach + 0.2, EstadoRobot.APROXIMACION);
            } else {
                return new ComandoRobot(vApproach + steer, vApproach - steer, EstadoRobot.APROXIMACION);
            }
        }

        if (veCanicaIzq) {
            pasosGiroComprometido = 0;
            giroEsEvasivo = false;
            ciclosPersistenciaLobito = 40;
            ciclosAlineacionLobito = 0;
            ultimoLobito = "LOBITO_IZQ";
            double dl = (sensores != null) ? sensores.getLaserLeft() : 25.0;
            double vL = (dl < 16.0) ? 1.6 : 2.0;
            double vR = (dl < 16.0) ? 2.8 : 3.0;
            return new ComandoRobot(vL, vR, EstadoRobot.APROXIMACION);
        }

        if (veCanicaDer) {
            pasosGiroComprometido = 0;
            giroEsEvasivo = false;
            ciclosPersistenciaLobito = 40;
            ciclosAlineacionLobito = 0;
            ultimoLobito = "LOBITO_DER";
            double dr = (sensores != null) ? sensores.getLaserRight() : 25.0;
            double vL = (dr < 16.0) ? 2.8 : 3.0;
            double vR = (dr < 16.0) ? 1.6 : 2.0;
            return new ComandoRobot(vL, vR, EstadoRobot.APROXIMACION);
        }

        // Persistencia de aproximacion durante barrido de servos (para no perder el objetivo mientras el servo barre)
        boolean bloqueoEnTrayectoria = (sensores != null && sensores.getUsCenter() < 8.0)
                || (bIzq && bDer)
                || ("LOBITO_IZQ".equals(ultimoLobito) && bIzq)
                || ("LOBITO_DER".equals(ultimoLobito) && bDer)
                || (bCen && (sensores == null || sensores.getUsCenter() < 8.0));
        if (ciclosPersistenciaLobito > 0 && !bloqueoEnTrayectoria) {
            ciclosPersistenciaLobito--;
            ciclosAlineacionLobito++;

            // Si ambos láseres están balanceados a corta distancia: canica realmente centrada frente a la boca
            boolean yaCentrada = false;
            if (sensores != null) {
                double dl = sensores.getLaserLeft();
                double dr = sensores.getLaserRight();
                if (dl < 22.0 && dr < 22.0 && Math.abs(dl - dr) < 4.0) {
                    yaCentrada = true;
                }
            }
            if (yaCentrada || "LOBITO_CENTRO".equals(ultimoLobito)) {
                return new ComandoRobot(2.2, 2.2, EstadoRobot.APROXIMACION);
            }
            if ("LOBITO_IZQ".equals(ultimoLobito)) {
                return new ComandoRobot(1.8, 2.8, EstadoRobot.APROXIMACION);
            }
            if ("LOBITO_DER".equals(ultimoLobito)) {
                return new ComandoRobot(2.8, 1.8, EstadoRobot.APROXIMACION);
            }
            return new ComandoRobot(2.2, 2.2, EstadoRobot.APROXIMACION);
        } else if (bloqueoEnTrayectoria) {
            ciclosPersistenciaLobito = 0;
            ultimoLobito = null;
        }

        // Prioridad 5b: Evitar salir prematuramente por el estrecho de salida (30 cm) antes de Panza Llena
        boolean sCen = "SALIDA".equals(cen);
        boolean sIzq = "SALIDA".equals(izq);
        boolean sDer = "SALIDA".equals(der);

        if (sCen || sIzq || sDer) {
            int fallback = ((contadorCiclos / 100) % 2 == 0) ? 1 : -1;
            int dir = (sensores != null) ? calcularDireccionEvasiva(sensores, fallback) : fallback;
            if (sCen || (sIzq && sDer)) {
                int pasos = (sensores == null) ? 26 : (26 + ((contadorCiclos * 7) % 11));
                return iniciarRetrocesoArcoYPivote(dir, pasos, EstadoRobot.BUSQUEDA);
            } else if (sIzq) {
                // Salida lateral a la izquierda: avanzar curvando a la derecha sin pivotar contra la pared
                return new ComandoRobot(3.8, 2.4, EstadoRobot.BUSQUEDA);
            } else {
                // Salida lateral a la derecha: avanzar curvando a la izquierda sin pivotar contra la pared
                return new ComandoRobot(2.4, 3.8, EstadoRobot.BUSQUEDA);
            }
        }

        // Prioridad 5c: Esquive y deflexion de bloqueos (100% clasificados por red neuronal)
        boolean obsIzq = bIzq;
        boolean obsDer = bDer;
        boolean obsCen = bCen;

        if (obsIzq && obsCen) {
            registrarBloqueoLateral(-1);
            if (ciclosMismoLadoBloqueado >= 40 || debeActivarAntiBucle()) {
                return activarManiobraAntiBucle(1, EstadoRobot.BUSQUEDA);
            }
            int dir = calcularDireccionEvasiva(sensores, 1);
            int pasos = (sensores == null) ? 26 : (24 + ((contadorCiclos * 5) % 13));
            return iniciarRetrocesoArcoYPivote(dir, pasos, EstadoRobot.BUSQUEDA);
        }

        if (obsCen && obsDer) {
            registrarBloqueoLateral(1);
            if (ciclosMismoLadoBloqueado >= 40 || debeActivarAntiBucle()) {
                return activarManiobraAntiBucle(-1, EstadoRobot.BUSQUEDA);
            }
            int dir = calcularDireccionEvasiva(sensores, -1);
            int pasos = (sensores == null) ? 26 : (24 + ((contadorCiclos * 5) % 13));
            return iniciarRetrocesoArcoYPivote(dir, pasos, EstadoRobot.BUSQUEDA);
        }

        if (obsCen) {
            int fallback = ((contadorCiclos / 80) % 2 == 0) ? 1 : -1;
            int dir = calcularDireccionEvasiva(sensores, fallback);
            registrarBloqueoLateral(dir);
            if (debeActivarAntiBucle()) {
                return activarManiobraAntiBucle(-dir, EstadoRobot.BUSQUEDA);
            }
            int pasos = (sensores == null) ? 26 : (24 + ((contadorCiclos * 7) % 15));
            return iniciarRetrocesoArcoYPivote(dir, pasos, EstadoRobot.BUSQUEDA);
        }

        if (obsIzq) {
            registrarBloqueoLateral(-1);
            if (debeActivarAntiBucle()) {
                return activarManiobraAntiBucle(1, EstadoRobot.BUSQUEDA);
            }
            if (sensores != null && sensores.getLaserLeft() < 10.0) {
                return new ComandoRobot(2.8, -2.8, EstadoRobot.BUSQUEDA);
            }
            double vOuter = (sensores == null) ? VEL_AVANCE_ARCO : 3.8;
            double vInner = (sensores == null) ? VEL_ARCO_LENTA : 1.6;
            return new ComandoRobot(vOuter, vInner, EstadoRobot.BUSQUEDA);
        }

        if (obsDer) {
            registrarBloqueoLateral(1);
            if (debeActivarAntiBucle()) {
                return activarManiobraAntiBucle(-1, EstadoRobot.BUSQUEDA);
            }
            if (sensores != null && sensores.getLaserRight() < 10.0) {
                return new ComandoRobot(-2.8, 2.8, EstadoRobot.BUSQUEDA);
            }
            double vOuter = (sensores == null) ? VEL_AVANCE_ARCO : 3.8;
            double vInner = (sensores == null) ? VEL_ARCO_LENTA : 1.6;
            return new ComandoRobot(vInner, vOuter, EstadoRobot.BUSQUEDA);
        }

        if (ciclosDesdeUltimoBloqueo > HORIZONTE_OLVIDO_OSCILACIONES) {
            contadorOscilaciones = 0;
            ciclosMismoLadoBloqueado = 0;
            ultimoLadoBloqueado = 0;
            reiniciarHistorialLados();
        }
        double sweep = ((contadorCiclos / 75) % 2 == 0) ? 0.45 : -0.45;
        if (sensores != null) {
            double cl = sensores.getUsLeft();
            double cr = sensores.getUsRight();
            if (cl < 32.0 || cr < 32.0) {
                if (cl < cr - 2.0) {
                    sweep = 0.35; // Menor despeje a la izquierda: curvar suavemente a la derecha
                } else if (cr < cl - 2.0) {
                    sweep = -0.35; // Menor despeje a la derecha: curvar suavemente a la izquierda
                } else {
                    sweep = (ultimoLadoBloqueado <= 0) ? 0.35 : -0.35;
                }
            }
            double vBase = (sensores.getUsLeft() < 28.0 || sensores.getUsRight() < 28.0 || sensores.getUsCenter() < 35.0) ? 3.0 : 4.2;
            return new ComandoRobot(vBase + sweep, vBase - sweep, EstadoRobot.BUSQUEDA);
        }
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
        if (ultimoSensores != null) {
            return contadorOscilaciones >= 4 || (contadorOscilaciones >= 2 && ciclosDesdeUltimoBloqueo < 25);
        }
        return contadorOscilaciones >= UMBRAL_OSCILACIONES_ANTIBUCLE || esPatronAlternante();
    }

    private void iniciarRetrocesoArco(double vIzq, double vDer, int pasos) {
        // El ciclo actual ya devuelve el primer retroceso, por eso se descuenta uno.
        pasosRetrocesoRestantes = pasos - 1;
        velRetrocesoIzq = vIzq;
        velRetrocesoDer = vDer;
    }

    private void iniciarAvanceArco(double vIzq, double vDer, int pasos) {
        pasosRetrocesoRestantes = pasos - 1;
        velRetrocesoIzq = vIzq;
        velRetrocesoDer = vDer;
        pasosGiroComprometido = 0;
        giroEsEvasivo = false;
    }

    private void iniciarGiroComprometido(int direccion, int pasos) {
        iniciarGiroComprometido(direccion, pasos, 2.4, 1.8);
    }

    private void iniciarGiroComprometido(int direccion, int pasos, double velocidad, double sesgo) {
        pasosGiroComprometido = pasos;
        direccionGiroComprometido = direccion;
        velGiroComprometido = velocidad;
        sesgoGiroComprometido = sesgo;
        giroAlineacionSalida = false;
    }

    // Retroceso y/o pivote evasivo limpio sin colisión trasera.
    private ComandoRobot iniciarRetrocesoArcoYPivote(int direccionGiro, int pasosGiro, EstadoRobot estadoComando) {
        if (ciclosDesdeUltimoReverso < 70) {
            contadorReversosConsecutivos++;
        }
        if (contadorReversosConsecutivos >= 4) {
            contadorReversosConsecutivos = 0;
            ultimoSentidoAntiBucle = -ultimoSentidoAntiBucle;
            return activarManiobraAntiBucle(ultimoSentidoAntiBucle, estadoComando);
        }
        boolean pegadoFrontal = (ultimoSensores != null) && (
            ultimoSensores.getUsCenter() < 11.0 ||
            ultimoSensores.getLaserLeft() < 9.0 ||
            ultimoSensores.getLaserRight() < 9.0
        );
        ciclosDesdeUltimoReverso = 0;
        int pasosRetro = (ultimoSensores == null) ? 10 : (pegadoFrontal ? 4 : 0);
        double vRetroL = (ultimoSensores == null) ? -3.6 : ((direccionGiro >= 0) ? -3.0 : -2.0);
        double vRetroR = (ultimoSensores == null) ? -3.6 : ((direccionGiro >= 0) ? -2.0 : -3.0);
        if (pasosRetro > 0) {
            iniciarRetrocesoArco(vRetroL, vRetroR, pasosRetro);
        } else {
            pasosRetrocesoRestantes = 0;
        }
        double velG = (ultimoSensores == null) ? 2.4 : 3.4;
        double sesgoG = (ultimoSensores == null) ? 1.8 : 0.0;
        iniciarGiroComprometido(direccionGiro, Math.max(pasosGiro, 18), velG, sesgoG);
        giroEsEvasivo = true;
        if (pasosRetro > 0) {
            return new ComandoRobot(vRetroL, vRetroR, estadoComando);
        }
        double vGL = (direccionGiro >= 0) ? velG + sesgoG : -velG;
        double vGR = (direccionGiro >= 0) ? -velG : velG + sesgoG;
        return new ComandoRobot(vGL, vGR, estadoComando);
    }

    // Notificación explícita de ingesta de presa: avance recto en el espacio despejado donde estaba la canica
    public synchronized void notificarCaptura() {
        iniciarAvanceArco(2.8, 2.8, 10);
        ciclosPersistenciaLobito = 0;
        ultimoLobito = null;
    }

    // Notificación de impacto físico o contacto: si no está ya retrocediendo, retroceder recto para despegarse del obstáculo
    public synchronized void notificarColision() {
        notificarColision("unknown");
    }

    public synchronized void notificarColision(String lado) {
        int dir;
        if ("rear_left".equals(lado)) {
            dir = 1;  // Obstáculo atrás a la izquierda: pivotar a la derecha (+1)
        } else if ("rear_right".equals(lado)) {
            dir = -1; // Obstáculo atrás a la derecha: pivotar a la izquierda (-1)
        } else if ("front_left".equals(lado)) {
            dir = 1;  // Obstáculo adelante a la izquierda: pivotar a la derecha (+1)
        } else if ("front_right".equals(lado)) {
            dir = -1; // Obstáculo adelante a la derecha: pivotar a la izquierda (-1)
        } else {
            dir = (ultimoSensores != null) ? calcularDireccionEvasiva(ultimoSensores, 1)
                                           : ((ultimoLadoBloqueado <= 0) ? 1 : -1);
        }

        pasosRetrocesoRestantes = 0;
        double velG = (ultimoSensores == null) ? 2.4 : 3.4;
        double sesgoG = (ultimoSensores == null) ? 1.8 : 0.0;
        iniciarGiroComprometido(dir, 16, velG, sesgoG);
        giroEsEvasivo = true;
        ciclosPersistenciaLobito = 0;
        ultimoLobito = null;
    }

    // Maniobra anti-bucle en dos fases: micro-despeje y giro amplio en el lugar.
    private ComandoRobot activarManiobraAntiBucle(int direccion, EstadoRobot estadoComando) {
        contadorOscilaciones = 0;
        ciclosMismoLadoBloqueado = 0;
        reiniciarHistorialLados();
        boolean pegadoFrontal = (ultimoSensores != null) && (
            ultimoSensores.getUsCenter() < 11.0 ||
            ultimoSensores.getLaserLeft() < 9.0 ||
            ultimoSensores.getLaserRight() < 9.0
        );
        int pasosRetro = (ultimoSensores == null) ? PASOS_RETRO_ARCO_ANTIBUCLE : (pegadoFrontal ? 6 : 0);
        double vRetro = (ultimoSensores == null) ? VEL_RETRO_ARCO_ANTIBUCLE : -3.0;
        int pasosGiro = (ultimoSensores == null) ? PASOS_GIRO_ANTIBUCLE : 24;
        double velG = (ultimoSensores == null) ? 2.4 : 3.4;
        double sesgoG = (ultimoSensores == null) ? 1.8 : 0.0;

        ciclosDesdeUltimoReverso = 0;
        if (pasosRetro > 0) {
            iniciarRetrocesoArco(vRetro, vRetro, pasosRetro);
        } else {
            pasosRetrocesoRestantes = 0;
        }
        iniciarGiroComprometido(direccion, pasosGiro, velG, sesgoG);
        giroEsEvasivo = true;
        if (pasosRetro > 0) {
            return new ComandoRobot(vRetro, vRetro, estadoComando);
        }
        double vGL = (direccion >= 0) ? velG + sesgoG : -velG;
        double vGR = (direccion >= 0) ? -velG : velG + sesgoG;
        return new ComandoRobot(vGL, vGR, estadoComando);
    }

    // Calcula la direccion de giro evasivo priorizando el lado con mayor despeje fisico.
    private int calcularDireccionEvasiva(VectorSensores sensores, int fallback) {
        if (sensores != null) {
            double clearanceL = sensores.getUsLeft();
            double clearanceR = sensores.getUsRight();
            if (sensores.getCantidadSensores() >= 6 && sensores.getAnguloServo() >= 0.0) {
                clearanceL = Math.min(clearanceL, sensores.getLaserLeft());
                clearanceR = Math.min(clearanceR, sensores.getLaserRight());
            }
            if (clearanceL < 22.0 && clearanceR >= 22.0) {
                return 1; // Obstaculo a la izquierda: girar hacia la derecha
            }
            if (clearanceR < 22.0 && clearanceL >= 22.0) {
                return -1; // Obstaculo a la derecha: girar hacia la izquierda
            }
            if (Math.abs(clearanceL - clearanceR) >= 0.8) {
                return (clearanceL > clearanceR) ? -1 : 1; // Girar hacia el sector con mayor despeje libre
            }
        }
        return fallback;
    }

    // Activa la transicion formal hacia el modo escape.
    public synchronized void activarModoEscape() {
        this.estado = EstadoRobot.ESCAPE;
        this.ladoSeguimientoEscape = 0;
        this.pasosRetrocesoRestantes = 0;
        this.pasosGiroComprometido = 0;
        this.giroAlineacionSalida = false;
        this.ciclosPersistenciaLobito = 0;
        this.ultimoLobito = null;
        LOGGER.info("MUTACION CONDUCTUAL ACTIVADA: Modo ESCAPE.");
    }

    // Retorna el estado actual del automata finito del robot.
    public EstadoRobot getEstado() {
        return estado;
    }

    // Establece explicitamente el estado del automata finito.
    public synchronized void setEstado(EstadoRobot nuevoEstado) {
        this.estado = nuevoEstado;
        if (nuevoEstado == EstadoRobot.ESCAPE) {
            this.ladoSeguimientoEscape = 0;
        }
        this.pasosRetrocesoRestantes = 0;
        this.pasosGiroComprometido = 0;
        this.giroAlineacionSalida = false;
    }
}
