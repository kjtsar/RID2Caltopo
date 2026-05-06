#!/usr/bin/env python3
"""Generate reviewed-frame thermal candidate CSVs with optional affine-motion validation.

This prototype asks a more targeted question than motion-first detection:
1. Can a compact thermal blob proposal get closer to the annotated people?
2. Does affine residual motion help reject static thermal clutter afterward?
"""

from __future__ import annotations

import argparse
from pathlib import Path

from registration_eval import (
    decode_needed_frames,
    detect_corners,
    ffprobe_metadata,
    load_review,
    phase_correlation_shift,
    ransac_affine,
    residual_local_score_warped,
    track_features,
)

THERMAL_SCALES = (
    (1, 4, 8),
    (2, 6, 12),
    (3, 8, 16),
)


def apply_temporal_persistence(rows, radius_norm: float, min_neighbors: int):
    if not rows:
        return []
    by_frame: dict[int, list[dict]] = {}
    for row in rows:
        by_frame.setdefault(int(row["frame"]), []).append(row)

    kept = []
    for row in rows:
        frame = int(row["frame"])
        cx = float(row["cx_norm"])
        cy = float(row["cy_norm"])
        neighbors = 0
        for other_frame in (frame - 1, frame + 1):
            found = False
            for other in by_frame.get(other_frame, []):
                dx = float(other["cx_norm"]) - cx
                dy = float(other["cy_norm"]) - cy
                if dx * dx + dy * dy <= radius_norm * radius_norm:
                    found = True
                    break
            if found:
                neighbors += 1
        if neighbors >= min_neighbors:
            kept.append(row)
    return kept


def apply_track_filter(rows, radius_norm: float, min_track_len: int, max_frame_gap: int, top_tracks: int):
    if not rows:
        return []

    indexed = sorted(
        enumerate(rows),
        key=lambda item: (int(item[1]["frame"]), -float(item[1]["weight"])),
    )
    tracks = []

    for row_idx, row in indexed:
        frame = int(row["frame"])
        cx = float(row["cx_norm"])
        cy = float(row["cy_norm"])

        best_track = None
        best_dist2 = None
        for track in tracks:
            frame_gap = frame - track["last_frame"]
            if frame_gap <= 0 or frame_gap > max_frame_gap:
                continue
            dx = cx - track["last_cx"]
            dy = cy - track["last_cy"]
            dist2 = dx * dx + dy * dy
            if dist2 > radius_norm * radius_norm:
                continue
            if best_dist2 is None or dist2 < best_dist2:
                best_dist2 = dist2
                best_track = track

        if best_track is None:
            tracks.append(
                {
                    "rows": [row_idx],
                    "last_frame": frame,
                    "last_cx": cx,
                    "last_cy": cy,
                    "sum_cx": cx,
                    "sum_cy": cy,
                    "sum_weight": float(row["weight"]),
                    "sum_motion": float(row.get("motion_score", 0.0)),
                }
            )
        else:
            best_track["rows"].append(row_idx)
            best_track["last_frame"] = frame
            best_track["last_cx"] = cx
            best_track["last_cy"] = cy
            best_track["sum_cx"] += cx
            best_track["sum_cy"] += cy
            best_track["sum_weight"] += float(row["weight"])
            best_track["sum_motion"] += float(row.get("motion_score", 0.0))

    scored_tracks = []
    for track in tracks:
        track_len = len(track["rows"])
        if track_len < min_track_len:
            continue
        avg_cx = track["sum_cx"] / float(track_len)
        avg_cy = track["sum_cy"] / float(track_len)
        avg_weight = track["sum_weight"] / float(track_len)
        avg_motion = track["sum_motion"] / float(track_len)
        track_bonus = min(0.35, 0.08 * float(track_len - 1))
        track_score = avg_weight + track_bonus + (0.20 * max(avg_motion, 0.0))
        scored_tracks.append((track_score, avg_cx, avg_cy, avg_weight, track_bonus, track))

    scored_tracks.sort(reverse=True, key=lambda item: item[0])
    if top_tracks > 0:
        scored_tracks = scored_tracks[:top_tracks]

    kept = []
    for _track_score, avg_cx, avg_cy, avg_weight, track_bonus, track in scored_tracks:
        for row_idx in track["rows"]:
            row = dict(rows[row_idx])
            # Smooth the output position toward the track center so a stable
            # small target is not judged entirely by one jittery frame sample.
            row["cx_norm"] = avg_cx
            row["cy_norm"] = avg_cy
            row["weight"] = max(float(row["weight"]), avg_weight) + track_bonus
            kept.append(row)
    return kept


def thermal_blob_score(
    image,
    x: int,
    y: int,
    center_half: int,
    ring_half: int,
    outer_half: int,
    polarity: str,
) -> float | None:
    h, w = image.shape
    if (
        x - outer_half < 0
        or y - outer_half < 0
        or x + outer_half >= w
        or y + outer_half >= h
    ):
        return None

    center_vals = []
    ring_vals = []
    outer_vals = []
    for oy in range(-outer_half, outer_half + 1):
        for ox in range(-outer_half, outer_half + 1):
            v = float(image[y + oy, x + ox])
            cheb = max(abs(ox), abs(oy))
            if cheb <= center_half:
                center_vals.append(v)
            elif cheb <= ring_half:
                ring_vals.append(v)
            else:
                outer_vals.append(v)
    if not center_vals or not ring_vals or not outer_vals:
        return None

    center_mean = sum(center_vals) / float(len(center_vals))
    ring_mean = sum(ring_vals) / float(len(ring_vals))
    outer_mean = sum(outer_vals) / float(len(outer_vals))
    ring_var = sum((v - ring_mean) * (v - ring_mean) for v in ring_vals) / float(len(ring_vals))
    ring_std = max(ring_var ** 0.5, 4.0)
    center_var = sum((v - center_mean) * (v - center_mean) for v in center_vals) / float(len(center_vals))
    center_std = center_var ** 0.5

    if polarity == "bh":
        local_contrast = ring_mean - center_mean
        outer_contrast = outer_mean - center_mean
    else:
        local_contrast = center_mean - ring_mean
        outer_contrast = center_mean - outer_mean

    if local_contrast <= 0.0:
        return None

    score = local_contrast / ring_std

    # Favor compact, locally consistent centers over textured boundaries.
    if center_std > 10.0:
        score *= max(0.15, 1.0 - ((center_std - 10.0) / 20.0))

    # Penalize very broad masses whose contrast persists well beyond the local
    # ring, but do it softly so obvious hotspots at different image scales do
    # not disappear simply because the fixed window was a poor match.
    if outer_contrast > local_contrast * 0.85:
        score *= 0.55
    elif outer_contrast > local_contrast * 0.60:
        score *= 0.78

    return score


def motion_validation_score(prev, curr, matrix, x: int, y: int) -> float:
    if matrix is None:
        return 0.0
    score = residual_local_score_warped(prev, curr, matrix, float(x), float(y))
    if score is None:
        return 0.0
    return score


def top_candidates(
    prev,
    curr,
    matrix,
    scan_zone: float,
    grid_step: int,
    polarity: str,
    require_motion: bool,
    top_k: int,
    motion_gate: float,
):
    h, w = curr.shape
    max_outer_half = max(spec[2] for spec in THERMAL_SCALES)
    margin_x = int((1.0 - scan_zone) * 0.5 * w)
    margin_y = int((1.0 - scan_zone) * 0.5 * h)
    x0 = max(max_outer_half + 1, margin_x)
    x1 = min(w - max_outer_half - 1, w - margin_x)
    y0 = max(max_outer_half + 1, margin_y)
    y1 = min(h - max_outer_half - 1, h - margin_y)

    candidates = []
    for py in range(y0, y1, grid_step):
        for px in range(x0, x1, grid_step):
            thermal_score = None
            for center_half, ring_half, outer_half in THERMAL_SCALES:
                score_here = thermal_blob_score(curr, px, py, center_half, ring_half, outer_half, polarity)
                if score_here is None:
                    continue
                if thermal_score is None or score_here > thermal_score:
                    thermal_score = score_here
            if thermal_score is None or thermal_score <= 0.0:
                continue
            motion_score = motion_validation_score(prev, curr, matrix, px, py)
            score = thermal_score
            if require_motion:
                if motion_score < 0.0:
                    motion_score = 0.0
                score += 0.35 * min(2.0, motion_score / max(motion_gate, 0.30))
            candidates.append((score, px, py, thermal_score, motion_score))

    if not candidates:
        return []

    raw_scores = [c[0] for c in candidates]
    mean_score = sum(raw_scores) / float(len(raw_scores))
    var_score = sum((v - mean_score) * (v - mean_score) for v in raw_scores) / float(len(raw_scores))
    std_score = max(var_score ** 0.5, 0.15)
    candidates = [
        (((score - mean_score) / std_score), px, py, thermal_score, motion_score)
        for score, px, py, thermal_score, motion_score in candidates
    ]

    candidates.sort(reverse=True)
    chosen = []
    min_sep = 24
    for score, px, py, thermal_score, motion_score in candidates:
        too_close = False
        for _cscore, cx, cy, _cthermal, _cmotion in chosen:
            if (px - cx) * (px - cx) + (py - cy) * (py - cy) < min_sep * min_sep:
                too_close = True
                break
        if too_close:
            continue
        chosen.append((score, px, py, thermal_score, motion_score))
        if len(chosen) >= top_k:
            break
    return chosen


def write_csv(path: Path, rows, threshold: float, scan_zone: float, grid_step: int):
    with path.open("w", newline="") as handle:
        handle.write(f"# threshold: {threshold:.2f}  scan_zone: {scan_zone:.2f}  grid_step: {grid_step}\n")
        handle.write("frame,time_s,algorithm,cx_norm,cy_norm,box_w_norm,box_h_norm,weight,label\n")
        for row in rows:
            handle.write(
                f"{row['frame']},{row['time_s']:.3f},{row['algorithm']},"
                f"{row['cx_norm']:.4f},{row['cy_norm']:.4f},"
                f"{row['box_w_norm']:.4f},{row['box_h_norm']:.4f},"
                f"{row['weight']:.2f},\n"
            )


def evaluate(
    video_path: Path,
    review_path: Path,
    thermal_csv: Path,
    validated_csv: Path,
    scan_zone: float,
    threshold: float,
    grid_step: int,
    max_corners: int,
    search_radius: int,
    polarity: str,
    top_k: int,
    motion_gate: float,
    persist_radius_norm: float,
    persist_neighbors: int,
    track_radius_norm: float,
    min_track_len: int,
    track_frame_gap: int,
    top_tracks: int,
) -> str:
    width, height, fps = ffprobe_metadata(video_path)
    annotations = load_review(review_path, fps)
    reviewed_frames = sorted({ann.frame_idx for ann in annotations if ann.frame_idx > 0})
    needed_frames = set(reviewed_frames)
    needed_frames |= {idx - 1 for idx in reviewed_frames}
    frames = decode_needed_frames(video_path, width, height, needed_frames)

    thermal_rows = []
    validated_rows = []
    model_count = 0

    for frame_idx in reviewed_frames:
        prev = frames.get(frame_idx - 1)
        curr = frames.get(frame_idx)
        if prev is None or curr is None:
            continue
        corners = detect_corners(prev, scan_zone=scan_zone, max_corners=max_corners)
        shift_x, shift_y = phase_correlation_shift(prev, curr)
        matches = track_features(prev, curr, corners, shift_x, shift_y, search_radius=search_radius)
        matrix, inliers, _ = ransac_affine(matches)
        if matrix is not None and len(inliers) >= 3:
            model_count += 1

        time_s = frame_idx / fps
        thermal_candidates = top_candidates(prev, curr, matrix, scan_zone, grid_step, polarity, False, top_k, motion_gate)
        validated_candidates = top_candidates(prev, curr, matrix, scan_zone, grid_step, polarity, True, top_k, motion_gate)

        for thermal_score, px, py, _thermal_base, motion_score in thermal_candidates:
            if thermal_score < threshold:
                continue
            thermal_rows.append(
                {
                    "frame": frame_idx,
                    "time_s": time_s,
                    "algorithm": "thermal_compact",
                    "cx_norm": px / float(width - 1),
                    "cy_norm": py / float(height - 1),
                    "box_w_norm": 20.0 / float(width),
                    "box_h_norm": 20.0 / float(height),
                    "weight": thermal_score,
                    "motion_score": motion_score,
                }
            )
        for validated_score, px, py, _thermal_base, motion_score in validated_candidates:
            if validated_score < threshold:
                continue
            validated_rows.append(
                {
                    "frame": frame_idx,
                    "time_s": time_s,
                    "algorithm": "thermal_affine_validated",
                    "cx_norm": px / float(width - 1),
                    "cy_norm": py / float(height - 1),
                    "box_w_norm": 20.0 / float(width),
                    "box_h_norm": 20.0 / float(height),
                    "weight": validated_score,
                    "motion_score": motion_score,
                }
            )

    thermal_rows = apply_temporal_persistence(thermal_rows, persist_radius_norm, persist_neighbors)
    validated_rows = apply_temporal_persistence(validated_rows, persist_radius_norm, persist_neighbors)
    thermal_rows = apply_track_filter(thermal_rows, track_radius_norm, min_track_len, track_frame_gap, top_tracks)
    validated_rows = apply_track_filter(validated_rows, track_radius_norm, min_track_len, track_frame_gap, top_tracks)

    write_csv(thermal_csv, thermal_rows, threshold, scan_zone, grid_step)
    write_csv(validated_csv, validated_rows, threshold, scan_zone, grid_step)
    return (
        f"reviewed_frames={len(reviewed_frames)} "
        f"modeled_frames={model_count} "
        f"thermal_rows={len(thermal_rows)} "
        f"validated_rows={len(validated_rows)}"
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("video", type=Path)
    parser.add_argument("review_json", type=Path)
    parser.add_argument("--thermal-csv", type=Path, required=True)
    parser.add_argument("--validated-csv", type=Path, required=True)
    parser.add_argument("--scan-zone", type=float, default=0.63)
    parser.add_argument("--threshold", type=float, default=0.0)
    parser.add_argument("--grid-step", type=int, default=10)
    parser.add_argument("--max-corners", type=int, default=72)
    parser.add_argument("--search-radius", type=int, default=8)
    parser.add_argument("--polarity", choices=("bh", "wh"), default="bh")
    parser.add_argument("--top-k", type=int, default=3)
    parser.add_argument("--motion-gate", type=float, default=0.30)
    parser.add_argument("--persist-radius", type=float, default=0.08)
    parser.add_argument("--persist-neighbors", type=int, default=1)
    parser.add_argument("--track-radius", type=float, default=0.12)
    parser.add_argument("--min-track-len", type=int, default=2)
    parser.add_argument("--track-frame-gap", type=int, default=20)
    parser.add_argument("--top-tracks", type=int, default=2)
    args = parser.parse_args()
    print(
        evaluate(
            args.video,
            args.review_json,
            args.thermal_csv,
            args.validated_csv,
            args.scan_zone,
            args.threshold,
            args.grid_step,
            args.max_corners,
            args.search_radius,
            args.polarity,
            args.top_k,
            args.motion_gate,
            args.persist_radius,
            args.persist_neighbors,
            args.track_radius,
            args.min_track_len,
            args.track_frame_gap,
            args.top_tracks,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
