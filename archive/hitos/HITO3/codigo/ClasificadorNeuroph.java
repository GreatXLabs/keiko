package com.orquitas.server;

import java.io.File;
import org.neuroph.core.NeuralNetwork;

/**
 * Subsistema de percepcion basado en Neuroph 2.98 que fusiona la Red Ultrasonica
 * de entorno (3 clases) y la Red Laser de deteccion de canicas (4 clases) (RF-02).
 */
public class ClasificadorNeuroph {
    private final NeuralNetwork<?> neuralNetUltrasonico;
    private final NeuralNetwork<?> neuralNetCanicas;

    public ClasificadorNeuroph(File modeloUltrasonico, File modeloCanicas) {
        if (modeloUltrasonico == null || !modeloUltrasonico.isFile()) {
            throw new IllegalArgumentException("No se encontro el modelo ultrasonico Neuroph: " + modeloUltrasonico);
        }
        if (modeloCanicas == null || !modeloCanicas.isFile()) {
            throw new IllegalArgumentException("No se encontro el modelo de canicas Neuroph: " + modeloCanicas);
        }
        this.neuralNetUltrasonico = NeuralNetwork.createFromFile(modeloUltrasonico);
        this.neuralNetCanicas = NeuralNetwork.createFromFile(modeloCanicas);
    }

    public synchronized String[] clasificar(VectorSensores sensores) {
        double[] norm = sensores.toArrayNormalizado();
        String[] terna = new String[]{
                clasificarSectorUltrasonico(norm[0]),
                clasificarSectorUltrasonico(norm[1]),
                clasificarSectorUltrasonico(norm[2])
        };

        if (sensores.getLaserLeft() > 0.0 && sensores.getLaserRight() > 0.0) {
            String resCanica = clasificarCanicas(sensores);
            if ("LOBITO_IZQ".equals(resCanica) && !"BLOQUEO".equals(terna[0])) {
                terna[0] = "LOBITO";
            } else if ("LOBITO_CENTRO".equals(resCanica) && !"BLOQUEO".equals(terna[1])) {
                terna[1] = "LOBITO";
            } else if ("LOBITO_DER".equals(resCanica) && !"BLOQUEO".equals(terna[2])) {
                terna[2] = "LOBITO";
            } else if ("NINGUNA".equals(resCanica)) {
                double normLaserIzq = Math.min(1.0, Math.max(0.0, (sensores.getLaserLeft() - 2.0) / 248.0));
                if ("BLOQUEO".equals(clasificarSectorUltrasonico(normLaserIzq))) {
                    terna[0] = "BLOQUEO";
                }
                double normLaserDer = Math.min(1.0, Math.max(0.0, (sensores.getLaserRight() - 2.0) / 248.0));
                if ("BLOQUEO".equals(clasificarSectorUltrasonico(normLaserDer))) {
                    terna[2] = "BLOQUEO";
                }
            }
        }
        return terna;
    }

    private String clasificarSectorUltrasonico(double distNormalizada) {
        String[] clases = {"BLOQUEO", "ESPACIO_LIBRE", "SALIDA"};
        neuralNetUltrasonico.setInput(distNormalizada);
        neuralNetUltrasonico.calculate();
        double[] salida = neuralNetUltrasonico.getOutput();
        int idxMax = 0;
        for (int i = 1; i < salida.length && i < clases.length; i++) {
            if (salida[i] > salida[idxMax]) {
                idxMax = i;
            }
        }
        return clases[idxMax];
    }

    private String clasificarCanicas(VectorSensores sensores) {
        String[] clases = {"LOBITO_CENTRO", "LOBITO_DER", "LOBITO_IZQ", "NINGUNA"};
        double[] norm = sensores.toArrayNormalizado();
        neuralNetCanicas.setInput(norm[0], norm[1], norm[2], norm[3], norm[4], norm[5]);
        neuralNetCanicas.calculate();
        double[] salida = neuralNetCanicas.getOutput();
        int idxMax = 0;
        for (int i = 1; i < salida.length && i < clases.length; i++) {
            if (salida[i] > salida[idxMax]) {
                idxMax = i;
            }
        }
        return clases[idxMax];
    }
}
