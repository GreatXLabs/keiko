#!/usr/bin/env python3
import math
import os
import sys
import unittest

SIM_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
COMMON_DIR = os.path.join(SIM_DIR, "controllers", "common")
SCRIPTS_DIR = os.path.join(SIM_DIR, "scripts")
for d in (COMMON_DIR, SCRIPTS_DIR):
    if d not in sys.path:
        sys.path.insert(0, d)

import build_worlds
from ultrasonic_physics import UltrasonicPhysicsModel, SensorBorderFilter


class TestUltrasonicPhysicsAndSensors(unittest.TestCase):
    def test_trisector_front_6_is_default_layout_and_preserves_other_configs(self):
        self.assertIn("trisector_front_6", build_worlds.SENSOR_LAYOUTS)
        for cfg in ("dual_plane_4", "dual_plane_6", "dual_plane_8", "trisector_10"):
            self.assertIn(cfg, build_worlds.SENSOR_LAYOUTS)

        wbt = build_worlds.generate_world("live_visualizer", "standard", "trisector_front_6", "north")
        for s_name in ("us_low_left", "us_low_center", "us_low_right", "us_high_left", "us_high_center", "us_high_right"):
            self.assertIn(f'name "{s_name}"', wbt)
        self.assertNotIn('name "us_side_left"', wbt)
        self.assertNotIn('name "us_rear_left"', wbt)

    def test_marble_acoustic_visibility_limit(self):
        model = UltrasonicPhysicsModel(mode="realistic", seed=42)
        # Robot en (0, 0) mirando a +X (yaw = 0.0)
        # Caso 1: Canica cerca (20 cm al frente en X=0.34, Y=0.0 -> ~20 cm desde sensor central en X=0.14)
        # El sensor bajo central lee ~20 cm (canica) y el alto lee 95 cm (muro de fondo).
        raw_near = {
            "us_low_left": 100.0,
            "us_low_center": 20.0,
            "us_low_right": 100.0,
            "us_high_left": 100.0,
            "us_high_center": 95.0,
            "us_high_right": 100.0,
        }
        res_near = model.apply(
            raw_near,
            robot_pose=(0.0, 0.0, 0.0),
            marble_positions=[(0.34, 0.0)],
        )
        self.assertLess(res_near["us_low_center"], 28.0)
        self.assertGreater(res_near["us_high_center"] - res_near["us_low_center"], 50.0)

        # Caso 2: Canica lejos (a 50 cm del sensor bajo central -> X=0.64, Y=0.0).
        # Aunque el raycast geométrico ideal de Webots haya reportado 50.0 cm en us_low_center,
        # físicamente una esfera de 16 mm a 50 cm no refleja suficiente energía 40 kHz,
        # por lo que el sensor bajo pierde el eco de la canica y lee el fondo (~95 cm).
        raw_far_marble = {
            "us_low_left": 100.0,
            "us_low_center": 50.0,
            "us_low_right": 100.0,
            "us_high_left": 100.0,
            "us_high_center": 95.0,
            "us_high_right": 100.0,
        }
        res_far = model.apply(
            raw_far_marble,
            robot_pose=(0.0, 0.0, 0.0),
            marble_positions=[(0.64, 0.0)],
        )
        self.assertGreater(res_far["us_low_center"], 80.0)

    def test_specular_reflection_on_oblique_wall(self):
        model = UltrasonicPhysicsModel(mode="realistic", seed=7)
        # Cuando un sensor incide con ángulo muy oblicuo (> 42 grados respecto a la normal),
        # el pulso rebota como espejo y devuelve multipath o pérdida de eco.
        degraded = model.apply_wall_incidence(dist_cm=40.0, incidence_rad=math.radians(55.0))
        self.assertGreater(degraded, 50.0)

    def test_border_filter_rejects_single_spike(self):
        flt = SensorBorderFilter(sensor_names=["us_low_center"])
        for _ in range(3):
            v = flt.update({"us_low_center": 40.0})["us_low_center"]
        self.assertAlmostEqual(v, 40.0, delta=1.0)
        # Inyectar un pico espurio aislado de crosstalk (250 cm en 1 solo ciclo)
        v_spike = flt.update({"us_low_center": 250.0})["us_low_center"]
        # El filtro de mediana de 3 muestras rechaza el pico de 1 ciclo
        self.assertLess(v_spike, 60.0)


if __name__ == "__main__":
    unittest.main()
