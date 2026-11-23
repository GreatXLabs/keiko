#!/usr/bin/env python3
import ast
import json
import os
import sys
import types
import unittest

SIM_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
CONTROLLER_DIR = os.path.join(SIM_ROOT, "controllers", "robot_client")
PARAMS_PATH = os.path.join(SIM_ROOT, "config", "simulation_params.json")
ROBOT_CLIENT_PATH = os.path.join(CONTROLLER_DIR, "robot_client.py")


def _import_robot_client():
    if CONTROLLER_DIR not in sys.path:
        sys.path.insert(0, CONTROLLER_DIR)
    injected_stub = False
    if "controller" not in sys.modules:
        try:
            import controller  # noqa: F401
        except ImportError:
            stub = types.ModuleType("controller")
            stub.Supervisor = type("Supervisor", (), {})
            sys.modules["controller"] = stub
            injected_stub = True
    try:
        import robot_client
        return robot_client
    finally:
        if injected_stub and "controller" in sys.modules:
            del sys.modules["controller"]


robot_client = _import_robot_client()


class TestRobotClientProtocol(unittest.TestCase):
    def test_format_telemetry_line_has_seven_fields_no_pose(self):
        line = robot_client.format_telemetry_line(45.25, 120.5, 80.0, 38.4, 15.2, -15.0, 1234)
        self.assertTrue(line.startswith("TELEMETRY:"))
        self.assertTrue(line.endswith("\n"))
        payload = line[len("TELEMETRY:"):].strip()
        tokens = payload.split(",")
        self.assertEqual(len(tokens), 7, f"Expected 7 telemetry fields, got {len(tokens)}: {line!r}")
        values = [float(tok) for tok in tokens[:6]]
        self.assertEqual(values, [45.25, 120.5, 80.0, 38.4, 15.2, -15.0])
        self.assertEqual(int(tokens[6]), 1234)

    def test_simulation_params_and_continuous_mega_sweep(self):
        with open(PARAMS_PATH, "r", encoding="utf-8") as fh:
            params = json.load(fh)
        self.assertIn("servo_sweep", params)
        self.assertEqual(params["servo_sweep"]["min_deg"], -45.0)
        self.assertEqual(params["servo_sweep"]["max_deg"], 60.0)
        self.assertEqual(params["servo_sweep"]["step_deg"], 5.0)

        cfg = robot_client.load_sweep_config()
        self.assertEqual(cfg, (-45.0, 60.0, 5.0))
        self.assertEqual(robot_client.load_sweep_config("/nonexistent/simulation_params.json"), (-45.0, 60.0, 5.0))

        angles = robot_client.build_sweep_angles(*cfg)
        self.assertEqual(len(angles), 22)
        self.assertEqual(angles[0], -45.0)
        self.assertEqual(angles[-1], 60.0)
        for i in range(1, len(angles)):
            self.assertAlmostEqual(angles[i] - angles[i - 1], 5.0, places=5)

    def test_no_forbidden_events_or_state_sweep_switching(self):
        with open(ROBOT_CLIENT_PATH, "r", encoding="utf-8") as fh:
            source = fh.read()

        self.assertNotIn("EVENT:SALIDA_META", source)
        self.assertNotIn("EVENT:COLISION", source)
        self.assertNotIn("approach_binocular_angles", source)
        self.assertNotIn("escape_guard_angles", source)
        self.assertIn("find_penalizable_contact", source)

        tree = ast.parse(source)
        main_fn = next(
            node for node in tree.body
            if isinstance(node, ast.FunctionDef) and node.name == "main"
        )
        main_src = ast.get_source_segment(source, main_fn) or ""
        self.assertIn("format_telemetry_line(", main_src)


if __name__ == "__main__":
    unittest.main()
