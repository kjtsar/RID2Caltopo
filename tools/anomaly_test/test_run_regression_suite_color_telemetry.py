#!/usr/bin/env python3
"""Focused tests for visible-color telemetry summaries."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import run_regression_suite


class ColorTelemetrySummaryTest(unittest.TestCase):
    def test_detector_args_recognize_app_color_profiles(self) -> None:
        self.assertTrue(
            run_regression_suite.detector_args_use_color(
                ["--app-defaults", "--app-appearance", "color"]
            )
        )
        self.assertTrue(
            run_regression_suite.detector_args_use_color(
                ["--app-defaults", "--app-appearance", "auto"]
            )
        )
        self.assertFalse(
            run_regression_suite.detector_args_use_color(
                ["--app-defaults", "--app-appearance", "thermal"]
            )
        )

    def shadow_candidate(self, index: int, score: float, x_norm: float = 0.5, **updates: object) -> dict:
        candidate = {
            "index": index,
            "valid": True,
            "x_norm": x_norm,
            "y_norm": 0.5,
            "bbox_left_norm": x_norm - 0.01,
            "bbox_top_norm": 0.49,
            "bbox_right_norm": x_norm + 0.01,
            "bbox_bottom_norm": 0.51,
            "shadow_color_valid": True,
            "shadow_blob_domain": "dense_pixel_exact",
            "shadow_ring_domain": "dense_pixel_exact",
            "shadow_sampled_grid_contribution_domain": "sampled_grid_bbox",
            "shadow_blob_sample_count": 5,
            "shadow_ring_sample_count": 9,
            "shadow_sampled_grid_contribution_count": 2,
            "shadow_predominant_u_bin": 3,
            "shadow_predominant_v_bin": 7,
            "shadow_predominant_family_share": 0.8,
            "shadow_normalized_entropy": 0.2,
            "shadow_purity": 0.8,
            "shadow_excluded_background_rarity": 0.7,
            "shadow_normalized_rarity_factor": 0.7,
            "shadow_local_ring_divergence": 0.6,
            "shadow_chroma_reliability": 0.9,
            "shadow_temporal_valid": True,
            "shadow_temporal_consistency": 0.9,
            "shadow_composite_uniqueness": score,
        }
        candidate.update(updates)
        return candidate

    def test_shadow_order_uses_epsilon_bands_and_preserves_production_order(self) -> None:
        candidates = [
            self.shadow_candidate(0, 0.50000),
            self.shadow_candidate(1, 0.50005),
            self.shadow_candidate(2, 0.7),
        ]
        ordered = run_regression_suite.shadow_order_candidates(candidates)
        self.assertEqual([candidate["index"] for candidate in ordered], [2, 0, 1])

    def test_shadow_review_rows_report_valid_and_invalid_candidates(self) -> None:
        valid = self.shadow_candidate(0, 0.4, x_norm=0.2)
        invalid = self.shadow_candidate(1, 0.8, x_norm=0.8, shadow_color_valid=False)
        frames = [{"time_s": 1.0, "winning_candidate_index": 0, "candidates": [valid, invalid]}]
        details = [
            {"time_s": 1.0, "x_norm": 0.2, "y_norm": 0.5, "review_kind": "missed_target"},
            {"time_s": 1.0, "x_norm": 0.8, "y_norm": 0.5, "review_kind": "false_positive"},
        ]
        result = run_regression_suite.summarize_shadow_review_rows(frames, details, 0.05)
        self.assertEqual(result["reviewed_rows"][0]["shadow_rank"], 1)
        self.assertIsNone(result["reviewed_rows"][0]["winner_swap"])
        self.assertEqual(result["reviewed_rows"][1]["invalid_shadow_reason"], "shadow_color_invalid")

    def test_shadow_review_rows_handle_no_candidates_and_missing_fields(self) -> None:
        details = [{"time_s": 1.0, "x_norm": 0.5, "y_norm": 0.5, "review_kind": "missed_target"}]
        empty = run_regression_suite.summarize_shadow_review_rows(
            [{"time_s": 1.0, "candidates": []}], details, 0.05
        )
        self.assertEqual(empty["reviewed_rows"][0]["invalid_shadow_reason"], "no_candidates")
        missing = run_regression_suite.summarize_shadow_review_rows(
            [{"time_s": 1.0, "candidates": [{"index": 0, "x_norm": 0.5, "y_norm": 0.5}]}],
            details,
            0.05,
        )
        self.assertTrue(missing["reviewed_rows"][0]["invalid_shadow_reason"].startswith("missing_fields:"))

    def test_shadow_review_rows_do_not_claim_far_away_candidate(self) -> None:
        candidate = self.shadow_candidate(0, 0.8, x_norm=0.8)
        details = [
            {"time_s": 1.0, "x_norm": 0.2, "y_norm": 0.5, "review_kind": "missed_target"}
        ]
        result = run_regression_suite.summarize_shadow_review_rows(
            [{"time_s": 1.0, "winning_candidate_index": 0, "candidates": [candidate]}],
            details,
            0.05,
        )
        row = result["reviewed_rows"][0]
        self.assertEqual(row["invalid_shadow_reason"], "no_nearby_candidate")
        self.assertIsNone(row["production_rank"])
        self.assertIsNone(row["shadow_rank"])

    def test_review_kind_override_preserves_floristan_intended_positive(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp = Path(tmp_dir)
            source = tmp / "floristan.review.json"
            effective = tmp / "effective.json"
            source.write_text(json.dumps({"frames": [{"source_timestamp_us": 2_235_567, "annotations": [
                {"review_kind": "false_positive", "x_norm": 0.4, "y_norm": 0.3}
            ]}]}) + "\n")
            path = run_regression_suite.apply_review_kind_overrides(
                source,
                effective,
                [{"time_s": 2.235567, "from": "false_positive", "to": "missed_target"}],
            )
            self.assertEqual(json.loads(path.read_text())["frames"][0]["annotations"][0]["review_kind"], "missed_target")
            self.assertEqual(json.loads(source.read_text())["frames"][0]["annotations"][0]["review_kind"], "false_positive")

    def test_summarize_color_telemetry_reports_target_stage_and_nearby_candidate(self) -> None:
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp = Path(tmp_dir)
            review = tmp / "review.json"
            telemetry = tmp / "color_debug.jsonl"
            review.write_text(
                json.dumps(
                    {
                        "frames": [
                            {
                                "source_timestamp_us": 1_000_000,
                                "annotations": [
                                    {
                                        "x_norm": 0.50,
                                        "y_norm": 0.50,
                                        "verdict": "bad",
                                        "review_kind": "missed_target",
                                        "object_type": "artifact",
                                        "scenario": "",
                                        "note": "red target",
                                    }
                                ],
                            }
                        ]
                    }
                )
                + "\n"
            )
            telemetry.write_text(
                json.dumps(
                    {
                        "time_s": 1.01,
                        "candidate_count": 1,
                        "winning_candidate_index": 0,
                        "candidates": [
                            {
                                "index": 0,
                                "x_norm": 0.51,
                                "y_norm": 0.50,
                                "bbox_left_norm": 0.505,
                                "bbox_top_norm": 0.495,
                                "bbox_right_norm": 0.515,
                                "bbox_bottom_norm": 0.505,
                                "final_score": 3.25,
                                "area": 1.0,
                                "span": 1.0,
                                "hist_current_count": 2.0,
                                "hist_recent_count": 1.0,
                                "scene_commonness": 0.02,
                                "local_ring_chroma_contrast": 30.0,
                                "local_ring_luma_contrast": 40.0,
                                "current_nearest_hist_distance": 0.0,
                                "recent_nearest_hist_distance": 1.0,
                            }
                        ],
                        "target": {
                            "enabled": True,
                            "stage": "no_candidate",
                            "support_map_compact_prominence": 0.42,
                            "support_map_local_peak": 2.76,
                            "support_map_ring_mean": 2.2,
                            "component_rejection_reason": 1,
                            "nearest_candidate_distance": 0.01,
                        },
                    }
                )
                + "\n"
            )
            detail_rows = [
                {
                    "time_s": 1.0,
                    "x_norm": 0.50,
                    "y_norm": 0.50,
                    "review_kind": "missed_target",
                    "scenario": "",
                    "note": "red target",
                    "outcome": "miss",
                }
            ]

            summary = run_regression_suite.summarize_color_telemetry(
                review_path=review,
                telemetry_path=telemetry,
                start_s=None,
                end_s=None,
                detail_rows=detail_rows,
                time_tolerance_s=0.05,
            )

            self.assertEqual(summary["miss_count"], 1)
            self.assertEqual(summary["stage_counts"], {"candidate_near_target": 1})
            self.assertEqual(summary["nearby_extracted_count"], 1)
            self.assertEqual(summary["target_stage_counts"], {"no_candidate": 1})
            self.assertIn("Color Telemetry", summary["markdown"])
            self.assertIn("compact=0.42", summary["markdown"])


if __name__ == "__main__":
    unittest.main()
