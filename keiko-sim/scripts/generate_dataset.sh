#!/usr/bin/env bash
# =============================================================================
# Generador Masivo Acelerado de Datasets Sinteticos - Orquitas (Keiko)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SIM_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

SENSORS="${1:-4}"

case "${SENSORS}" in
    4)  WORLD_FILE="${SIM_DIR}/worlds/bahia_de_caza_dataset_dp4.wbt" ;;
    6)  WORLD_FILE="${SIM_DIR}/worlds/bahia_de_caza_dataset_dp6.wbt" ;;
    8)  WORLD_FILE="${SIM_DIR}/worlds/bahia_de_caza_dataset.wbt" ;;
    10) WORLD_FILE="${SIM_DIR}/worlds/bahia_de_caza_dataset_trisector.wbt" ;;
    *)
        echo "[ERROR] Configuracion de sensores no soportada: ${SENSORS} (use 4, 6, 8 o 10)."
        exit 1
        ;;
esac

export SENSOR_CONFIG="${SENSORS}"
OUT_CSV="${SIM_DIR}/data/datasets/orquitas_dataset_${SENSORS}_sensores.csv"

echo "=========================================================="
echo "  🧠 Generador Masivo de Datasets Sinteticos (Webots 3D)"
echo "  Configuracion: ${SENSORS} Sensores"
echo "  Mundo: ${WORLD_FILE}"
echo "  Destino: ${OUT_CSV}"
echo "=========================================================="

if ! command -v webots &> /dev/null; then
    if [ -f "${HOME}/.local/bin/webots" ]; then
        export PATH="${HOME}/.local/bin:${PATH}"
    else
        echo "[ERROR] Webots no esta instalado o no se encuentra en PATH."
        exit 1
    fi
fi

if [ ! -f "${WORLD_FILE}" ]; then
    echo "[ERROR] Mundo no encontrado: ${WORLD_FILE}"
    echo "        Ejecute: python3 keiko-sim/scripts/build_worlds.py"
    exit 1
fi

# Ejecucion acelerada sin renderizado grafico
webots --batch --mode=fast --no-rendering --stdout --stderr "${WORLD_FILE}"

echo ""
echo "=========================================================="
echo "  Dataset generado exitosamente:"
echo "  Archivo: ${OUT_CSV}"
echo "  Filas totales: $(wc -l < "${OUT_CSV}")"
echo "  Distribucion de clases:"
# Última columna es class_label
awk -F',' '{print $NF}' "${OUT_CSV}" | sort | uniq -c
echo "=========================================================="
