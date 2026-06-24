#!/usr/bin/env python3
"""Tests for visible-color review prep helpers."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import generate_visible_color_review_prep as prep


class VisibleColorReviewPrepTest(unittest.TestCase):
    def test_unreviewed_source_clips_excludes_reviewed_sources(self) -> None:
        manifest = {
            "source_clips": [
                {"id": "color1", "source_path": "Color1.mp4"},
                {"id": "red1", "source_path": "Red1.mp4"},
                {"id": "red2", "source_path": "Red2.mp4"},
            ],
            "excerpts": [
                {"source_clip_id": "red1", "review_status": "reviewed"},
                {"source_clip_id": "red2", "review_status": "reviewed"},
            ],
        }

        clips = prep.unreviewed_source_clips(manifest)

        self.assertEqual(["color1"], [clip["id"] for clip in clips])

    def test_contact_sheet_plan_uses_stable_names(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo_root = Path(tmp)
            output_dir = repo_root / "out"
            clip = {
                "id": "color1",
                "label": "Color1.mp4",
                "source_path": "app/src/test/resources/vidcap/Color1.mp4",
            }

            plan = prep.contact_sheet_plan(repo_root, output_dir, clip)

        self.assertEqual("color1", plan["id"])
        self.assertEqual(repo_root / "app/src/test/resources/vidcap/Color1.mp4", plan["video_path"])
        self.assertEqual(output_dir / "color1-contact.png", plan["contact_sheet_path"])
        self.assertEqual(output_dir / "color1-metadata.json", plan["metadata_path"])

    def test_report_records_pending_review_clip_ids(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_path = Path(tmp) / "review_prep_report.json"
            plans = [
                {
                    "id": "color1",
                    "label": "Color1.mp4",
                    "video_path": Path(tmp) / "Color1.mp4",
                    "contact_sheet_path": Path(tmp) / "color1-contact.png",
                    "metadata_path": Path(tmp) / "color1-metadata.json",
                    "source_notes": "Full source retained for later reviewed excerpt selection.",
                    "metadata_summary": {
                        "duration_s": 6.3,
                        "width": 1280,
                        "height": 720,
                        "fps": 29.9,
                        "frame_count": 197,
                    },
                }
            ]

            prep.write_report(report_path, plans)
            report = json.loads(report_path.read_text(encoding="utf-8"))

        self.assertEqual(["color1"], report["pending_review_source_clip_ids"])
        self.assertEqual("needs_review_excerpt", report["clips"][0]["review_status"])
        self.assertIn("create a reviewed excerpt", report["clips"][0]["review_action"])
        self.assertEqual("Full source retained for later reviewed excerpt selection.", report["clips"][0]["source_notes"])
        self.assertEqual(6.3, report["clips"][0]["metadata_summary"]["duration_s"])
        self.assertEqual("color1-contact.png", Path(report["clips"][0]["contact_sheet_path"]).name)

    def test_metadata_summary_normalizes_ffprobe_fields(self) -> None:
        metadata = {
            "streams": [
                {
                    "width": 1280,
                    "height": 720,
                    "avg_frame_rate": "197000/6579",
                    "nb_frames": "197",
                }
            ],
            "format": {
                "duration": "6.301100",
            },
        }

        summary = prep.metadata_summary(metadata)

        self.assertEqual(1280, summary["width"])
        self.assertEqual(720, summary["height"])
        self.assertEqual(197, summary["frame_count"])
        self.assertAlmostEqual(6.3011, summary["duration_s"])
        self.assertAlmostEqual(29.944, summary["fps"], places=3)


if __name__ == "__main__":
    unittest.main()
