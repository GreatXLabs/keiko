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
            int fallbackDir = (estado == EstadoRobot.ESCAPE) ? 1 : (ultimoLadoBloqueado <= 0 ? 1 : -1);
            int dir = calcularDireccionEvasiva(sensores, fallbackDir);
            int pasosGiro = bucle ? 28 : 22;

            int pasosRetroceso = bucle ? PASOS_RETRO_ARCO_ANTIBUCLE : PASOS_RETRO_ARCO_TOTAL;
            iniciarRetrocesoArco(VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_RAPIDA, pasosRetroceso);
            double sesgo = (sensores == null) ? (bucle ? SESGO_GIRO_ANTIBUCLE : SESGO_GIRO_NORMAL) : 0.0;
            iniciarGiroComprometido(dir, pasosGiro, VEL_GIRO_AMPLIO, sesgo);
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
                return iniciarRetrocesoArcoYPivote(1, 16, EstadoRobot.ESCAPE);
            }
            if (bCen && bDer) {
                registrarBloqueoLateral(1);
                if (debeActivarAntiBucle()) {
                    return activarManiobraAntiBucle(-1, EstadoRobot.ESCAPE);
                }
                return iniciarRetrocesoArcoYPivote(-1, 16, EstadoRobot.ESCAPE);
            }
            if (bCen) {
                int dir = calcularDireccionEvasiva(sensores, 1);
                return iniciarRetrocesoArcoYPivote(dir, 16, EstadoRobot.ESCAPE);
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
        // Prioridad 5a: Deteccion y aproximacion de presas (LOBITO) con embocado fino a boca de 7.0 cm
        boolean lIzq = "LOBITO".equals(izq);
        boolean lCen = "LOBITO".equals(cen);
        boolean lDer = "LOBITO".equals(der);

        if (lCen && !bCen) {
            pasosGiroComprometido = 0;
            ultimoLobito = "LOBITO_CENTRO";
            ciclosPersistenciaLobito = 10;
            // Desaceleración suave al 40-50% en los últimos 10-15 cm para embocar sin rebote
            boolean enZonaBucal = sensores != null && (sensores.getLaserLeft() <= 18.0 || sensores.getLaserRight() <= 18.0);
            double vApproach = enZonaBucal ? 2.4 : 3.4;

            double diff = 0.0;
            if (sensores != null) {
                double dl = sensores.getLaserLeft();
                double dr = sensores.getLaserRight();
                if (dl < 32.0 && dr < 32.0) {
                    diff = (dl - dr);
                } else if (dr < 20.0 && dl >= 20.0) {
                    diff = (20.0 - dr);
                } else if (dl < 20.0 && dr >= 20.0) {
                    diff = -(20.0 - dl);
                }
            }
            double steer = Math.max(-0.6, Math.min(0.6, diff * 0.10));

            if (bIzq && !bDer) {
                return new ComandoRobot(vApproach + 0.3, vApproach - 0.3, EstadoRobot.APROXIMACION);
            } else if (bDer && !bIzq) {
                return new ComandoRobot(vApproach - 0.3, vApproach + 0.3, EstadoRobot.APROXIMACION);
            } else {
                return new ComandoRobot(vApproach + steer, vApproach - steer, EstadoRobot.APROXIMACION);
            }
        }

        if (lIzq && !bIzq && !bCen) {
            pasosGiroComprometido = 0;
            ultimoLobito = "LOBITO_IZQ";
            ciclosPersistenciaLobito = 10;
            return new ComandoRobot(1.2, 2.8, EstadoRobot.APROXIMACION);
        }

        if (lDer && !bDer && !bCen) {
            pasosGiroComprometido = 0;
            ultimoLobito = "LOBITO_DER";
            ciclosPersistenciaLobito = 10;
            return new ComandoRobot(2.8, 1.2, EstadoRobot.APROXIMACION);
        }

        // Persistencia de aproximacion durante barrido de servos (para no perder el objetivo mientras el servo barre)
        if (ciclosPersistenciaLobito > 0 && !bCen && !bIzq && !bDer) {
            ciclosPersistenciaLobito--;
            if ("LOBITO_CENTRO".equals(ultimoLobito)) {
                return new ComandoRobot(2.4, 2.4, EstadoRobot.APROXIMACION);
            } else if ("LOBITO_IZQ".equals(ultimoLobito)) {
                return new ComandoRobot(1.4, 2.6, EstadoRobot.APROXIMACION);
            } else if ("LOBITO_DER".equals(ultimoLobito)) {
                return new ComandoRobot(2.6, 1.4, EstadoRobot.APROXIMACION);
            }
        } else if (bCen || bIzq || bDer) {
            ciclosPersistenciaLobito = 0;
            ultimoLobito = null;
        }

        // Prioridad 5b: Evitar salir prematuramente por el estrecho de salida (30 cm) antes de Panza Llena
        // Solo aplica si el frente no está bloqueado físicamente y el giro evasivo tiene espacio libre.
        boolean sCen = "SALIDA".equals(cen);
        boolean sIzq = "SALIDA".equals(izq);
        boolean sDer = "SALIDA".equals(der);

        if (!bCen) {
            if (sCen || (sIzq && sDer)) {
                int dir = bIzq ? -1 : (bDer ? 1 : ((ultimoLadoBloqueado <= 0) ? 1 : -1));
                iniciarGiroComprometido(dir, 12);
                return new ComandoRobot(VEL_GIRO_COMPROMETIDO * dir, -VEL_GIRO_COMPROMETIDO * dir, EstadoRobot.BUSQUEDA);
            }
            if (sIzq && !bDer) {
                iniciarGiroComprometido(1, 8);
                return new ComandoRobot(VEL_GIRO_COMPROMETIDO, -VEL_GIRO_COMPROMETIDO, EstadoRobot.BUSQUEDA);
            }
            if (sDer && !bIzq) {
                iniciarGiroComprometido(-1, 8);
                return new ComandoRobot(-VEL_GIRO_COMPROMETIDO, VEL_GIRO_COMPROMETIDO, EstadoRobot.BUSQUEDA);
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
            int pasos = 22;
            return iniciarRetrocesoArcoYPivote(dir, pasos, EstadoRobot.BUSQUEDA);
        }

        if (obsCen && obsDer) {
            registrarBloqueoLateral(1);
            if (ciclosMismoLadoBloqueado >= 40 || debeActivarAntiBucle()) {
                return activarManiobraAntiBucle(-1, EstadoRobot.BUSQUEDA);
            }
            int dir = calcularDireccionEvasiva(sensores, -1);
            int pasos = 22;
            return iniciarRetrocesoArcoYPivote(dir, pasos, EstadoRobot.BUSQUEDA);
        }

        if (obsCen) {
            int dir = calcularDireccionEvasiva(sensores, (ultimoLadoBloqueado <= 0) ? 1 : -1);
            int pasos = 22;
            return iniciarRetrocesoArcoYPivote(dir, pasos, EstadoRobot.BUSQUEDA);
        }

        if (obsIzq) {
            registrarBloqueoLateral(-1);
            if (ciclosMismoLadoBloqueado >= 25 || debeActivarAntiBucle()) {
                return activarManiobraAntiBucle(1, EstadoRobot.BUSQUEDA);
            }
            return new ComandoRobot(VEL_AVANCE_ARCO, VEL_ARCO_LENTA, EstadoRobot.BUSQUEDA);
        }

        if (obsDer) {
            registrarBloqueoLateral(1);
            if (ciclosMismoLadoBloqueado >= 25 || debeActivarAntiBucle()) {
                return activarManiobraAntiBucle(-1, EstadoRobot.BUSQUEDA);
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
        if (sensores != null && sensores.getCantidadSensores() >= 6) {
            double dl = sensores.getLaserLeft();
            double dr = sensores.getLaserRight();
            double usl = sensores.getUsLeft();
            double usr = sensores.getUsRight();
            double clearL = Math.min(usl, dl);
            double clearR = Math.min(usr, dr);
            if (clearL < 28.0 && clearR >= 28.0) {
                sweep = 0.55; // Curvar hacia la derecha (alejarse de la izquierda)
            } else if (clearR < 28.0 && clearL >= 28.0) {
                sweep = -0.55; // Curvar hacia la izquierda (alejarse de la derecha)
            }
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

    // Retroceso recto normal (5 ciclos) seguido de pivote asimetrico, para despejar trompa y cola.
    private ComandoRobot iniciarRetrocesoArcoYPivote(int direccionGiro, int pasosGiro, EstadoRobot estadoComando) {
        if (ciclosDesdeUltimoReverso < 70) {
            contadorReversosConsecutivos++;
        }
        ciclosDesdeUltimoReverso = 0;
        if (contadorReversosConsecutivos >= 4) {
            contadorReversosConsecutivos = 0;
            ultimoSentidoAntiBucle = -ultimoSentidoAntiBucle;
            return activarManiobraAntiBucle(ultimoSentidoAntiBucle, estadoComando);
        }
        double sesgo = (ultimoSensores == null) ? SESGO_GIRO_NORMAL : 0.0;
        int pasosRetro = (ultimoSensores != null) ? 5 : PASOS_RETRO_ARCO_NORMAL;
        iniciarRetrocesoArco(VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_RAPIDA, pasosRetro);
        iniciarGiroComprometido(direccionGiro, pasosGiro, VEL_GIRO_COMPROMETIDO, sesgo);
        return new ComandoRobot(VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_RAPIDA, estadoComando);
    }

    // Notificación explícita de ingesta de presa: retroceder para despejar el entorno y buscar la siguiente.
    public synchronized void notificarCaptura() {
        int dir;
        if (ultimoSensores != null) {
            dir = calcularDireccionEvasiva(ultimoSensores, 1);
        } else {
            dir = (ultimoLadoBloqueado <= 0) ? 1 : -1;
        }
        iniciarRetrocesoArco(VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_RAPIDA, 6);
        iniciarGiroComprometido(dir, 24, VEL_GIRO_AMPLIO, 0.0);
        ciclosPersistenciaLobito = 0;
        ultimoLobito = null;
    }

    // Notificación de impacto físico o contacto: retroceso y giro evasivo inmediato para no atascarse.
    public synchronized void notificarColision() {
        int dir;
        if (ultimoSensores != null) {
            dir = calcularDireccionEvasiva(ultimoSensores, 1);
        } else {
            dir = (ultimoLadoBloqueado <= 0) ? -1 : 1;
        }

        if (pasosRetrocesoRestantes > 0 || velRetrocesoIzq < 0) {
            iniciarRetrocesoArco(4.8, 4.8, 6);
            iniciarGiroComprometido(dir, 12, VEL_GIRO_COMPROMETIDO, 0.0);
        } else {
            iniciarRetrocesoArco(VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_RAPIDA, 5);
            iniciarGiroComprometido(dir, 14, VEL_GIRO_COMPROMETIDO, 0.0);
        }
        ciclosPersistenciaLobito = 0;
        ultimoLobito = null;
    }

    // Maniobra anti-bucle en dos fases: retroceso recto y giro amplio asimetrico.
    private ComandoRobot activarManiobraAntiBucle(int direccion, EstadoRobot estadoComando) {
        contadorOscilaciones = 0;
        ciclosMismoLadoBloqueado = 0;
        reiniciarHistorialLados();
        double sesgo = (ultimoSensores == null) ? SESGO_GIRO_ANTIBUCLE : 0.0;
        int pasos = (ultimoSensores == null) ? PASOS_GIRO_ANTIBUCLE : 24;
        int pasosRetro = (ultimoSensores == null) ? PASOS_RETRO_ARCO_ANTIBUCLE : 6;
        iniciarRetrocesoArco(VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_RAPIDA, pasosRetro);
        iniciarGiroComprometido(direccion, pasos, VEL_GIRO_AMPLIO, sesgo);
        return new ComandoRobot(VEL_RETRO_ARCO_RAPIDA, VEL_RETRO_ARCO_RAPIDA, estadoComando);
    }

    // Calcula la direccion de giro evasivo priorizando el lado con mayor despeje fisico.
    private int calcularDireccionEvasiva(VectorSensores sensores, int fallback) {
        if (sensores != null) {
            double clearanceL = sensores.getUsLeft();
            double clearanceR = sensores.getUsRight();
            if (sensores.getCantidadSensores() >= 6) {
                clearanceL = Math.min(clearanceL, sensores.getLaserLeft());
                clearanceR = Math.min(clearanceR, sensores.getLaserRight());
            }
            if (clearanceL < 22.0 && clearanceR >= 22.0) {
                return 1; // Obstaculo a la izquierda: girar hacia la derecha
            }
            if (clearanceR < 22.0 && clearanceL >= 22.0) {
                return -1; // Obstaculo a la derecha: girar hacia la izquierda
            }
            if (Math.abs(clearanceL - clearanceR) > 3.0) {
                return (clearanceL > clearanceR) ? -1 : 1; // Girar hacia el sector con mayor despeje libre
            }
        }
        return fallback;
    }

    // Activa la transicion formal hacia el modo escape.
    public synchronized void activarModoEscape() {
        this.estado = EstadoRobot.ESCAPE;
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
        this.pasosRetrocesoRestantes = 0;
        this.pasosGiroComprometido = 0;
        this.giroAlineacionSalida = false;
    }
}
