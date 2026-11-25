package com.orquitas.server;

import java.util.logging.Logger;

/**
 * Planificador y maquina de estados finita (FSM) puramente simbolica de la Orquita.
 * Decide comandos diferenciales exclusivamente a partir de la terna neuronal [IZQ, CEN, DER]
 * emitida por Neuroph 2.98, el estado de mision y la memoria simbolica de pasos/oscilaciones.
 */
public class PlanificadorNavegacion {
    private static final Logger LOGGER = Logger.getLogger(PlanificadorNavegacion.class.getName());

    private static final int HORIZONTE_OLVIDO_OSCILACIONES = 150;
    private static final int TAM_HISTORIAL_LADOS = 6;
    private static final int UMBRAL_EPISODIOS_MISMO_LADO = 3;
    private static final int PERSISTENCIA_LOBITO_CICLOS = 12;
    private static final int CICLOS_AVANCE_BUSQUEDA = 4;
    private static final int CICLOS_PAUSA_BUSQUEDA = 6;
    private static final int CICLOS_PIVOT_LOBITO = 2;
    private static final int CICLOS_PAUSA_PIVOT_LOBITO = 4;
    private static final int CICLOS_AVANCE_CENTRO_LOBITO = 3;
    private static final int CICLOS_PAUSA_CENTRO_LOBITO = 3;
    private static final int MAX_CICLOS_CENTRADO_SIN_CENTRO = 26;
    private static final int COOLDOWN_FALSO_LOBITO_CICLOS = 25;
    private static final int PASOS_GIRO_DESPEGUE_FALSO_LOBITO = 6;
    private static final int[] DURACIONES_GIRO_ASIMETRICO = {13, 17, 21};
    private static final double VEL_ARCO_EXT = 4.8;
    private static final double VEL_ARCO_INT = 2.0;

    private volatile EstadoRobot estado = EstadoRobot.BUSQUEDA;
    // [SOLO PRUEBAS / DEBUG - cantidad de pasos en reversa ajustable en vivo desde TUI]
    private volatile int pasosReversa = ConfiguracionServidor.PASOS_REVERSA_PREDETERMINADO;
    private int pasosRetrocesoRestantes = 0;
    private double velRetrocesoIzq = 0.0;
    private double velRetrocesoDer = 0.0;
    private int pasosGiroComprometido = 0;
    private double velGiroIzq = 0.0;
    private double velGiroDer = 0.0;
    private boolean giroSoloSalidaActivo = false;
    private int ultimoSentidoGiroSalida = 1;
    private int indiceGiroAsimetrico = 0;

    private int contadorCiclos = 0;
    private int ultimoLadoBloqueado = 0;
    private int ciclosDesdeUltimoBloqueo = HORIZONTE_OLVIDO_OSCILACIONES;
    private int contadorOscilaciones = 0;
    private int episodiosMismoLado = 0;
    private int contadorReversosConsecutivos = 0;
    private int ciclosDesdeUltimoReverso = 200;
    private final int[] historialLados = new int[TAM_HISTORIAL_LADOS];
    private int indiceHistorialLados = 0;
    private int entradasHistorialLados = 0;
    private int ciclosPersistenciaLobito = 0;
    private String ultimoLobito = null;
    private int ciclosCentradoSinCentro = 0;
    private int ciclosCooldownFalsoLobito = 0;
    private int fasePulsoBusqueda = 0;
    private int fasePulsoAproximacion = 0;
    private int ciclosSinSalidaEscape = 0;
    // [SOLO PRUEBAS / DEBUG - permite forzar CAPTURA para validar picker en pista]
    private volatile boolean modoCapturaForzado = false;

    public PlanificadorNavegacion(GestorEstomago gestorEstomago) {
        this(gestorEstomago, ConfiguracionServidor.PASOS_REVERSA_PREDETERMINADO);
    }

    public PlanificadorNavegacion(GestorEstomago gestorEstomago, int pasosReversa) {
        this.pasosReversa = ConfiguracionServidor.acotarPasosReversa(pasosReversa);
        if (gestorEstomago != null
                && gestorEstomago.contarCapturasValidas() >= ConfiguracionServidor.CAPTURAS_REQUERIDAS_ESCAPE) {
            this.estado = EstadoRobot.ESCAPE;
        }
    }

    /** Evalua la accion motriz exclusivamente sobre la terna simbolica [IZQ, CEN, DER]. */
    public synchronized ComandoRobot evaluarPares(VectorSensores sensoresIgnorados, String[] pares) {
        if (estado == EstadoRobot.FINALIZACION || estado == EstadoRobot.ERROR_SEGURO) {
            return new ComandoRobot(0.0, 0.0, estado);
        }
        if (pares == null || pares.length < 3) {
            return new ComandoRobot(0.0, 0.0, estado);
        }

        contadorCiclos++;
        ciclosDesdeUltimoBloqueo++;
        ciclosDesdeUltimoReverso++;
        if (ciclosDesdeUltimoReverso > 100) {
            contadorReversosConsecutivos = 0;
        }

        if (modoCapturaForzado) {
            this.estado = EstadoRobot.CAPTURA;
            return new ComandoRobot(2.4, 2.4, EstadoRobot.CAPTURA);
        }

        String izq = pares[0] != null ? pares[0] : "ESPACIO_LIBRE";
        String cen = pares[1] != null ? pares[1] : "ESPACIO_LIBRE";
        String der = pares[2] != null ? pares[2] : "ESPACIO_LIBRE";

        return (estado == EstadoRobot.ESCAPE)
                ? evaluarModoEscape(izq, cen, der)
                : evaluarModoBusqueda(izq, cen, der);
    }

    private ComandoRobot evaluarModoEscape(String izq, String cen, String der) {
        boolean sIzq = "SALIDA".equals(izq), sCen = "SALIDA".equals(cen), sDer = "SALIDA".equals(der);
        boolean bIzq = "BLOQUEO".equals(izq), bCen = "BLOQUEO".equals(cen), bDer = "BLOQUEO".equals(der);

        if (sIzq || sCen || sDer) {
            ciclosSinSalidaEscape = 0;
            cancelarGiroYAvance();
        } else {
            ciclosSinSalidaEscape++;
        }

        ComandoRobot comprometido = ejecutarManiobraComprometida(EstadoRobot.ESCAPE);
        if (comprometido != null) {
            return comprometido;
        }
        if (sCen) {
            if (bIzq && bDer) {
                iniciarRetroceso(-2.4, -2.4, 6);
                return new ComandoRobot(-2.4, -2.4, EstadoRobot.ESCAPE);
            }
            if (bDer) return new ComandoRobot(1.6, 3.2, EstadoRobot.ESCAPE);
            if (bIzq) return new ComandoRobot(3.2, 1.6, EstadoRobot.ESCAPE);
            return new ComandoRobot(3.4, 3.4, EstadoRobot.ESCAPE);
        }
        if (sIzq) {
            return bCen
                    ? iniciarRetrocesoYPivote(-1, 14, EstadoRobot.ESCAPE)
                    : new ComandoRobot(1.8, 4.2, EstadoRobot.ESCAPE);
        }
        if (sDer) {
            return bCen
                    ? iniciarRetrocesoYPivote(1, 14, EstadoRobot.ESCAPE)
                    : new ComandoRobot(4.2, 1.8, EstadoRobot.ESCAPE);
        }

        ComandoRobot evasion = evaluarBloqueo(bIzq, bCen, bDer, EstadoRobot.ESCAPE);
        if (evasion != null) {
            return evasion;
        }
        double sweep = ((contadorCiclos / 75) % 2 == 0) ? 0.35 : -0.35;
        return new ComandoRobot(4.2 + sweep, 4.2 - sweep, EstadoRobot.ESCAPE);
    }

    private ComandoRobot evaluarModoBusqueda(String izq, String cen, String der) {
        boolean lIzq = "LOBITO".equals(izq), lCen = "LOBITO".equals(cen), lDer = "LOBITO".equals(der);
        boolean bIzq = "BLOQUEO".equals(izq), bCen = "BLOQUEO".equals(cen), bDer = "BLOQUEO".equals(der);
        boolean hayBloqueo = bIzq || bCen || bDer;
        boolean haySalida = "SALIDA".equals(izq) || "SALIDA".equals(cen) || "SALIDA".equals(der);
        if (ciclosCooldownFalsoLobito > 0) {
            ciclosCooldownFalsoLobito--;
        }
        boolean lobitoPermitido = (ciclosCooldownFalsoLobito == 0);

        if (hayBloqueo || haySalida) {
            resetEstadoLobito();
            fasePulsoBusqueda = 0;
        } else if (lobitoPermitido && (lIzq || lCen || lDer)) {
            cancelarGiroYAvance();
            fasePulsoBusqueda = 0;
        }
        if (giroSoloSalidaActivo && (bCen || (bIzq && bDer))) {
            pasosGiroComprometido = 0;
            giroSoloSalidaActivo = false;
        }
        ComandoRobot comprometido = ejecutarManiobraComprometida(EstadoRobot.BUSQUEDA);
        if (comprometido != null) {
            fasePulsoBusqueda = 0;
            return comprometido;
        }

        // Prioridad estricta en BUSQUEDA: SALIDA > BLOQUEO > LOBITO
        // Evitar egreso prematuro antes de completar las 3 capturas: sin bloqueo al frente,
        // pivotar en el lugar (0 pasos de reversa) eligiendo lado libre y alternando sentido.
        if ("SALIDA".equals(cen) || ("SALIDA".equals(izq) && "SALIDA".equals(der))) {
            int dir;
            if (bIzq && !bDer) {
                dir = 1;
            } else if (bDer && !bIzq) {
                dir = -1;
            } else {
                ultimoSentidoGiroSalida = -ultimoSentidoGiroSalida;
                dir = ultimoSentidoGiroSalida;
            }
            int durGiro = siguienteDuracionGiro();
            if (bCen) {
                return iniciarRetrocesoYPivote(dir, durGiro, EstadoRobot.BUSQUEDA);
            }
            pasosRetrocesoRestantes = 0;
            iniciarGiro(dir, Math.max(0, durGiro - 1));
            giroSoloSalidaActivo = true;
            return new ComandoRobot(velGiroIzq, velGiroDer, EstadoRobot.BUSQUEDA);
        }
        if ("SALIDA".equals(izq)) {
            if (bCen) {
                return iniciarRetrocesoYPivote(1, 14, EstadoRobot.BUSQUEDA);
            }
            if (!bDer) {
                return new ComandoRobot(3.8, -2.2, EstadoRobot.BUSQUEDA);
            }
        }
        if ("SALIDA".equals(der)) {
            if (bCen) {
                return iniciarRetrocesoYPivote(-1, 14, EstadoRobot.BUSQUEDA);
            }
            if (!bIzq) {
                return new ComandoRobot(-2.2, 3.8, EstadoRobot.BUSQUEDA);
            }
        }

        ComandoRobot evasion = evaluarBloqueo(bIzq, bCen, bDer, EstadoRobot.BUSQUEDA);
        if (evasion != null) {
            return evasion;
        }

        if (lobitoPermitido) {
            ComandoRobot lobitoCmd = evaluarLobito(lIzq, lCen, lDer);
            if (lobitoCmd != null) {
                return lobitoCmd;
            }
        }

        if (ciclosDesdeUltimoBloqueo > HORIZONTE_OLVIDO_OSCILACIONES) {
            reiniciarMemoriaOscilaciones();
        }
        int fase = fasePulsoBusqueda;
        fasePulsoBusqueda = (fasePulsoBusqueda + 1) % (CICLOS_AVANCE_BUSQUEDA + CICLOS_PAUSA_BUSQUEDA);
        if (fase >= CICLOS_AVANCE_BUSQUEDA) {
            return new ComandoRobot(0.0, 0.0, EstadoRobot.BUSQUEDA);
        }
        double sweep = ((contadorCiclos / 22) % 2 == 0) ? 0.25 : -0.25;
        return new ComandoRobot(4.8 + sweep, 4.8 - sweep, EstadoRobot.BUSQUEDA);
    }

    private ComandoRobot evaluarLobito(boolean lIzq, boolean lCen, boolean lDer) {
        if (lCen) {
            if (!"CEN".equals(ultimoLobito)) {
                fasePulsoAproximacion = 0;
            }
            ciclosCentradoSinCentro = 0;
            ciclosPersistenciaLobito = PERSISTENCIA_LOBITO_CICLOS;
            ultimoLobito = "CEN";
            int fase = fasePulsoAproximacion;
            fasePulsoAproximacion = (fasePulsoAproximacion + 1) % (CICLOS_AVANCE_CENTRO_LOBITO + CICLOS_PAUSA_CENTRO_LOBITO);
            if (fase >= CICLOS_AVANCE_CENTRO_LOBITO) {
                return new ComandoRobot(0.0, 0.0, EstadoRobot.APROXIMACION);
            }
            return new ComandoRobot(2.6, 2.6, EstadoRobot.APROXIMACION);
        }
        if (lIzq || lDer) {
            String lado = lIzq ? "IZQ" : "DER";
            if (!lado.equals(ultimoLobito)) {
                fasePulsoAproximacion = 0;
            }
            ciclosCentradoSinCentro++;
            if (ciclosCentradoSinCentro >= MAX_CICLOS_CENTRADO_SIN_CENTRO) {
                return activarDespegueFalsoLobito(lado);
            }
            ciclosPersistenciaLobito = PERSISTENCIA_LOBITO_CICLOS;
            ultimoLobito = lado;
            int fase = fasePulsoAproximacion;
            fasePulsoAproximacion = (fasePulsoAproximacion + 1) % (CICLOS_PIVOT_LOBITO + CICLOS_PAUSA_PIVOT_LOBITO);
            if (fase >= CICLOS_PIVOT_LOBITO) {
                return new ComandoRobot(0.0, 0.0, EstadoRobot.APROXIMACION);
            }
            return lIzq
                    ? new ComandoRobot(-2.4, 2.4, EstadoRobot.APROXIMACION)
                    : new ComandoRobot(2.4, -2.4, EstadoRobot.APROXIMACION);
        }
        if (ciclosPersistenciaLobito > 0) {
            ciclosPersistenciaLobito--;
            if (!"CEN".equals(ultimoLobito)) {
                ciclosCentradoSinCentro++;
                if (ciclosCentradoSinCentro >= MAX_CICLOS_CENTRADO_SIN_CENTRO) {
                    return activarDespegueFalsoLobito(ultimoLobito);
                }
                return new ComandoRobot(0.0, 0.0, EstadoRobot.APROXIMACION);
            }
            int fase = fasePulsoAproximacion;
            fasePulsoAproximacion = (fasePulsoAproximacion + 1) % 4;
            return (fase == 0)
                    ? new ComandoRobot(2.2, 2.2, EstadoRobot.APROXIMACION)
                    : new ComandoRobot(0.0, 0.0, EstadoRobot.APROXIMACION);
        }
        ultimoLobito = null;
        ciclosCentradoSinCentro = 0;
        fasePulsoAproximacion = 0;
        return null;
    }

    private ComandoRobot activarDespegueFalsoLobito(String ladoSospechoso) {
        resetEstadoLobito();
        fasePulsoBusqueda = 0;
        ciclosCooldownFalsoLobito = COOLDOWN_FALSO_LOBITO_CICLOS;
        int dirAlejarse = "IZQ".equals(ladoSospechoso) ? 1 : ("DER".equals(ladoSospechoso) ? -1 : ultimoSentidoGiroSalida);
        iniciarGiro(dirAlejarse, PASOS_GIRO_DESPEGUE_FALSO_LOBITO - 1);
        return new ComandoRobot(velGiroIzq, velGiroDer, EstadoRobot.BUSQUEDA);
    }

    private void resetEstadoLobito() {
        ciclosPersistenciaLobito = 0;
        ultimoLobito = null;
        ciclosCentradoSinCentro = 0;
        fasePulsoAproximacion = 0;
    }

    private void cancelarGiroYAvance() {
        pasosGiroComprometido = 0;
        giroSoloSalidaActivo = false;
        if (velRetrocesoIzq > 0.0 && velRetrocesoDer > 0.0) {
            pasosRetrocesoRestantes = 0;
        }
    }

    private ComandoRobot ejecutarManiobraComprometida(EstadoRobot estadoSalida) {
        if (pasosRetrocesoRestantes > 0) {
            pasosRetrocesoRestantes--;
            return new ComandoRobot(velRetrocesoIzq, velRetrocesoDer, estadoSalida);
        }
        if (pasosGiroComprometido > 0) {
            pasosGiroComprometido--;
            if (pasosGiroComprometido == 0) {
                giroSoloSalidaActivo = false;
            }
            return new ComandoRobot(velGiroIzq, velGiroDer, estadoSalida);
        }
        giroSoloSalidaActivo = false;
        return null;
    }

    private int siguienteDuracionGiro() {
        int dur = DURACIONES_GIRO_ASIMETRICO[indiceGiroAsimetrico];
        indiceGiroAsimetrico = (indiceGiroAsimetrico + 1) % DURACIONES_GIRO_ASIMETRICO.length;
        return dur;
    }

    private ComandoRobot evaluarBloqueo(boolean bIzq, boolean bCen, boolean bDer, EstadoRobot estadoSalida) {
        if (bCen || (bIzq && bDer)) {
            int dir = (bIzq && !bDer) ? 1 : ((bDer && !bIzq) ? -1 : ((ultimoLadoBloqueado <= 0) ? 1 : -1));
            registrarBloqueoLateral(-dir);
            if (debeActivarAntiBucle()) {
                return activarManiobraAntiBucle(dir, estadoSalida);
            }
            return iniciarRetrocesoYPivote(dir, siguienteDuracionGiro(), estadoSalida);
        }
        if (bIzq || bDer) {
            int lado = bIzq ? -1 : 1;
            registrarBloqueoLateral(lado);
            if (debeActivarAntiBucle()) {
                return activarManiobraAntiBucle(-lado, estadoSalida);
            }
            return bIzq
                    ? new ComandoRobot(VEL_ARCO_EXT, VEL_ARCO_INT, estadoSalida)
                    : new ComandoRobot(VEL_ARCO_INT, VEL_ARCO_EXT, estadoSalida);
        }
        return null;
    }

    private ComandoRobot iniciarRetrocesoYPivote(int dir, int pasosGiro, EstadoRobot estadoSalida) {
        if (ciclosDesdeUltimoReverso < 70) {
            contadorReversosConsecutivos++;
        }
        if (contadorReversosConsecutivos >= 4) {
            contadorReversosConsecutivos = 0;
            return activarManiobraAntiBucle(dir, estadoSalida);
        }
        ciclosDesdeUltimoReverso = 0;
        giroSoloSalidaActivo = false;
        int rev = pasosReversa;
        if (rev <= 0) {
            pasosRetrocesoRestantes = 0;
            iniciarGiro(dir, Math.max(0, pasosGiro - 1));
            return new ComandoRobot(velGiroIzq, velGiroDer, estadoSalida);
        }
        iniciarRetroceso(-3.6, -3.6, rev);
        iniciarGiro(dir, pasosGiro);
        return new ComandoRobot(-3.6, -3.6, estadoSalida);
    }

    private ComandoRobot activarManiobraAntiBucle(int dir, EstadoRobot estadoSalida) {
        reiniciarMemoriaOscilaciones();
        ciclosDesdeUltimoReverso = 0;
        giroSoloSalidaActivo = false;
        int revAnti = (pasosReversa > 0) ? Math.min(ConfiguracionServidor.PASOS_REVERSA_MAX, pasosReversa + 2) : 0;
        if (revAnti <= 0) {
            pasosRetrocesoRestantes = 0;
            iniciarGiro(dir, 15);
            return new ComandoRobot(velGiroIzq, velGiroDer, estadoSalida);
        }
        iniciarRetroceso(-4.2, -4.2, revAnti);
        iniciarGiro(dir, 16);
        return new ComandoRobot(-4.2, -4.2, estadoSalida);
    }

    private void iniciarRetroceso(double vIzq, double vDer, int pasos) {
        pasosRetrocesoRestantes = Math.max(0, pasos - 1);
        velRetrocesoIzq = vIzq;
        velRetrocesoDer = vDer;
    }

    private void iniciarGiro(int dir, int pasos) {
        pasosGiroComprometido = Math.max(0, pasos);
        velGiroIzq = (dir >= 0) ? 4.2 : -2.4;
        velGiroDer = (dir >= 0) ? -2.4 : 4.2;
    }

    private void registrarBloqueoLateral(int lado) {
        if (ciclosDesdeUltimoBloqueo >= HORIZONTE_OLVIDO_OSCILACIONES) {
            reiniciarMemoriaOscilaciones();
        }
        if (ultimoLadoBloqueado != 0 && ultimoLadoBloqueado != lado) {
            contadorOscilaciones++;
        }
        if (ultimoLadoBloqueado != lado) {
            episodiosMismoLado = 1;
            historialLados[indiceHistorialLados] = lado;
            indiceHistorialLados = (indiceHistorialLados + 1) % historialLados.length;
            if (entradasHistorialLados < historialLados.length) {
                entradasHistorialLados++;
            }
        } else if (ciclosDesdeUltimoBloqueo > 1) {
            episodiosMismoLado++;
        }
        ultimoLadoBloqueado = lado;
        ciclosDesdeUltimoBloqueo = 0;
    }

    private void reiniciarMemoriaOscilaciones() {
        contadorOscilaciones = 0;
        episodiosMismoLado = 0;
        ultimoLadoBloqueado = 0;
        indiceHistorialLados = 0;
        entradasHistorialLados = 0;
    }

    private boolean debeActivarAntiBucle() {
        return contadorOscilaciones >= 2
                || episodiosMismoLado >= UMBRAL_EPISODIOS_MISMO_LADO
                || esPatronAlternante();
    }

    private boolean esPatronAlternante() {
        if (entradasHistorialLados < 3) {
            return false;
        }
        int anterior = 0;
        for (int i = 0; i < entradasHistorialLados; i++) {
            int pos = (indiceHistorialLados - 1 - i + historialLados.length * 2) % historialLados.length;
            int actual = historialLados[pos];
            if (actual == 0 || actual == anterior) {
                return false;
            }
            anterior = actual;
        }
        return true;
    }

    /** Registra captura simbolica ejecutando un breve impulso de avance y giro de despeje. */
    public synchronized void notificarCaptura() {
        resetEstadoLobito();
        ciclosCooldownFalsoLobito = 0;
        fasePulsoBusqueda = 0;
        pasosRetrocesoRestantes = 3;
        velRetrocesoIzq = 2.8;
        velRetrocesoDer = 2.8;
        pasosGiroComprometido = 4;
        velGiroIzq = (ultimoLadoBloqueado <= 0) ? 3.2 : 1.8;
        velGiroDer = (ultimoLadoBloqueado <= 0) ? 1.8 : 3.2;
    }

    public synchronized void activarModoEscape() {
        this.modoCapturaForzado = false;
        this.giroSoloSalidaActivo = false;
        this.estado = EstadoRobot.ESCAPE;
        this.pasosRetrocesoRestantes = 0;
        this.pasosGiroComprometido = 0;
        resetEstadoLobito();
        this.ciclosCooldownFalsoLobito = 0;
        this.fasePulsoBusqueda = 0;
        this.ciclosSinSalidaEscape = 0;
        LOGGER.info("MUTACION CONDUCTUAL ACTIVADA: Modo ESCAPE.");
    }

    public synchronized void finalizar() {
        this.modoCapturaForzado = false;
        this.giroSoloSalidaActivo = false;
        this.estado = EstadoRobot.FINALIZACION;
        this.pasosRetrocesoRestantes = 0;
        this.pasosGiroComprometido = 0;
        resetEstadoLobito();
        this.ciclosCooldownFalsoLobito = 0;
        this.fasePulsoBusqueda = 0;
    }

    public EstadoRobot getEstado() { return estado; }

    public synchronized int getPasosReversa() { return pasosReversa; }

    /** // [SOLO PRUEBAS / DEBUG - ajustar cantidad de pasos en reversa desde TUI] */
    public synchronized void setPasosReversa(int pasosReversa) {
        this.pasosReversa = ConfiguracionServidor.acotarPasosReversa(pasosReversa);
    }

    public synchronized void setEstado(EstadoRobot nuevoEstado) {
        this.modoCapturaForzado = false;
        this.giroSoloSalidaActivo = false;
        this.estado = nuevoEstado;
        if (nuevoEstado == EstadoRobot.ESCAPE) {
            this.ciclosSinSalidaEscape = 0;
        }
    }

    // [SOLO PRUEBAS / DEBUG - metodos para forzar estados individuales desde TUI]
    public synchronized void forzarModoBusqueda() {
        this.modoCapturaForzado = false;
        this.giroSoloSalidaActivo = false;
        this.estado = EstadoRobot.BUSQUEDA;
        this.pasosRetrocesoRestantes = 0;
        this.pasosGiroComprometido = 0;
        resetEstadoLobito();
        this.ciclosCooldownFalsoLobito = 0;
        this.fasePulsoBusqueda = 0;
        LOGGER.info("[PRUEBA] Modo forzado: BUSQUEDA.");
    }

    public synchronized void forzarModoEscape() {
        activarModoEscape();
    }

    public synchronized void forzarModoCaptura() {
        this.modoCapturaForzado = true;
        this.giroSoloSalidaActivo = false;
        this.estado = EstadoRobot.CAPTURA;
        this.pasosRetrocesoRestantes = 0;
        this.pasosGiroComprometido = 0;
        LOGGER.info("[PRUEBA] Modo forzado: CAPTURA (prueba de picker).");
    }

    public synchronized boolean isModoCapturaForzado() {
        return modoCapturaForzado;
    }

    public synchronized int getCiclosSinSalidaEscape() { return ciclosSinSalidaEscape; }

    public synchronized int getContadorCiclos() { return contadorCiclos; }

    /** Reinicia el estado conductual de navegacion a BUSQUEDA para pruebas tras resetear el estomago. */
    public synchronized void reiniciarParaPrueba() {
        this.modoCapturaForzado = false;
        this.giroSoloSalidaActivo = false;
        this.estado = EstadoRobot.BUSQUEDA;
        this.pasosRetrocesoRestantes = 0;
        this.velRetrocesoIzq = 0.0;
        this.velRetrocesoDer = 0.0;
        this.pasosGiroComprometido = 0;
        this.velGiroIzq = 0.0;
        this.velGiroDer = 0.0;
        this.contadorCiclos = 0;
        this.contadorReversosConsecutivos = 0;
        this.ciclosDesdeUltimoReverso = 200;
        this.ciclosDesdeUltimoBloqueo = HORIZONTE_OLVIDO_OSCILACIONES;
        resetEstadoLobito();
        this.ciclosCooldownFalsoLobito = 0;
        this.fasePulsoBusqueda = 0;
        this.ciclosSinSalidaEscape = 0;
        reiniciarMemoriaOscilaciones();
        LOGGER.info("[PRUEBA] Planificador reiniciado a modo BUSQUEDA.");
    }
}
