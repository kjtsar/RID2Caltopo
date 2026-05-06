#!/usr/bin/env python3
"""Run anomaly_video_test with GMV and affine registration and compare outputs.

Example:
  python3 tools/anomaly_test/compare_registration_backends.py \
      app/src/test/resources/vidcap/PowerHouse1.mp4 \
      app/src/test/resources/vidcap/PowerHouse1.review.json \
      -p bh -a 6 -t 2.8 -m 2 -s 0.6
"""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path


def run_backend(
    binary: Path,
    video: Path,
    backend: str,
    csv_path: Path,
    forwarded_args: list[str],
) -> None:
    cmd = [
        str(binary),
        str(video),
        "--no-video",
        "--registration",
        backend,
        "-c",
        str(csv_path),
        *forwarded_args,
    ]
    subprocess.run(cmd, check=True)


def run_review_eval(
    review_eval: Path,
    review_json: Path,
    csv_paths: list[Path],
    time_window: float,
) -> str:
    cmd = [
        "python3",
        str(review_eval),
        str(review_json),
        *(str(path) for path in csv_paths),
        "--time-window",
        f"{time_window:.3f}",
    ]
    return subprocess.check_output(cmd, text=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("video", type=Path)
    parser.add_argument("review_json", type=Path)
    parser.add_argument(
        "--binary",
        type=Path,
        default=Path("tools/anomaly_test/build/anomaly_video_test"),
        help="Path to anomaly_video_test binary",
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=None,
        help="Directory for generated CSVs (default: alongside the input video)",
    )
    parser.add_argument(
        "--time-window",
        type=float,
        default=0.10,
        help="Review matching window in seconds",
    )
    args, forwarded = parser.parse_known_args()

    video = args.video.resolve()
    review_json = args.review_json.resolve()
    binary = args.binary.resolve()
    out_dir = (args.out_dir.resolve() if args.out_dir is not None else video.parent)
    out_dir.mkdir(parents=True, exist_ok=True)

    stem = video.stem
    gmv_csv = out_dir / f"{stem}_gmv_detections.csv"
    affine_csv = out_dir / f"{stem}_affine_detections.csv"

    run_backend(binary, video, "gmv", gmv_csv, forwarded)
    run_backend(binary, video, "affine", affine_csv, forwarded)

    review_eval = Path(__file__).with_name("review_eval.py").resolve()
    print(
        run_review_eval(
            review_eval=review_eval,
            review_json=review_json,
            csv_paths=[gmv_csv, affine_csv],
            time_window=args.time_window,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
