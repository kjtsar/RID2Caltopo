#!/usr/bin/env python3
"""Compare shadow micro-candidate telemetry with review annotations.

This is a read-only analysis helper. It expects thermal JSONL records from
anomaly_video_test after a detector build adds shadow micro-candidate telemetry.
The script is intentionally tolerant of field names so it can be used while the
shadow schema is still settling.
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

from review_eval import NEGATIVE_KINDS, POSITIVE_KINDS, Annotation, load_review


FEATURE_ALIASES = {
    "prominence": ("prominence", "local_evidence_prominence", "micro_prominence", "peak_ring_prominence"),
    "ring_hot_fraction": ("ring_hot_fraction", "local_evidence_ring_hot_fraction", "ring_fraction", "ring_hot"),
    "ring_ratio": ("ring_ratio", "local_evidence_ring_ratio", "ring_mean_ratio"),
    "compactness": ("compactness", "local_evidence_compactness", "core_share", "center_share"),
    "hot_count": ("hot_count", "local_evidence_hot_count", "local_window_hot_count"),
    "centroid_offset": ("centroid_offset", "local_evidence_centroid_offset", "raw_centroid_offset"),
    "one_sided_support": ("one_sided_support", "local_evidence_one_sided_support", "anisotropy", "edge_score"),
    "distance_to_track": ("distance_to_track", "nearest_track_distance", "local_evidence_distance_to_track"),
    "peak_delta": ("peak_delta", "local_evidence_peak_delta", "local_peak_raw_delta"),
    "mean_delta": ("mean_delta", "local_evidence_mean_delta", "local_window_raw_delta_mean"),
    "area": ("area", "local_evidence_area"),
    "span": ("span", "local_evidence_span"),
}

CANDIDATE_KEYS = (
    "shadow_micro_candidates",
    "micro_candidate_shadows",
    "micro_candidates",
    "local_evidence_candidates",
)


@dataclass
class ShadowCandidate:
    source: Path
    frame: int
    time_s: float
    index: int
    x: float
    y: float
    left: float | None
    top: float | None
    right: float | None
    bottom: float | None
    features: dict[str, float]
    raw: dict[str, Any]

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
        return float(value)
    except (TypeError, ValueError):
        return default


def boolish(value: Any) -> bool:
    return value is True or str(value).lower() in {"1", "true", "yes", "y"}


def first_num(mapping: dict[str, Any], names: Iterable[str]) -> float | None:
    for name in names:
        value = num(mapping.get(name))
        if value is not None:
            return value
    return None


def feature_value(candidate: dict[str, Any], canonical: str) -> float | None:
    value = first_num(candidate, FEATURE_ALIASES[canonical])
    if value is not None:
        return value
    if canonical == "centroid_offset":
        dx = first_num(candidate, ("centroid_dx", "local_evidence_centroid_dx", "raw_centroid_dx"))
        dy = first_num(candidate, ("centroid_dy", "local_evidence_centroid_dy", "raw_centroid_dy"))
        if dx is not None and dy is not None:
            return math.hypot(dx, dy)
    if canonical == "ring_ratio":
        ring = first_num(candidate, ("ring_mean_delta", "local_evidence_ring_mean_delta", "ring_mean"))
        peak = first_num(candidate, ("peak_delta", "local_evidence_peak_delta", "local_peak_raw_delta"))
        if ring is not None and peak is not None and abs(peak) > 1e-6:
            return ring / peak
    if canonical == "prominence":
        peak = first_num(candidate, ("peak_delta", "local_evidence_peak_delta", "local_peak_raw_delta"))
        ring = first_num(candidate, ("ring_mean_delta", "local_evidence_ring_mean_delta", "ring_mean"))
        if peak is not None and ring is not None:
            return peak - ring
    return None


def candidate_xy(candidate: dict[str, Any], target: dict[str, Any]) -> tuple[float | None, float | None]:
    x = first_num(candidate, ("x_norm", "cx_norm", "local_evidence_x_norm", "bbox_center_x_norm"))
    y = first_num(candidate, ("y_norm", "cy_norm", "local_evidence_y_norm", "bbox_center_y_norm"))
    if x is not None and y is not None:
        return x, y

    sx = first_num(candidate, ("sample_x", "peak_sample_x", "local_peak_raw_sample_x"))
    sy = first_num(candidate, ("sample_y", "peak_sample_y", "local_peak_raw_sample_y"))
    tsx = first_num(target, ("sample_x",))
    tsy = first_num(target, ("sample_y",))
    tx = first_num(target, ("x_norm",))
    ty = first_num(target, ("y_norm",))
    if None not in (sx, sy, tsx, tsy, tx, ty):
        # Fallback for target-trace telemetry: estimate candidate position from
        # sample offset. Exact sample pitch is not exported, so use this only as
        # a last resort for rough matching.
        return tx + (sx - tsx) / 176.0, ty + (sy - tsy) / 96.0
    return None, None


def candidate_bbox(candidate: dict[str, Any], x: float, y: float) -> tuple[float | None, float | None, float | None, float | None]:
    left = first_num(candidate, ("bbox_left_norm", "left_norm"))
    top = first_num(candidate, ("bbox_top_norm", "top_norm"))
    right = first_num(candidate, ("bbox_right_norm", "right_norm"))
    bottom = first_num(candidate, ("bbox_bottom_norm", "bottom_norm"))
    if None not in (left, top, right, bottom):
        return left, top, right, bottom
    width = first_num(candidate, ("box_w_norm", "w_norm", "width_norm"))
    height = first_num(candidate, ("box_h_norm", "h_norm", "height_norm"))
    if width is not None and height is not None:
        return x - width * 0.5, y - height * 0.5, x + width * 0.5, y + height * 0.5
    return None, None, None, None


def extract_shadow_candidates(record: dict[str, Any], source: Path) -> list[ShadowCandidate]:
    target = record.get("target") or {}
    raw_candidates: list[dict[str, Any]] = []
    for key in CANDIDATE_KEYS:
        values = record.get(key)
        if isinstance(values, list):
            raw_candidates.extend(item for item in values if isinstance(item, dict))

    if isinstance(target, dict) and boolish(target.get("micro_candidate_would_create")):
        raw_candidates.append(target)

    result: list[ShadowCandidate] = []
    for index, item in enumerate(raw_candidates):
        if not boolish(item.get("would_create", item.get("micro_candidate_would_create", True))):
            continue
        x, y = candidate_xy(item, target)
        if x is None or y is None:
            continue
        left, top, right, bottom = candidate_bbox(item, x, y)
        features: dict[str, float] = {}
        for canonical in FEATURE_ALIASES:
            value = feature_value(item, canonical)
            if value is not None and math.isfinite(value):
                features[canonical] = value
        result.append(
            ShadowCandidate(
                source=source,
                frame=int(num(record.get("frame"), -1) or -1),
                time_s=float(num(record.get("time_s"), -1.0) or -1.0),
                index=index,
                x=x,
                y=y,
                left=left,
                top=top,
                right=right,
                bottom=bottom,
                features=features,
                raw=item,
            )
        )
    return result


def load_shadow_jsonl(path: Path) -> list[ShadowCandidate]:
    candidates: list[ShadowCandidate] = []
    with path.open() as handle:
        for line in handle:
            if not line.strip():
                continue
            candidates.extend(extract_shadow_candidates(json.loads(line), path))
    return candidates


def annotations_near(candidate: ShadowCandidate, annotations: list[Annotation], time_window_s: float) -> list[Annotation]:
    return [ann for ann in annotations if abs(ann.time_s - candidate.time_s) <= time_window_s]


def classify_candidate(
    candidate: ShadowCandidate,
    annotations: list[Annotation],
    time_window_s: float,
    near_radius: float,
) -> tuple[str, Annotation | None, float | None, bool]:
    nearby = annotations_near(candidate, annotations, time_window_s)
    if not nearby:
        return "unreviewed", None, None, False

    negative_hits: list[tuple[Annotation, float, bool]] = []
    positive_hits: list[tuple[Annotation, float, bool]] = []
    near_positive: list[tuple[Annotation, float, bool]] = []
    for ann in nearby:
        dist = candidate.distance(ann)
        inside = candidate.contains(ann)
        if ann.review_kind in NEGATIVE_KINDS and (inside or dist <= near_radius):
            negative_hits.append((ann, dist, inside))
        elif ann.review_kind in POSITIVE_KINDS and (inside or dist <= near_radius):
            positive_hits.append((ann, dist, inside))
        elif ann.review_kind in POSITIVE_KINDS:
            near_positive.append((ann, dist, inside))

    if negative_hits:
        ann, dist, inside = min(negative_hits, key=lambda item: item[1])
        return "negative_match", ann, dist, inside
    if positive_hits:
        ann, dist, inside = min(positive_hits, key=lambda item: item[1])
        return "positive_match", ann, dist, inside
    if near_positive:
        ann, dist, inside = min(near_positive, key=lambda item: item[1])
        return "positive_time_only", ann, dist, inside
    return "reviewed_time_unmatched", None, None, False


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
    frac = index - low
    return values[low] * (1.0 - frac) + values[high] * frac


def summarize_feature(values: list[float]) -> dict[str, float | int | None]:
    return {
        "n": len(values),
        "min": min(values) if values else None,
        "p10": quantile(values, 0.10),
        "median": quantile(values, 0.50),
        "p90": quantile(values, 0.90),
        "max": max(values) if values else None,
        "mean": statistics.fmean(values) if values else None,
    }


def threshold_suggestions(rows: list[dict[str, Any]], min_positive_keep: float) -> list[dict[str, Any]]:
    positives = [row for row in rows if row["label"] == "positive_match"]
    negatives = [row for row in rows if row["label"] == "negative_match"]
    suggestions: list[dict[str, Any]] = []
    if not positives or not negatives:
        return suggestions

    directions = {
        "prominence": "min",
        "compactness": "min",
        "peak_delta": "min",
        "hot_count": "max",
        "centroid_offset": "max",
        "ring_hot_fraction": "max",
        "ring_ratio": "max",
        "one_sided_support": "max",
        "distance_to_track": "max",
        "area": "max",
        "span": "max",
    }
    for feature, direction in directions.items():
        pos_values = [row["features"][feature] for row in positives if feature in row["features"]]
        neg_values = [row["features"][feature] for row in negatives if feature in row["features"]]
        if len(pos_values) < 3 or not neg_values:
            continue
        candidates = sorted(set(pos_values + neg_values))
        best = None
        for threshold in candidates:
            if direction == "min":
                pos_keep = sum(value >= threshold for value in pos_values)
                neg_keep = sum(value >= threshold for value in neg_values)
            else:
                pos_keep = sum(value <= threshold for value in pos_values)
                neg_keep = sum(value <= threshold for value in neg_values)
            pos_rate = pos_keep / len(pos_values)
            if pos_rate + 1e-9 < min_positive_keep:
                continue
            neg_reject = 1.0 - (neg_keep / len(neg_values))
            score = (neg_reject, pos_rate, -abs(threshold))
            if best is None or score > best[0]:
                best = (score, threshold, pos_rate, neg_reject)
        if best is not None:
            _, threshold, pos_rate, neg_reject = best
            suggestions.append(
                {
                    "feature": feature,
                    "direction": direction,
                    "threshold": threshold,
                    "positive_keep_rate": pos_rate,
                    "negative_reject_rate": neg_reject,
                    "positive_count": len(pos_values),
                    "negative_count": len(neg_values),
                }
            )
    suggestions.sort(key=lambda item: (item["negative_reject_rate"], item["positive_keep_rate"]), reverse=True)
    return suggestions


def build_report(
    review_path: Path,
    jsonl_paths: list[Path],
    start_s: float | None,
    end_s: float | None,
    time_window_s: float,
    near_radius: float,
    min_positive_keep: float,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    annotations = load_review(review_path, start_s=start_s, end_s=end_s)
    candidates: list[ShadowCandidate] = []
    for path in jsonl_paths:
        candidates.extend(load_shadow_jsonl(path))

    rows: list[dict[str, Any]] = []
    for candidate in candidates:
        if start_s is not None and candidate.time_s + 1e-6 < start_s:
            continue
        if end_s is not None and candidate.time_s - 1e-6 > end_s:
            continue
        label, ann, distance, inside = classify_candidate(candidate, annotations, time_window_s, near_radius)
        rows.append(
            {
                "source": str(candidate.source),
                "frame": candidate.frame,
                "time_s": candidate.time_s,
                "index": candidate.index,
                "x_norm": candidate.x,
                "y_norm": candidate.y,
                "label": label,
                "annotation_time_s": None if ann is None else ann.time_s,
                "annotation_x_norm": None if ann is None else ann.x,
                "annotation_y_norm": None if ann is None else ann.y,
                "review_kind": None if ann is None else ann.review_kind,
                "object_type": None if ann is None else ann.object_type,
                "scenario": None if ann is None else ann.scenario,
                "distance": distance,
                "inside": inside,
                "features": candidate.features,
            }
        )

    labels = sorted({row["label"] for row in rows})
    feature_summary: dict[str, dict[str, dict[str, float | int | None]]] = {}
    for label in labels:
        group = [row for row in rows if row["label"] == label]
        feature_summary[label] = {}
        for feature in FEATURE_ALIASES:
            values = [row["features"][feature] for row in group if feature in row["features"]]
            if values:
                feature_summary[label][feature] = summarize_feature(values)

    report = {
        "review_path": str(review_path),
        "jsonl_paths": [str(path) for path in jsonl_paths],
        "start_s": start_s,
        "end_s": end_s,
        "time_window_s": time_window_s,
        "near_radius": near_radius,
        "annotation_count": len(annotations),
        "candidate_count": len(rows),
        "label_counts": {label: sum(1 for row in rows if row["label"] == label) for label in labels},
        "feature_summary": feature_summary,
        "threshold_suggestions": threshold_suggestions(rows, min_positive_keep),
    }
    return report, rows


def print_text_report(report: dict[str, Any]) -> None:
    print(f"review: {report['review_path']}")
    print(f"candidates: {report['candidate_count']}")
    print("label_counts:")
    for label, count in sorted(report["label_counts"].items()):
        print(f"  {label}: {count}")
    print("feature_summary:")
    for label, features in sorted(report["feature_summary"].items()):
        print(f"  {label}:")
        for feature, stats in sorted(features.items()):
            print(
                f"    {feature}: n={stats['n']} "
                f"p10={fmt(stats['p10'])} med={fmt(stats['median'])} "
                f"p90={fmt(stats['p90'])} mean={fmt(stats['mean'])}"
            )
    print("threshold_suggestions:")
    for item in report["threshold_suggestions"][:12]:
        op = ">=" if item["direction"] == "min" else "<="
        print(
            f"  {item['feature']} {op} {item['threshold']:.6g}: "
            f"keep_pos={item['positive_keep_rate']:.2f} "
            f"reject_neg={item['negative_reject_rate']:.2f} "
            f"(n_pos={item['positive_count']}, n_neg={item['negative_count']})"
        )


def fmt(value: Any) -> str:
    return "n/a" if value is None else f"{float(value):.4g}"


def write_rows_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    features = sorted({feature for row in rows for feature in row["features"]})
    base_fields = [
        "source",
        "frame",
        "time_s",
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
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=base_fields + features)
        writer.writeheader()
        for row in rows:
            flat = {key: row.get(key) for key in base_fields}
            flat.update(row["features"])
            writer.writerow(flat)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("review_json", type=Path)
    parser.add_argument("thermal_jsonl", type=Path, nargs="+")
    parser.add_argument("--start-s", type=float, default=None)
    parser.add_argument("--end-s", type=float, default=None)
    parser.add_argument("--time-window", type=float, default=0.10)
    parser.add_argument(
        "--near-radius",
        type=float,
        default=0.025,
        help="Normalized center-distance fallback for candidates without useful boxes.",
    )
    parser.add_argument("--min-positive-keep", type=float, default=0.80)
    parser.add_argument("--rows-csv", type=Path, default=None)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    report, rows = build_report(
        review_path=args.review_json,
        jsonl_paths=args.thermal_jsonl,
        start_s=args.start_s,
        end_s=args.end_s,
        time_window_s=args.time_window,
        near_radius=args.near_radius,
        min_positive_keep=args.min_positive_keep,
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
