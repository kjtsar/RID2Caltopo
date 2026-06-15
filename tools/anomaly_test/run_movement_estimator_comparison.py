#!/usr/bin/env python3
"""Compare legacy 2D registration with layered movement-estimator modes."""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
from dataclasses import dataclass
from pathlib import Path

import review_eval


MODES = ("legacy-affine", "layered-shadow", "layered-active")


@dataclass(frozen=True)
class Case:
    label: str
    video: Path
    time_start: float
    time_end: float
    args: tuple[str, ...]
    review: Path | None = None


CASES = (
    Case(
        label="powerhouse-team-ir",
        video=Path("app/src/test/resources/vidcap/PowerHouseTeam.mp4"),
        time_start=0.0,
        time_end=10.0,
        args=("--registration", "affine", "--stride", "1", "-p", "bh", "-a", "6", "-t", "2.8", "-m", "2", "-s", "0.80"),
        review=Path("app/src/test/resources/vidcap/PowerHouseTeam.review.json"),
    ),
    Case(
        label="powerhouse1-ir",
        video=Path("app/src/test/resources/vidcap/PowerHouse1.mp4"),
        time_start=0.0,
        time_end=4.8,
        args=("--registration", "affine", "--stride", "1", "-p", "bh", "-a", "6", "-t", "2.8", "-m", "2", "-s", "0.80"),
        review=Path("app/src/test/resources/vidcap/PowerHouse1.review.json"),
    ),
    Case(
        label="powerhouse2-ir",
        video=Path("app/src/test/resources/vidcap/PowerHouse2.mp4"),
        time_start=0.0,
        time_end=2.1,
        args=("--registration", "affine", "--stride", "1", "-p", "bh", "-a", "6", "-t", "2.8", "-m", "2", "-s", "0.80"),
        review=Path("app/src/test/resources/vidcap/PowerHouse2.review.json"),
    ),
    Case(
        label="powerhouse3-ir",
        video=Path("app/src/test/resources/vidcap/PowerHouse3.mp4"),
        time_start=0.0,
        time_end=4.1,
        args=("--registration", "affine", "--stride", "1", "-p", "bh", "-a", "6", "-t", "2.8", "-m", "2", "-s", "0.80"),
        review=Path("app/src/test/resources/vidcap/PowerHouse3.review.json"),
    ),
    Case(
        label="red1-visible",
        video=Path("app/src/test/resources/vidcap/Red1.mp4"),
        time_start=0.0,
        time_end=5.1,
        args=("--registration", "affine", "--stride", "1", "-a", "5", "-t", "2.8", "-m", "2", "-s", "0.80"),
        review=Path("app/src/test/resources/vidcap/Red1.review.json"),
    ),
)


def run_case(binary: Path, repo_root: Path, case: Case, mode: str, output_dir: Path) -> dict[str, object]:
    run_dir = output_dir / case.label / mode
    run_dir.mkdir(parents=True, exist_ok=True)
    summary_path = run_dir / "summary.json"
    csv_path = run_dir / "detections.csv"
    cmd = [
        str(binary),
        str((repo_root / case.video).resolve()),
        "--movement-estimator",
        mode,
        "--time-start",
        f"{case.time_start:.3f}",
        "--time-end",
        f"{case.time_end:.3f}",
        "--summary-json",
        str(summary_path),
        "-c",
        str(csv_path),
        "--no-video",
        *case.args,
    ]
    subprocess.run(cmd, check=True)
    summary = json.loads(summary_path.read_text())
    summary["_summary_path"] = str(summary_path)
    summary["_csv_path"] = str(csv_path)
    return summary


def detection_rows(csv_path: Path) -> list[tuple[str, ...]]:
    rows: list[tuple[str, ...]] = []
    with csv_path.open(newline="") as handle:
        for raw_line in handle:
            if raw_line.startswith("#"):
                continue
            parsed = next(csv.reader([raw_line]))
            if parsed and parsed[0] == "frame":
                continue
            if parsed:
                rows.append(tuple(parsed[:8]))
    return rows


def stage_ms(summary: dict[str, object], name: str) -> float:
    timing = summary.get("stage_timing", {})
    if not isinstance(timing, dict):
        return 0.0
    stages = timing.get("stages", {})
    if not isinstance(stages, dict):
        return 0.0
    stage = stages.get(name, {})
    if not isinstance(stage, dict):
        return 0.0
    value = stage.get("avg_ms", 0.0)
    return float(value) if isinstance(value, (int, float)) else 0.0


def metric(summary: dict[str, object], name: str) -> float:
    value = summary.get(name, 0.0)
    return float(value) if isinstance(value, (int, float)) else 0.0


def movement_metric(summary: dict[str, object], name: str) -> float:
    movement = summary.get("movement_estimator", {})
    if not isinstance(movement, dict):
        return 0.0
    value = movement.get(name, 0.0)
    return float(value) if isinstance(value, (int, float)) else 0.0


def avg_total_ms(summary: dict[str, object]) -> float:
    timing = summary.get("stage_timing", {})
    if not isinstance(timing, dict):
        return 0.0
    value = timing.get("avg_total_ms", 0.0)
    return float(value) if isinstance(value, (int, float)) else 0.0


def review_score(repo_root: Path, case: Case, csv_path: Path) -> dict[str, object] | None:
    if case.review is None:
        return None
    annotations = review_eval.load_review(
        repo_root / case.review,
        start_s=case.time_start,
        end_s=case.time_end,
    )
    detections = review_eval.load_detections(csv_path)
    return review_eval.score_review(
        annotations,
        detections,
        time_window_s=0.10,
        track_gap_s=0.35,
        track_join_radius=0.08,
    )


def review_metric(score: dict[str, object] | None, name: str) -> float:
    if score is None:
        return 0.0
    value = score.get(name, 0.0)
    return float(value) if isinstance(value, (int, float)) else 0.0


def pressure_metric(score: dict[str, object] | None, name: str) -> float:
    if score is None:
        return 0.0
    pressure = score.get("detection_pressure", {})
    if not isinstance(pressure, dict):
        return 0.0
    value = pressure.get(name, 0.0)
    return float(value) if isinstance(value, (int, float)) else 0.0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--binary",
        type=Path,
        default=Path("tools/anomaly_test/build_timing/anomaly_video_test"),
        help="Path to anomaly_video_test",
    )
    parser.add_argument("--output-dir", type=Path, default=None)
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    binary = args.binary if args.binary.is_absolute() else repo_root / args.binary
    binary = binary.resolve()
    if args.output_dir is not None:
        output_dir = args.output_dir.resolve()
    else:
        output_dir = repo_root / "tools/anomaly_test/out/movement-estimator-comparison"
    output_dir.mkdir(parents=True, exist_ok=True)

    summaries: dict[str, dict[str, dict[str, object]]] = {}
    for case in CASES:
        summaries[case.label] = {}
        for mode in MODES:
            print(f"\n== {case.label}: {mode} ==")
            summaries[case.label][mode] = run_case(binary, repo_root, case, mode, output_dir)
            csv_path = Path(str(summaries[case.label][mode]["_csv_path"]))
            summaries[case.label][mode]["_review_score"] = review_score(repo_root, case, csv_path)

    print("\nMovement estimator comparison\n")
    print(
        "case,mode,realtime,detect_frames,total_boxes,total_ms,move_ms,motion_ms,"
        "parallax_load,suppress,aoi_valid,aoi_independent,aoi_parallax,"
        "aoi_ind_score,review_precision,review_recall,review_tp,review_fp,"
        "review_pos,review_neg,review_missed,first_hit_s,first_box_avg_s,"
        "hit_dist_p50_norm,hit_dist_p90_norm,hit_dist_p90_px_1080p,"
        "hit_dist_max_norm,miss_dist_p50_norm,miss_dist_p90_norm,"
        "fp_dist_p50_norm,pressure_frames,pressure_events,pressure_area_p90,"
        "pressure_area_max,pressure_streak,off_target_events,off_target_streak,"
        "shadow_equal"
    )
    all_shadow_equal = True
    for case in CASES:
        legacy = summaries[case.label]["legacy-affine"]
        legacy_rows = detection_rows(Path(str(legacy["_csv_path"])))
        shadow_rows = detection_rows(Path(str(summaries[case.label]["layered-shadow"]["_csv_path"])))
        shadow_equal = legacy_rows == shadow_rows
        all_shadow_equal = all_shadow_equal and shadow_equal
        for mode in MODES:
            summary = summaries[case.label][mode]
            score = summary.get("_review_score")
            score = score if isinstance(score, dict) else None
            print(
                "{case},{mode},{realtime:.3f},{detections},{boxes},{total_ms:.3f},"
                "{move_ms:.3f},{motion_ms:.3f},{parallax:.3f},{suppress:.3f},"
                "{aoi_valid},{aoi_independent},{aoi_parallax},{aoi_score:.3f},"
                "{precision:.3f},{recall:.3f},{tp},{fp},{pos},{neg},{missed},"
                "{first_hit:.3f},{latency:.3f},{hit_p50:.4f},{hit_p90:.4f},"
                "{hit_p90_px:.2f},{hit_max:.4f},{miss_p50:.4f},{miss_p90:.4f},"
                "{fp_p50:.4f},{pressure_frames},{pressure_events},"
                "{pressure_area_p90:.4f},{pressure_area_max:.4f},"
                "{pressure_streak},{off_target_events},{off_target_streak},"
                "{shadow_equal}".format(
                    case=case.label,
                    mode=mode,
                    realtime=metric(summary, "realtime_factor"),
                    detections=int(metric(summary, "detection_frames")),
                    boxes=int(metric(summary, "total_boxes")),
                    total_ms=avg_total_ms(summary),
                    move_ms=stage_ms(summary, "movement_estimator"),
                    motion_ms=stage_ms(summary, "motion_scoring"),
                    parallax=movement_metric(summary, "avg_parallax_load"),
                    suppress=movement_metric(summary, "avg_parallax_suppression_scale"),
                    aoi_valid=int(movement_metric(summary, "aoi_valid_count")),
                    aoi_independent=int(movement_metric(summary, "aoi_independent_count")),
                    aoi_parallax=int(movement_metric(summary, "aoi_parallax_count")),
                    aoi_score=movement_metric(summary, "aoi_avg_independent_score"),
                    precision=review_metric(score, "reviewed_precision"),
                    recall=review_metric(score, "reviewed_recall"),
                    tp=int(review_metric(score, "true_positive_annotations")),
                    fp=int(review_metric(score, "false_positive_annotations")),
                    pos=int(review_metric(score, "positive_annotation_count")),
                    neg=int(review_metric(score, "negative_annotation_count")),
                    missed=int(review_metric(score, "missed_annotations")),
                    first_hit=review_metric(score, "first_hit_time_s_min"),
                    latency=review_metric(score, "latency_to_first_box_avg_s"),
                    hit_p50=review_metric(score, "hit_distance_p50_norm"),
                    hit_p90=review_metric(score, "hit_distance_p90_norm"),
                    hit_p90_px=review_metric(score, "hit_distance_p90_px_1080p"),
                    hit_max=review_metric(score, "hit_distance_max_norm"),
                    miss_p50=review_metric(score, "miss_distance_p50_norm"),
                    miss_p90=review_metric(score, "miss_distance_p90_norm"),
                    fp_p50=review_metric(score, "false_positive_distance_p50_norm"),
                    pressure_frames=int(pressure_metric(score, "box_frame_count")),
                    pressure_events=int(pressure_metric(score, "box_event_count")),
                    pressure_area_p90=pressure_metric(score, "box_area_p90_norm"),
                    pressure_area_max=pressure_metric(score, "box_area_max_norm"),
                    pressure_streak=int(pressure_metric(score, "max_box_frame_streak")),
                    off_target_events=int(pressure_metric(score, "off_target_box_event_count")),
                    off_target_streak=int(pressure_metric(score, "max_off_target_box_frame_streak")),
                    shadow_equal="yes" if shadow_equal else "NO",
                )
            )

    print(f"\nOutput directory: {output_dir}")
    if not all_shadow_equal:
        print("Shadow-mode detection equality failed.")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
