#!/usr/bin/env bash
# =============================================================================
# Entrenamiento Neuroph 2.98 sobre Datos Sinteticos de Webots - Orquitas (Keiko)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SIM_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TRAIN_DIR="$(cd "${SIM_DIR}/../neuroph-train" && pwd)"

SENSORS="${1:-4}"

case "${SENSORS}" in
    4)  TEST_METHOD="testTrainNeurophOnSimulatedDataset"; NNET="orquitas_4sensores_neuroph.nnet";;
    6)  TEST_METHOD="testTrainNeurophOn6SensorsDataset";   NNET="orquitas_6sensores_neuroph.nnet";;
    8)  TEST_METHOD="testTrainNeurophOn8SensorsDataset";   NNET="orquitas_8sensores_neuroph.nnet";;
    10) TEST_METHOD="testTrainNeurophOn10SensorsDataset";  NNET="orquitas_10sensores_neuroph.nnet";;
    *)
        echo "[ERROR] Configuración de sensores no soportada: '${SENSORS}'. Usar 4, 6, 8 o 10."
        exit 1
        ;;
esac

echo "=========================================================="
echo "  ⚡ Entrenando Red Neuronal Neuroph 2.98 (${SENSORS} sensores)"
echo "  Directorio de Entrenamiento: ${TRAIN_DIR}"
echo "  Prueba Maven: SimulatedDatasetTrainTest#${TEST_METHOD}"
echo "=========================================================="

cd "${TRAIN_DIR}"
mvn test -Dtest="SimulatedDatasetTrainTest#${TEST_METHOD}"

NNET_PATH="${SIM_DIR}/data/models/${NNET}"
echo ""
echo "=========================================================="
if [[ -s "${NNET_PATH}" ]]; then
    echo "  Modelo .nnet generado y validado:"
    ls -lh "${NNET_PATH}"
else
    echo "[ERROR] El modelo .nnet no existe o está vacío: ${NNET_PATH}"
    exit 1
fi
echo "=========================================================="
