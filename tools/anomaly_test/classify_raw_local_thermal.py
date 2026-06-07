#!/usr/bin/env python3
"""Classify thermal target JSONL frames by raw-local failure mode.

This is intended for PH1-style target traces emitted by anomaly_video_test with
--thermal-target and --thermal-debug-jsonl. It does not change detector behavior.
"""

from __future__ import annotations

import argparse
import collections
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any


CLASSES = (
    "centroid_shift",
    "barely_under_threshold",
    "true_cold",
    "terrain_merged",
    "off_track_local_peak",
    "publication_or_track_arbitration",
    "unclassified",
)


@dataclass
class ClassifiedFrame:
    frame: int
    time_s: float
    cls: str
    reason: str
    stage: str
    target_margin: float
    peak_margin: float
    peak_offset: float
    centroid_offset: float
    hot_count: int
    local_peak_raw_score: float
    local_peak_is_component_seed: bool


def num(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def boolean(value: Any) -> bool:
    return value is True or str(value).lower() == "true"


def classify_record(record: dict[str, Any], thermal_min_delta: float) -> ClassifiedFrame:
    target = record.get("target") or {}
    frame = int(record.get("frame", -1))
    time_s = num(record.get("time_s"), -1.0)
    stage = str(target.get("stage", ""))

    target_raw_delta = num(target.get("target_raw_delta"), -1.0)
    target_raw_score = num(target.get("target_raw_score"), -1.0)
    target_delta = num(target.get("target_delta"), -1.0)
    local_peak_raw_delta = num(target.get("local_peak_raw_delta"), -1.0)
    local_peak_raw_score = num(target.get("local_peak_raw_score"), -1.0)
    peak_offset = num(
        target.get("local_peak_raw_distance", target.get("local_peak_distance")),
        99.0,
    )
    hot_count = int(num(target.get("local_window_hot_count"), 0.0))
    centroid_dx = num(target.get("local_window_weighted_centroid_dx"), -1.0)
    centroid_dy = num(target.get("local_window_weighted_centroid_dy"), -1.0)
    centroid_valid = not (
        abs(centroid_dx + 1.0) < 1e-6
        and abs(centroid_dy + 1.0) < 1e-6
        and local_peak_raw_delta <= 0.0
    )
    centroid_offset = math.hypot(centroid_dx, centroid_dy) if centroid_valid else -1.0
    target_margin = target_raw_delta - thermal_min_delta
    peak_margin = local_peak_raw_delta - thermal_min_delta

    nearby_rejected = boolean(target.get("nearby_rejected_component_valid"))
    nearby_contains = boolean(target.get("nearby_rejected_component_contains_target"))
    nearby_gate = str(target.get("nearby_rejected_component_gate", "none"))
    nearby_distance = num(target.get("nearby_rejected_component_distance"), 99.0)
    component_rejected = boolean(target.get("component_rejected"))
    dropped_or_capped = (
        boolean(target.get("dropped_by_cap"))
        or boolean(target.get("dropped_by_nms"))
        or boolean(target.get("replaced_by_nms"))
        or int(num(target.get("pre_cap_rank"), -1)) >= 0
        or int(num(target.get("extracted_rank"), -1)) >= 0
        or int(num(target.get("provisional_candidate_index"), -1)) >= 0
    )

    peak_positive = (
        peak_margin >= 0.0
        and local_peak_raw_score > 0.0
        and peak_offset <= 3.0
    )
    target_score_suppressed = target_delta <= 0.0 or target_raw_score <= 0.0
    terrain_like_nearby = (
        component_rejected
        or stage == "rejected_by_gate"
        or nearby_contains
        or (
            nearby_rejected
            and nearby_distance <= 3.0
            and nearby_gate in {"max_area", "ring_hot", "support_near"}
            and hot_count >= 7
        )
    )

    if dropped_or_capped:
        cls = "publication_or_track_arbitration"
        reason = "target-local candidate reached extraction/provisional metadata but was capped, NMSed, or not published"
    elif terrain_like_nearby:
        cls = "terrain_merged"
        reason = f"target is in or near rejected terrain component ({nearby_gate or 'component'})"
    elif (
        target_score_suppressed
        and peak_positive
        and centroid_offset >= 1.0
        and 1 <= hot_count <= 6
    ):
        cls = "centroid_shift"
        reason = "target sample suppressed, but compact nearby raw peak and shifted raw centroid are present"
    elif (
        target_score_suppressed
        and -max(0.75, 0.25 * thermal_min_delta) <= target_margin < 0.0
        and peak_offset <= 1.5
    ):
        cls = "barely_under_threshold"
        reason = "target raw delta is just below thermal_min_delta"
    elif target_margin < -max(1.25, 0.45 * thermal_min_delta) and not peak_positive:
        cls = "true_cold"
        reason = "target raw delta is well below threshold with no nearby positive raw peak"
    elif peak_margin >= 0.0 and (peak_offset > 3.0 or local_peak_raw_score <= 0.0):
        cls = "off_track_local_peak"
        reason = "nearby raw peak is outside rescue radius or score-suppressed"
    else:
        cls = "unclassified"
        reason = "available fields do not meet a strong class rule"

    return ClassifiedFrame(
        frame=frame,
        time_s=time_s,
        cls=cls,
        reason=reason,
        stage=stage,
        target_margin=target_margin,
        peak_margin=peak_margin,
        peak_offset=peak_offset,
        centroid_offset=centroid_offset,
        hot_count=hot_count,
        local_peak_raw_score=local_peak_raw_score,
        local_peak_is_component_seed=boolean(target.get("local_peak_is_component_seed")),
    )


def contiguous_runs(frames: list[ClassifiedFrame]) -> list[dict[str, Any]]:
    if not frames:
        return []
    frames = sorted(frames, key=lambda item: item.frame)
    runs: list[dict[str, Any]] = []
    start = prev = frames[0]
    for item in frames[1:]:
        if item.frame == prev.frame + 1:
            prev = item
            continue
        runs.append(run_summary(start, prev))
        start = prev = item
    runs.append(run_summary(start, prev))
    return runs


def run_summary(start: ClassifiedFrame, end: ClassifiedFrame) -> dict[str, Any]:
    return {
        "frame_start": start.frame,
        "frame_end": end.frame,
        "time_start_s": start.time_s,
        "time_end_s": end.time_s,
        "frame_count": end.frame - start.frame + 1,
    }


def load_frames(path: Path, thermal_min_delta: float) -> list[ClassifiedFrame]:
    result: list[ClassifiedFrame] = []
    with path.open() as handle:
        for line in handle:
            if not line.strip():
                continue
            record = json.loads(line)
            target = record.get("target") or {}
            if not boolean(target.get("enabled", True)):
                continue
            result.append(classify_record(record, thermal_min_delta))
    return result


def as_report(frames: list[ClassifiedFrame], min_run_len: int) -> dict[str, Any]:
    by_class: dict[str, list[ClassifiedFrame]] = {cls: [] for cls in CLASSES}
    for item in frames:
        by_class[item.cls].append(item)

    counts = {cls: len(by_class[cls]) for cls in CLASSES}
    runs = {
        cls: [
            run for run in contiguous_runs(items)
            if run["frame_count"] >= min_run_len
        ]
        for cls, items in by_class.items()
    }
    stages = collections.Counter(item.stage for item in frames)
    seed_counts = collections.Counter(
        item.cls for item in frames if item.local_peak_is_component_seed
    )

    return {
        "frame_count": len(frames),
        "counts": counts,
        "stage_counts": dict(stages),
        "local_peak_component_seed_counts": dict(seed_counts),
        "runs": runs,
    }


def print_text_report(report: dict[str, Any]) -> None:
    print(f"frames: {report['frame_count']}")
    print("counts:")
    for cls in CLASSES:
        print(f"  {cls}: {report['counts'].get(cls, 0)}")
    print("stage_counts:")
    for stage, count in sorted(report["stage_counts"].items()):
        print(f"  {stage}: {count}")
    print("local_peak_component_seed_counts:")
    for cls, count in sorted(report["local_peak_component_seed_counts"].items()):
        print(f"  {cls}: {count}")
    print("runs:")
    for cls in CLASSES:
        runs = report["runs"].get(cls, [])
        if not runs:
            continue
        formatted = ", ".join(
            f"f{run['frame_start']}-{run['frame_end']} "
            f"({run['time_start_s']:.3f}-{run['time_end_s']:.3f}s, "
            f"n={run['frame_count']})"
            for run in runs
        )
        print(f"  {cls}: {formatted}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jsonl", type=Path, help="Thermal target JSONL path")
    parser.add_argument(
        "--thermal-min-delta",
        type=float,
        default=10.0,
        help="thermal_min_delta used by the harness, default: 10.0",
    )
    parser.add_argument(
        "--min-run-len",
        type=int,
        default=2,
        help="minimum contiguous frame run length to print, default: 2",
    )
    parser.add_argument("--json", action="store_true", help="emit JSON report")
    args = parser.parse_args()

    frames = load_frames(args.jsonl, args.thermal_min_delta)
    report = as_report(frames, args.min_run_len)
    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print_text_report(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
