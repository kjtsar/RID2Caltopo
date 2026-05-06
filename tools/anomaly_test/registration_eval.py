#!/usr/bin/env python3
"""Evaluate sparse feature registration around reviewed anomaly frames.

This is an offline experiment meant to answer:
1. Can robust frame registration explain away many false positives?
2. Do missed targets stay less explainable than false-positive regions after
   compensation?

It approximates an OpenCV-style pipeline using only local dependencies:
- sparse corner detection on the previous frame
- patch matching into the current frame
- affine fit with RANSAC
- patch residuals at annotation points before/after compensation
"""

from __future__ import annotations

import argparse
import json
import math
import random
import subprocess
from dataclasses import dataclass
from pathlib import Path

import numpy as np


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


@dataclass
class RegistrationResult:
    frame_idx: int
    time_s: float
    matched_features: int
    inliers: int
    mean_residual_px: float
    matrix: np.ndarray | None


def ffprobe_metadata(video_path: Path) -> tuple[int, int, float]:
    cmd = [
        "ffprobe",
        "-v",
        "error",
        "-select_streams",
        "v:0",
        "-show_entries",
        "stream=width,height,r_frame_rate",
        "-of",
        "json",
        str(video_path),
    ]
    raw = subprocess.check_output(cmd, text=True)
    data = json.loads(raw)
    stream = data["streams"][0]
    width = int(stream["width"])
    height = int(stream["height"])
    rate = stream.get("r_frame_rate", "30/1")
    num, den = rate.split("/", 1)
    fps = float(num) / float(den)
    return width, height, fps


def load_review(path: Path, fps: float) -> list[Annotation]:
    raw = json.loads(path.read_text())
    result: list[Annotation] = []
    for frame in raw.get("frames", []):
        time_s = float(frame.get("source_timestamp_us", 0)) / 1_000_000.0
        frame_idx = max(0, int(round(time_s * fps)))
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
                    scenario=str(ann.get("scenario", "")),
                    note=str(ann.get("note", "")),
                )
            )
    return result


def decode_needed_frames(video_path: Path, width: int, height: int, frame_indices: set[int]) -> dict[int, np.ndarray]:
    if not frame_indices:
        return {}
    max_frame = max(frame_indices)
    cmd = [
        "ffmpeg",
        "-v",
        "error",
        "-i",
        str(video_path),
        "-f",
        "rawvideo",
        "-pix_fmt",
        "gray",
        "-vsync",
        "0",
        "-",
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    if proc.stdout is None:
        raise RuntimeError("failed to open ffmpeg stdout")
    frame_size = width * height
    wanted: dict[int, np.ndarray] = {}
    idx = 0
    try:
        while idx <= max_frame:
            buf = proc.stdout.read(frame_size)
            if len(buf) < frame_size:
                break
            if idx in frame_indices:
                arr = np.frombuffer(buf, dtype=np.uint8).copy().reshape((height, width))
                wanted[idx] = arr
                if len(wanted) == len(frame_indices):
                    break
            idx += 1
    finally:
        if proc.poll() is None:
            proc.terminate()
        proc.stdout.close()
        proc.wait()
    return wanted


def gradients(image: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    gy = np.zeros_like(image, dtype=np.float32)
    gx = np.zeros_like(image, dtype=np.float32)
    gx[:, 1:-1] = image[:, 2:].astype(np.float32) - image[:, :-2].astype(np.float32)
    gy[1:-1, :] = image[2:, :].astype(np.float32) - image[:-2, :].astype(np.float32)
    return gx, gy


def box_blur(arr: np.ndarray, radius: int) -> np.ndarray:
    if radius <= 0:
        return arr.copy()
    h, w = arr.shape
    integ = np.pad(arr, ((1, 0), (1, 0)), mode="constant").cumsum(0).cumsum(1)
    y0 = np.clip(np.arange(h) - radius, 0, h)
    y1 = np.clip(np.arange(h) + radius + 1, 0, h)
    x0 = np.clip(np.arange(w) - radius, 0, w)
    x1 = np.clip(np.arange(w) + radius + 1, 0, w)
    total = (
        integ[y1[:, None], x1[None, :]]
        - integ[y0[:, None], x1[None, :]]
        - integ[y1[:, None], x0[None, :]]
        + integ[y0[:, None], x0[None, :]]
    )
    area = (y1 - y0)[:, None] * (x1 - x0)[None, :]
    return (total / area).astype(np.float32)


def detect_corners(
    image: np.ndarray,
    scan_zone: float,
    max_corners: int = 180,
    quality: float = 0.08,
    min_distance: int = 10,
) -> list[tuple[int, int, float]]:
    gx, gy = gradients(image)
    ixx = box_blur(gx * gx, 2)
    iyy = box_blur(gy * gy, 2)
    ixy = box_blur(gx * gy, 2)
    trace = ixx + iyy
    det = ixx * iyy - ixy * ixy
    tmp = np.maximum(trace * trace - 4.0 * det, 0.0)
    eig_min = 0.5 * (trace - np.sqrt(tmp))

    h, w = image.shape
    margin_x = int((1.0 - scan_zone) * 0.5 * w)
    margin_y = int((1.0 - scan_zone) * 0.5 * h)
    y0, y1 = margin_y, h - margin_y
    x0, x1 = margin_x, w - margin_x
    roi = eig_min[y0:y1, x0:x1]
    thresh = float(np.max(roi)) * quality if roi.size else 0.0
    padded = np.pad(eig_min, 1, mode="edge")
    neighbors = []
    for oy in range(3):
        for ox in range(3):
            neighbors.append(padded[oy : oy + h, ox : ox + w])
    local_max = np.maximum.reduce(neighbors)
    mask = eig_min >= thresh
    mask &= eig_min >= local_max
    mask[:4, :] = False
    mask[-4:, :] = False
    mask[:, :4] = False
    mask[:, -4:] = False
    mask[:y0, :] = False
    mask[y1:, :] = False
    mask[:, :x0] = False
    mask[:, x1:] = False
    coords = np.argwhere(mask)
    candidates: list[tuple[int, int, float]] = [
        (int(x), int(y), float(eig_min[y, x])) for y, x in coords
    ]
    candidates.sort(key=lambda item: item[2], reverse=True)

    chosen: list[tuple[int, int, float]] = []
    for cand in candidates:
        x, y, score = cand
        if any((x - px) * (x - px) + (y - py) * (y - py) < min_distance * min_distance for px, py, _ in chosen):
            continue
        chosen.append((x, y, score))
        if len(chosen) >= max_corners:
            break
    return chosen


def phase_correlation_shift(prev: np.ndarray, curr: np.ndarray) -> tuple[float, float]:
    a = prev.astype(np.float32) - float(np.mean(prev))
    b = curr.astype(np.float32) - float(np.mean(curr))
    fa = np.fft.rfft2(a)
    fb = np.fft.rfft2(b)
    cross = fa * np.conj(fb)
    denom = np.abs(cross)
    denom[denom < 1e-6] = 1e-6
    corr = np.fft.irfft2(cross / denom, s=prev.shape)
    y, x = np.unravel_index(np.argmax(corr), corr.shape)
    if x > prev.shape[1] // 2:
        x -= prev.shape[1]
    if y > prev.shape[0] // 2:
        y -= prev.shape[0]
    return float(x), float(y)


def extract_patch(image: np.ndarray, cx: int, cy: int, half: int) -> np.ndarray | None:
    if cx - half < 0 or cy - half < 0 or cx + half >= image.shape[1] or cy + half >= image.shape[0]:
        return None
    return image[cy - half : cy + half + 1, cx - half : cx + half + 1].astype(np.float32)


def track_features(
    prev: np.ndarray,
    curr: np.ndarray,
    corners: list[tuple[int, int, float]],
    base_dx: float,
    base_dy: float,
    patch_half: int = 4,
    search_radius: int = 14,
) -> list[tuple[float, float, float, float, float]]:
    matches: list[tuple[float, float, float, float, float]] = []
    for x, y, _score in corners:
        patch = extract_patch(prev, x, y, patch_half)
        if patch is None:
            continue
        pred_x = int(round(x - base_dx))
        pred_y = int(round(y - base_dy))
        best_err = None
        best_xy = None
        second_err = None
        for dy in range(-search_radius, search_radius + 1):
            for dx in range(-search_radius, search_radius + 1):
                cx = pred_x + dx
                cy = pred_y + dy
                cand = extract_patch(curr, cx, cy, patch_half)
                if cand is None:
                    continue
                diff = patch - cand
                err = float(np.mean(diff * diff))
                if best_err is None or err < best_err:
                    second_err = best_err
                    best_err = err
                    best_xy = (cx, cy)
                elif second_err is None or err < second_err:
                    second_err = err
        if best_err is None or best_xy is None:
            continue
        if second_err is not None and second_err <= best_err * 1.05:
            continue
        matches.append((float(x), float(y), float(best_xy[0]), float(best_xy[1]), best_err))
    return matches


def fit_affine_least_squares(matches: list[tuple[float, float, float, float, float]]) -> np.ndarray | None:
    if len(matches) < 3:
        return None
    a_rows = []
    b_vals = []
    for x0, y0, x1, y1, _ in matches:
        a_rows.append([x0, y0, 1.0, 0.0, 0.0, 0.0])
        a_rows.append([0.0, 0.0, 0.0, x0, y0, 1.0])
        b_vals.append(x1)
        b_vals.append(y1)
    a = np.asarray(a_rows, dtype=np.float32)
    b = np.asarray(b_vals, dtype=np.float32)
    try:
        params, *_ = np.linalg.lstsq(a, b, rcond=None)
    except np.linalg.LinAlgError:
        return None
    return np.asarray(
        [
            [params[0], params[1], params[2]],
            [params[3], params[4], params[5]],
        ],
        dtype=np.float32,
    )


def affine_point(matrix: np.ndarray, x: float, y: float) -> tuple[float, float]:
    nx = float(matrix[0, 0] * x + matrix[0, 1] * y + matrix[0, 2])
    ny = float(matrix[1, 0] * x + matrix[1, 1] * y + matrix[1, 2])
    return nx, ny


def ransac_affine(
    matches: list[tuple[float, float, float, float, float]],
    iters: int = 120,
    inlier_thresh: float = 3.0,
) -> tuple[np.ndarray | None, list[int], float]:
    if len(matches) < 3:
        return None, [], float("inf")
    rng = random.Random(0)
    best_matrix = None
    best_inliers: list[int] = []
    best_mean = float("inf")
    idxs = list(range(len(matches)))
    for _ in range(iters):
        sample = rng.sample(idxs, 3)
        model = fit_affine_least_squares([matches[i] for i in sample])
        if model is None:
            continue
        inliers: list[int] = []
        residual_sum = 0.0
        for i, (x0, y0, x1, y1, _err) in enumerate(matches):
            px, py = affine_point(model, x0, y0)
            resid = math.hypot(px - x1, py - y1)
            if resid <= inlier_thresh:
                inliers.append(i)
                residual_sum += resid
        if not inliers:
            continue
        mean = residual_sum / float(len(inliers))
        if len(inliers) > len(best_inliers) or (len(inliers) == len(best_inliers) and mean < best_mean):
            best_matrix = model
            best_inliers = inliers
            best_mean = mean
    if best_matrix is None or len(best_inliers) < 3:
        return None, [], float("inf")
    refined = fit_affine_least_squares([matches[i] for i in best_inliers])
    if refined is not None:
        best_matrix = refined
        residuals = []
        refined_inliers: list[int] = []
        for i, (x0, y0, x1, y1, _err) in enumerate(matches):
            px, py = affine_point(best_matrix, x0, y0)
            resid = math.hypot(px - x1, py - y1)
            if resid <= inlier_thresh:
                refined_inliers.append(i)
                residuals.append(resid)
        if refined_inliers:
            best_inliers = refined_inliers
            best_mean = float(np.mean(residuals))
    return best_matrix, best_inliers, best_mean


def bilinear_sample(image: np.ndarray, x: float, y: float) -> float | None:
    h, w = image.shape
    if x < 0.0 or y < 0.0 or x >= w - 1 or y >= h - 1:
        return None
    x0 = int(math.floor(x))
    y0 = int(math.floor(y))
    fx = x - x0
    fy = y - y0
    p00 = float(image[y0, x0])
    p10 = float(image[y0, x0 + 1])
    p01 = float(image[y0 + 1, x0])
    p11 = float(image[y0 + 1, x0 + 1])
    return (
        p00 * (1.0 - fx) * (1.0 - fy)
        + p10 * fx * (1.0 - fy)
        + p01 * (1.0 - fx) * fy
        + p11 * fx * fy
    )


def patch_residual_same(prev: np.ndarray, curr: np.ndarray, x: float, y: float, half: int = 5) -> float | None:
    vals = []
    for oy in range(-half, half + 1):
        for ox in range(-half, half + 1):
            px = int(round(x)) + ox
            py = int(round(y)) + oy
            if px < 0 or py < 0 or px >= prev.shape[1] or py >= prev.shape[0]:
                return None
            vals.append(abs(float(prev[py, px]) - float(curr[py, px])))
    return float(np.mean(vals)) if vals else None


def patch_residual_warped(prev: np.ndarray, curr: np.ndarray, matrix: np.ndarray, x: float, y: float, half: int = 5) -> float | None:
    vals = []
    for oy in range(-half, half + 1):
        for ox in range(-half, half + 1):
            x0 = x + ox
            y0 = y + oy
            if x0 < 0 or y0 < 0 or x0 >= prev.shape[1] or y0 >= prev.shape[0]:
                return None
            sx, sy = affine_point(matrix, x0, y0)
            samp = bilinear_sample(curr, sx, sy)
            if samp is None:
                return None
            vals.append(abs(float(prev[int(round(y0)), int(round(x0))]) - samp))
    return float(np.mean(vals)) if vals else None


def residual_local_score_same(
    prev: np.ndarray,
    curr: np.ndarray,
    x: float,
    y: float,
    center_half: int = 2,
    ring_half: int = 6,
) -> float | None:
    center_vals = []
    ring_vals = []
    cx = int(round(x))
    cy = int(round(y))
    for oy in range(-ring_half, ring_half + 1):
        for ox in range(-ring_half, ring_half + 1):
            px = cx + ox
            py = cy + oy
            if px < 0 or py < 0 or px >= prev.shape[1] or py >= prev.shape[0]:
                return None
            resid = abs(float(prev[py, px]) - float(curr[py, px]))
            if abs(ox) <= center_half and abs(oy) <= center_half:
                center_vals.append(resid)
            else:
                ring_vals.append(resid)
    if not center_vals or len(ring_vals) < 8:
        return None
    center_mean = float(np.mean(center_vals))
    ring_mean = float(np.mean(ring_vals))
    ring_std = float(np.std(ring_vals))
    ring_std = max(ring_std, 1.0)
    return (center_mean - ring_mean) / ring_std


def residual_local_score_warped(
    prev: np.ndarray,
    curr: np.ndarray,
    matrix: np.ndarray,
    x: float,
    y: float,
    center_half: int = 2,
    ring_half: int = 6,
) -> float | None:
    center_vals = []
    ring_vals = []
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
            resid = abs(float(prev[int(round(y0)), int(round(x0))]) - samp)
            if abs(ox) <= center_half and abs(oy) <= center_half:
                center_vals.append(resid)
            else:
                ring_vals.append(resid)
    if not center_vals or len(ring_vals) < 8:
        return None
    center_mean = float(np.mean(center_vals))
    ring_mean = float(np.mean(ring_vals))
    ring_std = float(np.std(ring_vals))
    ring_std = max(ring_std, 1.0)
    return (center_mean - ring_mean) / ring_std


def evaluate(video_path: Path, review_path: Path, scan_zone: float) -> str:
    width, height, fps = ffprobe_metadata(video_path)
    annotations = load_review(review_path, fps)
    needed_frames = {ann.frame_idx for ann in annotations if ann.frame_idx > 0}
    needed_frames |= {ann.frame_idx - 1 for ann in annotations if ann.frame_idx > 0}
    frames = decode_needed_frames(video_path, width, height, needed_frames)

    reg_by_frame: dict[int, RegistrationResult] = {}
    lines = [
        f"Video: {video_path}",
        f"Review: {review_path}",
        f"Frames loaded: {len(frames)}",
        f"Annotations: {len(annotations)}",
    ]

    summary: dict[str, dict[str, float]] = {}

    for ann in annotations:
        if ann.frame_idx <= 0:
            continue
        prev = frames.get(ann.frame_idx - 1)
        curr = frames.get(ann.frame_idx)
        if prev is None or curr is None:
            continue
        reg = reg_by_frame.get(ann.frame_idx)
        if reg is None:
            corners = detect_corners(prev, scan_zone=scan_zone)
            shift_x, shift_y = phase_correlation_shift(prev, curr)
            matches = track_features(prev, curr, corners, shift_x, shift_y)
            matrix, inliers, mean_residual = ransac_affine(matches)
            reg = RegistrationResult(
                frame_idx=ann.frame_idx,
                time_s=ann.time_s,
                matched_features=len(matches),
                inliers=len(inliers),
                mean_residual_px=float(mean_residual if math.isfinite(mean_residual) else -1.0),
                matrix=matrix,
            )
            reg_by_frame[ann.frame_idx] = reg

        px = ann.x * float(width - 1)
        py = ann.y * float(height - 1)
        same = patch_residual_same(prev, curr, px, py)
        warped = patch_residual_warped(prev, curr, reg.matrix, px, py) if reg.matrix is not None else None
        kind = ann.review_kind or "unclassified"
        stats = summary.setdefault(
            kind,
            {
                "count": 0.0,
                "have_model": 0.0,
                "same_sum": 0.0,
                "warped_sum": 0.0,
                "improved": 0.0,
                "same_score_sum": 0.0,
                "warped_score_sum": 0.0,
                "score_improved": 0.0,
                "have_score": 0.0,
                "feature_sum": 0.0,
                "inlier_sum": 0.0,
                "resid_sum": 0.0,
            },
        )
        stats["count"] += 1.0
        same_score = residual_local_score_same(prev, curr, px, py)
        warped_score = (
            residual_local_score_warped(prev, curr, reg.matrix, px, py)
            if reg.matrix is not None
            else None
        )
        if reg.matrix is not None and same is not None and warped is not None:
            stats["have_model"] += 1.0
            stats["same_sum"] += same
            stats["warped_sum"] += warped
            stats["feature_sum"] += reg.matched_features
            stats["inlier_sum"] += reg.inliers
            stats["resid_sum"] += max(reg.mean_residual_px, 0.0)
            if warped < same:
                stats["improved"] += 1.0
        if same_score is not None and warped_score is not None:
            stats["have_score"] += 1.0
            stats["same_score_sum"] += same_score
            stats["warped_score_sum"] += warped_score
            if warped_score < same_score:
                stats["score_improved"] += 1.0

    lines.append("")
    lines.append("By review_kind:")
    for kind, stats in sorted(summary.items()):
        count = int(stats["count"])
        have_model = int(stats["have_model"])
        if have_model == 0:
            lines.append(f"  {kind}: modeled 0/{count}")
            continue
        mean_same = stats["same_sum"] / stats["have_model"]
        mean_warped = stats["warped_sum"] / stats["have_model"]
        mean_features = stats["feature_sum"] / stats["have_model"]
        mean_inliers = stats["inlier_sum"] / stats["have_model"]
        mean_resid = stats["resid_sum"] / stats["have_model"]
        improve_rate = stats["improved"] / stats["have_model"]
        ratio = mean_warped / mean_same if mean_same > 1e-6 else 0.0
        line = (
            f"  {kind}: modeled {have_model}/{count}, "
            f"patch-MAD same {mean_same:.2f} -> warped {mean_warped:.2f} "
            f"(ratio {ratio:.3f}), improved {improve_rate:.1%}, "
            f"features {mean_features:.1f}, inliers {mean_inliers:.1f}, "
            f"fit-residual {mean_resid:.2f}px"
        )
        if stats["have_score"] > 0.0:
            mean_same_score = stats["same_score_sum"] / stats["have_score"]
            mean_warped_score = stats["warped_score_sum"] / stats["have_score"]
            score_improve = stats["score_improved"] / stats["have_score"]
            line += (
                f", local-score same {mean_same_score:.2f} -> warped {mean_warped_score:.2f} "
                f"(lower is more explained), improved {score_improve:.1%}"
            )
        lines.append(line)
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("video", type=Path)
    parser.add_argument("review_json", type=Path)
    parser.add_argument("--scan-zone", type=float, default=0.63)
    args = parser.parse_args()
    print(evaluate(args.video, args.review_json, args.scan_zone))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
