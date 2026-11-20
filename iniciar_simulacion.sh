#!/usr/bin/env bash
# =============================================================================
# Lanzador de Servidor Central + Simulador Webots (Bahia de Caza)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

SCEN_ARG="${1:-1}"
MODEL_FILE="${SCRIPT_DIR}/keiko-sim/data/models/modelo_ultrasonico.nnet"
SERVER_JAR="${SCRIPT_DIR}/keiko-server/target/keiko-server.jar"

echo "=================================================================="
echo "  🐋 KEIKO: SERVIDOR CENTRAL + SIMULACION WEBOTS"
echo "=================================================================="

if [[ "${SCEN_ARG,,}" == "random" || "${SCEN_ARG,,}" == "rand" ]]; then
    echo ">> Generando escenario aleatorio reglamentario (3 canicas 16mm, 2 obstaculos 6x6x6cm)..."
    python3 "${SCRIPT_DIR}/keiko-sim/scripts/build_worlds.py" --random
    WORLD_FILE="${SCRIPT_DIR}/keiko-sim/worlds/bahia_de_caza_scen_random.wbt"
elif [[ "${SCEN_ARG,,}" == "circular" || "${SCEN_ARG,,}" == "circulo" || "${SCEN_ARG,,}" == "round" ]]; then
    WORLD_FILE="${SCRIPT_DIR}/keiko-sim/worlds/bahia_de_caza_circular_client.wbt"
    if [ ! -f "${WORLD_FILE}" ]; then
        echo ">> Generando mundos .wbt con build_worlds.py..."
        python3 "${SCRIPT_DIR}/keiko-sim/scripts/build_worlds.py"
    fi
elif [[ "${SCEN_ARG}" =~ ^[0-9]+$ ]]; then
    WORLD_FILE="${SCRIPT_DIR}/keiko-sim/worlds/bahia_de_caza_scen_${SCEN_ARG}.wbt"
    if [ ! -f "${WORLD_FILE}" ]; then
        echo ">> Generando mundos .wbt con build_worlds.py..."
        python3 "${SCRIPT_DIR}/keiko-sim/scripts/build_worlds.py"
    fi
elif [ -f "${SCEN_ARG}" ]; then
    WORLD_FILE="$(realpath "${SCEN_ARG}")"
elif [ -f "${SCRIPT_DIR}/keiko-sim/worlds/${SCEN_ARG}" ]; then
    WORLD_FILE="${SCRIPT_DIR}/keiko-sim/worlds/${SCEN_ARG}"
else
    echo -e "\n[ERROR] Escenario '${SCEN_ARG}' no encontrado."
    echo "Uso: ./iniciar_simulacion.sh [1..14 | random | circular]"
    exit 1
fi

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

# 2. Compilar cambios recientes del Servidor Central
MVN_BIN="mvn"
if ! command -v mvn &>/dev/null && [ -x "/usr/lib/netbeans/java/maven/bin/mvn" ]; then
    MVN_BIN="/usr/lib/netbeans/java/maven/bin/mvn"
fi
if command -v "${MVN_BIN}" &>/dev/null; then
    echo ">> Compilando cambios recientes de keiko-server con Maven..."
    "${MVN_BIN}" -f "${SCRIPT_DIR}/keiko-server/pom.xml" package -DskipTests=true
fi

# 3. Matar instancias previas del servidor, simulador y controlador, y limpiar estado previo
echo ">> Limpiando procesos previos (keiko-server, webots, robot_client) y estado de estomago..."
pkill -9 -f "keiko-server.jar" 2>/dev/null || true
pkill -9 -f "webots-bin" 2>/dev/null || true
pkill -9 -f "robot_client.py" 2>/dev/null || true
rm -rf "/tmp/webots/$(whoami)/"* 2>/dev/null || true
rm -f "${SCRIPT_DIR}/estomago.txt" "${SCRIPT_DIR}/keiko-server/estomago.txt" 2>/dev/null || true
sleep 0.5

# 4. Lanzar Servidor Central Java en segundo plano (con --reset)
echo ">> Iniciando Servidor Central (puerto 8888, reset de estomago)..."
java -jar "${SERVER_JAR}" --port 8888 --model "${MODEL_FILE}" --reset &
SERVER_PID=$!

# Asegurar que al salir (Ctrl+C o cierre normal) se mate el servidor
cleanup() {
    echo -e "\n>> Deteniendo Servidor Central (PID: ${SERVER_PID})..."
    kill -9 "${SERVER_PID}" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# 4. Esperar a que el servidor inicialice sockets y redes neuronales
echo ">> Esperando inicializacion del servidor (2 segundos)..."
sleep 2

# 5. Abrir Webots con el escenario (en modo realtime y redirigiendo stdout/stderr a la terminal)
export DISPLAY="${DISPLAY:-:0.0}"
echo ">> Lanzando Webots con escenario: $(basename "${WORLD_FILE}")..."
WEBOTS_ARGS=(--mode=realtime --stdout --stderr "${WORLD_FILE}")

if command -v nvidia-smi &> /dev/null && nvidia-smi &> /dev/null; then
    # Si la GPU NVIDIA ya es la pantalla primaria activa, ejecutar de forma nativa
    if nvidia-smi | grep -q "Disp.A.*On"; then
        webots "${WEBOTS_ARGS[@]}"
    else
        __NV_PRIME_RENDER_OFFLOAD=1 __GLX_VENDOR_LIBRARY_NAME=nvidia webots "${WEBOTS_ARGS[@]}"
    fi
else
    webots "${WEBOTS_ARGS[@]}"
fi

