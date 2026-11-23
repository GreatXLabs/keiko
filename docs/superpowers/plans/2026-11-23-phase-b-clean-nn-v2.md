# Phase B (`v2.0.0`): Pure Neuroph 2.98 Navigation & Sim-Real Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate all hardcoded sensor thresholds (`HRD-01..14`) and simulation-only pose/event shortcuts (`SIM-01..13`), align `robot_client.py` and `keiko-sim` tests (`100%` passing), validate/retrain Neuroph 2.98 `.nnet` models if needed, harden competition firmware (`COMP-03`), and release synchronized version **`2.0.0`**.

**Architecture:** Strip pose fields and raw-threshold overrides from `VectorSensores`, `MensajeProtocolo`, `ManejadorCliente`, and `ClasificadorNeuroph`, and rewrite `PlanificadorNavegacion` as a pure symbolic FSM operating solely on Neuroph's `[IZQ, CEN, DER]` classification triplet plus step/oscillation memory. Align `robot_client.py` to emit identical 7-field telemetry and `[-45°, +60°]` continuous sweep as the Mega firmware, fix `live_visualizer.py` and `build_worlds.py` (`0.300 m` net exit opening), validate headless Webots navigation (retraining `.nnet` via Neuroph 2.98 if required), and bump the entire ecosystem to `2.0.0`.

**Tech Stack:** Java 21 LTS, Apache Maven 3.9+ (`neuroph-core` 2.98, `jSerialComm` 2.11.0, JUnit 5.10.2), Python 3 (`unittest`), Webots R2025a (headless `--batch --no-rendering`), Arduino C++ (Mega 2560), Bash.

**Spec:** `docs/superpowers/specs/2026-11-23-phase-b-clean-nn-v2-design.md`

## Global Constraints

- **No direct push to `main`:** work on `feat/phase-b-pure-nn-v2` and integrate via Pull Request.
- **No PII or local metadata in git:** never commit usernames, personal emails, local absolute paths (e.g. `/home/<user>`), IPs, or `.env` secrets.
- **Zero hardcoded sensor decisions:** neither `ClasificadorNeuroph` nor `PlanificadorNavegacion` may use raw distances (`getUs*`, `getLaser*`, `getAnguloServo`, cm/degree `if` thresholds) to classify, filter, override, or drive navigation.
- **Zero simulation pose shortcuts:** no `hasPose`, `posX`, `posY`, `theta`, `EVENT:SALIDA_META`, or `EVENT:COLISION` navigation logic between simulator and server.
- **Synchronized version `2.0.0` / `mega-3us-2laser`:** enforced at release close via `./scripts/check_versions.sh` (`9/9 [OK]`).

---

### Task 1: Strip Pose & Collision Coupling from Telemetry and Client Handler (`SIM-03`, `SIM-04`, `COMP-02`)

**Files:**
- Modify: `keiko-server/src/main/java/com/orquitas/server/VectorSensores.java`
- Modify: `keiko-server/src/main/java/com/orquitas/server/MensajeProtocolo.java`
- Modify: `keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java`
- Test: `keiko-server/src/test/java/com/orquitas/server/ProtocolParserTest.java`
- Test: `keiko-server/src/test/java/com/orquitas/server/HardwareSerialAndCaptureTest.java`

**Interfaces:**
- Removes from `VectorSensores`: `hasPose()`, `getPosX()`, `getPosY()`, `getTheta()`, and 6-arg pose constructor. Keeps `(double[] canales, long timestamp)` and legacy 4/6-arg sensor-only constructors.
- Updates `MensajeProtocolo.parsearTelemetria(String)`: accepts 4/6/8/10 channels (+ optional 1 timestamp field = 5/7/9/11 tokens); rejects 12/14-token pose payloads (`IllegalArgumentException`) and never extracts `(x, y, theta)`.
- Updates `ManejadorCliente.procesarEvento(String)`: removes `SALIDA_META:` branch and removes `planificador.notificarColision(...)` call on `COLISION` (keeps capture handling intact).

- [ ] **Step 1: Update/write failing tests in `ProtocolParserTest.java` and `HardwareSerialAndCaptureTest.java` (RED)**

1. In `ProtocolParserTest.java`:
   - Update `testParseValid6SensorTelemetry` to use 7 tokens (`6 sensors + ts`, no pose) and remove `hasPose()`/`getPosX()`/`getPosY()` assertions.
   - Update `testParseValid8SensorTelemetry` to use 9 tokens (`8 sensors + ts`, no pose).
   - Add `testRejectsTelemetryWithLegacyPosePayload()` asserting `IllegalArgumentException` for 12-token and 14-token pose payloads.
2. In `HardwareSerialAndCaptureTest.java`:
   - Add `testStreamSessionIgnoresDeprecatedSimulationEvents()` sending `EVENT:SALIDA_META:0.00,0.60\n` and `EVENT:COLISION,impacto=1,lado=front_left\n` followed by `TELEMETRY:...`, asserting the session stays connected, `planificador.getEstado()` remains `BUSQUEDA`, and no `SALIDA_META` shortcut alters state.

- [ ] **Step 2: Run tests to verify failure (RED)**

Run:
```bash
cd keiko-server && mvn test -Dtest=ProtocolParserTest,HardwareSerialAndCaptureTest
```
Expected: FAIL (`12/14`-token telemetry still parsed instead of throwing `IllegalArgumentException`).

- [ ] **Step 3: Remove pose support and simulation event coupling from `VectorSensores.java`, `MensajeProtocolo.java`, and `ManejadorCliente.java`**

1. In `VectorSensores.java`: remove `hasPose`, `posX`, `posY`, `theta` fields, the 6-argument constructor `(double[], long, boolean, double, double, double)`, and `hasPose()`/`getPosX()`/`getPosY()`/`getTheta()` getters. Keep `VectorSensores(double[] canales, long timestamp)` and the sensor-only constructors.
2. In `MensajeProtocolo.java`: simplify `parsearTelemetria(String)` to accept `4, 6, 8, 10` sensor tokens (or `5, 7, 9, 11` with timestamp); throw `IllegalArgumentException("Cantidad de campos de telemetria no soportada: " + total)` for any other token count (including 12/14). Construct `new VectorSensores(canales, ts)`.
3. In `ManejadorCliente.java`: in `procesarEvento`, remove the `COLISION` (`planificador.notificarColision`) and `SALIDA_META:` (`planificador.establecerSalidaMeta`) branches (log `[EVENTO RECIBIDO]` or ignore simulation-only events cleanly without mutating planner state).

- [ ] **Step 4: Run tests to verify pass (GREEN) — note `PlanificadorNavegacion.java` may still fail compile if it calls `hasPose()`**

Run `cd keiko-server && mvn test -Dtest=ProtocolParserTest,HardwareSerialAndCaptureTest` (if `PlanificadorNavegacion.java` breaks compilation because it still references `hasPose()`, either leave stub or execute Task 3 immediately after Task 2 — check cleanly!). Note in pre-flight: Tasks 1-3 all touch `keiko-server` main classes; running `mvn test` after Task 3 verifies all server classes together, OR keep `VectorSensores.hasPose()` temporarily deprecated ONLY until Task 3 deletes it? Better ruling: delete `hasPose()` in Task 1 and if `mvn test` needs `PlanificadorNavegacion` updated, check compilation after Task 3, OR delete `hasPose()`/`establecerSalidaMeta` references in `PlanificadorNavegacion` in Task 3 — wait: `writing-plans` says each task ends with an independently testable deliverable! So let's keep the compile clean in Task 1 by cleaning `PlanificadorNavegacion`'s `hasPose`/`establecerSalidaMeta` calls in Task 3 *or* ordering **Task 3 (`PlanificadorNavegacion`) BEFORE deleting `VectorSensores.hasPose()`**!
Look how smart that ordering is:
- In Task 1: update `MensajeProtocolo.parsearTelemetria` (stop parsing pose, reject 12/14 tokens), update `ManejadorCliente` (stop calling `establecerSalidaMeta`/`notificarColision`), update `ProtocolParserTest` & `HardwareSerialAndCaptureTest` -> **`mvn test` compiles and passes independently!**
- In Task 2: clean `ClasificadorNeuroph` (`HRD-01..02`) -> **`mvn test` compiles and passes independently!**
- In Task 3: rewrite `PlanificadorNavegacion` cleanly (removing all `SIM-05..13` and `HRD-03..14`) AND delete `hasPose`/`getPos*`/`getTheta` from `VectorSensores` -> **`mvn clean test` compiles and passes 100% independently!**

- [ ] **Step 5: Commit Task 1**

Run:
```bash
git add keiko-server/src/main/java/com/orquitas/server/MensajeProtocolo.java keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java keiko-server/src/test/java/com/orquitas/server/ProtocolParserTest.java keiko-server/src/test/java/com/orquitas/server/HardwareSerialAndCaptureTest.java
git commit -m "refactor(protocol): drop pose telemetry parsing and simulation event hooks"
```

---

### Task 2: Pure Neuroph 2.98 Inference in `ClasificadorNeuroph` (`HRD-01`, `HRD-02`, `COMP-05`)

**Files:**
- Modify: `keiko-server/src/main/java/com/orquitas/server/ClasificadorNeuroph.java`
- Test: `keiko-server/src/test/java/com/orquitas/server/TwoNetworksInferenceTest.java`

**Interfaces:**
- `ClasificadorNeuroph.clasificarCanicas(VectorSensores)` returns `CLASES_CANICAS_4[maxIdx]` directly from `neuralNetCanicas.getOutput()` without `servoDeg >= 18.0` or `dl < dr - 3.5` overrides.

- [ ] **Step 1: Add pure-NN unit assertion in `TwoNetworksInferenceTest.java`**

Verify `clasificarCanicas` matches direct `NeuralNetwork.calculate()` argmax (no hardcoded overrides), and run `mvn test -Dtest=TwoNetworksInferenceTest`.

- [ ] **Step 2: Remove `HRD-01` and `HRD-02` blocks from `ClasificadorNeuroph.java` (`lines 186-220`)**

In `ClasificadorNeuroph.clasificarCanicas(VectorSensores)`: remove the `if (servoDeg >= 18.0)` kinematic filter and the `if (!"NINGUNA".equals(clase) && sensores.getUsCenter() >= 16.0)` binocular override; return `CLASES_CANICAS_4[maxIdx]` directly.

- [ ] **Step 3: Run `cd keiko-server && mvn test`**

Record exact results of `TwoNetworksInferenceTest` (`14` marble assertions + `Red1` + `DualFusion`). If any specific test vector in `TwoNetworksInferenceTest.testRed2MarbleDetection()` fails purely because the existing `modelo_canicas.nnet` relied on `HRD-01`/`HRD-02`, document the exact failing vectors in the Task 2 report for Task 6 retraining (do NOT re-introduce `if` overrides or delete assertions prematurely!).

- [ ] **Step 4: Commit Task 2**

Run:
```bash
git add keiko-server/src/main/java/com/orquitas/server/ClasificadorNeuroph.java keiko-server/src/test/java/com/orquitas/server/TwoNetworksInferenceTest.java
git commit -m "refactor(ai): remove post-inference hardcoded filters in ClasificadorNeuroph"
```

---

### Task 3: Pure Symbolic FSM in `PlanificadorNavegacion` & Remove `VectorSensores` Pose Fields (`SIM-05..13`, `HRD-03..14`)

**Files:**
- Modify: `keiko-server/src/main/java/com/orquitas/server/PlanificadorNavegacion.java`
- Modify: `keiko-server/src/main/java/com/orquitas/server/VectorSensores.java`
- Test: `keiko-server/src/test/java/com/orquitas/server/PlanificadorNavegacionTest.java`

**Interfaces:**
- `PlanificadorNavegacion`: keeps public API `PlanificadorNavegacion(GestorEstomago)`, `evaluarPares(VectorSensores, String[])`, `activarModoEscape()`, `finalizar()`, `notificarCaptura()`, `getEstado()`, `getCiclosSinSalidaEscape()` (if needed) — removes `establecerSalidaMeta`, `notificarColision`, and ALL raw sensor / pose reads.
- `VectorSensores`: removes `hasPose`, `posX`, `posY`, `theta` fields, 6-arg constructor, and getters.

- [ ] **Step 1: Add anti-hardcoding invariance test in `PlanificadorNavegacionTest.java` (RED)**

Add `testDecisionesDependenSoloDeTernaNeuronalYNoDeDistanciasCrudas()` creating two identical planners: one fed `(null, terna)` and the other fed `(extremeDummySensorVector, terna)` across `BUSQUEDA`, `APROXIMACION`, `BLOQUEO`, and `ESCAPE`, asserting identical `ComandoRobot` `(vLeft, vRight, estado)` outputs on every cycle!

- [ ] **Step 2: Run `PlanificadorNavegacionTest` to confirm it fails before rewrite (RED)**

Run:
```bash
cd keiko-server && mvn test -Dtest=PlanificadorNavegacionTest
```
Expected: FAIL (current `PlanificadorNavegacion` mutates behavior based on raw `VectorSensores` fields).

- [ ] **Step 3: Rewrite `PlanificadorNavegacion.java` as a clean symbolic FSM and strip pose fields from `VectorSensores.java`**

Implement clean, modular `PlanificadorNavegacion.java` (< ~280 lines) using ONLY `String[] pares` (`[IZQ, CEN, DER]`), `EstadoRobot`, and symbolic memory:
- `FINALIZACION`: `(0.0, 0.0, FINALIZACION)`
- Active committed maneuvers (`pasosRetrocesoRestantes`, `pasosGiroComprometido`) decremented cleanly.
- `ESCAPE`:
  - `SALIDA` in center (`"SALIDA".equals(cen)`): if both `izq` and `der` are `"BLOQUEO"` (pinched between jambs), reverse `(-2.4, -2.4)` for `6` cycles; if `BLOQUEO` on right jamb, steer left `(1.6, 3.2)`; if `BLOQUEO` on left jamb, steer right `(3.2, 1.6)`; otherwise shoot straight `(3.4, 3.4)`.
  - `SALIDA` on left (`"SALIDA".equals(izq)`): forward arc left `(1.8, 4.2)` (or gentle escape if right blocked).
  - `SALIDA` on right (`"SALIDA".equals(der)`): forward arc right `(4.2, 1.8)`.
  - No `SALIDA` visible: handle `BLOQUEO` using the same clean obstacle evasion/anti-loop FSM in `ESCAPE` state, or explore forward `(4.2, 4.2)` with periodic gentle sweep bias to scan for the 30 cm exit!
- `BUSQUEDA` / `APROXIMACION` / `CAPTURA`:
  - Priority 1 — `"LOBITO"` in `pares` (or short symbolic `ciclosPersistenciaLobito` when `"ESPACIO_LIBRE"` across all 3 sectors during sweeping):
    - Center `"LOBITO"`: drive straight `(3.8, 3.8, APROXIMACION)`.
    - Left `"LOBITO"`: arc left `(1.8, 3.8, APROXIMACION)`.
    - Right `"LOBITO"`: arc right `(3.8, 1.8, APROXIMACION)`.
  - Priority 2 — `BLOQUEO`:
    - Center blocked (`"BLOQUEO".equals(cen)`): `10`-step reverse `(-3.6, -3.6)` then pivot away from blocked side (`+1` if `bIzq`, `-1` if `bDer`, else alternating), or anti-loop if `contadorReversosConsecutivos >= 4`.
    - Both sides blocked (`bIzq && bDer`): `10`-step reverse + pivot.
    - Single side blocked (`bIzq` or `bDer`): register side blockage in oscillation/alternation memory; if `debeActivarAntiBucle()` (`contadorOscilaciones >= 2 || esPatronAlternante()`), trigger `12`-step reverse `(-4.2, -4.2)` + `16`-step wide turn `(4.2, -2.4)` (mirrored by direction); otherwise forward arc away from wall (`(4.8, 2.0)` if `bIzq`, `(2.0, 4.8)` if `bDer`).
  - Priority 3 — Free exploration (`BUSQUEDA`): advance `(4.8, 4.8)` (with optional subtle symbolic counter oscillation so the robot sweeps across the bay rather than driving infinitely straight if empty).
- `notificarCaptura()`: sets short symbolic forward/clear burst (`3` steps forward + `4` steps slight turn) without consulting sensors or pose.
- Also remove `hasPose`, `posX`, `posY`, `theta` from `VectorSensores.java`.

- [ ] **Step 4: Run full Java test suite (`mvn clean test`)**

Run:
```bash
cd keiko-server && mvn clean test
```
Expected: `PlanificadorNavegacionTest` (10/10), `ProtocolParserTest`, `ConfiguracionServidorTest`, `HardwareSerialAndCaptureTest`, `ConcurrencyEstomagoTest`, `Hito2IntegrationTest`, `NeurophInferenceTest` all PASS!

- [ ] **Step 5: Commit Task 3**

Run:
```bash
git add keiko-server/src/main/java/com/orquitas/server/PlanificadorNavegacion.java keiko-server/src/main/java/com/orquitas/server/VectorSensores.java keiko-server/src/test/java/com/orquitas/server/PlanificadorNavegacionTest.java
git commit -m "refactor(planner): rewrite PlanificadorNavegacion as pure symbolic FSM and strip VectorSensores pose"
```

---

### Task 4: Align `robot_client.py` to Physical Mega Rig (`SIM-01`, `SIM-02`, `COMP-01`, `COMP-02`)

**Files:**
- Modify: `keiko-sim/config/simulation_params.json` (ensure `servo_sweep` parameters `min_deg: -45.0, max_deg: 60.0, step_deg: 5.0` are explicitly defined)
- Modify: `keiko-sim/controllers/robot_client/robot_client.py`
- Create/Test: `keiko-sim/tests/test_robot_client_protocol.py`

**Interfaces:**
- Helper functions in `robot_client.py`:
  - `load_sweep_config(params_path=...) -> tuple[float, float, float]` (returns `(-45.0, 60.0, 5.0)` from `simulation_params.json` with fallback)
  - `build_sweep_angles(min_deg, max_deg, step_deg) -> list[float]` (`[-45.0, -40.0, ..., 60.0]`)
  - `format_telemetry_line(us_izq, us_cen, us_der, laser_izq, laser_der, servo_deg, ts_ms) -> str` (`7` fields, zero pose!)

- [ ] **Step 1: Write failing unit tests in `keiko-sim/tests/test_robot_client_protocol.py` (RED)**

Test (mocking Webots `controller` module only at import if `controller` isn't on `PYTHONPATH`):
1. `format_telemetry_line(...)` emits exactly 7 comma-separated numeric values (`TELEMETRY:usL,usC,usR,lasL,lasR,ang,ts\n`) matching `MensajeProtocolo.parsearTelemetria`.
2. `build_sweep_angles(*load_sweep_config())` starts at `-45.0`, ends at `60.0`, step `5.0` (`22` angles).
3. Static inspection or helper check confirms `robot_client.py` does NOT send `EVENT:SALIDA_META` or `EVENT:COLISION` over socket.

- [ ] **Step 2: Run test to confirm failure (RED)**

Run:
```bash
python3 -m unittest keiko-sim/tests/test_robot_client_protocol.py -v
```
Expected: FAIL (`format_telemetry_line` / `load_sweep_config` not defined).

- [ ] **Step 3: Update `simulation_params.json` and `robot_client.py`**

1. In `keiko-sim/config/simulation_params.json`: add `"servo_sweep": { "min_deg": -45.0, "max_deg": 60.0, "step_deg": 5.0 }`.
2. In `robot_client.py`:
   - Add pure functions `load_sweep_config`, `build_sweep_angles`, `format_telemetry_line`.
   - Remove `EVENT:SALIDA_META` send in `try_connect()` (`lines 260-264`).
   - Remove state-based `approach_binocular_angles` / `escape_guard_angles` switching; sweep continuously over `build_sweep_angles(*load_sweep_config())` (`-45.0..+60.0`, step `5.0`).
   - Emit 7-field `format_telemetry_line(...)` in `main()` (`lines 496-500`).
   - Keep `find_penalizable_contact` collision logging for local Webots penalty audit (`-5 pts`), but remove `sock.sendall("EVENT:COLISION...")` (`lines 597-601`).

- [ ] **Step 4: Run `test_robot_client_protocol.py` (GREEN)**

Run:
```bash
python3 -m unittest keiko-sim/tests/test_robot_client_protocol.py -v
```
Expected: PASS (`3/3 OK`).

- [ ] **Step 5: Commit Task 4**

Run:
```bash
git add keiko-sim/config/simulation_params.json keiko-sim/controllers/robot_client/robot_client.py keiko-sim/tests/test_robot_client_protocol.py
git commit -m "refactor(sim): align robot_client telemetry (7 fields) and -45..+60 sweep with Mega firmware"
```

---

### Task 5: Fix `live_visualizer.py`, `build_worlds.py` Exit Gap (`0.300 m` clear), and `keiko-sim/tests/` (`100%` Green)

**Files:**
- Modify: `keiko-sim/controllers/live_visualizer/live_visualizer.py`
- Modify: `keiko-sim/scripts/build_worlds.py`
- Regenerate: `keiko-sim/worlds/*.wbt` (via `python3 keiko-sim/scripts/build_worlds.py`)
- Modify: `keiko-sim/tests/test_meshes_and_arena.py`
- Modify: `keiko-sim/tests/test_ultrasonic_physics.py`

- [ ] **Step 1: Fix `live_visualizer.py:231` (`marble_positions` -> `active_obstacles_xy`)**

In `keiko-sim/controllers/live_visualizer/live_visualizer.py`: gather `active_obstacles_xy` from `OBSTACLE_BOX_1`/`OBSTACLE_BOX_2` (or `OBSTACLE_CYLINDER_*`) and call `us_physics.apply(raw_readings, robot_pose=(rx, ry, yaw), active_obstacles_xy=active_obstacles_xy)`.

- [ ] **Step 2: Fix `build_worlds.py` exit gap (`half_gap = max(exit_width / 2.0, exit_width / 2.0 + wall_thickness / 2.0)` = `0.1525 m` -> `0.305 m` centerline = exact `0.300 m` interior clear gap) and regenerate `.wbt` worlds**

In `build_worlds.py:627`: change `half_gap = max(0.155, exit_width / 2.0)` to `half_gap = exit_width / 2.0 + wall_thickness / 2.0` (`0.1525 m` -> `actual_exit_gap_m = 0.305 m`, clear interior opening = `0.305 - 0.005 = 0.300 m`, within `0.30 ± 0.005 m`!). Run `python3 keiko-sim/scripts/build_worlds.py` to regenerate all `.wbt` worlds cleanly.

- [ ] **Step 3: Align `test_meshes_and_arena.py` and `test_ultrasonic_physics.py` to current `3US + 2Laser` architecture**

1. In `test_meshes_and_arena.py`:
   - `test_semi_ellipse_wall_segments_and_exit_gap`: test active `ARENA_PROFILES` / `MAP_CONFIGS` variants (`standard`, `circular`, `realistic`, `scen_1..14`) for `wall_thickness == 0.005`, `wall_height == 0.18`, `exit_width == 0.30`, `actual_exit_gap_m` within `0.30 ± 0.005` (with clear gap >= `0.30`), and realistic area bounds (`1.35..2.30 m²` including `circular`).
   - `test_marbles_and_obstacles_clearance`: iterate active `("standard", "circular", "scen_1", "scen_9", "scen_10")` checking 3 marbles and >=2 obstacles inside the bay.
   - `test_generated_world_uses_meshes_and_has_no_chamfers_or_exit_tunnel`: call `build_worlds.generate_world("robot_client", "standard", "north", include_capote=...)` matching current 4-parameter signature.
   - `test_chassis_collision_boxes_follow_cad_and_clear_wheels`: assert the 5 CAD collision boxes (`0.09875 ±0.0575 0.039`, `0.09875 0 0.0525`, `0 0 0.048`, `-0.095 0 0.048`) preserving the `7.0 cm` central picker opening!
   - `test_webots_loads_new_world_without_vrml_errors`: test `bahia_de_caza_client.wbt` (`robot_client`) or `bahia_de_caza.wbt` (`live_visualizer`) cleanly without `ERROR:` or Python exceptions (`TypeError`), checking `Salida detectada: NORTH @` (matching `EXIT_PLATFORM` / `detect_exit`).
2. In `test_ultrasonic_physics.py`:
   - Replace stale `SENSOR_LAYOUTS` test with `test_official_3us_2laser_rig_in_generated_world` verifying `us_left`, `us_center`, `us_right`, `laser_left`, `laser_right`, `servo_left`, `servo_right` in `generate_world("robot_client", "standard", "north")`.
   - Update `test_marble_acoustic_visibility_limit` -> `test_3d_obstacle_acoustic_visibility_at_5_5cm` using `active_obstacles_xy` (verifying 3× HC-SR04 at `5.5 cm` detect `6×6×6` obstacles within `0.45 m` and ignore distant obstacles `> 0.55 m`).

- [ ] **Step 4: Run full Python test suite (`unittest`)**

Run:
```bash
python3 -m unittest discover -s keiko-sim/tests -v
```
Expected: **100% PASS** (`0` failures, `0` errors, including `TestWebotsHeadlessLoad`!).

- [ ] **Step 5: Commit Task 5**

Run:
```bash
git add keiko-sim/controllers/live_visualizer/live_visualizer.py keiko-sim/scripts/build_worlds.py keiko-sim/worlds/ keiko-sim/tests/test_meshes_and_arena.py keiko-sim/tests/test_ultrasonic_physics.py
git commit -m "fix(sim): repair live_visualizer, exact 0.300m exit clearance, and align Python test suite"
```

---

### Task 6: Validate Pure-NN Performance (Java + Headless Webots) & Retrain `.nnet` in Neuroph 2.98 if Needed

**Files:**
- Potentially modify (only if validation reveals classification gaps without `HRD-01..14`): `keiko-sim/data/datasets/dataset_canicas.csv`, `keiko-sim/data/models/modelo_*.nnet`, `keiko-server/models/modelo_*.nnet`, `keiko-server/src/main/resources/models/modelo_*.nnet`, `keiko-server/src/test/java/com/orquitas/server/TwoNetworksInferenceTest.java`

- [ ] **Step 1: Inspect `TwoNetworksInferenceTest` and run headless Webots benchmark (`scen_1`..`scen_8`)**

Run `cd keiko-server && mvn clean test && mvn package -DskipTests=true` and run a short headless Webots verification across representative scenarios (`benchmark_hil.py` or `iniciar_simulacion.sh` in `--batch --mode=fast --no-rendering` with `ORQUITAS_AUTO_QUIT=1`, `ORQUITAS_SIM_TIMEOUT=90`s) to measure marble captures & escape behavior under pure Neuroph 2.98.

- [ ] **Step 2: Remediate / Retrain if pure Neuroph fails classification cases**

- Check which cases in `TwoNetworksInferenceTest` or headless Webots fail without `HRD-01..02` / `HRD-03..14`.
- If `TwoNetworksInferenceTest` fails on raw `modelo_canicas.nnet` (or headless Webots misses marbles / confuses side walls at `+18°..+60°` or cubes `6×6×6`):
  - Collect clean `[-45°, +60°]` 7-field samples via `robot_client.py` (`ORQUITAS_RECORD_DATASET=1`) / `generate_datasets.py` + `consolidar_dataset_v2.py` (preserving backup of v1!).
  - Retrain via `cd neuroph-train && mvn test -Dtest=TrainTwoNetworksTest` (Neuroph 2.98), verify accuracy >= 95%, and copy updated `modelo_ultrasonico.nnet` / `modelo_canicas.nnet` into `keiko-sim/data/models/`, `keiko-server/models/`, and `keiko-server/src/main/resources/models/`.
  - Re-run `cd keiko-server && mvn clean test` and headless Webots scenarios.

- [ ] **Step 3: Commit any updated datasets/models/test adjustments (if modified)**

Run explicit `git add` and `git commit -m "feat(ai): validate and update Neuroph 2.98 models for pure-NN navigation"` (or record no-retrain-needed in task report if existing models already pass cleanly!).

---

### Task 7: Firmware Competition Hardening (`COMP-03`) & Release `2.0.0` Sync + Documentation

**Files:**
- Modify: `VERSION` (`2.0.0`)
- Modify: `firmware/orquita_firmware/orquita_firmware.ino` (`#define PERMITIR_MANUAL 0`, `KEIKO_VERSION "2.0.0"`)
- Modify: `keiko-server/pom.xml` (`2.0.0`)
- Modify: `keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java` (`VERSION_SISTEMA = "2.0.0"`)
- Modify: `keiko-sim/config/simulation_params.json` (`"version": "2.0.0"`)
- Modify: `keiko-sim/controllers/robot_client/robot_client.py` (`KEIKO_VERSION = "2.0.0"`)
- Modify: `docs/AUDITORIA_CONSOLIDACION.md` (add `Fase B (v2.0.0) — Resolución` section marking `SIM-01..13`, `HRD-01..14`, `COMP-01..05` as resolved with SHA-256 hashes & new test evidence)
- Modify: `AGENTS.md`, `CHANGELOG.md`, `README.md`, `keiko-server/README.md`, `keiko-sim/README.md`

- [ ] **Step 1: Harden firmware (`PERMITIR_MANUAL 0`) and bump all 9 version sync points to `2.0.0`**

Update `VERSION`, `orquita_firmware.ino`, `keiko-server/pom.xml`, `ConfiguracionServidor.java`, `simulation_params.json`, `robot_client.py` to `2.0.0` and set `#define PERMITIR_MANUAL 0` in `orquita_firmware.ino` (with clear comment noting `1` enables USB/BT bench `wasdkx ` testing).

- [ ] **Step 2: Run `./scripts/check_versions.sh`, `mvn clean test`, `mvn package`, and `unittest`**

Capture exact outputs for `CHANGELOG.md` and `docs/AUDITORIA_CONSOLIDACION.md`.

- [ ] **Step 3: Update `docs/AUDITORIA_CONSOLIDACION.md`, `CHANGELOG.md`, `AGENTS.md`, and READMEs for `2.0.0`**

- `docs/AUDITORIA_CONSOLIDACION.md`: add closing Phase B resolution section documenting exact commits, `.nnet` SHA-256 hashes, and green test results (`mvn clean test`, `unittest`, `check_versions.sh`, headless Webots).
- `CHANGELOG.md`: add `[2.0.0] — 2026-11-23` entry with compatibility matrix, all resolved `SIM-01..13`/`HRD-01..14`/`COMP-01..05` items, and observed verification evidence.
- `AGENTS.md`: update active version (`2.0.0`), remove obsolete "Estado actual de deuda técnica" notice, remove deprecated pose telemetry note, and reflect `PERMITIR_MANUAL 0` competition default.
- `README.md`, `keiko-server/README.md`, `keiko-sim/README.md`: update version `2.0.0`, test counts, and remove Phase A audit debt notices.

- [ ] **Step 4: Commit `2.0.0` release prep**

Run:
```bash
git add VERSION AGENTS.md CHANGELOG.md README.md firmware/ keiko-server/ keiko-sim/ docs/AUDITORIA_CONSOLIDACION.md
git commit -m "chore(release): finalize Phase B pure-NN v2.0.0 baseline"
```

---

### Task 8: Final Verification, Whole-Branch Review & PR Integration

- [ ] **Step 1: Run full verification suite cleanly**

Run:
```bash
./scripts/check_versions.sh
cd keiko-server && mvn clean test && mvn package -DskipTests=true
cd .. && python3 -m unittest discover -s keiko-sim/tests -v
git status
```
Expected: ALL pass (`9/9`, Java `0` failures, Python `0` failures, clean working tree).

- [ ] **Step 2: Whole-branch review + push branch & create PR to `main` (tag `v2.0.0` after merge)**
