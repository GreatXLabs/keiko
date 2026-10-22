package com.orquitas.server;

import org.neuroph.core.NeuralNetwork;
import java.io.File;
import java.util.logging.Logger;

/**
 * Clasificador del entorno de la Bahia de Caza mediante Red Neuronal Neuroph 2.98.
 * Soporta como arquitectura principal la evaluacion por par coaxial (d_bajo, d_alto -> 4 clases:
 * BLOQUEO, ESPACIO_LIBRE, LOBITO, SALIDA) reutilizando la misma red para los 3 sectores
 * (IZQ, CENTRO, DER), ademas de compatibilidad con modelos de 5, 7 o 9 salidas.
 */
public class ClasificadorNeuroph {
    private static final Logger LOGGER = Logger.getLogger(ClasificadorNeuroph.class.getName());

    // Orden alfabetico exacto generado por DatasetParser en neuroph-train para par coaxial (2 entradas -> 4 salidas)
    public static final String[] CLASES_PAR_4 = {
        "BLOQUEO", "ESPACIO_LIBRE", "LOBITO", "SALIDA"
    };

    // Orden alfabetico para modelos globales de 5, 7 y 9 clases
    public static final String[] CLASES_5 = {
        "ESPACIO_LIBRE", "LOBITO", "MURO", "OBSTACULO", "SALIDA"
    };
    public static final String[] CLASES_7 = {
        "ESPACIO_LIBRE", "LOBITO_CENTRO", "LOBITO_DER", "LOBITO_IZQ", "MURO", "OBSTACULO", "SALIDA"
    };
    public static final String[] CLASES_9 = {
        "ESPACIO_LIBRE", "LOBITO_CENTRO", "LOBITO_DER", "LOBITO_IZQ", "MURO",
        "OBSTACULO_CENTRO", "OBSTACULO_DER", "OBSTACULO_IZQ", "SALIDA"
    };
    public static final String[] CLASES = CLASES_PAR_4;

    private NeuralNetwork<?> neuralNetwork;
    private final File archivoModelo;
    private double ultimaConfianza = 0.0;

    // Inicializa el clasificador con el archivo de la red neuronal.
    public ClasificadorNeuroph(File archivoModelo) {
        this.archivoModelo = archivoModelo;
        cargarModelo();
    }

    // Carga la red neuronal desde el archivo .nnet especificado.
    private void cargarModelo() {
        if (archivoModelo != null && archivoModelo.exists()) {
            try {
                this.neuralNetwork = NeuralNetwork.createFromFile(archivoModelo);
                LOGGER.info("Red neuronal Neuroph 2.98 cargada desde: " + archivoModelo.getAbsolutePath()
                        + " (entradas=" + neuralNetwork.getInputsCount()
                        + ", salidas=" + neuralNetwork.getOutputsCount() + ")");
            } catch (Exception e) {
                LOGGER.warning("No se pudo cargar modelo Neuroph: " + e.getMessage());
            }
        }
    }

    // Evalua un par coaxial individual (d_bajo, d_alto en cm) con la red Neuroph 2.98.
    public synchronized String clasificarPar(double dLowCm, double dHighCm) {
        if (Double.isNaN(dLowCm) || Double.isNaN(dHighCm)
                || Double.isInfinite(dLowCm) || Double.isInfinite(dHighCm)
                || dLowCm < 0.0 || dLowCm > 400.0 || dHighCm < 0.0 || dHighCm > 400.0) {
            this.ultimaConfianza = 0.0;
            return "ESPACIO_LIBRE";
        }
        if (neuralNetwork == null) {
            throw new IllegalStateException("Red neuronal Neuroph 2.98 no cargada; prohibido clasificar con umbrales fijos.");
        }

        double span = VectorSensores.MAX_RANGO_CM - VectorSensores.MIN_RANGO_CM;
        double nLow = Math.min(1.0, Math.max(0.0, (dLowCm - VectorSensores.MIN_RANGO_CM) / span));
        double nHigh = Math.min(1.0, Math.max(0.0, (dHighCm - VectorSensores.MIN_RANGO_CM) / span));

        if (neuralNetwork.getInputsCount() == 2) {
            neuralNetwork.setInput(nLow, nHigh);
        } else {
            double[] replica = adaptarDimensionEntrada(new double[]{nLow, nLow, nLow, nHigh, nHigh, nHigh}, neuralNetwork.getInputsCount());
            neuralNetwork.setInput(replica);
        }
        neuralNetwork.calculate();
        double[] salida = neuralNetwork.getOutput();
        return argMaxClase(salida);
    }

    // Evalua los 3 pares frontales [IZQ, CENTRO, DER] usando la misma red neuronal Neuroph 2.98.
    public synchronized String[] clasificarPares(VectorSensores sensores) {
        if (sensores == null || !sensores.isValido()) {
            this.ultimaConfianza = 0.0;
            return new String[]{"ESPACIO_LIBRE", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
        }
        if (neuralNetwork == null) {
            throw new IllegalStateException("Red neuronal Neuroph 2.98 no cargada; prohibido clasificar con umbrales fijos.");
        }

        if (neuralNetwork.getInputsCount() == 2) {
            String cIzq = clasificarPar(sensores.getDLowLeft(), sensores.getDHighLeft());
            double confIzq = this.ultimaConfianza;
            String cCen = clasificarPar(sensores.getDLowCenter(), sensores.getDHighCenter());
            double confCen = this.ultimaConfianza;
            String cDer = clasificarPar(sensores.getDLowRight(), sensores.getDHighRight());
            double confDer = this.ultimaConfianza;
            this.ultimaConfianza = (confIzq + confCen + confDer) / 3.0;
            return new String[]{cIzq, cCen, cDer};
        }

        // Si se cargo un modelo global de 4/6/8/10 entradas, traducir su clase global a terna [IZQ, CEN, DER]
        String global = clasificarGlobal(sensores);
        return traducirGlobalATerna(global);
    }

    // RF-06: Reconocer tipo de elemento detectado mediante Neuroph 2.98.
    public synchronized String clasificar(VectorSensores sensores) {
        if (sensores == null || !sensores.isValido()) {
            this.ultimaConfianza = 0.0;
            return "ESPACIO_LIBRE";
        }
        if (neuralNetwork == null) {
            throw new IllegalStateException("Red neuronal Neuroph 2.98 no cargada; prohibido clasificar con umbrales fijos.");
        }
        if (neuralNetwork.getInputsCount() == 2) {
            String[] pares = clasificarPares(sensores);
            return sintetizarEtiquetaGlobal(pares);
        }
        return clasificarGlobal(sensores);
    }

    private String clasificarGlobal(VectorSensores sensores) {
        double[] norm = sensores.toArrayNormalizado();
        int inputsRequired = neuralNetwork.getInputsCount();
        double[] entrada = adaptarDimensionEntrada(norm, inputsRequired);

        neuralNetwork.setInput(entrada);
        neuralNetwork.calculate();
        double[] salida = neuralNetwork.getOutput();
        return argMaxClase(salida);
    }

    // Sintetiza una etiqueta descriptiva a partir del vector neuronal [IZQ, CENTRO, DER].
    public static String sintetizarEtiquetaGlobal(String[] pares) {
        if (pares == null || pares.length < 3) return "ESPACIO_LIBRE";
        String izq = pares[0], cen = pares[1], der = pares[2];

        if ("LOBITO".equals(cen)) return "LOBITO_CENTRO";
        if ("LOBITO".equals(izq)) return "LOBITO_IZQ";
        if ("LOBITO".equals(der)) return "LOBITO_DER";

        if ("SALIDA".equals(cen) || "SALIDA".equals(izq) || "SALIDA".equals(der)) {
            return "SALIDA";
        }

        boolean bIzq = "BLOQUEO".equals(izq);
        boolean bCen = "BLOQUEO".equals(cen);
        boolean bDer = "BLOQUEO".equals(der);

        if (bIzq && bCen && bDer) return "MURO";
        if ((bIzq && bCen) || (bCen && bDer) || (bIzq && bDer)) return "MURO";
        if (bCen) return "OBSTACULO_CENTRO";
        if (bIzq) return "OBSTACULO_IZQ";
        if (bDer) return "OBSTACULO_DER";

        return "ESPACIO_LIBRE";
    }

    private static String[] traducirGlobalATerna(String global) {
        if (global == null) return new String[]{"ESPACIO_LIBRE", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
        switch (global) {
            case "LOBITO_IZQ": return new String[]{"LOBITO", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
            case "LOBITO_DER": return new String[]{"ESPACIO_LIBRE", "ESPACIO_LIBRE", "LOBITO"};
            case "LOBITO_CENTRO":
            case "LOBITO": return new String[]{"ESPACIO_LIBRE", "LOBITO", "ESPACIO_LIBRE"};
            case "OBSTACULO_IZQ": return new String[]{"BLOQUEO", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
            case "OBSTACULO_DER": return new String[]{"ESPACIO_LIBRE", "ESPACIO_LIBRE", "BLOQUEO"};
            case "OBSTACULO_CENTRO":
            case "OBSTACULO": return new String[]{"ESPACIO_LIBRE", "BLOQUEO", "ESPACIO_LIBRE"};
            case "MURO": return new String[]{"BLOQUEO", "BLOQUEO", "BLOQUEO"};
            case "SALIDA": return new String[]{"ESPACIO_LIBRE", "SALIDA", "ESPACIO_LIBRE"};
            default: return new String[]{"ESPACIO_LIBRE", "ESPACIO_LIBRE", "ESPACIO_LIBRE"};
        }
    }

    // Adapta el vector normalizado al numero de neuronas de entrada de la red cargada.
    private double[] adaptarDimensionEntrada(double[] norm, int inputsRequired) {
        if (norm.length == inputsRequired) {
            return norm;
        }
        if (inputsRequired == 6 && norm.length == 4) {
            double ll = norm[0], lr = norm[1], hl = norm[2], hr = norm[3];
            return new double[]{ll, 0.5 * (ll + lr), lr, hl, 0.5 * (hl + hr), hr};
        }
        if (inputsRequired == 4 && norm.length >= 6) {
            return new double[]{norm[0], norm[2], norm[3], norm[5]};
        }
        double[] adaptado = new double[inputsRequired];
        for (int i = 0; i < inputsRequired; i++) {
            adaptado[i] = (i < norm.length) ? norm[i] : 1.0;
        }
        return adaptado;
    }

    // Obtiene la clase con mayor activacion en la capa de salida de Neuroph.
    private String argMaxClase(double[] salida) {
        String[] etiquetas = obtenerEtiquetasPorSalidas(salida.length);
        int bestIdx = 0;
        double maxVal = salida[0];
        double sumExp = 0.0;
        for (int i = 0; i < salida.length && i < etiquetas.length; i++) {
            if (salida[i] > maxVal) {
                maxVal = salida[i];
                bestIdx = i;
            }
            sumExp += Math.exp(salida[i]);
        }
        this.ultimaConfianza = (sumExp > 0.0) ? Math.max(maxVal, Math.exp(maxVal) / sumExp) : maxVal;
        return etiquetas[bestIdx];
    }

    // Selecciona el arreglo de etiquetas alfabeticas correspondiente al numero de neuronas de salida.
    public static String[] obtenerEtiquetasPorSalidas(int numOutputs) {
        if (numOutputs == 4) return CLASES_PAR_4;
        if (numOutputs == 9) return CLASES_9;
        if (numOutputs == 7) return CLASES_7;
        return CLASES_5;
    }

    // Retorna el nivel de activacion/confianza de la ultima inferencia neuronal.
    public double getUltimaConfianza() {
        return ultimaConfianza;
    }

    // Retorna verdadero si la red neuronal esta cargada en memoria.
    public boolean isCargado() {
        return neuralNetwork != null;
    }
}
