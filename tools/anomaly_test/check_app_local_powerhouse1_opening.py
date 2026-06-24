#!/usr/bin/env python3
"""Gate app-local PowerHouse1 opening recall through the video harness."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

import review_eval


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--clip", type=Path, required=True)
    parser.add_argument("--review", type=Path, required=True)
    parser.add_argument("--out-csv", type=Path, required=True)
    parser.add_argument("--out-summary", type=Path, required=True)
    parser.add_argument("--start-s", type=float, default=0.0)
    parser.add_argument("--end-s", type=float, default=4.8)
    parser.add_argument("--min-recall", type=float, default=0.20)
    parser.add_argument("--min-tracks", type=int, default=1)
    parser.add_argument("--min-thermal-tracks", type=int, default=1)
    parser.add_argument("--max-motion-events", type=int, default=1000)
    args = parser.parse_args()

    args.out_csv.parent.mkdir(parents=True, exist_ok=True)
    args.out_summary.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            str(args.binary),
            str(args.clip),
            "--app-defaults",
            "--app-display-output",
            "--time-start",
            f"{args.start_s}",
            "--time-end",
            f"{args.end_s}",
            "--no-video",
            "-c",
            str(args.out_csv),
            "--summary-json",
            str(args.out_summary),
        ],
        check=True,
    )

    _text, payload = review_eval.summarize(
        args.review,
        [args.out_csv],
        time_window_s=0.10,
        start_s=args.start_s,
        end_s=args.end_s,
    )
    score = payload["csv_results"][0]["score"]
    annotations = review_eval.load_review(args.review, start_s=args.start_s, end_s=args.end_s)
    detections = review_eval.load_detections(args.out_csv)
    motion_event_count = sum(1 for d in detections if d.algorithm == "motion")
    thermal_score = review_eval.score_review(
        annotations,
        [d for d in detections if d.algorithm == "thermal"],
        time_window_s=0.10,
        track_gap_s=0.35,
        track_join_radius=0.08,
    )
    recall = score["reviewed_recall"]
    matched_tracks = int(score["matched_tracks"])
    thermal_matched_tracks = int(thermal_score["matched_tracks"])
    summary = {
        "true_positive_annotations": score["true_positive_annotations"],
        "positive_annotation_count": score["positive_annotation_count"],
        "reviewed_recall": recall,
        "matched_tracks": matched_tracks,
        "positive_tracks": score["positive_tracks"],
        "thermal_true_positive_annotations": thermal_score["true_positive_annotations"],
        "thermal_reviewed_recall": thermal_score["reviewed_recall"],
        "thermal_matched_tracks": thermal_matched_tracks,
        "motion_event_count": motion_event_count,
    }
    print(json.dumps(summary, indent=2))
    if (
        recall is None
        or recall < args.min_recall
        or matched_tracks < args.min_tracks
        or thermal_matched_tracks < args.min_thermal_tracks
        or motion_event_count > args.max_motion_events
    ):
        print(
            "PowerHouse1 app-local opening recall gate failed: "
            f"recall={recall} min_recall={args.min_recall} "
            f"matched_tracks={matched_tracks} min_tracks={args.min_tracks} "
            f"thermal_matched_tracks={thermal_matched_tracks} "
            f"min_thermal_tracks={args.min_thermal_tracks} "
            f"motion_event_count={motion_event_count} "
            f"max_motion_events={args.max_motion_events}",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
