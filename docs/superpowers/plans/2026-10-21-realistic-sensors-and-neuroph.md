# Realistic Ultrasonic Sensors & Neuroph 2.98 Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the 6-front-sensor (`trisector_front_6`) topology, realistic HC-SR04 40 kHz acoustic degradation (marble cross-section limit $\le 32\text{ cm}$, specular reflection, noise), align `neuroph-train` and `keiko-server` for 100% neural inference without fixed-threshold rules, and benchmark 5-class vs 7-class vs 9-class directional Neuroph MLP models.

**Architecture:**
1. Add `trisector_front_6` (3 low + 3 high front ultrasonic sensors) as the default sensor layout in `keiko-sim/scripts/build_worlds.py` and `sonar_visualizer.py`, while preserving modular support for 4, 6-side, 8, and 10 sensors.
2. Create `keiko-sim/controllers/common/ultrasonic_physics.py` to model realistic HC-SR04 acoustics (marble visibility $\le 32\text{ cm}$ within $\pm 9.5^\circ$, oblique wall specular drop-out $> 42^\circ$, Gaussian distance noise, dead zone $< 2.5\text{ cm}$, and crosstalk spikes).
3. Update `dataset_collector.py` to generate realistic 6-front-sensor datasets with both 5 canonical classes (`ESPACIO_LIBRE`, `LOBITO`, `MURO`, `OBSTACULO`, `SALIDA`), 7 classes (`LOBITO_IZQ/CENTRO/DER`), and 9 classes (`LOBITO_*` + `OBSTACULO_*`).
4. Update `VectorSensores.java`, `MensajeProtocolo.java`, `ClasificadorNeuroph.java`, and `PlanificadorNavegacion.java` in `keiko-server` + `SimulatedDatasetTrainTest.java` in `neuroph-train` to fix class ordering, support $N$-sensor vectors normalized over $[0, 250\text{ cm}]$, and benchmark the trained `.nnet` models.

**Tech Stack:** Python 3, Webots R2025a, Java 21 LTS, Maven, Neuroph 2.98, JUnit 5.

---

### Task 1: Add `trisector_front_6` Sensor Topology and Realistic `ultrasonic_physics.py`

**Files:**
- Create: `keiko-sim/controllers/common/ultrasonic_physics.py`
- Modify: `keiko-sim/scripts/build_worlds.py`
- Modify: `keiko-sim/controllers/common/sonar_visualizer.py`
- Test: `keiko-sim/tests/test_ultrasonic_physics.py`

- [ ] **Step 1: Write unit tests for `ultrasonic_physics.py` and `trisector_front_6` world generation**
- [ ] **Step 2: Implement `ultrasonic_physics.py` and add `trisector_front_6` to `build_worlds.py` and `sonar_visualizer.py`**
- [ ] **Step 3: Regenerate worlds and verify unit tests pass**

---

### Task 2: Generate Realistic 6-Sensor Datasets (5, 7, and 9 Classes)

**Files:**
- Modify: `keiko-sim/controllers/dataset_collector/dataset_collector.py`
- Create/Update: `keiko-sim/data/datasets/orquitas_dataset_6_front_5clases.csv`, `orquitas_dataset_6_front_7clases.csv`, `orquitas_dataset_6_front_9clases.csv`

- [ ] **Step 1: Update `dataset_collector.py` to use `ultrasonic_physics.py` and output 5-class, 7-class, and 9-class datasets for `trisector_front_6`**
- [ ] **Step 2: Run Webots dataset generation and verify class balance and realistic sensor signatures**

---

### Task 3: Align `keiko-server` and `neuroph-train` & Benchmark Topologies

**Files:**
- Modify: `keiko-server/src/main/java/com/orquitas/server/VectorSensores.java`
- Modify: `keiko-server/src/main/java/com/orquitas/server/MensajeProtocolo.java`
- Modify: `keiko-server/src/main/java/com/orquitas/server/ClasificadorNeuroph.java`
- Modify: `keiko-server/src/main/java/com/orquitas/server/PlanificadorNavegacion.java`
- Modify: `neuroph-train/src/test/java/com/neuroph/train/SimulatedDatasetTrainTest.java`
- Modify: `keiko-server/src/test/java/com/orquitas/server/ProtocolParserTest.java`
- Modify: `keiko-server/src/test/java/com/orquitas/server/NeurophInferenceTest.java`

- [ ] **Step 1: Update `VectorSensores`, `MensajeProtocolo`, `ClasificadorNeuroph`, and `PlanificadorNavegacion` for 6-sensor (and modular $N$-sensor) neural inference**
- [ ] **Step 2: Run iterative Neuroph 2.98 training benchmark comparing 5, 7, and 9 classes across MLP topologies (`[24]`, `[36]`, `[24, 16]`, `[32, 16]`)**
- [ ] **Step 3: Export the winning `.nnet` model and verify all `neuroph-train` and `keiko-server` JUnit tests pass**
