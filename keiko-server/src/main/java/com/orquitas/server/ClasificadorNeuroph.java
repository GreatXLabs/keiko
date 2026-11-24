package com.orquitas.server;

import org.neuroph.core.NeuralNetwork;
import java.io.File;
import java.util.logging.Logger;

/**
 * Clasificador del entorno de la Bahía de Caza mediante Redes Neuronales Neuroph 2.98.
 * Implementa la arquitectura de 2 redes especializadas:
 * 1. Red 1 (modelo_ultrasonico.nnet): 1 entrada normalizada -> 3 salidas {BLOQUEO, ESPACIO_LIBRE, SALIDA}.
 *    Se ejecuta 3 veces por ciclo (us_left, us_center, us_right) para armar el vector base [IZQ, CEN, DER].
 * 2. Red 2 (modelo_canicas.nnet): 6 entradas [us_izq, us_cen, us_der, laser_izq, laser_der, angulo_servo]
 *    -> 4 salidas {LOBITO_CENTRO, LOBITO_DER, LOBITO_IZQ, NINGUNA}.
 *    Se ejecuta 1 vez por ciclo. Si detecta canica, pisa el sector correspondiente del vector base con LOBITO.
 * 
 * También mantiene compatibilidad con modelos legacy (.nnet de 2, 4, 6, 8 o 10 entradas).
 */
public class ClasificadorNeuroph {
    private static final Logger LOGGER = Logger.getLogger(ClasificadorNeuroph.class.getName());

    public static final String[] CLASES_US_3 = { "BLOQUEO", "ESPACIO_LIBRE", "SALIDA" };
    public static final String[] CLASES_CANICAS_4 = { "LOBITO_CENTRO", "LOBITO_DER", "LOBITO_IZQ", "NINGUNA" };

    // Clases legacy
    public static final String[] CLASES_PAR_4 = { "BLOQUEO", "ESPACIO_LIBRE", "LOBITO", "SALIDA" };
    public static final String[] CLASES_5 = { "ESPACIO_LIBRE", "LOBITO", "MURO", "OBSTACULO", "SALIDA" };
    public static final String[] CLASES_7 = { "ESPACIO_LIBRE", "LOBITO_CENTRO", "LOBITO_DER", "LOBITO_IZQ", "MURO", "OBSTACULO", "SALIDA" };
    public static final String[] CLASES_9 = {
        "ESPACIO_LIBRE", "LOBITO_CENTRO", "LOBITO_DER", "LOBITO_IZQ", "MURO",
        "OBSTACULO_CENTRO", "OBSTACULO_DER", "OBSTACULO_IZQ", "SALIDA"
    };
    public static final String[] CLASES = CLASES_PAR_4;

    private NeuralNetwork<?> neuralNetUltrasonico;
    private NeuralNetwork<?> neuralNetCanicas;
    private NeuralNetwork<?> neuralNetLegacy;

    private final File archivoModelo;
    private double ultimaConfianza = 0.0;
    private double[] ultimasConfianzasUs = new double[]{0.0, 0.0, 0.0};
    private String ultimaClaseCanica = "NINGUNA";

    public ClasificadorNeuroph(File archivoModelo) {
        this.archivoModelo = archivoModelo;
        cargarModelos();
    }

    public ClasificadorNeuroph(File modeloUltrasonico, File modeloCanicas) {
        this.archivoModelo = modeloUltrasonico;
        cargarModeloEspecifico(modeloUltrasonico, true);
        cargarModeloEspecifico(modeloCanicas, false);
    }

    private void cargarModelos() {
        // 1. Intentar cargar archivo pasado
        if (archivoModelo != null && archivoModelo.exists()) {
            File dir = archivoModelo.getParentFile();
            if (archivoModelo.getName().contains("ultrasonico") || archivoModelo.getName().contains("us")) {
                cargarModeloEspecifico(archivoModelo, true);
                if (dir != null) {
                    File fCanicas = new File(dir, "modelo_canicas.nnet");
                    if (fCanicas.exists()) cargarModeloEspecifico(fCanicas, false);
                }
            } else if (archivoModelo.getName().contains("canica")) {
                cargarModeloEspecifico(archivoModelo, false);
                if (dir != null) {
                    File fUS = new File(dir, "modelo_ultrasonico.nnet");
                    if (fUS.exists()) cargarModeloEspecifico(fUS, true);
                }
            } else {
                // Modelo legacy explícito (ej. orquitas_production_champion.nnet)
                try {
                    neuralNetLegacy = NeuralNetwork.createFromFile(archivoModelo);
                    LOGGER.info("[NEUROPH LEGACY] Cargado modelo: " + archivoModelo.getName()
                            + " (in=" + neuralNetLegacy.getInputsCount() + ", out=" + neuralNetLegacy.getOutputsCount() + ")");
                } catch (Exception e) {
                    LOGGER.warning("[NEUROPH] Error al cargar modelo legacy: " + e.getMessage());
                }
            }
        }

        // 2. Si no es un modelo legacy explícito, buscar las 2 nuevas redes oficiales en data/models/
        if (neuralNetLegacy == null) {
            String[] possibleDirs = new String[]{
                "../keiko-sim/data/models",
                "keiko-sim/data/models",
                "data/models"
            };

            if (neuralNetUltrasonico == null) {
                for (String d : possibleDirs) {
                    File f = new File(d, "modelo_ultrasonico.nnet");
                    if (f.exists()) {
                        cargarModeloEspecifico(f, true);
                        break;
                    }
                }
            }

            if (neuralNetCanicas == null) {
                for (String d : possibleDirs) {
                    File f = new File(d, "modelo_canicas.nnet");
                    if (f.exists()) {
                        cargarModeloEspecifico(f, false);
                        break;
                    }
                }
            }
        }
    }

    private void cargarModeloEspecifico(File f, boolean esUltrasonico) {
        if (f == null || !f.exists()) return;
        try {
            NeuralNetwork<?> net = NeuralNetwork.createFromFile(f);
            if (esUltrasonico) {
                this.neuralNetUltrasonico = net;
                LOGGER.info("[NEUROPH RED 1] modelo_ultrasonico.nnet cargado desde: " + f.getAbsolutePath()
                        + " (in=" + net.getInputsCount() + ", out=" + net.getOutputsCount() + ")");
            } else {
                this.neuralNetCanicas = net;
                LOGGER.info("[NEUROPH RED 2] modelo_canicas.nnet cargado desde: " + f.getAbsolutePath()
                        + " (in=" + net.getInputsCount() + ", out=" + net.getOutputsCount() + ")");
            }
        } catch (Exception e) {
            LOGGER.warning("[NEUROPH] Error cargando " + f.getName() + ": " + e.getMessage());
        }
    }

    public boolean isCargado() {
        return (neuralNetUltrasonico != null) || (neuralNetLegacy != null);
    }

    public synchronized void recargarDesdeArchivos(File modeloUltrasonico, File modeloCanicas) {
        cargarModeloEspecifico(modeloUltrasonico, true);
        cargarModeloEspecifico(modeloCanicas, false);
    }

    public static double normalizarUs(double distCm) {
        return Math.min(1.0, Math.max(0.0, (distCm - 2.0) / 248.0));
    }

    public static double normalizarLaser(double distCm) {
        return Math.min(1.0, Math.max(0.0, (distCm - 2.0) / 41.0));
    }

    public static double normalizarAnguloServo(double anguloGrados) {
        return Math.min(1.0, Math.max(0.0, (anguloGrados - (-45.0)) / 105.0));
    }

    // Clasifica un sector ultrasónico con Red 1 (modelo_ultrasonico.nnet)
    public synchronized String clasificarSectorUltrasonico(double distCm) {
        if (neuralNetUltrasonico == null) {
            return "ESPACIO_LIBRE";
        }
        double norm = normalizarUs(distCm);
        neuralNetUltrasonico.setInput(norm);
        neuralNetUltrasonico.calculate();
        double[] out = neuralNetUltrasonico.getOutput();
        int maxIdx = 0;
        double maxVal = out[0];
        for (int i = 1; i < out.length; i++) {
            if (out[i] > maxVal) {
                maxVal = out[i];
                maxIdx = i;
            }
        }
        this.ultimaConfianza = maxVal;
        return CLASES_US_3[maxIdx];
    }

    private double ultimaConfianzaCanica = 0.0;

    public double getUltimaConfianzaCanica() {
        return ultimaConfianzaCanica;
    }

    // Clasifica detección de canicas con Red 2 (modelo_canicas.nnet)
    public synchronized String clasificarCanicas(VectorSensores sensores) {
        if (neuralNetCanicas == null || sensores == null || sensores.getCantidadSensores() < 6) {
            this.ultimaConfianzaCanica = 0.0;
            return "NINGUNA";
        }
        double nUsIzq = normalizarUs(sensores.getUsLeft());
        double nUsCen = normalizarUs(sensores.getUsCenter());
        double nUsDer = normalizarUs(sensores.getUsRight());
        double nLaserIzq = normalizarLaser(sensores.getLaserLeft());
        double nLaserDer = normalizarLaser(sensores.getLaserRight());
        double nServo = normalizarAnguloServo(sensores.getAnguloServo());

        neuralNetCanicas.setInput(nUsIzq, nUsCen, nUsDer, nLaserIzq, nLaserDer, nServo);
        neuralNetCanicas.calculate();
        double[] out = neuralNetCanicas.getOutput();
        int maxIdx = 0;
        double maxVal = out[0];
        for (int i = 1; i < out.length; i++) {
            if (out[i] > maxVal) {
                maxVal = out[i];
                maxIdx = i;
            }
        }
        this.ultimaConfianzaCanica = maxVal;
        this.ultimaClaseCanica = CLASES_CANICAS_4[maxIdx];
        return this.ultimaClaseCanica;
    }

    // Evalúa un par coaxial individual (para compatibilidad con tests legacy y modelos de 2 entradas)
    public synchronized String clasificarPar(double dLowCm, double dHighCm) {
        if (Double.isNaN(dLowCm) || Double.isNaN(dHighCm)
                || Double.isInfinite(dLowCm) || Double.isInfinite(dHighCm)
                || dLowCm < 0.0 || dLowCm > 400.0 || dHighCm < 0.0 || dHighCm > 400.0) {
            this.ultimaConfianza = 0.0;
            return "ESPACIO_LIBRE";
        }
        if (neuralNetLegacy != null) {
            return clasificarParLegacy(dLowCm, dHighCm);
        }
        if (neuralNetUltrasonico != null) {
            return clasificarSectorUltrasonico(dLowCm);
        }
        throw new IllegalStateException("Red neuronal Neuroph 2.98 no cargada; prohibido clasificar con umbrales fijos.");
    }

    // Clasificación de los 3 sectores [IZQ, CEN, DER] combinando las 2 redes
    public synchronized String[] clasificarPares(VectorSensores sensores) {
        return clasificarPares(sensores, true);
    }

    /** Clasifica [IZQ, CEN, DER]; cuando evaluarCanicas es false (ej. ESCAPE), omite Red 2 y no inyecta LOBITO. */
    public synchronized String[] clasificarPares(VectorSensores sensores, boolean evaluarCanicas) {
        if (sensores == null || !sensores.isValido()) {
            this.ultimaConfianza = 0.0;
            return new String[]{"ESPACIO_LIBRE", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
        }

        // Si tenemos modelo legacy explícito activo
        if (neuralNetLegacy != null) {
            if (neuralNetLegacy.getInputsCount() == 2) {
                String cIzq = clasificarParLegacy(sensores.getDLowLeft(), sensores.getDHighLeft());
                double confIzq = this.ultimaConfianza;
                String cCen = clasificarParLegacy(sensores.getDLowCenter(), sensores.getDHighCenter());
                double confCen = this.ultimaConfianza;
                String cDer = clasificarParLegacy(sensores.getDLowRight(), sensores.getDHighRight());
                double confDer = this.ultimaConfianza;
                this.ultimasConfianzasUs = new double[]{confIzq, confCen, confDer};
                this.ultimaClaseCanica = "NINGUNA";
                this.ultimaConfianzaCanica = 0.0;
                return new String[]{cIzq, cCen, cDer};
            } else {
                String global = clasificarGlobalLegacy(sensores);
                this.ultimasConfianzasUs = new double[]{this.ultimaConfianza, this.ultimaConfianza, this.ultimaConfianza};
                this.ultimaClaseCanica = "NINGUNA";
                this.ultimaConfianzaCanica = 0.0;
                return traducirGlobalATerna(global);
            }
        }

        // Sistema oficial: Red 1 (3US) + Red 2 (2Laser/Servo)
        if (neuralNetUltrasonico != null && neuralNetUltrasonico.getInputsCount() == 1) {
            String cIzq = clasificarSectorUltrasonico(sensores.getUsLeft());
            double confIzq = this.ultimaConfianza;
            String cCen = clasificarSectorUltrasonico(sensores.getUsCenter());
            double confCen = this.ultimaConfianza;
            String cDer = clasificarSectorUltrasonico(sensores.getUsRight());
            double confDer = this.ultimaConfianza;
            this.ultimasConfianzasUs = new double[]{confIzq, confCen, confDer};
            this.ultimaConfianza = (confIzq + confCen + confDer) / 3.0;
            this.ultimaClaseCanica = "NINGUNA";
            this.ultimaConfianzaCanica = 0.0;

            String[] terna = new String[]{cIzq, cCen, cDer};

            // Fusión con Red 2 (modelo_canicas.nnet): omitida en ESCAPE; en BUSQUEDA una canica
            // solo se confirma si Red 1 no clasificó BLOQUEO ni SALIDA en ese sector
            if (evaluarCanicas && neuralNetCanicas != null && sensores.getCantidadSensores() >= 6) {
                String marbleResult = clasificarCanicas(sensores);
                if ("LOBITO_CENTRO".equals(marbleResult)) {
                    if (!"BLOQUEO".equals(terna[1]) && !"SALIDA".equals(terna[1])) {
                        terna[1] = "LOBITO";
                    }
                } else if ("LOBITO_IZQ".equals(marbleResult)) {
                    if (!"BLOQUEO".equals(terna[0]) && !"SALIDA".equals(terna[0])) {
                        terna[0] = "LOBITO";
                    }
                } else if ("LOBITO_DER".equals(marbleResult)) {
                    if (!"BLOQUEO".equals(terna[2]) && !"SALIDA".equals(terna[2])) {
                        terna[2] = "LOBITO";
                    }
                }
            }
            return terna;
        }

        throw new IllegalStateException("Red neuronal Neuroph 2.98 no cargada; prohibido clasificar con umbrales fijos.");
    }

    private String clasificarParLegacy(double dLowCm, double dHighCm) {
        double span = VectorSensores.MAX_RANGO_CM - VectorSensores.MIN_RANGO_CM;
        double nLow = Math.min(1.0, Math.max(0.0, (dLowCm - VectorSensores.MIN_RANGO_CM) / span));
        double nHigh = Math.min(1.0, Math.max(0.0, (dHighCm - VectorSensores.MIN_RANGO_CM) / span));
        neuralNetLegacy.setInput(nLow, nHigh);
        neuralNetLegacy.calculate();
        double[] salida = neuralNetLegacy.getOutput();
        int maxIdx = 0;
        double maxVal = salida[0];
        for (int i = 1; i < salida.length; i++) {
            if (salida[i] > maxVal) { maxVal = salida[i]; maxIdx = i; }
        }
        this.ultimaConfianza = maxVal;
        return CLASES_PAR_4[maxIdx];
    }

    private String clasificarGlobalLegacy(VectorSensores sensores) {
        double[] norm = sensores.toArrayNormalizado();
        int inputsRequired = neuralNetLegacy.getInputsCount();
        double[] entrada = new double[inputsRequired];
        for (int i = 0; i < inputsRequired; i++) {
            entrada[i] = (i < norm.length) ? norm[i] : 0.0;
        }
        neuralNetLegacy.setInput(entrada);
        neuralNetLegacy.calculate();
        double[] salida = neuralNetLegacy.getOutput();
        int maxIdx = 0;
        double maxVal = salida[0];
        for (int i = 1; i < salida.length; i++) {
            if (salida[i] > maxVal) { maxVal = salida[i]; maxIdx = i; }
        }
        this.ultimaConfianza = maxVal;
        String[] clases = (salida.length == 5) ? CLASES_5 : ((salida.length == 7) ? CLASES_7 : CLASES_9);
        return clases[maxIdx];
    }

    private String[] traducirGlobalATerna(String global) {
        if (global == null) return new String[]{"ESPACIO_LIBRE", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
        switch (global) {
            case "LOBITO_IZQ": return new String[]{"LOBITO", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
            case "LOBITO_CENTRO": return new String[]{"ESPACIO_LIBRE", "LOBITO", "ESPACIO_LIBRE"};
            case "LOBITO_DER": return new String[]{"ESPACIO_LIBRE", "LOBITO", "LOBITO"};
            case "SALIDA": return new String[]{"SALIDA", "SALIDA", "SALIDA"};
            case "MURO":
            case "OBSTACULO": return new String[]{"BLOQUEO", "BLOQUEO", "BLOQUEO"};
            default: return new String[]{"ESPACIO_LIBRE", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
        }
    }

    public synchronized String clasificar(VectorSensores sensores) {
        String[] pares = clasificarPares(sensores);
        return sintetizarEtiquetaGlobal(pares);
    }

    public static String sintetizarEtiquetaGlobal(String[] pares) {
        if (pares == null || pares.length < 3) return "ESPACIO_LIBRE";
        String izq = pares[0], cen = pares[1], der = pares[2];
        if ("LOBITO".equals(cen)) return "LOBITO_CENTRO";
        if ("LOBITO".equals(izq)) return "LOBITO_IZQ";
        if ("LOBITO".equals(der)) return "LOBITO_DER";
        if ("SALIDA".equals(cen) || "SALIDA".equals(izq) || "SALIDA".equals(der)) return "SALIDA";
        if ("BLOQUEO".equals(cen) && "BLOQUEO".equals(izq) && "BLOQUEO".equals(der)) return "OBSTACULO";
        return "ESPACIO_LIBRE";
    }

    public double getUltimaConfianza() {
        return ultimaConfianza;
    }

    public synchronized double[] getUltimasConfianzasUs() {
        return new double[]{ultimasConfianzasUs[0], ultimasConfianzasUs[1], ultimasConfianzasUs[2]};
    }

    public synchronized String getUltimaClaseCanica() {
        return ultimaClaseCanica;
    }

    public boolean isRedUltrasonicoCargada() {
        return neuralNetUltrasonico != null;
    }

    public boolean isRedCanicasCargada() {
        return neuralNetCanicas != null;
    }

    public boolean isRedLegacyCargada() {
        return neuralNetLegacy != null;
    }

    public NeuralNetwork<?> getNeuralNetUltrasonico() {
        return neuralNetUltrasonico;
    }

    public NeuralNetwork<?> getNeuralNetCanicas() {
        return neuralNetCanicas;
    }
}
