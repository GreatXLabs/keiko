#!/usr/bin/env bash
# =============================================================================
# Lanzador de la Arquitectura Distribuida Completa - Orquitas (Keiko)
# Programación Sobre Redes & Desarrollo de Sistemas (Ciclo 2026)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SERVER_DIR="${ROOT_DIR}/keiko-server"
SERVER_JAR="${SERVER_DIR}/target/keiko-server.jar"
WORLD_FILE="${ROOT_DIR}/keiko-sim/worlds/bahia_de_caza_client.wbt"

echo "=================================================================="
echo "  🐋 ORQUITAS (KEIKO) - ARQUITECTURA DISTRIBUIDA COMPLETA"
echo "  Servidor Central (Java 21 + Neuroph 2.98) <-> Cliente Webots 3D"
echo "=================================================================="

# 1. Asegurar binario de Webots en PATH
if ! command -v webots &> /dev/null; then
    if [ -f "${HOME}/.local/bin/webots" ]; then
        export PATH="${HOME}/.local/bin:${PATH}"
    else
        echo "[ERROR] Webots no está instalado o no se encuentra en PATH."
        exit 1
    fi
fi

# 2. Compilar Servidor Java si no existe el fat JAR
if [ ! -f "${SERVER_JAR}" ]; then
    echo "📦 [BUILD] Compilando keiko-server.jar con Maven..."
    mvn -f "${SERVER_DIR}/pom.xml" package -DskipTests=true
fi

# 3. Iniciar Servidor Central en segundo plano
echo "🧠 [INICIO] Arrancando Servidor Central (Cerebro + Hilo Auditor)..."
java -jar "${SERVER_JAR}" --port 8888 --reset &
SERVER_PID=$!

cleanup() {
    echo ""
    echo "🛑 [APAGADO] Finalizando Servidor Central (PID: ${SERVER_PID})..."
    kill "${SERVER_PID}" 2>/dev/null || true
    wait "${SERVER_PID}" 2>/dev/null || true
    echo "✅ Sistema distribuido cerrado limpiamente."
}
trap cleanup EXIT INT TERM

# Esperar a que el servidor de sockets esté listo
sleep 1.5

# 4. Iniciar Webots 3D con el cliente Hardware-in-the-Loop
echo "🚀 [CLIENTE] Iniciando Webots 3D con la Orquita 4WD..."
if [[ "${1:-}" == "--batch" ]]; then
    webots --batch --mode=fast --stdout --stderr "${WORLD_FILE}"
else
    webots "${WORLD_FILE}"
fi
