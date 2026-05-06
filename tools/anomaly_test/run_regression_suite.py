#!/usr/bin/env python3
"""Run the reviewed anomaly regression suite and emit JSON + Markdown reports."""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path


def load_manifest(path: Path) -> dict:
    return json.loads(path.read_text())


def resolve_path(base: Path, value: str | None) -> Path | None:
    if value is None:
        return None
    path = Path(value)
    if path.is_absolute():
        return path
    return (base / path).resolve()


def run_review_eval(
    review_eval: Path,
    review_json: Path,
    detection_csv: Path,
    start_s: float | None,
    end_s: float | None,
    time_window_s: float,
) -> dict:
    cmd = [
        "python3",
        str(review_eval),
        str(review_json),
        str(detection_csv),
        "--time-window",
        f"{time_window_s:.3f}",
        "--json",
    ]
    if start_s is not None:
        cmd += ["--start-s", f"{start_s:.3f}"]
    if end_s is not None:
        cmd += ["--end-s", f"{end_s:.3f}"]
    raw = subprocess.check_output(cmd, text=True)
    return json.loads(raw)


def format_ratio(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.3f}"


def format_seconds(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.3f}s"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path("tools/anomaly_test/regression_suite_manifest.json"),
    )
    parser.add_argument(
        "--binary",
        type=Path,
        default=Path("tools/anomaly_test/build/anomaly_video_test"),
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=Path("tools/anomaly_test/out/regression"),
    )
    parser.add_argument(
        "--report-json",
        type=Path,
        default=None,
    )
    parser.add_argument(
        "--report-md",
        type=Path,
        default=None,
    )
    parser.add_argument(
        "--time-window",
        type=float,
        default=0.10,
    )
    args = parser.parse_args()

    repo_root = Path.cwd().resolve()
    manifest_path = args.manifest.resolve()
    binary = args.binary.resolve()
    out_dir = args.out_dir.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    report_json = (args.report_json.resolve() if args.report_json is not None else out_dir / "suite_report.json")
    report_md = (args.report_md.resolve() if args.report_md is not None else out_dir / "suite_report.md")

    manifest = load_manifest(manifest_path)
    review_eval = (manifest_path.parent / "review_eval.py").resolve()

    source_clips = {clip["id"]: clip for clip in manifest.get("source_clips", [])}
    suite_results: list[dict] = []
    reviewed_excerpts = 0
    pending_excerpts = 0

    for excerpt in manifest.get("excerpts", []):
        clip = source_clips[excerpt["source_clip_id"]]
        source_path = resolve_path(repo_root, clip["source_path"])
        if source_path is None:
            raise RuntimeError(f"missing source path for {clip['id']}")

        excerpt_result = {
            "id": excerpt["id"],
            "label": excerpt["label"],
            "source_clip_id": clip["id"],
            "source_path": str(source_path),
            "start_s": excerpt.get("start_s"),
            "end_s": excerpt.get("end_s"),
            "review_status": excerpt["review_status"],
            "notes": excerpt.get("notes", ""),
            "tags": excerpt.get("tags", []),
        }

        if excerpt["review_status"] != "reviewed":
            pending_excerpts += 1
            excerpt_result["status"] = "pending_review"
            suite_results.append(excerpt_result)
            continue

        reviewed_excerpts += 1
        excerpt_dir = out_dir / excerpt["id"]
        excerpt_dir.mkdir(parents=True, exist_ok=True)
        csv_path = excerpt_dir / "detections.csv"
        summary_path = excerpt_dir / "summary.json"

        cmd = [
            str(binary),
            str(source_path),
            "--no-video",
            "-c",
            str(csv_path),
            "--summary-json",
            str(summary_path),
        ]
        if excerpt.get("start_s") is not None:
            cmd += ["--time-start", f"{float(excerpt['start_s']):.3f}"]
        if excerpt.get("end_s") is not None:
            cmd += ["--time-end", f"{float(excerpt['end_s']):.3f}"]
        cmd += excerpt.get("detector_args", manifest.get("default_detector_args", []))
        subprocess.run(cmd, check=True)

        review_path = resolve_path(repo_root, excerpt.get("review_path"))
        if review_path is None:
            raise RuntimeError(f"reviewed excerpt missing review path: {excerpt['id']}")
        summary = json.loads(summary_path.read_text())
        review_metrics = run_review_eval(
            review_eval=review_eval,
            review_json=review_path,
            detection_csv=csv_path,
            start_s=excerpt.get("start_s"),
            end_s=excerpt.get("end_s"),
            time_window_s=args.time_window,
        )
        excerpt_result["status"] = "scored"
        excerpt_result["summary"] = summary
        excerpt_result["review_metrics"] = review_metrics["csv_results"][0]["score"]
        excerpt_result["review_path"] = str(review_path)
        excerpt_result["csv_path"] = str(csv_path)
        excerpt_result["summary_path"] = str(summary_path)
        suite_results.append(excerpt_result)

    scored = [item for item in suite_results if item["status"] == "scored"]
    aggregate = {
        "reviewed_excerpts": reviewed_excerpts,
        "pending_excerpts": pending_excerpts,
        "scored_excerpts": len(scored),
        "true_positive_annotations": sum(item["review_metrics"]["true_positive_annotations"] for item in scored),
        "false_positive_annotations": sum(item["review_metrics"]["false_positive_annotations"] for item in scored),
        "missed_annotations": sum(item["review_metrics"]["missed_annotations"] for item in scored),
        "positive_annotation_count": sum(item["review_metrics"]["positive_annotation_count"] for item in scored),
        "negative_annotation_count": sum(item["review_metrics"]["negative_annotation_count"] for item in scored),
        "matched_tracks": sum(item["review_metrics"]["matched_tracks"] for item in scored),
        "positive_tracks": sum(item["review_metrics"]["positive_tracks"] for item in scored),
        "analysis_wall_s": sum(item["summary"]["analysis_wall_s"] for item in scored),
        "media_span_s": sum(item["summary"]["media_span_s"] for item in scored),
    }
    if aggregate["positive_annotation_count"] > 0:
        aggregate["reviewed_recall"] = (
            aggregate["true_positive_annotations"] / aggregate["positive_annotation_count"]
        )
    else:
        aggregate["reviewed_recall"] = None
    if (aggregate["true_positive_annotations"] + aggregate["false_positive_annotations"]) > 0:
        aggregate["reviewed_precision"] = (
            aggregate["true_positive_annotations"]
            / (aggregate["true_positive_annotations"] + aggregate["false_positive_annotations"])
        )
    else:
        aggregate["reviewed_precision"] = None
    if aggregate["analysis_wall_s"] > 0.0 and aggregate["media_span_s"] > 0.0:
        aggregate["realtime_factor"] = aggregate["media_span_s"] / aggregate["analysis_wall_s"]
    else:
        aggregate["realtime_factor"] = None

    report = {
        "manifest": str(manifest_path),
        "suite_name": manifest.get("suite_name", manifest_path.stem),
        "aggregate": aggregate,
        "excerpts": suite_results,
    }
    report_json.write_text(json.dumps(report, indent=2) + "\n")

    lines = [
        f"# {report['suite_name']}",
        "",
        "## Aggregate",
        "",
        f"- Reviewed excerpts scored: {aggregate['scored_excerpts']}",
        f"- Pending excerpts: {aggregate['pending_excerpts']}",
        f"- TP annotations: {aggregate['true_positive_annotations']}/{aggregate['positive_annotation_count']}",
        f"- FP annotations: {aggregate['false_positive_annotations']}/{aggregate['negative_annotation_count']}",
        f"- Missed annotations: {aggregate['missed_annotations']}",
        f"- Reviewed precision: {format_ratio(aggregate['reviewed_precision'])}",
        f"- Reviewed recall: {format_ratio(aggregate['reviewed_recall'])}",
        f"- Track hits: {aggregate['matched_tracks']}/{aggregate['positive_tracks']}",
        f"- Runtime: {aggregate['analysis_wall_s']:.3f}s for {aggregate['media_span_s']:.3f}s of media",
        f"- Realtime factor: {format_ratio(aggregate['realtime_factor'])}x",
        "",
        "## Excerpts",
        "",
    ]
    for item in suite_results:
        if item["status"] != "scored":
            lines += [
                f"### {item['label']}",
                "",
                f"- Status: pending review",
                f"- Source: `{item['source_clip_id']}`",
                f"- Notes: {item['notes']}",
                "",
            ]
            continue
        score = item["review_metrics"]
        summary = item["summary"]
        lines += [
            f"### {item['label']}",
            "",
            f"- Source: `{item['source_clip_id']}` {item['start_s']}s to {item['end_s']}s",
            f"- TP: {score['true_positive_annotations']}/{score['positive_annotation_count']}",
            f"- FP: {score['false_positive_annotations']}/{score['negative_annotation_count']}",
            f"- Misses: {score['missed_annotations']}",
            f"- Precision: {format_ratio(score['reviewed_precision'])}",
            f"- Recall: {format_ratio(score['reviewed_recall'])}",
            f"- Track latency avg/p95/max: {format_seconds(score['latency_to_first_box_avg_s'])} / {format_seconds(score['latency_to_first_box_p95_s'])} / {format_seconds(score['latency_to_first_box_max_s'])}",
            f"- Runtime: {summary['analysis_wall_s']:.3f}s, realtime {summary['realtime_factor']:.3f}x ({summary['realtime_label']})",
            "",
        ]
    report_md.write_text("\n".join(lines) + "\n")

    print(f"Wrote {report_json}")
    print(f"Wrote {report_md}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
