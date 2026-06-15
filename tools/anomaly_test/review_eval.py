#!/usr/bin/env python3
"""Score local-playback review points against anomaly_video_test detection CSVs.

Example:
  python3 tools/anomaly_test/review_eval.py \
      app/src/test/resources/vidcap/PowerHouseTeam.review.json \
      app/src/test/resources/vidcap/PowerHouseTeam_motion_debug_detections.csv \
      app/src/test/resources/vidcap/PowerHouseTeam_saliency_v11_debug_detections.csv
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


@dataclass
class Annotation:
    frame_idx: int
    time_s: float
    x: float
    y: float
    verdict: str
    review_kind: str
    object_type: str
    scenario: str
    note: str
    track_id: str | None = None


@dataclass
class Detection:
    frame: int
    time_s: float
    algorithm: str
    cx: float
    cy: float
    w: float
    h: float
    weight: float

    def contains(self, x: float, y: float) -> bool:
        return abs(self.cx - x) <= self.w * 0.5 and abs(self.cy - y) <= self.h * 0.5

    def center_distance(self, x: float, y: float) -> float:
        return math.hypot(self.cx - x, self.cy - y)


POSITIVE_KINDS = {"correct_detection", "missed_target"}
NEGATIVE_KINDS = {"false_positive"}


@dataclass
class Track:
    track_id: str
    annotations: list[Annotation]


def load_review(path: Path, start_s: float | None = None, end_s: float | None = None) -> list[Annotation]:
    raw = json.loads(path.read_text())
    result: list[Annotation] = []
    for frame in raw.get("frames", []):
        time_s = float(frame.get("source_timestamp_us", 0)) / 1_000_000.0
        if start_s is not None and time_s + 1e-6 < start_s:
            continue
        if end_s is not None and time_s - 1e-6 > end_s:
            continue
        frame_idx = int(frame.get("frame_idx", max(0, round(time_s * 30.0))))
        for ann in frame.get("annotations", []):
            result.append(
                Annotation(
                    frame_idx=frame_idx,
                    time_s=time_s,
                    x=float(ann.get("x_norm", 0.0)),
                    y=float(ann.get("y_norm", 0.0)),
                    verdict=str(ann.get("verdict", "")),
                    review_kind=str(ann.get("review_kind", "")),
                    object_type=str(ann.get("object_type", "")),
                    scenario=str(ann.get("scenario") or ""),
                    note=str(ann.get("note", "")),
                    track_id=(str(ann.get("track_id")) if ann.get("track_id") is not None else None),
                )
            )
    return result


def load_detections(path: Path) -> list[Detection]:
    rows: list[Detection] = []
    with path.open(newline="") as handle:
        filtered = (line for line in handle if not line.startswith("#"))
        reader = csv.DictReader(filtered)
        for row in reader:
            rows.append(
                Detection(
                    frame=int(row["frame"]),
                    time_s=float(row["time_s"]),
                    algorithm=row["algorithm"],
                    cx=float(row["cx_norm"]),
                    cy=float(row["cy_norm"]),
                    w=float(row["box_w_norm"]),
                    h=float(row["box_h_norm"]),
                    weight=float(row["weight"]),
                )
            )
    return rows


def nearby_detections(
    annotation: Annotation,
    detections: Iterable[Detection],
    time_window_s: float,
) -> list[Detection]:
    return [
        d for d in detections if abs(d.time_s - annotation.time_s) <= time_window_s
    ]


def best_detection(annotation: Annotation, detections: Iterable[Detection]) -> tuple[Detection | None, float | None, bool]:
    candidates = list(detections)
    if not candidates:
        return None, None, False
    containing = [d for d in candidates if d.contains(annotation.x, annotation.y)]
    pool = containing if containing else candidates
    best = min(
        pool,
        key=lambda d: (d.center_distance(annotation.x, annotation.y), abs(d.time_s - annotation.time_s)),
    )
    return best, best.center_distance(annotation.x, annotation.y), best in containing


def build_positive_tracks(
    annotations: list[Annotation],
    track_gap_s: float,
    track_join_radius: float,
) -> list[Track]:
    positives = [
        ann for ann in annotations
        if ann.review_kind in POSITIVE_KINDS
    ]
    positives.sort(key=lambda ann: (ann.time_s, ann.y, ann.x))
    if not positives:
        return []

    if any(ann.track_id for ann in positives):
        grouped: dict[str, list[Annotation]] = {}
        for ann in positives:
            key = ann.track_id or f"implicit:{ann.object_type}:{ann.scenario}:{ann.time_s:.3f}:{ann.x:.3f}:{ann.y:.3f}"
            grouped.setdefault(key, []).append(ann)
        return [Track(track_id=key, annotations=sorted(items, key=lambda ann: ann.time_s)) for key, items in sorted(grouped.items())]

    tracks: list[Track] = []
    active: list[Track] = []
    next_track_id = 1
    for ann in positives:
        best_track: Track | None = None
        best_dist: float | None = None
        for track in active:
            prev = track.annotations[-1]
            if ann.time_s - prev.time_s > track_gap_s:
                continue
            if prev.object_type != ann.object_type:
                continue
            dist = math.hypot(prev.x - ann.x, prev.y - ann.y)
            if dist > track_join_radius:
                continue
            if best_dist is None or dist < best_dist:
                best_dist = dist
                best_track = track
        if best_track is None:
            track = Track(track_id=f"track_{next_track_id:02d}", annotations=[ann])
            next_track_id += 1
            tracks.append(track)
            active.append(track)
        else:
            best_track.annotations.append(ann)
        active = [track for track in active if ann.time_s - track.annotations[-1].time_s <= track_gap_s]
    return tracks


def quantile(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    if len(values) == 1:
        return values[0]
    values = sorted(values)
    index = (len(values) - 1) * percentile
    low = int(math.floor(index))
    high = int(math.ceil(index))
    if low == high:
        return values[low]
    fraction = index - low
    return values[low] * (1.0 - fraction) + values[high] * fraction


def max_consecutive_frame_streak(frames: Iterable[int]) -> int:
    best = 0
    current = 0
    previous: int | None = None
    for frame in sorted(set(frames)):
        if previous is not None and frame == previous + 1:
            current += 1
        else:
            current = 1
        best = max(best, current)
        previous = frame
    return best


def detection_matches_positive_annotation(
    detection: Detection,
    annotations: Iterable[Annotation],
    time_window_s: float,
) -> bool:
    for annotation in annotations:
        if annotation.review_kind not in POSITIVE_KINDS:
            continue
        if abs(detection.time_s - annotation.time_s) > time_window_s:
            continue
        if detection.contains(annotation.x, annotation.y):
            return True
    return False


def summarize_detection_pressure(
    detections: list[Detection],
    annotations: list[Annotation],
    time_window_s: float,
) -> dict[str, float | int | None]:
    areas = [max(0.0, detection.w) * max(0.0, detection.h) for detection in detections]
    box_frames = [detection.frame for detection in detections]
    off_target = [
        detection for detection in detections
        if not detection_matches_positive_annotation(detection, annotations, time_window_s)
    ]
    off_target_frames = [detection.frame for detection in off_target]

    return {
        "box_frame_count": len(set(box_frames)),
        "box_event_count": len(detections),
        "box_area_sum_norm": sum(areas),
        "box_area_mean_norm": (statistics.fmean(areas) if areas else None),
        "box_area_p50_norm": quantile(areas, 0.50),
        "box_area_p90_norm": quantile(areas, 0.90),
        "box_area_max_norm": (max(areas) if areas else None),
        "max_box_frame_streak": max_consecutive_frame_streak(box_frames),
        "off_target_box_event_count": len(off_target),
        "off_target_box_frame_count": len(set(off_target_frames)),
        "max_off_target_box_frame_streak": max_consecutive_frame_streak(off_target_frames),
    }


def format_optional_float(value: object, digits: int = 4) -> str:
    if isinstance(value, (int, float)):
        return f"{float(value):.{digits}f}"
    return "n/a"


def score_review(
    annotations: list[Annotation],
    detections: list[Detection],
    time_window_s: float,
    track_gap_s: float,
    track_join_radius: float,
) -> dict:
    positive_total = 0
    negative_total = 0
    true_positive_annotations = 0
    false_positive_annotations = 0
    missed_annotations = 0
    near_miss_annotations = 0
    avg_hit_distance: list[float] = []
    miss_distances: list[float] = []
    false_positive_distances: list[float] = []
    by_kind: dict[str, dict[str, float]] = {}
    detailed_rows: list[dict] = []

    for idx, ann in enumerate(annotations, start=1):
        kind_stats = by_kind.setdefault(
            ann.review_kind or "unclassified",
            {"total": 0.0, "matched": 0.0, "inside": 0.0, "dist_sum": 0.0},
        )
        kind_stats["total"] += 1.0
        nearby = nearby_detections(ann, detections, time_window_s)
        best, dist, inside = best_detection(ann, nearby)
        if ann.review_kind in POSITIVE_KINDS:
            positive_total += 1
            if inside:
                true_positive_annotations += 1
                if dist is not None:
                    avg_hit_distance.append(dist)
                    kind_stats["dist_sum"] += dist
                kind_stats["matched"] += 1.0
                kind_stats["inside"] += 1.0
                outcome = "true_positive"
            else:
                missed_annotations += 1
                if best is not None:
                    near_miss_annotations += 1
                    if dist is not None:
                        miss_distances.append(dist)
                outcome = "miss"
        elif ann.review_kind in NEGATIVE_KINDS:
            negative_total += 1
            if inside:
                false_positive_annotations += 1
                if dist is not None:
                    false_positive_distances.append(dist)
                    kind_stats["dist_sum"] += dist
                kind_stats["matched"] += 1.0
                kind_stats["inside"] += 1.0
                outcome = "false_positive"
            else:
                outcome = "clean_negative"
        else:
            outcome = "ignored"
            if best is not None and dist is not None:
                kind_stats["matched"] += 1.0
                kind_stats["dist_sum"] += dist
                if inside:
                    kind_stats["inside"] += 1.0

        detailed_rows.append(
            {
                "annotation_index": idx,
                "time_s": ann.time_s,
                "x_norm": ann.x,
                "y_norm": ann.y,
                "review_kind": ann.review_kind,
                "object_type": ann.object_type,
                "scenario": ann.scenario,
                "outcome": outcome,
                "matched_detection": None if best is None else {
                    "time_s": best.time_s,
                    "algorithm": best.algorithm,
                    "cx_norm": best.cx,
                    "cy_norm": best.cy,
                    "weight": best.weight,
                    "contains_point": inside,
                    "distance": dist,
                },
            }
        )

    tracks = build_positive_tracks(annotations, track_gap_s=track_gap_s, track_join_radius=track_join_radius)
    track_latencies: list[float] = []
    track_first_hit_times: list[float] = []
    matched_tracks = 0
    for track in tracks:
        first_ann = track.annotations[0]
        hit_time: float | None = None
        for ann in track.annotations:
            nearby = nearby_detections(ann, detections, time_window_s)
            best, _dist, inside = best_detection(ann, nearby)
            if inside and best is not None:
                hit_time = best.time_s
                break
        if hit_time is not None:
            matched_tracks += 1
            track_first_hit_times.append(hit_time)
            track_latencies.append(max(0.0, hit_time - first_ann.time_s))

    precision = (
        true_positive_annotations / (true_positive_annotations + false_positive_annotations)
        if (true_positive_annotations + false_positive_annotations) > 0
        else None
    )
    recall = (
        true_positive_annotations / positive_total
        if positive_total > 0
        else None
    )
    return {
        "annotation_count": len(annotations),
        "positive_annotation_count": positive_total,
        "negative_annotation_count": negative_total,
        "true_positive_annotations": true_positive_annotations,
        "false_positive_annotations": false_positive_annotations,
        "missed_annotations": missed_annotations,
        "near_miss_annotations": near_miss_annotations,
        "clean_negative_annotations": negative_total - false_positive_annotations,
        "reviewed_precision": precision,
        "reviewed_recall": recall,
        "avg_hit_distance": (statistics.fmean(avg_hit_distance) if avg_hit_distance else None),
        "hit_distance_p50_norm": quantile(avg_hit_distance, 0.50),
        "hit_distance_p90_norm": quantile(avg_hit_distance, 0.90),
        "hit_distance_max_norm": (max(avg_hit_distance) if avg_hit_distance else None),
        "hit_distance_p50_px_1080p": (
            quantile(avg_hit_distance, 0.50) * 1080.0 if avg_hit_distance else None
        ),
        "hit_distance_p90_px_1080p": (
            quantile(avg_hit_distance, 0.90) * 1080.0 if avg_hit_distance else None
        ),
        "hit_distance_max_px_1080p": (
            max(avg_hit_distance) * 1080.0 if avg_hit_distance else None
        ),
        "miss_distance_min_norm": (min(miss_distances) if miss_distances else None),
        "miss_distance_p50_norm": quantile(miss_distances, 0.50),
        "miss_distance_p90_norm": quantile(miss_distances, 0.90),
        "miss_distance_max_norm": (max(miss_distances) if miss_distances else None),
        "miss_distance_p50_px_1080p": (
            quantile(miss_distances, 0.50) * 1080.0 if miss_distances else None
        ),
        "false_positive_distance_p50_norm": quantile(false_positive_distances, 0.50),
        "false_positive_distance_max_norm": (
            max(false_positive_distances) if false_positive_distances else None
        ),
        "positive_tracks": len(tracks),
        "matched_tracks": matched_tracks,
        "missed_tracks": len(tracks) - matched_tracks,
        "first_hit_time_s_min": (min(track_first_hit_times) if track_first_hit_times else None),
        "first_hit_time_s_avg": (statistics.fmean(track_first_hit_times) if track_first_hit_times else None),
        "first_hit_time_s_p95": quantile(track_first_hit_times, 0.95),
        "first_hit_time_s_max": (max(track_first_hit_times) if track_first_hit_times else None),
        "first_hit_latency_s_min": (min(track_latencies) if track_latencies else None),
        "latency_to_first_box_avg_s": (statistics.fmean(track_latencies) if track_latencies else None),
        "latency_to_first_box_p95_s": quantile(track_latencies, 0.95),
        "latency_to_first_box_max_s": (max(track_latencies) if track_latencies else None),
        "detection_pressure": summarize_detection_pressure(detections, annotations, time_window_s),
        "by_review_kind": by_kind,
        "details": detailed_rows,
    }


def summarize(
    review_path: Path,
    csv_paths: list[Path],
    time_window_s: float,
    start_s: float | None = None,
    end_s: float | None = None,
    track_gap_s: float = 0.35,
    track_join_radius: float = 0.08,
) -> tuple[str, dict]:
    annotations = load_review(review_path, start_s=start_s, end_s=end_s)
    lines: list[str] = []
    lines.append(f"Review: {review_path}")
    lines.append(f"Annotated points: {len(annotations)}")
    result_json: dict[str, object] = {
        "review_path": str(review_path),
        "time_window_s": time_window_s,
        "annotation_count": len(annotations),
        "start_s": start_s,
        "end_s": end_s,
        "csv_results": [],
    }
    for csv_path in csv_paths:
        detections = load_detections(csv_path)
        score = score_review(
            annotations,
            detections,
            time_window_s=time_window_s,
            track_gap_s=track_gap_s,
            track_join_radius=track_join_radius,
        )
        lines.append("")
        lines.append(f"Detections: {csv_path.name}")
        lines.append(f"Rows: {len(detections)}")
        for row in score["details"]:
            idx = row["annotation_index"]
            outcome = row["outcome"]
            match = row["matched_detection"]
            ann = annotations[idx - 1]
            kind = ann.review_kind or "unclassified"
            if match is None:
                lines.append(
                    f"  ann#{idx} t={ann.time_s:.3f}s {ann.verdict}/{kind}/{ann.object_type} "
                    f"xy=({ann.x:.3f},{ann.y:.3f}) -> no detection in ±{time_window_s:.3f}s"
                )
                continue
            lines.append(
                f"  ann#{idx} t={ann.time_s:.3f}s {ann.verdict}/{kind}/{ann.object_type} "
                f"xy=({ann.x:.3f},{ann.y:.3f}) -> {match['algorithm']} "
                f"t={match['time_s']:.3f}s c=({match['cx_norm']:.3f},{match['cy_norm']:.3f}) "
                f"d={match['distance']:.3f} inside={'Y' if match['contains_point'] else 'N'} "
                f"w={match['weight']:.2f} [{outcome}]"
            )
            if ann.note:
                lines.append(f"    note: {ann.note}")
        summary_line = (
            "  Summary: "
            f"TP {score['true_positive_annotations']}/{score['positive_annotation_count']}, "
            f"FP {score['false_positive_annotations']}/{score['negative_annotation_count']}, "
            f"misses {score['missed_annotations']}"
        )
        if score["reviewed_precision"] is not None:
            summary_line += f", precision {score['reviewed_precision']:.3f}"
        if score["reviewed_recall"] is not None:
            summary_line += f", recall {score['reviewed_recall']:.3f}"
        lines.append(summary_line)
        if score["latency_to_first_box_avg_s"] is not None:
            lines.append(
                "  Tracks: "
                f"{score['matched_tracks']}/{score['positive_tracks']} matched, "
                f"first-hit {score['first_hit_time_s_min']:.3f}s, "
                f"latency avg {score['latency_to_first_box_avg_s']:.3f}s, "
                f"p95 {score['latency_to_first_box_p95_s']:.3f}s, "
                f"max {score['latency_to_first_box_max_s']:.3f}s"
            )
        if score["hit_distance_p50_norm"] is not None:
            lines.append(
                "  Hit distance: "
                f"p50 {score['hit_distance_p50_norm']:.4f} "
                f"p90 {score['hit_distance_p90_norm']:.4f} "
                f"max {score['hit_distance_max_norm']:.4f} norm "
                f"(p90 {score['hit_distance_p90_px_1080p']:.1f}px @1080p)"
            )
        if score["miss_distance_p50_norm"] is not None:
            lines.append(
                "  Miss nearest-distance: "
                f"min {score['miss_distance_min_norm']:.4f} "
                f"p50 {score['miss_distance_p50_norm']:.4f} "
                f"p90 {score['miss_distance_p90_norm']:.4f} "
                f"max {score['miss_distance_max_norm']:.4f} norm "
                f"(p50 {score['miss_distance_p50_px_1080p']:.1f}px @1080p)"
            )
        if score["false_positive_distance_p50_norm"] is not None:
            lines.append(
                "  Reviewed FP distance: "
                f"p50 {score['false_positive_distance_p50_norm']:.4f} "
                f"max {score['false_positive_distance_max_norm']:.4f} norm"
            )
        pressure = score["detection_pressure"]
        lines.append(
            "  Box pressure: "
            f"frames {pressure['box_frame_count']}, "
            f"events {pressure['box_event_count']}, "
            f"area p50/p90/max {format_optional_float(pressure['box_area_p50_norm'])}/"
            f"{format_optional_float(pressure['box_area_p90_norm'])}/"
            f"{format_optional_float(pressure['box_area_max_norm'])}, "
            f"streak {pressure['max_box_frame_streak']}, "
            f"off-target events {pressure['off_target_box_event_count']}, "
            f"off-target streak {pressure['max_off_target_box_frame_streak']}"
        )
        if score["by_review_kind"]:
            lines.append("  By review_kind:")
            for kind, stats in sorted(score["by_review_kind"].items()):
                total = int(stats["total"])
                matched = int(stats["matched"])
                inside = int(stats["inside"])
                avg_kind_dist = (stats["dist_sum"] / stats["matched"]) if stats["matched"] > 0.0 else None
                if avg_kind_dist is None:
                    lines.append(
                        f"    {kind}: matched {matched}/{total}, inside-box {inside}/{total}"
                    )
                else:
                    lines.append(
                        f"    {kind}: matched {matched}/{total}, inside-box {inside}/{total}, "
                        f"avg-center-dist {avg_kind_dist:.3f}"
                    )
        result_json["csv_results"].append(
            {
                "csv_path": str(csv_path),
                "detection_count": len(detections),
                "score": score,
            }
        )
    return "\n".join(lines), result_json


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("review_json", type=Path)
    parser.add_argument("detection_csv", type=Path, nargs="+")
    parser.add_argument("--time-window", type=float, default=0.10, help="Match detections within +/- this many seconds")
    parser.add_argument("--start-s", type=float, default=None, help="Only score annotations at or after this source time")
    parser.add_argument("--end-s", type=float, default=None, help="Only score annotations at or before this source time")
    parser.add_argument("--track-gap", type=float, default=0.35, help="Max gap between positive annotations in the same inferred track")
    parser.add_argument("--track-join-radius", type=float, default=0.08, help="Max normalized point distance for inferred positive tracks")
    parser.add_argument("--json", action="store_true", help="Emit JSON instead of a text report")
    args = parser.parse_args()
    text, payload = summarize(
        args.review_json,
        args.detection_csv,
        args.time_window,
        start_s=args.start_s,
        end_s=args.end_s,
        track_gap_s=args.track_gap,
        track_join_radius=args.track_join_radius,
    )
    if args.json:
        print(json.dumps(payload, indent=2))
    else:
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
