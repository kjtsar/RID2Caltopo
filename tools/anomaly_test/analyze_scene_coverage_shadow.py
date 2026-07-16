#!/usr/bin/env python3
"""Evaluate shadow scene-coverage masks against authoritative full-scan boxes."""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


COLS = 8
ROWS = 6
BLOCK_COUNT = COLS * ROWS
SCHEMA_VERSION = 1
DEFAULT_MAX_EXPLAINED_LATENCY_FRAMES = 60
DEFAULT_MAX_EXPLAINED_LATENCY_US = 2_000_000

REASON_FLAGS = {
    0x00000001: "invalid_input",
    0x00000002: "startup",
    0x00000004: "scene_discontinuity",
    0x00000008: "registration_hard",
    0x00000010: "lookup_missing",
    0x00000020: "grid_mismatch",
    0x00000040: "warp_low",
    0x00000080: "new_exposed_high",
    0x00000100: "registration_soft",
    0x00000200: "registration_gap",
    0x00000400: "movement_weak",
    0x00000800: "max_age",
    0x00001000: "timestamp_fallback",
    0x00002000: "mandatory_over_budget",
    0x00004000: "recovery_hysteresis",
    0x00008000: "target_revisit",
    0x00010000: "local_untrusted",
}
VALID_SHADOW_MODES = {"full_required", "locked_incremental", "recovery"}
VALID_RESCAN_MODES = {"full", "partial", "target_only", "appearance_stride_skip", "unset"}


class EvidenceError(ValueError):
    pass


def _finite_number(value: Any, field: str, line_number: int) -> float:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise EvidenceError(f"line {line_number}: {field} must be numeric")
    number = float(value)
    if not math.isfinite(number):
        raise EvidenceError(f"line {line_number}: {field} must be finite")
    return number


def _integer(value: Any, field: str, line_number: int) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        raise EvidenceError(f"line {line_number}: {field} must be an integer")
    return value


def _mask(value: Any, field: str, line_number: int) -> int:
    if not isinstance(value, str) or not value:
        raise EvidenceError(f"line {line_number}: {field} must be a hexadecimal string")
    try:
        parsed = int(value, 16)
    except ValueError as exc:
        raise EvidenceError(f"line {line_number}: {field} is not hexadecimal") from exc
    if parsed < 0 or parsed >= (1 << BLOCK_COUNT):
        raise EvidenceError(f"line {line_number}: {field} exceeds the 48-block mask")
    return parsed


def _validate_box(box: Any, line_number: int, box_index: int) -> dict[str, Any]:
    prefix = f"raw_boxes[{box_index}]"
    if not isinstance(box, dict):
        raise EvidenceError(f"line {line_number}: {prefix} must be an object")
    algorithm = box.get("algorithm")
    if not isinstance(algorithm, str) or not algorithm:
        raise EvidenceError(f"line {line_number}: {prefix}.algorithm must be non-empty")
    algorithm_mask = _integer(box.get("algorithm_mask"), f"{prefix}.algorithm_mask", line_number)
    values = {
        field: _finite_number(box.get(field), f"{prefix}.{field}", line_number)
        for field in (
            "cx_norm",
            "cy_norm",
            "left_norm",
            "top_norm",
            "right_norm",
            "bottom_norm",
        )
    }
    if not (0.0 <= values["left_norm"] <= values["right_norm"] <= 1.0):
        raise EvidenceError(f"line {line_number}: {prefix} has invalid horizontal bounds")
    if not (0.0 <= values["top_norm"] <= values["bottom_norm"] <= 1.0):
        raise EvidenceError(f"line {line_number}: {prefix} has invalid vertical bounds")
    if not (values["left_norm"] <= values["cx_norm"] <= values["right_norm"]):
        raise EvidenceError(f"line {line_number}: {prefix}.cx_norm is outside its bounds")
    if not (values["top_norm"] <= values["cy_norm"] <= values["bottom_norm"]):
        raise EvidenceError(f"line {line_number}: {prefix}.cy_norm is outside its bounds")
    return {"algorithm": algorithm, "algorithm_mask": algorithm_mask, **values}


def load_records(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    previous_frame = -1
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                raise EvidenceError(f"line {line_number}: invalid JSON: {exc.msg}") from exc
            if not isinstance(record, dict):
                raise EvidenceError(f"line {line_number}: record must be an object")
            if record.get("schema_version") != SCHEMA_VERSION:
                raise EvidenceError(
                    f"line {line_number}: schema_version must be {SCHEMA_VERSION}"
                )
            frame = _integer(record.get("frame"), "frame", line_number)
            if frame <= previous_frame:
                raise EvidenceError(f"line {line_number}: frames must be strictly increasing")
            previous_frame = frame
            source_ts_us = _integer(record.get("source_ts_us"), "source_ts_us", line_number)
            shadow_mode = record.get("mode")
            if shadow_mode not in VALID_SHADOW_MODES:
                raise EvidenceError(f"line {line_number}: unknown shadow mode {shadow_mode!r}")
            rescan_mode = record.get("authoritative_rescan_mode")
            if rescan_mode not in VALID_RESCAN_MODES:
                raise EvidenceError(f"line {line_number}: unknown rescan mode {rescan_mode!r}")
            selected_mask = _mask(record.get("selected_mask"), "selected_mask", line_number)
            mandatory_mask = _mask(record.get("mandatory_mask"), "mandatory_mask", line_number)
            selected_fraction = _finite_number(
                record.get("selected_fraction"), "selected_fraction", line_number
            )
            if not 0.0 <= selected_fraction <= 1.0:
                raise EvidenceError(f"line {line_number}: selected_fraction is outside [0,1]")
            max_debt = _finite_number(
                record.get("max_coverage_debt"), "max_coverage_debt", line_number
            )
            max_age_us = _integer(record.get("max_age_us"), "max_age_us", line_number)
            max_age_frames = _integer(
                record.get("max_age_frames"), "max_age_frames", line_number
            )
            reason_flags = _integer(record.get("reason_flags"), "reason_flags", line_number)
            raw_boxes = record.get("raw_boxes")
            if not isinstance(raw_boxes, list):
                raise EvidenceError(f"line {line_number}: raw_boxes must be an array")
            records.append(
                {
                    **record,
                    "frame": frame,
                    "source_ts_us": source_ts_us,
                    "mode": shadow_mode,
                    "authoritative_rescan_mode": rescan_mode,
                    "selected_mask_value": selected_mask,
                    "mandatory_mask_value": mandatory_mask,
                    "selected_fraction": selected_fraction,
                    "max_coverage_debt": max_debt,
                    "max_age_us": max_age_us,
                    "max_age_frames": max_age_frames,
                    "reason_flags": reason_flags,
                    "raw_boxes": [
                        _validate_box(box, line_number, index)
                        for index, box in enumerate(raw_boxes)
                    ],
                }
            )
    if not records:
        raise EvidenceError("evidence contains no records")
    return records


def block_for_point(x_norm: float, y_norm: float) -> int:
    col = min(COLS - 1, max(0, int(x_norm * COLS)))
    row = min(ROWS - 1, max(0, int(y_norm * ROWS)))
    return row * COLS + col


def blocks_for_bounds(box: dict[str, Any]) -> list[int]:
    left_col = min(COLS - 1, max(0, int(box["left_norm"] * COLS)))
    right_x = max(box["left_norm"], math.nextafter(box["right_norm"], -math.inf))
    right_col = min(COLS - 1, max(0, int(right_x * COLS)))
    top_row = min(ROWS - 1, max(0, int(box["top_norm"] * ROWS)))
    bottom_y = max(box["top_norm"], math.nextafter(box["bottom_norm"], -math.inf))
    bottom_row = min(ROWS - 1, max(0, int(bottom_y * ROWS)))
    return [
        row * COLS + col
        for row in range(top_row, bottom_row + 1)
        for col in range(left_col, right_col + 1)
    ]


def _next_selection(
    records: list[dict[str, Any]], record_index: int, block: int
) -> dict[str, int] | None:
    start = records[record_index]
    for later in records[record_index + 1 :]:
        if later["selected_mask_value"] & (1 << block):
            latency_us = later["source_ts_us"] - start["source_ts_us"]
            return {
                "frame": later["frame"],
                "latency_frames": later["frame"] - start["frame"],
                "latency_us": latency_us if latency_us >= 0 else -1,
            }
    return None


def analyze_records(
    records: list[dict[str, Any]],
    *,
    max_explained_latency_frames: int = DEFAULT_MAX_EXPLAINED_LATENCY_FRAMES,
    max_explained_latency_us: int = DEFAULT_MAX_EXPLAINED_LATENCY_US,
) -> dict[str, Any]:
    state_counts = Counter(record["mode"] for record in records)
    fractions: dict[str, list[float]] = defaultdict(list)
    for record in records:
        fractions[record["mode"]].append(record["selected_fraction"])
    selected_by_mode = {
        mode: {
            "frame_count": len(values),
            "avg": sum(values) / len(values),
            "min": min(values),
            "max": max(values),
        }
        for mode, values in sorted(fractions.items())
    }

    full_reason_counts: Counter[str] = Counter()
    full_frames = 0
    candidates: list[dict[str, Any]] = []
    for record_index, record in enumerate(records):
        if record["mode"] == "full_required":
            for bit, name in REASON_FLAGS.items():
                if record["reason_flags"] & bit:
                    full_reason_counts[name] += 1
        if record["authoritative_rescan_mode"] != "full":
            continue
        full_frames += 1
        for box_index, box in enumerate(record["raw_boxes"]):
            center_block = block_for_point(box["cx_norm"], box["cy_norm"])
            footprint_blocks = blocks_for_bounds(box)
            covered = bool(record["selected_mask_value"] & (1 << center_block))
            detail: dict[str, Any] = {
                "frame": record["frame"],
                "source_ts_us": record["source_ts_us"],
                "box_index": box_index,
                "algorithm": box["algorithm"],
                "center": [box["cx_norm"], box["cy_norm"]],
                "bounds": [
                    box["left_norm"],
                    box["top_norm"],
                    box["right_norm"],
                    box["bottom_norm"],
                ],
                "center_block": center_block,
                "footprint_blocks": footprint_blocks,
                "covered_same_frame": covered,
            }
            if not covered:
                next_selection = _next_selection(records, record_index, center_block)
                detail["next_shadow_selection"] = next_selection
                detail["explained_by_later_selection"] = bool(
                    next_selection
                    and next_selection["latency_frames"] <= max_explained_latency_frames
                    and (
                        next_selection["latency_us"] < 0
                        or next_selection["latency_us"] <= max_explained_latency_us
                    )
                )
            candidates.append(detail)

    covered_count = sum(candidate["covered_same_frame"] for candidate in candidates)
    misses = [candidate for candidate in candidates if not candidate["covered_same_frame"]]
    unexplained = [
        candidate for candidate in misses if not candidate["explained_by_later_selection"]
    ]
    return {
        "schema_version": SCHEMA_VERSION,
        "coverage_basis": "candidate_center_block",
        "evidence": {
            "record_count": len(records),
            "authoritative_full_frame_count": full_frames,
            "first_frame": records[0]["frame"],
            "last_frame": records[-1]["frame"],
        },
        "candidates": {
            "total": len(candidates),
            "covered_same_frame": covered_count,
            "missed_same_frame": len(misses),
            "explained_by_later_selection": len(misses) - len(unexplained),
            "unexplained": len(unexplained),
            "missed_details": misses,
        },
        "selected_fraction_by_shadow_mode": selected_by_mode,
        "state_counts": dict(sorted(state_counts.items())),
        "full_required_reason_counts": dict(sorted(full_reason_counts.items())),
        "max_coverage_debt": max(record["max_coverage_debt"] for record in records),
        "max_age_us": max(record["max_age_us"] for record in records),
        "max_age_frames": max(record["max_age_frames"] for record in records),
        "gate_policy": {
            "max_explained_latency_frames": max_explained_latency_frames,
            "max_explained_latency_us": max_explained_latency_us,
        },
    }


def gate_failures(report: dict[str, Any]) -> list[str]:
    failures: list[str] = []
    evidence = report["evidence"]
    candidates = report["candidates"]
    if evidence["authoritative_full_frame_count"] <= 0:
        failures.append("no authoritative full-scan frames in evidence")
    if candidates["total"] <= 0:
        failures.append("no raw candidates on authoritative full-scan frames")
    if candidates["unexplained"] > 0:
        failures.append(
            f"{candidates['unexplained']} full-scan candidate miss(es) lack a bounded later selection"
        )
    return failures


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("jsonl", type=Path)
    parser.add_argument("--output", type=Path, help="Write the aggregate JSON report")
    parser.add_argument("--strict", action="store_true", help="Fail closed on incomplete or unexplained evidence")
    parser.add_argument(
        "--max-explained-latency-frames",
        type=int,
        default=DEFAULT_MAX_EXPLAINED_LATENCY_FRAMES,
    )
    parser.add_argument(
        "--max-explained-latency-us",
        type=int,
        default=DEFAULT_MAX_EXPLAINED_LATENCY_US,
    )
    args = parser.parse_args(argv)
    if args.max_explained_latency_frames < 0 or args.max_explained_latency_us < 0:
        parser.error("latency limits must be non-negative")
    try:
        records = load_records(args.jsonl)
        report = analyze_records(
            records,
            max_explained_latency_frames=args.max_explained_latency_frames,
            max_explained_latency_us=args.max_explained_latency_us,
        )
    except (OSError, EvidenceError) as exc:
        print(f"scene coverage evidence error: {exc}", file=sys.stderr)
        return 2
    failures = gate_failures(report) if args.strict else []
    report["strict_gate"] = {"enabled": args.strict, "passed": not failures, "failures": failures}
    encoded = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    print(encoded, end="")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
