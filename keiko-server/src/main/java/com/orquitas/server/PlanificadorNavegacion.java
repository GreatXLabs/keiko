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

    // Vector de barrido radar por tics (-40° a +40° en pasos de 5° = 17 tics)
    private static final int TAM_VECTOR_RADAR = 17;
    private static final double ANGULO_MIN_RADAR = -40.0;
    private static final double PASO_TIC_RADAR = 5.0;
    private static final int MAX_TICS_ANCHO_CANICA = 6;
    private final double[] vectorRadarIzq = new double[TAM_VECTOR_RADAR];
    private final double[] vectorRadarDer = new double[TAM_VECTOR_RADAR];
    private final boolean[] vectorCandidatoIzq = new boolean[TAM_VECTOR_RADAR];
    private final boolean[] vectorCandidatoDer = new boolean[TAM_VECTOR_RADAR];
    private final int[] cicloLecturaRadar = new int[TAM_VECTOR_RADAR];

    // Estado para estrategia Stop-and-Scan (pausa breve de escaneo estacionario) y escudo lateral
    private int pasosPausaEscaneo = 0;
    private int ciclosSinPausaEscaneo = 0;
    private int ultimoCicloPausaCandidato = -100;
    private int ciclosAlertaFlancoIzq = 0;
    private int ciclosAlertaFlancoDer = 0;
    private double ultimaYEstimadaCanica = 0.0;
    private int ultimoSentidoGiroEvasivo = 0;
    private int ciclosDesdeUltimoGiroEvasivo = 200;
    private int pasosGiroAcumulados = 0;

    // Inicializa el planificador evaluando si el estomago ya posee capturas suficientes para escape.
    public PlanificadorNavegacion(GestorEstomago gestorEstomago) {
        this.gestorEstomago = gestorEstomago;
        for (int i = 0; i < TAM_VECTOR_RADAR; i++) {
            vectorRadarIzq[i] = 250.0;
            vectorRadarDer[i] = 250.0;
            cicloLecturaRadar[i] = -1000;
        }
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
        ciclosDesdeUltimoGiroEvasivo++;
        ciclosSinPausaEscaneo++;
        if (ciclosAlertaFlancoIzq > 0) ciclosAlertaFlancoIzq--;
        if (ciclosAlertaFlancoDer > 0) ciclosAlertaFlancoDer--;
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

        // Actualizar vector radar por tics y validar ancho angular de candidatos a canica
        if (sensores != null && sensores.getCantidadSensores() >= 6) {
            actualizarVectorRadar(sensores, "LOBITO".equals(izq), "LOBITO".equals(cen), "LOBITO".equals(der));
            int idxActual = anguloATicIndex(sensores.getAnguloServo());
            if ("LOBITO".equals(izq) && !esAnchoTicsCanicaValido(true, idxActual)) {
                izq = (sensores.getUsLeft() < 22.0) ? "BLOQUEO" : "ESPACIO_LIBRE";
            }
            if ("LOBITO".equals(der) && !esAnchoTicsCanicaValido(false, idxActual)) {
                der = (sensores.getUsRight() < 22.0) ? "BLOQUEO" : "ESPACIO_LIBRE";
            }
            // Protección de impacto inminente: una canica nunca está a < 3.6 cm de los láseres laterales
            if (sensores.getAnguloServo() >= -20.0) {
                if (sensores.getLaserLeft() < 3.6) {
                    izq = "BLOQUEO";
                }
                if (sensores.getLaserRight() < 3.6) {
                    der = "BLOQUEO";
                }
            }
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
            pasosGiroAcumulados++;
            ciclosDesdeUltimoGiroEvasivo = 0;
            if (sensores != null && giroEsEvasivo) {
                // Si ya giró lo suficiente y tiene el frente y flancos despejados hacia el interior de la bahía,
                // finalizar el giro antes de sobre-rotar hacia la otra pared de una esquina
                if (pasosGiroAcumulados >= 10
                        && sensores.getUsCenter() > 48.0
                        && sensores.getUsLeft() > 26.0
                        && sensores.getUsRight() > 26.0
                        && sensores.getLaserLeft() > 18.0
                        && sensores.getLaserRight() > 18.0) {
                    pasosGiroComprometido = 0;
                } else if (pasosGiroComprometido <= 1 && pasosGiroAcumulados < 76
                        && (sensores.getUsCenter() < 25.0 || sensores.getUsLeft() < 17.0 || sensores.getUsRight() < 17.0)) {
                    // Si aún apunta hacia la pared de una esquina a 90°, continuar girando en el MISMO sentido
                    pasosGiroComprometido = 10;
                }
            }
            if (pasosGiroComprometido == 0) {
                giroEsEvasivo = false;
                giroAlineacionSalida = false;
                pasosGiroAcumulados = 0;
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
            int pasosGiro = (sensores == null) ? (bucle ? 28 : 20) : (bucle ? 52 : 32);
            boolean pegadoFrontal = (sensores == null) || (
                sensores.getUsCenter() < 11.0 ||
                sensores.getLaserLeft() < 9.0 ||
                sensores.getLaserRight() < 9.0
            );
            int pasosRetroceso = pegadoFrontal ? (bucle ? PASOS_RETRO_ARCO_ANTIBUCLE : PASOS_RETRO_ARCO_TOTAL) : 0;
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
        // Prioridad 5a: Deteccion y aproximacion de presas (LOBITO) con radar por tics,
        // pausa estacionaria Stop-and-Scan y centrado binocular a boca de 7.0 cm
        boolean lIzq = "LOBITO".equals(izq);
        boolean lCen = "LOBITO".equals(cen);
        boolean lDer = "LOBITO".equals(der);

        boolean veCanicaCen = lCen && (!bCen || (sensores != null && sensores.getUsCenter() >= 6.0));
        boolean veCanicaIzq = lIzq && !bIzq && (sensores == null || sensores.getLaserLeft() >= 3.8);
        boolean veCanicaDer = lDer && !bDer && (sensores == null || sensores.getLaserRight() >= 3.8);

        // Seguridad ultrasónica continua: cancelar cualquier pausa de escaneo si hay riesgo cercano
        boolean riesgoUltrasonico = bIzq || bCen || bDer || (sensores != null && (
                sensores.getUsCenter() < 16.0 || sensores.getUsLeft() < 14.0 || sensores.getUsRight() < 14.0));
        if (riesgoUltrasonico) {
            pasosPausaEscaneo = 0;
        }

        // Stop-and-Scan al detectar un nuevo candidato a distancia media: detenerse brevemente
        // para que los servos barran varios tics sin vibración ni desplazamiento del chasis
        if (!riesgoUltrasonico && sensores != null && sensores.getCantidadSensores() >= 6
                && (veCanicaCen || veCanicaIzq || veCanicaDer)
                && ciclosPersistenciaLobito == 0
                && (contadorCiclos - ultimoCicloPausaCandidato) >= 28) {
            double dMinCand = Math.min(sensores.getLaserLeft(), sensores.getLaserRight());
            if (dMinCand > 11.5 && dMinCand <= 23.0) {
                pasosPausaEscaneo = 3; // 3 tics adicionales (4 lecturas limpias en total)
                ultimoCicloPausaCandidato = contadorCiclos;
                ciclosSinPausaEscaneo = 0;
                ciclosPersistenciaLobito = 45;
                if (veCanicaCen) ultimoLobito = "LOBITO_CENTRO";
                else if (veCanicaIzq) ultimoLobito = "LOBITO_IZQ";
                else ultimoLobito = "LOBITO_DER";
                return new ComandoRobot(0.0, 0.0, EstadoRobot.APROXIMACION);
            }
        }

        // Ejecución de ventana activa de Stop-and-Scan (motores en 0.0 mientras el radar barre tics)
        if (pasosPausaEscaneo > 0 && sensores != null) {
            pasosPausaEscaneo--;
            ciclosSinPausaEscaneo = 0;
            if (veCanicaCen) {
                ultimoLobito = "LOBITO_CENTRO";
                ciclosPersistenciaLobito = Math.max(ciclosPersistenciaLobito, 48);
            } else if (veCanicaIzq) {
                ultimoLobito = "LOBITO_IZQ";
                ciclosPersistenciaLobito = Math.max(ciclosPersistenciaLobito, 42);
            } else if (veCanicaDer) {
                ultimoLobito = "LOBITO_DER";
                ciclosPersistenciaLobito = Math.max(ciclosPersistenciaLobito, 42);
            }
            EstadoRobot estPausa = (ciclosPersistenciaLobito > 0) ? EstadoRobot.APROXIMACION : EstadoRobot.BUSQUEDA;
            return new ComandoRobot(0.0, 0.0, estPausa);
        }

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
                    // Centrado binocular directo: cuando ambos láseres ven la canica, igualar distancias (dl ≈ dr)
                    diff = (dl - dr) * 1.65;
                } else if (Math.abs(ultimaYEstimadaCanica) > 0.4) {
                    // Guiado por coordenada lateral Y estimada por el vector radar (-Y = derecha, +Y = izquierda)
                    diff = -ultimaYEstimadaCanica * 1.4;
                } else if (dr < 25.0 && dl >= 25.0) {
                    diff = (25.0 - dr) + 3.0;
                } else if (dl < 25.0 && dr >= 25.0) {
                    diff = -((25.0 - dl) + 3.0);
                }
            }
            double steer = Math.max(-0.85, Math.min(0.85, diff * 0.12));

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
            ciclosPersistenciaLobito = 44;
            ciclosAlineacionLobito = 0;
            ultimoLobito = "LOBITO_IZQ";
            if (sensores == null) {
                return new ComandoRobot(2.0, 3.0, EstadoRobot.APROXIMACION);
            }
            double dl = sensores.getLaserLeft();
            // Acomodarse primero en dirección a la canica con giro marcado y poco avance longitudinal
            double vL = (dl < 15.0) ? 0.2 : 0.7;
            double vR = (dl < 15.0) ? 3.0 : 3.2;
            return new ComandoRobot(vL, vR, EstadoRobot.APROXIMACION);
        }

        if (veCanicaDer) {
            pasosGiroComprometido = 0;
            giroEsEvasivo = false;
            ciclosPersistenciaLobito = 44;
            ciclosAlineacionLobito = 0;
            ultimoLobito = "LOBITO_DER";
            if (sensores == null) {
                return new ComandoRobot(3.0, 2.0, EstadoRobot.APROXIMACION);
            }
            double dr = sensores.getLaserRight();
            // Acomodarse primero en dirección a la canica con giro marcado y poco avance longitudinal
            double vL = (dr < 15.0) ? 3.0 : 3.2;
            double vR = (dr < 15.0) ? 0.2 : 0.7;
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

            // Si ambos láseres están balanceados a corta distancia o el vector radar la ubica en [-2.8, +2.8] cm:
            // canica centrada frente a la boca de 7.0 cm -> embocar recto
            boolean yaCentrada = false;
            double steerBinocular = 0.0;
            if (sensores != null) {
                double dl = sensores.getLaserLeft();
                double dr = sensores.getLaserRight();
                if (dl < 22.5 && dr < 22.5 && Math.abs(dl - dr) < 4.2) {
                    yaCentrada = true;
                    steerBinocular = Math.max(-0.45, Math.min(0.45, (dl - dr) * 0.14));
                } else if (sensores.getCantidadSensores() >= 6 && Math.abs(ultimaYEstimadaCanica) <= 2.8) {
                    yaCentrada = true;
                    steerBinocular = Math.max(-0.45, Math.min(0.45, -ultimaYEstimadaCanica * 0.12));
                }
            }
            if (yaCentrada || "LOBITO_CENTRO".equals(ultimoLobito)) {
                double vBase = (sensores == null) ? 2.2 : 2.5;
                return new ComandoRobot(vBase + steerBinocular, vBase - steerBinocular, EstadoRobot.APROXIMACION);
            }
            if ("LOBITO_IZQ".equals(ultimoLobito)) {
                if (sensores != null && ciclosAlineacionLobito <= 12) {
                    return new ComandoRobot(0.9, 3.0, EstadoRobot.APROXIMACION);
                }
                return new ComandoRobot(1.8, 2.8, EstadoRobot.APROXIMACION);
            }
            if ("LOBITO_DER".equals(ultimoLobito)) {
                if (sensores != null && ciclosAlineacionLobito <= 12) {
                    return new ComandoRobot(3.0, 0.9, EstadoRobot.APROXIMACION);
                }
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
            boolean jambaCercana = bIzq || bCen || bDer || (sensores != null && (
                    sensores.getUsLeft() < 25.0 || sensores.getUsRight() < 25.0 || sensores.getUsCenter() < 28.0
                    || sensores.getLaserLeft() < 15.0 || sensores.getLaserRight() < 15.0));
            if (sCen || (sIzq && sDer) || jambaCercana) {
                int pasos = (sensores == null) ? 26 : (28 + ((contadorCiclos * 7) % 11));
                return iniciarRetrocesoArcoYPivote(dir, pasos, EstadoRobot.BUSQUEDA);
            } else if (sIzq) {
                // Salida lateral a la izquierda con espacio amplio: curvar a la derecha
                return new ComandoRobot(3.8, 2.2, EstadoRobot.BUSQUEDA);
            } else {
                // Salida lateral a la derecha con espacio amplio: curvar a la izquierda
                return new ComandoRobot(2.2, 3.8, EstadoRobot.BUSQUEDA);
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

        // Escudo lateral preventivo: cuando los láseres abren hacia los flancos (+15° a +40°)
        // y detectan un obstáculo cercano a los costados, desviar suavemente para no rozar con las ruedas
        if (sensores != null && sensores.getCantidadSensores() >= 6) {
            if (ciclosAlertaFlancoIzq > 0 && ciclosAlertaFlancoDer == 0) {
                return new ComandoRobot(3.5, 1.7, EstadoRobot.BUSQUEDA);
            }
            if (ciclosAlertaFlancoDer > 0 && ciclosAlertaFlancoIzq == 0) {
                return new ComandoRobot(1.7, 3.5, EstadoRobot.BUSQUEDA);
            }
        }

        // Stop-and-Scan en exploración: si algún láser detecta una caída de distancia (< 27 cm)
        // con fondo ultrasónico despejado (> 30 cm), o cada 95 ciclos en zona abierta, detenerse
        // durante 4 tics para realizar un barrido láser estacionario de alta precisión
        if (sensores != null && sensores.getCantidadSensores() >= 6
                && sensores.getUsCenter() > 30.0
                && sensores.getUsLeft() > 26.0
                && sensores.getUsRight() > 26.0) {
            boolean anomaliaLaser = (sensores.getLaserLeft() < 27.0 && sensores.getLaserLeft() >= 4.0)
                    || (sensores.getLaserRight() < 27.0 && sensores.getLaserRight() >= 4.0);
            if ((anomaliaLaser && ciclosSinPausaEscaneo >= 35) || ciclosSinPausaEscaneo >= 95) {
                pasosPausaEscaneo = 4;
                ciclosSinPausaEscaneo = 0;
                return new ComandoRobot(0.0, 0.0, EstadoRobot.BUSQUEDA);
            }
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
        pasosGiroAcumulados = 0;
        direccionGiroComprometido = direccion;
        ultimoSentidoGiroEvasivo = direccion;
        ciclosDesdeUltimoGiroEvasivo = 0;
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
            // Mantener el mismo sentido de desatasco en esquina en lugar de oscilar izquierda-derecha
            int dirEscape = (ultimoSentidoGiroEvasivo != 0) ? ultimoSentidoGiroEvasivo : ultimoSentidoAntiBucle;
            return activarManiobraAntiBucle(dirEscape, estadoComando);
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
        pasosPausaEscaneo = 0;
        ultimoLobito = null;
        ultimaYEstimadaCanica = 0.0;
    }

    // Notificación de impacto físico o contacto: si no está ya retrocediendo, retroceder recto para despegarse del obstáculo
    public synchronized void notificarColision() {
        notificarColision("unknown");
    }

    public synchronized void notificarColision(String lado) {
        pasosPausaEscaneo = 0;
        ciclosPersistenciaLobito = 0;
        ultimoLobito = null;

        // Si el impacto es trasero (rear_left / rear_right) y el frente está libre, avanzar en arco
        // para despegar inmediatamente la cola del muro u obstáculo en vez de barrerla girando en el lugar
        boolean frenteDespejado = (ultimoSensores != null)
                && ultimoSensores.getUsCenter() > 16.0
                && ultimoSensores.getLaserLeft() > 10.0
                && ultimoSensores.getLaserRight() > 10.0;

        if ("rear_left".equals(lado) && frenteDespejado) {
            iniciarAvanceArco(3.6, 2.0, 8);
            return;
        }
        if ("rear_right".equals(lado) && frenteDespejado) {
            iniciarAvanceArco(2.0, 3.6, 8);
            return;
        }

        int dir;
        if ("rear_left".equals(lado) || "front_left".equals(lado)) {
            dir = 1;  // Obstáculo a la izquierda: girar a la derecha (+1)
        } else if ("rear_right".equals(lado) || "front_right".equals(lado)) {
            dir = -1; // Obstáculo a la derecha: girar a la izquierda (-1)
        } else {
            dir = (ultimoSensores != null) ? calcularDireccionEvasiva(ultimoSensores, 1)
                                           : ((ultimoLadoBloqueado <= 0) ? 1 : -1);
        }

        // Si el impacto es frontal en simulación real, desenganchar primero con micro-retroceso
        if (ultimoSensores != null && ("front_left".equals(lado) || "front_right".equals(lado))) {
            iniciarRetrocesoArco(-2.8, -2.8, 5);
        } else {
            pasosRetrocesoRestantes = 0;
        }
        double velG = (ultimoSensores == null) ? 2.4 : 3.4;
        double sesgoG = (ultimoSensores == null) ? 1.8 : 0.0;
        iniciarGiroComprometido(dir, (ultimoSensores == null) ? 16 : 22, velG, sesgoG);
        giroEsEvasivo = true;
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
        int pasosGiro = (ultimoSensores == null) ? PASOS_GIRO_ANTIBUCLE : 52;
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
    // Si acaba de realizar un giro evasivo reciente (< 48 ciclos), mantiene el mismo sentido
    // para salir limpiamente de esquinas de 90° sin oscilar entre las dos paredes.
    private int calcularDireccionEvasiva(VectorSensores sensores, int fallback) {
        if (sensores != null) {
            if (ciclosDesdeUltimoGiroEvasivo < 48 && ultimoSentidoGiroEvasivo != 0) {
                return ultimoSentidoGiroEvasivo;
            }
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

    // Convierte un ángulo de servo [-40°, +40°] al índice discreto de tic [0 .. 16].
    private int anguloATicIndex(double anguloDeg) {
        int idx = (int) Math.round((anguloDeg - ANGULO_MIN_RADAR) / PASO_TIC_RADAR);
        return Math.max(0, Math.min(TAM_VECTOR_RADAR - 1, idx));
    }

    // Registra la lectura actual de ambos láseres en el vector de tics del radar,
    // evalúa alertas de flanco lateral (+15° a +40°) y estima la posición lateral Y de la canica.
    private void actualizarVectorRadar(VectorSensores sensores, boolean lIzq, boolean lCen, boolean lDer) {
        double theta = sensores.getAnguloServo();
        int idx = anguloATicIndex(theta);
        double dl = sensores.getLaserLeft();
        double dr = sensores.getLaserRight();

        vectorRadarIzq[idx] = dl;
        vectorRadarDer[idx] = dr;
        cicloLecturaRadar[idx] = contadorCiclos;

        boolean candL = (lIzq || (lCen && dl <= dr + 2.5))
                && dl >= 3.5 && dl <= 23.0
                && sensores.getUsLeft() >= dl + 10.0;
        boolean candR = (lDer || (lCen && dr <= dl + 2.5))
                && dr >= 3.5 && dr <= 23.0
                && sensores.getUsRight() >= dr + 10.0;

        vectorCandidatoIzq[idx] = candL;
        vectorCandidatoDer[idx] = candR;

        // Detección de obstáculos en los flancos laterales cuando el servo abre hacia afuera (+15° a +40°)
        if (theta >= 15.0) {
            if (dl < 14.5 && !candL) {
                ciclosAlertaFlancoIzq = 6;
            }
            if (dr < 14.5 && !candR) {
                ciclosAlertaFlancoDer = 6;
            }
        }

        // Proyección trigonométrica de la coordenada lateral Y de la canica respecto al eje central (boca en [-3.5, +3.5] cm)
        double thetaRad = Math.toRadians(theta);
        if (candL && candR && Math.abs(dl - dr) <= 4.5) {
            ultimaYEstimadaCanica = (dr - dl) * 0.65;
        } else if (candL) {
            ultimaYEstimadaCanica = 8.3 + (2.0 + dl) * Math.sin(thetaRad);
        } else if (candR) {
            ultimaYEstimadaCanica = -8.3 - (2.0 + dr) * Math.sin(thetaRad);
        }
    }

    // Verifica que el objeto detectado como posible canica tenga un ancho angular angosto en tics
    // (1 a MAX_TICS_ANCHO_CANICA tics). Si abarca demasiados tics continuos, es una pared oblicua u obstáculo ancho.
    private boolean esAnchoTicsCanicaValido(boolean esIzquierda, int idxActual) {
        double[] vecDist = esIzquierda ? vectorRadarIzq : vectorRadarDer;
        int anchoTics = 1;

        for (int i = idxActual - 1; i >= 0; i--) {
            if ((contadorCiclos - cicloLecturaRadar[i]) <= 22 && vecDist[i] >= 3.5 && vecDist[i] <= 23.0) {
                anchoTics++;
            } else {
                break;
            }
        }
        for (int i = idxActual + 1; i < TAM_VECTOR_RADAR; i++) {
            if ((contadorCiclos - cicloLecturaRadar[i]) <= 22 && vecDist[i] >= 3.5 && vecDist[i] <= 23.0) {
                anchoTics++;
            } else {
                break;
            }
        }
        return anchoTics <= MAX_TICS_ANCHO_CANICA;
    }

    // Activa la transicion formal hacia el modo escape.
    public synchronized void activarModoEscape() {
        this.estado = EstadoRobot.ESCAPE;
        this.ladoSeguimientoEscape = 0;
        this.pasosRetrocesoRestantes = 0;
        this.pasosGiroComprometido = 0;
        this.pasosPausaEscaneo = 0;
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
        this.pasosPausaEscaneo = 0;
        this.giroAlineacionSalida = false;
    }
}
