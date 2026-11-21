package com.orquitas.server;

/**
 * Hilo Lector que audita periodicamente estomago.txt (RF-07) y dispara el cambio a ESCAPE (RF-08).
 */
public class AuditorEstomago extends Thread {
    private final GestorEstomago gestorEstomago;
    private final PlanificadorNavegacion planificador;
    private final ReceptorConexiones receptor;
    private final int intervaloMs;
    private final int capturasRequeridas;
    private boolean escapeActivado = false;

    public AuditorEstomago(GestorEstomago gestorEstomago,
                           PlanificadorNavegacion planificador,
                           ReceptorConexiones receptor,
                           int intervaloMs,
                           int capturasRequeridas) {
        super("AuditorEstomago-HiloLector");
        this.gestorEstomago = gestorEstomago;
        this.planificador = planificador;
        this.receptor = receptor;
        this.intervaloMs = intervaloMs;
        this.capturasRequeridas = capturasRequeridas;
    }

    public void iniciar() {
        setDaemon(true);
        start();
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            int totalFisico = gestorEstomago.contarCapturasRegistradas();
            if (!escapeActivado && totalFisico >= capturasRequeridas) {
                cambiarAEscape();
            }
            try {
                Thread.sleep(intervaloMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void cambiarAEscape() {
        escapeActivado = true;
        planificador.activarModoEscape();
        receptor.transmitirATodos(MensajeProtocolo.crearModoEscape());
    }

    public void detener() {
        interrupt();
    }
}
