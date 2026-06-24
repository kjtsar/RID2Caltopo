#!/usr/bin/env python3
"""Tests for the visible-color realtime qualification report."""

from __future__ import annotations

import csv
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import run_color_realtime_qualification as qual


def write_detection_csv(path: Path, rows: list[dict[str, object]]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        handle.write("# comment\n")
        writer = csv.DictWriter(
            handle,
            fieldnames=(
                "frame",
                "time_s",
                "algorithm",
                "cx_norm",
                "cy_norm",
                "box_w_norm",
                "box_h_norm",
                "weight",
                "label",
            ),
        )
        writer.writeheader()
        writer.writerows(rows)


class ColorRealtimeQualificationTest(unittest.TestCase):
    def test_detection_signature_ignores_review_label_column(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            csv_path = Path(tmp) / "detections.csv"
            write_detection_csv(
                csv_path,
                [
                    {
                        "frame": 99,
                        "time_s": "3.343",
                        "algorithm": "color",
                        "cx_norm": "0.5285",
                        "cy_norm": "0.4478",
                        "box_w_norm": "0.0442",
                        "box_h_norm": "0.0442",
                        "weight": "1.00",
                        "label": "G",
                    }
                ],
            )

            self.assertEqual(
                qual.detection_signature(csv_path),
                [("99", "3.343", "color", "0.5285", "0.4478", "0.0442", "0.0442", "1.00")],
            )

    def test_summarize_red2_geometry_reports_track_motion(self) -> None:
        rows = [
            qual.DetectionRow(99, 3.343, "color", 0.5285, 0.4478, 0.0442, 0.0442, 1.0),
            qual.DetectionRow(215, 7.300, "color", 0.5668, 0.4506, 0.0442, 0.0442, 1.0),
        ]

        geometry = qual.summarize_red2_geometry(rows)

        self.assertEqual(geometry["box_rows"], 2)
        self.assertAlmostEqual(geometry["first_time_s"], 3.343)
        self.assertAlmostEqual(geometry["last_time_s"], 7.300)
        self.assertAlmostEqual(geometry["first_cx_norm"], 0.5285)
        self.assertAlmostEqual(geometry["last_cx_norm"], 0.5668)
        self.assertAlmostEqual(geometry["track_dx_norm"], 0.0383)
        self.assertTrue(geometry["matches_expected_red2_patio_track"])

    def test_candidate_comparison_requires_identical_signatures(self) -> None:
        one = [("99", "3.343", "color", "0.5285", "0.4478", "0.0442", "0.0442", "1.00")]
        same = list(one)
        different = [("100", "3.377", "color", "0.5285", "0.4478", "0.0442", "0.0442", "1.00")]

        self.assertTrue(qual.candidate_outputs_match(one, same))
        self.assertFalse(qual.candidate_outputs_match(one, different))

    def test_review_precision_recall_uses_review_eval_key_names(self) -> None:
        precision, recall = qual.review_precision_recall(
            {
                "reviewed_precision": 1.0,
                "reviewed_recall": 0.6,
            }
        )

        self.assertEqual(precision, 1.0)
        self.assertEqual(recall, 0.6)

    def test_evaluate_gate_accepts_current_red_expectations(self) -> None:
        report = {
            "cases": [
                {
                    "label": "red1-reviewed",
                    "candidate_outputs_identical": True,
                    "candidate_1_repeated_outputs_identical": True,
                    "candidate_1": {
                        "realtime_factor": 1.05,
                        "best_realtime_factor": 1.05,
                    },
                    "review_score": {
                        "reviewed_precision": 1.0,
                        "reviewed_recall": 0.6,
                    },
                },
                {
                    "label": "red2-reviewed",
                    "candidate_outputs_identical": True,
                    "candidate_1_repeated_outputs_identical": True,
                    "candidate_1": {
                        "realtime_factor": 1.19,
                        "best_realtime_factor": 1.19,
                    },
                    "review_score": {
                        "reviewed_precision": 1.0,
                        "reviewed_recall": 1.0,
                    },
                    "red2_geometry": {
                        "matches_expected_red2_patio_track": True,
                    },
                },
            ],
        }

        result = qual.evaluate_gate(report)

        self.assertTrue(result.passed)
        self.assertEqual(result.failures, [])

    def test_evaluate_gate_reports_candidate_and_capability_failures(self) -> None:
        report = {
            "cases": [
                {
                    "label": "red1-reviewed",
                    "candidate_outputs_identical": False,
                    "candidate_1_repeated_outputs_identical": False,
                    "candidate_1": {
                        "realtime_factor": 0.99,
                        "best_realtime_factor": 0.99,
                    },
                    "review_score": {
                        "reviewed_precision": 0.75,
                        "reviewed_recall": 0.5,
                    },
                },
                {
                    "label": "red2-reviewed",
                    "candidate_outputs_identical": True,
                    "candidate_1_repeated_outputs_identical": True,
                    "candidate_1": {
                        "realtime_factor": 1.1,
                        "best_realtime_factor": 1.1,
                    },
                    "review_score": {
                        "reviewed_precision": 1.0,
                        "reviewed_recall": 1.0,
                    },
                    "red2_geometry": {
                        "matches_expected_red2_patio_track": False,
                    },
                },
            ],
        }

        result = qual.evaluate_gate(report)

        self.assertFalse(result.passed)
        self.assertIn("red1-reviewed: candidate limit 1 output differs from limit 4", result.failures)
        self.assertIn("red1-reviewed: repeated candidate limit 1 outputs differ", result.failures)
        self.assertIn("red1-reviewed: candidate limit 1 realtime 0.990 below 1.000", result.failures)
        self.assertIn("red1-reviewed: reviewed precision 0.750 below 1.000", result.failures)
        self.assertIn("red1-reviewed: reviewed recall 0.500 below 0.600", result.failures)
        self.assertIn("red2-reviewed: Red2 patio-target geometry check failed", result.failures)

    def test_evaluate_gate_uses_best_candidate_one_realtime_probe(self) -> None:
        report = {
            "cases": [
                {
                    "label": "red1-reviewed",
                    "candidate_outputs_identical": True,
                    "candidate_1_repeated_outputs_identical": True,
                    "candidate_1": {
                        "realtime_factor": 0.82,
                        "best_realtime_factor": 1.04,
                    },
                    "review_score": {
                        "reviewed_precision": 1.0,
                        "reviewed_recall": 0.6,
                    },
                },
                {
                    "label": "red2-reviewed",
                    "candidate_outputs_identical": True,
                    "candidate_1_repeated_outputs_identical": True,
                    "candidate_1": {
                        "realtime_factor": 1.18,
                        "best_realtime_factor": 1.18,
                    },
                    "review_score": {
                        "reviewed_precision": 1.0,
                        "reviewed_recall": 1.0,
                    },
                    "red2_geometry": {
                        "matches_expected_red2_patio_track": True,
                    },
                },
            ],
        }

        result = qual.evaluate_gate(report)

        self.assertTrue(result.passed)
        self.assertEqual([], result.failures)

    def test_evaluate_gate_scores_red2_reviewed_case(self) -> None:
        report = {
            "cases": [
                {
                    "label": "red2-reviewed",
                    "candidate_outputs_identical": True,
                    "candidate_1_repeated_outputs_identical": True,
                    "candidate_1": {
                        "realtime_factor": 1.05,
                        "best_realtime_factor": 1.05,
                    },
                    "review_score": {
                        "reviewed_precision": 0.8,
                        "reviewed_recall": 0.75,
                    },
                    "red2_geometry": {
                        "matches_expected_red2_patio_track": True,
                    },
                },
            ],
        }

        result = qual.evaluate_gate(report)

        self.assertFalse(result.passed)
        self.assertIn("red2-reviewed: reviewed precision 0.800 below 1.000", result.failures)
        self.assertIn("red2-reviewed: reviewed recall 0.750 below 0.900", result.failures)

    def test_evaluate_gate_requires_all_reviewed_manifest_excerpts(self) -> None:
        report = {
            "manifest_coverage": {
                "missing_reviewed_excerpt_ids": ["new-reviewed-color-case"],
            },
            "cases": [
                {
                    "label": "red1-reviewed",
                    "candidate_outputs_identical": True,
                    "candidate_1_repeated_outputs_identical": True,
                    "candidate_1": {
                        "realtime_factor": 1.05,
                        "best_realtime_factor": 1.05,
                    },
                    "review_score": {
                        "reviewed_precision": 1.0,
                        "reviewed_recall": 0.6,
                    },
                },
                {
                    "label": "red2-reviewed",
                    "candidate_outputs_identical": True,
                    "candidate_1_repeated_outputs_identical": True,
                    "candidate_1": {
                        "realtime_factor": 1.19,
                        "best_realtime_factor": 1.19,
                    },
                    "review_score": {
                        "reviewed_precision": 1.0,
                        "reviewed_recall": 1.0,
                    },
                    "red2_geometry": {
                        "matches_expected_red2_patio_track": True,
                    },
                },
            ],
        }

        result = qual.evaluate_gate(report)

        self.assertFalse(result.passed)
        self.assertIn(
            "manifest: reviewed visible-color excerpt not covered by realtime gate: new-reviewed-color-case",
            result.failures,
        )

    def test_manifest_coverage_reports_current_reviewed_excerpt(self) -> None:
        coverage = qual.manifest_coverage(
            qual.REPO_ROOT / qual.COLOR_MANIFEST,
            qual.CASES,
        )

        self.assertIn("red1-opening-target-track", coverage["reviewed_excerpt_ids"])
        self.assertIn("red1-opening-target-track", coverage["covered_reviewed_excerpt_ids"])
        self.assertEqual([], coverage["missing_reviewed_excerpt_ids"])

    def test_manifest_coverage_reports_unreviewed_source_clips(self) -> None:
        coverage = qual.manifest_coverage(
            qual.REPO_ROOT / qual.COLOR_MANIFEST,
            qual.CASES,
        )

        self.assertEqual(
            ["color1", "color2", "color3"],
            coverage["unreviewed_source_clip_ids"],
        )
        self.assertNotIn("red1", coverage["unreviewed_source_clip_ids"])
        self.assertNotIn("red2", coverage["unreviewed_source_clip_ids"])

    def test_red2_is_promoted_to_reviewed_realtime_case(self) -> None:
        red2_case = next(case for case in qual.CASES if case.label == "red2-reviewed")

        self.assertEqual("red2-tablet-patio-target", red2_case.manifest_excerpt_id)
        self.assertEqual(qual.REPO_ROOT / "tools/anomaly_test/reviews/Red2.review.json", qual.REPO_ROOT / red2_case.review)
        self.assertEqual(0.0, red2_case.time_start)
        self.assertIsNone(red2_case.time_end)
        coverage = qual.manifest_coverage(
            qual.REPO_ROOT / qual.COLOR_MANIFEST,
            qual.CASES,
        )
        self.assertIn("red2-tablet-patio-target", coverage["reviewed_excerpt_ids"])
        self.assertIn("red2-tablet-patio-target", coverage["covered_reviewed_excerpt_ids"])

    def test_select_primary_candidate_run_records_best_realtime_factor(self) -> None:
        cold = {
            "candidate_limit": 1,
            "csv_path": "/tmp/cold.csv",
            "summary_path": "/tmp/cold.json",
            "summary": {
                "realtime_factor": 0.82,
                "frame_count": 153,
                "rescan_modes": {},
            },
            "signature": [("1", "0.033", "color", "0.5", "0.5", "0.04", "0.04", "1.00")],
        }
        warm = {
            "candidate_limit": 1,
            "csv_path": "/tmp/warm.csv",
            "summary_path": "/tmp/warm.json",
            "summary": {
                "realtime_factor": 1.06,
                "frame_count": 153,
                "rescan_modes": {},
            },
            "signature": list(cold["signature"]),
        }

        selected = qual.select_primary_candidate_run([cold, warm])

        self.assertIs(selected, warm)
        compact = qual.compact_run(selected, repeated_runs=[cold, warm])
        self.assertEqual(1.06, compact["best_realtime_factor"])
        self.assertEqual([0.82, 1.06], compact["realtime_factors"])


if __name__ == "__main__":
    unittest.main()
