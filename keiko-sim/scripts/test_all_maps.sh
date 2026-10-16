#!/usr/bin/env bash
# =============================================================================
# Suite de Evaluación Desatendida Multi-Mapa - Proyecto Orquitas (Keiko)
# Ejecuta las 4 pistas reglamentarias en modo headless y computa el puntaje PSR.
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SIM_DIR="${ROOT_DIR}/keiko-sim"
LOG_LIVE="${SIM_DIR}/logs/simulation_live.log"
LOG_EVAL_DIR="${SIM_DIR}/logs"
mkdir -p "${LOG_EVAL_DIR}"

# Mapas reglamentarios (controller live_visualizer, dual_plane_8) con salida en
# distintas paredes: Norte (estandar), Este y Sur (multi-orientacion).
MAPS=(
    "bahia_de_caza.wbt|Estandar"
    "bahia_de_caza_labyrinth.wbt|Laberinto"
    "bahia_de_caza_corners.wbt|Esquinas"
    "bahia_de_caza_corridors.wbt|Pasillos"
    "bahia_de_caza_east.wbt|Salida Este"
    "bahia_de_caza_south.wbt|Salida Sur"
)

TIMEOUT_SEC=90   # wall-clock máximo por mapa en modo fast

echo "=================================================================="
echo "  🧪 SUITE DE EVALUACIÓN HEADLESS MULTI-MAPA - ORQUITAS (KEIKO)"
echo "  Pista: Bahía de Caza (2.4m x 2.4m) | Chasis: 4WD Skid-Steer"
echo "  Puntaje PSR oficial (máx. 90 pts + bonus de tiempo)"
echo "=================================================================="

# 1. Regenerar todos los mundos de simulación
echo ""
echo "🔧 [PASO 1] Regenerando mundos de simulación..."
(cd "${ROOT_DIR}" && python3 keiko-sim/scripts/build_worlds.py)

# 2. Verificar instalación de Webots
if ! command -v webots &> /dev/null; then
    if [ -f "${HOME}/.local/bin/webots" ]; then
        export PATH="${HOME}/.local/bin:${PATH}"
    else
        echo "[ERROR] Webots no está instalado o no se encuentra en PATH."
        exit 1
    fi
fi

compute_score() {
    local logfile="$1"
    local captures escape complete collisions time_sec score bonus
    captures=$(grep -c "CAPTURA VERIFICADA" "$logfile" 2>/dev/null || true)
    escape=$(grep -c "ESTADO: MODO ESCAPE ACTIVADO" "$logfile" 2>/dev/null || true)
    complete=$(grep -c "MISIÓN COMPLETADA CON ÉXITO: BAHÍA ABANDONADA" "$logfile" 2>/dev/null || true)
    collisions=$(grep -c "COLISIÓN REGISTRADA: -5 pts" "$logfile" 2>/dev/null || true)
    time_sec=$(grep -oE 'Tiempo total: [0-9.]+' "$logfile" 2>/dev/null | head -n1 | grep -oE '[0-9.]+' || true)

    score=0
    [ "$captures" -ge 1 ] && score=$((score + 5))
    [ "$captures" -ge 2 ] && score=$((score + 10))
    [ "$captures" -ge 3 ] && score=$((score + 15))
    [ "$escape" -ge 1 ] && score=$((score + 15))

    if [ "$complete" -ge 1 ]; then
        score=$((score + 30))
        if [ -n "$time_sec" ]; then
            bonus=$(awk -v t="$time_sec" 'BEGIN { if (t <= 60) print 15; else if (t <= 240) print 10; else if (t <= 600) print 5; else print 0 }')
            score=$((score + bonus))
        fi
    fi

    score=$((score - collisions * 5))
    echo "$score"
}

run_map() {
    local world="$1"
    local label="$2"
    local idx="$3"
    local world_path="${SIM_DIR}/worlds/${world}"
    local eval_log="${LOG_EVAL_DIR}/eval_${idx}_${world%.wbt}.log"

    echo ""
    echo "🚀 [MAPA ${idx}] ${label} (${world})..."
    rm -f "${LOG_LIVE}"
    pkill -9 webots-bin 2>/dev/null || true
    sleep 1

    webots --batch --mode=fast --stdout --stderr "${world_path}" > /dev/null 2>&1 &
    local sim_pid=$!

    local elapsed=0
    local passed=false
    while [ $elapsed -lt $TIMEOUT_SEC ]; do
        sleep 3
        elapsed=$((elapsed + 3))
        if [ -f "${LOG_LIVE}" ] && grep -q "MISIÓN COMPLETADA CON ÉXITO: BAHÍA ABANDONADA" "${LOG_LIVE}"; then
            passed=true
            break
        fi
        if ! kill -0 $sim_pid 2>/dev/null; then
            break
        fi
    done

    kill -9 $sim_pid 2>/dev/null || true
    pkill -9 webots-bin 2>/dev/null || true

    # Conservar el log de este mapa antes de la siguiente iteración
    if [ -f "${LOG_LIVE}" ]; then
        cp "${LOG_LIVE}" "${eval_log}"
    else
        echo "" > "${eval_log}"
    fi

    local captures escape complete collisions time_sec score
    captures=$(grep -c "CAPTURA VERIFICADA" "${eval_log}" 2>/dev/null || true)
    escape=$(grep -c "ESTADO: MODO ESCAPE ACTIVADO" "${eval_log}" 2>/dev/null || true)
    complete=$(grep -c "MISIÓN COMPLETADA CON ÉXITO: BAHÍA ABANDONADA" "${eval_log}" 2>/dev/null || true)
    collisions=$(grep -c "COLISIÓN REGISTRADA: -5 pts" "${eval_log}" 2>/dev/null || true)
    time_sec=$(grep -oE 'Tiempo total: [0-9.]+' "${eval_log}" 2>/dev/null | head -n1 | grep -oE '[0-9.]+' || true)
    score=$(compute_score "${eval_log}")

    local status
    if [ "$complete" -ge 1 ]; then
        status="✅ COMPLETADO"
    elif [ "$captures" -gt 0 ] || [ "$escape" -ge 1 ]; then
        status="⚠️ PARCIAL"
    else
        status="❌ NO COMPLETADO"
    fi

    echo "  ${status}"
    echo "     - Capturas verificadas : ${captures}/3"
    echo "     - Modo Escape activado : $([ "$escape" -ge 1 ] && echo SI || echo NO)"
    echo "     - Bahía abandonada     : $([ "$complete" -ge 1 ] && echo SI || echo NO)"
    echo "     - Colisiones (-5 pts)  : ${collisions}"
    if [ -n "$time_sec" ]; then
        echo "     - Tiempo total         : ${time_sec}s"
    fi
    echo "     - PUNTAJE PSR          : ${score} pts"
    echo "     - Log                  : ${eval_log}"
}

echo ""
echo "🧪 [PASO 2] Evaluación headless de los mapas reglamentarios (multi-orientación)..."
i=0
for entry in "${MAPS[@]}"; do
    i=$((i + 1))
    world="${entry%%|*}"
    label="${entry##*|}"
    run_map "$world" "$label" "$i"
done

echo ""
echo "=================================================================="
echo "  🏁 RESUMEN GENERAL DE EVALUACIÓN PSR"
echo "=================================================================="
i=0
for entry in "${MAPS[@]}"; do
    i=$((i + 1))
    world="${entry%%|*}"
    label="${entry##*|}"
    eval_log="${LOG_EVAL_DIR}/eval_${i}_${world%.wbt}.log"
    if [ -f "${eval_log}" ]; then
        score=$(compute_score "${eval_log}")
        complete=$(grep -c "MISIÓN COMPLETADA CON ÉXITO: BAHÍA ABANDONADA" "${eval_log}" 2>/dev/null || true)
        printf "  %-12s | %s | %3d pts\n" "${label}" "$([ "$complete" -ge 1 ] && echo COMPLETADO || echo INCOMPLETO)" "$score"
    fi
done
echo "=================================================================="
echo "  Pipeline de simulación y evaluación operativo."
echo "=================================================================="
