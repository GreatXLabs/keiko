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
    def test_official_3us_2laser_rig_in_generated_world(self):
        wbt = build_worlds.generate_world("robot_client", "standard", "north")
        for dev_name in (
            "us_left",
            "us_center",
            "us_right",
            "laser_left",
            "laser_right",
            "servo_left",
            "servo_right",
        ):
            self.assertIn(f'name "{dev_name}"', wbt)
        for legacy_name in ("us_low_left", "us_high_center", "us_side_left", "us_rear_left"):
            self.assertNotIn(f'name "{legacy_name}"', wbt)

    def test_3d_obstacle_acoustic_visibility_at_5_5cm(self):
        model = UltrasonicPhysicsModel(mode="realistic", seed=42)
        # Robot en (0, 0) mirando a +X (yaw = 0.0); us_center en X=+0.1325, Z=0.055 m.
        # Caso 1: Obstáculo 6x6x6 cm dentro del cono acústico (<= 0.45 m): en (0.3825, 0.0) -> 0.25 m del sensor.
        raw_near = {
            "us_left": 120.0,
            "us_center": 120.0,
            "us_right": 120.0,
        }
        res_near = model.apply(
            raw_near,
            robot_pose=(0.0, 0.0, 0.0),
            active_obstacles_xy=[(0.3825, 0.0)],
        )
        self.assertLess(res_near["us_center"], 28.0)

        # Caso 2: Obstáculo lejano (> 0.55 m desde el sensor, p. ej. en X=0.80 m -> 0.6675 m).
        # Queda fuera del umbral acústico de 0.45 m y preserva la lectura de fondo (~120 cm).
        res_far = model.apply(
            raw_near,
            robot_pose=(0.0, 0.0, 0.0),
            active_obstacles_xy=[(0.80, 0.0)],
        )
        self.assertGreater(res_far["us_center"], 100.0)

    def test_specular_reflection_on_oblique_wall(self):
        model = UltrasonicPhysicsModel(mode="realistic", seed=7)
        # Cuando un sensor incide con ángulo muy oblicuo (> 42 grados respecto a la normal),
        # el pulso rebota como espejo y devuelve multipath o pérdida de eco.
        degraded = model.apply_wall_incidence(dist_cm=40.0, incidence_rad=math.radians(55.0))
        self.assertGreater(degraded, 50.0)

    def test_border_filter_rejects_single_spike(self):
        flt = SensorBorderFilter(sensor_names=["us_center"])
        for _ in range(3):
            v = flt.update({"us_center": 40.0})["us_center"]
        self.assertAlmostEqual(v, 40.0, delta=1.0)
        # Inyectar un pico espurio aislado de crosstalk (250 cm en 1 solo ciclo)
        v_spike = flt.update({"us_center": 250.0})["us_center"]
        # El filtro de mediana de 3 muestras rechaza el pico de 1 ciclo
        self.assertLess(v_spike, 60.0)


if __name__ == "__main__":
    unittest.main()
