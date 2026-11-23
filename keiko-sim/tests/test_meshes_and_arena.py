#!/usr/bin/env python3
import math
import os
import re
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

        # Capote: ~0.330m total X (frente en +0.1325m, cola/aleta caudal en -0.1975m), 0.200m Y width, ~0.156m Z height
        n_cap, c_min, c_max = read_stl_bbox(capote_stl)
        self.assertGreater(n_cap, 20)
        self.assertAlmostEqual(c_max[0] - c_min[0], 0.330, delta=0.01)
        self.assertAlmostEqual(c_min[0], -0.1975, delta=0.005)
        self.assertAlmostEqual(c_max[0], 0.1325, delta=0.005)
        self.assertAlmostEqual(c_max[1] - c_min[1], 0.200, delta=0.01)
        self.assertAlmostEqual(c_max[2] - c_min[2], 0.156, delta=0.01)


import sys
SCRIPTS_DIR = os.path.join(SIM_DIR, "scripts")
if SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, SCRIPTS_DIR)

import build_worlds


class TestArenaGeometry(unittest.TestCase):
    def test_semi_ellipse_wall_segments_and_exit_gap(self):
        for variant in ("standard", "circular", "realistic"):
            geom = build_worlds.get_arena_geometry(variant)
            self.assertAlmostEqual(geom["wall_thickness"], 0.005, places=4)
            self.assertAlmostEqual(geom["wall_height"], 0.18, places=3)
            self.assertAlmostEqual(geom["exit_width"], 0.30, delta=0.005)
            # Check area is within competition bounds (~1.70 m^2; circular ~2.12 m^2)
            self.assertGreater(geom["area_m2"], 1.35)
            self.assertLess(geom["area_m2"], 2.30)
            # Centerline gap is 0.305 m (+ wall_thickness 0.005 m -> >= 0.300 m interior clear gap)
            clear_gap_m = geom["actual_exit_gap_m"] - geom["wall_thickness"]
            self.assertGreaterEqual(clear_gap_m, 0.30)
            self.assertAlmostEqual(clear_gap_m, 0.30, delta=0.005)
            self.assertAlmostEqual(geom["actual_exit_gap_m"], 0.305, delta=0.005)

    def test_marbles_and_obstacles_clearance(self):
        variants = ("standard", "circular", *(f"scen_{i}" for i in range(1, 15)))
        for variant in variants:
            cfg = build_worlds.MAP_CONFIGS[variant]
            geom = build_worlds.get_arena_geometry(cfg.get("variant", "standard"))
            ex, ey = geom["exit_pos"]
            self.assertEqual(len(cfg["marbles"]), 3)
            self.assertGreaterEqual(len(cfg["boxes"]), 2)
            self.assertLessEqual(len(cfg["boxes"]), 3)
            for m_name, mx, my in cfg["marbles"]:
                # Inside bay and with safe margin from back wall and exit doorway
                self.assertGreater(my, geom["y_back"] + 0.15, f"{m_name} too close to back wall in {variant}")
                self.assertGreater(
                    math.hypot(mx - ex, my - ey),
                    0.25,
                    f"{m_name} too close to exit doorway in {variant}",
                )

    def test_generated_world_uses_meshes_and_has_no_chamfers_or_exit_tunnel(self):
        wbt_no_cap = build_worlds.generate_world("robot_client", "standard", "north", include_capote=False)
        self.assertIn("../meshes/orquita_chassis_body.stl", wbt_no_cap)
        self.assertIn("../meshes/orquita_wheel.stl", wbt_no_cap)
        self.assertNotIn("../meshes/orquita_capote.stl", wbt_no_cap)
        self.assertNotIn("EXIT_CHAMFER", wbt_no_cap)
        self.assertNotIn("EXIT_WALL_NORTH", wbt_no_cap)

        wbt_cap = build_worlds.generate_world("robot_client", "standard", "north", include_capote=True)
        self.assertIn("../meshes/orquita_capote.stl", wbt_cap)

    def test_chassis_collision_boxes_follow_cad_and_clear_wheels(self):
        wbt = build_worlds.generate_world("robot_client", "standard", "north")
        robot_body = wbt.split('  name "orquita_robot"', 1)[1]
        boxes = re.findall(
            r"Pose \{\s+translation ([^\n]+)\s+children \[\s+Box \{ size ([^}]+) \}",
            robot_body,
        )

        # 5 CAD boxes preserving the 7.0 cm central picker opening (y in [-0.035, +0.035])
        self.assertEqual(boxes, [
            ("0.09875 0.0575 0.039", "0.0675 0.045 0.072"),
            ("0.09875 -0.0575 0.039", "0.0675 0.045 0.072"),
            ("0.09875 0 0.0525", "0.0675 0.070 0.045"),
            ("0 0 0.048", "0.125 0.088 0.055"),
            ("-0.095 0 0.048", "0.065 0.150 0.055"),
        ])


class TestWebotsHeadlessLoad(unittest.TestCase):
    def test_webots_loads_new_world_without_vrml_errors(self):
        import shutil
        import subprocess
        webots_bin = shutil.which("webots") or shutil.which(
            "webots", path=os.path.expanduser("~/.local/bin")
        )
        if not webots_bin:
            self.skipTest("Webots no instalado en PATH")
        world = os.path.join(SIM_DIR, "worlds", "bahia_de_caza.wbt")
        cmd = [webots_bin, "--batch", "--mode=fast", "--no-rendering", "--stdout", "--stderr", world]
        if shutil.which("xvfb-run"):
            cmd = ["xvfb-run", "-a"] + cmd
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            out, err = proc.communicate(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, err = proc.communicate()
        combined = (out or "") + "\n" + (err or "")
        self.assertNotIn("ERROR:", combined, f"Errores VRML en Webots:\n{combined}")
        self.assertNotIn("Traceback (most recent call last)", combined, f"Excepción Python en Webots:\n{combined}")
        self.assertIn("Salida detectada: NORTH @", combined)


if __name__ == "__main__":
    unittest.main()
