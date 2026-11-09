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
    private static final int MAX_TICS_ANCHO_CANICA = 5;
    private static final int TAM_VENTANA_US = 20;
    private final double[] vectorRadarIzq = new double[TAM_VECTOR_RADAR];
    private final double[] vectorRadarDer = new double[TAM_VECTOR_RADAR];
    private final boolean[] vectorCandidatoIzq = new boolean[TAM_VECTOR_RADAR];
    private final boolean[] vectorCandidatoDer = new boolean[TAM_VECTOR_RADAR];
    private final int[] cicloLecturaRadar = new int[TAM_VECTOR_RADAR];

    // Ventana deslizante de lecturas ultrasónicas recientes para detectar la sombra de los cubos 6x6x6 cm
    private final double[] ventanaUsIzq = new double[TAM_VENTANA_US];
    private final double[] ventanaUsCen = new double[TAM_VENTANA_US];
    private final double[] ventanaUsDer = new double[TAM_VENTANA_US];
    private int idxVentanaUs = 0;

    // Estado para estrategia Stop-and-Scan (pausa breve de escaneo estacionario) y escudo lateral
    private int pasosPausaEscaneo = 0;
    private int ciclosSinPausaEscaneo = 0;
    private int ultimoCicloPausaCandidato = -100;
    private int ciclosAlertaFlancoIzq = 0;
    private int ciclosAlertaFlancoDer = 0;
    private double ultimaYEstimadaCanica = 0.0;
    private int pasosAlineacionMeta = 6;
    private int ciclosInhibicionCanica = 0;
    private int ultimoSentidoGiroEvasivo = 0;
    private int ciclosDesdeUltimoGiroEvasivo = 200;
    private int pasosGiroAcumulados = 0;
    private int ciclosPersistenciaSalida = 0;
    private int ultimaDireccionSalida = 0;
    private int ciclosSinVerSalidaEscape = 0;
    private int pasosEscaneoSalidaEscape = 0;
    private double metaSalidaX = 0.0;
    private double metaSalidaY = 0.60;
    private boolean tieneMetaSalida = false;
    private int pasosEscudoPerimetral = 0;
    private int direccionEscudoPerimetral = 1;

    public synchronized void establecerSalidaMeta(double x, double y) {
        this.metaSalidaX = x;
        this.metaSalidaY = y;
        this.tieneMetaSalida = true;
    }

    // Inicializa el planificador evaluando si el estomago ya posee capturas suficientes para escape.
    public PlanificadorNavegacion(GestorEstomago gestorEstomago) {
        this.gestorEstomago = gestorEstomago;
        for (int i = 0; i < TAM_VECTOR_RADAR; i++) {
            vectorRadarIzq[i] = 250.0;
            vectorRadarDer[i] = 250.0;
            cicloLecturaRadar[i] = -1000;
        }
        for (int i = 0; i < TAM_VENTANA_US; i++) {
            ventanaUsIzq[i] = 250.0;
            ventanaUsCen[i] = 250.0;
            ventanaUsDer[i] = 250.0;
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
        if (ciclosInhibicionCanica > 0) ciclosInhibicionCanica--;
        if (ciclosPersistenciaSalida > 0) ciclosPersistenciaSalida--;
        if (ciclosDesdeUltimoReverso > 100) {
            contadorReversosConsecutivos = 0;
        }

        String izq = pares[0] != null ? pares[0] : "ESPACIO_LIBRE";
        String cen = pares[1] != null ? pares[1] : "ESPACIO_LIBRE";
        String der = pares[2] != null ? pares[2] : "ESPACIO_LIBRE";

        if (estado == EstadoRobot.ESCAPE || ciclosInhibicionCanica > 0) {
            if ("LOBITO".equals(izq)) izq = "ESPACIO_LIBRE";
            if ("LOBITO".equals(cen)) cen = "ESPACIO_LIBRE";
            if ("LOBITO".equals(der)) der = "ESPACIO_LIBRE";
        }

        // Actualizar vector radar por tics, ventana ultrasónica y validar candidatos a canica vs cubo 6x6x6 cm
        if (sensores != null && sensores.getCantidadSensores() >= 6 && Math.abs(sensores.getAnguloServo()) <= 50.0) {
            registrarVentanaUltrasonica(sensores);
            actualizarVectorRadar(sensores, "LOBITO".equals(izq), "LOBITO".equals(cen), "LOBITO".equals(der));
            int idxActual = anguloATicIndex(sensores.getAnguloServo());
            double dl = sensores.getLaserLeft();
            double dr = sensores.getLaserRight();
            boolean sombraIzq = esSombraObstaculoUltrasonico(true, dl, sensores) || esLaserEnPared(true, dl, sensores);
            boolean sombraDer = esSombraObstaculoUltrasonico(false, dr, sensores) || esLaserEnPared(false, dr, sensores);

            if ("LOBITO".equals(izq)) {
                if (!esAnchoTicsCanicaValido(true, idxActual, dl) || sombraIzq) {
                    izq = (sensores.getUsLeft() < 22.0 || (sombraIzq && dl < 11.5)) ? "BLOQUEO" : "ESPACIO_LIBRE";
                }
            }
            if ("LOBITO".equals(der)) {
                if (!esAnchoTicsCanicaValido(false, idxActual, dr) || sombraDer) {
                    der = (sensores.getUsRight() < 22.0 || (sombraDer && dr < 11.5)) ? "BLOQUEO" : "ESPACIO_LIBRE";
                }
            }
            if ("LOBITO".equals(cen)) {
                double dMinLaser = Math.min(dl, dr);
                boolean ladoIzqMasCerca = dl <= dr;
                boolean sombraCen = ladoIzqMasCerca ? sombraIzq : sombraDer;
                if (!esAnchoTicsCanicaValido(ladoIzqMasCerca, idxActual, dMinLaser) || sombraCen) {
                    cen = (sensores.getUsCenter() < 22.0 || (sombraCen && dMinLaser < 10.5)) ? "BLOQUEO" : "ESPACIO_LIBRE";
                }
            }
            // Reflejo anticolisión de canica: SOLO cuando el fondo ultrasónico frontal está totalmente despejado (> 36 cm en los 3 US)
            // garantizando que no hay ningún cubo 3D ni pared en la trayectoria frontal.
            if (estado != EstadoRobot.ESCAPE && ciclosInhibicionCanica == 0 && Math.abs(sensores.getAnguloServo()) <= 15.0
                    && !"LOBITO".equals(izq) && !"LOBITO".equals(cen) && !"LOBITO".equals(der)) {
                boolean fondoUsLimpio = sensores.getUsLeft() > 36.0 && sensores.getUsCenter() > 36.0 && sensores.getUsRight() > 36.0;
                if (fondoUsLimpio && dl <= 18.0 && dr <= 18.0 && Math.abs(dl - dr) <= 3.2 && !sombraIzq && !sombraDer) {
                    cen = "LOBITO";
                }
            }
            // Protección de impacto inminente: los láseres ToF están a Y = ±8.3 cm frente a las ruedas y paragolpes.
            // Si cualquier láser mide < 5.0 cm, hay un obstáculo 3D o muro físico a milímetros del chasis.
            // Es BLOQUEO inmediato e incondicional (no depende de sombra ultrasónica).
            if (dl < 5.0) {
                izq = "BLOQUEO";
            }
            if (dr < 5.0) {
                der = "BLOQUEO";
            }
        }

        boolean bIzq = "BLOQUEO".equals(izq);
        boolean bCen = "BLOQUEO".equals(cen);
        boolean bDer = "BLOQUEO".equals(der);

        if (estado == EstadoRobot.FINALIZACION || estado == EstadoRobot.ERROR_SEGURO) {
            return new ComandoRobot(0.0, 0.0, estado);
        }

        // Si en modo ESCAPE cualquiera de los 3 sensores ultrasónicos detecta el hueco de SALIDA (> 160 cm),
        // cancelar inmediatamente cualquier giro comprometido o avance post-captura para enfilar la salida sin demora.
        boolean veSalidaDirecta = (estado == EstadoRobot.ESCAPE) && (
                "SALIDA".equals(izq) || "SALIDA".equals(cen) || "SALIDA".equals(der)
                || (sensores != null && sensores.getCantidadSensores() >= 6
                    && (sensores.getUsLeft() > 160.0 || sensores.getUsCenter() > 160.0 || sensores.getUsRight() > 160.0))
        );
        if (veSalidaDirecta) {
            pasosGiroComprometido = 0;
            giroEsEvasivo = false;
            pasosEscaneoSalidaEscape = 0;
            ciclosSinVerSalidaEscape = 0;
            if (sensores != null && sensores.getUsCenter() >= 14.0 && velRetrocesoIzq > 0 && velRetrocesoDer > 0) {
                pasosRetrocesoRestantes = 0;
            }
        }

        // ---------------------------------------------------------------------
        // 1. EJECUCION DE MANIOBRA COMPROMETIDA ACTIVA (MARCHA ATRAS O GIRO)
        // El retroceso puede ser en arco asimetrico para que la cola siga la estela
        // libre en lugar de barrer lateralmente contra un obstaculo.
        // ---------------------------------------------------------------------
        if (pasosRetrocesoRestantes > 0) {
            pasosRetrocesoRestantes--;
            EstadoRobot est = (estado == EstadoRobot.ESCAPE) ? EstadoRobot.ESCAPE : EstadoRobot.BUSQUEDA;
            if (sensores != null && sensores.hasPose() && sensores.getPosY() < -0.42
                    && Math.sin(sensores.getTheta()) > 0.0
                    && (velRetrocesoIzq <= 0 || velRetrocesoDer <= 0)) {
                pasosRetrocesoRestantes = 0; // Solo cancelar si mira al norte (el retroceso acercaría la cola al muro sur)
            } else if (velRetrocesoIzq > 0 && velRetrocesoDer > 0) {
                // Maniobra de avance (para despegar cola de colisión trasera o post-captura)
                if (bCen && pasosRetrocesoRestantes <= 4 && (sensores == null || sensores.getUsCenter() < 8.0)) {
                    pasosRetrocesoRestantes = 0;
                } else {
                    return new ComandoRobot(velRetrocesoIzq, velRetrocesoDer, est);
                }
            } else {
                return new ComandoRobot(velRetrocesoIzq, velRetrocesoDer, est);
            }
        }

        // Si detecta una canica real en BUSQUEDA (clasificada por Red 2), fijar el blanco inmediatamente
        boolean veCanicaL = "LOBITO".equals(izq) && (sensores == null || sensores.getUsLeft() >= 10.0);
        boolean veCanicaC = "LOBITO".equals(cen) && (sensores == null || sensores.getUsCenter() >= 10.0);
        boolean veCanicaR = "LOBITO".equals(der) && (sensores == null || sensores.getUsRight() >= 10.0);
        boolean veCanica = veCanicaL || veCanicaC || veCanicaR;
        if (veCanica && estado == EstadoRobot.BUSQUEDA) {
            pasosGiroComprometido = 0;
            giroEsEvasivo = false;
            if (sensores == null || sensores.getUsCenter() >= 8.0) {
                pasosRetrocesoRestantes = 0;
            }
        }

        boolean cazandoCanica = (veCanica || ciclosPersistenciaLobito > 0);

        // Escudo preventivo perimetral incondicional: si el robot se aproxima a los muros perimetrales,
        // virar inmediatamente hacia el centro de caza (0.0, -0.04)
        ComandoRobot cmdEscudoP = verificarEscudoPerimetral(sensores, estado, cazandoCanica);
        if (cmdEscudoP != null) {
            pasosGiroComprometido = 0;
            giroEsEvasivo = false;
            ciclosPersistenciaLobito = 0;
            ultimoLobito = null;
            return cmdEscudoP;
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
                } else if (estado != EstadoRobot.ESCAPE && pasosGiroComprometido <= 1 && pasosGiroAcumulados < 76
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
            // Si la cola está cerca del muro sur (y = -0.60) y mira hacia el norte con despeje frontal,
            // avanzar suavemente mientras gira para despegar la cola sin barrer contra el muro
            if (sensores != null && sensores.hasPose() && sensores.getPosY() < -0.42
                    && Math.sin(sensores.getTheta()) > 0.0 && sensores.getUsCenter() > 14.0) {
                if (direccionGiroComprometido >= 0) {
                    return new ComandoRobot(2.8, 0.4, est);
                }
                return new ComandoRobot(0.4, 2.8, est);
            }
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
        // En APROXIMACION de canica, no abortar si aún hay >= 9 cm de despeje frontal al muro.
        boolean salidaFrontalEnEscape = estado == EstadoRobot.ESCAPE && "SALIDA".equals(cen);
        boolean enAproximacionValida = (veCanica || ciclosPersistenciaLobito > 0) && (sensores == null || sensores.getUsCenter() >= 9.0);
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
            if (sensores != null && sensores.hasPose()) {
                boolean colaCercaMuro = (sensores.getPosY() < -0.40 && Math.sin(sensores.getTheta()) > -0.20)
                        || (sensores.getPosY() > 0.20 && Math.sin(sensores.getTheta()) < 0.20)
                        || (sensores.getPosX() < -0.62 && Math.cos(sensores.getTheta()) > -0.20)
                        || (sensores.getPosX() > 0.62 && Math.cos(sensores.getTheta()) < 0.20);
                if (colaCercaMuro) {
                    pasosRetroceso = 0;
                }
            }
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
            if (sensores != null && sensores.hasPose()) {
                double px = sensores.getPosX();
                double py = sensores.getPosY();
                double th = sensores.getTheta();

                // Si ya cruzó la salida (y > 0.60 m), crucero a fondo en el espacio exterior
                if (py > 0.60) {
                    return new ComandoRobot(4.8, 4.8, EstadoRobot.ESCAPE);
                }

                double dl = Math.min(sensores.getUsLeft(), sensores.getLaserLeft());
                double dr = Math.min(sensores.getUsRight(), sensores.getLaserRight());

                // FASE 2: Pasillo final de salida (centrado en el corredor central)
                // Hueco de 30 cm en [-0.15, +0.15]. El chasis de 15 cm cruza centrado en x ≈ 0.0 con 7.5 cm de luz.
                boolean enCorredorCentral = (Math.abs(px) < 0.05 && py >= 0.28);
                if (enCorredorCentral) {
                    double diffNorth = 1.5708 - th;
                    while (diffNorth > Math.PI) diffNorth -= 2 * Math.PI;
                    while (diffNorth < -Math.PI) diffNorth += 2 * Math.PI;

                    // Si la orientación difiere de Norte, alinear en el lugar antes de cruzar
                    if (Math.abs(diffNorth) > 0.25) {
                        return (diffNorth > 0) ? new ComandoRobot(-2.4, 2.4, EstadoRobot.ESCAPE)
                                               : new ComandoRobot(2.4, -2.4, EstadoRobot.ESCAPE);
                    }

                    // Centrado lateral fino en x = 0.0 hacia el espacio exterior
                    double steer = diffNorth * 1.5 + px * 3.5;

                    // Micro-asistencia ante proximidad a jambas
                    if (dr < 6.0 && dl < 6.0) {
                        steer = (dr < dl) ? 0.30 : -0.30;
                    } else if (dr < 6.0) {
                        steer = 0.30;  // Separar de jamba derecha (girar izquierda)
                    } else if (dl < 6.0) {
                        steer = -0.30; // Separar de jamba izquierda (girar derecha)
                    }

                    steer = Math.max(-0.45, Math.min(0.45, steer));
                    return new ComandoRobot(4.4 - steer, 4.4 + steer, EstadoRobot.ESCAPE);
                }

                // Si está en la zona alta (py >= 0.28) pero fuera del corredor (|px| >= 0.05):
                // PROHIBIDO avanzar contra los postes del hueco (x = ±0.15, y = 0.57).
                // Recentrarse hacia el punto de entrada (0.0, 0.26) donde no hay ningún obstáculo.
                if (py >= 0.28) {
                    double dx = 0.0 - px;
                    double dy = 0.26 - py;
                    double targetAngle = Math.atan2(dy, dx);
                    double diff = targetAngle - th;
                    while (diff > Math.PI) diff -= 2 * Math.PI;
                    while (diff < -Math.PI) diff += 2 * Math.PI;

                    if (Math.abs(diff) > 0.30) {
                        return (diff > 0) ? new ComandoRobot(-2.4, 2.4, EstadoRobot.ESCAPE)
                                           : new ComandoRobot(2.4, -2.4, EstadoRobot.ESCAPE);
                    }
                    return new ComandoRobot(3.2, 3.2, EstadoRobot.ESCAPE);
                }

                // FASE 1 (py < 0.28): Navegación hacia el norte por los corredores libres de la bahía
                // (Idéntica a la corrida récord de task-4856 que esquivó todos los obstáculos y canicas)
                double angleOffset = Math.max(-0.65, Math.min(0.65, px * 2.8));
                double targetAngle = 1.5708 - angleOffset;
                double diff = targetAngle - th;
                while (diff > Math.PI) diff -= 2 * Math.PI;
                while (diff < -Math.PI) diff += 2 * Math.PI;

                // Evitar obstáculos frontales o laterales en la ruta hacia el punto de centrado
                boolean bloqueoFrente = bCen || (sensores.getUsCenter() < 18.0)
                        || (sensores.getLaserLeft() < 10.0) || (sensores.getLaserRight() < 10.0)
                        || (sensores.getUsLeft() < 13.0) || (sensores.getUsRight() < 13.0);
                if (bloqueoFrente) {
                    // Direccion evasiva inteligente: orientar hacia el corredor libre
                    int dir = (px > 0.08) ? 1 : (px < -0.08 ? -1 : (sensores.getUsRight() < sensores.getUsLeft() ? 1 : -1));
                    if (sensores.getUsLeft() < 10.0 || sensores.getLaserLeft() < 8.0) {
                        dir = -1; // Obstáculo rozando a la izquierda: virar a la derecha (-1: vL > vR)
                    } else if (sensores.getUsRight() < 10.0 || sensores.getLaserRight() < 8.0) {
                        dir = 1;  // Obstáculo rozando a la derecha: virar a la izquierda (+1: vL < vR)
                    }
                    boolean muyPegado = sensores.getUsCenter() < 11.0 || sensores.getUsLeft() < 7.0 || sensores.getUsRight() < 7.0
                            || sensores.getLaserLeft() < 6.0 || sensores.getLaserRight() < 6.0;
                    if (muyPegado) {
                        return (dir > 0) ? new ComandoRobot(-1.8, 2.4, EstadoRobot.ESCAPE)
                                         : new ComandoRobot(2.4, -1.8, EstadoRobot.ESCAPE);
                    }
                    if (dir > 0) {
                        return new ComandoRobot(0.8, 3.8, EstadoRobot.ESCAPE);
                    } else {
                        return new ComandoRobot(3.8, 0.8, EstadoRobot.ESCAPE);
                    }
                }

                if (Math.abs(diff) > 0.25) {
                    if (diff > 0) {
                        return new ComandoRobot(1.6, 4.0, EstadoRobot.ESCAPE);
                    } else {
                        return new ComandoRobot(4.0, 1.6, EstadoRobot.ESCAPE);
                    }
                }
                double steer = Math.max(-0.5, Math.min(0.5, diff * 1.4));
                return new ComandoRobot(4.4 - steer, 4.4 + steer, EstadoRobot.ESCAPE);
            }

            // Fallback reactivo para simulación sin odometría y tests unitarios
            boolean sIzq = "SALIDA".equals(izq) || (sensores != null && sensores.getUsLeft() > 160.0);
            boolean sCen = "SALIDA".equals(cen) || (sensores != null && sensores.getUsCenter() > 160.0);
            boolean sDer = "SALIDA".equals(der) || (sensores != null && sensores.getUsRight() > 160.0);

            if (bIzq && sCen && bDer) {
                return new ComandoRobot(-2.4, -2.4, EstadoRobot.ESCAPE);
            }
            if (sCen && bDer) {
                return new ComandoRobot(1.6, 3.2, EstadoRobot.ESCAPE);
            }
            if (bIzq && sCen) {
                return new ComandoRobot(3.2, 1.6, EstadoRobot.ESCAPE);
            }
            if (sCen) {
                return new ComandoRobot(3.4, 3.4, EstadoRobot.ESCAPE);
            }
            if (sIzq) {
                if (bCen) {
                    return iniciarRetrocesoArcoYPivote(-1, 5, EstadoRobot.ESCAPE);
                }
                return new ComandoRobot(1.6, 3.2, EstadoRobot.ESCAPE);
            }
            if (sDer) {
                if (bCen) {
                    return iniciarRetrocesoArcoYPivote(1, 5, EstadoRobot.ESCAPE);
                }
                return new ComandoRobot(3.2, 1.6, EstadoRobot.ESCAPE);
            }
            return new ComandoRobot(3.6, 4.2, EstadoRobot.ESCAPE);
        }

        // ---------------------------------------------------------------------
        // 5. LOGICA DE BUSQUEDA Y APROXIMACION (CAPTURAS < 3)
        // ---------------------------------------------------------------------

        // Prioridad 5a: Deteccion y aproximacion de presas (LOBITO) con radar por tics,
        // pausa estacionaria Stop-and-Scan y centrado binocular a boca de 7.0 cm
        boolean lIzq = "LOBITO".equals(izq);
        boolean lCen = "LOBITO".equals(cen);
        boolean lDer = "LOBITO".equals(der);

        boolean veCanicaCen = lCen && (sensores == null || sensores.getUsCenter() >= 10.0);
        boolean veCanicaIzq = lIzq && (sensores == null || (sensores.getLaserLeft() >= 2.0 && sensores.getUsLeft() >= 10.0));
        boolean veCanicaDer = lDer && (sensores == null || (sensores.getLaserRight() >= 2.0 && sensores.getUsRight() >= 10.0));

        // Seguridad ultrasónica continua: cancelar cualquier pausa de escaneo si hay riesgo cercano
        boolean riesgoUltrasonico = bIzq || bCen || bDer || (sensores != null && (
                sensores.getUsCenter() < 16.0 || sensores.getUsLeft() < 14.0 || sensores.getUsRight() < 14.0));
        if (riesgoUltrasonico) {
            pasosPausaEscaneo = 0;
        }

        // Stop-and-Scan al detectar un nuevo candidato a distancia media: detenerse brevemente
        // para que los servos barran varios tics sin vibración ni desplazamiento del chasis
        if (!riesgoUltrasonico && sensores != null && sensores.getCantidadSensores() >= 6
                && (veCanicaIzq || veCanicaDer)
                && ciclosPersistenciaLobito == 0
                && (contadorCiclos - ultimoCicloPausaCandidato) >= 26) {
            double dMinCand = Math.min(sensores.getLaserLeft(), sensores.getLaserRight());
            if (dMinCand > 11.0 && dMinCand <= 23.0) {
                pasosPausaEscaneo = 5; // 5 tics adicionales (6 lecturas limpias en reposo, ~0.19 s)
                ultimoCicloPausaCandidato = contadorCiclos;
                ciclosSinPausaEscaneo = 0;
                ciclosPersistenciaLobito = 38;
                ciclosAlineacionLobito = 0;
                pasosAlineacionMeta = calcularPasosAlineacionMeta();
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
                ciclosPersistenciaLobito = Math.max(ciclosPersistenciaLobito, 40);
                pasosAlineacionMeta = 0;
            } else if (veCanicaIzq) {
                ultimoLobito = "LOBITO_IZQ";
                ciclosPersistenciaLobito = Math.max(ciclosPersistenciaLobito, 38);
                pasosAlineacionMeta = calcularPasosAlineacionMeta();
            } else if (veCanicaDer) {
                ultimoLobito = "LOBITO_DER";
                ciclosPersistenciaLobito = Math.max(ciclosPersistenciaLobito, 38);
                pasosAlineacionMeta = calcularPasosAlineacionMeta();
            }
            EstadoRobot estPausa = (ciclosPersistenciaLobito > 0) ? EstadoRobot.APROXIMACION : EstadoRobot.BUSQUEDA;
            return new ComandoRobot(0.0, 0.0, estPausa);
        }

        if (veCanicaCen) {
            pasosGiroComprometido = 0;
            giroEsEvasivo = false;
            ultimoLobito = "LOBITO_CENTRO";
            ciclosPersistenciaLobito = 70;
            ciclosAlineacionLobito = 0;
            pasosAlineacionMeta = 0;
            // Desaceleración suave al 40-50% en los últimos 10-15 cm para embocar sin rebote
            boolean enZonaBucal = sensores != null && (sensores.getLaserLeft() <= 18.0 || sensores.getLaserRight() <= 18.0);
            double vApproach = enZonaBucal ? 2.4 : 3.2;

            double diff = 0.0;
            if (sensores != null) {
                double dl = sensores.getLaserLeft();
                double dr = sensores.getLaserRight();
                if (dl < 25.0 && dr < 25.0) {
                    // Centrado binocular directo: cuando ambos láseres ven la canica, igualar distancias (dl ≈ dr)
                    diff = (dl - dr) * 1.65;
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
            ciclosPersistenciaLobito = 70;
            ciclosAlineacionLobito = 0;
            pasosAlineacionMeta = calcularPasosAlineacionMeta();
            ultimoLobito = "LOBITO_IZQ";
            if (sensores == null) {
                return new ComandoRobot(2.0, 3.0, EstadoRobot.APROXIMACION);
            }
            double dl = sensores.getLaserLeft();
            if (dl < 12.0) {
                return new ComandoRobot(0.4, 2.2, EstadoRobot.APROXIMACION);
            }
            return new ComandoRobot(0.6, 2.4, EstadoRobot.APROXIMACION);
        }

        if (veCanicaDer) {
            pasosGiroComprometido = 0;
            giroEsEvasivo = false;
            ciclosPersistenciaLobito = 70;
            ciclosAlineacionLobito = 0;
            pasosAlineacionMeta = calcularPasosAlineacionMeta();
            ultimoLobito = "LOBITO_DER";
            if (sensores == null) {
                return new ComandoRobot(3.0, 2.0, EstadoRobot.APROXIMACION);
            }
            double dr = sensores.getLaserRight();
            if (dr < 12.0) {
                return new ComandoRobot(2.2, 0.4, EstadoRobot.APROXIMACION);
            }
            return new ComandoRobot(2.4, 0.6, EstadoRobot.APROXIMACION);
        }

        // Persistencia de aproximacion durante barrido de servos (para no perder el objetivo mientras el servo barre)
        boolean bloqueoEnTrayectoria = (sensores != null && (
                sensores.getUsCenter() < 12.0 ||
                sensores.getUsLeft() < 10.0 ||
                sensores.getUsRight() < 10.0
        )) || (bIzq && bCen && bDer);
        if (ciclosPersistenciaLobito > 0 && !bloqueoEnTrayectoria) {
            ciclosPersistenciaLobito--;
            ciclosAlineacionLobito++;

            // Si los láseres siguen viendo la canica frente al robot, refrescar persistencia
            if (sensores != null && sensores.getUsCenter() > 18.0) {
                if (sensores.getLaserLeft() < 28.0 || sensores.getLaserRight() < 28.0) {
                    ciclosPersistenciaLobito = Math.max(ciclosPersistenciaLobito, 40);
                }
            }

            // 1. Si un láser ve claramente la canica en un flanco (d < 28 cm con asimetría > 2.5 cm),
            // virar suavemente hacia ese flanco para ponerla frente a la boca central de 7.0 cm sin patearla
            if (sensores != null) {
                double dl = sensores.getLaserLeft();
                double dr = sensores.getLaserRight();
                if (dr < 28.0 && dr < dl - 2.5) {
                    if (dr < 12.0) {
                        return new ComandoRobot(2.2, 0.4, EstadoRobot.APROXIMACION);
                    }
                    return new ComandoRobot(2.4, 0.8, EstadoRobot.APROXIMACION);
                }
                if (dl < 28.0 && dl < dr - 2.5) {
                    if (dl < 12.0) {
                        return new ComandoRobot(0.4, 2.2, EstadoRobot.APROXIMACION);
                    }
                    return new ComandoRobot(0.8, 2.4, EstadoRobot.APROXIMACION);
                }
            }

            // 2. Solo declarar yaCentrada cuando AMBOS láseres confirman la canica equilibrada frente a la boca de 7.0 cm
            boolean yaCentrada = false;
            double steerBinocular = 0.0;
            if (sensores != null) {
                double dl = sensores.getLaserLeft();
                double dr = sensores.getLaserRight();
                if (dl < 25.0 && dr < 25.0 && Math.abs(dl - dr) <= 4.0) {
                    yaCentrada = true;
                    steerBinocular = Math.max(-0.45, Math.min(0.45, (dl - dr) * 0.14));
                }
            }
            if (yaCentrada) {
                double vBase = (sensores == null) ? 2.2 : 2.4;
                return new ComandoRobot(vBase + steerBinocular, vBase - steerBinocular, EstadoRobot.APROXIMACION);
            }

            // 3. Si ningún láser la ve en este instante, usar memoria de último avistamiento
            if ("LOBITO_IZQ".equals(ultimoLobito)) {
                if (sensores != null && sensores.getLaserLeft() > 28.0 && sensores.getLaserRight() > 28.0
                        && ciclosAlineacionLobito > 16) {
                    ciclosPersistenciaLobito = 0;
                    ultimoLobito = null;
                } else if (ciclosAlineacionLobito <= pasosAlineacionMeta) {
                    return new ComandoRobot(0.6, 2.4, EstadoRobot.APROXIMACION);
                } else {
                    return new ComandoRobot(1.8, 2.5, EstadoRobot.APROXIMACION);
                }
            }
            if ("LOBITO_DER".equals(ultimoLobito)) {
                if (sensores != null && sensores.getLaserLeft() > 28.0 && sensores.getLaserRight() > 28.0
                        && ciclosAlineacionLobito > 16) {
                    ciclosPersistenciaLobito = 0;
                    ultimoLobito = null;
                } else if (ciclosAlineacionLobito <= pasosAlineacionMeta) {
                    return new ComandoRobot(2.4, 0.6, EstadoRobot.APROXIMACION);
                } else {
                    return new ComandoRobot(2.5, 1.8, EstadoRobot.APROXIMACION);
                }
            }
            if ("LOBITO_CENTRO".equals(ultimoLobito)) {
                if (sensores != null && sensores.getLaserLeft() > 28.0 && sensores.getLaserRight() > 28.0
                        && ciclosAlineacionLobito > 14) {
                    ciclosPersistenciaLobito = 0;
                    ultimoLobito = null;
                } else {
                    return new ComandoRobot(2.4, 2.4, EstadoRobot.APROXIMACION);
                }
            }
            return new ComandoRobot(2.2, 2.2, EstadoRobot.APROXIMACION);
        } else if (bloqueoEnTrayectoria) {
            // Si abortó una aproximación por bloqueo ultrasónico real, era un obstáculo 6x6x6 cm o muro
            if (ciclosPersistenciaLobito > 0 && sensores != null && sensores.getCantidadSensores() >= 6
                    && (sensores.getUsCenter() < 10.0)) {
                ciclosInhibicionCanica = 20;
            }
            ciclosPersistenciaLobito = 0;
            ultimoLobito = null;
        }

        // Prioridad 5b: Evitar salir prematuramente por el estrecho de salida (30 cm) antes de Panza Llena
        boolean sCen = "SALIDA".equals(cen);
        boolean sIzq = "SALIDA".equals(izq);
        boolean sDer = "SALIDA".equals(der);

        if (sCen || sIzq || sDer) {
            // Solo considerar riesgo de escape prematuro si el robot está realmente próximo
            // a la embocadura norte (|px| < 0.25, py > 0.35 y avanzando hacia el norte)
            if (sensores != null && sensores.hasPose()) {
                double px = sensores.getPosX();
                double py = sensores.getPosY();
                double th = sensores.getTheta();
                if (py > 0.35 && Math.abs(px) < 0.25 && Math.sin(th) > 0.0) {
                    int fallback = ((contadorCiclos / 100) % 2 == 0) ? 1 : -1;
                    int dir = calcularDireccionEvasiva(sensores, fallback);
                    int pasos = 28 + ((contadorCiclos * 7) % 11);
                    return iniciarRetrocesoArcoYPivote(dir, pasos, EstadoRobot.BUSQUEDA);
                }
            } else {
                int fallback = ((contadorCiclos / 100) % 2 == 0) ? 1 : -1;
                int dir = (sensores != null) ? calcularDireccionEvasiva(sensores, fallback) : fallback;
                boolean jambaCercana = bIzq || bCen || bDer || (sensores != null && (
                        sensores.getUsLeft() < 25.0 || sensores.getUsRight() < 25.0 || sensores.getUsCenter() < 28.0
                        || sensores.getLaserLeft() < 15.0 || sensores.getLaserRight() < 15.0));
                if (sCen || (sIzq && sDer) || jambaCercana) {
                    int pasos = (sensores == null) ? 26 : (28 + ((contadorCiclos * 7) % 11));
                    return iniciarRetrocesoArcoYPivote(dir, pasos, EstadoRobot.BUSQUEDA);
                } else if (sIzq) {
                    return new ComandoRobot(3.8, 2.2, EstadoRobot.BUSQUEDA);
                } else {
                    return new ComandoRobot(2.2, 3.8, EstadoRobot.BUSQUEDA);
                }
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
            if (sensores != null && sensores.getLaserLeft() < 7.0) {
                return iniciarRetrocesoArcoYPivote(1, 16, EstadoRobot.BUSQUEDA);
            }
            if (sensores != null && sensores.getLaserLeft() < 10.0) {
                return new ComandoRobot(3.6, 1.8, EstadoRobot.BUSQUEDA);
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
            if (sensores != null && sensores.getLaserRight() < 7.0) {
                return iniciarRetrocesoArcoYPivote(-1, 16, EstadoRobot.BUSQUEDA);
            }
            if (sensores != null && sensores.getLaserRight() < 10.0) {
                return new ComandoRobot(1.8, 3.6, EstadoRobot.BUSQUEDA);
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

        // Stop-and-Scan en exploración: si algún láser detecta una caída de distancia (< 24 cm)
        // con fondo ultrasónico despejado (d_us >= d_laser + 7 cm), o cada 65 ciclos en zona abierta, detenerse
        // durante 6 tics (~0.19 s, muy lejos del límite de 10 s) para barrido láser estacionario
        if (sensores != null && sensores.getCantidadSensores() >= 6
                && sensores.getUsCenter() > 18.0
                && sensores.getUsLeft() > 16.0
                && sensores.getUsRight() > 16.0) {
            boolean anomaliaLaser = (sensores.getLaserLeft() < 24.0 && sensores.getLaserLeft() >= 3.0 && sensores.getUsLeft() >= sensores.getLaserLeft() + 7.0)
                    || (sensores.getLaserRight() < 24.0 && sensores.getLaserRight() >= 3.0 && sensores.getUsRight() >= sensores.getLaserRight() + 7.0);
            if ((anomaliaLaser && ciclosSinPausaEscaneo >= 20) || ciclosSinPausaEscaneo >= 65) {
                pasosPausaEscaneo = 6;
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
            double vBase = (sensores.getUsLeft() < 28.0 || sensores.getUsRight() < 28.0 || sensores.getUsCenter() < 35.0) ? 2.6 : 3.4;
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
        if (ultimoSensores != null && ultimoSensores.hasPose()) {
            boolean colaCercaMuro = (ultimoSensores.getPosY() < -0.40 && Math.sin(ultimoSensores.getTheta()) > -0.20)
                    || (ultimoSensores.getPosX() < -0.62 && Math.cos(ultimoSensores.getTheta()) > -0.20)
                    || (ultimoSensores.getPosX() > 0.62 && Math.cos(ultimoSensores.getTheta()) < 0.20);
            if (colaCercaMuro) {
                pasosRetro = 0; // Evitar retrocesos cuando la cola está contra cualquier muro
            }
        }
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

    // Notificación explícita de ingesta de presa: avance recto solo si hay despeje libre al frente
    public synchronized void notificarCaptura() {
        if (ultimoSensores != null && (ultimoSensores.getUsCenter() < 25.0 || 
                (ultimoSensores.hasPose() && ultimoSensores.getPosY() < -0.35 && Math.sin(ultimoSensores.getTheta()) < 0))) {
            pasosRetrocesoRestantes = 0;
        } else {
            iniciarAvanceArco(2.6, 2.6, 6);
        }
        ciclosPersistenciaLobito = 0;
        pasosPausaEscaneo = 0;
        pasosEscudoPerimetral = 0;
        ciclosInhibicionCanica = 0;
        ultimoLobito = null;
        ultimaYEstimadaCanica = 0.0;
    }

    // Notificación de impacto físico o contacto: si no está ya retrocediendo, retroceder recto para despegarse del obstáculo
    public synchronized void notificarColision() {
        notificarColision("unknown");
    }

    public synchronized void notificarColision(String lado) {
        pasosPausaEscaneo = 0;
        pasosEscudoPerimetral = 0;
        ciclosPersistenciaLobito = 0;
        ultimoLobito = null;
        if (ultimoSensores != null && ultimoSensores.getCantidadSensores() >= 6) {
            ciclosInhibicionCanica = 28;
        }

        // Si el impacto es trasero (rear_left / rear_right) y el frente tiene margen (> 10.5 cm),
        // avanzar inmediatamente hacia adelante durante 12 pasos para alejar la cola del muro u obstáculo
        // sin exigir condición de láseres laterales (que impedía el desatasco y causaba ráfagas de 5-7 golpes)
        boolean frenteTransitable = (ultimoSensores != null) && ultimoSensores.getUsCenter() > 10.5;

        if ("rear_left".equals(lado)) {
            iniciarAvanceArco(2.0, 3.2, 10);
            return;
        }
        if ("rear_right".equals(lado)) {
            iniciarAvanceArco(3.2, 2.0, 10);
            return;
        }

        int dir;
        if ("front_left".equals(lado)) {
            dir = 1;  // Obstáculo frontal izquierdo: girar a la derecha (+1)
        } else if ("front_right".equals(lado)) {
            dir = -1; // Obstáculo frontal derecho: girar a la izquierda (-1)
        } else {
            dir = (ultimoSensores != null) ? calcularDireccionEvasiva(ultimoSensores, 1)
                                           : ((ultimoLadoBloqueado <= 0) ? 1 : -1);
        }

        // Si el impacto es frontal en simulación real, desenganchar primero con retroceso moderado (6 pasos si la cola no está contra una pared)
        boolean colaCercaMuro = false;
        if (ultimoSensores != null && ultimoSensores.hasPose()) {
            double cpx = ultimoSensores.getPosX();
            double cpy = ultimoSensores.getPosY();
            double cth = ultimoSensores.getTheta();
            colaCercaMuro = (cpx < -0.55 && Math.cos(cth) > 0.2)
                    || (cpx > 0.55 && Math.cos(cth) < -0.2)
                    || (cpy < -0.40 && Math.sin(cth) > 0.2)
                    || (cpy > 0.24 && Math.sin(cth) < -0.2);
        }
        if (ultimoSensores != null && ("front_left".equals(lado) || "front_right".equals(lado)) && !colaCercaMuro) {
            iniciarRetrocesoArco(-2.4, -2.4, 6);
        } else {
            pasosRetrocesoRestantes = 0;
        }
        double velG = (ultimoSensores == null) ? 2.4 : 2.4;
        double sesgoG = 0.0;
        iniciarGiroComprometido(dir, (ultimoSensores == null) ? 16 : 18, velG, sesgoG);
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

    // Registra las 3 lecturas ultrasónicas actuales en la ventana deslizante de 20 ciclos (~0.64 s).
    private void registrarVentanaUltrasonica(VectorSensores sensores) {
        ventanaUsIzq[idxVentanaUs] = sensores.getUsLeft();
        ventanaUsCen[idxVentanaUs] = sensores.getUsCenter();
        ventanaUsDer[idxVentanaUs] = sensores.getUsRight();
        idxVentanaUs = (idxVentanaUs + 1) % TAM_VENTANA_US;
    }

    // Detecta si la lectura láser actual corresponde a la sombra de un cubo 6x6x6 cm o muro cercano:
    // dado que las canicas miden 1.6 cm y jamás son vistas por el ultrasonido (Z = 5.5 cm), si el ultrasonido
    // de ese lado o del centro midió recientemente una distancia corta comparable al láser (<= dLaser + 8.5 cm
    // ante asimetría de obstáculo 3D, o <= dLaser + 4.5 cm ante muro plano), es un obstáculo 3D y no una canica.
    private boolean esSombraObstaculoUltrasonico(boolean esIzquierda, double dLaser, VectorSensores sensores) {
        double minL = 250.0;
        double minC = 250.0;
        double minR = 250.0;
        for (int i = 0; i < TAM_VENTANA_US; i++) {
            if (ventanaUsIzq[i] < minL) minL = ventanaUsIzq[i];
            if (ventanaUsCen[i] < minC) minC = ventanaUsCen[i];
            if (ventanaUsDer[i] < minR) minR = ventanaUsDer[i];
        }
        double minLado = esIzquierda ? Math.min(minL, minC) : Math.min(minR, minC);
        if (minLado <= dLaser + 8.5) {
            return true;
        }
        // Si el ultrasonido frontal o lateral ve un obstáculo dentro de 35 cm,
        // cualquier objeto visto por el láser a menos de 26 cm es parte del obstáculo volumétrico 3D
        if (minLado < 35.0 && dLaser < 26.0) {
            return true;
        }
        if (minC < 32.0 && dLaser < 24.0) {
            return true;
        }
        // Si un rayo ultrasónico ve un salto abrupto (> 26 cm más cerca que su vecino y < 32 cm),
        // está tocando el borde de un cubo 6x6x6 cm en ese sector
        double usLado = esIzquierda ? sensores.getUsLeft() : sensores.getUsRight();
        double usCen = sensores.getUsCenter();
        if (usLado < 32.0 && (usCen - usLado) > 26.0 && dLaser >= usLado - 9.0) {
            return true;
        }
        if (usCen < 32.0 && (usLado - usCen) > 26.0 && dLaser >= usCen - 9.0) {
            return true;
        }
        return false;
    }

    // Comprueba si un punto (x, y) en coordenadas de la arena coincide con la pared perimetral
    private boolean esPuntoEnPared(double x, double y) {
        if (y <= -0.55) return true;
        if (y >= 0.32 && Math.abs(x) > 0.15) return true;
        double yRel = y + 0.60;
        if (yRel < 0.0 || yRel > 1.20) return true;
        double arg = 1.0 - (yRel * yRel) / (1.20 * 1.20);
        double xWall = 0.90 * Math.sqrt(Math.max(0.0, arg));
        return Math.abs(x) >= (xWall - 0.06);
    }

    // Calcula si el impacto físico del rayo láser VL53L0X cae sobre el muro exterior
    private boolean esLaserEnPared(boolean esIzquierda, double dLaser, VectorSensores sensores) {
        if (sensores == null || !sensores.hasPose() || dLaser >= 190.0) {
            return false;
        }
        double rx = sensores.getPosX();
        double ry = sensores.getPosY();
        double yaw = sensores.getTheta();
        double sRad = Math.toRadians(sensores.getAnguloServo());

        double ax = 0.1405;
        double ay = esIzquierda ? 0.083 : -0.083;
        double chipYaw = esIzquierda ? sRad : -sRad;

        double cx = ax + 0.020 * Math.cos(chipYaw);
        double cy = ay + 0.020 * Math.sin(chipYaw);

        double chipWorldX = rx + cx * Math.cos(yaw) - cy * Math.sin(yaw);
        double chipWorldY = ry + cx * Math.sin(yaw) + cy * Math.cos(yaw);
        double beamYaw = yaw + chipYaw;

        double dM = dLaser / 100.0;
        double hitX = chipWorldX + dM * Math.cos(beamYaw);
        double hitY = chipWorldY + dM * Math.sin(beamYaw);

        return esPuntoEnPared(hitX, hitY);
    }

    // Calcula cuántos pasos de giro necesita el robot para alinear la canica con el centro de la boca
    // según la distancia lateral estimada |Y|, evitando avanzar recto cuando solo un láser lateral la vio.
    private int calcularPasosAlineacionMeta() {
        double absY = Math.abs(ultimaYEstimadaCanica);
        if (absY <= 3.0) {
            return 0;
        }
        return Math.min(16, Math.max(8, (int) Math.round((absY - 1.5) * 1.1)));
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
                && dl >= 2.0 && dl <= 23.0
                && sensores.getUsLeft() >= dl + 10.0
                && !esLaserEnPared(true, dl, sensores);
        boolean candR = (lDer || (lCen && dr <= dl + 2.5))
                && dr >= 2.0 && dr <= 23.0
                && sensores.getUsRight() >= dr + 10.0
                && !esLaserEnPared(false, dr, sensores);

        vectorCandidatoIzq[idx] = candL;
        vectorCandidatoDer[idx] = candR;

        // Detección de obstáculos en los flancos laterales cuando el servo abre hacia afuera (+15° a +40°):
        // solo se activa si hay sombra ultrasónica o pared real para no desviar el auto contra una canica lateral.
        if (theta >= 15.0) {
            if (dl < 14.5 && !candL && (esSombraObstaculoUltrasonico(true, dl, sensores) || esLaserEnPared(true, dl, sensores))) {
                ciclosAlertaFlancoIzq = 6;
            }
            if (dr < 14.5 && !candR && (esSombraObstaculoUltrasonico(false, dr, sensores) || esLaserEnPared(false, dr, sensores))) {
                ciclosAlertaFlancoDer = 6;
            }
        }

        // Proyección trigonométrica de la coordenada lateral Y de la canica respecto al eje central (boca en [-3.5, +3.5] cm)
        // considerando el semicono exterior (+10°) cuando un solo láser detecta la canica.
        double thetaConeRad = Math.toRadians(theta + 10.0);
        if (candL && candR && Math.abs(dl - dr) <= 4.0) {
            ultimaYEstimadaCanica = (dr - dl) * 0.65;
        } else if (candL) {
            ultimaYEstimadaCanica = Math.max(4.5, 8.3 + (2.0 + dl) * Math.sin(thetaConeRad));
        } else if (candR) {
            ultimaYEstimadaCanica = Math.min(-4.5, -8.3 - (2.0 + dr) * Math.sin(thetaConeRad));
        }
    }

    // Verifica que el objeto detectado como posible canica tenga un ancho angular angosto en tics
    // acorde al cono óptico de 25° del VL53L0X (6 tics de 5° para un punto) y no sea una pared ancha.
    private boolean esAnchoTicsCanicaValido(boolean esIzquierda, int idxActual, double dLaser) {
        double[] vecDist = esIzquierda ? vectorRadarIzq : vectorRadarDer;
        int anchoTics = 1;

        for (int i = idxActual - 1; i >= 0; i--) {
            if ((contadorCiclos - cicloLecturaRadar[i]) <= 22 && vecDist[i] >= 2.0 && vecDist[i] <= 23.0) {
                anchoTics++;
            } else {
                break;
            }
        }
        for (int i = idxActual + 1; i < TAM_VECTOR_RADAR; i++) {
            if ((contadorCiclos - cicloLecturaRadar[i]) <= 22 && vecDist[i] >= 2.0 && vecDist[i] <= 23.0) {
                anchoTics++;
            } else {
                break;
            }
        }
        int maxPermitido = (dLaser > 14.5) ? 7 : 10;
        return anchoTics <= maxPermitido;
    }

    // Activa la transicion formal hacia el modo escape.
    public synchronized void activarModoEscape() {
        this.estado = EstadoRobot.ESCAPE;
        this.ladoSeguimientoEscape = 0;
        this.pasosRetrocesoRestantes = 0;
        this.pasosGiroComprometido = 0;
        this.pasosPausaEscaneo = 0;
        this.pasosEscudoPerimetral = 0;
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
        this.pasosEscudoPerimetral = 0;
        this.giroAlineacionSalida = false;
    }

    // Escudo preventivo perimetral analítico con histéresis y maniobra comprometida:
    // modela el arco superior y el muro sur de la bahía (y_back = -0.60, a = 1.20, b = 0.90),
    // orientando al robot hacia el centro de caza (0.0, -0.04) sin oscilaciones ni bucles límite.
    private ComandoRobot verificarEscudoPerimetral(VectorSensores sensores, EstadoRobot estadoActual, boolean cazandoCanica) {
        if (sensores == null || !sensores.hasPose()) {
            return null;
        }

        double px = sensores.getPosX();
        double py = sensores.getPosY();
        double th = sensores.getTheta();

        // En ESCAPE, permitir paso libre por el embudo y pasillo de salida (|px| < 0.18 y py > 0.28)
        if (estadoActual == EstadoRobot.ESCAPE && Math.abs(px) < 0.18 && py > 0.28) {
            pasosEscudoPerimetral = 0;
            return null;
        }

        // Si el robot está persiguiendo activamente una canica dentro de márgenes físicos seguros (> 18 cm a muros y despeje frontal),
        // permitir que complete la embocadura sin abortar
        double yRelCheck = py + 0.60;
        double argCheck = 1.0 - (yRelCheck * yRelCheck) / (1.20 * 1.20);
        double xWallCheck = 0.90 * Math.sqrt(Math.max(0.0, argCheck));
        if (cazandoCanica && py >= -0.44 && py <= 0.22 && Math.abs(px) < (xWallCheck - 0.18)
                && sensores.getUsCenter() >= 18.0 && sensores.getLaserLeft() >= 8.0 && sensores.getLaserRight() >= 8.0) {
            pasosEscudoPerimetral = 0;
            return null;
        }

        // Si hay una pared u obstáculo a quemarropa (< 14 cm al centro o < 11 cm a laterales),
        // ceder inmediatamente a la capa reactiva de evasión física para evitar colisiones
        if (sensores.getUsCenter() < 14.0 || sensores.getUsLeft() < 11.0 || sensores.getUsRight() < 11.0) {
            pasosEscudoPerimetral = 0;
            return null;
        }

        // 1. Fase comprometida activa: si ya se inició una maniobra de despegue de pared, sostenerla hasta completar el arco
        if (pasosEscudoPerimetral > 0) {
            pasosEscudoPerimetral--;
            // Viraje rodante con avance positivo hacia el centro (despega cola y chasis sin barrer)
            if (direccionEscudoPerimetral > 0) {
                return new ComandoRobot(0.6, 2.8, estadoActual);
            } else {
                return new ComandoRobot(2.8, 0.6, estadoActual);
            }
        }

        // En BUSQUEDA, impedir terminantemente filtrarse por la puerta norte (hueco central |px| < 0.16 y py > 0.28):
        // virar en el lugar hacia el sur y regresar al centro
        if (estadoActual != EstadoRobot.ESCAPE && Math.abs(px) < 0.16 && py > 0.28) {
            pasosEscudoPerimetral = 0;
            if (Math.sin(th) > -0.30) {
                return (px >= 0) ? new ComandoRobot(2.4, -2.4, estadoActual)
                                 : new ComandoRobot(-2.4, 2.4, estadoActual);
            } else {
                return new ComandoRobot(3.4, 3.4, estadoActual);
            }
        }

        // Pared sur plana (y = -0.60 m): activa si py < -0.42 y apunta hacia el sur o paralelo
        boolean cercaSur = (py < -0.42 && Math.sin(th) < 0.20);

        // Arco elíptico este/oeste: x_wall(y) = 0.90 * sqrt(1 - ((y+0.60)/1.20)^2)
        double yRel = py + 0.60;
        double arg = 1.0 - (yRel * yRel) / (1.20 * 1.20);
        double xWall = 0.90 * Math.sqrt(Math.max(0.0, arg));
        boolean cercaArco = (Math.abs(px) > (xWall - 0.22));

        if (!cercaSur && !cercaArco) {
            return null;
        }

        // Rumbo hacia el centro de la zona de caza (0.0, -0.04)
        double dx = 0.0 - px;
        double dy = -0.04 - py;
        double anguloCentro = Math.atan2(dy, dx);
        double diff = anguloCentro - th;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;

        // Si ya está apuntando hacia el centro (error angular < 26°):
        // NO desactivar el escudo para no caer en oscilación reactiva.
        // Avanzar decididamente hacia el interior de la pista hasta cruzar el margen seguro.
        if (Math.abs(diff) < 0.45) {
            boolean enZonaSegura = (Math.abs(px) < (xWall - 0.28)) && (py > -0.40) && (py < 0.24);
            if (enZonaSegura) {
                return null; // Ya despegó de la pared: control libre para buscar canicas
            }
            // Aún está en el zócalo perimetral pero bien orientado: avanzar recto hacia el centro
            return new ComandoRobot(3.2, 3.2, estadoActual);
        }

        // 3. Caso especial Spawn inicial en esquina SO (-0.74, -0.42) mirando al norte:
        if (px < -0.60 && py < -0.26 && Math.sin(th) > 0.30) {
            return new ComandoRobot(3.4, 1.8, estadoActual);
        }

        // 4. Activar maniobra perimetral comprometida (latch de 16 ciclos ≈ 0.5 s)
        direccionEscudoPerimetral = (diff > 0) ? 1 : -1;
        pasosEscudoPerimetral = 16;

        return (direccionEscudoPerimetral > 0) ? new ComandoRobot(0.6, 2.8, estadoActual)
                                               : new ComandoRobot(2.8, 0.6, estadoActual);
    }
}
