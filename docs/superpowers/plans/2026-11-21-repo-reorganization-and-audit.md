# Keiko Repo Reorganization, Synchronized Versioning & Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify the diverged `main`, `origin/tests-emi`, and `origin/feat/hw-field-ready` branches into an audited, cleanly organized repository (`v2.0.0-audit.1`) with synchronized SemVer versioning (`VERSION`, `HELLO` handshake, `CHANGELOG.md`), archived legacy/experimental artifacts (`archive/`), cataloged hardware tests (`pruebas/`), updated `AGENTS.md` + READMEs, and a formal line-by-line hardcoding audit report (`docs/AUDITORIA_CONSOLIDACION.md`).

**Architecture:** Preserve all historical branch tips as immutable `archive/*` Git tags before modifying anything. Unify `keiko-sim` and `firmware` from `origin/tests-emi`, merge `keiko-server` from `origin/tests-emi` + `origin/feat/hw-field-ready` (stream-based `ManejadorCliente` + `ReceptorSerie` + `jSerialComm` + `tests-emi` fixes), add `HELLO:<version>,<rig>` handshake with TDD, move legacy/Hito/experimental files to `archive/`, and document every hardcoded rule and test result in `docs/AUDITORIA_CONSOLIDACION.md`.

**Tech Stack:** Git, Java 21 LTS, Apache Maven 3.9+ (`neuroph-core` 2.98, `jSerialComm` 2.11.0, JUnit 5.10.2), Python 3 (`unittest`), Arduino C++ (Mega 2560), Bash.

**Spec:** `docs/superpowers/specs/2026-11-21-repo-reorganization-design.md`

## Global Constraints

- **No direct push to `main`:** all work happens on branch `chore/repo-reorg-and-audit` and integrates via Pull Request.
- **No PII or local metadata in git:** never commit usernames, personal emails, local absolute paths (e.g. `/home/<user>`), IPs, or `.env` secrets.
- **Preserve history:** never rewrite or drop history of `tests-emi` or `feat/hw-field-ready`; archive their tips as tags first.
- **Audit first, strip later (Phase A = `2.0.0-audit.1`):** do NOT remove navigation logic from `PlanificadorNavegacion.java` or `ClasificadorNeuroph.java` during Phase A; unify and document every hardcoded rule and simulation shortcut accurately for Phase B (`2.0.0`).
- **Synchronized version string:** `2.0.0-audit.1` and rig identifier `mega-3us-2laser` must match across `VERSION`, `keiko-server/pom.xml`, `ConfiguracionServidor.java`, `orquita_firmware.ino`, `simulation_params.json`, and `robot_client.py`.
- **Two-network Neuroph 2.98 architecture:** `modelo_ultrasonico.nnet` (3 US) + `modelo_canicas.nnet` (2 VL53L0X + servo angle) on Arduino Mega 2560 + L293D + PCA9685 + HC-05 (`Serial1`).

---

### Task 1: Create Historical Archive Tags in Git

**Files:**
- Git tags only (no repository file edits)

**Interfaces:**
- Produces: Git tags `archive/main-pre-reorg`, `archive/feat-hw-field-ready`, `archive/tests-emi`, `archive/feat-sim-3us-2laser`.

- [ ] **Step 1: Verify exact commit hashes of all branches**

Run:
```bash
git rev-parse main origin/feat/hw-field-ready origin/tests-emi origin/feat/sim-3us-2laser
```
Expected: 4 SHA-1 hashes matching `25e2600...`, `365b998...`, `83f86bf...`, `c65b9f9...`.

- [ ] **Step 2: Create annotated archive tags locally**

Run:
```bash
git tag -a archive/main-pre-reorg 25e2600 -m "Archive main tip prior to 2.0.0-audit.1 repo reorganization"
git tag -a archive/feat-hw-field-ready 365b998 -m "Archive feat/hw-field-ready prior to unification"
git tag -a archive/tests-emi 83f86bf -m "Archive tests-emi prior to unification and audit"
git tag -a archive/feat-sim-3us-2laser c65b9f9 -m "Archive feat/sim-3us-2laser prior to cleanup"
```

- [ ] **Step 3: Verify tags exist and point to the expected commits**

Run:
```bash
git tag -n1 "archive/*"
```
Expected: All 4 `archive/*` tags listed with their messages.

---

### Task 2: Clean Local Junk and Archive Legacy, Hito & Discarded Experimental Files

**Files:**
- Delete: `docs/uml/VCU - SQs - Clases.drawio.xml`, untracked local `TestPlanner.class`, `TestQuery.class`, `keiko-server/keiko-server/`, root `client_test*.log`, `server_test*.log`
- Move to `archive/hitos/`: `HITO3/` (all contents), `docs/GUIA_MODIFICACION_DOCUMENTACION_HITO2.md`
- Move to `archive/firmware-uno/`: `firmware/orquita_firmware/orquita_firmware.ino` (current UNO sketch on `main` as `orquita_firmware_uno.ino`), `keiko-sim/docs/circuito_electronico.md`
- Move to `archive/sensores-legacy/`: `keiko-sim/docs/configuracion_sensores_ia.md`, `keiko-sim/docs/ESTUDIO_SENSORES_CONFIGURACIONES.md`
- Move to `archive/pruebas-experimentales/`: `pruebas/detector-6-ultrasonidos/`, `pruebas/lidar_scanner/`, `pruebas/vision/`, `pruebas/marble_test/`, `pruebas/prueba-trabado-vcc/`
- Create: `archive/README.md`

- [ ] **Step 1: Remove obsolete UML directory and untracked local artifacts**

Run:
```bash
git rm -r docs/uml
rm -rf TestPlanner.class TestQuery.class keiko-server/keiko-server client_test.log client_test2.log client_test3.log server_test.log server_test2.log server_test3.log
```
Expected: `docs/uml/` staged for deletion; untracked local `.class`/log/nested `keiko-server/keiko-server` removed.

- [ ] **Step 2: Create `archive/` directories and move Hito 2/3, UNO firmware, legacy sensor docs, and discarded experiments**

Run:
```bash
mkdir -p archive/hitos archive/firmware-uno archive/sensores-legacy archive/pruebas-experimentales

# Hitos
mv HITO3 archive/hitos/HITO3
git mv docs/GUIA_MODIFICACION_DOCUMENTACION_HITO2.md archive/hitos/GUIA_MODIFICACION_DOCUMENTACION_HITO2.md

# Firmware UNO & old wiring doc
git mv firmware/orquita_firmware/orquita_firmware.ino archive/firmware-uno/orquita_firmware_uno.ino
git mv keiko-sim/docs/circuito_electronico.md archive/firmware-uno/circuito_electronico_uno.md

# Legacy coaxial ultrasonic sensor study docs
git mv keiko-sim/docs/configuracion_sensores_ia.md archive/sensores-legacy/configuracion_sensores_ia.md
git mv keiko-sim/docs/ESTUDIO_SENSORES_CONFIGURACIONES.md archive/sensores-legacy/ESTUDIO_SENSORES_CONFIGURACIONES.md

# Discarded/superseded experiments from pruebas/
git mv pruebas/detector-6-ultrasonidos archive/pruebas-experimentales/detector-6-ultrasonidos
git mv pruebas/lidar_scanner archive/pruebas-experimentales/lidar_scanner
git mv pruebas/vision archive/pruebas-experimentales/vision
git mv pruebas/marble_test archive/pruebas-experimentales/marble_test
git mv pruebas/prueba-trabado-vcc archive/pruebas-experimentales/prueba-trabado-vcc
```

- [ ] **Step 3: Inspect archived experimental folders to document exact purpose accurately**

Run:
```bash
head -n 15 archive/pruebas-experimentales/marble_test/marble_test.ino archive/pruebas-experimentales/prueba-trabado-vcc/prueba-trabado-vcc.ino archive/pruebas-experimentales/detector-6-ultrasonidos/detector-6-ultrasonidos.ino
```
Expected: Exact header comments confirming what each sketch tested.

- [ ] **Step 4: Write `archive/README.md` indexing every archived item and why it was archived**

Create `archive/README.md`:
```markdown
# Archivo Histórico del Proyecto Orquitas (Keiko)

Este directorio conserva código, documentación y experimentos previos que ya no forman parte del stack activo (`Arduino Mega 2560 + L293D + PCA9685 + 3 HC-SR04 + 2 VL53L0X + estómago VL53L0X + doble red Neuroph 2.98`), para mantener trazabilidad si el equipo necesita consultar algún antecedente.

---

## Índice de Contenidos

### 1. `archive/firmware-uno/`
- `orquita_firmware_uno.ino`: Primer firmware del robot basado en **Arduino UNO R3 + Driver L298N + 4 HC-SR04** (38400 bps en D0/D1, sensor bucal digital en D2). Reemplazado por `firmware/orquita_firmware/orquita_firmware.ino` (Arduino Mega 2560).
- `circuito_electronico_uno.md`: Diagrama de cableado y BOM original para Arduino UNO + L298N. Reemplazado por `pruebas/PICKER-ESTOMAGO-Y-ALIMENTACION.md`.

### 2. `archive/sensores-legacy/`
- `configuracion_sensores_ia.md`: Especificación previa de doble plano coaxial con 4/6/8/10 sensores ultrasónicos HC-SR04.
- `ESTUDIO_SENSORES_CONFIGURACIONES.md`: Comparativa técnica entre arreglos puramente ultrasónicos (previa a la adopción del arreglo mixto `3 US + 2 Láser ToF`).

### 3. `archive/hitos/`
- `HITO3/`: Carpeta de entrega académica del Hito 3 (incluye `DCU - Keiko.docx`, `DCU - Keiko.md`, `VCU - SQs - Clases.drawio.xml` y una copia histórica estática en `codigo/*.java` que no debe editarse; el código vivo reside en `keiko-server/`).
- `GUIA_MODIFICACION_DOCUMENTACION_HITO2.md`: Guía de ajustes documentales de la segunda entrega académica.

### 4. `archive/pruebas-experimentales/`
Experimentos de hardware/percepción descartados o superados durante la exploración de campo:
- `detector-6-ultrasonidos/`: Prototipo con matriz 3x2 de 6 HC-SR04 (descartado por crosstalk acústico y menor resolución sobre canicas de 16 mm frente a VL53L0X).
- `lidar_scanner/`: Barrido 2D de un VL53L0X en PCA9685 con visualización en tiempo real (`live_plot.py`, `map_plot.py`, `room_scan.py`, `scan_match.py`, `classify_live.py`, `test_icp.py`). Útil como referencia de ICP/nube de puntos; superado por el barrido dual en espejo (`detector-mixto`).
- `vision/` (`marble_pipeline.py`): Pipeline experimental OpenCV de detección de canicas por cámara de piso; descartado del robot final por restricciones de cómputo y hardware.
- `marble_test/`: Test inicial aislado de lectura de VL53L0X con servo sobre PCA9685; reemplazado por `pruebas/prueba-vl53l0x/` y `pruebas/detector-mixto/`.
- `prueba-trabado-vcc/`: Experimento para detectar atasco del picker midiendo caída de Vcc interna del Mega (solo funciona si los servos comparten 5V del Mega; no aplica cuando el picker se alimenta con regulador externo XL4015).
```

- [ ] **Step 5: Stage and commit archive reorganization**

Run:
```bash
git add archive/
git commit -m "chore(archive): move legacy UNO firmware, hitos, and discarded experiments to archive"
```

---

### Task 3: Document Active Hardware & Simulation Tests in `pruebas/README.md`

**Files:**
- Create: `pruebas/README.md`

**Interfaces:**
- Reads headers of `pruebas/simulacion/*.py` to ensure every active simulation script is accurately listed.

- [ ] **Step 1: Inspect first lines of all scripts in `pruebas/simulacion/`**

Run:
```bash
head -n 6 pruebas/simulacion/*.py
```
Expected: Summary docstrings/comments for every `.py` script in `pruebas/simulacion/`.

- [ ] **Step 2: Write `pruebas/README.md` cataloging every active folder and file**

Create `pruebas/README.md` documenting:
1. **Tests de banco para el robot actual (Arduino Mega 2560 + L293D + PCA9685 + HC-05 + VL53L0X + HC-SR04):**
   - `detector-mixto/`: Prueba integrada del frente sensorial real (3 HC-SR04 arriba + 2 VL53L0X abajo sobre servos SG90 en PCA9685 canales 0 y 1).
   - `prueba-hc05/` y `hc05-at/`: Puente USB-Bluetooth (`Serial` <-> `Serial1` D18/D19 + `STATE` D30) y configuración de módulo HC-05 por comandos AT a 38400 bps.
   - `prueba-i2c/` y `prueba-xshut/`: Escaneo de bus I2C (`0x40` PCA9685, `0x29`/`0x30`/`0x31`/`0x32` VL53L0X) y diagnóstico de reasignación de direcciones vía pines `XSHUT`.
   - `prueba-vl53l0x/`: Lectura básica de un sensor ToF VL53L0X apuntando a canica de 16 mm.
   - `prueba-servos/` y `prueba-servo-360/`: Calibración de servos SG90 de barrido (canales 0-1, `-45°..+60°`) y servos SG90 modificados 360° del picker (canales 2-3, punto neutro y velocidad).
   - `prueba-traccion/`: Test individual de los 4 motores DC en shield L293D (`M1..M4`).
   - `prueba-picker/`: Test del motor DC auxiliar del picker con L298N (`ENA=D45, IN1=D22, IN2=D23`).
   - `picker-girando/`, `picker-estomago/`, `picker-destrabe/`: Pruebas progresivas del sistema de recolección (giro continuo, detector de captura armada en VL53L0X `0x32` de estómago, y rutina de destrabe).
2. **Bitácoras, fotos y notas técnicas:**
   - `PICKER-ESTOMAGO-Y-ALIMENTACION.md`: Pinout oficial verificado del Mega, shield L293D, PCA9685, XL4015 y estómago ToF.
   - `SIMULACION-UNITY.md`: Especificación geométrica de sensores del frente medida sobre el chasis real.
   - `bitacora-01-lidar-mapeo-y-vision.docx`, `bitacora-02-ultrasonido-vs-laser.docx`: Bitácoras de campo.
   - `fotos_auto/`, `fotos_pista/`: Registro fotográfico del prototipo y la pista.
3. **Simulaciones matemáticas en Python (`pruebas/simulacion/`):**
   - List each `.py` file verified in Step 1 (`acustica.py`, `sim_laser.py`, `sim_altura_us.py`, `sim_altura_us_detalle.py`, `sim_escenas.py`, `analisis_*.py`, `barrido_laser.py`, `fit_laser.py`, `mapa_cobertura.py`, `validar_modelo.py`) and output files (`resultados_*`, `resultados/*.png`, `mejor_ajuste.npy`).

- [ ] **Step 3: Commit `pruebas/README.md`**

Run:
```bash
git add pruebas/README.md
git commit -m "docs(pruebas): add index documenting active Mega bench tests and simulations"
```

---

### Task 4: Integrate `keiko-sim/` and `firmware/` from `origin/tests-emi` and Move Root Scripts

**Files:**
- Replace/Add from `origin/tests-emi`: `firmware/orquita_firmware/orquita_firmware.ino`, `docs/ITERACION_SIMULACION_3US_2LASER.md`, `keiko-sim/` (all updated/new controllers, physics, datasets, models, worlds, meshes, scripts), `neuroph-train/sample_data/orquitas_sensor_data.csv`, `neuroph-train/src/test/java/com/neuroph/train/TrainTwoNetworksTest.java`, `pruebas/simulacion/mejor_ajuste.npy`
- Create (moved from root): `keiko-sim/scripts/iniciar_simulacion.sh`, `keiko-sim/scripts/iterar_simulacion.sh`
- Preserve from `main`: `modelos-3d/Assembly_Main.FCStd`, `modelos-3d/Piezas/bahia-keiko.stl`
- Update: `.gitignore` (from `origin/tests-emi`)

**Interfaces:**
- Produces: Unified `firmware/orquita_firmware/orquita_firmware.ino` (Mega 2560 `7814e0b` baseline) and full `keiko-sim` simulation + dataset v2 + `.nnet` models.

- [ ] **Step 1: Checkout `tests-emi` simulation, docs, firmware, and `.gitignore` updates without touching `keiko-server/` yet**

Run:
```bash
git checkout origin/tests-emi -- \
  .gitignore \
  docs/ITERACION_SIMULACION_3US_2LASER.md \
  firmware/orquita_firmware/orquita_firmware.ino \
  keiko-sim/ \
  neuroph-train/sample_data/orquitas_sensor_data.csv \
  neuroph-train/src/test/java/com/neuroph/train/TrainTwoNetworksTest.java \
  pruebas/simulacion/mejor_ajuste.npy
```
Note: check `git status` after this to confirm `archive/sensores-legacy/` files weren't resurrected by `git checkout origin/tests-emi -- keiko-sim/`. If `keiko-sim/docs/configuracion_sensores_ia.md`, `keiko-sim/docs/ESTUDIO_SENSORES_CONFIGURACIONES.md`, or `keiko-sim/docs/circuito_electronico.md` reappeared in `keiko-sim/docs/`, remove those 3 duplicates because they already live in `archive/`!

Run:
```bash
rm -f keiko-sim/docs/circuito_electronico.md keiko-sim/docs/configuracion_sensores_ia.md keiko-sim/docs/ESTUDIO_SENSORES_CONFIGURACIONES.md
```

- [ ] **Step 2: Move `iniciar_simulacion.sh` and `iterar_simulacion.sh` from `origin/tests-emi` into `keiko-sim/scripts/` and fix `ROOT_DIR`**

Inspect `git show origin/tests-emi:iniciar_simulacion.sh` and `git show origin/tests-emi:iterar_simulacion.sh` completely first, then write them into `keiko-sim/scripts/iniciar_simulacion.sh` and `keiko-sim/scripts/iterar_simulacion.sh` changing `SCRIPT_DIR` -> `REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"` so every path (`keiko-sim/...`, `keiko-server/...`) resolves identically whether invoked from repo root (`./keiko-sim/scripts/iniciar_simulacion.sh`) or from inside `keiko-sim/scripts/`. Mark both executable (`chmod +x`).

- [ ] **Step 3: Run `keiko-sim` Python unit tests**

Run:
```bash
python3 -m unittest discover -s keiko-sim/tests -v
```
Expected: Inspect exact pass/fail results (if any test reflects pre-3US+2Laser assumptions, record it accurately rather than hiding it).

- [ ] **Step 4: Commit unified `firmware/`, `keiko-sim/`, and simulation scripts**

Run:
```bash
git add .gitignore docs/ITERACION_SIMULACION_3US_2LASER.md firmware/ keiko-sim/ neuroph-train/ pruebas/simulacion/mejor_ajuste.npy
git commit -m "feat(sim,firmware): unify Mega firmware and 3US+2Laser Webots suite from tests-emi"
```

---

### Task 5: Unify `keiko-server/` (`tests-emi` + `feat/hw-field-ready`)

**Files:**
- Modify/Add in `keiko-server/`:
  - `pom.xml` (base `tests-emi` + `jSerialComm` `2.11.0` from `feat/hw-field-ready`)
  - `.env.example`, `scripts/bt_rfcomm_bridge.py` (from `feat/hw-field-ready`)
  - `src/main/java/com/orquitas/server/ConfiguracionServidor.java` (from `feat/hw-field-ready`: `--serial`, `--baud`, `--list-ports`)
  - `src/main/java/com/orquitas/server/ServidorCentral.java` (from `feat/hw-field-ready`: `ReceptorSerie` lifecycle + banner)
  - `src/main/java/com/orquitas/server/ReceptorSerie.java` (from `feat/hw-field-ready`)
  - `src/main/java/com/orquitas/server/ReceptorConexiones.java` (base `feat/hw-field-ready` + defensive copy fix in `detener()` from `tests-emi` `83f86bf`)
  - `src/main/java/com/orquitas/server/ManejadorCliente.java` (base `feat/hw-field-ready` stream/cooldown/heartbeat + `tests-emi` `notificarColision(lado)` / `SALIDA_META:` / telemetry log until Phase B audit cleanup)
  - `src/main/java/com/orquitas/server/MensajeProtocolo.java`, `VectorSensores.java`, `ClasificadorNeuroph.java`, `PlanificadorNavegacion.java` (from `tests-emi` `83f86bf`)
  - `src/test/java/com/orquitas/server/HardwareSerialAndCaptureTest.java`, `Hito2IntegrationTest.java`, `NeurophInferenceTest.java` (from `feat/hw-field-ready`)
  - `src/test/java/com/orquitas/server/PlanificadorNavegacionTest.java`, `TwoNetworksInferenceTest.java` (from `tests-emi`)
  - `models/modelo_canicas.nnet`, `models/modelo_ultrasonico.nnet`, `src/main/resources/models/modelo_canicas.nnet`, `src/main/resources/models/modelo_ultrasonico.nnet` (synced from `keiko-sim/data/models/` of `tests-emi`)

- [ ] **Step 1: Bring `tests-emi` base server files + `feat/hw-field-ready` serial/test files**

Run:
```bash
git checkout origin/tests-emi -- \
  keiko-server/src/main/java/com/orquitas/server/ClasificadorNeuroph.java \
  keiko-server/src/main/java/com/orquitas/server/MensajeProtocolo.java \
  keiko-server/src/main/java/com/orquitas/server/PlanificadorNavegacion.java \
  keiko-server/src/main/java/com/orquitas/server/VectorSensores.java \
  keiko-server/src/test/java/com/orquitas/server/PlanificadorNavegacionTest.java \
  keiko-server/src/test/java/com/orquitas/server/TwoNetworksInferenceTest.java

git checkout origin/feat/hw-field-ready -- \
  keiko-server/.env.example \
  keiko-server/scripts/bt_rfcomm_bridge.py \
  keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java \
  keiko-server/src/main/java/com/orquitas/server/ReceptorSerie.java \
  keiko-server/src/main/java/com/orquitas/server/ServidorCentral.java \
  keiko-server/src/test/java/com/orquitas/server/HardwareSerialAndCaptureTest.java \
  keiko-server/src/test/java/com/orquitas/server/Hito2IntegrationTest.java \
  keiko-server/src/test/java/com/orquitas/server/NeurophInferenceTest.java
```

- [ ] **Step 2: Synchronize `.nnet` models in `keiko-server` from `keiko-sim/data/models/` and record diff vs `feat/hw-field-ready` for the audit**

Run:
```bash
mkdir -p keiko-server/models keiko-server/src/main/resources/models
cp keiko-sim/data/models/modelo_ultrasonico.nnet keiko-server/models/modelo_ultrasonico.nnet
cp keiko-sim/data/models/modelo_canicas.nnet keiko-server/models/modelo_canicas.nnet
cp keiko-sim/data/models/modelo_ultrasonico.nnet keiko-server/src/main/resources/models/modelo_ultrasonico.nnet
cp keiko-sim/data/models/modelo_canicas.nnet keiko-server/src/main/resources/models/modelo_canicas.nnet
```

- [ ] **Step 3: Merge `keiko-server/pom.xml`, `ReceptorConexiones.java`, and `ManejadorCliente.java` cleanly**

- In `keiko-server/pom.xml`: start from `origin/tests-emi:keiko-server/pom.xml` and add `<jserialcomm.version>2.11.0</jserialcomm.version>` + the `com.fazecast:jSerialComm` dependency.
- In `ReceptorConexiones.java`: start from `origin/feat/hw-field-ready:keiko-server/src/main/java/com/orquitas/server/ReceptorConexiones.java` and apply `origin/tests-emi`'s defensive copy in `detener()`:
  ```java
  List<ManejadorCliente> copiaClientes;
  synchronized (clientesConectados) {
      copiaClientes = new ArrayList<>(clientesConectados);
      clientesConectados.clear();
  }
  for (ManejadorCliente client : copiaClientes) {
      client.cerrar();
  }
  ```
- In `ManejadorCliente.java`: start from `origin/feat/hw-field-ready:keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java` (preserving stream constructor, heartbeat thread, and capture cooldown) and preserve `tests-emi` event branches (`COLISION_` side parsing calling `planificador.notificarColision(lado)` and `SALIDA_META:` calling `planificador.establecerSalidaMeta(ex, ey)` — noting that `SALIDA_META:` will be flagged for removal in `AUDITORIA_CONSOLIDACION.md`).

- [ ] **Step 4: Run `mvn clean test` to verify compilation and tests after server unification**

Run:
```bash
cd keiko-server && mvn clean test
```
Expected: Compile succeeds and JUnit tests execute (if any test fails due to updated `.nnet` or branch mismatches, inspect and resolve test compatibility or document accurately before proceeding).

- [ ] **Step 5: Commit unified `keiko-server/`**

Run:
```bash
git add keiko-server/
git commit -m "feat(server): unify tests-emi dual-network planner with hw-field-ready serial SPP support"
```

---

### Task 6: Implement Synchronized Versioning (`VERSION`, `check_versions.sh`) and `HELLO` Handshake (TDD)

**Files:**
- Create: `VERSION` (`2.0.0-audit.1`)
- Create: `scripts/check_versions.sh`
- Modify: `keiko-server/pom.xml` (`<version>2.0.0-audit.1</version>`)
- Modify: `keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java` (add `VERSION_SISTEMA`, `RIG_HARDWARE`)
- Modify: `keiko-server/src/main/java/com/orquitas/server/MensajeProtocolo.java` (add `PREFIX_HELLO = "HELLO:"`, `InfoHandshake`, `parsearHello(String)`, `crearHello(String, String)`, `esCompatibleMajorMinor(String, String)`)
- Modify: `keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java` (dispatch `PREFIX_HELLO`, log client version/rig, warn `[VERSION MISMATCH]` on Major/Minor or rig mismatch)
- Modify: `keiko-server/src/main/java/com/orquitas/server/ServidorCentral.java` (print `VERSION_SISTEMA` and `RIG_HARDWARE` in startup banner)
- Modify: `firmware/orquita_firmware/orquita_firmware.ino` (add `#define KEIKO_VERSION "2.0.0-audit.1"`, `#define KEIKO_RIG "mega-3us-2laser"`, and send `HELLO:` on startup / competition activation)
- Modify: `keiko-sim/config/simulation_params.json` (add `"version": "2.0.0-audit.1"`, `"rig": "mega-3us-2laser"`)
- Modify: `keiko-sim/controllers/robot_client/robot_client.py` (define `KEIKO_VERSION`, `KEIKO_RIG`, send `HELLO:<version>,<rig>\n` right after socket connect)
- Test: `keiko-server/src/test/java/com/orquitas/server/ProtocolParserTest.java`

**Interfaces:**
- `MensajeProtocolo.PREFIX_HELLO` (`"HELLO:"`)
- `MensajeProtocolo.InfoHandshake` (record `(String version, String rig)`)
- `MensajeProtocolo.parsearHello(String linea) -> InfoHandshake`
- `MensajeProtocolo.crearHello(String version, String rig) -> String`
- `MensajeProtocolo.esCompatibleMajorMinor(String versionA, String versionB) -> boolean`
- `ConfiguracionServidor.VERSION_SISTEMA = "2.0.0-audit.1"`
- `ConfiguracionServidor.RIG_HARDWARE = "mega-3us-2laser"`

- [ ] **Step 1: Write failing tests in `ProtocolParserTest.java` for `HELLO` parsing & Major.Minor compatibility**

Add tests to `keiko-server/src/test/java/com/orquitas/server/ProtocolParserTest.java`:
```java
@Test
void parsearHelloValidoYCompatibilidad() {
    MensajeProtocolo.InfoHandshake info = MensajeProtocolo.parsearHello("HELLO:2.0.0-audit.1,mega-3us-2laser");
    assertEquals("2.0.0-audit.1", info.version());
    assertEquals("mega-3us-2laser", info.rig());
    assertTrue(MensajeProtocolo.esCompatibleMajorMinor("2.0.0-audit.1", "2.0.3"));
    assertFalse(MensajeProtocolo.esCompatibleMajorMinor("2.0.0-audit.1", "2.1.0"));
    assertFalse(MensajeProtocolo.esCompatibleMajorMinor("2.0.0-audit.1", "1.0.0"));
}

@Test
void parsearHelloInvalidoLanzaExcepcion() {
    assertThrows(IllegalArgumentException.class, () -> MensajeProtocolo.parsearHello("HELLO:"));
    assertThrows(IllegalArgumentException.class, () -> MensajeProtocolo.parsearHello("TELEMETRY:1,2,3,4"));
}
```

- [ ] **Step 2: Run `ProtocolParserTest` to confirm it fails (Red)**

Run:
```bash
cd keiko-server && mvn test -Dtest=ProtocolParserTest
```
Expected: FAIL (compilation error: `InfoHandshake`, `parsearHello`, `esCompatibleMajorMinor` not defined).

- [ ] **Step 3: Create root `VERSION` and implement `ConfiguracionServidor`, `MensajeProtocolo`, `ManejadorCliente`, `ServidorCentral`, and `pom.xml` version**

1. Create `/VERSION` containing `2.0.0-audit.1`.
2. Update `<version>2.0.0-audit.1</version>` in `keiko-server/pom.xml`.
3. Add to `ConfiguracionServidor.java`:
   ```java
   public static final String VERSION_SISTEMA = "2.0.0-audit.1";
   public static final String RIG_HARDWARE = "mega-3us-2laser";
   ```
4. Add to `MensajeProtocolo.java`:
   ```java
   public static final String PREFIX_HELLO = "HELLO:";

   public record InfoHandshake(String version, String rig) {}

   public static InfoHandshake parsearHello(String linea) {
       if (linea == null || !linea.startsWith(PREFIX_HELLO)) {
           throw new IllegalArgumentException("Prefijo HELLO invalido");
       }
       String payload = linea.substring(PREFIX_HELLO.length()).trim();
       if (payload.isEmpty()) {
           throw new IllegalArgumentException("Payload HELLO vacio");
       }
       String[] partes = payload.split(",", 2);
       String version = partes[0].trim();
       if (version.isEmpty()) {
           throw new IllegalArgumentException("Version vacia en HELLO");
       }
       String rig = (partes.length > 1 && !partes[1].trim().isEmpty()) ? partes[1].trim() : "desconocido";
       return new InfoHandshake(version, rig);
   }

   public static String crearHello(String version, String rig) {
       return String.format(Locale.US, "%s%s,%s\n", PREFIX_HELLO, version, rig);
   }

   public static boolean esCompatibleMajorMinor(String versionA, String versionB) {
       if (versionA == null || versionB == null) return false;
       String[] a = versionA.split("-")[0].split("\\.");
       String[] b = versionB.split("-")[0].split("\\.");
       if (a.length < 2 || b.length < 2) return false;
       return a[0].equals(b[0]) && a[1].equals(b[1]);
   }
   ```
5. In `ManejadorCliente.despacharLinea(String)`: handle `PREFIX_HELLO` via `procesarHello(linea)`:
   ```java
   } else if (linea.startsWith(MensajeProtocolo.PREFIX_HELLO)) {
       procesarHello(linea);
   ```
   ```java
   private void procesarHello(String linea) {
       try {
           MensajeProtocolo.InfoHandshake hs = MensajeProtocolo.parsearHello(linea);
           boolean versionOk = MensajeProtocolo.esCompatibleMajorMinor(
                   ConfiguracionServidor.VERSION_SISTEMA, hs.version());
           boolean rigOk = ConfiguracionServidor.RIG_HARDWARE.equals(hs.rig());
           if (versionOk && rigOk) {
               LOGGER.info(String.format("[HANDSHAKE OK] Cliente=%s | Version=%s | Rig=%s",
                       descripcionOrigen, hs.version(), hs.rig()));
           } else {
               LOGGER.warning(String.format(
                       "[VERSION MISMATCH] Cliente=%s reporta Version=%s Rig=%s | Esperado Version=%s.* Rig=%s",
                       descripcionOrigen, hs.version(), hs.rig(),
                       ConfiguracionServidor.VERSION_SISTEMA, ConfiguracionServidor.RIG_HARDWARE));
           }
       } catch (Exception e) {
           LOGGER.warning("[HANDSHAKE] Error al parsear HELLO: " + e.getMessage());
       }
   }
   ```
6. In `ServidorCentral.imprimirBanner()`: print `Version: %s | Rig: %s` using `ConfiguracionServidor.VERSION_SISTEMA` and `ConfiguracionServidor.RIG_HARDWARE`.

- [ ] **Step 4: Run `ProtocolParserTest` and full Maven test suite (Green)**

Run:
```bash
cd keiko-server && mvn test
```
Expected: PASS on `ProtocolParserTest` and full suite.

- [ ] **Step 5: Propagate `VERSION` and `HELLO:` handshake to `orquita_firmware.ino`, `simulation_params.json`, and `robot_client.py`**

1. In `firmware/orquita_firmware/orquita_firmware.ino`:
   - Add at top configuration section:
     ```cpp
     #define KEIKO_VERSION "2.0.0-audit.1"
     #define KEIKO_RIG     "mega-3us-2laser"
     ```
   - Add helper `enviarHello()`:
     ```cpp
     void enviarHello() {
       LINK_SERIAL.print(F("HELLO:"));
       LINK_SERIAL.print(F(KEIKO_VERSION));
       LINK_SERIAL.print(F(","));
       LINK_SERIAL.println(F(KEIKO_RIG));
     }
     ```
   - Call `enviarHello()` at end of `setup()` and when `modoCompetencia` switches to `true` (so the server sees it both on serial/BT connect and on run start).
2. In `keiko-sim/config/simulation_params.json`: add top-level `"version": "2.0.0-audit.1"` and `"rig": "mega-3us-2laser"`.
3. In `keiko-sim/controllers/robot_client/robot_client.py`:
   - Define `KEIKO_VERSION = "2.0.0-audit.1"` and `KEIKO_RIG = "mega-3us-2laser"`.
   - In `try_connect()`, immediately after `s.connect(...)`, send `f"HELLO:{KEIKO_VERSION},{KEIKO_RIG}\n".encode("utf-8")`.

- [ ] **Step 6: Create and test `scripts/check_versions.sh`**

Create `scripts/check_versions.sh` (`chmod +x`):
```bash
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
```

Run `./scripts/check_versions.sh` -> Expected: `[OK]` on all 9 checks, exit code `0`.

- [ ] **Step 7: Commit synchronized versioning and `HELLO` handshake**

Run:
```bash
git add VERSION scripts/check_versions.sh keiko-server/ firmware/orquita_firmware/orquita_firmware.ino keiko-sim/config/simulation_params.json keiko-sim/controllers/robot_client/robot_client.py
git commit -m "feat(versioning): add VERSION, HELLO handshake and cross-module version check"
```

---

### Task 7: Conduct Full Hardcoding & Simulation-Shortcut Audit and Write `docs/AUDITORIA_CONSOLIDACION.md`

**Files:**
- Create: `docs/AUDITORIA_CONSOLIDACION.md`

**Interfaces:**
- Reads: `PlanificadorNavegacion.java`, `ClasificadorNeuroph.java`, `ManejadorCliente.java`, `robot_client.py`, `orquita_firmware.ino`, binary hashes of `.nnet` models across `feat/hw-field-ready` vs `tests-emi`, and live test runs.

- [ ] **Step 1: Collect exact line numbers and symbols of every raw-sensor threshold and pose hack**

Run diagnostic searches across `keiko-server/src/main/java/com/orquitas/server/` and `keiko-sim/controllers/robot_client/robot_client.py` to inspect every single occurrence of `getUs*`, `getLaser*`, `angServo`, `hasPose`, `getPosX`, `getPosY`, `getTheta`, `SALIDA_META`, and `.nnet` SHA-256 hashes (`sha256sum` comparing `origin/feat/hw-field-ready` vs unified models).

- [ ] **Step 2: Run full test verification suite to capture exact observed outputs**

Run:
```bash
./scripts/check_versions.sh
cd keiko-server && mvn clean test
python3 -m unittest discover -s keiko-sim/tests -v
```
Record exact counts, successes, failures, and environment caveats (no fabricated numbers).

- [ ] **Step 3: Write `docs/AUDITORIA_CONSOLIDACION.md`**

Include:
1. Resumen ejecutivo de unificación (`main` + `tests-emi` + `feat/hw-field-ready` -> `v2.0.0-audit.1`).
2. Matriz de origen de componentes y comparación SHA-256 de modelos `.nnet`.
3. Tabla detallada de hallazgos prohibidos (`[PROHIBIDO - DECISIÓN SENSORIAL HARDCODEADA]` y `[PROHIBIDO - ATAJO SIMULACIÓN]`) con archivo, rango de líneas exacto, fragmento/regla detectada, impacto y acción requerida para Fase B (`v2.0.0`).
4. Tabla de mecanismos permitidos (`[PERMITIDO - MEMORIA FSM / SEGURIDAD ENLACE]`).
5. Resultados reales observados de pruebas (`mvn clean test`, `unittest`, `check_versions.sh`) y hoja de ruta puntual para Fase B (`v2.0.0`).

- [ ] **Step 4: Commit audit report**

Run:
```bash
git add docs/AUDITORIA_CONSOLIDACION.md
git commit -m "docs(audit): publish branch consolidation and anti-hardcoding audit report"
```

---

### Task 8: Rewrite `AGENTS.md`, Create `CHANGELOG.md`, and Align All `README.md` Files

**Files:**
- Create: `CHANGELOG.md`
- Rewrite: `AGENTS.md`
- Modify: `README.md`, `keiko-server/README.md`, `keiko-sim/README.md`

- [ ] **Step 1: Write `CHANGELOG.md` at repository root**

Document `2.0.0-audit.1` (date `2026-11-21`) with:
- Compatibility table (`keiko-server 2.0.0-audit.1` <-> `firmware mega-3us-2laser 2.0.0-audit.1` <-> `keiko-sim 2.0.0-audit.1` <-> `modelo_ultrasonico.nnet` + `modelo_canicas.nnet`).
- Detailed changes (`Added`, `Changed`, `Archived`, `Removed`, `Known Audit Findings`, `Tests Run`) explaining *what* changed and *why*.
- Historical summary section referencing `archive/*` tags (`1.x` UNO legacy and pre-unification branches).

- [ ] **Step 2: Rewrite `AGENTS.md`**

Ensure `AGENTS.md` contains:
- Git/PR workflow (no direct push to `main`, branches, Conventional Commits).
- **Mandatory Versioning & Changelog rule:** any code/model/simulation change must keep `VERSION` synced (`./scripts/check_versions.sh`), preserve `HELLO:<version>,<rig>`, and add an entry to `CHANGELOG.md` stating what changed, why, and test evidence.
- **Official Physical Hardware Architecture:** Arduino Mega 2560 + L293D + PCA9685 + 3 HC-SR04 + 2 VL53L0X on SG90 servos + stomach VL53L0X (`0x32`) + picker + HC-05 (`Serial1`).
- **Dual Neuroph 2.98 AI Pipeline & Strict Anti-Hardcoding Rule:** `modelo_ultrasonico.nnet` (`BLOQUEO`, `ESPACIO_LIBRE`, `SALIDA`) + `modelo_canicas.nnet` (`LOBITO_CENTRO`, `LOBITO_DER`, `LOBITO_IZQ`, `NINGUNA`); strictly forbidden to code navigation thresholds on raw sensor readings or use `hasPose()`/`posX`/`posY`/`theta`/`SALIDA_META` outside simulation logging/visualization.
- **Protocol & Persistence:** `HELLO`, `TELEMETRY`, `COMMAND`, `EVENT`, `STATE_CHANGE`, `HEARTBEAT`; `estomago.txt` (`ReentrantReadWriteLock` + `FileDescriptor.sync()` + `AuditorEstomago`); Fail-Safe `< 500 ms`.
- **Updated Documentation Router & Repo Map:** covering `keiko-server/`, `keiko-sim/`, `firmware/`, `neuroph-train/`, `modelos-3d/`, `pruebas/`, `docs/AUDITORIA_CONSOLIDACION.md`, `archive/`, `VERSION`, `CHANGELOG.md`, `scripts/check_versions.sh` — with zero references to deleted `docs/uml/` or archived Hito 1-3 files.

- [ ] **Step 3: Update `README.md`, `keiko-server/README.md`, and `keiko-sim/README.md`**

Fix all stale claims:
- `README.md`: replace UNO/L298N/4-US/`(4 -> 24 -> 5)` with Mega 2560 + 3US + 2VL53L0X + dual Neuroph, add `VERSION`/`CHANGELOG.md`/`pruebas/`/`archive/`/`modelos-3d/` to map, update quick commands to `./keiko-sim/scripts/iniciar_simulacion.sh` and `./scripts/check_versions.sh`.
- `keiko-server/README.md`: replace `[4 sensores]` and `5 clases` with dual Neuroph 2.98 (`3US + 2Laser`), `--serial` / Bluetooth SPP flags, `HELLO` handshake, and Maven `mvn clean test` / `mvn package`.
- `keiko-sim/README.md`: replace `2.4 m x 2.4 m` with semi-elliptical Bahía de Caza (`~1.80 m` base x `~1.20 m` depth), document `scen_1..14`, `circular`, `iniciar_simulacion.sh`, `iterar_simulacion.sh`, and `robot_client.py` 3US+2Laser rig.

- [ ] **Step 4: Commit documentation updates**

Run:
```bash
git add AGENTS.md CHANGELOG.md README.md keiko-server/README.md keiko-sim/README.md
git commit -m "docs: rewrite AGENTS.md, add CHANGELOG.md and align module READMEs"
```

---

### Task 9: Final Verification & PR Preparation

**Files:**
- None (verification only)

- [ ] **Step 1: Run all verification checks after final edit**

Run:
```bash
./scripts/check_versions.sh
cd keiko-server && mvn clean test && mvn package -DskipTests=true
cd .. && python3 -m unittest discover -s keiko-sim/tests -v
git status
```
Expected: `check_versions.sh` exits 0; `mvn clean test` and `mvn package` succeed; working tree is clean on `chore/repo-reorg-and-audit`.

- [ ] **Step 2: Confirm with user before pushing tags/branch to `origin` and opening PR**

Present observed test results and ask user confirmation to push `archive/*` tags + `chore/repo-reorg-and-audit` to `origin` for Pull Request review (and delete stale remote branches after PR squash-merge).
