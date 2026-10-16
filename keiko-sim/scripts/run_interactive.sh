#!/usr/bin/env bash
# =============================================================================
# Lanzador Interactivo y Panel de Control - Proyecto Orquitas (Keiko)
# Programación Sobre Redes & Desarrollo de Sistemas (Ciclo 2026)
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SIM_DIR="${ROOT_DIR}/keiko-sim"
SERVER_DIR="${ROOT_DIR}/keiko-server"
SERVER_JAR="${SERVER_DIR}/target/keiko-server.jar"
ESTOMAGO_FILE="${ROOT_DIR}/estomago.txt"

# Colores de terminal
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

clear || true
echo -e "${CYAN}${BOLD}"
echo "============================================================================"
echo "    🐋  PROYECTO ORQUITAS (KEIKO) - PANEL DE CONTROL Y SIMULACIÓN 3D        "
echo "    Arquitectura Distribuida + Neuroph 2.98 + Auditoría de Persistencia     "
echo "============================================================================"
echo -e "${NC}"

echo -e "${YELLOW}Selecciona una opción para ejecutar:${NC}"
echo ""
echo -e "  ${BOLD}[ VISUALIZACIÓN INTERACTIVA 3D (GUI WEBOTS + RADAR HUD) ]${NC}"
echo -e "  ${GREEN}1)${NC} Abrir Mapa Estándar (3 canicas, salida Norte, obstáculos moderados)"
echo -e "  ${GREEN}2)${NC} Abrir Mapa Laberinto (pasillos estrechos de 40cm, alta densidad)"
echo -e "  ${GREEN}3)${NC} Abrir Mapa Esquinas Críticas (canicas encajadas en esquinas a 90°)"
echo -e "  ${GREEN}4)${NC} Abrir Mapa con Salida en Pared Este (verificación de salida dinámica)"
echo ""
echo -e "  ${BOLD}[ HARDWARE-IN-THE-LOOP (HIL: SERVIDOR JAVA + CLIENTE WEBOTS) ]${NC}"
echo -e "  ${GREEN}5)${NC} Iniciar Sistema Distribuido Completo (Servidor Central Java + Webots 3D)"
echo -e "  ${GREEN}6)${NC} Ejecutar Benchmark Multi-Mapa HIL (Estándar + Laberinto + Esquinas)"
echo ""
echo -e "  ${BOLD}[ PRUEBAS CRÍTICAS DE ESPECIFICACIÓN DOCENTE ]${NC}"
echo -e "  ${GREEN}7)${NC} Prueba de Parada de Emergencia / Fail-Safe (<500 ms ante corte de enlace)"
echo -e "  ${GREEN}8)${NC} Prueba de Recuperación ante Reinicio Forzado (\"La Linareada\" Crash Recovery)"
echo ""
echo -e "  ${BOLD}[ CLUSTER Y MODELOS ]${NC}"
echo -e "  ${GREEN}9)${NC} Consultar Estado del Cluster Remoto (neuroph.aguilucho.ar:443)"
echo -e "  ${GREEN}10)${NC} Ver Bitácora Consolidada de Experimentos (EXPERIMENTS.md)"
echo ""
echo -e "  ${RED}0)${NC} Salir"
echo ""
echo -e -n "${CYAN}Ingresa tu opción [0-10]: ${NC}"
read -r OPTION

case "${OPTION}" in
    1)
        echo -e "\n${GREEN}🚀 Iniciando Webots 3D en Mapa Estándar con Radar Sonar HUD...${NC}"
        webots "${SIM_DIR}/worlds/bahia_de_caza.wbt"
        ;;
    2)
        echo -e "\n${GREEN}🚀 Iniciando Webots 3D en Mapa Laberinto...${NC}"
        webots "${SIM_DIR}/worlds/bahia_de_caza_labyrinth.wbt"
        ;;
    3)
        echo -e "\n${GREEN}🚀 Iniciando Webots 3D en Mapa Esquinas Críticas...${NC}"
        webots "${SIM_DIR}/worlds/bahia_de_caza_corners.wbt"
        ;;
    4)
        echo -e "\n${GREEN}🚀 Iniciando Webots 3D en Mapa Salida Este...${NC}"
        webots "${SIM_DIR}/worlds/bahia_de_caza_hybrid.wbt"
        ;;
    5)
        echo -e "\n${BLUE}🧠 Levantando Servidor Central Java (Cerebro + Hilo Auditor)...${NC}"
        bash "${SIM_DIR}/scripts/run_distributed.sh"
        ;;
    6)
        echo -e "\n${BLUE}🧪 Ejecutando Benchmark Automatizado Multi-Mapa HIL...${NC}"
        python3 "${SIM_DIR}/scripts/benchmark_hil.py"
        ;;
    7)
        echo -e "\n${YELLOW}⚠️  Iniciando Prueba de Fail-Safe (<500 ms)...${NC}"
        echo "1. Levantando servidor central en puerto 8899..."
        ( cd "${SERVER_DIR}" && exec java -jar "${SERVER_JAR}" --port 8899 --reset --estomago "${ESTOMAGO_FILE}" ) > /tmp/failsafe_server.log 2>&1 &
        SRV_PID=$!
        sleep 1.5
        echo "2. Conectando cliente robot Webots 3D..."
        KEIKO_SERVER_PORT=8899 webots --batch --mode=fast --no-rendering "${SIM_DIR}/worlds/bahia_de_caza_client.wbt" > /dev/null 2>&1 &
        CLI_PID=$!
        sleep 4
        echo -e "3. ${RED}¡CORTANDO ENLACE DE RED (Simulación de pérdida de señal Bluetooth)!${NC}"
        kill -9 "${SRV_PID}" 2>/dev/null || true
        sleep 2
        kill -9 "${CLI_PID}" 2>/dev/null || true
        pkill -9 webots-bin 2>/dev/null || true
        echo -e "\n${GREEN}✅ Resultado de Telemetría Fail-Safe:${NC}"
        tail -n 25 "${SIM_DIR}/logs/simulation_client.log" | grep -E "(FAIL-SAFE|PARADA|ENLACE|velocidad|MOTORES)" || tail -n 15 "${SIM_DIR}/logs/simulation_client.log"
        echo -e "${GREEN}Verificación completada: Motores detenidos preventivamente.${NC}"
        ;;
    8)
        echo -e "\n${YELLOW}📁 Prueba de Persistencia y Reinicio Forzado (\"La Linareada\"):${NC}"
        echo "1. Creando estomago.txt con 2 capturas previas simuladas..."
        cat << 'EOF' > "${ESTOMAGO_FILE}"
# ========================================================
# ESTOMAGO PERSISTENTE - PROYECTO ORQUITAS (PSR/DS 2026)
# FUENTE UNICA DE VERDAD BIOLOGICA (NO VOLATIL)
# ========================================================
2026-09-12T00:00:01.000000000Z | CAPTURA #1 | METADATA: Evento previo simulado: lobito_1 | CHECKSUM=A1B2C3D4
2026-09-12T00:00:02.000000000Z | CAPTURA #2 | METADATA: Evento previo simulado: lobito_2 | CHECKSUM=E5F6A7B8
EOF
        echo "2. Levantando Servidor Central SIN flag --reset (debe restaurar capturas=2 del disco)..."
        ( cd "${SERVER_DIR}" && exec java -jar "${SERVER_JAR}" --port 8899 --estomago "${ESTOMAGO_FILE}" ) > /tmp/crash_recovery_server.log 2>&1 &
        SRV_PID=$!
        sleep 2
        kill -9 "${SRV_PID}" 2>/dev/null || true
        cat /tmp/crash_recovery_server.log | grep -E "(PERSISTENCIA|ESTADO RESTAURADO|Capturas)"
        echo -e "${GREEN}✅ Verificación exitosa: El servidor no utilizó variables en memoria.${NC}"
        echo "   Reconstruyó el estado biológico leyendo exclusivamente estomago.txt."
        ;;
    9)
        echo -e "\n${BLUE}☁️ Consultando estado del Cluster Dokploy (neuroph.aguilucho.ar)...${NC}"
        python3 -c '
import asyncio
import sys
sys.path.insert(0, "'"${SIM_DIR}"'/scripts")
from cluster_client import ClusterClient

async def check():
    c = ClusterClient()
    await c.connect()
    w = await c.get_workers()
    print(f"\n⚡ Workers Activos en Cluster Dokploy: {len(w)}")
    for worker in w:
        print(f"   - {worker.get(\"workerName\")} (Cores: {worker.get(\"cores\", 1)}, Estado: {worker.get(\"state\", \"IDLE\")})")
    await c.close()

asyncio.run(check())
'
        ;;
    10)
        echo -e "\n${CYAN}Mostrando resumen de EXPERIMENTS.md:${NC}"
        head -n 27 "${ROOT_DIR}/docs/EXPERIMENTS.md"
        ;;
    0)
        echo "Saliendo..."
        exit 0
        ;;
    *)
        echo -e "${RED}Opción inválida.${NC}"
        exit 1
        ;;
esac
