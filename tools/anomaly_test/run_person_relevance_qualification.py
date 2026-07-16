#!/usr/bin/env python3
"""Qualify Person Relevance from paired OFF and SHADOW host artifacts.

The runner deliberately consumes anomaly_video_test CSV/summary output instead
of decoding video itself. Person decisions come from a deterministic evidence
file, allowing host qualification before a production ML runtime is present.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import statistics
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import review_eval


SCHEMA_VERSION = 1
MAX_AVG_RATIO = 1.10
MAX_P95_RATIO = 1.20
MIN_REALTIME_FACTOR = 1.0
TIME_WINDOW_S = 0.10
REQUIRED_COUNTERS = (
    "offers",
    "admissions",
    "drops",
    "replaced",
    "stale",
    "evaluated",
    "published",
    "backend_failures",
)
REQUIRED_IDENTITY = (
    "model_name",
    "model_version",
    "model_sha256",
    "runtime",
    "backend",
)


class QualificationError(ValueError):
    pass


@dataclass(frozen=True)
class PersonDecision:
    frame: int
    time_s: float
    cx: float
    cy: float
    w: float
    h: float
    raw_score: float
    threshold: float
    status: str

    @property
    def accepted(self) -> bool:
        return self.status == "accepted" and self.raw_score >= self.threshold

    def contains(self, x: float, y: float) -> bool:
        return abs(self.cx - x) <= self.w * 0.5 and abs(self.cy - y) <= self.h * 0.5


def _finite_number(value: object, label: str, *, minimum: float | None = None) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise QualificationError(f"{label} must be a number")
    result = float(value)
    if not math.isfinite(result):
        raise QualificationError(f"{label} must be finite")
    if minimum is not None and result < minimum:
        raise QualificationError(f"{label} must be at least {minimum:g}")
    return result


def _integer(value: object, label: str, *, minimum: int = 0) -> int:
    number = _finite_number(value, label, minimum=float(minimum))
    if not number.is_integer():
        raise QualificationError(f"{label} must be an integer")
    return int(number)


def _resolve(base: Path, value: object, label: str) -> Path:
    if not isinstance(value, str) or not value.strip():
        raise QualificationError(f"{label} must be a non-empty path")
    path = Path(value)
    return path if path.is_absolute() else base / path


def load_json(path: Path, label: str) -> dict[str, object]:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise QualificationError(f"{label}: cannot read valid JSON from {path}: {exc}") from exc
    if not isinstance(raw, dict):
        raise QualificationError(f"{label}: JSON root must be an object")
    return raw


def detection_signature(path: Path) -> list[tuple[str, ...]]:
    fields = (
        "frame",
        "time_s",
        "algorithm",
        "cx_norm",
        "cy_norm",
        "box_w_norm",
        "box_h_norm",
        "weight",
    )
    try:
        with path.open(newline="", encoding="utf-8") as handle:
            reader = csv.DictReader(line for line in handle if line.strip() and not line.startswith("#"))
            if reader.fieldnames is None or any(field not in reader.fieldnames for field in fields):
                raise QualificationError(f"detection CSV {path} is missing required columns")
            return [tuple(row[field] for field in fields) for row in reader]
    except OSError as exc:
        raise QualificationError(f"cannot read detection CSV {path}: {exc}") from exc


def load_detections(path: Path, label: str) -> list[review_eval.Detection]:
    try:
        return review_eval.load_detections(path)
    except (OSError, ValueError, KeyError) as exc:
        raise QualificationError(f"{label}: cannot read valid detections from {path}: {exc}") from exc


def load_review(path: Path, label: str) -> list[review_eval.Annotation]:
    try:
        return review_eval.load_review(path)
    except (OSError, json.JSONDecodeError, ValueError, KeyError, TypeError) as exc:
        raise QualificationError(f"{label}: cannot read valid review from {path}: {exc}") from exc


def _timing(summary: dict[str, object], label: str) -> dict[str, float]:
    timing = summary.get("qualification_timing")
    if isinstance(timing, dict):
        avg = _finite_number(timing.get("avg_ms"), f"{label}.qualification_timing.avg_ms", minimum=0.0)
        p95 = _finite_number(timing.get("p95_ms"), f"{label}.qualification_timing.p95_ms", minimum=0.0)
        max_ms = (
            _finite_number(timing.get("max_ms"), f"{label}.qualification_timing.max_ms", minimum=0.0)
            if timing.get("max_ms") is not None else None
        )
    else:
        stage = summary.get("stage_timing")
        if not isinstance(stage, dict) or stage.get("compiled") is not True:
            raise QualificationError(f"{label}: required compiled playback timing is missing")
        avg = _finite_number(stage.get("avg_total_ms"), f"{label}.stage_timing.avg_total_ms", minimum=0.0)
        max_ms = _finite_number(stage.get("max_total_ms"), f"{label}.stage_timing.max_total_ms", minimum=0.0)
        by_mode = stage.get("by_rescan_mode")
        if not isinstance(by_mode, dict):
            raise QualificationError(f"{label}: stage_timing.by_rescan_mode is missing")
        populated_p95: list[float] = []
        for mode_name, mode in by_mode.items():
            if not isinstance(mode, dict):
                raise QualificationError(f"{label}: timing mode {mode_name} is malformed")
            count = _integer(mode.get("frame_count"), f"{label}.{mode_name}.frame_count")
            if count > 0:
                populated_p95.append(
                    _finite_number(mode.get("p95_total_ms"), f"{label}.{mode_name}.p95_total_ms", minimum=0.0)
                )
        if not populated_p95:
            raise QualificationError(f"{label}: no populated rescan mode exposes p95 timing")
        p95 = max(populated_p95)
    realtime = _finite_number(summary.get("realtime_factor"), f"{label}.realtime_factor", minimum=0.0)
    result = {"avg_ms": avg, "p95_ms": p95, "realtime_factor": realtime}
    if max_ms is not None:
        result["max_ms"] = max_ms
    return result


def _model_identity(raw: object, label: str) -> dict[str, str]:
    if not isinstance(raw, dict):
        raise QualificationError(f"{label}: model_identity must be an object")
    identity: dict[str, str] = {}
    for field in REQUIRED_IDENTITY:
        value = raw.get(field)
        if not isinstance(value, str) or not value.strip():
            raise QualificationError(f"{label}: model_identity.{field} is required")
        identity[field] = value
    sha = identity["model_sha256"].lower()
    if len(sha) != 64 or any(char not in "0123456789abcdef" for char in sha):
        raise QualificationError(f"{label}: model_identity.model_sha256 must be 64 hexadecimal characters")
    identity["model_sha256"] = sha
    return identity


def load_evidence(path: Path, label: str) -> dict[str, object]:
    raw = load_json(path, label)
    if raw.get("schema_version") != 1 or raw.get("backend_kind") != "deterministic_fake":
        raise QualificationError(f"{label}: evidence must use schema 1 deterministic_fake backend")
    identity = _model_identity(raw.get("model_identity"), label)
    provenance_raw = raw.get("provenance")
    if not isinstance(provenance_raw, dict):
        raise QualificationError(f"{label}: provenance must be an object")
    fixture_id = provenance_raw.get("fixture_id")
    generator = provenance_raw.get("generator")
    source_detectors = provenance_raw.get("source_detectors")
    if not isinstance(fixture_id, str) or not fixture_id.strip():
        raise QualificationError(f"{label}: provenance.fixture_id is required")
    if not isinstance(generator, str) or not generator.strip():
        raise QualificationError(f"{label}: provenance.generator is required")
    if (
        not isinstance(source_detectors, list)
        or not source_detectors
        or any(not isinstance(value, str) or not value.strip() for value in source_detectors)
    ):
        raise QualificationError(f"{label}: provenance.source_detectors must be a non-empty string array")
    provenance = {
        "fixture_id": fixture_id,
        "generator": generator,
        "source_detectors": source_detectors,
    }
    counters_raw = raw.get("counters")
    if not isinstance(counters_raw, dict):
        raise QualificationError(f"{label}: counters are required")
    counters = {
        name: _integer(counters_raw.get(name), f"{label}.counters.{name}")
        for name in REQUIRED_COUNTERS
    }
    inference_raw = raw.get("inference_time_us")
    if not isinstance(inference_raw, list) or not inference_raw:
        raise QualificationError(f"{label}: inference_time_us must be a non-empty array")
    inference_us = [
        _finite_number(value, f"{label}.inference_time_us[{index}]", minimum=0.0)
        for index, value in enumerate(inference_raw)
    ]
    queue_age_raw = raw.get("queue_age_us")
    if not isinstance(queue_age_raw, list) or not queue_age_raw:
        raise QualificationError(f"{label}: queue_age_us must be a non-empty array")
    queue_age_us = [
        _finite_number(value, f"{label}.queue_age_us[{index}]", minimum=0.0)
        for index, value in enumerate(queue_age_raw)
    ]
    decisions_raw = raw.get("decisions")
    if not isinstance(decisions_raw, list):
        raise QualificationError(f"{label}: decisions must be an array")
    decisions: list[PersonDecision] = []
    canonical_decisions: list[dict[str, object]] = []
    valid_statuses = {"accepted", "below_threshold", "stale", "backend_failure"}
    for index, item in enumerate(decisions_raw):
        prefix = f"{label}.decisions[{index}]"
        if not isinstance(item, dict):
            raise QualificationError(f"{prefix} must be an object")
        box = item.get("box")
        if not isinstance(box, dict):
            raise QualificationError(f"{prefix}.box must be an object")
        status = item.get("status")
        if status not in valid_statuses:
            raise QualificationError(f"{prefix}.status is invalid")
        decision = PersonDecision(
            frame=_integer(item.get("frame"), f"{prefix}.frame"),
            time_s=_finite_number(item.get("time_s"), f"{prefix}.time_s", minimum=0.0),
            cx=_finite_number(box.get("cx_norm"), f"{prefix}.box.cx_norm", minimum=0.0),
            cy=_finite_number(box.get("cy_norm"), f"{prefix}.box.cy_norm", minimum=0.0),
            w=_finite_number(box.get("width_norm"), f"{prefix}.box.width_norm", minimum=0.0),
            h=_finite_number(box.get("height_norm"), f"{prefix}.box.height_norm", minimum=0.0),
            raw_score=_finite_number(item.get("raw_score"), f"{prefix}.raw_score", minimum=0.0),
            threshold=_finite_number(item.get("threshold"), f"{prefix}.threshold", minimum=0.0),
            status=str(status),
        )
        if any(value > 1.0 for value in (decision.cx, decision.cy, decision.w, decision.h, decision.raw_score, decision.threshold)):
            raise QualificationError(f"{prefix}: normalized geometry and scores must be within [0, 1]")
        if status == "accepted" and decision.raw_score < decision.threshold:
            raise QualificationError(f"{prefix}: accepted score is below threshold")
        if status == "below_threshold" and decision.raw_score >= decision.threshold:
            raise QualificationError(f"{prefix}: below-threshold score meets threshold")
        decisions.append(decision)
        canonical_decisions.append(item)
    expected_signature = raw.get("decision_signature_sha256")
    if not isinstance(expected_signature, str):
        raise QualificationError(f"{label}: decision_signature_sha256 is required")
    actual_signature = hashlib.sha256(
        json.dumps(canonical_decisions, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    if expected_signature.lower() != actual_signature:
        raise QualificationError(f"{label}: deterministic decision signature mismatch")
    return {
        "model_identity": identity,
        "provenance": provenance,
        "decision_signature_sha256": actual_signature,
        "counters": counters,
        "inference_time_us": inference_us,
        "queue_age_us": queue_age_us,
        "decisions": decisions,
        "first_person_relevance_latency_ms": _finite_number(
            raw.get("first_person_relevance_latency_ms"),
            f"{label}.first_person_relevance_latency_ms",
            minimum=0.0,
        ),
    }


def percentile(values: Iterable[float], fraction: float) -> float:
    ordered = sorted(values)
    if not ordered:
        raise QualificationError("percentile requires at least one sample")
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def _annotation_matched_by_detection(annotation: review_eval.Annotation, detections: list[review_eval.Detection]) -> bool:
    return any(
        abs(detection.time_s - annotation.time_s) <= TIME_WINDOW_S
        and detection.contains(annotation.x, annotation.y)
        for detection in detections
    )


def _annotation_matched_by_decision(annotation: review_eval.Annotation, decisions: list[PersonDecision]) -> bool:
    return any(
        decision.accepted
        and abs(decision.time_s - annotation.time_s) <= TIME_WINDOW_S
        and decision.contains(annotation.x, annotation.y)
        for decision in decisions
    )


def _positive_annotations(annotations: list[review_eval.Annotation]) -> list[review_eval.Annotation]:
    return [
        annotation for annotation in annotations
        if annotation.object_type.lower() == "person" and annotation.review_kind in review_eval.POSITIVE_KINDS
    ]


def _quality_metrics(
    annotations: list[review_eval.Annotation],
    off_detections: list[review_eval.Detection],
    shadow_detections: list[review_eval.Detection],
    decisions: list[PersonDecision],
    duration_s: float,
) -> dict[str, object]:
    positives = _positive_annotations(annotations)
    if not positives:
        raise QualificationError("review contains no positive person annotations")
    upstream = [annotation for annotation in positives if _annotation_matched_by_detection(annotation, off_detections)]
    person_hits = [annotation for annotation in upstream if _annotation_matched_by_decision(annotation, decisions)]
    end_to_end = [annotation for annotation in positives if _annotation_matched_by_decision(annotation, decisions)]
    app_visible = [
        annotation for annotation in end_to_end
        if _annotation_matched_by_detection(annotation, shadow_detections)
    ]
    accepted = [decision for decision in decisions if decision.accepted]
    unmatched = [
        decision for decision in accepted
        if not any(
            abs(decision.time_s - annotation.time_s) <= TIME_WINDOW_S
            and decision.contains(annotation.x, annotation.y)
            for annotation in positives
        )
    ]
    minutes = duration_s / 60.0
    return {
        "positive_person_annotations": len(positives),
        "upstream_candidate_hits": len(upstream),
        "upstream_candidate_recall": len(upstream) / len(positives),
        "candidate_conditioned_person_hits": len(person_hits),
        "candidate_conditioned_person_recall": (len(person_hits) / len(upstream) if upstream else None),
        "end_to_end_person_hits": len(end_to_end),
        "end_to_end_person_recall": len(end_to_end) / len(positives),
        "app_visible_person_hits": len(app_visible),
        "app_visible_person_recall": len(app_visible) / len(positives),
        "accepted_person_boxes": len(accepted),
        "unmatched_accepted_boxes": len(unmatched),
        "false_positives_per_minute": len(unmatched) / minutes,
        "unmatched_accepted_boxes_per_minute": len(unmatched) / minutes,
    }


def evaluate_case(case: dict[str, object], base: Path) -> tuple[dict[str, object], list[str]]:
    case_id = case.get("id")
    if not isinstance(case_id, str) or not case_id:
        raise QualificationError("case id is required")
    duration_s = _finite_number(case.get("duration_s"), f"{case_id}.duration_s", minimum=1e-9)
    review_path = _resolve(base, case.get("review"), f"{case_id}.review")
    pair: dict[str, dict[str, object]] = {}
    for mode in ("off", "shadow"):
        raw = case.get(mode)
        if not isinstance(raw, dict):
            raise QualificationError(f"{case_id}.{mode} must be an object")
        csv_path = _resolve(base, raw.get("detections_csv"), f"{case_id}.{mode}.detections_csv")
        summary_path = _resolve(base, raw.get("summary_json"), f"{case_id}.{mode}.summary_json")
        summary = load_json(summary_path, f"{case_id}.{mode}.summary")
        pair[mode] = {
            "detections_csv": str(csv_path),
            "summary_json": str(summary_path),
            "signature": detection_signature(csv_path),
            "detections": load_detections(csv_path, f"{case_id}.{mode}"),
            "timing": _timing(summary, f"{case_id}.{mode}"),
        }
    evidence_path = _resolve(base, case.get("person_evidence"), f"{case_id}.person_evidence")
    evidence = load_evidence(evidence_path, f"{case_id}.person_evidence")
    annotations = load_review(review_path, f"{case_id}.review")
    signatures_identical = pair["off"]["signature"] == pair["shadow"]["signature"]
    quality = _quality_metrics(
        annotations,
        pair["off"]["detections"],
        pair["shadow"]["detections"],
        evidence["decisions"],
        duration_s,
    )
    off_timing = pair["off"]["timing"]
    shadow_timing = pair["shadow"]["timing"]
    avg_ratio = shadow_timing["avg_ms"] / off_timing["avg_ms"] if off_timing["avg_ms"] > 0.0 else math.inf
    p95_ratio = shadow_timing["p95_ms"] / off_timing["p95_ms"] if off_timing["p95_ms"] > 0.0 else math.inf
    inference_us = evidence["inference_time_us"]
    queue_age_us = evidence["queue_age_us"]
    report = {
        "id": case_id,
        "duration_s": duration_s,
        "roi_signatures_identical": signatures_identical,
        "quality": quality,
        "person_runtime": {
            "model_identity": evidence["model_identity"],
            "provenance": evidence["provenance"],
            "decision_signature_sha256": evidence["decision_signature_sha256"],
            "counters": evidence["counters"],
            "first_person_relevance_latency_ms": evidence["first_person_relevance_latency_ms"],
            "inference_avg_ms": statistics.fmean(inference_us) / 1000.0,
            "inference_p95_ms": percentile(inference_us, 0.95) / 1000.0,
            "inference_max_ms": max(inference_us) / 1000.0,
            "queue_age_avg_ms": statistics.fmean(queue_age_us) / 1000.0,
            "queue_age_p95_ms": percentile(queue_age_us, 0.95) / 1000.0,
            "queue_age_max_ms": max(queue_age_us) / 1000.0,
        },
        "playback": {
            "off": off_timing,
            "shadow": shadow_timing,
            "avg_ratio": avg_ratio,
            "p95_ratio": p95_ratio,
        },
    }
    failures: list[str] = []
    if not signatures_identical:
        failures.append(f"{case_id}: OFF and SHADOW ROI signatures differ")
    if avg_ratio > MAX_AVG_RATIO:
        failures.append(f"{case_id}: SHADOW average playback is {avg_ratio:.3f}x OFF, above {MAX_AVG_RATIO:.2f}x")
    if p95_ratio > MAX_P95_RATIO:
        failures.append(f"{case_id}: SHADOW p95 playback is {p95_ratio:.3f}x OFF, above {MAX_P95_RATIO:.2f}x")
    if shadow_timing["realtime_factor"] < MIN_REALTIME_FACTOR:
        failures.append(
            f"{case_id}: SHADOW realtime factor {shadow_timing['realtime_factor']:.3f} is below {MIN_REALTIME_FACTOR:.1f}"
        )
    return report, failures


def run_qualification(manifest_path: Path) -> dict[str, object]:
    manifest = load_json(manifest_path, "manifest")
    if manifest.get("schema_version") != SCHEMA_VERSION:
        raise QualificationError(f"manifest.schema_version must be {SCHEMA_VERSION}")
    cases = manifest.get("cases")
    if not isinstance(cases, list) or not cases:
        raise QualificationError("manifest.cases must be a non-empty array")
    reports: list[dict[str, object]] = []
    failures: list[str] = []
    identities: set[tuple[str, ...]] = set()
    for raw_case in cases:
        if not isinstance(raw_case, dict):
            raise QualificationError("each manifest case must be an object")
        case_report, case_failures = evaluate_case(raw_case, manifest_path.parent)
        reports.append(case_report)
        failures.extend(case_failures)
        identity = case_report["person_runtime"]["model_identity"]
        identities.add(tuple(identity[field] for field in REQUIRED_IDENTITY))
    if len(identities) != 1:
        failures.append("suite: model identity differs between cases")
    return {
        "schema_version": SCHEMA_VERSION,
        "suite": str(manifest.get("suite_name") or "person-relevance-qualification"),
        "passed": not failures,
        "gates": {
            "roi_signatures_identical": True,
            "max_avg_ratio": MAX_AVG_RATIO,
            "max_p95_ratio": MAX_P95_RATIO,
            "min_realtime_factor": MIN_REALTIME_FACTOR,
        },
        "cases": reports,
        "failures": failures,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="Paired OFF/SHADOW qualification manifest")
    parser.add_argument("--output", type=Path, help="Write the JSON report to this path")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        report = run_qualification(args.manifest.resolve())
    except QualificationError as exc:
        report = {
            "schema_version": SCHEMA_VERSION,
            "suite": "person-relevance-qualification",
            "passed": False,
            "cases": [],
            "failures": [str(exc)],
        }
    output = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    else:
        print(output, end="")
    if not report["passed"]:
        for failure in report["failures"]:
            print(f"FAIL: {failure}", file=sys.stderr)
        return 1
    print(f"PASS: {len(report['cases'])} Person Relevance case(s)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
