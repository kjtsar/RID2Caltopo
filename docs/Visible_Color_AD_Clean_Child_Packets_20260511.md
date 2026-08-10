# Visible Color AD Clean Child Packets (2026-05-11)

This document is a clean launch surface for the next visible-color threads.
It intentionally excludes older parent-ledger history, superseded experiment
queues, and mixed architectural guidance.

## Current Design Target

The intended fresh visible-color detector is:

- dense pixel-first for fresh / unlocked regions
- compact-blob only
- hard-exclude anything larger than the configured `Small Target` envelope
- score blob uniqueness from the most unique pixel inside the blob
- use 8-neighbor continuity for dense boundary growth
- treat AR-lock / revisit as a later optimization layer, not the primary
  blob-construction method

## Current Implementation Gap

The code does **not** implement that design yet.

What it still is today:

- coarse/support-peak-first with dense local verification

What is still missing:

- a true fresh dense-blob construction path
- 8-neighbor dense growth in that fresh path
- blob-level uniqueness anchored to the strongest unique pixel inside the blob

## Clean Packet Queue

### Child Packet: `child_dense_arch_analysis_v1`

Child id: `child_dense_arch_analysis_v1`
Role: `analysis`
Checkpoint: current tree state as of `2026-05-11`
Hypothesis: the fresh visible-color path can be split cleanly so fresh /
unlocked regions form dense blobs directly, while AR-lock / revisit remains a
later optimization layer
Write scope: read-only analysis of `app/src/main/cpp/anomaly_analysis.c/h`
Non-goals:
- do not edit repo-tracked files
- do not tune thresholds
- do not run the full regression or perf sweep
Required commands:
- `cd /Users/kjt/Projects/RID2Caltopo`
- `sed -n '1,220p' docs/Visible_Color_AD_Clean_Summary_20260511.md`
- `sed -n '2000,2275p' app/src/main/cpp/anomaly_analysis.c`
- `sed -n '3590,4065p' app/src/main/cpp/anomaly_analysis.c`
- `sed -n '11745,11830p' app/src/main/cpp/anomaly_analysis.c`
Validation ownership:
- read-only child; no mutation validation required
Adoption gate:
- produce a concrete implementation map with:
  - exact entry point for fresh dense blob construction
  - exact bypass point for the support-peak-first path
  - exact state/output that must still feed ranking/debugging
  - exact point where AR-lock / revisit re-enters
Artifact suffix:
- `child_dense_arch_analysis_v1`
Expected report format:
- attempted analysis
- exact code seam to change
- exact code seam to leave alone
- risks
- conclusion: launch `child_dense_growth_v1` as-is | refine packet first

### Child Packet: `child_dense_growth_v1`

Child id: `child_dense_growth_v1`
Role: `experiment`
Checkpoint: current tree state as of `2026-05-11`
Hypothesis: replacing fresh-mode coarse/support-peak-first blob construction
with dense fresh-region blob growth using 8-neighbor continuity will move the
implementation toward the intended architecture without yet changing uniqueness
scoring
Write scope: `app/src/main/cpp/anomaly_analysis.c`,
`app/src/main/cpp/anomaly_analysis.h`, and directly related native tests only
Non-goals:
- do not redesign AR-lock / revisit
- do not change winner-gate policy first
- do not change blob uniqueness scoring in this child
- do not broaden large-blob rescue behavior
Required commands:
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test`
- `cmake -B build`
- `cmake --build build`
- `./build/anomaly_test`
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/build`
- `ctest --output-on-failure`
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test`
- `cmake -B build_timing -DANOMALY_DEBUG_TIMING=ON`
- `cmake --build build_timing`
- `./build_timing/anomaly_test`
- `cd /Users/kjt/Projects/RID2Caltopo`
- `./gradlew :app:compileDebugKotlin`
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend legacy --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_legacy_summary_child_dense_growth_v1.json --color-debug-jsonl /tmp/red1_app_legacy_color_debug_child_dense_growth_v1.jsonl --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv -c /tmp/red1_app_legacy_detections_child_dense_growth_v1.csv`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_fresh_summary_child_dense_growth_v1.json --color-debug-jsonl /tmp/red1_app_fresh_color_debug_child_dense_growth_v1.jsonl --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv -c /tmp/red1_app_fresh_detections_child_dense_growth_v1.csv`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_fresh_notrace_summary_child_dense_growth_v1.json -c /tmp/red1_app_fresh_notrace_detections_child_dense_growth_v1.csv`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_fresh_trace_summary_child_dense_growth_v1.json --color-debug-jsonl /tmp/red1_app_fresh_trace_color_debug_child_dense_growth_v1.jsonl --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv -c /tmp/red1_app_fresh_trace_detections_child_dense_growth_v1.csv`
- `shasum -a 256 /tmp/red1_app_fresh_notrace_detections_child_dense_growth_v1.csv`
- `shasum -a 256 /tmp/red1_app_fresh_trace_detections_child_dense_growth_v1.csv`
- `cd /Users/kjt/Projects/RID2Caltopo`
- `python3 tools/anomaly_test/run_regression_suite.py --manifest tools/anomaly_test/regression_suite_manifest.json --binary tools/anomaly_test/build_timing/anomaly_video_test --out-dir /private/tmp/regression_main_child_dense_growth_v1 --report-json /private/tmp/regression_main_child_dense_growth_v1/suite_report.json --report-md /private/tmp/regression_main_child_dense_growth_v1/suite_report.md`
- `python3 tools/anomaly_test/run_regression_suite.py --manifest tools/anomaly_test/regression_suite_color_manifest.json --binary tools/anomaly_test/build_timing/anomaly_video_test --out-dir /private/tmp/regression_color_child_dense_growth_v1 --report-json /private/tmp/regression_color_child_dense_growth_v1/suite_report.json --report-md /private/tmp/regression_color_child_dense_growth_v1/suite_report.md`
- `python3 tools/anomaly_test/run_visible_color_perf_benchmarks.py --binary tools/anomaly_test/build_timing/anomaly_video_test --output /private/tmp/visible_color_perf_child_dense_growth_v1`
Validation ownership:
- this child owns the full validation sweep for its own mutation
- if validation shows a null result or regression, revert its own change before
  reporting unless explicitly instructed otherwise
Adoption gate:
- implementation truly switches the fresh path away from coarse-peak-first blob
  construction
- reviewed Red1 shows a correctness win or at minimum a meaningful move toward
  extraction without broadening the large nuisance blob lane
- black-hot reviewed regression and visible-color perf stay within the current
  envelope unless a clear correctness win justifies review
Artifact suffix:
- `child_dense_growth_v1`
Expected report format:
- attempted change
- whether code remains active or was reverted
- commands run
- pass/fail summary
- headline metrics before vs after
- conclusion: adopt | reject | needs narrower follow-up
- artifact paths

### Child Packet: `child_peak_uniqueness_v1`

Child id: `child_peak_uniqueness_v1`
Role: `experiment`
Checkpoint: current tree state as of `2026-05-11`
Hypothesis: once a compact dense blob is accepted, its uniqueness/rarity
signal should come from the most unique pixel inside that blob rather than the
blob's averaged isolation metrics, and that scoring change can be tested
independently from dense growth
Write scope: `app/src/main/cpp/anomaly_analysis.c`,
`app/src/main/cpp/anomaly_analysis.h`, and directly related native tests only
Non-goals:
- do not replace the fresh blob-construction seam in this child
- do not relax large-blob gates
- do not tune global rarity thresholds first
- do not change AR-lock / revisit policy
Required commands:
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test`
- `cmake -B build`
- `cmake --build build`
- `./build/anomaly_test`
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/build`
- `ctest --output-on-failure`
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test`
- `cmake -B build_timing -DANOMALY_DEBUG_TIMING=ON`
- `cmake --build build_timing`
- `./build_timing/anomaly_test`
- `cd /Users/kjt/Projects/RID2Caltopo`
- `./gradlew :app:compileDebugKotlin`
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend legacy --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_legacy_summary_child_peak_uniqueness_v1.json --color-debug-jsonl /tmp/red1_app_legacy_color_debug_child_peak_uniqueness_v1.jsonl --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv -c /tmp/red1_app_legacy_detections_child_peak_uniqueness_v1.csv`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_fresh_summary_child_peak_uniqueness_v1.json --color-debug-jsonl /tmp/red1_app_fresh_color_debug_child_peak_uniqueness_v1.jsonl --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv -c /tmp/red1_app_fresh_detections_child_peak_uniqueness_v1.csv`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_fresh_notrace_summary_child_peak_uniqueness_v1.json -c /tmp/red1_app_fresh_notrace_detections_child_peak_uniqueness_v1.csv`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_fresh_trace_summary_child_peak_uniqueness_v1.json --color-debug-jsonl /tmp/red1_app_fresh_trace_color_debug_child_peak_uniqueness_v1.jsonl --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv -c /tmp/red1_app_fresh_trace_detections_child_peak_uniqueness_v1.csv`
- `shasum -a 256 /tmp/red1_app_fresh_notrace_detections_child_peak_uniqueness_v1.csv`
- `shasum -a 256 /tmp/red1_app_fresh_trace_detections_child_peak_uniqueness_v1.csv`
- `cd /Users/kjt/Projects/RID2Caltopo`
- `python3 tools/anomaly_test/run_regression_suite.py --manifest tools/anomaly_test/regression_suite_manifest.json --binary tools/anomaly_test/build_timing/anomaly_video_test --out-dir /private/tmp/regression_main_child_peak_uniqueness_v1 --report-json /private/tmp/regression_main_child_peak_uniqueness_v1/suite_report.json --report-md /private/tmp/regression_main_child_peak_uniqueness_v1/suite_report.md`
- `python3 tools/anomaly_test/run_regression_suite.py --manifest tools/anomaly_test/regression_suite_color_manifest.json --binary tools/anomaly_test/build_timing/anomaly_video_test --out-dir /private/tmp/regression_color_child_peak_uniqueness_v1 --report-json /private/tmp/regression_color_child_peak_uniqueness_v1/suite_report.json --report-md /private/tmp/regression_color_child_peak_uniqueness_v1/suite_report.md`
- `python3 tools/anomaly_test/run_visible_color_perf_benchmarks.py --binary tools/anomaly_test/build_timing/anomaly_video_test --output /private/tmp/visible_color_perf_child_peak_uniqueness_v1`
Validation ownership:
- this child owns the full validation sweep for its own mutation
- if validation shows a null result or regression, revert its own change before
  reporting unless explicitly instructed otherwise
Adoption gate:
- accepted dense blobs now expose and use peak-pixel rarity/uniqueness rather
  than blob-averaged isolation as the primary uniqueness signal
- reviewed Red1 correctness improves without re-admitting the oversized
  nuisance blob lane
- no material regression in reviewed black-hot behavior or visible-color perf
Artifact suffix:
- `child_peak_uniqueness_v1`
Expected report format:
- attempted change
- whether code remains active or was reverted
- commands run
- pass/fail summary
- headline metrics before vs after
- conclusion: adopt | reject | needs narrower follow-up
- artifact paths

### Child Packet: `child_dense_validation_v1`

Child id: `child_dense_validation_v1`
Role: `validation`
Checkpoint: whichever candidate checkpoint the parent thread explicitly names
after reviewing the experiment reports
Hypothesis: the nominated dense-fresh candidate is a real win rather than a
telemetry artifact
Write scope: read-only unless the validation packet explicitly instructs the
child to revert a failed candidate tree
Non-goals:
- no redesign
- no new threshold tuning
- no opportunistic cleanup
Required commands:
- run the same full validation command set listed above using the parent-named
  checkpoint suffix for artifacts
Validation ownership:
- full rerun only; no redesign
Adoption gate:
- confirm the candidate's reported replay, regression, parity, and perf claims
Artifact suffix:
- `child_dense_validation_v1`
Expected report format:
- commands run
- pass/fail summary
- metric deltas versus parent-approved baseline
- conclusion: confirm | reject | inconclusive
- artifact paths
