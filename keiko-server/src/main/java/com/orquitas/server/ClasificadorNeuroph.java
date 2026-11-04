package com.orquitas.server;

import org.neuroph.core.NeuralNetwork;

import java.io.File;
import java.io.InputStream;
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
 * Soporta carga desde disco y fallback automático desde recursos embebidos en el JAR.
 */
public class ClasificadorNeuroph {
    private static final Logger LOGGER = Logger.getLogger(ClasificadorNeuroph.class.getName());

    public static final String[] CLASES_US_3 = { "BLOQUEO", "ESPACIO_LIBRE", "SALIDA" };
    public static final String[] CLASES_CANICAS_4 = { "LOBITO_CENTRO", "LOBITO_DER", "LOBITO_IZQ", "NINGUNA" };

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
        if (archivoModelo != null && archivoModelo.exists()) {
            File dir = archivoModelo.getParentFile();
            String nombre = archivoModelo.getName().toLowerCase();
            if (nombre.contains("ultrasonico") || nombre.contains("us")) {
                cargarModeloEspecifico(archivoModelo, true);
                if (dir != null) {
                    File fCanicas = new File(dir, "modelo_canicas.nnet");
                    if (fCanicas.exists()) cargarModeloEspecifico(fCanicas, false);
                }
            } else if (nombre.contains("canica")) {
                cargarModeloEspecifico(archivoModelo, false);
                if (dir != null) {
                    File fUS = new File(dir, "modelo_ultrasonico.nnet");
                    if (fUS.exists()) cargarModeloEspecifico(fUS, true);
                }
            } else {
                try {
                    neuralNetLegacy = NeuralNetwork.createFromFile(archivoModelo);
                    LOGGER.info("[NEUROPH LEGACY] Cargado modelo: " + archivoModelo.getName()
                            + " (in=" + neuralNetLegacy.getInputsCount() + ", out=" + neuralNetLegacy.getOutputsCount() + ")");
                } catch (Exception e) {
                    LOGGER.warning("[NEUROPH] Error al cargar modelo legacy: " + e.getMessage());
                }
            }
        }

        if (neuralNetLegacy == null) {
            String[] directoriosCandidatos = new String[]{
                "models",
                "keiko-server/models",
                "../keiko-sim/data/models",
                "keiko-sim/data/models",
                "data/models"
            };

            if (neuralNetUltrasonico == null) {
                for (String d : directoriosCandidatos) {
                    File f = new File(d, "modelo_ultrasonico.nnet");
                    if (f.exists()) {
                        cargarModeloEspecifico(f, true);
                        break;
                    }
                }
            }
            if (neuralNetUltrasonico == null) {
                cargarDesdeRecursoClasspath("models/modelo_ultrasonico.nnet", true);
            }

            if (neuralNetCanicas == null) {
                for (String d : directoriosCandidatos) {
                    File f = new File(d, "modelo_canicas.nnet");
                    if (f.exists()) {
                        cargarModeloEspecifico(f, false);
                        break;
                    }
                }
            }
            if (neuralNetCanicas == null) {
                cargarDesdeRecursoClasspath("models/modelo_canicas.nnet", false);
            }
        }
    }

    private void cargarModeloEspecifico(File f, boolean esUltrasonico) {
        if (f == null || !f.exists()) return;
        try {
            NeuralNetwork<?> net = NeuralNetwork.createFromFile(f);
            asignarRed(net, esUltrasonico, f.getPath());
        } catch (Exception e) {
            LOGGER.warning("[NEUROPH] Error cargando " + f.getName() + ": " + e.getMessage());
        }
    }

    private void cargarDesdeRecursoClasspath(String rutaRecurso, boolean esUltrasonico) {
        try (InputStream is = ClasificadorNeuroph.class.getClassLoader().getResourceAsStream(rutaRecurso)) {
            if (is == null) return;
            NeuralNetwork<?> net = NeuralNetwork.load(is);
            asignarRed(net, esUltrasonico, "classpath:" + rutaRecurso);
        } catch (Exception e) {
            LOGGER.warning("[NEUROPH] Error cargando recurso embebido " + rutaRecurso + ": " + e.getMessage());
        }
    }

    private void asignarRed(NeuralNetwork<?> net, boolean esUltrasonico, String origen) {
        if (net == null) return;
        if (esUltrasonico) {
            this.neuralNetUltrasonico = net;
            LOGGER.info("[NEUROPH RED 1] modelo_ultrasonico.nnet cargado desde " + origen
                    + " (in=" + net.getInputsCount() + ", out=" + net.getOutputsCount() + ")");
        } else {
            this.neuralNetCanicas = net;
            LOGGER.info("[NEUROPH RED 2] modelo_canicas.nnet cargado desde " + origen
                    + " (in=" + net.getInputsCount() + ", out=" + net.getOutputsCount() + ")");
        }
    }

    public boolean isCargado() {
        return (neuralNetUltrasonico != null) || (neuralNetLegacy != null);
    }

    public synchronized String clasificarSectorUltrasonico(double distCm) {
        if (neuralNetUltrasonico == null) {
            return "ESPACIO_LIBRE";
        }
        double norm = Math.min(1.0, Math.max(0.0, (distCm - 2.0) / 248.0));
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

    public synchronized String clasificarCanicas(VectorSensores sensores) {
        if (neuralNetCanicas == null || sensores == null || sensores.getCantidadSensores() < 6) {
            return "NINGUNA";
        }
        double nUsIzq = Math.min(1.0, Math.max(0.0, (sensores.getUsLeft() - 2.0) / 248.0));
        double nUsCen = Math.min(1.0, Math.max(0.0, (sensores.getUsCenter() - 2.0) / 248.0));
        double nUsDer = Math.min(1.0, Math.max(0.0, (sensores.getUsRight() - 2.0) / 248.0));
        double nLaserIzq = Math.min(1.0, Math.max(0.0, (sensores.getLaserLeft() - 2.0) / 40.0));
        double nLaserDer = Math.min(1.0, Math.max(0.0, (sensores.getLaserRight() - 2.0) / 40.0));
        double nServo = Math.min(1.0, Math.max(0.0, (sensores.getAnguloServo() - (-45.0)) / 105.0));

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
        return CLASES_CANICAS_4[maxIdx];
    }

    public synchronized String clasificarPar(double dLowCm, double dHighCm) {
        if (Double.isNaN(dLowCm) || Double.isNaN(dHighCm)
                || Double.isInfinite(dLowCm) || Double.isInfinite(dHighCm)
                || dLowCm < 0.0 || dLowCm > VectorSensores.MAX_LECTURA_VALIDA_CM
                || dHighCm < 0.0 || dHighCm > VectorSensores.MAX_LECTURA_VALIDA_CM) {
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

    public synchronized String[] clasificarPares(VectorSensores sensores) {
        if (sensores == null || !sensores.isValido()) {
            this.ultimaConfianza = 0.0;
            return new String[]{"ESPACIO_LIBRE", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
        }

        if (neuralNetLegacy != null) {
            if (neuralNetLegacy.getInputsCount() == 2) {
                String cIzq = clasificarParLegacy(sensores.getDLowLeft(), sensores.getDHighLeft());
                String cCen = clasificarParLegacy(sensores.getDLowCenter(), sensores.getDHighCenter());
                String cDer = clasificarParLegacy(sensores.getDLowRight(), sensores.getDHighRight());
                return new String[]{cIzq, cCen, cDer};
            } else {
                String global = clasificarGlobalLegacy(sensores);
                return traducirGlobalATerna(global);
            }
        }

        if (neuralNetUltrasonico != null && neuralNetUltrasonico.getInputsCount() == 1) {
            String cIzq = clasificarSectorUltrasonico(sensores.getUsLeft());
            double confIzq = this.ultimaConfianza;
            String cCen = clasificarSectorUltrasonico(sensores.getUsCenter());
            double confCen = this.ultimaConfianza;
            String cDer = clasificarSectorUltrasonico(sensores.getUsRight());
            double confDer = this.ultimaConfianza;
            this.ultimaConfianza = (confIzq + confCen + confDer) / 3.0;

            String[] terna = new String[]{cIzq, cCen, cDer};

            if (neuralNetCanicas != null && sensores.getCantidadSensores() >= 6) {
                String marbleResult = clasificarCanicas(sensores);
                if ("LOBITO_CENTRO".equals(marbleResult) && !"BLOQUEO".equals(terna[1])) {
                    terna[1] = "LOBITO";
                } else if ("LOBITO_IZQ".equals(marbleResult) && !"BLOQUEO".equals(terna[0])) {
                    terna[0] = "LOBITO";
                } else if ("LOBITO_DER".equals(marbleResult) && !"BLOQUEO".equals(terna[2])) {
                    terna[2] = "LOBITO";
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

    public NeuralNetwork<?> getNeuralNetUltrasonico() {
        return neuralNetUltrasonico;
    }

    public NeuralNetwork<?> getNeuralNetCanicas() {
        return neuralNetCanicas;
    }
}
