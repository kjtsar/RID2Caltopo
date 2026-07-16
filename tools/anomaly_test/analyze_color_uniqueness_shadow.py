#!/usr/bin/env python3
"""Aggregate diagnostic evidence for Color AD uniqueness shadow scoring."""

from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path

from review_eval import load_review
from run_regression_suite import (
    POSITIVE_KINDS,
    SHADOW_COMPONENT_FIELDS,
    load_thermal_debug_jsonl,
    shadow_invalid_reason,
    shadow_order_candidates,
    summarize_shadow_review_rows,
)


def distribution(values: list[float]) -> dict:
    if not values:
        return {"count": 0, "min": None, "max": None, "mean": None, "median": None}
    return {
        "count": len(values),
        "min": min(values),
        "max": max(values),
        "mean": statistics.fmean(values),
        "median": statistics.median(values),
    }


def review_details(review_path: Path, start_s: float | None = None, end_s: float | None = None) -> list[dict]:
    return [
        {
            "time_s": annotation.time_s,
            "x_norm": annotation.x,
            "y_norm": annotation.y,
            "review_kind": annotation.review_kind,
            "scenario": annotation.scenario,
            "note": annotation.note,
            "outcome": "review_context",
        }
        for annotation in load_review(review_path, start_s=start_s, end_s=end_s)
    ]


def aggregate_evidence(records: list[dict]) -> dict:
    candidate_count = 0
    valid_candidate_count = 0
    temporal_valid_count = 0
    complete_winner_frames = 0
    winner_agreement_count = 0
    component_values = {field.removeprefix("shadow_"): [] for field in SHADOW_COMPONENT_FIELDS}
    review_rows: list[dict] = []
    timing: list[dict] = []

    for record in records:
        frames = record["frames"]
        for frame in frames:
            candidates = list(frame.get("candidates", []))
            candidate_count += len(candidates)
            valid = [candidate for candidate in candidates if shadow_invalid_reason(candidate) is None]
            valid_candidate_count += len(valid)
            temporal_valid_count += sum(candidate.get("shadow_temporal_valid") is True for candidate in valid)
            for candidate in valid:
                for field in SHADOW_COMPONENT_FIELDS:
                    component_values[field.removeprefix("shadow_")].append(float(candidate[field]))
            if candidates and len(valid) == len(candidates):
                complete_winner_frames += 1
                production_index = int(frame.get("winning_candidate_index", -1))
                production_winner = next(
                    (candidate for candidate in candidates if int(candidate.get("index", -1)) == production_index),
                    candidates[0],
                )
                if shadow_order_candidates(candidates)[0] is production_winner:
                    winner_agreement_count += 1
        details = record.get("review_details", [])
        if details:
            review_rows.extend(summarize_shadow_review_rows(frames, details, 0.05)["reviewed_rows"])
        if record.get("timing"):
            timing.append(record["timing"])

    positive_lifts = [
        float(row["rank_delta"])
        for row in review_rows
        if row["review_class"] == "positive" and row["rank_delta"] is not None
    ]
    negative_lifts = [
        -float(row["rank_delta"])
        for row in review_rows
        if row["review_class"] == "negative" and row["rank_delta"] is not None
    ]
    opportunities = [
        row for row in review_rows
        if row["review_class"] == "positive"
        and row["production_rank"] not in (None, 1)
        and row["shadow_rank"] == 1
    ]
    missing_or_invalid = candidate_count == 0 or valid_candidate_count != candidate_count
    return {
        "diagnostic_only": True,
        "promotion_decision": None,
        "promotion_decision_reason": (
            "missing_or_invalid_shadow_evidence" if missing_or_invalid else "shadow_diagnostics_do_not_promote_scoring"
        ),
        "frame_count": sum(len(record["frames"]) for record in records),
        "candidate_count": candidate_count,
        "valid_shadow_candidate_count": valid_candidate_count,
        "valid_shadow_coverage": valid_candidate_count / candidate_count if candidate_count else None,
        "complete_winner_evidence_frames": complete_winner_frames,
        "production_shadow_winner_agreement_count": winner_agreement_count,
        "production_shadow_winner_agreement": (
            winner_agreement_count / complete_winner_frames if complete_winner_frames else None
        ),
        "positive_rank_lift": distribution(positive_lifts),
        "negative_rank_lift": distribution(negative_lifts),
        "first_reviewed_hit_opportunity": min(opportunities, key=lambda row: row["time_s"], default=None),
        "temporal_valid_rate": temporal_valid_count / valid_candidate_count if valid_candidate_count else None,
        "component_distributions": {
            component: distribution(values) for component, values in component_values.items()
        },
        "reviewed_rows": review_rows,
        "timing": timing,
    }


def records_from_suite_report(report_path: Path) -> list[dict]:
    report = json.loads(report_path.read_text())
    records: list[dict] = []
    for profile in report.get("profiles", []):
        for excerpt in profile.get("excerpts", []):
            telemetry = excerpt.get("color_debug_jsonl_path")
            if not telemetry:
                continue
            records.append(
                {
                    "frames": load_thermal_debug_jsonl(Path(telemetry)),
                    "review_details": excerpt.get("review_metrics", {}).get("details", []),
                    "timing": excerpt.get("summary"),
                }
            )
    return records


def markdown_report(report: dict) -> str:
    def ratio(value: float | None) -> str:
        return "n/a" if value is None else f"{value:.3f}"

    first = report["first_reviewed_hit_opportunity"]
    first_text = "none" if first is None else f"{float(first['time_s']):.3f}s"
    lines = [
        "# Color Uniqueness Shadow Analysis",
        "",
        "- Diagnostic only; production scoring and winner selection are unchanged.",
        f"- Valid shadow coverage: {report['valid_shadow_candidate_count']}/{report['candidate_count']} "
        f"({ratio(report['valid_shadow_coverage'])})",
        f"- Production/shadow winner agreement: {report['production_shadow_winner_agreement_count']}/"
        f"{report['complete_winner_evidence_frames']} ({ratio(report['production_shadow_winner_agreement'])})",
        f"- Positive rank lift mean: {ratio(report['positive_rank_lift']['mean'])}",
        f"- Negative rank lift mean: {ratio(report['negative_rank_lift']['mean'])}",
        f"- Temporal-valid rate: {ratio(report['temporal_valid_rate'])}",
        f"- First reviewed hit opportunity: {first_text}",
        f"- Promotion decision: none ({report['promotion_decision_reason']})",
        "",
        "## Component Distributions",
        "",
    ]
    for component, values in report["component_distributions"].items():
        lines.append(
            f"- {component}: n={values['count']} mean={ratio(values['mean'])} "
            f"median={ratio(values['median'])} min={ratio(values['min'])} max={ratio(values['max'])}"
        )
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("color_jsonl", nargs="*", type=Path)
    parser.add_argument("--review", type=Path)
    parser.add_argument("--summary-json", type=Path)
    parser.add_argument("--suite-report", type=Path)
    parser.add_argument("--output-json", type=Path)
    parser.add_argument("--output-md", type=Path)
    args = parser.parse_args()
    if args.suite_report:
        records = records_from_suite_report(args.suite_report)
    else:
        if not args.color_jsonl:
            parser.error("provide color JSONL paths or --suite-report")
        details = review_details(args.review) if args.review else []
        timing = json.loads(args.summary_json.read_text()) if args.summary_json else None
        records = [
            {
                "frames": load_thermal_debug_jsonl(path),
                "review_details": details,
                "timing": timing,
            }
            for path in args.color_jsonl
        ]
    report = aggregate_evidence(records)
    markdown = markdown_report(report)
    if args.output_json:
        args.output_json.write_text(json.dumps(report, indent=2) + "\n")
    else:
        print(json.dumps(report, indent=2))
    if args.output_md:
        args.output_md.write_text(markdown)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
