# Realistic Semi-Ellipse Arena and 3D CAD Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the square $2.4 \times 2.4\text{ m}$ arena and primitive box robot in `keiko-sim` with a parametric deformable semi-ellipse cardboard bay ($\sim 1.80\text{ m} \times 1.20\text{ m}$, $5\text{ mm}$ wall thickness, $30\text{ cm}$ unchamfered exit gap), whiteboard-eraser obstacles, safe-margin marbles, and the real 3D CAD meshes from `GreatXLabs/Keiko-Modelos-3D` (including configurable `Capote` shell).

**Architecture:** We extract and decimate three binary STL meshes in meters (`orquita_chassis_body.stl`, `orquita_wheel.stl`, `orquita_capote.stl`) from the official CAD repository into `keiko-sim/meshes/`. Then we update `simulation_params.json` and `build_worlds.py` to generate polygonal semi-ellipse cardboard walls (with `standard`, `deformed_wide`, `deformed_narrow`, and `asymmetric` variants), a clean $30\text{ cm}$ exit gap opening into open space ($> 2.0\text{ m}$ acoustic range), 2–3 eraser-sized obstacles ($14 \times 6 \times 12\text{ cm}$), and 3 marbles ($\varnothing = 16\text{ mm}$) with $\ge 22\text{ cm}$ clearance. Finally, we update the Webots controllers (`live_visualizer.py`, `robot_client.py`, `dataset_collector.py`, `arena_mapper.py`) to operate within the semi-ellipse coordinates and verify everything in headless Webots.

**Tech Stack:** Python 3.12, Webots R2025a (VRML97 `.wbt`), STL binary mesh processing (`struct`), `pytest` / `unittest`.

**Spec:** `docs/superpowers/specs/2026-10-20-realistic-arena-and-3d-model-design.md`

## Global Constraints

- Never push directly to `main`; work on branch `feat/realistic-arena-and-3d-model`.
- Zero PII or local absolute paths with usernames in git commits or repository files.
- Arena fixed measurements: exit width $= 0.30\text{ m}$ ($30\text{ cm}$), wall thickness $= 0.005\text{ m}$ ($5\text{ mm}$), wall height $= 0.18\text{ m}$ ($18\text{ cm}$).
- Arena approximate measurements: flat back wall along South ($Y = -0.60\text{ m}$, $X \in [-0.90, +0.90]\text{ m}$, $2b \approx 1.80\text{ m}$), semi-major axis depth $a \approx 1.20\text{ m}$ reaching vertex at $(0.0, +0.60)\text{ m}$, area $\approx 1.70\text{ m}^2$.
- No exit chamfers (`EXIT_CHAMFER_*`) and no close exterior exit tunnel walls (`EXIT_WALL_*`) that would block ultrasonic rays from reading $> 200\text{ cm}$ through the $30\text{ cm}$ exit opening.
- Robot CAD dimensions: $257\text{ mm}$ length $\times 150\text{ mm}$ width $\times 76.1\text{ mm}$ height, wheel diameter $67.2\text{ mm}$ at $X = \pm 52.1\text{ mm}, Y = \pm 58.6\text{ mm}$, plus optional `Capote` shell ($265 \times 160 \times 100\text{ mm}$ with snout to $+197.5\text{ mm}$, $200\text{ mm}$ front scoop, and dorsal fin to $Z = 156\text{ mm}$).

---

### Task 1: Extract and Decimate 3D CAD Meshes (`keiko-sim/meshes/`)

**Files:**
- Create: `keiko-sim/scripts/extract_cad_meshes.py`
- Create: `keiko-sim/meshes/orquita_chassis_body.stl`
- Create: `keiko-sim/meshes/orquita_wheel.stl`
- Create: `keiko-sim/meshes/orquita_capote.stl`
- Test: `keiko-sim/tests/test_meshes_and_arena.py`

**Interfaces:**
- Produces:
  - `extract_cad_meshes.generate_all_meshes(meshes_dir: str) -> dict`
  - Binary STL files in `keiko-sim/meshes/` scaled in meters ($1\text{ unit} = 1\text{ m}$) and oriented in Webots robot frame ($+X$ forward, $+Y$ left, $+Z$ up).

- [ ] **Step 1: Write the failing test for CAD mesh extraction and dimensions**

Create `keiko-sim/tests/test_meshes_and_arena.py`:
```python
#!/usr/bin/env python3
import os
import struct
import unittest

SIM_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
MESHES_DIR = os.path.join(SIM_DIR, "meshes")


def read_stl_bbox(path):
    with open(path, "rb") as f:
        data = f.read()
    n_tri = struct.unpack("<I", data[80:84])[0]
    min_v = [float("inf")] * 3
    max_v = [float("-inf")] * 3
    offset = 84
    for _ in range(n_tri):
        vals = struct.unpack_from("<9f", data, offset + 12)
        offset += 50
        for j in (0, 3, 6):
            for k in range(3):
                v = vals[j + k]
                if v < min_v[k]:
                    min_v[k] = v
                if v > max_v[k]:
                    max_v[k] = v
    return n_tri, min_v, max_v


class TestMeshesAndArena(unittest.TestCase):
    def test_stl_meshes_exist_and_match_cad_dimensions(self):
        body_stl = os.path.join(MESHES_DIR, "orquita_chassis_body.stl")
        wheel_stl = os.path.join(MESHES_DIR, "orquita_wheel.stl")
        capote_stl = os.path.join(MESHES_DIR, "orquita_capote.stl")

        for p in (body_stl, wheel_stl, capote_stl):
            self.assertTrue(os.path.exists(p), f"Missing mesh file: {p}")
            self.assertLess(os.path.getsize(p), 4_000_000, f"Mesh too heavy (>4MB): {p}")

        # Body: ~0.257m along X (forward), ~0.150m along Y (width)
        n_body, b_min, b_max = read_stl_bbox(body_stl)
        self.assertGreater(n_body, 100)
        self.assertAlmostEqual(b_max[0] - b_min[0], 0.257, delta=0.01)
        self.assertAlmostEqual(b_max[1] - b_min[1], 0.150, delta=0.01)

        # Wheel: diameter ~0.0672m in X and Z, width ~0.025m in Y
        n_wheel, w_min, w_max = read_stl_bbox(wheel_stl)
        self.assertGreater(n_wheel, 50)
        self.assertAlmostEqual(w_max[0] - w_min[0], 0.0672, delta=0.005)
        self.assertAlmostEqual(w_max[2] - w_min[2], 0.0672, delta=0.005)

        # Capote: ~0.330m total X (from -0.1325 to +0.1975), 0.200m Y width at front scoop, ~0.156m Z height with fin
        n_cap, c_min, c_max = read_stl_bbox(capote_stl)
        self.assertGreater(n_cap, 20)
        self.assertAlmostEqual(c_max[0] - c_min[0], 0.330, delta=0.01)
        self.assertAlmostEqual(c_max[1] - c_min[1], 0.200, delta=0.01)
        self.assertAlmostEqual(c_max[2] - c_min[2], 0.156, delta=0.01)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest keiko-sim/tests/test_meshes_and_arena.py`
Expected: FAIL with `AssertionError: False is not true : Missing mesh file`

- [ ] **Step 3: Implement `keiko-sim/scripts/extract_cad_meshes.py` and generate the STL files**

Create `keiko-sim/scripts/extract_cad_meshes.py` to:
1. Download `Assembly_Main-Orquita.stl` from `GreatXLabs/Keiko-Modelos-3D` (via `gh api` or cached temp file), separate connected components for the chassis body vs. wheel, apply spatial vertex clustering decimation so the meshes are fast and compact ($< 2\text{ MB}$), convert from FreeCAD millimeters ($+Y$ forward, $+X$ right, $+Z$ up) to Webots meters ($+X$ forward, $+Y$ left, $+Z$ up, with ground at $Z = 0$), and write `orquita_chassis_body.stl` and `orquita_wheel.stl`.
2. Reconstruct the exact triangulated surface of the `Capote` (`Chassis.FCStd`: main shell $265 \times 160 \times 100\text{ mm}$ with rear $29\text{ mm}$ chamfers, middle snout section $Y \in [97.5, 167.5]\text{ mm}$, $X \in [-55, +55]\text{ mm}$, $Z \in [17.5, 82.5]\text{ mm}$, front $200\text{ mm}$ wide scoop blade $Y \in [137.5, 197.5]\text{ mm}$, $Z \in [42.5, 52.5]\text{ mm}$ with $16\text{ mm}$ chamfers, and top dorsal orca fin $X \in [-5, +5]\text{ mm}$, $Z \in [100, 156]\text{ mm}$) and write `orquita_capote.stl`.

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 keiko-sim/scripts/extract_cad_meshes.py && python3 -m unittest keiko-sim/tests/test_meshes_and_arena.py`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add keiko-sim/scripts/extract_cad_meshes.py keiko-sim/meshes/*.stl keiko-sim/tests/test_meshes_and_arena.py
git commit -m "feat(sim): extract and decimate 3D CAD meshes from Keiko-Modelos-3D"
```

---

### Task 2: Update `simulation_params.json` and Parametric Semi-Ellipse Arena Generator in `build_worlds.py`

**Files:**
- Modify: `keiko-sim/config/simulation_params.json`
- Modify: `keiko-sim/scripts/build_worlds.py`
- Modify: `keiko-sim/tests/test_meshes_and_arena.py`

**Interfaces:**
- Consumes: `keiko-sim/meshes/orquita_chassis_body.stl`, `keiko-sim/meshes/orquita_wheel.stl`, `keiko-sim/meshes/orquita_capote.stl`
- Produces:
  - `build_worlds.compute_semi_ellipse_segments(variant: str) -> list[dict]`
  - `build_worlds.generate_world(controller_name: str, map_name: str, sensor_config: str, exit_wall: str, include_capote: bool) -> str`
  - Generated `.wbt` files in `keiko-sim/worlds/`

- [ ] **Step 1: Add unit tests for semi-ellipse geometry, $30\text{ cm}$ gap, $5\text{ mm}$ walls, eraser obstacles, and `.wbt` generation**

Append to `keiko-sim/tests/test_meshes_and_arena.py`:
```python
import sys
SCRIPTS_DIR = os.path.join(SIM_DIR, "scripts")
if SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, SCRIPTS_DIR)

import build_worlds


class TestArenaGeometry(unittest.TestCase):
    def test_semi_ellipse_wall_segments_and_exit_gap(self):
        for variant in ("standard", "deformed_wide", "deformed_narrow", "asymmetric", "labyrinth", "corners"):
            geom = build_worlds.get_arena_geometry(variant)
            self.assertAlmostEqual(geom["wall_thickness"], 0.005, places=4)
            self.assertAlmostEqual(geom["wall_height"], 0.18, places=3)
            self.assertAlmostEqual(geom["exit_width"], 0.30, delta=0.005)
            # Check area is approximately 1.70 m^2 (+/- 0.35 m^2 for deformed variants)
            self.assertGreater(geom["area_m2"], 1.35)
            self.assertLess(geom["area_m2"], 2.15)
            # Check gap between left and right arc endpoints at the exit
            self.assertAlmostEqual(geom["actual_exit_gap_m"], 0.30, delta=0.005)

    def test_marbles_and_obstacles_clearance(self):
        for variant in ("standard", "labyrinth", "corners", "corridors"):
            cfg = build_worlds.MAP_CONFIGS[variant]
            self.assertEqual(len(cfg["marbles"]), 3)
            self.assertGreaterEqual(len(cfg["boxes"]), 2)
            self.assertLessEqual(len(cfg["boxes"]), 3)
            for m_name, mx, my in cfg["marbles"]:
                # Inside semi-ellipse and with safe margin from back wall (y = -0.60) and arc
                self.assertGreater(my, -0.38, f"{m_name} too close to back wall in {variant}")
                self.assertLess(my, 0.38, f"{m_name} too close to exit vertex in {variant}")

    def test_generated_world_uses_meshes_and_has_no_chamfers_or_exit_tunnel(self):
        wbt_no_cap = build_worlds.generate_world("live_visualizer", "standard", "dual_plane_8", "north", include_capote=False)
        self.assertIn("../meshes/orquita_chassis_body.stl", wbt_no_cap)
        self.assertIn("../meshes/orquita_wheel.stl", wbt_no_cap)
        self.assertNotIn("../meshes/orquita_capote.stl", wbt_no_cap)
        self.assertNotIn("EXIT_CHAMFER", wbt_no_cap)
        self.assertNotIn("EXIT_WALL_NORTH", wbt_no_cap)

        wbt_cap = build_worlds.generate_world("live_visualizer", "standard", "dual_plane_8", "north", include_capote=True)
        self.assertIn("../meshes/orquita_capote.stl", wbt_cap)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest keiko-sim/tests/test_meshes_and_arena.py`
Expected: FAIL with `AttributeError: module 'build_worlds' has no attribute 'get_arena_geometry'`

- [ ] **Step 3: Update `simulation_params.json` and `build_worlds.py`**

Update `keiko-sim/config/simulation_params.json` and `keiko-sim/scripts/build_worlds.py` so that:
- `get_arena_geometry(map_name)` computes the flat back wall at $Y = -0.60\text{ m}$ ($X \in [-b, +b]$ with $b \approx 0.90\text{ m}$) and the curved semi-ellipse cardboard segments reaching $(0, +0.60\text{ m})$ ($a \approx 1.20\text{ m}$), leaving an exact $0.30\text{ m}$ ($30\text{ cm}$) central opening at the top of the arc, with $5\text{ mm}$ thickness and $18\text{ cm}$ height.
- Supports deformation profiles (`standard`, `deformed_wide`, `deformed_narrow`, `asymmetric`) plus obstacle/marble presets (`standard`, `labyrinth`, `corners`, `corridors`) using 2 to 3 whiteboard-eraser-sized obstacles ($0.14 \times 0.06 \times 0.12\text{ m}$) and 3 marbles ($\varnothing = 0.016\text{ m}$) placed with $\ge 22\text{ cm}$ clearance.
- `generate_world(..., include_capote=False)` uses `orquita_chassis_body.stl`, `orquita_wheel.stl` on the 4 `HingeJoint` nodes at $(\pm 0.0521, \pm 0.0586, 0.0336)\text{ m}$, and optionally includes `orquita_capote.stl` when `include_capote=True` (or via `--capote` CLI flag / `ORQUITAS_CAPOTE=1`).

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 keiko-sim/scripts/build_worlds.py && python3 -m unittest keiko-sim/tests/test_meshes_and_arena.py`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add keiko-sim/config/simulation_params.json keiko-sim/scripts/build_worlds.py keiko-sim/worlds/*.wbt keiko-sim/tests/test_meshes_and_arena.py
git commit -m "feat(sim): replace square arena with deformable semi-ellipse bay and 3D CAD robot"
```

---

### Task 3: Adapt Controllers and Mapper to the Semi-Ellipse Arena and Verify in Headless Webots

**Files:**
- Modify: `keiko-sim/controllers/common/arena_mapper.py`
- Modify: `keiko-sim/controllers/live_visualizer/live_visualizer.py`
- Modify: `keiko-sim/controllers/robot_client/robot_client.py`
- Modify: `keiko-sim/controllers/dataset_collector/dataset_collector.py`
- Modify: `keiko-sim/tests/test_meshes_and_arena.py`

**Interfaces:**
- Consumes: Updated `.wbt` worlds from Task 2 (exit at $(0.0, +0.60)\text{ m}$ for North orientation, back wall at $Y = -0.60\text{ m}$, width $X \in [-0.90, +0.90]\text{ m}$).
- Produces: Working Webots simulation controllers that detect the semi-ellipse exit, capture marbles on pass-over, and collect valid sensor samples inside the semi-ellipse bounds.

- [ ] **Step 1: Add integration test running Webots headless on the new semi-ellipse world**

Append to `keiko-sim/tests/test_meshes_and_arena.py`:
```python
import subprocess


class TestWebotsHeadlessLoad(unittest.TestCase):
    def test_webots_loads_new_semi_ellipse_world_without_vrml_errors(self):
        world_path = os.path.join(SIM_DIR, "worlds", "bahia_de_caza.wbt")
        cmd = [
            "webots", "--batch", "--mode=fast", "--no-rendering",
            "--stdout", "--stderr", world_path
        ]
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            out, err = proc.communicate(timeout=12)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, err = proc.communicate()
        combined = (out or "") + "\n" + (err or "")
        self.assertNotIn("ERROR:", combined)
        self.assertIn("ORQUITAS (KEIKO)", combined)
```

- [ ] **Step 2: Update `EXIT_GEOM` and arena bounds in the controllers**

Update `EXIT_GEOM` in `live_visualizer.py`, `robot_client.py`, and `dataset_collector.py` to match the semi-ellipse vertex distance ($0.60\text{ m}$ from center, i.e., $1.20\text{ m}$ from the back wall at $Y = -0.60\text{ m}$):
- `"north": ((0.0, 0.60), math.pi / 2.0)`
- `"east": ((0.60, 0.0), 0.0)`
- `"south": ((0.0, -0.60), -math.pi / 2.0)`
- `"west": ((-0.60, 0.0), math.pi)`
Also update `ArenaMapper` default bounds to `x_min=-0.95, x_max=0.95, y_min=-0.65, y_max=0.65` and include the semi-ellipse wall segments (`WALL_ARC_*`, `WALL_BACK`) in `live_visualizer.py` obstacle clearance list.

- [ ] **Step 3: Run all unit and headless Webots tests**

Run: `python3 -m unittest keiko-sim/tests/test_meshes_and_arena.py -v`
Expected: PASS (all 4 tests pass, zero VRML/mesh errors in Webots).

- [ ] **Step 4: Commit**

```bash
git add keiko-sim/controllers/ keiko-sim/tests/test_meshes_and_arena.py
git commit -m "feat(sim): adapt controllers and occupancy mapper to semi-ellipse bay geometry"
```
