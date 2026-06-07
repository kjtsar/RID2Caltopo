#!/usr/bin/env python3
"""Summarize visual stability of anomaly detection CSV output."""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path


def read_detection_rows(path: Path) -> list[dict[str, float | str]]:
    rows: list[dict[str, float | str]] = []
    with path.open(newline="") as handle:
        reader = csv.DictReader(line for line in handle if not line.startswith("#"))
        for row in reader:
            rows.append(
                {
                    "frame": int(row["frame"]),
                    "time_s": float(row["time_s"]),
                    "algorithm": row["algorithm"],
                    "cx_norm": float(row["cx_norm"]),
                    "cy_norm": float(row["cy_norm"]),
                    "box_w_norm": float(row["box_w_norm"]),
                    "box_h_norm": float(row["box_h_norm"]),
                    "weight": float(row["weight"]),
                }
            )
    return rows


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = int(round((len(ordered) - 1) * pct))
    return ordered[max(0, min(idx, len(ordered) - 1))]


def group_rows_by_frame(rows: list[dict[str, float | str]]) -> dict[int, list[dict[str, float | str]]]:
    by_frame: dict[int, list[dict[str, float | str]]] = {}
    for row in rows:
        by_frame.setdefault(int(row["frame"]), []).append(row)
    return by_frame


def visibility_gaps(visible_frames: list[int]) -> list[dict[str, int]]:
    gaps: list[dict[str, int]] = []
    previous = None
    for frame in visible_frames:
        if previous is not None and frame - previous > 1:
            gaps.append(
                {
                    "start_frame": previous + 1,
                    "end_frame": frame - 1,
                    "duration_frames": frame - previous - 1,
                }
            )
        previous = frame
    return gaps


def greedy_tracks(
    by_frame: dict[int, list[dict[str, float | str]]],
    gate_norm: float,
) -> list[dict[str, object]]:
    tracks: list[dict[str, object]] = []
    for frame in sorted(by_frame):
        used: set[int] = set()
        for track in tracks:
            if int(track["last_frame"]) != frame - 1:
                continue
            best: tuple[float, int, dict[str, float | str]] | None = None
            last = track["last"]
            assert isinstance(last, dict)
            for idx, row in enumerate(by_frame[frame]):
                if idx in used:
                    continue
                dist = math.hypot(
                    float(row["cx_norm"]) - float(last["cx_norm"]),
                    float(row["cy_norm"]) - float(last["cy_norm"]),
                )
                if dist <= gate_norm and (best is None or dist < best[0]):
                    best = (dist, idx, row)
            if best is None:
                continue
            dist, idx, row = best
            used.add(idx)
            track["rows"].append(row)  # type: ignore[index, union-attr]
            track["jumps"].append(dist)  # type: ignore[index, union-attr]
            track["last"] = row
            track["last_frame"] = frame
        for idx, row in enumerate(by_frame[frame]):
            if idx in used:
                continue
            tracks.append({"rows": [row], "jumps": [], "last": row, "last_frame": frame})
    return tracks


def summarize_tracks(tracks: list[dict[str, object]]) -> list[dict[str, object]]:
    summaries: list[dict[str, object]] = []
    for track in tracks:
        rows = track["rows"]
        jumps = track["jumps"]
        assert isinstance(rows, list)
        assert isinstance(jumps, list)
        if len(rows) < 2:
            continue
        first = rows[0]
        last = rows[-1]
        summaries.append(
            {
                "start_s": first["time_s"],
                "end_s": last["time_s"],
                "frame_count": len(rows),
                "max_jump_norm": max(jumps) if jumps else 0.0,
                "p95_jump_norm": percentile([float(v) for v in jumps], 0.95),
                "mean_jump_norm": (sum(float(v) for v in jumps) / len(jumps)) if jumps else 0.0,
                "start_xy": [first["cx_norm"], first["cy_norm"]],
                "end_xy": [last["cx_norm"], last["cy_norm"]],
            }
        )
    summaries.sort(key=lambda item: int(item["frame_count"]), reverse=True)
    return summaries


def summarize(rows: list[dict[str, float | str]], frame_count: int, cadence_frames: int) -> dict[str, object]:
    by_frame = group_rows_by_frame(rows)
    visible_frames = sorted(by_frame)
    gaps = visibility_gaps(visible_frames)
    short_gaps = [gap for gap in gaps if gap["duration_frames"] <= cadence_frames]
    tracks = summarize_tracks(greedy_tracks(by_frame, gate_norm=0.08))
    all_jumps = [
        float(jump)
        for track in greedy_tracks(by_frame, gate_norm=0.08)
        for jump in track["jumps"]  # type: ignore[index]
    ]
    return {
        "frame_count": frame_count,
        "row_count": len(rows),
        "visible_frame_count": len(visible_frames),
        "visibility_ratio": (len(visible_frames) / frame_count) if frame_count > 0 else 0.0,
        "gap_count": len(gaps),
        "short_gap_count": len(short_gaps),
        "max_gap_frames": max((gap["duration_frames"] for gap in gaps), default=0),
        "gaps": gaps[:20],
        "track_count_len2plus": len(tracks),
        "max_jump_norm": max(all_jumps) if all_jumps else 0.0,
        "p95_jump_norm": percentile(all_jumps, 0.95),
        "mean_jump_norm": (sum(all_jumps) / len(all_jumps)) if all_jumps else 0.0,
        "top_tracks": tracks[:8],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_path", type=Path)
    parser.add_argument("--frame-count", type=int, required=True)
    parser.add_argument("--cadence-frames", type=int, default=15)
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()

    report = summarize(
        read_detection_rows(args.csv_path),
        frame_count=args.frame_count,
        cadence_frames=args.cadence_frames,
    )
    text = json.dumps(report, indent=2) + "\n"
    if args.json_out is not None:
        args.json_out.write_text(text)
    else:
        print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
