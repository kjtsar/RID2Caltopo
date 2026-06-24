#!/usr/bin/env python3
"""Focused tests for reviewed detection quality metrics."""

from __future__ import annotations

import math
import unittest

import review_eval


class ReviewEvalMetricsTest(unittest.TestCase):
    def test_score_review_reports_hit_distance_distribution(self) -> None:
        annotations = [
            review_eval.Annotation(0, 1.0, 0.50, 0.50, "good", "correct_detection", "person", "", ""),
            review_eval.Annotation(1, 1.1, 0.52, 0.50, "good", "correct_detection", "person", "", ""),
            review_eval.Annotation(2, 1.2, 0.90, 0.90, "bad", "false_positive", "artifact", "", ""),
        ]
        detections = [
            review_eval.Detection(30, 1.0, "thermal", 0.51, 0.50, 0.06, 0.06, 1.0),
            review_eval.Detection(33, 1.1, "thermal", 0.54, 0.50, 0.06, 0.06, 1.0),
        ]

        score = review_eval.score_review(
            annotations,
            detections,
            time_window_s=0.05,
            track_gap_s=0.35,
            track_join_radius=0.08,
        )

        self.assertEqual(score["true_positive_annotations"], 2)
        self.assertEqual(score["false_positive_annotations"], 0)
        self.assertAlmostEqual(score["hit_distance_p50_norm"], 0.015)
        self.assertAlmostEqual(score["hit_distance_p90_norm"], 0.019)
        self.assertAlmostEqual(score["hit_distance_max_norm"], 0.02)
        self.assertAlmostEqual(score["hit_distance_p50_px_1080p"], 16.2)
        self.assertAlmostEqual(score["hit_distance_p90_px_1080p"], 20.52)

    def test_score_review_reports_track_first_hit_time(self) -> None:
        annotations = [
            review_eval.Annotation(0, 2.0, 0.30, 0.30, "good", "missed_target", "person", "", ""),
            review_eval.Annotation(1, 2.1, 0.31, 0.30, "good", "missed_target", "person", "", ""),
        ]
        detections = [
            review_eval.Detection(64, 2.133, "thermal", 0.31, 0.30, 0.06, 0.06, 1.0),
        ]

        score = review_eval.score_review(
            annotations,
            detections,
            time_window_s=0.05,
            track_gap_s=0.35,
            track_join_radius=0.08,
        )

        self.assertEqual(score["matched_tracks"], 1)
        self.assertTrue(math.isclose(score["first_hit_time_s_min"], 2.133))
        self.assertTrue(math.isclose(score["first_hit_latency_s_min"], 0.133))

    def test_score_review_reports_miss_and_fp_distance_distribution(self) -> None:
        annotations = [
            review_eval.Annotation(0, 1.0, 0.10, 0.10, "bad", "missed_target", "person", "", ""),
            review_eval.Annotation(1, 1.1, 0.20, 0.20, "bad", "missed_target", "person", "", ""),
            review_eval.Annotation(2, 1.2, 0.50, 0.50, "bad", "false_positive", "unknown", "", ""),
        ]
        detections = [
            review_eval.Detection(30, 1.0, "thermal", 0.20, 0.10, 0.02, 0.02, 1.0),
            review_eval.Detection(33, 1.1, "thermal", 0.40, 0.20, 0.02, 0.02, 1.0),
            review_eval.Detection(36, 1.2, "thermal", 0.51, 0.50, 0.06, 0.06, 1.0),
        ]

        score = review_eval.score_review(
            annotations,
            detections,
            time_window_s=0.05,
            track_gap_s=0.35,
            track_join_radius=0.08,
        )

        self.assertEqual(score["missed_annotations"], 2)
        self.assertAlmostEqual(score["miss_distance_p50_norm"], 0.15)
        self.assertAlmostEqual(score["miss_distance_p90_norm"], 0.19)
        self.assertAlmostEqual(score["miss_distance_min_norm"], 0.10)
        self.assertAlmostEqual(score["false_positive_distance_p50_norm"], 0.01)
        self.assertAlmostEqual(score["false_positive_distance_max_norm"], 0.01)

    def test_summarize_detection_pressure_reports_box_area_and_streaks(self) -> None:
        annotations = [
            review_eval.Annotation(0, 1.0, 0.50, 0.50, "good", "correct_detection", "person", "", ""),
            review_eval.Annotation(1, 1.1, 0.51, 0.50, "good", "correct_detection", "person", "", ""),
        ]
        detections = [
            review_eval.Detection(30, 1.0, "thermal", 0.50, 0.50, 0.10, 0.20, 1.0),
            review_eval.Detection(31, 1.033, "thermal", 0.90, 0.90, 0.20, 0.20, 1.0),
            review_eval.Detection(32, 1.067, "motion", 0.70, 0.70, 0.10, 0.10, 1.0),
            review_eval.Detection(35, 1.167, "thermal", 0.20, 0.20, 0.20, 0.30, 1.0),
        ]

        pressure = review_eval.summarize_detection_pressure(
            detections,
            annotations,
            time_window_s=0.05,
        )

        self.assertEqual(pressure["box_frame_count"], 4)
        self.assertEqual(pressure["box_event_count"], 4)
        self.assertAlmostEqual(pressure["box_area_sum_norm"], 0.13)
        self.assertAlmostEqual(pressure["box_area_mean_norm"], 0.0325)
        self.assertAlmostEqual(pressure["box_area_p50_norm"], 0.03)
        self.assertAlmostEqual(pressure["box_area_p90_norm"], 0.054)
        self.assertAlmostEqual(pressure["box_area_max_norm"], 0.06)
        self.assertEqual(pressure["max_box_frame_streak"], 3)
        self.assertEqual(pressure["off_target_box_event_count"], 3)
        self.assertEqual(pressure["off_target_box_frame_count"], 3)
        self.assertEqual(pressure["max_off_target_box_frame_streak"], 2)

    def test_score_review_includes_detection_pressure(self) -> None:
        annotations = [
            review_eval.Annotation(0, 1.0, 0.50, 0.50, "good", "correct_detection", "person", "", ""),
        ]
        detections = [
            review_eval.Detection(30, 1.0, "thermal", 0.50, 0.50, 0.10, 0.10, 1.0),
            review_eval.Detection(31, 1.033, "thermal", 0.80, 0.80, 0.10, 0.10, 1.0),
        ]

        score = review_eval.score_review(
            annotations,
            detections,
            time_window_s=0.05,
            track_gap_s=0.35,
            track_join_radius=0.08,
        )

        self.assertEqual(score["detection_pressure"]["box_event_count"], 2)
        self.assertEqual(score["detection_pressure"]["off_target_box_event_count"], 1)


if __name__ == "__main__":
    unittest.main()
