#!/usr/bin/env python3
"""Compare movement shadow telemetry with review annotations.

This is a read-only analysis helper for thermal debug JSONL emitted by
anomaly_video_test. It understands both candidate rows and --thermal-target
rows, then optionally correlates them with a detection CSV to estimate nearby
motion persistence and centroid drift.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

from review_eval import NEGATIVE_KINDS, POSITIVE_KINDS, Annotation, Detection, load_detections, load_review


NUMERIC_FIELDS = (
    "final_score",
    "temporal_score",
    "base_score",
    "peak_delta",
    "mean_delta",
    "area",
    "span",
    "center_share",
    "quality",
    "motion_support",
    "movement_residual_px",
    "movement_independent_score",
    "movement_confidence",
    "movement_motion_support",
    "local_peak_movement_residual_px",
    "local_peak_movement_independent_score",
    "local_peak_movement_confidence",
    "local_peak_movement_motion_support",
    "target_score",
    "target_raw_delta",
    "target_raw_score",
    "target_temporal_margin",
    "target_spatial_score",
    "local_peak_score",
    "local_peak_delta",
    "local_peak_distance",
    "local_peak_raw_delta",
    "local_peak_raw_score",
    "local_peak_raw_distance",
    "local_peak_raw_temporal_margin",
    "local_peak_raw_spatial_score",
    "local_window_hot_count",
    "local_window_raw_delta_mean",
    "micro_candidate_prominence",
    "micro_candidate_ring_mean",
    "micro_candidate_ring_hot_fraction",
    "micro_candidate_hot_count",
    "micro_candidate_compactness",
    "micro_candidate_centroid_offset",
    "micro_candidate_one_sided_support",
    "pre_cap_rank",
    "provisional_final_score",
    "raw_delta_rescue_score",
    "matched_track_hit_count",
)

BOOLEAN_FIELDS = (
    "movement_tile_valid",
    "movement_independent",
    "movement_parallax",
    "would_promote_movement_rescue",
    "local_peak_movement_tile_valid",
    "local_peak_movement_independent",
    "local_peak_movement_parallax",
    "movement_shadow_thermal_support",
    "movement_shadow_clutter_veto",
    "movement_shadow_motion_support",
    "movement_shadow_parallax_penalty",
    "movement_rescue_would_publish",
    "movement_boost_would_publish",
    "micro_candidate_would_create",
    "hot_eligible",
    "local_peak_is_component_seed",
    "provisional_score_eligible",
    "raw_delta_rescue_eligible",
)


@dataclass
class MovementRow:
    source: Path
    frame: int
    time_s: float
    source_kind: str
    index: int
    x: float
    y: float
    left: float | None
    top: float | None
    right: float | None
    bottom: float | None
    fields: dict[str, Any]

    def contains(self, ann: Annotation) -> bool:
        if None in (self.left, self.top, self.right, self.bottom):
            return False
        assert self.left is not None and self.top is not None
        assert self.right is not None and self.bottom is not None
        return self.left <= ann.x <= self.right and self.top <= ann.y <= self.bottom

    def distance(self, ann: Annotation) -> float:
        return math.hypot(self.x - ann.x, self.y - ann.y)


def num(value: Any, default: float | None = None) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return default
    return result if math.isfinite(result) else default


def boolish(value: Any) -> bool:
    return value is True or str(value).lower() in {"1", "true", "yes", "y"}


def first_num(mapping: dict[str, Any], names: Iterable[str]) -> float | None:
    for name in names:
        value = num(mapping.get(name))
        if value is not None:
            return value
    return None


def normalized_xy(item: dict[str, Any], record: dict[str, Any]) -> tuple[float | None, float | None]:
    x = first_num(item, ("x_norm", "cx_norm", "raw_x_norm"))
    y = first_num(item, ("y_norm", "cy_norm", "raw_y_norm"))
    if x is not None and y is not None:
        return x, y

    target = record.get("target") if isinstance(record.get("target"), dict) else {}
    sx = first_num(item, ("local_peak_raw_sample_x", "local_peak_sample_x", "sample_x"))
    sy = first_num(item, ("local_peak_raw_sample_y", "local_peak_sample_y", "sample_y"))
    tsx = first_num(target, ("sample_x",))
    tsy = first_num(target, ("sample_y",))
    tx = first_num(target, ("x_norm",))
    ty = first_num(target, ("y_norm",))
    if None not in (sx, sy, tsx, tsy, tx, ty):
        # Debug traces do not export sample pitch, so this is only a rough
        # fallback for row labelling when no normalized point is present.
        return tx + (sx - tsx) / 176.0, ty + (sy - tsy) / 96.0
    return None, None


def normalized_bbox(item: dict[str, Any], x: float, y: float) -> tuple[float | None, float | None, float | None, float | None]:
    left = first_num(item, ("bbox_left_norm", "left_norm"))
    top = first_num(item, ("bbox_top_norm", "top_norm"))
    right = first_num(item, ("bbox_right_norm", "right_norm"))
    bottom = first_num(item, ("bbox_bottom_norm", "bottom_norm"))
    if None not in (left, top, right, bottom):
        return left, top, right, bottom

    width = first_num(item, ("box_w_norm", "w_norm", "width_norm"))
    height = first_num(item, ("box_h_norm", "h_norm", "height_norm"))
    if width is not None and height is not None:
        return x - width * 0.5, y - height * 0.5, x + width * 0.5, y + height * 0.5
    return None, None, None, None


def collect_fields(item: dict[str, Any]) -> dict[str, Any]:
    fields: dict[str, Any] = {}
    for name in NUMERIC_FIELDS:
        value = num(item.get(name))
        if value is not None:
            fields[name] = value
    for name in BOOLEAN_FIELDS:
        if name in item:
            fields[name] = boolish(item.get(name))
    for name in ("movement_layer", "local_peak_movement_layer", "movement_rescue_reject_reason", "stage"):
        if name in item:
            fields[name] = str(item.get(name))
    return fields


def extract_rows(record: dict[str, Any], source: Path) -> list[MovementRow]:
    rows: list[MovementRow] = []
    frame = int(num(record.get("frame"), -1) or -1)
    time_s = float(num(record.get("time_s"), -1.0) or -1.0)

    candidates = record.get("candidates")
    if isinstance(candidates, list):
        for index, item in enumerate(candidates):
            if not isinstance(item, dict) or not boolish(item.get("valid", True)):
                continue
            x, y = normalized_xy(item, record)
            if x is None or y is None:
                continue
            left, top, right, bottom = normalized_bbox(item, x, y)
            rows.append(
                MovementRow(
                    source=source,
                    frame=frame,
                    time_s=time_s,
                    source_kind="candidate",
                    index=index,
                    x=x,
                    y=y,
                    left=left,
                    top=top,
                    right=right,
                    bottom=bottom,
                    fields=collect_fields(item),
                )
            )

    target = record.get("target")
    if isinstance(target, dict) and boolish(target.get("enabled", True)) and boolish(target.get("valid", True)):
        x, y = normalized_xy(target, record)
        if x is not None and y is not None:
            left, top, right, bottom = normalized_bbox(target, x, y)
            rows.append(
                MovementRow(
                    source=source,
                    frame=frame,
                    time_s=time_s,
                    source_kind="target",
                    index=-1,
                    x=x,
                    y=y,
                    left=left,
                    top=top,
                    right=right,
                    bottom=bottom,
                    fields=collect_fields(target),
                )
            )
    return rows


def load_jsonl_rows(path: Path) -> list[MovementRow]:
    rows: list[MovementRow] = []
    with path.open() as handle:
        for line in handle:
            if line.strip():
                rows.extend(extract_rows(json.loads(line), path))
    return rows


def nearby_annotations(row: MovementRow, annotations: list[Annotation], time_window_s: float) -> list[Annotation]:
    return [ann for ann in annotations if abs(ann.time_s - row.time_s) <= time_window_s]


def classify_row(
    row: MovementRow,
    annotations: list[Annotation],
    time_window_s: float,
    near_radius: float,
) -> tuple[str, Annotation | None, float | None, bool]:
    nearby = nearby_annotations(row, annotations, time_window_s)
    if not nearby:
        return "unreviewed", None, None, False

    negative_hits: list[tuple[Annotation, float, bool]] = []
    positive_hits: list[tuple[Annotation, float, bool]] = []
    positive_time_only: list[tuple[Annotation, float, bool]] = []
    for ann in nearby:
        dist = row.distance(ann)
        inside = row.contains(ann)
        if ann.review_kind in NEGATIVE_KINDS and (inside or dist <= near_radius):
            negative_hits.append((ann, dist, inside))
        elif ann.review_kind in POSITIVE_KINDS and (inside or dist <= near_radius):
            positive_hits.append((ann, dist, inside))
        elif ann.review_kind in POSITIVE_KINDS:
            positive_time_only.append((ann, dist, inside))

    if negative_hits:
        ann, dist, inside = min(negative_hits, key=lambda item: item[1])
        return "negative_match", ann, dist, inside
    if positive_hits:
        ann, dist, inside = min(positive_hits, key=lambda item: item[1])
        return "positive_match", ann, dist, inside
    if positive_time_only:
        ann, dist, inside = min(positive_time_only, key=lambda item: item[1])
        return "positive_time_only", ann, dist, inside
    return "reviewed_time_unmatched", None, None, False


def detection_distance(row: MovementRow, detection: Detection) -> float:
    return math.hypot(row.x - detection.cx, row.y - detection.cy)


def correlate_detections(
    row: MovementRow,
    detections: list[Detection],
    time_window_s: float,
    radius: float,
    algorithm: str | None,
) -> dict[str, float | int | None]:
    nearby = [
        det
        for det in detections
        if abs(det.time_s - row.time_s) <= time_window_s
        and (algorithm is None or det.algorithm == algorithm)
        and detection_distance(row, det) <= radius
    ]
    if not nearby:
        return {
            "nearby_detection_count": 0,
            "nearby_detection_frames": 0,
            "nearby_detection_time_span_s": None,
            "nearby_detection_centroid_drift": None,
            "nearest_detection_distance": None,
        }
    times = [det.time_s for det in nearby]
    frames = {det.frame for det in nearby}
    first = min(nearby, key=lambda det: det.time_s)
    last = max(nearby, key=lambda det: det.time_s)
    return {
        "nearby_detection_count": len(nearby),
        "nearby_detection_frames": len(frames),
        "nearby_detection_time_span_s": max(times) - min(times),
        "nearby_detection_centroid_drift": math.hypot(last.cx - first.cx, last.cy - first.cy),
        "nearest_detection_distance": min(detection_distance(row, det) for det in nearby),
    }


def quantile(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    values = sorted(values)
    if len(values) == 1:
        return values[0]
    index = (len(values) - 1) * percentile
    low = int(math.floor(index))
    high = int(math.ceil(index))
    if low == high:
        return values[low]
    fraction = index - low
    return values[low] * (1.0 - fraction) + values[high] * fraction


def summarize_numbers(values: list[float]) -> dict[str, float | int | None]:
    return {
        "n": len(values),
        "min": min(values) if values else None,
        "p10": quantile(values, 0.10),
        "median": quantile(values, 0.50),
        "p90": quantile(values, 0.90),
        "max": max(values) if values else None,
        "mean": statistics.fmean(values) if values else None,
    }


def summarize_booleans(rows: list[dict[str, Any]], field: str) -> dict[str, int]:
    return {
        "true": sum(1 for row in rows if row.get(field) is True),
        "false": sum(1 for row in rows if row.get(field) is False),
        "missing": sum(1 for row in rows if field not in row),
    }


def summarize_categories(rows: list[dict[str, Any]], field: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for row in rows:
        value = str(row.get(field, "missing"))
        counts[value] = counts.get(value, 0) + 1
    return dict(sorted(counts.items()))


def build_report(
    review_path: Path,
    jsonl_paths: list[Path],
    detection_csv: Path | None,
    start_s: float | None,
    end_s: float | None,
    source_kind: str,
    time_window_s: float,
    near_radius: float,
    detection_time_window_s: float,
    detection_radius: float,
    detection_algorithm: str | None,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    annotations = load_review(review_path, start_s=start_s, end_s=end_s)
    detections = load_detections(detection_csv) if detection_csv is not None else []

    movement_rows: list[MovementRow] = []
    for path in jsonl_paths:
        movement_rows.extend(load_jsonl_rows(path))

    rows: list[dict[str, Any]] = []
    for movement_row in movement_rows:
        if start_s is not None and movement_row.time_s + 1e-6 < start_s:
            continue
        if end_s is not None and movement_row.time_s - 1e-6 > end_s:
            continue
        if source_kind != "all" and movement_row.source_kind != source_kind:
            continue

        label, ann, distance, inside = classify_row(movement_row, annotations, time_window_s, near_radius)
        flat: dict[str, Any] = {
            "source": str(movement_row.source),
            "frame": movement_row.frame,
            "time_s": movement_row.time_s,
            "source_kind": movement_row.source_kind,
            "index": movement_row.index,
            "x_norm": movement_row.x,
            "y_norm": movement_row.y,
            "label": label,
            "annotation_time_s": None if ann is None else ann.time_s,
            "annotation_x_norm": None if ann is None else ann.x,
            "annotation_y_norm": None if ann is None else ann.y,
            "review_kind": None if ann is None else ann.review_kind,
            "object_type": None if ann is None else ann.object_type,
            "scenario": None if ann is None else ann.scenario,
            "distance": distance,
            "inside": inside,
        }
        flat.update(movement_row.fields)
        if detections:
            flat.update(
                correlate_detections(
                    movement_row,
                    detections,
                    detection_time_window_s,
                    detection_radius,
                    detection_algorithm,
                )
            )
        rows.append(flat)

    labels = sorted({row["label"] for row in rows})
    numeric_fields = sorted(
        {
            key
            for row in rows
            for key, value in row.items()
            if isinstance(value, (int, float)) and not isinstance(value, bool)
        }
        - {"frame", "index", "time_s", "x_norm", "y_norm", "annotation_time_s", "annotation_x_norm", "annotation_y_norm"}
    )
    bool_fields = sorted({key for row in rows for key, value in row.items() if isinstance(value, bool)})

    groups: dict[str, dict[str, Any]] = {}
    for label in labels:
        group = [row for row in rows if row["label"] == label]
        groups[label] = {
            "count": len(group),
            "numeric": {
                field: summarize_numbers([float(row[field]) for row in group if isinstance(row.get(field), (int, float)) and not isinstance(row.get(field), bool)])
                for field in numeric_fields
                if any(isinstance(row.get(field), (int, float)) and not isinstance(row.get(field), bool) for row in group)
            },
            "booleans": {field: summarize_booleans(group, field) for field in bool_fields},
            "movement_layer": summarize_categories(group, "movement_layer"),
            "local_peak_movement_layer": summarize_categories(group, "local_peak_movement_layer"),
            "movement_rescue_reject_reason": summarize_categories(group, "movement_rescue_reject_reason"),
            "source_kind": summarize_categories(group, "source_kind"),
        }

    report = {
        "review_path": str(review_path),
        "jsonl_paths": [str(path) for path in jsonl_paths],
        "detection_csv": None if detection_csv is None else str(detection_csv),
        "start_s": start_s,
        "end_s": end_s,
        "source_kind": source_kind,
        "time_window_s": time_window_s,
        "near_radius": near_radius,
        "detection_time_window_s": detection_time_window_s if detections else None,
        "detection_radius": detection_radius if detections else None,
        "detection_algorithm": detection_algorithm if detections else None,
        "annotation_count": len(annotations),
        "row_count": len(rows),
        "label_counts": {label: sum(1 for row in rows if row["label"] == label) for label in labels},
        "groups": groups,
    }
    return report, rows


def fmt(value: Any) -> str:
    return "n/a" if value is None else f"{float(value):.4g}"


def print_text_report(report: dict[str, Any]) -> None:
    print(f"review: {report['review_path']}")
    print(f"rows: {report['row_count']}  annotations: {report['annotation_count']}")
    print("label_counts:")
    for label, count in sorted(report["label_counts"].items()):
        print(f"  {label}: {count}")
    for label, group in sorted(report["groups"].items()):
        print(f"{label}:")
        print(f"  movement_layer: {group['movement_layer']}")
        print(f"  local_peak_movement_layer: {group['local_peak_movement_layer']}")
        print(f"  movement_rescue_reject_reason: {group['movement_rescue_reject_reason']}")
        for field in (
            "movement_rescue_would_publish",
            "movement_boost_would_publish",
            "movement_shadow_thermal_support",
            "movement_shadow_clutter_veto",
            "movement_independent",
            "movement_parallax",
            "local_peak_movement_independent",
            "local_peak_movement_parallax",
            "would_promote_movement_rescue",
        ):
            stats = group["booleans"].get(field)
            if stats:
                print(f"  {field}: {stats}")
        for field in (
            "movement_independent_score",
            "movement_confidence",
            "movement_residual_px",
            "local_peak_movement_independent_score",
            "local_peak_movement_confidence",
            "local_peak_movement_residual_px",
            "micro_candidate_ring_hot_fraction",
            "micro_candidate_one_sided_support",
            "local_window_raw_delta_mean",
            "nearby_detection_count",
            "nearby_detection_time_span_s",
            "nearby_detection_centroid_drift",
        ):
            stats = group["numeric"].get(field)
            if stats:
                print(
                    f"  {field}: n={stats['n']} "
                    f"p10={fmt(stats['p10'])} med={fmt(stats['median'])} "
                    f"p90={fmt(stats['p90'])} mean={fmt(stats['mean'])}"
                )


def write_rows_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    fields = [
        "source",
        "frame",
        "time_s",
        "source_kind",
        "index",
        "x_norm",
        "y_norm",
        "label",
        "annotation_time_s",
        "annotation_x_norm",
        "annotation_y_norm",
        "review_kind",
        "object_type",
        "scenario",
        "distance",
        "inside",
    ]
    extras = sorted({key for row in rows for key in row if key not in fields})
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields + extras)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("review_json", type=Path)
    parser.add_argument("thermal_jsonl", type=Path, nargs="+")
    parser.add_argument("--detections-csv", type=Path, default=None)
    parser.add_argument("--start-s", type=float, default=None)
    parser.add_argument("--end-s", type=float, default=None)
    parser.add_argument("--source-kind", choices=("all", "candidate", "target"), default="all")
    parser.add_argument("--time-window", type=float, default=0.10)
    parser.add_argument("--near-radius", type=float, default=0.030)
    parser.add_argument("--detection-time-window", type=float, default=0.50)
    parser.add_argument("--detection-radius", type=float, default=0.045)
    parser.add_argument("--detection-algorithm", default="motion")
    parser.add_argument("--rows-csv", type=Path, default=None)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    report, rows = build_report(
        review_path=args.review_json,
        jsonl_paths=args.thermal_jsonl,
        detection_csv=args.detections_csv,
        start_s=args.start_s,
        end_s=args.end_s,
        source_kind=args.source_kind,
        time_window_s=args.time_window,
        near_radius=args.near_radius,
        detection_time_window_s=args.detection_time_window,
        detection_radius=args.detection_radius,
        detection_algorithm=None if args.detection_algorithm == "all" else args.detection_algorithm,
    )
    if args.rows_csv is not None:
        write_rows_csv(args.rows_csv, rows)
    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print_text_report(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
