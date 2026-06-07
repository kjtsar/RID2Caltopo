#!/usr/bin/env python3
"""Run the focused registration starvation experiments and summarize results.

This keeps the current handoff's four key replays in one place:
  - PowerHouseTeam 0.0s-10.0s with affine
  - PowerHouseTeam 0.0s-10.0s with gmv
  - PowerHouse1    0.0s-4.8s  with affine
  - PowerHouse1    0.0s-4.8s  with gmv

It shells out to anomaly_video_test, captures --summary-json output, and
prints a compact comparison report focused on registration validity and
rescan-mode mix.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Experiment:
    label: str
    video: Path
    registration: str
    time_start: float
    time_end: float


EXPERIMENTS = (
    Experiment(
        label="PowerHouseTeam-affine",
        video=Path("app/src/test/resources/vidcap/PowerHouseTeam.mp4"),
        registration="affine",
        time_start=0.0,
        time_end=10.0,
    ),
    Experiment(
        label="PowerHouseTeam-gmv",
        video=Path("app/src/test/resources/vidcap/PowerHouseTeam.mp4"),
        registration="gmv",
        time_start=0.0,
        time_end=10.0,
    ),
    Experiment(
        label="PowerHouse1-affine",
        video=Path("app/src/test/resources/vidcap/PowerHouse1.mp4"),
        registration="affine",
        time_start=0.0,
        time_end=4.8,
    ),
    Experiment(
        label="PowerHouse1-gmv",
        video=Path("app/src/test/resources/vidcap/PowerHouse1.mp4"),
        registration="gmv",
        time_start=0.0,
        time_end=4.8,
    ),
)


def build_command(
    binary: Path,
    experiment: Experiment,
    summary_json: Path,
    csv_path: Path,
    forwarded_args: list[str],
) -> list[str]:
    return [
        str(binary),
        str(experiment.video),
        "--registration",
        experiment.registration,
        "--time-start",
        f"{experiment.time_start:.3f}",
        "--time-end",
        f"{experiment.time_end:.3f}",
        "--summary-json",
        str(summary_json),
        "-c",
        str(csv_path),
        "--no-video",
        *forwarded_args,
    ]


def dominant_reason(reason_counts: dict[str, int]) -> str:
    best_name = "none"
    best_count = 0
    for name, count in reason_counts.items():
        if count > best_count and name not in {"none", "debug-input-unavailable"}:
            best_name = name
            best_count = count
    return best_name


def format_ratio(numerator: int, denominator: int) -> str:
    if denominator <= 0:
        return "0/0"
    pct = 100.0 * float(numerator) / float(denominator)
    return f"{numerator}/{denominator} ({pct:.1f}%)"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--binary",
        type=Path,
        default=Path("tools/anomaly_test/build/anomaly_video_test"),
        help="Path to anomaly_video_test",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=None,
        help="Optional directory for summaries and CSVs",
    )
    args, forwarded = parser.parse_known_args()

    repo_root = Path(__file__).resolve().parents[2]
    binary = args.binary.resolve()
    output_dir = args.output_dir.resolve() if args.output_dir is not None else None
    if output_dir is not None:
        output_dir.mkdir(parents=True, exist_ok=True)

    summaries: list[tuple[Experiment, dict[str, object], Path, Path]] = []
    with tempfile.TemporaryDirectory(prefix="focused-reg-exp-") as tmpdir:
        tmp_root = Path(tmpdir)
        for experiment in EXPERIMENTS:
            video = (repo_root / experiment.video).resolve()
            if not video.exists():
                raise FileNotFoundError(f"missing video: {video}")

            run_root = output_dir if output_dir is not None else tmp_root
            summary_json = run_root / f"{experiment.label}_summary.json"
            csv_path = run_root / f"{experiment.label}_detections.csv"

            cmd = build_command(
                binary=binary,
                experiment=Experiment(
                    label=experiment.label,
                    video=video,
                    registration=experiment.registration,
                    time_start=experiment.time_start,
                    time_end=experiment.time_end,
                ),
                summary_json=summary_json,
                csv_path=csv_path,
                forwarded_args=forwarded,
            )
            print(f"\n== Running {experiment.label} ==")
            subprocess.run(cmd, check=True)
            summaries.append((experiment, json.loads(summary_json.read_text()), summary_json, csv_path))

    print("\nFocused registration summary\n")
    for experiment, summary, summary_json, csv_path in summaries:
        frame_count = int(summary.get("frame_count", 0))
        rescan_modes = dict(summary.get("rescan_modes", {}))
        scan_reason_counts = dict(summary.get("scan_reason_counts", {}))
        registration_reason_counts = dict(summary.get("registration_reason_counts", {}))
        reg_invalid = int(scan_reason_counts.get("reg-invalid", 0))
        full = int(rescan_modes.get("full", 0))
        partial = int(rescan_modes.get("partial", 0))
        target_only = int(rescan_modes.get("target_only", 0))
        realtime_factor = summary.get("realtime_factor")
        if isinstance(realtime_factor, (int, float)):
            realtime_text = f"{float(realtime_factor):.2f}x"
        else:
            realtime_text = "n/a"

        print(f"{experiment.label}")
        print(f"  frames:        {frame_count}")
        print(f"  reg-invalid:   {format_ratio(reg_invalid, frame_count)}")
        print(f"  rescan modes:  full={full} partial={partial} target_only={target_only}")
        print(f"  reg reason:    {dominant_reason(registration_reason_counts)}")
        print(f"  realtime:      {realtime_text}")
        print(f"  summary:       {summary_json}")
        print(f"  csv:           {csv_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
