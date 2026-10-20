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


if __name__ == "__main__":
    unittest.main()
