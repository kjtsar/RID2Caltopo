#!/usr/bin/env python3
"""Run a fixed visible-color performance benchmark matrix.

This keeps color-path performance tracking on a stable, repeatable harness
instead of relying only on noisy in-app timing. The matrix tracks the legacy
coarse visible-light profile, the current app-parity dense stride profile, and
the dense every-frame comparison profile:

  - legacy coarse auto detail
  - app dense stride/defaults (`--app-defaults --app-appearance color`)
  - dense gold comparison (`--pixel-step 1` every frame)

Each profile runs over fixed 10s windows from Color1/2/3 and writes per-case
summary JSON plus one aggregate report JSON for before/after diffing.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class ClipCase:
    label: str
    video: Path
    time_start: float
    time_end: float


@dataclass(frozen=True)
class Profile:
    id: str
    label: str
    detector_args: tuple[str, ...]


CASES = (
    ClipCase(
        label="Color1-0.0s-10.0s",
        video=Path("app/src/test/resources/vidcap/Color1.mp4"),
        time_start=0.0,
        time_end=10.0,
    ),
    ClipCase(
        label="Color2-0.0s-10.0s",
        video=Path("app/src/test/resources/vidcap/Color2.mp4"),
        time_start=0.0,
        time_end=10.0,
    ),
    ClipCase(
        label="Color3-0.0s-10.0s",
        video=Path("app/src/test/resources/vidcap/Color3.mp4"),
        time_start=0.0,
        time_end=10.0,
    ),
)


PROFILES = (
    Profile(
        id="visible-color-legacy-auto",
        label="Visible color legacy auto detail",
        detector_args=(
            "-a", "1",
            "-t", "2.8",
            "-m", "2",
            "-s", "0.8",
            "--registration", "affine",
            "--stride", "1",
            "--small-target-fraction", "0.005",
        ),
    ),
    Profile(
        id="visible-color-app-dense-stride",
        label="Visible color app dense stride",
        detector_args=(
            "--app-defaults",
            "--app-appearance", "color",
            "--app-display-output",
        ),
    ),
    Profile(
        id="visible-color-dense-gold",
        label="Visible color dense gold",
        detector_args=(
            "-a", "1",
            "-t", "2.8",
            "-m", "2",
            "-s", "0.8",
            "--registration", "affine",
            "--stride", "1",
            "--small-target-fraction", "0.005",
            "--pixel-step", "1",
        ),
    ),
)


def stage_metric_ms(summary: dict[str, object], stage_name: str, metric_name: str) -> float:
    stage_timing = summary.get("stage_timing", {})
    if not isinstance(stage_timing, dict):
        return 0.0
    stages = stage_timing.get("stages", {})
    if not isinstance(stages, dict):
        return 0.0
    stage = stages.get(stage_name, {})
    if not isinstance(stage, dict):
        return 0.0
    value = stage.get(metric_name, 0.0)
    return float(value) if isinstance(value, (int, float)) else 0.0


def stage_avg_ms(summary: dict[str, object], stage_name: str) -> float:
    return stage_metric_ms(summary, stage_name, "avg_ms")


def stage_max_ms(summary: dict[str, object], stage_name: str) -> float:
    return stage_metric_ms(summary, stage_name, "max_ms")


def build_command(
    binary: Path,
    video: Path,
    profile: Profile,
    case: ClipCase,
    summary_json: Path,
    csv_path: Path,
    forwarded_args: list[str],
) -> list[str]:
    return [
        str(binary),
        str(video),
        "--time-start",
        f"{case.time_start:.3f}",
        "--time-end",
        f"{case.time_end:.3f}",
        "--summary-json",
        str(summary_json),
        "-c",
        str(csv_path),
        "--no-video",
        *profile.detector_args,
        *forwarded_args,
    ]


def aggregate_profile_metrics(results: list[dict[str, object]]) -> dict[str, float]:
    if not results:
        return {
            "case_count": 0.0,
            "avg_realtime_factor": 0.0,
            "avg_total_ms": 0.0,
            "max_total_ms": 0.0,
            "avg_sampled_grid_prep_ms": 0.0,
            "avg_color_scoring_ms": 0.0,
            "max_color_scoring_ms": 0.0,
            "avg_color_seed_scoring_ms": 0.0,
            "max_color_seed_scoring_ms": 0.0,
            "avg_color_blob_extraction_ms": 0.0,
            "max_color_blob_extraction_ms": 0.0,
            "avg_color_candidate_ranking_ms": 0.0,
            "max_color_candidate_ranking_ms": 0.0,
            "avg_scan_planning_ms": 0.0,
            "avg_refresh_mask_build_ms": 0.0,
        }
    count = float(len(results))
    return {
        "case_count": count,
        "avg_realtime_factor": sum(float(r["realtime_factor"]) for r in results) / count,
        "avg_total_ms": sum(float(r["avg_total_ms"]) for r in results) / count,
        "max_total_ms": max(float(r["max_total_ms"]) for r in results),
        "avg_sampled_grid_prep_ms": sum(float(r["sampled_grid_prep_ms"]) for r in results) / count,
        "avg_color_scoring_ms": sum(float(r["color_scoring_ms"]) for r in results) / count,
        "max_color_scoring_ms": max(float(r["max_color_scoring_ms"]) for r in results),
        "avg_color_seed_scoring_ms": sum(float(r["color_seed_scoring_ms"]) for r in results) / count,
        "max_color_seed_scoring_ms": max(float(r["max_color_seed_scoring_ms"]) for r in results),
        "avg_color_blob_extraction_ms": sum(float(r["color_blob_extraction_ms"]) for r in results) / count,
        "max_color_blob_extraction_ms": max(float(r["max_color_blob_extraction_ms"]) for r in results),
        "avg_color_candidate_ranking_ms": sum(float(r["color_candidate_ranking_ms"]) for r in results) / count,
        "max_color_candidate_ranking_ms": max(float(r["max_color_candidate_ranking_ms"]) for r in results),
        "avg_scan_planning_ms": sum(float(r["scan_planning_ms"]) for r in results) / count,
        "avg_refresh_mask_build_ms": sum(float(r["refresh_mask_build_ms"]) for r in results) / count,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--binary",
        type=Path,
        default=Path("tools/anomaly_test/build_timing/anomaly_video_test"),
        help="Path to anomaly_video_test",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=None,
        help="Optional directory for emitted summaries, CSVs, and aggregate report",
    )
    args, forwarded = parser.parse_known_args()

    repo_root = Path(__file__).resolve().parents[2]
    binary = args.binary.resolve()
    output_dir = args.output_dir.resolve() if args.output_dir is not None else None
    if output_dir is not None:
        output_dir.mkdir(parents=True, exist_ok=True)

    report: dict[str, object] = {
        "suite": "visible-color-perf-bench",
        "binary": str(binary),
        "profiles": [],
    }

    with tempfile.TemporaryDirectory(prefix="visible-color-perf-bench-") as tmpdir:
        tmp_root = Path(tmpdir)
        for profile in PROFILES:
            profile_results: list[dict[str, object]] = []
            print(f"\n== Profile: {profile.id} ==")
            for case in CASES:
                video = (repo_root / case.video).resolve()
                if not video.exists():
                    raise FileNotFoundError(f"missing video: {video}")

                run_root = output_dir if output_dir is not None else tmp_root
                stem = f"{profile.id}_{case.label}"
                summary_json = run_root / f"{stem}_summary.json"
                csv_path = run_root / f"{stem}_detections.csv"
                cmd = build_command(
                    binary=binary,
                    video=video,
                    profile=profile,
                    case=case,
                    summary_json=summary_json,
                    csv_path=csv_path,
                    forwarded_args=forwarded,
                )
                print(f"  -> {case.label}")
                subprocess.run(cmd, check=True)
                summary = json.loads(summary_json.read_text())
                stage_timing = summary.get("stage_timing", {})
                if not isinstance(stage_timing, dict):
                    stage_timing = {}
                result = {
                    "case": case.label,
                    "video": str(video),
                    "time_start": case.time_start,
                    "time_end": case.time_end,
                    "summary_json": str(summary_json),
                    "csv_path": str(csv_path),
                    "frame_count": int(summary.get("frame_count", 0)),
                    "realtime_factor": float(summary.get("realtime_factor", 0.0)),
                    "avg_total_ms": float(stage_timing.get("avg_total_ms", 0.0)),
                    "min_total_ms": float(stage_timing.get("min_total_ms", 0.0)),
                    "max_total_ms": float(stage_timing.get("max_total_ms", 0.0)),
                    "sampled_grid_prep_ms": stage_avg_ms(summary, "sampled_grid_prep"),
                    "color_scoring_ms": stage_avg_ms(summary, "color_scoring"),
                    "max_color_scoring_ms": stage_max_ms(summary, "color_scoring"),
                    "color_seed_scoring_ms": stage_avg_ms(summary, "color_seed_scoring"),
                    "max_color_seed_scoring_ms": stage_max_ms(summary, "color_seed_scoring"),
                    "color_blob_extraction_ms": stage_avg_ms(summary, "color_blob_extraction"),
                    "max_color_blob_extraction_ms": stage_max_ms(summary, "color_blob_extraction"),
                    "color_candidate_ranking_ms": stage_avg_ms(summary, "color_candidate_ranking"),
                    "max_color_candidate_ranking_ms": stage_max_ms(summary, "color_candidate_ranking"),
                    "scan_planning_ms": stage_avg_ms(summary, "scan_planning"),
                    "refresh_mask_build_ms": stage_avg_ms(summary, "refresh_mask_build"),
                }
                profile_results.append(result)

            aggregates = aggregate_profile_metrics(profile_results)
            report["profiles"].append(
                {
                    "id": profile.id,
                    "label": profile.label,
                    "detector_args": list(profile.detector_args),
                    "cases": profile_results,
                    "aggregates": aggregates,
                }
            )

    print("\nVisible color performance benchmark summary\n")
    for profile in report["profiles"]:
        print(profile["id"])
        aggregates = profile["aggregates"]
        print(f"  avg realtime:          {float(aggregates['avg_realtime_factor']):.2f}x")
        print(f"  avg total ms:          {float(aggregates['avg_total_ms']):.2f}")
        print(f"  max total ms:          {float(aggregates['max_total_ms']):.2f}")
        print(f"  avg sampled-grid ms:   {float(aggregates['avg_sampled_grid_prep_ms']):.2f}")
        print(f"  avg color-scoring ms:  {float(aggregates['avg_color_scoring_ms']):.2f}")
        print(f"  max color-scoring ms:  {float(aggregates['max_color_scoring_ms']):.2f}")
        print(f"  avg color seed ms:     {float(aggregates['avg_color_seed_scoring_ms']):.2f}")
        print(f"  avg color blob ms:     {float(aggregates['avg_color_blob_extraction_ms']):.2f}")
        print(f"  avg color ranking ms:  {float(aggregates['avg_color_candidate_ranking_ms']):.2f}")
        print(f"  avg scan-plan ms:      {float(aggregates['avg_scan_planning_ms']):.2f}")
        print(f"  avg refresh-mask ms:   {float(aggregates['avg_refresh_mask_build_ms']):.2f}")

    if output_dir is not None:
        report_path = output_dir / "visible_color_perf_report.json"
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        print(f"\nAggregate report: {report_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
