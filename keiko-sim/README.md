# Keiko-Sim: Entorno de Simulación Robótica 3D

Módulo de simulación 3D en **Webots (R2025a)** para el ecosistema **Proyecto Orquitas (Keiko)** (`2.0.0-audit.2` | `mega-3us-2laser`).

Permite validar la arquitectura distribuida Hardware-in-the-Loop (`keiko-server` en Java 21 + `robot_client.py` en Webots), generar datasets V2 de entrenamiento para **Neuroph 2.98** y ejecutar baterías multi-escenario en la **Bahía de Caza**.

---

## 1. Escenarios y Física Modelada

- **Geometría Oficial (Bahía de Caza):**
  - **Perfil `standard` (semielíptico):** base apoyada contra pared plana de **~1.80 m** ($b = 0.90\text{ m}$), profundidad máxima de **~1.20 m** ($a = 1.20\text{ m}$), área interior de **~1.70 m²**, muros de cartón de **5 mm** de espesor y **18 cm** de altura, y **Estrecho de Salida** de **30 cm** sin chaflanes ni túneles.
  - **Perfil `circular`:** pista circular de radio $R = 0.90\text{ m}$ (diámetro **1.80 m**) cortada por pared trasera en $y = -0.60\text{ m}$.
  - **Escenarios generados por [`scripts/build_worlds.py`](scripts/build_worlds.py):** `bahia_de_caza_scen_1.wbt` .. `bahia_de_caza_scen_14.wbt`, `bahia_de_caza_circular.wbt`, `bahia_de_caza_circular_client.wbt`, `bahia_de_caza_client.wbt`, `bahia_de_caza.wbt` y `bahia_de_caza_dataset.wbt`.
- **Elementos de Pista:** 2 obstáculos 3D (`6 cm × 6 cm × 6 cm` mínimo) y 3 Lobitos (canicas físicas de **16 mm** de diámetro, masa `5.5 g`).
- **Modelo del Robot (`mega-3us-2laser`) en [`controllers/robot_client/robot_client.py`](controllers/robot_client/robot_client.py):**
  - Chasis 4WD Skid-Steer (`257 × 150 × 76.1 mm`, ruedas de `67.2 mm`).
  - **Fila superior:** 3× sensores ultrasónicos HC-SR04 fijos (`us_left`, `us_center`, `us_right`) a `5.5 cm` de altura con modelado físico ([`ultrasonic_physics.py`](controllers/common/ultrasonic_physics.py)) y filtro de frontera `SensorBorderFilter` (mediana de 3 + EMA $\alpha = 0.65$).
  - **Fila inferior:** 2× sensores láser ToF VL53L0X (`laser_left`, `laser_right`) montados sobre servos SG90 (`servo_left`, `servo_right`) en barrido espejo ([`laser_physics.py`](controllers/common/laser_physics.py)).
  - **Hueco bucal del picker:** abertura frontal libre de **7.0 cm** (`|Y| <= 0.035 m`) a ras de suelo con registro automático en [`DatasetRecorder`](controllers/common/dataset_recorder.py).
  - **Fail-Safe:** detiene los 4 motores si pasan **> 500 ms** sin recibir `COMMAND` ni `HEARTBEAT` del servidor.

---

## 2. Estructura del Subproyecto

```text
keiko-sim/
├── config/
│   └── simulation_params.json        <- Geometría, física, versión (2.0.0-audit.2) y rig (mega-3us-2laser)
├── worlds/
│   ├── bahia_de_caza_scen_1..14.wbt  <- Escenarios reglamentarios de prueba
│   ├── bahia_de_caza_circular*.wbt   <- Variante circular (standalone y cliente)
│   ├── bahia_de_caza_client.wbt      <- Escena cliente base
│   ├── bahia_de_caza.wbt             <- Escena interactiva base
│   └── bahia_de_caza_dataset.wbt     <- Escena para recolección de datasets
├── controllers/
│   ├── robot_client/robot_client.py  <- Cliente distribuido TCP + handshake HELLO (rig 3US+2Laser)
│   ├── common/                       <- arena_mapper, collision_audit, ultrasonic_physics, laser_physics, dataset_recorder, sonar_visualizer
│   ├── live_visualizer/              <- Visualizador local legacy
│   └── dataset_collector/            <- Colector de datasets por poses
├── data/
│   ├── datasets/                     <- dataset_ultrasonico.csv, dataset_canicas.csv, v2_simulaciones/, backup_v1_pre_simulaciones/
│   └── models/                       <- modelo_ultrasonico.nnet, modelo_canicas.nnet y modelos históricos
├── scripts/
│   ├── iniciar_simulacion.sh         <- Lanza keiko-server (--port 8888 --reset) + Webots [1..14|random|circular]
│   ├── iterar_simulacion.sh          <- Orquestador visual continuo (llama a entrenamiento_continuo.py)
│   ├── build_worlds.py               <- Generador paramétrico de mundos .wbt
│   ├── benchmark_hil.py              <- Benchmark multi-escenario Hardware-in-the-Loop
│   ├── consolidar_dataset_v2.py      <- Auditoría, balanceo y promoción de datasets V2
│   └── run_distributed.sh            <- Lanzador alternativo sobre bahia_de_caza_client.wbt
└── tests/                            <- Suite unittest (test_coaxial_neural_planner, test_collision_audit, test_meshes_and_arena, test_ultrasonic_physics)
```

---

## 3. Guía Rápida de Uso

### 1. Lanzar Simulación Distribuida (Servidor Central + Webots 3D)
Desde la raíz del repositorio:
```bash
# Escenario fijo del 1 al 14
./keiko-sim/scripts/iniciar_simulacion.sh 1

# Escenario aleatorio reglamentario generado al vuelo
./keiko-sim/scripts/iniciar_simulacion.sh random

# Pista circular
./keiko-sim/scripts/iniciar_simulacion.sh circular
```
*(Internamente compila `keiko-server`, inicia `keiko-server.jar` en `--port 8888 --reset` cargando `keiko-sim/data/models/modelo_ultrasonico.nnet` y abre Webots en tiempo real.)*

### 2. Iteración Continua con Supervisión Visual
```bash
./keiko-sim/scripts/iterar_simulacion.sh
```
Alterna proceduralmente entre pistas semielíptica (`standard`) y circular, registra puntajes y métricas en `keiko-sim/logs/entrenamiento_continuo.csv` y verifica capturas en `estomago.txt`.

### 3. Regenerar Mundos y Ejecutar Benchmark HIL
```bash
# Regenerar todos los escenarios .wbt
python3 keiko-sim/scripts/build_worlds.py

# Ejecutar benchmark multi-escenario contra keiko-server
python3 keiko-sim/scripts/benchmark_hil.py
```

### 4. Consolidar Datasets V2 de Simulación
```bash
python3 keiko-sim/scripts/consolidar_dataset_v2.py --status
python3 keiko-sim/scripts/consolidar_dataset_v2.py --merge --target-per-class=1500
python3 keiko-sim/scripts/consolidar_dataset_v2.py --deploy
```

### 5. Ejecutar Pruebas Unitarias de `keiko-sim`
```bash
python3 -m unittest discover -s keiko-sim/tests -v
```
> **Estado conocido en `2.0.0-audit.1`:** `23/30 OK`, `3 FAIL`, `4 ERROR` (los suites `test_coaxial_neural_planner.py` 15/15 y `test_collision_audit.py` 5/5 pasan completos; las fallas restantes se deben a pruebas coaxiales legacy desactualizadas, `actual_exit_gap_m = 0.3100 m` en `build_worlds.py` y un `TypeError` en `live_visualizer.py:231`, documentados en [`docs/AUDITORIA_CONSOLIDACION.md`](../docs/AUDITORIA_CONSOLIDACION.md)).

---

## 4. Redes Neuronales Activas (Neuroph 2.98)

El controlador `robot_client.py` transmite telemetría al servidor central, que clasifica mediante dos redes especializadas ubicadas en `data/models/` (sincronizadas con `keiko-server/models/`):

- **`modelo_ultrasonico.nnet` (Red 1):** 1 entrada normalizada → 3 salidas (`BLOQUEO`, `ESPACIO_LIBRE`, `SALIDA`), aplicada por sector `[IZQ, CEN, DER]`.
- **`modelo_canicas.nnet` (Red 2):** 6 entradas `[us_izq, us_cen, us_der, laser_izq, laser_der, angulo_servo]` → 4 salidas (`LOBITO_CENTRO`, `LOBITO_DER`, `LOBITO_IZQ`, `NINGUNA`).

El historial completo de experimentos y métricas previas se encuentra en [`docs/EXPERIMENTS.md`](../docs/EXPERIMENTS.md) y [`docs/ITERACION_SIMULACION_3US_2LASER.md`](../docs/ITERACION_SIMULACION_3US_2LASER.md).

---

## 5. Instalación de Webots

Webots (Apache 2.0) debe estar disponible en `PATH` o en `~/.local/bin/webots`:

- **Linux (portable oficial):**
  ```bash
  mkdir -p ~/.local/opt && cd ~/.local/opt
  curl -L -o webots.tar.bz2 https://github.com/cyberbotics/webots/releases/download/R2025a/webots-R2025a-x86-64.tar.bz2
  tar -xjf webots.tar.bz2 && rm webots.tar.bz2
  ln -sf ~/.local/opt/webots/webots ~/.local/bin/webots
  ```
- **Windows / macOS:** descargar `webots-R2025a_setup.exe` o `webots-R2025a.dmg` desde [Cyberbotics Webots Releases](https://github.com/cyberbotics/webots/releases/tag/R2025a).
