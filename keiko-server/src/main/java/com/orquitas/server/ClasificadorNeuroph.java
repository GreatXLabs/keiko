package com.orquitas.server;

import org.neuroph.core.NeuralNetwork;
import java.io.File;
import java.util.logging.Logger;

/**
 * Clasificador del entorno de la Bahia de Caza mediante Red Neuronal Neuroph 2.98.
 */
public class ClasificadorNeuroph {
    private static final Logger LOGGER = Logger.getLogger(ClasificadorNeuroph.class.getName());
    public static final String[] CLASES = {"LOBITO", "MURO", "OBSTACULO", "SALIDA", "ESPACIO_LIBRE"};

    private NeuralNetwork neuralNetwork;
    private final File archivoModelo;

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
                LOGGER.info("Red neuronal Neuroph 2.98 cargada desde: " + archivoModelo.getAbsolutePath());
            } catch (Exception e) {
                LOGGER.warning("No se pudo cargar modelo Neuroph: " + e.getMessage());
            }
        }
    }

    // RF-06: Reconocer tipo de elemento detectado mediante Neuroph 2.98.
    public String clasificar(VectorSensores sensores) {
        if (neuralNetwork != null) {
            try {
                double[] entrada = sensores.toArrayNormalizado(3.0);
                if (neuralNetwork.getInputsCount() == 4) {
                    neuralNetwork.setInput(entrada);
                    neuralNetwork.calculate();
                    double[] salida = neuralNetwork.getOutput();
                    return argMaxClase(salida);
                } else if (neuralNetwork.getInputsCount() == 6) {
                    double[] entrada6 = new double[] {entrada[0], entrada[1], entrada[2], entrada[3], 0.5, 0.5};
                    neuralNetwork.setInput(entrada6);
                    neuralNetwork.calculate();
                    double[] salida = neuralNetwork.getOutput();
                    return argMaxClase(salida);
                }
            } catch (Exception e) {
                LOGGER.warning("Error en inferencia Neuroph: " + e.getMessage());
            }
        }
        return inferenciaReglas(sensores);
    }

    // Obtiene la clase con mayor probabilidad de activacion.
    private String argMaxClase(double[] salida) {
        int bestIdx = 0;
        double maxVal = salida[0];
        for (int i = 1; i < salida.length && i < CLASES.length; i++) {
            if (salida[i] > maxVal) {
                maxVal = salida[i];
                bestIdx = i;
            }
        }
        return CLASES[bestIdx];
    }

    // Inferencia reactiva de contingencia basada en reglas de distancias.
    public static String inferenciaReglas(VectorSensores s) {
        double minLow = s.getMinLow();
        double minHigh = s.getMinHigh();

        if (minLow < 55.0 && minHigh > (minLow + 12.0)) {
            return "LOBITO";
        }
        if (minLow < 25.0 || minHigh < 25.0) {
            return "MURO";
        }
        if (minLow < 40.0 || minHigh < 40.0) {
            return "OBSTACULO";
        }
        if (minLow > 160.0 && minHigh > 160.0) {
            return "SALIDA";
        }
        return "ESPACIO_LIBRE";
    }

    // Retorna verdadero si la red neuronal esta cargada en memoria.
    public boolean isCargado() {
        return neuralNetwork != null;
    }
}
