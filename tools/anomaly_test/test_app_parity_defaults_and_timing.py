#!/usr/bin/env python3
"""Parity checks for app-facing anomaly defaults and timing telemetry."""

from __future__ import annotations

import re
import csv
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
KOTLIN_MODELS = ROOT / "app/src/main/java/org/ncssar/rid2caltopo/video/anomaly/AnomalyModels.kt"
APPLE_CONFIGURATION = ROOT / "apple/App/AppleAnomalySettingsView.swift"
APPLE_PARITY = ROOT / "apple/Sources/R2CCore/AnomalyConfigurationParity.swift"
HARNESS = ROOT / "tools/anomaly_test/anomaly_video_test.c"
ANALYSIS_HEADER = ROOT / "app/src/main/cpp/anomaly_analysis.h"
FFMPEG_BRIDGE = ROOT / "app/src/main/cpp/ffmpeg_bridge.c"
ANALYSIS = ROOT / "app/src/main/cpp/anomaly_analysis.c"
APP_GRADLE = ROOT / "app/build.gradle"
ANOMALY_VIDEO_TEST = ROOT / "tools/anomaly_test/build/anomaly_video_test"
RED2_VIDEO = ROOT / "app/src/test/resources/vidcap/Red2.mp4"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def kotlin_default_float(name: str) -> float:
    text = read(KOTLIN_MODELS)
    pattern = rf"val {name}: Float = ([0-9.]+)f"
    match = re.search(pattern, text)
    if not match:
        raise AssertionError(f"Missing Kotlin default {name}")
    return float(match.group(1))


def c_define_float(name: str) -> float:
    text = read(HARNESS)
    pattern = rf"#define {name} ([0-9.]+)f"
    match = re.search(pattern, text)
    if not match:
        raise AssertionError(f"Missing C define {name}")
    return float(match.group(1))


def run_red2_app_visible(candidate_limit: int, output_dir: Path) -> tuple[list[dict[str, str]], dict]:
    csv_path = output_dir / f"red2_candidates{candidate_limit}.csv"
    summary_path = output_dir / f"red2_candidates{candidate_limit}.summary.json"
    command = [
        str(ANOMALY_VIDEO_TEST),
        str(RED2_VIDEO),
        "--app-defaults",
        "--app-appearance",
        "color",
        "--app-display-output",
        "--app-color-target-candidates",
        str(candidate_limit),
        "--no-video",
        "-c",
        str(csv_path),
        "--summary-json",
        str(summary_path),
    ]
    subprocess.run(command, cwd=ROOT, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    with csv_path.open(newline="", encoding="utf-8") as handle:
        lines = [line for line in handle if line.strip() and not line.startswith("#")]
    with summary_path.open(encoding="utf-8") as handle:
        summary = json.load(handle)
    return list(csv.DictReader(lines)), summary


class AppParityDefaultsAndTimingTest(unittest.TestCase):
    def test_harness_sensitivity_default_matches_kotlin_app_default(self) -> None:
        self.assertAlmostEqual(
            kotlin_default_float("sensitivity"),
            c_define_float("APP_DEFAULT_SENSITIVITY"),
            places=4,
        )

    def test_apple_uniqueness_defaults_match_android_and_harness(self) -> None:
        apple = read(APPLE_CONFIGURATION)
        parity = read(APPLE_PARITY)
        kotlin = read(KOTLIN_MODELS)
        harness = read(HARNESS)

        for shared_default in (
            "defaultSensitivity = 0.59",
            "defaultMotionEvidenceSensitivity = 0.60",
            "defaultMinimumAreaFraction = 0.0015",
            "colorAdaptiveMinimumFrames = 30",
            "colorAdaptiveMaximumFrames = 60",
            "colorAdaptiveMaximumSeconds = 2.0",
        ):
            self.assertIn(shared_default, parity)
        for apple_default in (
            "var motionEnabled = true",
            "var saliencyEnabled = false",
            "var scanZone = 0.50",
            "var minimumHits = 2",
            "var thermalMinimumDelta = 10.0",
            "var smallTargetScreenFraction = 1.0 / 200.0",
            "var colorCandidateLimit = 1",
            "var targetColorMask = 0",
        ):
            self.assertIn(apple_default, apple)
        for android_default in (
            "val scanZone: Float = 0.50f",
            "val minHits: Int = 2",
            "val thermalMinDelta: Float = 10.0f",
            "val smallTargetScreenFraction: Float = 1.0f / 200.0f",
            "val colorTargetCandidateLimit: Int = 1",
            "val targetColorFamilyMask: Int = 0",
        ):
            self.assertIn(android_default, kotlin)
        for harness_default in (
            "#define APP_DEFAULT_SCAN_ZONE 0.50f",
            "#define APP_DEFAULT_MIN_HITS 2",
            "#define APP_DEFAULT_THERMAL_MIN_DELTA 10.0f",
            "#define APP_DEFAULT_SMALL_TARGET_SCREEN_FRACTION (1.0f / 200.0f)",
            "#define APP_DEFAULT_COLOR_TARGET_CANDIDATE_LIMIT 1",
        ):
            self.assertIn(harness_default, harness)

    def test_timing_summary_reports_min_values(self) -> None:
        harness = read(HARNESS)
        self.assertIn("min_total_ms", harness)
        self.assertIn("min_ms", harness)

    def test_timing_summary_groups_by_rescan_mode(self) -> None:
        harness = read(HARNESS)
        self.assertIn('\\"by_rescan_mode\\"', harness)
        for mode in ('\\"full\\"', '\\"partial\\"', '\\"target_only\\"', '\\"appearance_stride_skip\\"'):
            self.assertIn(mode, harness)
        for field in ('\\"frame_count\\"', '\\"avg_total_ms\\"', '\\"min_total_ms\\"', '\\"max_total_ms\\"'):
            self.assertIn(field, harness)

    def test_color_timing_has_perfetto_ready_substages(self) -> None:
        header = read(ANALYSIS_HEADER)
        for stage in (
            "ANOMALY_TIMING_STAGE_COLOR_SEED_SCORING",
            "ANOMALY_TIMING_STAGE_COLOR_BLOB_EXTRACTION",
            "ANOMALY_TIMING_STAGE_COLOR_CANDIDATE_RANKING",
        ):
            self.assertIn(stage, header)

    def test_app_default_color_candidate_limit_matches_kotlin(self) -> None:
        models = read(KOTLIN_MODELS)
        harness = read(HARNESS)
        self.assertIn("val colorTargetCandidateLimit: Int = 1", models)
        self.assertIn("APP_DEFAULT_COLOR_TARGET_CANDIDATE_LIMIT 1", harness)
        self.assertIn("native_cfg->color_target_candidate_limit", harness)

    def test_color_realtime_defaults_use_two_second_refresh_window(self) -> None:
        models = read(KOTLIN_MODELS)
        harness = read(HARNESS)
        bridge = read(FFMPEG_BRIDGE)

        self.assertIn("COLOR_REALTIME_ADAPTIVE_MAX_STRIDE_SECONDS = 2.0f", models)
        self.assertIn("APP_COLOR_REALTIME_ADAPTIVE_MAX_STRIDE_SECONDS 2.0f", harness)
        self.assertIn("APP_COLOR_REALTIME_ADAPTIVE_MAX_STRIDE_FRAMES 60", harness)
        self.assertIn("COLOR_REALTIME_ADAPTIVE_MAX_STRIDE_FRAMES", models)
        self.assertIn("adaptive_max_frames > 120", bridge)

    def test_app_debug_build_publishes_ad_stage_timing_to_perfetto(self) -> None:
        gradle = read(APP_GRADLE)
        self.assertRegex(
            gradle,
            r"debug\s*\{[\s\S]*arguments\s+\"-DANOMALY_DEBUG_TIMING=ON\"",
        )
        self.assertRegex(
            gradle,
            r"release\s*\{[\s\S]*arguments\s+\"-DANOMALY_DEBUG_TIMING=OFF\"",
        )

        bridge = read(FFMPEG_BRIDGE)
        self.assertIn("trace_set_anomaly_stage_timing_counters", bridge)
        self.assertIn("RID2C ad_stage_total_us", bridge)
        self.assertIn("RID2C ad_stage_%s_us", bridge)
        for stage_name in ("cseed", "cblob", "crank", "color", "sample", "solve"):
            self.assertIn(stage_name, bridge)

    def test_local_ad_prediction_only_cadence_runs_before_queue_admission(self) -> None:
        bridge = read(FFMPEG_BRIDGE)
        cadence_pos = bridge.find("local_ad_prequeue_prediction_only")
        enqueue_pos = bridge.find("sidecar_enqueued = enqueue_local_ad_input_best_effort_locked(")
        self.assertGreater(cadence_pos, 0)
        self.assertGreater(enqueue_pos, 0)
        self.assertLess(cadence_pos, enqueue_pos)
        self.assertIn("local_ad_cadence_frame_counter", bridge)
        self.assertIn("local_ad_cadence_ordinal", bridge)
        self.assertIn("packet.local_ad_cadence_ordinal", bridge)
        self.assertIn("RID2C local_ad_prequeue_prediction_only", bridge)
        self.assertIn("RID2C local_ad_full_scan_due", bridge)

    def test_local_ad_target_eval_without_tracks_stays_prediction_only(self) -> None:
        bridge = read(FFMPEG_BRIDGE)
        self.assertIn("anomaly_target_revisit_track_count", bridge)
        self.assertIn("local_ad_target_eval_has_no_revisit_work", bridge)
        self.assertIn("pthread_mutex_trylock(&session->anomaly_lock)", bridge)
        self.assertIn("RID2C local_ad_no_target_prediction_only", bridge)
        self.assertIn("bypass_for_local_cadence = true", bridge)

        budget = read(ROOT / "app/src/main/cpp/anomaly_runtime_budget.c")
        self.assertIn("full_scan_due", budget)
        self.assertIn("frame_stride_override = 1", budget)
        self.assertIn("suppress_implicit_full_refresh_stride", budget)

    def test_fresh_color_seed_rarity_uses_histogram_lut(self) -> None:
        analysis = read(ANALYSIS)
        self.assertIn("color_hist_rarity_lut", analysis)
        self.assertIn("color_hist_rarity_lut[hist_key]", analysis)
        self.assertIn("color_hist_rarity_lut[i] = anomaly_color_score_hist_rarity", analysis)

    def test_release_verification_runs_color_realtime_qualification_gate(self) -> None:
        gradle = read(APP_GRADLE)
        self.assertIn('tasks.register("colorRealtimeQualification")', gradle)
        self.assertRegex(
            gradle,
            r'dependsOn\("testDebugUnitTest", "trackerCoordinationTests", "colorRealtimeQualification"\)',
        )
        self.assertIn("run_color_realtime_qualification.py", gradle)
        self.assertIn("--target-color-perf-probe", gradle)
        self.assertIn("--fail-on-regression", gradle)

    def test_color_realtime_qualification_builds_host_harness(self) -> None:
        gradle = read(APP_GRADLE)
        self.assertIn('tasks.register("buildAnomalyVideoTest")', gradle)
        self.assertIn('dependsOn("buildAnomalyVideoTest")', gradle)
        self.assertIn("cmake", gradle)
        self.assertIn("--target", gradle)
        self.assertIn("anomaly_video_test", gradle)
        self.assertIn("target_color_perf_probe", gradle)
        self.assertNotIn("Skipping colorRealtimeQualification", gradle)

    def test_red2_realtime_candidate_limit_one_matches_four_candidate_output(self) -> None:
        if not ANOMALY_VIDEO_TEST.exists():
            self.skipTest(f"{ANOMALY_VIDEO_TEST} is not built")
        with tempfile.TemporaryDirectory() as tmp:
            rows_one, summary_one = run_red2_app_visible(1, Path(tmp))
            rows_four, summary_four = run_red2_app_visible(4, Path(tmp))

        self.assertEqual(rows_one, rows_four)
        self.assertGreater(float(summary_one["realtime_factor"]), 0.0)
        self.assertGreater(float(summary_four["realtime_factor"]), 0.0)
        self.assertGreaterEqual(len(rows_one), 100)

        first = rows_one[0]
        last = rows_one[-1]
        self.assertAlmostEqual(float(first["time_s"]), 3.34, delta=0.10)
        self.assertAlmostEqual(float(first["cx_norm"]), 0.529, delta=0.015)
        self.assertAlmostEqual(float(first["cy_norm"]), 0.448, delta=0.015)
        self.assertAlmostEqual(float(last["cx_norm"]), 0.567, delta=0.015)
        self.assertAlmostEqual(float(last["cy_norm"]), 0.451, delta=0.015)
        self.assertGreater(float(last["cx_norm"]), float(first["cx_norm"]))


if __name__ == "__main__":
    unittest.main()
