#!/usr/bin/env python3
"""Run app-visible visible-color realtime qualification cases.

This runner focuses on the reviewed Red clip and the tablet-profiling Red2
sample so realtime-default changes can be checked against repeatable host
evidence, not just one-off CSVs.
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import review_eval


REPO_ROOT = Path(__file__).resolve().parents[2]
COLOR_MANIFEST = Path("tools/anomaly_test/regression_suite_color_manifest.json")


@dataclass(frozen=True)
class DetectionRow:
    frame: int
    time_s: float
    algorithm: str
    cx_norm: float
    cy_norm: float
    box_w_norm: float
    box_h_norm: float
    weight: float


@dataclass(frozen=True)
class ClipCase:
    label: str
    video: Path
    time_start: float
    time_end: float | None
    review: Path | None = None
    manifest_excerpt_id: str | None = None


@dataclass(frozen=True)
class GateResult:
    passed: bool
    failures: list[str]


CASES = (
    ClipCase(
        label="red1-reviewed",
        video=Path("app/src/test/resources/vidcap/Red1.mp4"),
        time_start=0.0,
        time_end=5.1,
        review=Path("app/src/test/resources/vidcap/Red1.review.json"),
        manifest_excerpt_id="red1-opening-target-track",
    ),
    ClipCase(
        label="red2-reviewed",
        video=Path("app/src/test/resources/vidcap/Red2.mp4"),
        time_start=0.0,
        time_end=None,
        review=Path("tools/anomaly_test/reviews/Red2.review.json"),
        manifest_excerpt_id="red2-tablet-patio-target",
    ),
)


def detection_signature(csv_path: Path) -> list[tuple[str, ...]]:
    rows: list[tuple[str, ...]] = []
    with csv_path.open(newline="", encoding="utf-8") as handle:
        filtered = (line for line in handle if line.strip() and not line.startswith("#"))
        reader = csv.DictReader(filtered)
        for row in reader:
            rows.append(
                (
                    row["frame"],
                    row["time_s"],
                    row["algorithm"],
                    row["cx_norm"],
                    row["cy_norm"],
                    row["box_w_norm"],
                    row["box_h_norm"],
                    row["weight"],
                )
            )
    return rows


def load_detection_rows(csv_path: Path) -> list[DetectionRow]:
    rows: list[DetectionRow] = []
    with csv_path.open(newline="", encoding="utf-8") as handle:
        filtered = (line for line in handle if line.strip() and not line.startswith("#"))
        reader = csv.DictReader(filtered)
        for row in reader:
            rows.append(
                DetectionRow(
                    frame=int(row["frame"]),
                    time_s=float(row["time_s"]),
                    algorithm=row["algorithm"],
                    cx_norm=float(row["cx_norm"]),
                    cy_norm=float(row["cy_norm"]),
                    box_w_norm=float(row["box_w_norm"]),
                    box_h_norm=float(row["box_h_norm"]),
                    weight=float(row["weight"]),
                )
            )
    return rows


def candidate_outputs_match(left: Iterable[tuple[str, ...]], right: Iterable[tuple[str, ...]]) -> bool:
    return list(left) == list(right)


def summarize_red2_geometry(rows: list[DetectionRow]) -> dict[str, object]:
    if not rows:
        return {
            "box_rows": 0,
            "matches_expected_red2_patio_track": False,
        }
    first = rows[0]
    last = rows[-1]
    track_dx = last.cx_norm - first.cx_norm
    track_dy = last.cy_norm - first.cy_norm
    matches_expected = (
        abs(first.time_s - 3.34) <= 0.15
        and abs(first.cx_norm - 0.529) <= 0.02
        and abs(first.cy_norm - 0.448) <= 0.02
        and abs(last.cx_norm - 0.567) <= 0.02
        and abs(last.cy_norm - 0.451) <= 0.02
        and track_dx > 0.02
    )
    return {
        "box_rows": len(rows),
        "first_frame": first.frame,
        "first_time_s": first.time_s,
        "first_cx_norm": first.cx_norm,
        "first_cy_norm": first.cy_norm,
        "last_frame": last.frame,
        "last_time_s": last.time_s,
        "last_cx_norm": last.cx_norm,
        "last_cy_norm": last.cy_norm,
        "track_dx_norm": track_dx,
        "track_dy_norm": track_dy,
        "matches_expected_red2_patio_track": matches_expected,
    }


def run_harness(
    binary: Path,
    repo_root: Path,
    case: ClipCase,
    output_dir: Path,
    candidate_limit: int,
    run_label: str | None = None,
) -> dict[str, object]:
    suffix = f"candidates-{candidate_limit}"
    if run_label:
        suffix = f"{suffix}-{run_label}"
    run_dir = output_dir / case.label / suffix
    run_dir.mkdir(parents=True, exist_ok=True)
    csv_path = run_dir / "detections.csv"
    summary_path = run_dir / "summary.json"
    command = [
        str(binary),
        str((repo_root / case.video).resolve()),
        "--app-defaults",
        "--app-appearance",
        "color",
        "--app-display-output",
        "--app-color-target-candidates",
        str(candidate_limit),
        "--time-start",
        f"{case.time_start:.3f}",
        "--summary-json",
        str(summary_path),
        "-c",
        str(csv_path),
        "--no-video",
    ]
    if case.time_end is not None:
        command.extend(["--time-end", f"{case.time_end:.3f}"])
    subprocess.run(command, cwd=repo_root, check=True)
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    return {
        "candidate_limit": candidate_limit,
        "csv_path": str(csv_path),
        "summary_path": str(summary_path),
        "summary": summary,
        "signature": detection_signature(csv_path),
        "rows": load_detection_rows(csv_path),
    }


def score_review(repo_root: Path, case: ClipCase, csv_path: Path) -> dict[str, object] | None:
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


def compact_run(
    run: dict[str, object],
    repeated_runs: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    summary = run["summary"]
    assert isinstance(summary, dict)
    realtime_factors = [
        float(candidate_summary.get("realtime_factor", 0.0))
        for repeated_run in (repeated_runs or [run])
        for candidate_summary in [repeated_run["summary"]]
        if isinstance(candidate_summary, dict)
    ]
    best_realtime_factor = max(realtime_factors) if realtime_factors else 0.0
    return {
        "candidate_limit": run["candidate_limit"],
        "csv_path": run["csv_path"],
        "summary_path": run["summary_path"],
        "frame_count": summary.get("frame_count", 0),
        "realtime_factor": summary.get("realtime_factor", 0.0),
        "best_realtime_factor": best_realtime_factor,
        "realtime_factors": realtime_factors,
        "rescan_modes": summary.get("rescan_modes", {}),
        "box_row_count": len(run["signature"]),
    }


def select_primary_candidate_run(runs: list[dict[str, object]]) -> dict[str, object]:
    if not runs:
        raise ValueError("at least one candidate run is required")

    def realtime_factor(run: dict[str, object]) -> float:
        summary = run.get("summary", {})
        if not isinstance(summary, dict):
            return 0.0
        value = summary.get("realtime_factor", 0.0)
        return float(value) if isinstance(value, (int, float)) else 0.0

    return max(runs, key=realtime_factor)


def review_precision_recall(score: dict[str, object]) -> tuple[float, float]:
    precision = score.get("reviewed_precision", 0.0)
    recall = score.get("reviewed_recall", 0.0)
    return (
        float(precision) if isinstance(precision, (int, float)) else 0.0,
        float(recall) if isinstance(recall, (int, float)) else 0.0,
    )


def manifest_coverage(manifest_path: Path, cases: Iterable[ClipCase]) -> dict[str, object]:
    raw = json.loads(manifest_path.read_text(encoding="utf-8"))
    source_clip_ids = [
        str(source.get("id", ""))
        for source in raw.get("source_clips", [])
        if source.get("id")
    ]
    reviewed_excerpts = [
        excerpt
        for excerpt in raw.get("excerpts", [])
        if excerpt.get("review_status") == "reviewed" and excerpt.get("id")
    ]
    reviewed_ids = [str(excerpt.get("id", "")) for excerpt in reviewed_excerpts]
    reviewed_source_clip_ids = {
        str(excerpt.get("source_clip_id", ""))
        for excerpt in reviewed_excerpts
        if excerpt.get("source_clip_id")
    }
    covered_ids = sorted(
        case.manifest_excerpt_id
        for case in cases
        if case.manifest_excerpt_id is not None
    )
    missing_ids = sorted(set(reviewed_ids) - set(covered_ids))
    unreviewed_source_clip_ids = [
        source_id
        for source_id in source_clip_ids
        if source_id not in reviewed_source_clip_ids
    ]
    return {
        "manifest_path": str(manifest_path),
        "reviewed_excerpt_ids": reviewed_ids,
        "covered_reviewed_excerpt_ids": covered_ids,
        "missing_reviewed_excerpt_ids": missing_ids,
        "unreviewed_source_clip_ids": unreviewed_source_clip_ids,
    }


def evaluate_gate(report: dict[str, object]) -> GateResult:
    failures: list[str] = []
    coverage = report.get("manifest_coverage")
    if isinstance(coverage, dict):
        missing = coverage.get("missing_reviewed_excerpt_ids", [])
        if isinstance(missing, list):
            for excerpt_id in missing:
                failures.append(
                    "manifest: reviewed visible-color excerpt not covered by realtime gate: "
                    f"{excerpt_id}"
                )

    cases = report.get("cases", [])
    if not isinstance(cases, list):
        return GateResult(False, ["report: cases is missing"])

    for case in cases:
        if not isinstance(case, dict):
            failures.append("report: case entry is not an object")
            continue
        label = str(case.get("label", "unknown"))
        if not bool(case.get("candidate_outputs_identical", False)):
            failures.append(f"{label}: candidate limit 1 output differs from limit 4")
        if not bool(case.get("candidate_1_repeated_outputs_identical", True)):
            failures.append(f"{label}: repeated candidate limit 1 outputs differ")

        candidate_1 = case.get("candidate_1")
        if not isinstance(candidate_1, dict):
            failures.append(f"{label}: candidate limit 1 timing summary missing")
        else:
            realtime_value = candidate_1.get(
                "best_realtime_factor",
                candidate_1.get("realtime_factor", 0.0),
            )
            realtime_factor = (
                float(realtime_value)
                if isinstance(realtime_value, (int, float))
                else 0.0
            )
            if realtime_factor < 1.0:
                failures.append(
                    f"{label}: candidate limit 1 realtime {realtime_factor:.3f} below 1.000"
                )

        if label == "red1-reviewed":
            score = case.get("review_score")
            if not isinstance(score, dict):
                failures.append(f"{label}: review score missing")
            else:
                precision, recall = review_precision_recall(score)
                if precision < 1.0:
                    failures.append(f"{label}: reviewed precision {precision:.3f} below 1.000")
                if recall < 0.6:
                    failures.append(f"{label}: reviewed recall {recall:.3f} below 0.600")

        if label == "red2-reviewed":
            score = case.get("review_score")
            if not isinstance(score, dict):
                failures.append(f"{label}: review score missing")
            else:
                precision, recall = review_precision_recall(score)
                if precision < 1.0:
                    failures.append(f"{label}: reviewed precision {precision:.3f} below 1.000")
                if recall < 0.9:
                    failures.append(f"{label}: reviewed recall {recall:.3f} below 0.900")

        if label.startswith("red2"):
            geometry = case.get("red2_geometry")
            if not isinstance(geometry, dict):
                failures.append(f"{label}: Red2 geometry summary missing")
            elif not bool(geometry.get("matches_expected_red2_patio_track", False)):
                failures.append(f"{label}: Red2 patio-target geometry check failed")

    return GateResult(not failures, failures)


def run_case(binary: Path, repo_root: Path, case: ClipCase, output_dir: Path) -> dict[str, object]:
    one_cold = run_harness(binary, repo_root, case, output_dir, 1)
    four = run_harness(binary, repo_root, case, output_dir, 4)
    one_warm = run_harness(binary, repo_root, case, output_dir, 1, run_label="realtime-probe")
    one_runs = [one_cold, one_warm]
    one = select_primary_candidate_run(one_runs)
    one_signature = one["signature"]
    four_signature = four["signature"]
    assert isinstance(one_signature, list)
    assert isinstance(four_signature, list)
    csv_path = Path(str(one["csv_path"]))
    review_score = score_review(repo_root, case, csv_path)
    rows = one["rows"]
    assert isinstance(rows, list)
    return {
        "label": case.label,
        "video": str((repo_root / case.video).resolve()),
        "review": str((repo_root / case.review).resolve()) if case.review is not None else None,
        "candidate_outputs_identical": candidate_outputs_match(one_signature, four_signature),
        "candidate_1_repeated_outputs_identical": all(
            candidate_outputs_match(one_signature, run["signature"])
            for run in one_runs
        ),
        "candidate_1": compact_run(one, repeated_runs=one_runs),
        "candidate_4": compact_run(four),
        "review_score": review_score,
        "red2_geometry": summarize_red2_geometry(rows) if case.label.startswith("red2") else None,
    }


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
        default=Path("tools/anomaly_test/out/color-realtime-qualification"),
        help="Directory for per-case outputs and aggregate report",
    )
    parser.add_argument(
        "--fail-on-regression",
        action="store_true",
        help="Exit non-zero if candidate equivalence or Red clip capability gates fail",
    )
    args = parser.parse_args()

    repo_root = REPO_ROOT
    binary = args.binary if args.binary.is_absolute() else repo_root / args.binary
    output_dir = args.output_dir if args.output_dir.is_absolute() else repo_root / args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    report = {
        "suite": "color-realtime-qualification",
        "binary": str(binary.resolve()),
        "manifest_coverage": manifest_coverage(repo_root / COLOR_MANIFEST, CASES),
        "cases": [
            run_case(binary.resolve(), repo_root, case, output_dir)
            for case in CASES
        ],
    }
    report["all_candidate_outputs_identical"] = all(
        bool(case["candidate_outputs_identical"])
        for case in report["cases"]
    )
    gate = evaluate_gate(report)
    report["gate"] = {
        "passed": gate.passed,
        "failures": gate.failures,
    }
    report_path = output_dir / "color_realtime_qualification_report.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    print("\nColor realtime qualification summary\n")
    for case in report["cases"]:
        print(f"{case['label']}:")
        print(f"  candidate outputs identical: {case['candidate_outputs_identical']}")
        candidate_1 = case["candidate_1"]
        assert isinstance(candidate_1, dict)
        print(f"  candidate=1 realtime: {float(candidate_1['realtime_factor']):.2f}x")
        print(f"  candidate=1 box rows: {candidate_1['box_row_count']}")
        review_score = case.get("review_score")
        if isinstance(review_score, dict):
            precision, recall = review_precision_recall(review_score)
            print(
                "  review precision/recall: "
                f"{precision:.3f}/{recall:.3f}"
            )
        red2_geometry = case.get("red2_geometry")
        if isinstance(red2_geometry, dict):
            print(f"  Red2 geometry match: {red2_geometry['matches_expected_red2_patio_track']}")
    print(f"\nGate passed: {gate.passed}")
    for failure in gate.failures:
        print(f"  - {failure}")
    print(f"\nAggregate report: {report_path}")
    return 1 if args.fail_on_regression and not gate.passed else 0


if __name__ == "__main__":
    raise SystemExit(main())
