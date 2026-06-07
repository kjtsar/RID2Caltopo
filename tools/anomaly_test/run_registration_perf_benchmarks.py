#!/usr/bin/env python3
"""Run the fixed registration-first harness benchmark set.

This keeps the first optimization wave centered on the agreed benchmark cases:
  - PowerHouseTeam, affine, current defaults (`scan_zone=0.80`)
  - PowerHouseTeam, affine, comparison variant (`scan_zone=0.60`)
  - PowerHouse1, affine, current defaults (`scan_zone=0.80`)
  - PowerHouse1 opening window guardrail (`1.0s-4.0s`, `scan_zone=0.60`)

Each run uses `anomaly_video_test --summary-json` and prints a compact report
covering throughput, registration timing, planner timing, and registration
validity posture.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class BenchmarkCase:
    label: str
    video: Path
    registration: str
    scan_zone: float
    time_start: float
    time_end: float


CASES = (
    BenchmarkCase(
        label="PowerHouseTeam-affine-s0.80",
        video=Path("app/src/test/resources/vidcap/PowerHouseTeam.mp4"),
        registration="affine",
        scan_zone=0.80,
        time_start=0.0,
        time_end=10.0,
    ),
    BenchmarkCase(
        label="PowerHouseTeam-affine-s0.60",
        video=Path("app/src/test/resources/vidcap/PowerHouseTeam.mp4"),
        registration="affine",
        scan_zone=0.60,
        time_start=0.0,
        time_end=10.0,
    ),
    BenchmarkCase(
        label="PowerHouse1-affine-s0.80",
        video=Path("app/src/test/resources/vidcap/PowerHouse1.mp4"),
        registration="affine",
        scan_zone=0.80,
        time_start=0.0,
        time_end=4.8,
    ),
    BenchmarkCase(
        label="PowerHouse1-opening-affine-s0.60",
        video=Path("app/src/test/resources/vidcap/PowerHouse1.mp4"),
        registration="affine",
        scan_zone=0.60,
        time_start=1.0,
        time_end=4.0,
    ),
)


def build_command(
    binary: Path,
    case: BenchmarkCase,
    summary_json: Path,
    csv_path: Path,
    forwarded_args: list[str],
) -> list[str]:
    return [
        str(binary),
        str(case.video),
        "--registration",
        case.registration,
        "--time-start",
        f"{case.time_start:.3f}",
        "--time-end",
        f"{case.time_end:.3f}",
        "--summary-json",
        str(summary_json),
        "-c",
        str(csv_path),
        "--no-video",
        "--stride",
        "1",
        "-p",
        "bh",
        "-a",
        "6",
        "-t",
        "2.8",
        "-m",
        "2",
        "-s",
        f"{case.scan_zone:.2f}",
        *forwarded_args,
    ]


def dominant_reason(reason_counts: dict[str, int]) -> str:
    best_name = "none"
    best_count = 0
    for name, count in reason_counts.items():
        if name in {"none", "debug-input-unavailable"}:
            continue
        if count > best_count:
            best_name = name
            best_count = count
    return best_name


def stage_avg_ms(summary: dict[str, object], stage_name: str) -> float:
    stage_timing = summary.get("stage_timing", {})
    if not isinstance(stage_timing, dict):
        return 0.0
    stages = stage_timing.get("stages", {})
    if not isinstance(stages, dict):
        return 0.0
    stage = stages.get(stage_name, {})
    if not isinstance(stage, dict):
        return 0.0
    value = stage.get("avg_ms", 0.0)
    return float(value) if isinstance(value, (int, float)) else 0.0


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
        help="Optional directory for emitted summaries and CSVs",
    )
    args, forwarded = parser.parse_known_args()

    repo_root = Path(__file__).resolve().parents[2]
    binary = args.binary.resolve()
    output_dir = args.output_dir.resolve() if args.output_dir is not None else None
    if output_dir is not None:
        output_dir.mkdir(parents=True, exist_ok=True)

    results: list[tuple[BenchmarkCase, dict[str, object], Path, Path]] = []
    with tempfile.TemporaryDirectory(prefix="registration-perf-bench-") as tmpdir:
        tmp_root = Path(tmpdir)
        for case in CASES:
            video = (repo_root / case.video).resolve()
            if not video.exists():
                raise FileNotFoundError(f"missing video: {video}")
            run_root = output_dir if output_dir is not None else tmp_root
            summary_json = run_root / f"{case.label}_summary.json"
            csv_path = run_root / f"{case.label}_detections.csv"
            cmd = build_command(
                binary=binary,
                case=BenchmarkCase(
                    label=case.label,
                    video=video,
                    registration=case.registration,
                    scan_zone=case.scan_zone,
                    time_start=case.time_start,
                    time_end=case.time_end,
                ),
                summary_json=summary_json,
                csv_path=csv_path,
                forwarded_args=forwarded,
            )
            print(f"\n== Running {case.label} ==")
            subprocess.run(cmd, check=True)
            results.append((case, json.loads(summary_json.read_text()), summary_json, csv_path))

    print("\nRegistration performance benchmark summary\n")
    for case, summary, summary_json, csv_path in results:
        frame_count = int(summary.get("frame_count", 0))
        realtime_factor = float(summary.get("realtime_factor", 0.0))
        reg_invalid = int(dict(summary.get("scan_reason_counts", {})).get("reg-invalid", 0))
        reg_reason = dominant_reason(dict(summary.get("registration_reason_counts", {})))
        rescan_modes = dict(summary.get("rescan_modes", {}))
        print(case.label)
        print(f"  realtime:      {realtime_factor:.2f}x")
        print(f"  frames:        {frame_count}")
        print(f"  reg-invalid:   {reg_invalid}/{frame_count}")
        print(
            "  rescan modes:  full={full} partial={partial} target_only={target_only}".format(
                full=int(rescan_modes.get("full", 0)),
                partial=int(rescan_modes.get("partial", 0)),
                target_only=int(rescan_modes.get("target_only", 0)),
            )
        )
        print(f"  reg reason:    {reg_reason}")
        print(f"  avg total ms:  {float(summary.get('stage_timing', {}).get('avg_total_ms', 0.0)):.2f}")
        print(f"  reg prep ms:   {stage_avg_ms(summary, 'registration_prep'):.2f}")
        print(f"  reg solve ms:  {stage_avg_ms(summary, 'registration_solve'):.2f}")
        print(f"  thermal ms:    {stage_avg_ms(summary, 'thermal_scoring'):.2f}")
        print(f"  plan ms:       {stage_avg_ms(summary, 'scan_planning'):.2f}")
        print(f"  mask ms:       {stage_avg_ms(summary, 'refresh_mask_build'):.2f}")
        print(f"  summary:       {summary_json}")
        print(f"  csv:           {csv_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
