package com.orquitas.server;

import java.util.List;

/**
 * Interfaz de eventos disparados por el Hilo Auditor al supervisar estomago.txt.
 */
public interface OyenteAuditor {
    // Invocado cuando se audita un cambio en el numero de capturas.
    void onCapturaAuditada(int total);

    // Invocado al verificar la meta de capturas para iniciar escape.
    void onModoEscapeActivado(List<RegistroCaptura> capturas);
}
