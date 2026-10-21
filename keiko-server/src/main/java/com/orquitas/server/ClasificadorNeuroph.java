package com.orquitas.server;

import org.neuroph.core.NeuralNetwork;
import java.io.File;
import java.util.logging.Logger;

/**
 * Clasificador del entorno de la Bahia de Caza mediante Red Neuronal Neuroph 2.98.
 * Soporta modelos de 5, 7 o 9 clases entrenados con el orden alfabetico de DatasetParser
 * y vectores de 6 sensores frontales (3 bajos + 3 altos) o 4/8/10 sensores.
 */
public class ClasificadorNeuroph {
    private static final Logger LOGGER = Logger.getLogger(ClasificadorNeuroph.class.getName());

    // Orden alfabetico exacto generado por DatasetParser en neuroph-train
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
    public static final String[] CLASES = CLASES_5;

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

    // RF-06: Reconocer tipo de elemento detectado exclusivamente mediante Neuroph 2.98.
    public synchronized String clasificar(VectorSensores sensores) {
        if (sensores == null || !sensores.isValido()) {
            this.ultimaConfianza = 0.0;
            return "ESPACIO_LIBRE";
        }
        if (neuralNetwork == null) {
            throw new IllegalStateException("Red neuronal Neuroph 2.98 no cargada; prohibido clasificar con umbrales fijos.");
        }

        double[] norm = sensores.toArrayNormalizado();
        int inputsRequired = neuralNetwork.getInputsCount();
        double[] entrada = adaptarDimensionEntrada(norm, inputsRequired);

        neuralNetwork.setInput(entrada);
        neuralNetwork.calculate();
        double[] salida = neuralNetwork.getOutput();
        return argMaxClase(salida);
    }

    // Adapta el vector normalizado al numero de neuronas de entrada de la red cargada.
    private double[] adaptarDimensionEntrada(double[] norm, int inputsRequired) {
        if (norm.length == inputsRequired) {
            return norm;
        }
        if (inputsRequired == 6 && norm.length == 4) {
            // Interpolar sensores centrales a partir de los pares laterales (LL, LR, HL, HR)
            double ll = norm[0], lr = norm[1], hl = norm[2], hr = norm[3];
            return new double[]{ll, 0.5 * (ll + lr), lr, hl, 0.5 * (hl + hr), hr};
        }
        if (inputsRequired == 4 && norm.length >= 6) {
            // Tomar extremos izquierdo y derecho de los planos bajo y alto
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
