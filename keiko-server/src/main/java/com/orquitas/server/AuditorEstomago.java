package com.orquitas.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Hilo Auditor Independiente ("La Linareada").
 * 
 * Sondea periodicamente estomago.txt de manera concurrente.
 * Al validar la presencia de 3 registros fisicos en disco, activa la transicion al Modo Escape.
 */
public class AuditorEstomago extends Thread {
    private static final Logger LOGGER = Logger.getLogger(AuditorEstomago.class.getName());

    private final GestorEstomago gestorEstomago;
    private final int intervaloMs;
    private final int capturasRequeridas;
    private final List<OyenteAuditor> oyentes = new ArrayList<>();
    private final AtomicBoolean corriendo = new AtomicBoolean(true);
    private final AtomicBoolean modoEscapeActivado = new AtomicBoolean(false);
    private int ultimasCapturasAuditadas = 0;

    // Constructor con valores por defecto de sondeo y capturas.
    public AuditorEstomago(GestorEstomago gestor) {
        this(gestor, ConfiguracionServidor.AUDITOR_SONDEO_MS, ConfiguracionServidor.CAPTURAS_REQUERIDAS_ESCAPE);
    }

    // Constructor con parametros especificos de intervalo y meta de capturas.
    public AuditorEstomago(GestorEstomago gestor, int intervaloMs, int capturasRequeridas) {
        super("AuditorEstomago-Linareada");
        this.gestorEstomago = gestor;
        this.intervaloMs = intervaloMs;
        this.capturasRequeridas = capturasRequeridas;
        setDaemon(true);
    }

    // Registra un oyente de eventos de auditoria.
    public void agregarOyente(OyenteAuditor oyente) {
        if (oyente != null) oyentes.add(oyente);
    }

    // Inicia el hilo auditor en segundo plano.
    public void iniciar() {
        start();
    }

    // Detiene la ejecucion del hilo auditor e interrumpe el ciclo de sondeo.
    public void detener() {
        corriendo.set(false);
        interrupt();
    }

    // RF-04: Contar capturas registradas periodicamente en estomago.txt y activar escape.
    @Override
    public void run() {
        LOGGER.info(() -> String.format("[HILO AUDITOR INICIADO] Supervisando estomago.txt cada %d ms. Meta: %d capturas.",
                intervaloMs, capturasRequeridas));

        while (corriendo.get() && !Thread.currentThread().isInterrupted()) {
            try {
                int capturasFisicas = gestorEstomago.contarCapturasRegistradas();

                if (capturasFisicas != ultimasCapturasAuditadas) {
                    ultimasCapturasAuditadas = capturasFisicas;
                    LOGGER.info(() -> String.format("[AUDITOR] Inspeccion de disco completada: %d capturas fisicas validadas.", capturasFisicas));
                    notificarCapturaAuditada(capturasFisicas);
                }

                if (capturasFisicas >= capturasRequeridas && modoEscapeActivado.compareAndSet(false, true)) {
                    List<RegistroCaptura> capturas = gestorEstomago.obtenerCapturasValidas();
                    LOGGER.info(() -> String.format(
                            "\n=================================================================\n" +
                            "[AUDITOR] OBJETIVO DE CAPTURAS ALCANZADO (%d/%d)\n" +
                            "  Registros en 'estomago.txt': %d capturas validadas en almacenamiento fisico.\n" +
                            "  Transicion formal: ACTIVANDO MODO ESCAPE HACIA LA SALIDA.\n" +
                            "=================================================================\n",
                            capturasFisicas, capturasRequeridas, capturasFisicas));
                    notificarModoEscape(capturas);
                }

                Thread.sleep(intervaloMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.warning("[AUDITOR] Error en ciclo de auditoria: " + e.getMessage());
            }
        }
        LOGGER.info("[HILO AUDITOR FINALIZADO]");
    }

    // Notifica a los listeners registrados el conteo actual de capturas auditadas.
    private void notificarCapturaAuditada(int total) {
        for (OyenteAuditor l : oyentes) {
            try {
                l.onCapturaAuditada(total);
            } catch (Exception ignored) {}
        }
    }

    // Notifica a los listeners registrados la activacion del modo escape.
    private void notificarModoEscape(List<RegistroCaptura> caps) {
        for (OyenteAuditor l : oyentes) {
            try {
                l.onModoEscapeActivado(caps);
            } catch (Exception ignored) {}
        }
    }

    // Retorna verdadero si el auditor ya activo el modo escape.
    public boolean isModoEscapeActivado() {
        return modoEscapeActivado.get();
    }
}
