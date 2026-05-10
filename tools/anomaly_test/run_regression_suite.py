#!/usr/bin/env python3
"""Run the reviewed anomaly regression suite and emit JSON + Markdown reports."""

from __future__ import annotations

import argparse
import json
import math
import subprocess
from pathlib import Path

from review_eval import POSITIVE_KINDS, load_review


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


def detector_args_use_color(detector_args: list[str]) -> bool:
    for idx, arg in enumerate(detector_args):
        if arg == "-a" and idx + 1 < len(detector_args):
            try:
                return (int(detector_args[idx + 1]) & 0x01) != 0
            except ValueError:
                return False
    return False


def write_color_target_csv(
    *,
    review_path: Path,
    out_path: Path,
    start_s: float | None,
    end_s: float | None,
) -> bool:
    annotations = load_review(review_path, start_s=start_s, end_s=end_s)
    positive_by_frame: dict[int, tuple[float, float, float]] = {}
    for ann in annotations:
        if ann.review_kind not in POSITIVE_KINDS:
            continue
        positive_by_frame.setdefault(ann.frame_idx, (ann.time_s, ann.x, ann.y))
    if not positive_by_frame:
        return False
    lines = ["# frame,time_s,x_norm,y_norm"]
    for frame_idx, (time_s, x_norm, y_norm) in sorted(positive_by_frame.items()):
        lines.append(f"{frame_idx},{time_s:.6f},{x_norm:.6f},{y_norm:.6f}")
    out_path.write_text("\n".join(lines) + "\n")
    return True


def format_ratio(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.3f}"


def format_seconds(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.3f}s"


def load_thermal_debug_jsonl(path: Path) -> list[dict]:
    frames: list[dict] = []
    with path.open() as handle:
        for line in handle:
            text = line.strip()
            if not text:
                continue
            frames.append(json.loads(text))
    return frames


def thermal_target_stage_sort_key(stage: str) -> tuple[int, str]:
    order = {
        "not_hot": 0,
        "suppressed_by_neighbor": 1,
        "merged_into_component": 2,
        "rejected_by_gate": 3,
        "extracted": 4,
        "none": 5,
    }
    return (order.get(stage, 99), stage)


def point_in_bbox(annotation: dict, candidate: dict) -> bool:
    return (
        candidate["bbox_left_norm"] <= annotation["x_norm"] <= candidate["bbox_right_norm"]
        and candidate["bbox_top_norm"] <= annotation["y_norm"] <= candidate["bbox_bottom_norm"]
    )


def candidate_distance(annotation: dict, candidate: dict) -> float:
    return math.hypot(candidate["x_norm"] - annotation["x_norm"], candidate["y_norm"] - annotation["y_norm"])


def summarize_thermal_telemetry(
    review_path: Path,
    telemetry_path: Path,
    start_s: float | None,
    end_s: float | None,
    detail_rows: list[dict],
    time_tolerance_s: float = 0.05,
) -> dict:
    annotations = load_review(review_path, start_s=start_s, end_s=end_s)
    miss_rows = [
        row for row in detail_rows
        if row["review_kind"] in POSITIVE_KINDS and row["outcome"] == "miss"
    ]
    telemetry_frames = load_thermal_debug_jsonl(telemetry_path)
    results: list[dict] = []
    extracted_near_target = 0
    no_candidate_frames = 0
    for miss in miss_rows:
        frame = min(
            telemetry_frames,
            key=lambda item: abs(item["time_s"] - miss["time_s"]),
            default=None,
        )
        if frame is None or abs(frame["time_s"] - miss["time_s"]) > time_tolerance_s:
            results.append(
                {
                    "time_s": miss["time_s"],
                    "x_norm": miss["x_norm"],
                    "y_norm": miss["y_norm"],
                    "review_kind": miss["review_kind"],
                    "scenario": miss["scenario"],
                    "outcome": "no_telemetry_frame",
                }
            )
            continue

        candidates = list(frame.get("candidates", []))
        winner = None
        winner_idx = int(frame.get("winning_candidate_index", -1))
        if 0 <= winner_idx < len(candidates):
            winner = candidates[winner_idx]
        elif candidates:
            winner = max(candidates, key=lambda item: item.get("final_score", -1.0))

        nearby = None
        nearby_inside = False
        nearby_distance = None
        if candidates:
            nearby = min(candidates, key=lambda item: candidate_distance(miss, item))
            nearby_distance = candidate_distance(miss, nearby)
            nearby_inside = point_in_bbox(miss, nearby)
            if nearby_inside or nearby_distance <= 0.035:
                extracted_near_target += 1
        else:
            no_candidate_frames += 1

        results.append(
            {
                "time_s": miss["time_s"],
                "x_norm": miss["x_norm"],
                "y_norm": miss["y_norm"],
                "review_kind": miss["review_kind"],
                "scenario": miss["scenario"],
                "note": miss.get("note", ""),
                "frame_time_s": frame["time_s"],
                "candidate_count": frame.get("candidate_count", 0),
                "winner": winner,
                "nearby": nearby,
                "nearby_contains_target": nearby_inside,
                "nearby_distance": nearby_distance,
            }
        )

    clusters: list[dict] = []
    sorted_results = sorted(
        [row for row in results if "candidate_count" in row],
        key=lambda item: item["time_s"],
    )
    current: dict | None = None
    for row in sorted_results:
        if current is None or row["time_s"] - current["end_s"] > 0.45:
            current = {
                "start_s": row["time_s"],
                "end_s": row["time_s"],
                "miss_count": 0,
                "target_extracted_count": 0,
                "winner_contains_target_count": 0,
                "candidate_less_frames": 0,
                "rows": [],
            }
            clusters.append(current)
        current["end_s"] = row["time_s"]
        current["miss_count"] += 1
        if row.get("nearby") is not None and (row["nearby_contains_target"] or (row["nearby_distance"] or 1.0) <= 0.035):
            current["target_extracted_count"] += 1
        if row.get("winner") is not None and point_in_bbox(row, row["winner"]):
            current["winner_contains_target_count"] += 1
        if row.get("candidate_count", 0) == 0:
            current["candidate_less_frames"] += 1
        current["rows"].append(row)

    lines = [
        f"# Dense Thermal Telemetry: {review_path.name}",
        "",
        f"- Missed positive annotations analyzed: {len(sorted_results)}",
        f"- Misses with no thermal candidates at all: {no_candidate_frames}",
        f"- Misses with a nearby extracted candidate: {extracted_near_target}",
        "",
        "## Miss Clusters",
        "",
    ]
    for cluster in clusters:
        lines.append(
            f"- {cluster['start_s']:.3f}s to {cluster['end_s']:.3f}s: "
            f"{cluster['miss_count']} misses, "
            f"target extracted nearby on {cluster['target_extracted_count']}, "
            f"winner covered target on {cluster['winner_contains_target_count']}, "
            f"candidate-less frames {cluster['candidate_less_frames']}"
        )
        for row in cluster["rows"][:6]:
            winner = row.get("winner")
            nearby = row.get("nearby")
            winner_text = (
                "none"
                if winner is None
                else f"xy=({winner['x_norm']:.3f},{winner['y_norm']:.3f}) "
                     f"score={winner['final_score']:.3f} area={winner['area']:.1f} span={winner['span']:.1f} "
                     f"peak={winner['peak_delta']:.2f} iso={winner['isolation_rank']:.2f}"
            )
            nearby_text = (
                "none"
                if nearby is None
                else f"xy=({nearby['x_norm']:.3f},{nearby['y_norm']:.3f}) "
                     f"d={row['nearby_distance']:.3f} inside={'Y' if row['nearby_contains_target'] else 'N'} "
                     f"score={nearby['final_score']:.3f} area={nearby['area']:.1f} span={nearby['span']:.1f} "
                     f"peak={nearby['peak_delta']:.2f} iso={nearby['isolation_rank']:.2f}"
            )
            lines.append(
                f"  miss t={row['time_s']:.3f}s target=({row['x_norm']:.3f},{row['y_norm']:.3f}) "
                f"winner={winner_text} nearby={nearby_text}"
            )
            if row.get("note"):
                lines.append(f"    note: {row['note']}")
        if len(cluster["rows"]) > 6:
            lines.append(f"  ... {len(cluster['rows']) - 6} more misses in cluster")
        lines.append("")

    return {
        "review_path": str(review_path),
        "telemetry_path": str(telemetry_path),
        "miss_count": len(sorted_results),
        "no_candidate_frames": no_candidate_frames,
        "nearby_extracted_count": extracted_near_target,
        "clusters": clusters,
        "misses": results,
        "markdown": "\n".join(lines).rstrip() + "\n",
    }


def collect_target_trace_for_misses(
    *,
    binary: Path,
    source_path: Path,
    excerpt_dir: Path,
    review_path: Path,
    detail_rows: list[dict],
    profile_args: list[str],
    extra_detector_args: list[str],
    excerpt_start_s: float | None,
    excerpt_end_s: float | None,
) -> dict:
    miss_rows = [
        row for row in detail_rows
        if row["review_kind"] in POSITIVE_KINDS and row["outcome"] == "miss"
    ]
    trace_dir = excerpt_dir / "thermal_target_traces"
    trace_dir.mkdir(parents=True, exist_ok=True)
    traces: list[dict] = []
    for index, miss in enumerate(miss_rows, start=1):
        miss_time = float(miss["time_s"])
        window_start = miss_time - 0.12
        window_end = miss_time + 0.12
        if excerpt_start_s is not None:
            window_start = max(window_start, float(excerpt_start_s))
        if excerpt_end_s is not None:
            window_end = min(window_end, float(excerpt_end_s))
        trace_jsonl = trace_dir / f"trace_{index:03d}.jsonl"
        trace_csv = trace_dir / f"trace_{index:03d}.csv"
        trace_summary = trace_dir / f"trace_{index:03d}_summary.json"
        cmd = [
            str(binary),
            str(source_path),
            "--no-video",
            "-c",
            str(trace_csv),
            "--summary-json",
            str(trace_summary),
            "--time-start",
            f"{window_start:.3f}",
            "--time-end",
            f"{window_end:.3f}",
            "--thermal-debug-jsonl",
            str(trace_jsonl),
            "--thermal-target",
            f"{float(miss['x_norm']):.6f},{float(miss['y_norm']):.6f}",
        ]
        cmd += profile_args
        cmd += extra_detector_args
        subprocess.run(cmd, check=True)
        frames = load_thermal_debug_jsonl(trace_jsonl)
        if not frames:
            traces.append(
                {
                    "time_s": miss_time,
                    "x_norm": miss["x_norm"],
                    "y_norm": miss["y_norm"],
                    "outcome": "no_trace_frames",
                }
            )
            continue
        frame = min(frames, key=lambda item: abs(item["time_s"] - miss_time))
        target = dict(frame.get("target", {}))
        target.update(
            {
                "time_s": miss_time,
                "frame_time_s": frame["time_s"],
                "x_norm": miss["x_norm"],
                "y_norm": miss["y_norm"],
                "review_kind": miss["review_kind"],
                "scenario": miss["scenario"],
                "note": miss.get("note", ""),
            }
        )
        traces.append(target)

    stage_counts: dict[str, int] = {}
    gate_counts: dict[str, int] = {}
    drop_counts = {
        "dropped_by_cap": 0,
        "dropped_by_nms": 0,
        "replaced_by_nms": 0,
    }
    for trace in traces:
        stage = str(trace.get("stage", "none"))
        stage_counts[stage] = stage_counts.get(stage, 0) + 1
        gate = str(trace.get("rejection_gate", "none"))
        if stage == "rejected_by_gate":
            gate_counts[gate] = gate_counts.get(gate, 0) + 1
        for key in drop_counts:
            if trace.get(key):
                drop_counts[key] += 1

    lines = [
        f"# Dense Thermal Target Trace: {review_path.name}",
        "",
        f"- Missed positive annotations rerun with target tracing: {len(traces)}",
        "",
        "## Stage Counts",
        "",
    ]
    for stage, count in sorted(stage_counts.items(), key=lambda item: thermal_target_stage_sort_key(item[0])):
        lines.append(f"- {stage}: {count}")
    if gate_counts:
        lines += ["", "## Rejection Gates", ""]
        for gate, count in sorted(gate_counts.items()):
            lines.append(f"- {gate}: {count}")
    if any(drop_counts.values()):
        lines += ["", "## Retention Loss", ""]
        lines.append(f"- dropped_by_cap: {drop_counts['dropped_by_cap']}")
        lines.append(f"- dropped_by_nms: {drop_counts['dropped_by_nms']}")
        lines.append(f"- replaced_by_nms: {drop_counts['replaced_by_nms']}")
    lines += ["", "## Sample Rows", ""]
    for trace in traces[:12]:
        lines.append(
            f"- t={float(trace['time_s']):.3f}s target=({float(trace['x_norm']):.3f},{float(trace['y_norm']):.3f}) "
            f"stage={trace.get('stage','none')} gate={trace.get('rejection_gate','none')} "
            f"hot={'Y' if trace.get('hot_eligible') else 'N'} local_max={'Y' if trace.get('local_max') else 'N'} "
            f"seed=({trace.get('component_seed_x', -1)},{trace.get('component_seed_y', -1)}) "
            f"peak=({trace.get('component_peak_x', -1)},{trace.get('component_peak_y', -1)}) "
            f"area={float(trace.get('component_area', 0.0)):.1f} span={float(trace.get('component_span', 0.0)):.1f} "
            f"quality={float(trace.get('component_quality', 0.0)):.2f} rank={int(trace.get('extracted_rank', -1))} "
            f"drop_cap={'Y' if trace.get('dropped_by_cap') else 'N'} "
            f"drop_nms={'Y' if trace.get('dropped_by_nms') else 'N'} "
            f"repl_nms={'Y' if trace.get('replaced_by_nms') else 'N'} "
            f"nms_rank={int(trace.get('nms_conflict_rank', -1))}"
        )
        if trace.get("note"):
            lines.append(f"  note: {trace['note']}")

    return {
        "review_path": str(review_path),
        "trace_dir": str(trace_dir),
        "miss_count": len(traces),
        "stage_counts": stage_counts,
        "gate_counts": gate_counts,
        "drop_counts": drop_counts,
        "traces": traces,
        "markdown": "\n".join(lines).rstrip() + "\n",
    }


def manifest_profiles(manifest: dict) -> list[dict]:
    profiles = manifest.get("profiles")
    if profiles:
        return profiles
    return [
        {
            "id": "default",
            "label": manifest.get("suite_name", "default"),
            "description": "Single-profile compatibility mode.",
            "detector_args": manifest.get("default_detector_args", []),
        }
    ]


def aggregate_profile_results(results: list[dict], reviewed_excerpts: int, pending_excerpts: int) -> dict:
    scored = [item for item in results if item["status"] == "scored"]
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
    return aggregate


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
    parser.add_argument(
        "--thermal-telemetry-profile",
        action="append",
        default=[],
        help="Profile id to emit dense thermal candidate telemetry for.",
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
    telemetry_profiles = set(args.thermal_telemetry_profile)

    source_clips = {clip["id"]: clip for clip in manifest.get("source_clips", [])}
    reviewed_excerpts = sum(1 for excerpt in manifest.get("excerpts", []) if excerpt["review_status"] == "reviewed")
    pending_excerpts = sum(1 for excerpt in manifest.get("excerpts", []) if excerpt["review_status"] != "reviewed")

    profile_reports: list[dict] = []
    for profile in manifest_profiles(manifest):
        profile_results: list[dict] = []
        profile_dir = out_dir / profile["id"]
        profile_dir.mkdir(parents=True, exist_ok=True)
        profile_args = profile.get("detector_args", [])

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
                excerpt_result["status"] = "pending_review"
                profile_results.append(excerpt_result)
                continue

            excerpt_dir = profile_dir / excerpt["id"]
            excerpt_dir.mkdir(parents=True, exist_ok=True)
            csv_path = excerpt_dir / "detections.csv"
            summary_path = excerpt_dir / "summary.json"
            thermal_telemetry_path = excerpt_dir / "thermal_debug.jsonl"
            color_debug_jsonl_path = excerpt_dir / "color_debug.jsonl"
            color_target_csv_path = excerpt_dir / "color_target.csv"
            review_path = resolve_path(repo_root, excerpt.get("review_path"))
            if review_path is None:
                raise RuntimeError(f"reviewed excerpt missing review path: {excerpt['id']}")
            color_debug_enabled = detector_args_use_color(profile_args)
            color_target_written = False
            if color_debug_enabled:
                color_target_written = write_color_target_csv(
                    review_path=review_path,
                    out_path=color_target_csv_path,
                    start_s=excerpt.get("start_s"),
                    end_s=excerpt.get("end_s"),
                )

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
            if profile["id"] in telemetry_profiles:
                cmd += ["--thermal-debug-jsonl", str(thermal_telemetry_path)]
            if color_debug_enabled:
                cmd += ["--color-debug-jsonl", str(color_debug_jsonl_path)]
                if color_target_written:
                    cmd += ["--color-target-csv", str(color_target_csv_path)]
            cmd += profile_args
            cmd += excerpt.get("extra_detector_args", [])
            subprocess.run(cmd, check=True)

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
            if color_debug_enabled:
                excerpt_result["color_debug_jsonl_path"] = str(color_debug_jsonl_path)
                if color_target_written:
                    excerpt_result["color_target_csv_path"] = str(color_target_csv_path)
            if profile["id"] in telemetry_profiles:
                thermal_summary = summarize_thermal_telemetry(
                    review_path=review_path,
                    telemetry_path=thermal_telemetry_path,
                    start_s=excerpt.get("start_s"),
                    end_s=excerpt.get("end_s"),
                    detail_rows=review_metrics["csv_results"][0]["score"]["details"],
                    time_tolerance_s=max(0.05, args.time_window),
                )
                thermal_summary_json = excerpt_dir / "thermal_debug_summary.json"
                thermal_summary_md = excerpt_dir / "thermal_debug_summary.md"
                thermal_summary_json.write_text(json.dumps(thermal_summary, indent=2) + "\n")
                thermal_summary_md.write_text(thermal_summary["markdown"])
                target_trace_summary = collect_target_trace_for_misses(
                    binary=binary,
                    source_path=source_path,
                    excerpt_dir=excerpt_dir,
                    review_path=review_path,
                    detail_rows=review_metrics["csv_results"][0]["score"]["details"],
                    profile_args=profile_args,
                    extra_detector_args=excerpt.get("extra_detector_args", []),
                    excerpt_start_s=excerpt.get("start_s"),
                    excerpt_end_s=excerpt.get("end_s"),
                )
                target_trace_summary_json = excerpt_dir / "thermal_target_trace_summary.json"
                target_trace_summary_md = excerpt_dir / "thermal_target_trace_summary.md"
                target_trace_summary_json.write_text(json.dumps(target_trace_summary, indent=2) + "\n")
                target_trace_summary_md.write_text(target_trace_summary["markdown"])
                excerpt_result["thermal_telemetry_path"] = str(thermal_telemetry_path)
                excerpt_result["thermal_telemetry_summary_json"] = str(thermal_summary_json)
                excerpt_result["thermal_telemetry_summary_md"] = str(thermal_summary_md)
                excerpt_result["thermal_target_trace_summary_json"] = str(target_trace_summary_json)
                excerpt_result["thermal_target_trace_summary_md"] = str(target_trace_summary_md)
            profile_results.append(excerpt_result)

        profile_reports.append(
            {
                "id": profile["id"],
                "label": profile.get("label", profile["id"]),
                "description": profile.get("description", ""),
                "detector_args": profile_args,
                "aggregate": aggregate_profile_results(profile_results, reviewed_excerpts, pending_excerpts),
                "excerpts": profile_results,
            }
        )

    report = {
        "manifest": str(manifest_path),
        "suite_name": manifest.get("suite_name", manifest_path.stem),
        "profiles": profile_reports,
    }
    report_json.write_text(json.dumps(report, indent=2) + "\n")

    lines = [
        f"# {report['suite_name']}",
        "",
        "## Profiles",
        "",
        "",
    ]
    for profile in profile_reports:
        aggregate = profile["aggregate"]
        lines += [
            f"### {profile['label']}",
            "",
        ]
        if profile["description"]:
            lines += [profile["description"], ""]
        lines += [
            f"- Detector args: `{' '.join(profile['detector_args'])}`",
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
        ]
        for item in profile["excerpts"]:
            if item["status"] != "scored":
                lines += [
                    f"#### {item['label']}",
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
                f"#### {item['label']}",
                "",
                f"- Source: `{item['source_clip_id']}` {item['start_s']}s to {item['end_s']}s",
                f"- TP: {score['true_positive_annotations']}/{score['positive_annotation_count']}",
                f"- FP: {score['false_positive_annotations']}/{score['negative_annotation_count']}",
                f"- Misses: {score['missed_annotations']}",
                f"- Precision: {format_ratio(score['reviewed_precision'])}",
                f"- Recall: {format_ratio(score['reviewed_recall'])}",
                f"- Track latency avg/p95/max: {format_seconds(score['latency_to_first_box_avg_s'])} / {format_seconds(score['latency_to_first_box_p95_s'])} / {format_seconds(score['latency_to_first_box_max_s'])}",
                f"- Runtime: {summary['analysis_wall_s']:.3f}s, realtime {summary['realtime_factor']:.3f}x ({summary['realtime_label']})",
            ]
            if item.get("color_debug_jsonl_path"):
                lines.append(f"- Color debug JSONL: `{item['color_debug_jsonl_path']}`")
            if item.get("thermal_telemetry_summary_md"):
                lines.append(f"- Thermal telemetry: `{item['thermal_telemetry_summary_md']}`")
            lines.append("")
    report_md.write_text("\n".join(lines) + "\n")

    print(f"Wrote {report_json}")
    print(f"Wrote {report_md}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
