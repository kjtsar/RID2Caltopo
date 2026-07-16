#!/usr/bin/env python3
"""Focused tests for shadow scene-coverage evidence evaluation."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import analyze_scene_coverage_shadow as analyzer


def record(
    frame: int,
    *,
    selected_blocks: tuple[int, ...] = (),
    rescan: str = "full",
    shadow_mode: str = "locked_incremental",
    boxes: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    mask = sum(1 << block for block in selected_blocks)
    return {
        "schema_version": 1,
        "frame": frame,
        "source_ts_us": frame * 33_333,
        "authoritative_rescan_mode": rescan,
        "mode": shadow_mode,
        "selected_mask": f"{mask:012x}",
        "mandatory_mask": "000000000000",
        "selected_blocks": len(selected_blocks),
        "mandatory_blocks": 0,
        "estimated_selected_samples": 100 * len(selected_blocks),
        "selected_fraction": len(selected_blocks) / 48.0,
        "max_coverage_debt": 1.25,
        "max_age_us": 66_666,
        "max_age_frames": 2,
        "newly_exposed_fraction": 0.0,
        "reason_flags": 0,
        "raw_boxes": boxes or [],
    }


def box(cx: float = 0.30, cy: float = 0.25) -> dict[str, object]:
    return {
        "algorithm": "color",
        "algorithm_mask": 1,
        "cx_norm": cx,
        "cy_norm": cy,
        "left_norm": cx - 0.02,
        "top_norm": cy - 0.02,
        "right_norm": cx + 0.02,
        "bottom_norm": cy + 0.02,
    }


class SceneCoverageAnalyzerTest(unittest.TestCase):
    def load(self, rows: list[dict[str, object]]) -> list[dict[str, object]]:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.jsonl"
            path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            return analyzer.load_records(path)

    def test_covered_candidate_passes_strict_gate(self) -> None:
        center_block = analyzer.block_for_point(0.30, 0.25)
        report = analyzer.analyze_records(
            self.load([record(1, selected_blocks=(center_block,), boxes=[box()])])
        )

        self.assertEqual(1, report["candidates"]["covered_same_frame"])
        self.assertEqual([], analyzer.gate_failures(report))

    def test_missed_candidate_reports_bounded_later_selection(self) -> None:
        center_block = analyzer.block_for_point(0.30, 0.25)
        rows = [
            record(1, boxes=[box()]),
            record(2, selected_blocks=(center_block,), rescan="target_only"),
        ]
        report = analyzer.analyze_records(self.load(rows))
        detail = report["candidates"]["missed_details"][0]

        self.assertEqual(1, report["candidates"]["missed_same_frame"])
        self.assertEqual(1, report["candidates"]["explained_by_later_selection"])
        self.assertEqual(1, detail["next_shadow_selection"]["latency_frames"])
        self.assertEqual([], analyzer.gate_failures(report))

    def test_malformed_evidence_is_rejected(self) -> None:
        malformed = record(1, boxes=[box()])
        del malformed["raw_boxes"]
        with self.assertRaisesRegex(analyzer.EvidenceError, "raw_boxes must be an array"):
            self.load([malformed])

    def test_zero_candidate_evidence_does_not_pass_vacuously(self) -> None:
        report = analyzer.analyze_records(self.load([record(1)]))

        self.assertEqual(0, report["candidates"]["total"])
        self.assertIn(
            "no raw candidates on authoritative full-scan frames",
            analyzer.gate_failures(report),
        )


if __name__ == "__main__":
    unittest.main()
