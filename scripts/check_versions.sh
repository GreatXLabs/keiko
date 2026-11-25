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

echo "=== Verificando sincronizacion de version (${EXPECTED_VERSION} | ${EXPECTED_RIG}) ==="
check_match "keiko-server/pom.xml" "${POM_VER}" "${EXPECTED_VERSION}"
check_match "ConfiguracionServidor.VERSION_SISTEMA" "${JAVA_VER}" "${EXPECTED_VERSION}"
check_match "ConfiguracionServidor.RIG_HARDWARE" "${JAVA_RIG}" "${EXPECTED_RIG}"
check_match "orquita_firmware.ino KEIKO_VERSION" "${FW_VER}" "${EXPECTED_VERSION}"
check_match "orquita_firmware.ino KEIKO_RIG" "${FW_RIG}" "${EXPECTED_RIG}"

exit "${fail}"
