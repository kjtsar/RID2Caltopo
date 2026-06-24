#!/usr/bin/env python3

import unittest

import run_movement_estimator_comparison as comparison


class MovementEstimatorComparisonCasesTest(unittest.TestCase):
    def test_powerhouse_reviewed_ir_cases_are_included(self) -> None:
        labels = {case.label for case in comparison.CASES}

        self.assertIn("powerhouse1-ir", labels)
        self.assertIn("powerhouse2-ir", labels)
        self.assertIn("powerhouse3-ir", labels)
        self.assertIn("powerhouse-team-ir", labels)

    def test_pressure_metric_reads_nested_detection_pressure(self) -> None:
        score = {
            "detection_pressure": {
                "box_event_count": 7,
                "box_area_p90_norm": 0.125,
            }
        }

        self.assertEqual(comparison.pressure_metric(score, "box_event_count"), 7.0)
        self.assertEqual(comparison.pressure_metric(score, "box_area_p90_norm"), 0.125)
        self.assertEqual(comparison.pressure_metric(score, "missing"), 0.0)
        self.assertEqual(comparison.pressure_metric(None, "box_event_count"), 0.0)


if __name__ == "__main__":
    unittest.main()
