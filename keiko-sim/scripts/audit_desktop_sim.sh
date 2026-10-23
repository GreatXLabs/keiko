#!/usr/bin/env bash
# =============================================================================
# Auditor de Simulación en Escritorio con Captura Visual - Orquitas (Keiko)
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SERVER_DIR="${ROOT_DIR}/keiko-server"
SERVER_JAR="${SERVER_DIR}/target/keiko-server.jar"
WORLD_FILE="${WORLD_FILE:-${ROOT_DIR}/keiko-sim/worlds/bahia_de_caza_client.wbt}"
AUDIT_DIR="${ROOT_DIR}/keiko-sim/logs/audit"
mkdir -p "${AUDIT_DIR}"

export DISPLAY="${DISPLAY:-:1}"
echo "=================================================================="
echo "  📸 AUDITORÍA VISUAL Y DISTRIBUIDA - ORQUITAS (KEIKO)"
echo "  Display: ${DISPLAY} | Salida de capturas: ${AUDIT_DIR}"
echo "=================================================================="

# 1. Asegurar binario de Webots
if ! command -v webots &> /dev/null; then
    if [ -f "${HOME}/.local/bin/webots" ]; then
        export PATH="${HOME}/.local/bin:${PATH}"
    else
        echo "[ERROR] Webots no está instalado o no se encuentra en PATH."
        exit 1
    fi
fi

# 2. Resetear estomago.txt persistente
ESTOMAGO_FILE="${ROOT_DIR}/estomago.txt"
> "${ESTOMAGO_FILE}"
echo "📄 [ESTOMAGO] Archivo reiniciado en: ${ESTOMAGO_FILE}"

# 3. Iniciar Servidor Central
echo "🧠 [SERVIDOR] Arrancando Servidor Central (TCP 8888)..."
( cd "${SERVER_DIR}" && exec java -jar "${SERVER_JAR}" --port 8888 --reset --estomago "${ESTOMAGO_FILE}" ) &
SERVER_PID=$!

CAPTURE_PID=""
cleanup() {
    echo ""
    echo "🛑 [APAGADO] Finalizando procesos de auditoría..."
    if [ -n "${CAPTURE_PID}" ]; then
        kill "${CAPTURE_PID}" 2>/dev/null || true
    fi
    kill "${SERVER_PID}" 2>/dev/null || true
    wait "${SERVER_PID}" 2>/dev/null || true
    echo "✅ Auditoría completada."
}
trap cleanup EXIT INT TERM

sleep 1.5

# 4. Hilo en segundo plano para captura periódica con scrot
(
    step=0
    while true; do
        sleep 4
        step=$((step + 1))
        ts=$(date +%s)
        img_path="${AUDIT_DIR}/screenshot_${step}_${ts}.png"
        if command -v scrot &> /dev/null; then
            scrot "${img_path}" 2>/dev/null || true
            cp "${img_path}" /tmp/orquitas_latest_audit.png 2>/dev/null || true
        fi
    done
) &
CAPTURE_PID=$!

# 5. Ejecutar Webots (modo fast o GUI según argumento)
MODE="${1:---fast}"
echo "🚀 [SIMULADOR] Lanzando Webots 3D (${MODE})..."

if [ "${MODE}" == "--gui" ]; then
    webots "${WORLD_FILE}"
elif [ "${MODE}" == "--fast" ] || [ "${MODE}" == "--batch" ]; then
    webots --batch --mode=fast --stdout --stderr "${WORLD_FILE}"
else
    webots --batch --mode=realtime --stdout --stderr "${WORLD_FILE}"
fi

# 6. Reporte de Auditoría Final
echo ""
echo "=================================================================="
echo "  📊 REPORTE DE AUDITORÍA DISTRIBUIDA (FINALIZACIÓN)"
echo "=================================================================="
if [ -f "${ESTOMAGO_FILE}" ]; then
    total_capturas=$(grep -c "CAPTURA" "${ESTOMAGO_FILE}" || true)
    echo "  * Registros físicos en estomago.txt: ${total_capturas}/3"
    cat "${ESTOMAGO_FILE}"
else
    echo "  [ERROR] No se encontró ${ESTOMAGO_FILE}"
fi

LOG_FILE="${ROOT_DIR}/keiko-sim/logs/simulation_client.log"
if [ -f "${LOG_FILE}" ]; then
    echo "------------------------------------------------------------------"
    grep -E "MISIÓN DISTRIBUIDA|Canicas aseguradas|Posición de salida|COLISIÓN REGISTRADA|TIEMPO MÁXIMO|FIN REGLAMENTARIO" "${LOG_FILE}" | tail -n 20 || true
    echo "------------------------------------------------------------------"
fi

echo "🖼️ Última captura de pantalla disponible en: /tmp/orquitas_latest_audit.png"
echo "=================================================================="
