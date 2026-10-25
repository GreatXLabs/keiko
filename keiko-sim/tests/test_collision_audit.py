#!/usr/bin/env python3
import os
import sys
import unittest

COMMON_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "controllers", "common"))
if COMMON_DIR not in sys.path:
    sys.path.insert(0, COMMON_DIR)

from collision_audit import find_penalizable_contact, is_new_impact


class TestCollisionAudit(unittest.TestCase):
    def test_returns_first_external_chassis_contact(self):
        points = [
            (0.20, 0.00, 0.022),
            (0.30, 0.00, 0.050),
            (0.40, 0.01, 0.060),
        ]
        marbles = [(0.32, 0.00)]

        result = find_penalizable_contact(points, marbles, (0.0, 0.0), (0.0, 1.0))

        self.assertEqual(result, (0.40, 0.01, 0.060))

    def test_discards_floor_marble_and_exit_contacts(self):
        cases = [
            ([(0.20, 0.00, 0.022)], [], 0.0),
            ([(0.30, 0.00, 0.050)], [(0.32, 0.00)], 0.0),
            ([(0.40, 0.60, 0.050)], [], 0.581),
        ]

        for points, marbles, robot_y in cases:
            with self.subTest(points=points, marbles=marbles, robot_y=robot_y):
                self.assertIsNone(find_penalizable_contact(points, marbles, (0.0, robot_y), (0.0, 1.0)))

    def test_filter_thresholds_are_inclusive_only_where_required(self):
        points = [(0.035, 0.00, 0.050)]

        self.assertEqual(
            find_penalizable_contact(points, [(0.00, 0.00)], (0.0, 0.58), (0.0, 1.0)),
            (0.035, 0.00, 0.050),
        )
        self.assertEqual(
            find_penalizable_contact([(0.20, 0.00, 0.022001)], [], (0.0, 0.0), (0.0, 1.0)),
            (0.20, 0.00, 0.022001),
        )

    def test_exit_filter_uses_exit_aligned_coordinate(self):
        point = (0.40, 0.00, 0.050)
        cases = [
            ((0.00, 0.61), (0.0, 1.0), None),
            ((0.20, 0.61), (1.0, 0.0), point),
            ((0.00, -0.61), (0.0, -1.0), None),
            ((-0.61, 0.00), (-1.0, 0.0), None),
        ]

        for robot_position, exit_direction, expected in cases:
            with self.subTest(robot_position=robot_position, exit_direction=exit_direction):
                result = find_penalizable_contact([point], [], robot_position, exit_direction)
                self.assertEqual(result, expected)

    def test_contact_release_recontact_counts_without_cooldown(self):
        point = (0.40, 0.00, 0.050)
        sequence = [
            (point, False, True),
            (point, True, False),
            (None, False, False),
            (point, False, True),
        ]

        for contact_point, was_colliding, expected in sequence:
            with self.subTest(contact_point=contact_point, was_colliding=was_colliding):
                self.assertEqual(is_new_impact(contact_point, was_colliding), expected)


if __name__ == "__main__":
    unittest.main()
