#!/usr/bin/env bash
# =============================================================================
# Lanzador de Supervisión y Entrenamiento Continuo en Pantalla - Proyecto Orquitas
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

SERVER_JAR="${SCRIPT_DIR}/keiko-server/target/keiko-server.jar"

echo "=================================================================="
echo "  🐋 KEIKO: SUPERVISION Y ENTRENAMIENTO CONTINUO EN PANTALLA"
echo "=================================================================="

# 1. Verificar si webots esta instalado
if ! command -v webots &> /dev/null; then
    if [ -f "${HOME}/.local/bin/webots" ]; then
        export PATH="${HOME}/.local/bin:${PATH}"
    else
        echo -e "\n\033[1;31m[ERROR] 'webots' no esta instalado o no se encuentra en el PATH.\033[0m"
        echo "Para instalarlo en Arch Linux ejecuta en tu terminal:"
        echo -e "  \033[1;32myay -S webots-bin\033[0m\n"
        exit 1
    fi
fi

# 2. Compilar cambios recientes del Servidor Central si javac esta disponible
if command -v javac &> /dev/null && command -v jar &> /dev/null; then
    echo ">> Verificando / compilando cambios recientes de keiko-server..."
    javac --release 21 -cp "${SERVER_JAR}" -d "${SCRIPT_DIR}/keiko-server/target/classes" \
        "${SCRIPT_DIR}/keiko-server/src/main/java/com/orquitas/server/"*.java 2>/dev/null || true
    jar uf "${SERVER_JAR}" -C "${SCRIPT_DIR}/keiko-server/target/classes" . 2>/dev/null || true
fi

# 3. Detectar pantalla activa para renderizado visual en la sesion de la computadora
if [ -z "${DISPLAY:-}" ]; then
    export DISPLAY=":0.0"
fi

if [ -z "${XAUTHORITY:-}" ] && [ -f "${HOME}/.Xauthority" ]; then
    export XAUTHORITY="${HOME}/.Xauthority"
fi

echo ">> Sesion grafica detectada: DISPLAY=${DISPLAY}"
echo ">> Ejecutando orquestador continuo de simulacion..."

exec python3 "${SCRIPT_DIR}/keiko-sim/scripts/entrenamiento_continuo.py" "$@"
