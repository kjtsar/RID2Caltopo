#!/usr/bin/env python3
"""Focused tests for visible-color telemetry summaries."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import run_regression_suite


class ColorTelemetrySummaryTest(unittest.TestCase):
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
