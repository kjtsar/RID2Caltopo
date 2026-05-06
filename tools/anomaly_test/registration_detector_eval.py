#!/usr/bin/env python3
"""Generate reviewed-frame detection CSVs from raw vs affine-registered residuals.

This is a bridge between the registration prototype and the existing
review-eval loop. It emits anomaly_video_test-style CSV rows so we can score:
1. a simple local residual detector on raw frames
2. the same detector after sparse feature + affine compensation

The goal is not to produce the final app algorithm. It is to answer whether
using the stronger registration basis changes candidate locations in a way
that helps the existing review set.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from registration_eval import (
    affine_point,
    bilinear_sample,
    decode_needed_frames,
    detect_corners,
    ffprobe_metadata,
    load_review,
    phase_correlation_shift,
    ransac_affine,
    track_features,
)


def patch_stats_same(prev, curr, x: float, y: float, center_half: int, ring_half: int):
    center_vals = []
    ring_vals = []
    texture_vals = []
    cx = int(round(x))
    cy = int(round(y))
    for oy in range(-ring_half, ring_half + 1):
        for ox in range(-ring_half, ring_half + 1):
            px = cx + ox
            py = cy + oy
            if px < 0 or py < 0 or px >= prev.shape[1] or py >= prev.shape[0]:
                return None
            pv = float(prev[py, px])
            cv = float(curr[py, px])
            resid = abs(pv - cv)
            texture_vals.append(pv)
            if abs(ox) <= center_half and abs(oy) <= center_half:
                center_vals.append(resid)
            else:
                ring_vals.append(resid)
    if not center_vals or len(ring_vals) < 8:
        return None
    return center_vals, ring_vals, texture_vals


def patch_stats_warped(prev, curr, matrix, x: float, y: float, center_half: int, ring_half: int):
    center_vals = []
    ring_vals = []
    texture_vals = []
    for oy in range(-ring_half, ring_half + 1):
        for ox in range(-ring_half, ring_half + 1):
            x0 = x + ox
            y0 = y + oy
            if x0 < 0 or y0 < 0 or x0 >= prev.shape[1] or y0 >= prev.shape[0]:
                return None
            sx, sy = affine_point(matrix, x0, y0)
            samp = bilinear_sample(curr, sx, sy)
            if samp is None:
                return None
            pv = float(prev[int(round(y0)), int(round(x0))])
            resid = abs(pv - samp)
            texture_vals.append(pv)
            if abs(ox) <= center_half and abs(oy) <= center_half:
                center_vals.append(resid)
            else:
                ring_vals.append(resid)
    if not center_vals or len(ring_vals) < 8:
        return None
    return center_vals, ring_vals, texture_vals


def residual_score(center_vals, ring_vals, texture_vals) -> float:
    center_mean = sum(center_vals) / float(len(center_vals))
    ring_mean = sum(ring_vals) / float(len(ring_vals))
    ring_var = sum((v - ring_mean) * (v - ring_mean) for v in ring_vals) / float(len(ring_vals))
    ring_std = max(ring_var ** 0.5, 1.0)
    texture_mean = sum(texture_vals) / float(len(texture_vals))
    texture_var = sum((v - texture_mean) * (v - texture_mean) for v in texture_vals) / float(len(texture_vals))
    texture_std = texture_var ** 0.5
    if texture_std < 6.0:
        return -1.0
    return (center_mean - ring_mean) / ring_std


def best_candidate(prev, curr, matrix, scan_zone: float, grid_step: int, center_half: int, ring_half: int):
    h, w = prev.shape
    margin_x = int((1.0 - scan_zone) * 0.5 * w)
    margin_y = int((1.0 - scan_zone) * 0.5 * h)
    x0 = max(ring_half + 1, margin_x)
    x1 = min(w - ring_half - 1, w - margin_x)
    y0 = max(ring_half + 1, margin_y)
    y1 = min(h - ring_half - 1, h - margin_y)
    best_score = None
    best_xy = None
    for py in range(y0, y1, grid_step):
        for px in range(x0, x1, grid_step):
            if matrix is None:
                stats = patch_stats_same(prev, curr, px, py, center_half, ring_half)
            else:
                stats = patch_stats_warped(prev, curr, matrix, px, py, center_half, ring_half)
            if stats is None:
                continue
            score = residual_score(*stats)
            if best_score is None or score > best_score:
                best_score = score
                best_xy = (px, py)
    return best_xy, (-1.0 if best_score is None else best_score)


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
    raw_csv: Path,
    affine_csv: Path,
    scan_zone: float,
    threshold: float,
    grid_step: int,
    max_corners: int,
    search_radius: int,
) -> str:
    width, height, fps = ffprobe_metadata(video_path)
    annotations = load_review(review_path, fps)
    reviewed_frames = sorted({ann.frame_idx for ann in annotations if ann.frame_idx > 0})
    needed_frames = set(reviewed_frames)
    needed_frames |= {idx - 1 for idx in reviewed_frames}
    frames = decode_needed_frames(video_path, width, height, needed_frames)

    raw_rows = []
    affine_rows = []
    model_count = 0

    for frame_idx in reviewed_frames:
        prev = frames.get(frame_idx - 1)
        curr = frames.get(frame_idx)
        if prev is None or curr is None:
            continue
        corners = detect_corners(prev, scan_zone=scan_zone, max_corners=max_corners)
        shift_x, shift_y = phase_correlation_shift(prev, curr)
        matches = track_features(prev, curr, corners, shift_x, shift_y, search_radius=search_radius)
        matrix, inliers, _mean_residual = ransac_affine(matches)
        if matrix is not None and len(inliers) >= 3:
            model_count += 1

        raw_xy, raw_score = best_candidate(prev, curr, None, scan_zone, grid_step, center_half=2, ring_half=6)
        affine_xy, affine_score = best_candidate(prev, curr, matrix, scan_zone, grid_step, center_half=2, ring_half=6)
        time_s = frame_idx / fps
        if raw_xy is not None and raw_score >= threshold:
            raw_rows.append(
                {
                    "frame": frame_idx,
                    "time_s": time_s,
                    "algorithm": "raw_local_residual",
                    "cx_norm": raw_xy[0] / float(width - 1),
                    "cy_norm": raw_xy[1] / float(height - 1),
                    "box_w_norm": 24.0 / float(width),
                    "box_h_norm": 24.0 / float(height),
                    "weight": raw_score,
                }
            )
        if affine_xy is not None and affine_score >= threshold:
            affine_rows.append(
                {
                    "frame": frame_idx,
                    "time_s": time_s,
                    "algorithm": "affine_local_residual",
                    "cx_norm": affine_xy[0] / float(width - 1),
                    "cy_norm": affine_xy[1] / float(height - 1),
                    "box_w_norm": 24.0 / float(width),
                    "box_h_norm": 24.0 / float(height),
                    "weight": affine_score,
                }
            )

    write_csv(raw_csv, raw_rows, threshold, scan_zone, grid_step)
    write_csv(affine_csv, affine_rows, threshold, scan_zone, grid_step)
    return (
        f"reviewed_frames={len(reviewed_frames)} "
        f"modeled_frames={model_count} "
        f"raw_rows={len(raw_rows)} "
        f"affine_rows={len(affine_rows)}"
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("video", type=Path)
    parser.add_argument("review_json", type=Path)
    parser.add_argument("--raw-csv", type=Path, required=True)
    parser.add_argument("--affine-csv", type=Path, required=True)
    parser.add_argument("--scan-zone", type=float, default=0.63)
    parser.add_argument("--threshold", type=float, default=0.60)
    parser.add_argument("--grid-step", type=int, default=12)
    parser.add_argument("--max-corners", type=int, default=72)
    parser.add_argument("--search-radius", type=int, default=8)
    args = parser.parse_args()
    print(
        evaluate(
            args.video,
            args.review_json,
            args.raw_csv,
            args.affine_csv,
            args.scan_zone,
            args.threshold,
            args.grid_step,
            args.max_corners,
            args.search_radius,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
