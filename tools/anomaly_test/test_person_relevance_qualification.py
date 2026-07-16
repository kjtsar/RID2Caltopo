#!/usr/bin/env python3
"""Focused tests for paired Person Relevance host qualification."""

from __future__ import annotations

import csv
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import run_person_relevance_qualification as qual


MODEL_SHA = "1" * 64


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def write_detections(path: Path, *, cx: float = 0.5) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=(
                "frame", "time_s", "algorithm", "cx_norm", "cy_norm",
                "box_w_norm", "box_h_norm", "weight",
            ),
        )
        writer.writeheader()
        writer.writerow(
            {
                "frame": 30,
                "time_s": "1.000",
                "algorithm": "color",
                "cx_norm": f"{cx:.3f}",
                "cy_norm": "0.500",
                "box_w_norm": "0.200",
                "box_h_norm": "0.200",
                "weight": "1.000",
            }
        )


def timing_summary(avg_ms: float = 10.0, p95_ms: float = 15.0, realtime: float = 2.0) -> dict[str, object]:
    return {
        "realtime_factor": realtime,
        "qualification_timing": {
            "avg_ms": avg_ms,
            "p95_ms": p95_ms,
            "max_ms": p95_ms + 5.0,
        },
    }


def evidence() -> dict[str, object]:
    decisions = [
        {
            "frame": 30,
            "time_s": 1.0,
            "box": {
                "cx_norm": 0.5,
                "cy_norm": 0.5,
                "width_norm": 0.2,
                "height_norm": 0.2,
            },
            "raw_score": 0.9,
            "threshold": 0.6,
            "status": "accepted",
        },
        {
            "frame": 150,
            "time_s": 5.0,
            "box": {
                "cx_norm": 0.8,
                "cy_norm": 0.8,
                "width_norm": 0.1,
                "height_norm": 0.1,
            },
            "raw_score": 0.2,
            "threshold": 0.6,
            "status": "below_threshold",
        },
    ]
    signature = hashlib.sha256(
        json.dumps(decisions, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    return {
        "schema_version": 1,
        "backend_kind": "deterministic_fake",
        "model_identity": {
            "model_name": "fixture-person",
            "model_version": "1",
            "model_sha256": MODEL_SHA,
            "runtime": "host-fake-1",
            "backend": "deterministic-fixture",
        },
        "provenance": {
            "fixture_id": "fixture-person-v1",
            "generator": "test_person_relevance_qualification.py",
            "source_detectors": ["color_outlier"],
        },
        "counters": {
            "offers": 2,
            "admissions": 2,
            "drops": 0,
            "replaced": 0,
            "stale": 0,
            "evaluated": 2,
            "published": 1,
            "backend_failures": 0,
        },
        "inference_time_us": [900.0, 1100.0],
        "queue_age_us": [100.0, 300.0],
        "first_person_relevance_latency_ms": 42.0,
        "decisions": decisions,
        "decision_signature_sha256": signature,
    }


def create_suite(root: Path) -> Path:
    write_detections(root / "off.csv")
    write_detections(root / "shadow.csv")
    write_json(root / "off-summary.json", timing_summary())
    write_json(root / "shadow-summary.json", timing_summary(10.5, 16.0, 1.8))
    write_json(root / "evidence.json", evidence())
    write_json(
        root / "review.json",
        {
            "schema_version": 2,
            "frames": [
                {
                    "frame_idx": 30,
                    "source_timestamp_us": 1_000_000,
                    "annotations": [
                        {
                            "x_norm": 0.5,
                            "y_norm": 0.5,
                            "verdict": "good",
                            "review_kind": "correct_detection",
                            "object_type": "person",
                        }
                    ],
                }
            ],
        },
    )
    manifest = {
        "schema_version": 1,
        "suite_name": "fixture-suite",
        "cases": [
            {
                "id": "fixture",
                "duration_s": 60.0,
                "review": "review.json",
                "off": {
                    "detections_csv": "off.csv",
                    "summary_json": "off-summary.json",
                },
                "shadow": {
                    "detections_csv": "shadow.csv",
                    "summary_json": "shadow-summary.json",
                },
                "person_evidence": "evidence.json",
            }
        ],
    }
    path = root / "manifest.json"
    write_json(path, manifest)
    return path


class PersonRelevanceQualificationTest(unittest.TestCase):
    def test_tracked_smoke_fixture_passes(self) -> None:
        manifest = Path(__file__).resolve().parent / "fixtures/person_relevance/manifest.json"

        report = qual.run_qualification(manifest)

        self.assertTrue(report["passed"])
        self.assertEqual("tracked-person-smoke-v1", report["cases"][0]["person_runtime"]["provenance"]["fixture_id"])

    def test_passing_pair_reports_quality_runtime_and_performance(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report = qual.run_qualification(create_suite(Path(tmp)))

        self.assertTrue(report["passed"])
        case = report["cases"][0]
        self.assertTrue(case["roi_signatures_identical"])
        self.assertEqual(1.0, case["quality"]["upstream_candidate_recall"])
        self.assertEqual(1.0, case["quality"]["candidate_conditioned_person_recall"])
        self.assertEqual(1.0, case["quality"]["end_to_end_person_recall"])
        self.assertEqual(1.0, case["quality"]["app_visible_person_recall"])
        self.assertEqual(0.0, case["quality"]["false_positives_per_minute"])
        self.assertEqual("fixture-person", case["person_runtime"]["model_identity"]["model_name"])
        self.assertAlmostEqual(1.0, case["person_runtime"]["inference_avg_ms"])
        self.assertAlmostEqual(0.2, case["person_runtime"]["queue_age_avg_ms"])
        self.assertLess(case["playback"]["avg_ratio"], 1.10)

    def test_anomaly_video_summary_timing_uses_slowest_populated_mode_p95(self) -> None:
        timing = qual._timing(
            {
                "realtime_factor": 3.0,
                "stage_timing": {
                    "compiled": True,
                    "avg_total_ms": 4.0,
                    "max_total_ms": 20.0,
                    "by_rescan_mode": {
                        "full": {"frame_count": 3, "p95_total_ms": 18.0},
                        "partial": {"frame_count": 5, "p95_total_ms": 7.0},
                        "target_only": {"frame_count": 0, "p95_total_ms": 0.0},
                    },
                },
            },
            "fixture",
        )

        self.assertEqual(4.0, timing["avg_ms"])
        self.assertEqual(18.0, timing["p95_ms"])
        self.assertEqual(20.0, timing["max_ms"])

    def test_roi_signature_mismatch_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = create_suite(root)
            write_detections(root / "shadow.csv", cx=0.6)

            report = qual.run_qualification(manifest)

        self.assertFalse(report["passed"])
        self.assertIn("fixture: OFF and SHADOW ROI signatures differ", report["failures"])

    def test_performance_regression_fails_average_and_p95(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = create_suite(root)
            write_json(root / "shadow-summary.json", timing_summary(11.1, 18.1, 0.99))

            report = qual.run_qualification(manifest)

        self.assertFalse(report["passed"])
        self.assertTrue(any("average playback" in failure for failure in report["failures"]))
        self.assertTrue(any("p95 playback" in failure for failure in report["failures"]))
        self.assertTrue(any("realtime factor" in failure for failure in report["failures"]))

    def test_missing_required_metric_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = create_suite(root)
            summary = timing_summary()
            summary.pop("realtime_factor")
            write_json(root / "shadow-summary.json", summary)

            with self.assertRaisesRegex(qual.QualificationError, "realtime_factor must be a number"):
                qual.run_qualification(manifest)

    def test_malformed_evidence_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = create_suite(root)
            malformed = evidence()
            malformed["decisions"][0]["raw_score"] = "high"
            malformed["decision_signature_sha256"] = "0" * 64
            write_json(root / "evidence.json", malformed)

            with self.assertRaisesRegex(qual.QualificationError, "raw_score must be a number"):
                qual.run_qualification(manifest)


if __name__ == "__main__":
    unittest.main()
