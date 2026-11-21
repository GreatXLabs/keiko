#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXPECTED_VERSION="$(tr -d '[:space:]' < "${REPO_ROOT}/VERSION")"
EXPECTED_RIG="mega-3us-2laser"

fail=0
check_match() {
    local label="$1"
    local actual="$2"
    local expected="$3"
    if [ "${actual}" != "${expected}" ]; then
        echo "[ERROR] ${label}: esperado '${expected}', encontrado '${actual}'"
        fail=1
    else
        echo "[OK]    ${label}: ${actual}"
    fi
}

POM_VER="$(grep -m1 '<version>' "${REPO_ROOT}/keiko-server/pom.xml" | sed -E 's/.*<version>([^<]+)<\/version>.*/\1/' | tr -d '[:space:]')"
JAVA_VER="$(grep -m1 'VERSION_SISTEMA' "${REPO_ROOT}/keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java" | sed -E 's/.*"([^"]+)".*/\1/')"
JAVA_RIG="$(grep -m1 'RIG_HARDWARE' "${REPO_ROOT}/keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java" | sed -E 's/.*"([^"]+)".*/\1/')"
FW_VER="$(grep -m1 '#define KEIKO_VERSION' "${REPO_ROOT}/firmware/orquita_firmware/orquita_firmware.ino" | sed -E 's/.*"([^"]+)".*/\1/')"
FW_RIG="$(grep -m1 '#define KEIKO_RIG' "${REPO_ROOT}/firmware/orquita_firmware/orquita_firmware.ino" | sed -E 's/.*"([^"]+)".*/\1/')"
SIM_JSON_VER="$(python3 -c "import json; d=json.load(open('${REPO_ROOT}/keiko-sim/config/simulation_params.json')); print(d.get('version',''))")"
SIM_JSON_RIG="$(python3 -c "import json; d=json.load(open('${REPO_ROOT}/keiko-sim/config/simulation_params.json')); print(d.get('rig',''))")"
PY_VER="$(grep -m1 'KEIKO_VERSION =' "${REPO_ROOT}/keiko-sim/controllers/robot_client/robot_client.py" | sed -E 's/.*"([^"]+)".*/\1/')"
PY_RIG="$(grep -m1 'KEIKO_RIG =' "${REPO_ROOT}/keiko-sim/controllers/robot_client/robot_client.py" | sed -E 's/.*"([^"]+)".*/\1/')"

echo "=== Verificando sincronizacion de version (${EXPECTED_VERSION} | ${EXPECTED_RIG}) ==="
check_match "keiko-server/pom.xml" "${POM_VER}" "${EXPECTED_VERSION}"
check_match "ConfiguracionServidor.VERSION_SISTEMA" "${JAVA_VER}" "${EXPECTED_VERSION}"
check_match "ConfiguracionServidor.RIG_HARDWARE" "${JAVA_RIG}" "${EXPECTED_RIG}"
check_match "orquita_firmware.ino KEIKO_VERSION" "${FW_VER}" "${EXPECTED_VERSION}"
check_match "orquita_firmware.ino KEIKO_RIG" "${FW_RIG}" "${EXPECTED_RIG}"
check_match "simulation_params.json version" "${SIM_JSON_VER}" "${EXPECTED_VERSION}"
check_match "simulation_params.json rig" "${SIM_JSON_RIG}" "${EXPECTED_RIG}"
check_match "robot_client.py KEIKO_VERSION" "${PY_VER}" "${EXPECTED_VERSION}"
check_match "robot_client.py KEIKO_RIG" "${PY_RIG}" "${EXPECTED_RIG}"

exit "${fail}"
