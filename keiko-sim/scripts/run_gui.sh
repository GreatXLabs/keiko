#!/usr/bin/env bash
# =============================================================================
# Lanzador de Simulacion Interactiva en Tiempo Real - Orquitas (Keiko)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SIM_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
WORLD_FILE="${SIM_DIR}/worlds/bahia_de_caza.wbt"

echo "=========================================================="
echo "  🐋 Iniciando Simulacion 3D Webots - Bahia de Caza"
echo "  Mundo: ${WORLD_FILE}"
echo "=========================================================="

if ! command -v webots &> /dev/null; then
    if [ -f "${HOME}/.local/bin/webots" ]; then
        export PATH="${HOME}/.local/bin:${PATH}"
    else
        echo "[ERROR] Webots no esta instalado o no se encuentra en PATH."
        echo "Revisa la guia de instalacion en keiko-sim/README.md"
        exit 1
    fi
fi

# Iniciar Webots en modo interactivo
webots "${WORLD_FILE}"
