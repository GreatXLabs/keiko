#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
    echo "Uso: $0 <nueva_version> (ej: 2.0.1)" >&2
    exit 1
fi

NEW_VER="$1"
if ! printf '%s' "${NEW_VER}" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$'; then
    echo "Error: formato de version invalido '${NEW_VER}' (esperado X.Y.Z o X.Y.Z-tag)" >&2
    exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

python3 - "${REPO_ROOT}" "${NEW_VER}" <<'PY'
import json
import os
import re
import sys

root, new_ver = sys.argv[1], sys.argv[2]

def replace_once(rel_path, pattern, repl_fn):
    path = os.path.join(root, rel_path)
    with open(path, "r", encoding="utf-8") as fh:
        content = fh.read()
    new_content, n = re.subn(pattern, repl_fn, content, count=1, flags=re.MULTILINE)
    if n != 1:
        raise RuntimeError(f"No se pudo actualizar {rel_path} (coincidencias={n})")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(new_content)

with open(os.path.join(root, "VERSION"), "w", encoding="utf-8") as fh:
    fh.write(new_ver + "\n")

replace_once(
    "keiko-server/pom.xml",
    r"^(\s*<artifactId>keiko-server</artifactId>\s*\n\s*<version>)[^<]+(</version>)",
    lambda m: f"{m.group(1)}{new_ver}{m.group(2)}",
)
replace_once(
    "keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java",
    r'^(\s*public static final String VERSION_SISTEMA\s*=\s*")[^"]+(";)',
    lambda m: f"{m.group(1)}{new_ver}{m.group(2)}",
)
replace_once(
    "firmware/orquita_firmware/orquita_firmware.ino",
    r'^(#define\s+KEIKO_VERSION\s+")[^"]+(")',
    lambda m: f"{m.group(1)}{new_ver}{m.group(2)}",
)
sim_json_path = os.path.join(root, "keiko-sim/config/simulation_params.json")
with open(sim_json_path, "r", encoding="utf-8") as fh:
    sim_data = json.load(fh)
sim_data["version"] = new_ver
with open(sim_json_path, "w", encoding="utf-8") as fh:
    json.dump(sim_data, fh, indent=2, ensure_ascii=False)
    fh.write("\n")

replace_once(
    "keiko-sim/controllers/robot_client/robot_client.py",
    r'^(KEIKO_VERSION\s*=\s*")[^"]+(")',
    lambda m: f"{m.group(1)}{new_ver}{m.group(2)}",
)
replace_once(
    "README.md",
    r"^(Versión activa:\s*\*\*`)[^`]+(`\*\*)",
    lambda m: f"{m.group(1)}{new_ver}{m.group(2)}",
)
replace_once(
    "README.md",
    r"^(├── VERSION\s+<- Fuente única de verdad de versión \()[^)]+(\))",
    lambda m: f"{m.group(1)}{new_ver}{m.group(2)}",
)
replace_once(
    "AGENTS.md",
    r"^(- \*\*Fuente única de verdad:\*\*.*?\(actualmente `)[^`]+(`, rig)",
    lambda m: f"{m.group(1)}{new_ver}{m.group(2)}",
)
replace_once(
    "AGENTS.md",
    r"^(│?\s*\|\s*\*\*Versión y Compatibilidad\*\*.*?Versión activa \(`)[^`]+(`\))",
    lambda m: f"{m.group(1)}{new_ver}{m.group(2)}",
)
PY

exec "${REPO_ROOT}/scripts/check_versions.sh"
