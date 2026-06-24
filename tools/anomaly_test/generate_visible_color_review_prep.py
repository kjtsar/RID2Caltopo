#!/usr/bin/env python3
"""Generate contact sheets for visible-color clips awaiting review."""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path
from typing import Any


DEFAULT_MANIFEST = Path("tools/anomaly_test/regression_suite_color_manifest.json")
DEFAULT_OUTPUT_DIR = Path("tools/anomaly_test/out/visible-color-review-prep")


def load_manifest(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def unreviewed_source_clips(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    reviewed_source_ids = {
        str(excerpt.get("source_clip_id", ""))
        for excerpt in manifest.get("excerpts", [])
        if excerpt.get("review_status") == "reviewed" and excerpt.get("source_clip_id")
    }
    return [
        source
        for source in manifest.get("source_clips", [])
        if source.get("id") and source.get("source_path") and source.get("id") not in reviewed_source_ids
    ]


def contact_sheet_plan(repo_root: Path, output_dir: Path, clip: dict[str, Any]) -> dict[str, Any]:
    clip_id = str(clip["id"])
    return {
        "id": clip_id,
        "label": str(clip.get("label", clip_id)),
        "video_path": repo_root / str(clip["source_path"]),
        "contact_sheet_path": output_dir / f"{clip_id}-contact.png",
        "metadata_path": output_dir / f"{clip_id}-metadata.json",
        "source_notes": str(clip.get("notes", "")),
    }


def run_json(command: list[str], repo_root: Path) -> dict[str, Any]:
    result = subprocess.run(command, cwd=repo_root, check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def write_clip_metadata(repo_root: Path, plan: dict[str, Any]) -> None:
    video_path = Path(plan["video_path"])
    metadata = run_json(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration:stream=width,height,avg_frame_rate,nb_frames",
            "-of",
            "json",
            str(video_path),
        ],
        repo_root,
    )
    Path(plan["metadata_path"]).write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    plan["metadata_summary"] = metadata_summary(metadata)


def parse_rate(value: Any) -> float | None:
    text = str(value or "")
    if "/" in text:
        numerator, denominator = text.split("/", 1)
        try:
            denominator_value = float(denominator)
            if denominator_value == 0.0:
                return None
            return float(numerator) / denominator_value
        except ValueError:
            return None
    try:
        return float(text)
    except ValueError:
        return None


def metadata_summary(metadata: dict[str, Any]) -> dict[str, Any]:
    streams = metadata.get("streams", [])
    stream = streams[0] if isinstance(streams, list) and streams else {}
    if not isinstance(stream, dict):
        stream = {}
    fmt = metadata.get("format", {})
    if not isinstance(fmt, dict):
        fmt = {}

    def int_or_none(value: Any) -> int | None:
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    def float_or_none(value: Any) -> float | None:
        try:
            return float(value)
        except (TypeError, ValueError):
            return None

    return {
        "duration_s": float_or_none(fmt.get("duration")),
        "width": int_or_none(stream.get("width")),
        "height": int_or_none(stream.get("height")),
        "fps": parse_rate(stream.get("avg_frame_rate")),
        "frame_count": int_or_none(stream.get("nb_frames")),
    }


def write_contact_sheet(repo_root: Path, plan: dict[str, Any], fps: float, columns: int, rows: int, width: int) -> None:
    video_path = Path(plan["video_path"])
    output_path = Path(plan["contact_sheet_path"])
    output_path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-i",
            str(video_path),
            "-vf",
            f"fps={fps:g},scale={width}:-1,tile={columns}x{rows}",
            "-frames:v",
            "1",
            str(output_path),
        ],
        cwd=repo_root,
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
    )


def write_report(report_path: Path, plans: list[dict[str, Any]]) -> None:
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "suite": "visible-color-review-prep",
        "pending_review_source_clip_ids": [str(plan["id"]) for plan in plans],
        "clips": [
            {
                "id": str(plan["id"]),
                "label": str(plan["label"]),
                "video_path": str(plan["video_path"]),
                "contact_sheet_path": str(plan["contact_sheet_path"]),
                "metadata_path": str(plan["metadata_path"]),
                "metadata_summary": plan.get("metadata_summary", {}),
                "review_status": "needs_review_excerpt",
                "review_action": (
                    "Inspect the contact sheet/video, select any target-bearing interval, "
                    "create a reviewed excerpt and review sidecar, then add it to the "
                    "realtime qualification gate."
                ),
                "source_notes": str(plan.get("source_notes", "")),
            }
            for plan in plans
        ],
    }
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--fps", type=float, default=1.0)
    parser.add_argument("--columns", type=int, default=5)
    parser.add_argument("--rows", type=int, default=4)
    parser.add_argument("--width", type=int, default=320)
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    manifest_path = args.manifest if args.manifest.is_absolute() else repo_root / args.manifest
    output_dir = args.output_dir if args.output_dir.is_absolute() else repo_root / args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    clips = unreviewed_source_clips(load_manifest(manifest_path))
    plans = [contact_sheet_plan(repo_root, output_dir, clip) for clip in clips]
    for plan in plans:
        write_contact_sheet(repo_root, plan, args.fps, args.columns, args.rows, args.width)
        write_clip_metadata(repo_root, plan)
    write_report(output_dir / "visible_color_review_prep_report.json", plans)

    print(f"Visible-color review prep: {len(plans)} pending clips")
    for plan in plans:
        print(f"  {plan['id']}: {plan['contact_sheet_path']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
