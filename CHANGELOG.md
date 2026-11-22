# Changelog — Proyecto Orquitas (Keiko)

Todas las modificaciones relevantes de arquitectura, firmware, servidor Java, simulador Webots, modelos `.nnet` y protocolo se documentan en este archivo.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y las versiones sincronizadas mediante `VERSION` + [`scripts/check_versions.sh`](scripts/check_versions.sh).

---

## [2.0.0-audit.1] — 2026-11-21

Consolidación en fase A de las líneas divergentes (`main`, `origin/feat/hw-field-ready` y `origin/tests-emi`) sobre un árbol unificado para el hardware objetivo `mega-3us-2laser`, incorporando versionado sincronizado, handshake `HELLO` y auditoría exhaustiva de cumplimiento anti-hardcoding previa a la refactorización de Fase B (`2.0.0`).

### Matriz de Compatibilidad (`2.0.0-audit.1`)

| Componente | Ruta / Artefacto | Versión | Rig / Especificación | Hash / Verificación |
| :--- | :--- | :---: | :---: | :--- |
| **Raíz (`VERSION`)** | [`VERSION`](VERSION) | `2.0.0-audit.1` | `mega-3us-2laser` | Fuente única de verdad (`./scripts/check_versions.sh`) |
| **Servidor Central (Java 21)** | [`keiko-server/pom.xml`](keiko-server/pom.xml) + [`ConfiguracionServidor.java`](keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java) | `2.0.0-audit.1` | `mega-3us-2laser` | `mvn clean test` (36/36 OK) |
| **Firmware Robot (Mega 2560)** | [`firmware/orquita_firmware/orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino) | `2.0.0-audit.1` | `mega-3us-2laser` | `KEIKO_VERSION` / `KEIKO_RIG` + `HELLO:2.0.0-audit.1,mega-3us-2laser` |
| **Simulador 3D (Webots)** | [`keiko-sim/config/simulation_params.json`](keiko-sim/config/simulation_params.json) + [`robot_client.py`](keiko-sim/controllers/robot_client/robot_client.py) | `2.0.0-audit.1` | `mega-3us-2laser` | `KEIKO_VERSION` / `KEIKO_RIG` + `HELLO:2.0.0-audit.1,mega-3us-2laser` |
| **Red Neuronal 1 (Sector US)** | [`modelo_ultrasonico.nnet`](keiko-server/models/modelo_ultrasonico.nnet) | `2.0.0-audit.1` | 1 entrada → `{BLOQUEO, ESPACIO_LIBRE, SALIDA}` | SHA-256 `c0952be2...` (5 373 B) idéntico en `keiko-server/` y `keiko-sim/data/models/` |
| **Red Neuronal 2 (Canicas)** | [`modelo_canicas.nnet`](keiko-server/models/modelo_canicas.nnet) | `2.0.0-audit.1` | 6 entradas → `{LOBITO_CENTRO, LOBITO_DER, LOBITO_IZQ, NINGUNA}` | SHA-256 `9e717570...` (35 745 B) idéntico en `keiko-server/` y `keiko-sim/data/models/` |

> **Regla de handshake:** `ManejadorCliente` valida compatibilidad `major.minor` y coincidencia exacta de `rig` mediante `HELLO:<version>,<rig>` al iniciar la sesión serie o TCP.

### Added (Agregado)
- Archivo raíz [`VERSION`](VERSION) (`2.0.0-audit.1`) y validador automático [`scripts/check_versions.sh`](scripts/check_versions.sh) (9/9 comprobaciones entre Maven, Java, firmware `.ino`, `simulation_params.json` y `robot_client.py`). **Por qué:** evitar desincronización silenciosa entre hardware físico, simulador y servidor central.
- Handshake `HELLO:<version>,<rig>` en [`MensajeProtocolo.java`](keiko-server/src/main/java/com/orquitas/server/MensajeProtocolo.java), [`ManejadorCliente.java`](keiko-server/src/main/java/com/orquitas/server/ManejadorCliente.java), [`orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino) y [`robot_client.py`](keiko-sim/controllers/robot_client/robot_client.py). **Por qué:** detectar de inmediato incompatibilidades de versión o rig al conectar por Bluetooth SPP o TCP.
- Transporte serie real en `keiko-server` con `jSerialComm 2.11.0` ([`ReceptorSerie.java`](keiko-server/src/main/java/com/orquitas/server/ReceptorSerie.java)), puente Bluetooth RFCOMM/TCP ([`keiko-server/scripts/bt_rfcomm_bridge.py`](keiko-server/scripts/bt_rfcomm_bridge.py)), flags CLI `--serial`, `--baud`, `--list-ports` y carga de `.env` ([`ConfiguracionServidor.java`](keiko-server/src/main/java/com/orquitas/server/ConfiguracionServidor.java)). **Por qué:** habilitar la operación directa contra el módulo HC-05 o USB sin adaptadores externos obligatorios.
- Informe de auditoría técnica [`docs/AUDITORIA_CONSOLIDACION.md`](docs/AUDITORIA_CONSOLIDACION.md) con inventario `SIM-01..13`, `HRD-01..14`, `COMP-01..05` y hashes SHA-256 de modelos `.nnet`. **Por qué:** documentar con exactitud los atajos y umbrales heredados antes de su erradicación en Fase B (`2.0.0`).
- Índices documentales [`pruebas/README.md`](pruebas/README.md), [`archive/README.md`](archive/README.md) y este [`CHANGELOG.md`](CHANGELOG.md).

### Changed (Modificado)
- **Unificación de firmware activo a Arduino Mega 2560 (`mega-3us-2laser`):** [`firmware/orquita_firmware/orquita_firmware.ino`](firmware/orquita_firmware/orquita_firmware.ino) reemplaza al sketch UNO previo con soporte para shield L293D (`M1..M4`), PCA9685 (`0x40`), 3× HC-SR04 (`TRIG {24,27,29}`, `ECHO {25,26,28}`), 2× VL53L0X frontales en servos SG90 (`0x30`/`0x31`), picker 360° + DC auxiliar, VL53L0X de estómago (`0x32`, `A10`), HC-05 en `Serial1` (`9600` bps) y Fail-Safe `< 500 ms`. **Por qué:** alinear el repositorio con la plataforma física real verificada en banco.
- **Unificación de `keiko-server`:** integrado el planificador de doble red (`ClasificadorNeuroph` + `PlanificadorNavegacion`) con el soporte serie, cooldown anti-rebote (`1500 ms`), copia defensiva en broadcast TCP (`ReceptorConexiones.java`) y sincronización de los binarios `.nnet` (`modelo_ultrasonico.nnet` y `modelo_canicas.nnet`) en `keiko-server/models/`, `keiko-server/src/main/resources/models/` y `keiko-sim/data/models/`. **Por qué:** combinar la arquitectura de inferencia dual con la conectividad de campo en una sola base compilable.
- **Unificación de `keiko-sim`:** incorporados `build_worlds.py`, mundos `bahia_de_caza_scen_1..14.wbt`, `bahia_de_caza_circular.wbt` / `bahia_de_caza_circular_client.wbt`, `DatasetRecorder`, lanzadores [`iniciar_simulacion.sh`](keiko-sim/scripts/iniciar_simulacion.sh) e [`iterar_simulacion.sh`](keiko-sim/scripts/iterar_simulacion.sh) y entrenamiento continuo [`entrenamiento_continuo.py`](keiko-sim/scripts/entrenamiento_continuo.py). **Por qué:** reproducir en Webots 3D la geometría y sensores del robot Mega actual.
- **Alineación documental:** reescritura de [`AGENTS.md`](AGENTS.md), [`README.md`](README.md), [`keiko-server/README.md`](keiko-server/README.md) y [`keiko-sim/README.md`](keiko-sim/README.md) para reflejar rutas, puertos, flags y rig reales.

### Archived (Archivado)
- Traslado a [`archive/`](archive/README.md) de artefactos superados para conservar trazabilidad sin contaminar la ruta activa:
  - [`archive/firmware-uno/`](archive/firmware-uno/): firmware original Arduino UNO + L298N + 4 HC-SR04 y diagrama eléctrico previo.
  - [`archive/sensores-legacy/`](archive/sensores-legacy/): especificaciones coaxiales previas (4/6/8/10 HC-SR04).
  - [`archive/hitos/`](archive/hitos/): entregas académicas estáticas de Hito 2 y Hito 3 (incluye copia histórica Java en `archive/hitos/HITO3/codigo/`).
  - [`archive/pruebas-experimentales/`](archive/pruebas-experimentales/): experimentos descartados (`detector-6-ultrasonidos`, `lidar_scanner`, `vision`, `marble_test`, `prueba-trabado-vcc`).

### Removed (Eliminado)
- Eliminado el directorio `docs/uml/` por encontrarse obsoleto respecto a la arquitectura `mega-3us-2laser` y doble red Neuroph 2.98.

### Known Audit Findings (Hallazgos Conocidos para Fase B `2.0.0`)
Registrados en detalle en [`docs/AUDITORIA_CONSOLIDACION.md`](docs/AUDITORIA_CONSOLIDACION.md):
- **`SIM-01..SIM-13` (Atajos de simulación):** `robot_client.py` aún transmite `(rx, ry, yaw)` en `TELEMETRY:` (10 campos) y `EVENT:SALIDA_META`, mientras `PlanificadorNavegacion.java` y `ManejadorCliente.java` mantienen ramas `hasPose()`, `establecerSalidaMeta`, `verificarEscudoPerimetral`, `esPuntoEnPared`/`esLaserEnPared` y navegación trigonométrica en `ESCAPE`.
- **`HRD-01..HRD-14` (Hardcoding sensorial):** `ClasificadorNeuroph.java` y `PlanificadorNavegacion.java` conservan filtros post-inferencia, heurísticas de sombra/ancho (`esSombraObstaculoUltrasonico`, `esAnchoTicsCanicaValido`) y comparaciones sobre distancias crudas en cm que corrigen o sustituyen a Neuroph 2.98.
- **`COMP-01..COMP-05`:** Desalineación de rango angular de servo (`[-45°, +60°]` en firmware vs `<= 50°` en Java vs `[-15°, +35°]` en Webots), eventos `EVENT:COLISION` exclusivos de Webots, comandos de banco `TEST:` en firmware sin flag de deshabilitación para competencia y contador local `canicasRecolectadas` en firmware.
- **Deuda en `keiko-sim`:** `TypeError` activo en `live_visualizer.py:231` (`marble_positions` vs `active_obstacles_xy`), desvío de ancho de salida en `build_worlds.py` (`0.3100 m` vs `0.30 ± 0.005 m`) y tests unitarios coaxiales legacy desactualizados.

### Tests Run (Evidencia de Pruebas — 2026-11-21)
- `./scripts/check_versions.sh`: **9/9 `[OK]`** (`exit 0`).
- `cd keiko-server && mvn clean test`: **36/36 OK** (0 Failures, 0 Errors, 0 Skipped; `2.505 s`; 8 clases: `ProtocolParserTest` 8/8, `PlanificadorNavegacionTest` 9/9, `ConfiguracionServidorTest` 3/3, `HardwareSerialAndCaptureTest` 3/3, `TwoNetworksInferenceTest` 4/4, `ConcurrencyEstomagoTest` 2/2, `Hito2IntegrationTest` 2/2, `NeurophInferenceTest` 5/5).
- `python3 -m unittest discover -s keiko-sim/tests -v`: **23/30 OK, 3 FAIL, 4 ERROR** (`12.173 s`, `exit 1`; detallados en [`docs/AUDITORIA_CONSOLIDACION.md`](docs/AUDITORIA_CONSOLIDACION.md#53-simulador-python-python3--m-unittest-discover--s-keiko-simtests--v)).

---

## Histórico Pre-Unificación (`archive/*` — Serie `1.x` y Ramas Previas)

Para referencia histórica, se conservan los tags inmutables en Git y los artefactos estáticos en [`archive/`](archive/README.md):

| Tag Git | Commit | Descripción Histórica |
| :--- | :---: | :--- |
| `archive/main-pre-reorg` | `25e2600` | Estado previo de `main`: arquitectura inicial `1.x` con firmware Arduino UNO + L298N + 4 HC-SR04 coaxiales, modelos CAD/STL (`modelos-3d/`) y entregas académicas de Hitos. |
| `archive/feat-hw-field-ready` | `365b998` | Rama de integración serie/Bluetooth (`ReceptorSerie.java`, `bt_rfcomm_bridge.py`, carga `.env`, cooldown de captura) previa a la unificación con `tests-emi`. |
| `archive/tests-emi` | `83f86bf` | Rama con firmware definitivo Arduino Mega 2560 (`3US + 2Laser`), mundos `scen_1..14`/`circular` y arquitectura de doble red Neuroph 2.98. |
| `archive/feat-sim-3us-2laser` | `c65b9f9` | Rama experimental intermedia de simulación `3US + 2Laser` previa al merge en `main` (`#14`). |
