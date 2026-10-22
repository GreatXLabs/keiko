import os
import sys
import unittest

COMMON_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "controllers", "common"))
if COMMON_DIR not in sys.path:
    sys.path.insert(0, COMMON_DIR)

from coaxial_neural_planner import CoaxialNeuralClassifier, CoaxialNavigationPlanner


class TestCoaxialNeuralPlanner(unittest.TestCase):
    def setUp(self):
        self.clf = CoaxialNeuralClassifier()
        self.planner = CoaxialNavigationPlanner()

    def test_classifier_loads_real_neuroph_weights_and_classifies_all_four_classes(self):
        self.assertGreaterEqual(len(self.clf.layers), 2)
        self.assertEqual(self.clf.classify_pair(11.0, 11.5), "BLOQUEO")
        self.assertEqual(self.clf.classify_pair(20.0, 20.5), "BLOQUEO")
        self.assertEqual(self.clf.classify_pair(65.0, 66.0), "ESPACIO_LIBRE")
        self.assertEqual(self.clf.classify_pair(16.0, 60.0), "LOBITO")
        self.assertEqual(self.clf.classify_pair(13.0, 29.0), "LOBITO")
        self.assertEqual(self.clf.classify_pair(240.0, 242.0), "SALIDA")

    def test_classifier_allows_skimming_at_open_distances(self):
        # Beyond 24 cm from a wall, both low and high read ~25 cm -> ESPACIO_LIBRE
        self.assertEqual(self.clf.classify_pair(26.0, 26.5), "ESPACIO_LIBRE")

    def test_planner_frontal_block_commits_reverse_then_turn(self):
        readings = {
            "us_low_left": 11.0, "us_low_center": 10.5, "us_low_right": 11.2,
            "us_high_left": 11.5, "us_high_center": 11.0, "us_high_right": 11.8,
        }
        classes = self.clf.classify_3_pairs(readings)
        self.assertEqual(classes, ["BLOQUEO", "BLOQUEO", "BLOQUEO"])

        # Step 1: initiates reverse
        vl, vr, state, action = self.planner.evaluate(classes, captures_count=0)
        self.assertLess(vl, 0.0)
        self.assertLess(vr, 0.0)
        self.assertIn("RETROCESO", action)

        # Drain reverse steps and verify transition to committed turn
        saw_turn = False
        for _ in range(15):
            vl, vr, state, action = self.planner.evaluate(classes, captures_count=0)
            if "MANIOBRA_GIRO" in action:
                saw_turn = True
                self.assertLess(vl * vr, 0.0)
                break
        self.assertTrue(saw_turn)

    def test_planner_anti_snag_on_persistent_single_side_block(self):
        # A persistent block on right side across consecutive deflection attempts must trigger anti-loop reverse
        pairs = ["ESPACIO_LIBRE", "ESPACIO_LIBRE", "BLOQUEO"]
        triggered_anti_snag = False
        for _ in range(50):
            vl, vr, state, action = self.planner.evaluate(pairs, captures_count=0)
            if "ANTIBUCLE_RETROCESO" in action:
                triggered_anti_snag = True
                self.assertLess(vl, 0.0)
                self.assertLess(vr, 0.0)
                break
        self.assertTrue(triggered_anti_snag)

    def test_planner_pursues_marble_left_center_right_in_search_mode(self):
        # Left marble
        vl, vr, state, action = self.planner.evaluate(["LOBITO", "ESPACIO_LIBRE", "ESPACIO_LIBRE"], captures_count=0)
        self.assertEqual(action, "APROX_LOBITO_IZQ")
        self.assertLess(vl, vr)

        # Center marble
        vl, vr, state, action = self.planner.evaluate(["ESPACIO_LIBRE", "LOBITO", "ESPACIO_LIBRE"], captures_count=0)
        self.assertEqual(action, "CAPTURA_LOBITO_CENTRO")
        self.assertAlmostEqual(vl, vr, places=2)

        # Right marble
        vl, vr, state, action = self.planner.evaluate(["ESPACIO_LIBRE", "ESPACIO_LIBRE", "LOBITO"], captures_count=0)
        self.assertEqual(action, "APROX_LOBITO_DER")
        self.assertGreater(vl, vr)

    def test_planner_avoids_premature_exit_in_search_and_exits_in_escape(self):
        # Search mode -> turns away from exit
        vl, vr, state, action = self.planner.evaluate(["SALIDA", "SALIDA", "ESPACIO_LIBRE"], captures_count=1)
        self.assertEqual(action, "REINGRESO_DESDE_SALIDA")
        self.assertLess(vl * vr, 0.0)

        # Escape mode -> drives straight through exit
        planner_esc = CoaxialNavigationPlanner()
        vl, vr, state, action = planner_esc.evaluate(["ESPACIO_LIBRE", "SALIDA", "ESPACIO_LIBRE"], captures_count=3)
        self.assertEqual(state, "ESCAPE")
        self.assertEqual(action, "ESCAPE_SALIDA_DIRECTA")
        self.assertGreater(vl, 4.0)
        self.assertAlmostEqual(vl, vr, places=2)


if __name__ == "__main__":
    unittest.main()
