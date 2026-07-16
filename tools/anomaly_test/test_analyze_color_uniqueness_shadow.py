#!/usr/bin/env python3
"""Tests for aggregate Color AD uniqueness shadow diagnostics."""

from __future__ import annotations

import unittest

import analyze_color_uniqueness_shadow as analyzer


def candidate(index: int, x_norm: float, score: float, temporal_valid: bool = True) -> dict:
    return {
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
        "shadow_blob_sample_count": 6,
        "shadow_ring_sample_count": 10,
        "shadow_sampled_grid_contribution_count": 2,
        "shadow_predominant_u_bin": 2,
        "shadow_predominant_v_bin": 8,
        "shadow_predominant_family_share": 0.75,
        "shadow_normalized_entropy": 0.25,
        "shadow_purity": 0.75,
        "shadow_excluded_background_rarity": 0.7,
        "shadow_normalized_rarity_factor": 0.7,
        "shadow_local_ring_divergence": 0.6,
        "shadow_chroma_reliability": 0.8,
        "shadow_temporal_valid": temporal_valid,
        "shadow_temporal_consistency": 0.9,
        "shadow_composite_uniqueness": score,
    }


class AggregateShadowAnalyzerTest(unittest.TestCase):
    def test_positive_and_negative_rank_lift_temporal_rate_and_first_hit(self) -> None:
        frames = [
            {
                "time_s": 1.0,
                "winning_candidate_index": 0,
                "candidates": [candidate(0, 0.2, 0.4), candidate(1, 0.8, 0.8, temporal_valid=False)],
            },
            {
                "time_s": 2.0,
                "winning_candidate_index": 0,
                "candidates": [candidate(0, 0.2, 0.3), candidate(1, 0.8, 0.9)],
            },
        ]
        reviews = [
            {"time_s": 1.0, "x_norm": 0.8, "y_norm": 0.5, "review_kind": "missed_target"},
            {"time_s": 2.0, "x_norm": 0.2, "y_norm": 0.5, "review_kind": "false_positive"},
        ]
        report = analyzer.aggregate_evidence([{"frames": frames, "review_details": reviews}])
        self.assertEqual(report["valid_shadow_coverage"], 1.0)
        self.assertEqual(report["positive_rank_lift"]["mean"], 1.0)
        self.assertEqual(report["negative_rank_lift"]["mean"], 1.0)
        self.assertEqual(report["first_reviewed_hit_opportunity"]["time_s"], 1.0)
        self.assertEqual(report["temporal_valid_rate"], 0.75)
        self.assertIsNone(report["promotion_decision"])

    def test_no_candidates_and_invalid_evidence_make_no_decision(self) -> None:
        report = analyzer.aggregate_evidence([{"frames": [{"time_s": 1.0, "candidates": []}]}])
        self.assertIsNone(report["valid_shadow_coverage"])
        self.assertIsNone(report["production_shadow_winner_agreement"])
        self.assertIsNone(report["promotion_decision"])
        self.assertEqual(report["promotion_decision_reason"], "missing_or_invalid_shadow_evidence")


if __name__ == "__main__":
    unittest.main()
