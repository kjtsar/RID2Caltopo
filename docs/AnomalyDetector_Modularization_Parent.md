# AnomalyDetector Modularization Parent

## Goal

Move the current native anomaly detector toward a publishable stand-alone
`AnomalyDetector` module without changing shipped behavior prematurely.

The parent thread owns architecture, contracts, child-thread launch packets,
integration review, and adoption gates. Child threads do bounded extraction,
measurement, or interface proof work with disjoint ownership.

## Baseline

- Start from `v1.5.7(81)rc4` / v81rc4 behavior.
- Keep the first extraction lane behavior-preserving.
- Do not promote experimental branch state unless explicitly reintroduced.
- Keep Red1 app-parity and black-hot IR manifest results separate from
  exploratory timing or architecture work.

Current first split:

- `app/src/main/cpp/anomaly_analysis_internal.h` now holds shared internal
  clamp, timing, and result-plumbing helpers.
- `app/src/main/cpp/anomaly_detector.h` and
  `app/src/main/cpp/anomaly_detector.c` now expose the first consumer-facing C
  facade over the existing state/config/result/process path.
- `app/src/main/cpp/anomaly_motion_estimator.c` now owns the
  registration-backed layered movement sidecar implementation behind a private
  MotionEstimator boundary.
- `app/src/main/cpp/anomaly_motion_estimator.h` keeps registration opaque by
  accepting private projection, validity, and residual-displacement callbacks
  from `anomaly_analysis.c`.
- `app/src/main/cpp/anomaly_scan_planner.c` now owns the first private
  ScanPlanner wrapper for adaptive stride, scan-plan selection, cadence
  bookkeeping, color stride hold, and selective refresh-mask fallback.
- `app/src/main/cpp/anomaly_target_revisit.c` now owns pure target-revisit
  policy helpers for track counting, adaptive revisit risk, revisit radius, and
  point-gate matching.
- `app/src/main/cpp/anomaly_runtime_budget.c` now owns the first standalone
  runtime budget contract for Cursory/Thorough mode selection.
- `app/src/main/cpp/anomaly_runtime_pressure.c` now owns the live AD worker
  queue-pressure policy that was previously embedded in `ffmpeg_bridge.c`.
- `app/src/main/cpp/anomaly_runtime_handoff.c` now owns the pure AD worker
  frame metadata handoff decision for analyze versus forward-without-analysis.
- `app/src/main/cpp/anomaly_analysis.c` still owns detector behavior.

Current runtime direction:

- Treat the existing Android live path as already threaded:
  `FFmpeg decode -> AD input queue -> AD worker -> render queue -> render thread`.
- Keep `ffmpeg_bridge.c` as the Android/FFmpeg adapter owner for queue storage,
  pthread synchronization, AVFrame ownership, render forwarding, JNI/session
  lifecycle, and app logging.
- Move runtime policy and contracts into focused host-testable C modules before
  moving more ownership out of the bridge.
- Keep MotionEstimator behavior inside the current AD worker/analyzer path
  until a separate ME worker/evidence queue has explicit contracts,
  synchronization tests, and replay/performance gates.
- Overnight handoff and full-regression gate context is captured in
  `docs/superpowers/plans/2026-06-13-r2cad-overnight-handoff-and-regression.md`.

Latest parent validation after the consumer facade, MotionEstimator,
ScanPlanner, appearance-candidate, and target-observation splits:

- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test` -> 200 passed, 0 failed
- `tools/anomaly_test/build_timing/anomaly_test` -> 205 passed, 0 failed
  after the Packet 19 appearance-detector interface skeleton.
- `python3 tools/anomaly_test/run_regression_suite.py --binary tools/anomaly_test/build_timing/anomaly_video_test --out-dir /tmp/rid2c_packet8_scanplanner_ir`
- `python3 tools/anomaly_test/run_regression_suite.py --binary tools/anomaly_test/build_timing/anomaly_video_test --manifest tools/anomaly_test/regression_suite_color_manifest.json --out-dir /tmp/rid2c_packet8_scanplanner_color`
- `./gradlew :app:compileDebugKotlin`
- Android Studio build/run on SM-X350 after the Packet 14 appearance-candidate
  header split preserved basic app functionality, with observed Color AD around
  0.62x realtime.
- Tablet smoke checks after Packet 17 target-observation extraction still
  showed nominal behavior, with reported Color AD performance ranging roughly
  0.49x to 0.9x realtime and correlating with scene movement.
- Tablet smoke check after Packet 19 still showed nominal Color playback,
  roughly 0.49x to 0.70x realtime on the same file.
- Black-hot IR regression and registration-performance checks after Packet 19:
  - Regression output:
    `/private/tmp/rid2c_packet19_ir_regression/suite_report.md`
  - Registration/perf output:
    `/private/tmp/rid2c_packet19_registration_perf`
  - Current redesigned-incremental aggregate: precision 0.994, recall 0.599,
    realtime factor 1.409x.
  - Dense full-scan gold aggregate: precision 1.000, recall 0.362, realtime
    factor 0.581x.
  - Registration perf cases: PowerHouseTeam affine scan-zone 0.80 at 1.33x,
    PowerHouseTeam affine scan-zone 0.60 at 1.56x, PowerHouse1 affine
    scan-zone 0.80 at 0.84x, and PowerHouse1 opening scan-zone 0.60 at 0.97x.
- Post-Packet 21 IR gate after extracting pure thermal helpers:
  - Regression output:
    `/private/tmp/rid2c_packet21_ir_regression/suite_report.md`
  - Functionality matched prior aggregate counts: redesigned-incremental
    precision 0.994 / recall 0.599 with TP 167, FP 1, miss 112; dense
    full-scan gold precision 1.000 / recall 0.362 with TP 101, FP 0,
    miss 178.
  - Solo registration/perf output:
    `/private/tmp/rid2c_packet21_registration_perf_solo`
  - Solo perf cases: PowerHouseTeam affine scan-zone 0.80 at 1.20x,
    PowerHouseTeam affine scan-zone 0.60 at 1.47x, PowerHouse1 affine
    scan-zone 0.80 at 0.68x, and PowerHouse1 opening scan-zone 0.60 at 0.80x.
    Because registration-solve timing also moved and one earlier perf run was
    contaminated by concurrent regression, treat this as an idle-recheck item
    rather than confirmed detector-path regression evidence.
- Packet 21 idle perf recheck:
  - Output:
    `/private/tmp/rid2c_packet21_registration_perf_idle_recheck`
  - Registration perf cases recovered to Packet 20 or better: PowerHouseTeam
    affine scan-zone 0.80 at 1.35x, PowerHouseTeam affine scan-zone 0.60 at
    1.66x, PowerHouse1 affine scan-zone 0.80 at 0.84x, and PowerHouse1
    opening scan-zone 0.60 at 0.98x.
  - Treat the earlier Packet 21 slowdown as host/load noise, not a confirmed
    regression from pure thermal-helper extraction.
- Post-Packet 22 IR gate after extracting the thermal state owner:
  - Regression output:
    `/private/tmp/rid2c_packet22_ir_regression/suite_report.md`
  - Functionality matched prior aggregate counts: redesigned-incremental
    precision 0.994 / recall 0.599 with TP 167, FP 1, miss 112; dense
    full-scan gold precision 1.000 / recall 0.362 with TP 101, FP 0,
    miss 178.
  - Registration/perf output:
    `/private/tmp/rid2c_packet22_registration_perf`
  - Registration perf cases: PowerHouseTeam affine scan-zone 0.80 at 1.28x,
    PowerHouseTeam affine scan-zone 0.60 at 1.44x, PowerHouse1 affine
    scan-zone 0.80 at 0.69x, and PowerHouse1 opening scan-zone 0.60 at 0.88x.
    These are below the Packet 21 idle recheck but close to the earlier
    Packet 21 solo timing; treat as host/load-noisy unless repeated idle runs
    show a consistent drop.
- Post-Packet 23 IR gate after extracting the thermal temporal stats helper:
  - Regression output:
    `/private/tmp/rid2c_packet23_ir_regression/suite_report.md`
  - Functionality matched prior aggregate counts: redesigned-incremental
    precision 0.994 / recall 0.599 with TP 167, FP 1, miss 112; dense
    full-scan gold precision 1.000 / recall 0.362 with TP 101, FP 0,
    miss 178.
  - Registration/perf outputs:
    `/private/tmp/rid2c_packet23_registration_perf` and
    `/private/tmp/rid2c_packet23_registration_perf_idle_recheck`
  - Idle recheck registration perf cases: PowerHouseTeam affine scan-zone
    0.80 at 1.28x, PowerHouseTeam affine scan-zone 0.60 at 1.36x,
    PowerHouse1 affine scan-zone 0.80 at 0.61x, and PowerHouse1 opening
    scan-zone 0.60 at 0.77x. PowerHouse1 remains slower than Packet 22 while
    registration timing also moved; treat as a performance watch item before
    the next hot Thermal/IR loop extraction.
- Packet 23 second idle perf recheck:
  - Output:
    `/private/tmp/rid2c_packet23_registration_perf_second_idle`
  - Registration perf cases recovered to at or above Packet 22: PowerHouseTeam
    affine scan-zone 0.80 at 1.34x, PowerHouseTeam affine scan-zone 0.60 at
    1.61x, PowerHouse1 affine scan-zone 0.80 at 0.80x, and PowerHouse1
    opening scan-zone 0.60 at 0.98x.
  - Treat the earlier Packet 23 PowerHouse1 slowdown as host/load noise, not a
    confirmed regression from thermal temporal-stats extraction.
- Post-Packet 24 Color gate after extracting Color histogram/rarity helpers:
  - Regression output:
    `/private/tmp/rid2c_packet24_color_regression/suite_report.md`
  - Red1 visible-color baseline stayed at current seed behavior: recall 0.000
    with TP 0, FP 0, miss 15.
  - Red1 dense gold stayed at precision 1.000 / recall 1.000 with TP 15,
    FP 0, miss 0.
  - Visible-color perf output:
    `/private/tmp/rid2c_packet24_visible_color_perf/visible_color_perf_report.json`
  - Native harness visible-color perf: app-like auto averaged 0.31x realtime
    with 47.54 ms color scoring; dense gold averaged 0.05x realtime with
    395.86 ms color scoring. Treat this as harness evidence, not tablet
    playback truth; the practical adoption check remains tablet smoke on the
    same Color file.
- Packet 25 Color frontend-mode helper split:
  - Moved only the frontend-mode `static inline` helpers into
    `anomaly_color_detector.h`; no scoring, support-map, candidate extraction,
    winner-gate evaluation, promotion-track, ROI, debug/result, timing,
    threading, or lifecycle changes.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 302 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Full Color replay was intentionally skipped because this was a helper move
    plus mechanical call-site rename. Packet 24 remains the latest full Color
    manifest/perf gate, and tablet Color smoke after Packet 24 remained
    nominal.
- Packet 26 Color neighborhood contrast helper split:
  - Moved only the pure local/ring Color contrast helpers into
    `anomaly_color_detector.c`; no telemetry, support-map, scoring,
    `local_uv_support_count`, candidate extraction, winner gate, promotion
    tracks, ROI/debug/timing/lifecycle, or threading changes.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 317 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Full Color replay was intentionally skipped because this was a pure helper
    move plus mechanical call-site rename. Packet 24 remains the latest full
    Color manifest/perf gate.
- Packet 27 Color local UV support helper split:
  - Moved only the pure local UV support-count helper into
    `anomaly_color_detector.h`; no scoring, temporal rescue, contrast rescue,
    target telemetry, support-map, candidate extraction, winner gate, promotion
    tracks, ROI/debug/timing/lifecycle, or threading changes.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 326 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Full Color replay was intentionally skipped because this was a pure helper
    move plus mechanical call-site rename. Packet 24 remains the latest full
    Color manifest/perf gate.
- Packet 28 Color history/defaults split:
  - Moved the Color recent-history updater and fresh distinctness default/clamp
    helpers into `anomaly_color_detector.{h,c}` while keeping the histogram
    update API buffer-oriented. `anomaly_analysis.c` still owns reset/recovery
    lifecycle decisions.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 338 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet28_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
- Packet 29 Color target telemetry split:
  - Moved only the Color debug target telemetry struct/helper into
    `anomaly_color_detector.{h,c}`. Scoring, support maps, candidate
    extraction, winner gates, promotion tracks, ROI/result export, timing,
    lifecycle, and threading stayed in `anomaly_analysis.c`.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 364 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet29_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
- Packet 30 Color RGBA sampling primitive split:
  - Moved only the low-level RGBA-to-luma/U/V sampling helpers and dense pixel
    threshold helper into `anomaly_color_detector.h`. Dense seed ranking,
    dense refinement, dense component verification, sampling-state prep,
    support maps, scoring, winner gates, result export, timing, lifecycle, and
    threading stayed in `anomaly_analysis.c`.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 382 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet30_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
- Packet 31 Color candidate scalar helper split:
  - Moved only the pure scene-commonness and small-target-priority scalar
    helpers into `anomaly_color_detector.h`. Winner gates, support maps, dense
    verifier/ranking, candidate extraction, promotion tracks, result export,
    timing, lifecycle, threading, and public APIs stayed in
    `anomaly_analysis.c`.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 390 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet31_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
- Packet 32 Color dense-seed utility split:
  - Moved only the dense-seed typedef/constants and pure local-peak, seed-rank,
    seed-bounds, and ordered/NMS insert helpers into
    `anomaly_color_detector.h`. Support-map construction, dense verifier,
    candidate build/rescue/extraction, winner gates, promotion tracks, result
    export, timing, lifecycle, threading, and public APIs stayed in
    `anomaly_analysis.c`.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 408 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet32_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
- Packet 33 Color sampling phase/coordinate helper split:
  - Moved only the stable phase-zero/no-op sampling-phase helpers and pure
    sample-coordinate clamp helper into `anomaly_color_detector.h`.
    `prepare_color_sampling_state`, registration inverse helpers, ROI
    snapshot/carry-forward lifecycle, support maps, candidate extraction,
    scoring, result export, timing, lifecycle, threading, and public APIs
    stayed in `anomaly_analysis.c`.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 414 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Full Color replay intentionally skipped: this packet moved no scoring,
    seed-ordering, support-map, candidate-extraction, or sampling-state
    lifecycle logic. Packet 32 remains the latest full Color replay gate.
- Packet 34 Color blob-neighbor similarity helper split:
  - Moved only the pure blob-neighbor similarity helper into
    `anomaly_color_detector.h`. Support-map construction, cohesion-weight
    construction, candidate extraction/rescue/build, dense verifier, winner
    gates, promotion tracks, sampling-state lifecycle, result export, timing,
    threading, public APIs, and scoring thresholds stayed in
    `anomaly_analysis.c`.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 421 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet34_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
- Packet 35 Color candidate bbox normalization helper split:
  - Moved only the pure Color debug/result bbox-normalization helper into
    `anomaly_color_detector.h`. Support-map construction, candidate
    extraction/rescue/build, dense verifier/ranking/refinement, winner gates,
    promotion tracks, sampling-state lifecycle, scoring, timing/threading, and
    public AD APIs stayed in `anomaly_analysis.c`.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 433 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Full Color replay intentionally skipped: this packet moved no scoring,
    seed-ordering, support-map, candidate-extraction, or sampling-state
    lifecycle logic. Packet 34 remains the latest full Color replay gate.
- Packet 36 Color candidate temporal boost helper split:
  - Moved only the Color candidate temporal boost helper into
    `anomaly_color_detector.h`. `score_color_temporal_rescue`,
    `score_color_contrast_rescue`, support maps, cohesion weights, candidate
    extraction/rescue/build, dense verifier, winner gates, promotion tracks,
    sampling-state lifecycle, result export, timing/threading, and public APIs
    stayed in `anomaly_analysis.c`.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 441 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet36_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
  - Replay runtimes were faster than prior host runs, but treat that as
    host/load variance unless repeated.
- Packet 37 Color temporal rescue helper split:
  - Moved only the pre-support Color temporal rescue helper into
    `anomaly_color_detector.h` as `anomaly_color_score_temporal_rescue`.
    `score_color_contrast_rescue`, support maps, cohesion weights, candidate
    extraction/rescue/build, dense verifier, winner gates, promotion tracks,
    sampling-state lifecycle, result export, timing/threading, public APIs, and
    other thresholds stayed in `anomaly_analysis.c`.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 453 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet37_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
  - Replay runtimes were `0.635x` baseline and `0.093x` dense gold; treat
    these as host/load observations, not proof of a real performance shift.
- Packet 38 Color contrast rescue helper split:
  - Moved only the pre-support Color contrast rescue helper into
    `anomaly_color_detector.h` as `anomaly_color_score_contrast_rescue`.
    Support-map construction, local UV support, cohesion weights, candidate
    extraction/rescue/build, dense verifier, winner gates, promotion tracks,
    sampling-state lifecycle, result export, timing/threading, public APIs, and
    behavior thresholds stayed otherwise unchanged.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 461 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet38_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
  - Replay runtimes were `0.630x` baseline and `0.092x` dense gold; treat
    these as host/load observations, not proof of a real performance shift.
- Packet 39 fresh Color winner-gate helper split:
  - Moved only the pure fresh Color winner-gate decision into
    `anomaly_color_detector.h` as `anomaly_color_evaluate_fresh_winner_gate`.
    The call to `effective_thermal_small_target_span_px()` stayed in
    `anomaly_analysis.c`, and the resulting span is passed into the Color
    helper so Color does not depend on Thermal internals.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 473 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet39_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
  - Replay runtimes were `0.617x` baseline and `0.093x` dense gold; treat
    these as host/load observations, not proof of a real performance shift.
- Packet 40 Color seed-region suppression helper split:
  - Moved only the Color seed-region visited marking helper into
    `anomaly_color_detector.h` as `anomaly_color_suppress_seed_region`.
    The helper uses candidate bounds directly instead of depending on
    `anomaly_color_blob_candidate_t`, avoiding a new appearance-candidate type
    dependency in the Color header.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 592 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet40_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
  - Replay runtimes were `0.617x` baseline and `0.093x` dense gold; treat
    these as host/load observations, not proof of a real performance shift.
- Packet 41 Color blob cohesion-weight helper split:
  - Moved only the Color blob cohesion-weight computation into
    `anomaly_color_detector.h` as
    `anomaly_color_compute_blob_cohesion_weights`. Support-map construction,
    candidate extraction, dense verification, ranking, target traces,
    promotion tracks, winner gates, result export, timing/threading, public
    APIs, and thresholds stayed in `anomaly_analysis.c`.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 617 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet41_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
  - Replay runtimes were `0.615x` baseline and `0.093x` dense gold; treat
    these as host/load observations, not proof of a real performance shift.
- Packet 42 Color support patch-radius helper split:
  - Moved only the support-map patch-radius math into
    `anomaly_color_detector.h` as `anomaly_color_support_patch_radius`. The
    target-span calculation stays in `anomaly_analysis.c`, so Color does not
    inherit Thermal or target-span policy dependencies.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 623 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet42_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
  - Replay runtimes were `0.619x` baseline and `0.093x` dense gold; treat
    these as host/load observations, not proof of a real performance shift.
- Packet 43 Color support patch-score helper split:
  - Moved only the pure per-cell Color support-score math from
    `build_color_support_map(...)` into `anomaly_color_detector.h` as
    `anomaly_color_score_support_patch`. Support-map traversal, scratch-map
    lifecycle, seed bounds, max-support tracking, candidate extraction, dense
    verification, target traces, result export, timing/threading, and public
    APIs stayed in `anomaly_analysis.c`.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 635 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet43_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
  - Replay runtimes were `0.586x` baseline and `0.093x` dense gold; treat
    these as host/load observations, not proof of a real performance shift.
- Packet 44 Color support debug scalar helper reuse:
  - Moved only duplicate support-map scalar formulas into
    `anomaly_color_detector.h` helpers for distinctness ratio, distinctness
    gate, compact prominence, core share, and seed floor. Both
    `anomaly_color_score_support_patch(...)` and the Color target-debug
    support-map metric block now use the shared helpers.
  - Preserved the existing target-debug semantics where compact prominence and
    core share are still computed in legacy mode, while legacy seed floor stays
    fixed at `0.55f`.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 645 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet44_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
  - Replay runtimes were `0.611x` baseline and `0.094x` dense gold; treat
    these as host/load observations, not proof of a real performance shift.
- Packet 45 ScanPlanner selective-refresh mask builder split:
  - Moved the pure selective refresh mask builder from `anomaly_analysis.c`
    into `anomaly_scan_planner.{h,c}` as
    `anomaly_scan_planner_build_selective_refresh_mask`. ScanPlanner now owns
    the mask-selection rules after the parent supplies storage through
    `ensure_refresh_mask_capacity`.
  - Removed the pure mask-selection callback from `anomaly_scan_planner_ops_t`
    while keeping ROI cell summarization, target revisit annotation, allocation,
    adaptive stride, color sampling prep, timing semantics, and public APIs in
    their existing owners.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 664 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Black-hot IR regression output:
    `/private/tmp/rid2c_packet45_ir_regression/suite_report.md`
  - IR aggregate behavior stayed at the current documented checkpoint:
    current baseline precision 0.965 / recall 0.197, dense gold precision
    1.000 / recall 0.362, redesigned incremental precision 0.994 / recall
    0.599.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet45_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
  - Replay runtimes were host-load contaminated on this run, especially Color
    dense gold (`0.040x`), so treat runtime numbers as observational only and
    not proof of a real throughput shift.
- Packet 46 ScanPlanner ROI cell geometry contract:
  - Moved the shared ROI cell target size and sampled-cell span rule into the
    ScanPlanner contract as `ANOMALY_SCAN_PLANNER_ROI_CELL_TARGET_SIZE_PX` and
    `anomaly_scan_planner_roi_grid_cell_span(int sample_step)`.
  - `anomaly_analysis.c` now uses the same helper for ROI summaries and ROI
    state cell dimensions that ScanPlanner uses when consuming selective masks.
  - Behavior stays fixed at target size `16`, `sample_step <= 0` fallback to
    `1`, and ceil-style `(target + sample_step - 1) / sample_step` rounding.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 673 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Black-hot IR regression output:
    `/private/tmp/rid2c_packet46_ir_regression/suite_report.md`
  - IR aggregate behavior stayed at the current documented checkpoint:
    current baseline precision 0.965 / recall 0.197, dense gold precision
    1.000 / recall 0.362, redesigned incremental precision 0.994 / recall
    0.599.
  - Visible-color regression output:
    `/private/tmp/rid2c_packet46_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15. Red1 dense gold stayed at precision 1.000 / recall 1.000
    with TP 15, FP 0, miss 0.
  - Replay runtimes are observational only; Color dense gold was `0.044x`
    during this run and remains a heavy host-side comparison mode, not a
    promoted realtime posture.

- Packet 47 internal frame/ROI bounds geometry:
  - Added `anomaly_frame_geometry.h` as a header-only internal contract for
    pure frame ROI bounds geometry.
  - Named `anomaly_frame_roi_bounds_t`,
    `anomaly_frame_centered_roi_bounds(...)`, and
    `anomaly_frame_registration_roi_bounds(...)`.
  - `anomaly_analysis.c` now uses the named helpers for scan-zone and
    registration ROI bounds without moving ROI state ownership.
  - Stateful ROI mutation, ROI summarization, target revisit annotation, and
    previous-sample lookup remain in `anomaly_analysis.c`.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 680 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Full replay manifests were not rerun for this packet; Packet 46 just
    refreshed IR and Red1 parity, and Packet 47 only moves pure integer/float
    bounds geometry with focused native coverage.

- Packet 48 ROI state lifecycle helper module:
  - Added plain-C internal `anomaly_roi_state.{h,c}` for
    `anomaly_roi_state_t` lifecycle/allocation helpers only.
  - Named ROI pixel capacity, cell capacity, clear, and release helpers.
  - `anomaly_analysis.c` still owns ROI mutation, carry-forward, selective/full
    refresh, target revisit annotation, cell summarization, previous-sample
    lookup, scan-zone policy, scoring, timing/threading, and public APIs.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 705 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Full replay manifests were not rerun for this packet; the change is a
    mechanical lifecycle extraction with focused native coverage and no ROI
    mutation, scoring, refresh, timing, or public API behavior change.

- Packet 49 ROI cell summary producer helper:
  - ROI state now owns lifecycle plus ROI cell-summary production through
    `anomaly_roi_state_summarize_cells(...)`.
  - `anomaly_analysis.c` still owns ROI mutation/carry-forward, full/selective
    refresh, age-one-frame mutation, previous-sample lookup/snapshots, and
    target-revisit overlay.
  - Target-revisit overlay remains parent-owned until target-track/revisit
    contracts are cleaner.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 726 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Full replay manifests were not rerun for this packet; the change is a
    bounded ROI summary producer extraction with focused native coverage and no
    ROI mutation, target-revisit, scoring, timing, or public API behavior
    change.

- Packet 50 target-revisit policy helper:
  - Added plain-C internal `anomaly_target_revisit.{h,c}` for pure
    target-track/revisit policy helpers.
  - Named track counting, adaptive revisit risk, revisit radius, and point-gate
    matching helpers for ScanPlanner callbacks, track cleanup, revisit
    confirmation, selective-refresh off-gate suppression, and annotation radius
    lookup.
  - `anomaly_analysis.c` still owns target-revisit overlay annotation,
    confirmation search, target-track lifecycle/update functions, ROI
    mutation, scoring, timing/threading, and public APIs.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 756 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Full replay manifests were not rerun for this packet; the change is a pure
    target-revisit policy extraction with focused native coverage and no
    annotation, scoring, ROI mutation, timing, or public API behavior change.

- Packet 51 target-revisit ROI annotation helper:
  - Moved target-revisit ROI cell annotation into
    `anomaly_target_revisit_annotate_roi_cells(...)` in
    `anomaly_target_revisit.{h,c}`.
  - Preserved the existing annotation guards, forced-track filter, radius helper
    use, clamp/floor/cell loops, and OR-only target-revisit scan flag mutation.
  - `anomaly_analysis.c` still owns revisit confirmation search,
    `anomaly_revisit_confirmation_t`, target-track lifecycle/update functions,
    scoring, timing/threading, and public APIs.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 860 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Full replay manifests were not rerun for this packet; the change is an
    OR-only annotation helper extraction with focused native coverage and no
    scoring, ROI mutation, scan planning, timing, threading, or public API
    behavior change.

- Packet 52 target-track slot bookkeeping helpers:
  - Added plain-C internal `anomaly_target_tracks.{h,c}` for target-track slot
    helpers only.
  - Moved single-track clear, all-track clear, observation-match lookup, and
    slot allocation into named helpers while preserving the match gate,
    algorithm-mismatch gate, first-inactive allocation, and full-table
    weakest-slot ordering.
  - `anomaly_analysis.c` still owns registration prediction, observation update
    lifecycle, ROI clearing side effects, movement evidence, scoring,
    timing/threading, and public APIs.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 984 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Full replay manifests were not rerun for this packet; the change is a
    deterministic bookkeeping helper extraction with focused native coverage
    and no observation update, prediction, scoring, ROI clearing,
    timing/threading, or public API behavior change.

- Packet 53 target-track observation lifecycle helper:
  - Moved target-track observation update lifecycle into
    `anomaly_target_tracks_update_from_observations(...)`.
  - Kept registration-quality derivation and ROI clearing parent-owned:
    `anomaly_analysis.c` passes the quality in and performs
    `clear_all_roi_tracks(state)` only when the helper returns the preserved
    clear intent.
  - `anomaly_analysis.c` still owns registration prediction, revisit
    confirmation search, movement evidence, ROI clearing implementation,
    scoring, timing/threading, and public APIs.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 1010 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Full replay manifests were not rerun for this packet; the change is a
    target-track lifecycle extraction with focused native coverage and no
    registration prediction, revisit confirmation, movement evidence, scoring,
    timing, scan planning, ROI clearing implementation, or public API behavior
    change.

- Packet 54 target-track registration prediction helper:
  - Moved target-track registration prediction into
    `anomaly_target_tracks_predict_with_registration(...)`.
  - Kept `anomaly_registration_model_t` private to `anomaly_analysis.c` through
    `const void *` registration context plus valid and invert-point callbacks.
  - `anomaly_analysis.c` still owns registration validity/inversion helpers and
    registration-quality derivation through `registration_health_confidence(...)`.
  - Added focused native coverage for no-op paths, clear paths, invalid
    registration behavior, failed inverse forced-revisit, successful inverse
    clamping/quality updates, and non-fresh forced-revisit marking.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 1030 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Full replay manifests were not rerun for this packet; the change is a
    target-track registration-prediction extraction with focused native
    coverage and no registration model implementation change, movement
    evidence, scoring, timing, scan planning, ROI mutation/clearing
    implementation, or public API behavior change.

- Packet 55 ROI track clear/age lifecycle helper:
  - Added internal plain-C `anomaly_roi_tracks.{h,c}` for ROI track lifecycle
    helpers only.
  - Moved primary accumulator clear and saliency aux clear as private helpers
    inside `anomaly_roi_tracks.c`.
  - Exposed only `anomaly_roi_tracks_clear_saliency(...)`,
    `anomaly_roi_tracks_clear_all(...)`, and
    `anomaly_roi_tracks_age_one_frame(...)`.
  - Replaced existing clear/age call sites mechanically, and pointed
    ScanPlanner ops directly at `anomaly_roi_tracks_age_one_frame(...)`.
  - Preserved behavior: saliency-only clear resets primary slot 3 plus
    saliency display/aux state; clear-all resets primary accumulators, color
    promotion slots, saliency display/aux state, and target tracks through
    `anomaly_target_tracks_clear_all(state)`; age-one-frame decrements/clears
    only primary and saliency aux hold counters.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 1129 passed / 0 failed, and `./gradlew :app:compileDebugKotlin`.
  - Full replay manifests were not rerun for this packet; the change is a
    bounded lifecycle-helper extraction with focused native coverage and no
    saliency update, camera-motion compensation, color-promotion update, target
    observation lifecycle, registration prediction, revisit confirmation,
    movement evidence, scoring, timing, threading, or public API behavior
    change.

- Packet 56 motion tile interpretation helpers:
  - Moved pure movement tile interpretation helpers into
    `anomaly_motion_estimator.{h,c}` as
    `anomaly_motion_estimator_tile_independent_score(...)`,
    `anomaly_motion_estimator_tile_is_parallax_like(...)`, and
    `anomaly_motion_estimator_tile_is_independent(...)`.
  - Replaced `anomaly_analysis.c` call sites in target-track movement evidence,
    revisit confirmation search, thermal target trace/debug population, and
    thermal candidate debug export.
  - Preserved behavior: NULL/invalid score zero, residual/flow/layer weighting,
    background/coherent-near parallax classification, and local-outlier plus
    `>= 0.50` independent classification.
  - Worker validation: `git diff --check`, CMake build, CTest, and direct
    native harness at 1145 passed / 0 failed.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 1145 passed / 0 failed, and
    `./gradlew :app:compileDebugKotlin`.
  - Full replay manifests were not rerun for this packet; the change is a pure
    MotionEstimator tile-interpretation helper relocation with focused native
    coverage and no detector decision-loop, scoring-policy, timing, scan
    planning, threading, public API, or result-struct behavior change.

- Packet 57 motion candidate support helpers:
  - Moved bounded motion candidate support lookup/stamping helpers into
    `anomaly_motion_estimator.{h,c}` as
    `anomaly_motion_estimator_nearest_candidate_support_norm(...)` and
    `anomaly_motion_estimator_stamp_support(...)`.
  - Replaced `anomaly_analysis.c` call sites for saliency motion support
    stamping and thermal target/local-peak movement-support debug population.
  - Preserved behavior: invalid/null nearest-support inputs return `-1.0f`,
    nonpositive support is ignored, support coordinates are normalized by frame
    dimensions, squared max-distance gating is unchanged, stamp no-op gates are
    unchanged, center/neighbor scales remain `1.0`/`0.55`, motion support keeps
    the maximum value, and registration support only lowers when the
    registration map is non-null and the new scale is smaller.
  - Worker validation: `git diff --check`, CMake build, CTest, and direct
    native harness at 1167 passed / 0 failed.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 1167 passed / 0 failed, and
    `./gradlew :app:compileDebugKotlin`.
  - Full replay manifests were not rerun for this packet; the change is a pure
    MotionEstimator support-query/stamp helper relocation with focused native
    coverage and no detector decision-loop, scoring-policy, timing, scan
    planning, threading, public API, or result-struct behavior change.

- Packet 58 MotionEstimator primitive extraction:
  - Moved residual displacement, texture-scale, and structure-scale helpers
    into `anomaly_motion_estimator.{h,c}` as
    `anomaly_motion_estimator_find_residual_displacement(...)`,
    `anomaly_motion_estimator_texture_scale(...)`, and
    `anomaly_motion_estimator_structure_scale(...)`.
  - Replaced the sidecar ops initializer and GMV/appearance scorer call sites
    in `anomaly_analysis.c` with the MotionEstimator names.
  - Preserved behavior: same patch/SAD search, second-best margin,
    edge-of-search rejection, optional residual outputs, texture scale
    thresholds, and structure-scale gradient/minor-eigenvalue thresholds.
  - Worker validation: `git diff --check`, CMake build, CTest, and direct
    native harness at 1187 passed / 0 failed.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 1187 passed / 0 failed, and
    `./gradlew :app:compileDebugKotlin`.
  - Full replay manifests were not rerun for this packet; the change is a pure
    MotionEstimator primitive relocation with focused native coverage and no
    detector decision-loop, scoring-policy, timing, scan planning, threading,
    public API, or result-struct behavior change.

- Packet 59 MotionEstimator appearance-motion scorer contract:
  - Added the contract-only Phase 2 scorer shape in
    `anomaly_motion_estimator.{h,c}`:
    `ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS`,
    `anomaly_motion_appearance_proposal_t`,
    `anomaly_motion_appearance_score_t`,
    `anomaly_motion_appearance_scorer_state_t`,
    `anomaly_motion_appearance_scorer_input_t`, and
    `anomaly_motion_appearance_scorer_output_t`.
  - Added null-safe output initialization and winner-eligibility helpers.
  - Preserved behavior: the scorer body remains in `anomaly_analysis.c`, and
    winner selection, candidate scoring, persistence allocation, saliency
    stamping, debug fields, timing, and detector results are unchanged.
  - Worker validation: `git diff --check`, CMake build, CTest, and direct
    native harness at 1220 passed / 0 failed.
  - Parent validation: `git diff --check`, CMake build, CTest, direct native
    harness at 1220 passed / 0 failed, and
    `./gradlew :app:compileDebugKotlin`.
  - Full replay manifests were not rerun for this packet; the change is a pure
    MotionEstimator contract/helper addition with focused native coverage and
    no detector decision-loop, scoring-policy, timing, scan planning,
    threading, public API, or result-struct behavior change.

## Target Shape

The long-term module boundary should be contract-first:

```text
FrameInput -> MotionEstimator -> EvidenceFrame -> Modal Detectors -> Fusion/Tracking -> DetectionResult
```

The stand-alone detector consumer interface should stay narrow:

```c
int anomaly_detector_process(
        anomaly_detector_state_t        *state,
        const anomaly_frame_input_t     *frame,
        const anomaly_detector_config_t *config,
        anomaly_detector_result_t       *result_out);
```

Android, JNI, FFmpeg, overlay drawing, logging cadence, and playback timing
should remain adapters around that interface.

## Portability Boundary

The stand-alone native detector may rely on:

- ISO C and the C standard library.
- `pthread` primitives where a measured internal thread boundary is adopted.
- Small compatibility shims owned by the detector module when a platform lacks
  an otherwise standard facility.

Avoid in the core detector:

- Android, JNI, Java/Kotlin, FFmpeg, OpenGL, or app lifecycle dependencies.
- Platform logging, filesystem, clock, or thread APIs unless isolated behind a
  narrow adapter.
- Assumptions that playback, display, or annotation tools are present.

If a future optimization needs worker threads, the module should expose the
threading policy explicitly: single-threaded by default, no hidden frame copies,
bounded queues only when measured, and clean shutdown/reset semantics.

## Consumer Interface

The future consumer-facing frame input should name the current implicit inputs
to `anomaly_process_frame()`:

- RGBA plane pointer, stride, width, and height.
- Source timestamp in microseconds.
- Frame format metadata needed by future YUV/color frontends.
- Optional previous-frame or externally supplied evidence handles only after
  those become stable.

The detector config should remain value-type and plain C. Today this maps to
`anomaly_config_t`, which is populated from Kotlin via `NativeAnomalyConfig`
and passed by value through `ffmpeg_bridge.c`.

The detector result should separate:

- stable public detections and health summaries,
- optional debug/telemetry payloads,
- adapter-only overlay drawing side effects.

Keep out of the stand-alone detector module:

- FFmpeg session ownership, locks, AD queues, session IDs, runtime pause/disable
  policy, and playback timing accounting.
- RGBA conversion, overlay-frame cloning, JNI marshaling, and Android lifecycle
  reset decisions.
- Kotlin UI/default/persistence policy. The C API may expose defaults, but the
  app remains responsible for deciding when to apply or reset them.

One near-term cleanup is to reword `anomaly_config_t`'s current Kotlin/JNI
ownership comment once the C-facing API headers exist. The struct itself is
plain C and should not imply Android ownership.

### Active Control Updates

The public detector contract must define how control and display parameters
change while detection is active.

Separate config into three behavioral classes:

- Processing controls: algorithm mask, polarity/profile, thresholds, stride,
  scan zone, movement estimator mode, and color frontend. These can change
  detector behavior and may require state reset, partial reset, or a documented
  next-frame transition.
- Display controls: overlay boxes, guide boxes, hot overlay, debug visual
  layers, and alarm/display preferences. These should not change detector
  evidence, tracking, or timing beyond their own rendering cost.
- Debug/telemetry controls: trace target selection, timing/debug emission, and
  JSON/CSV verbosity. These should preserve detections unless the debug mode is
  explicitly documented as intrusive.

Near-term rule: keep accepting the current per-frame `anomaly_config_t`, but
document which fields are live-updatable versus reset-sensitive before
publishing a stand-alone API. Runtime adapters may debounce UI changes, but the
native module should own the state-transition semantics.

Current conservative classification, expanded in
`docs/AnomalyDetector_API_Contracts.md`:

- Live processing controls: `enabled`, `score_threshold`,
  `motion_evidence_scale`, `thermal_min_delta`, and `min_hits` pending a
  documented transition rule for existing hit counts.
- Display-only controls: `show_hot_overlay` and `show_candidate_blobs`.
- Reset-sensitive processing controls: `algorithm_mask`, `registration_mode`,
  `movement_estimator_mode`, `stride_mode`, `frame_stride`,
  `adaptive_min_stride_frames`, `adaptive_max_stride_frames`,
  `adaptive_max_stride_seconds`, `pixel_step`, `thermal_polarity`,
  `scan_zone`, `min_area_fraction`, `small_target_screen_fraction`, and
  `color_frontend_mode`.
- Debug/telemetry controls: `thermal_debug_target_enabled`,
  `thermal_debug_target_x_norm`, `thermal_debug_target_y_norm`,
  `color_debug_target_enabled`, `color_debug_target_x_norm`, and
  `color_debug_target_y_norm`.

Reset-sensitive means the current temporal evidence may no longer describe the
same detector contract. Stale state can include background thermal history,
ROI/sample coverage, color history, target tracks, registration cache, motion
persistence, adaptive-stride cadence, and per-algorithm accumulators. The
stand-alone API should eventually expose an explicit transition policy such as
`live`, `reset_detector_state`, or `reset_appearance_state`.

Today the app applies config updates through Kotlin/JNI into the FFmpeg bridge
as a next-frame value update. That is useful app behavior to preserve during
refactors, but it should not be mistaken for the final published API contract.
The final module should make reset semantics explicit rather than requiring
callers to know which internal state happened to be stale.

### Replay And Annotation Support

Frame-by-frame stepping, replay, and manual detection annotation are downstream
consumers of detector outputs. They should continue to be supported by keeping
the core detector deterministic for a given ordered frame/config stream and by
preserving result/debug fields needed by the native harness:

- stable detection boxes and track/debug metadata,
- frame/source timestamps and discontinuity reporting,
- scan-plan, registration, movement, color, thermal, and timing telemetry,
- optional CSV/JSON-friendly debug payloads produced by adapters.

The stand-alone module should not own UI annotation workflows, but it must keep
enough structured output for those tools to inspect every frame without replay
side effects.

## Producer Interfaces

### MotionEstimator

The MotionEstimator is the first major producer module after the top-level
detector interface. Its consumer interface can match the detector frame/config
shape, but its producer interface should emit reusable motion evidence rather
than final detections.

Likely producer fields:

- global camera/registration model and health,
- local residual vector tiles,
- independent-motion score map,
- parallax/background/unstable/local-outlier tile classification,
- global motion load and confidence,
- AOI query support for target revisit and shadow checks,
- optional motion candidate hints.

The MotionEstimator should not decide whether a frame is an IR, Color, Shape,
or Motion anomaly. It produces evidence that those consumers may use.

Split MotionEstimator work into two phases:

1. Registration-backed movement producer. This owns the layered sidecar /
   movement snapshot: residual vector tiles, parallax/background/local-outlier
   classification, confidence, and suppression scales.
2. Appearance-proposal motion scorer. This is currently seeded by IR/Color maps
   and writes `motion_candidate_support` and `saliency_motion_map`, so defer it
   until the appearance evidence contracts are clearer.

Keep the registration model as an input dependency to MotionEstimator at first.
Registration is already its own conceptual producer, and folding it into the
movement sidecar too early would blur responsibilities.

### Modal And Additive Detectors

IR and Color are modal/selective: the source profile determines which primary
appearance detector should run. Motion and Shape are additive: they should
boost, suppress, validate, or explain appearance evidence without replacing the
modal detector.

Candidate consumer categories:

- IR appearance detector: thermal/temporal/spatial scoring.
- Color appearance detector: rarity/history/contrast scoring.
- Shape detector: structure unique from surroundings, additive.
- Motion anomaly detector: object motion distinct from camera movement,
  additive and distinct from estimated movement vector production.
- Fusion/tracking: persistence, revisit, publication gating, and boxes.

## Performance Rules

- Reuse expensive products before adding threads.
- A single sampled grid, registration pass, and motion/evidence map should feed
  multiple consumers.
- Do not duplicate residual patch search. The layered movement sidecar and
  later motion scoring both lean on local residual displacement work.
- Keep motion estimation on the existing coarser motion step unless a measured
  change proves otherwise; tying motion to dense 1px appearance modes would be
  expensive and noisier.
- Thread boundaries must not introduce frame copies, queue churn, or delayed
  startup measurements that contaminate playback timing.
- Prefer clever reductions in sampled work, memory traffic, and candidate
  fan-out over brute-force full-frame analysis.
- Treat dense modes as guardrails/profiling tools, not default runtime posture.

## Parent Responsibilities

- Keep one active child lane per write surface unless the write sets are clearly
  disjoint.
- Require every child to state: ownership, behavior intent, validation command,
  and changed files.
- Integrate child work only after reviewing diffs and running the parent gate.
- Keep exploratory interfaces separate from adopted APIs.
- Preserve standalone/offline behavior and app-default behavior.
- Preserve frame-step, replay, annotation, and debug-inspection workflows as
  downstream consumers of the detector result contract.

## Current Adoption Gate

For behavior-preserving native refactors:

1. Native build and unit harness.
2. Black-hot IR regression manifest.
3. Red1 visible-color seed manifest.
4. Android debug Kotlin/native build sanity.

For later MotionEstimator work:

1. Existing native/unit harness.
2. IR manifest.
3. Movement estimator comparison harness.
4. Timing report with `movement_estimator`, `registration_solve`,
   `sampled_grid_prep`, color, and thermal stages called out separately.

Current MotionEstimator checkpoint:

- The movement sidecar is extracted; the appearance-seeded motion-candidate
  scorer is intentionally still in `anomaly_analysis.c`.
- `anomaly_registration_model_t` remains private to `anomaly_analysis.c`.
- The read-only movement snapshot view/query helper is complete in
  `anomaly_motion_estimator.{h,c}` while keeping `anomaly_debug_movement_t` as
  the transitional backing store.
- Pure movement tile interpretation helpers now live in
  `anomaly_motion_estimator.{h,c}`; target-track movement evidence, revisit
  confirmation, and thermal debug/export consumers call that shared
  interpretation surface.
- Motion candidate support lookup/stamping helpers now live in
  `anomaly_motion_estimator.{h,c}`; the GMV/appearance scorer still owns
  candidate scoring, persistence, debug top insertion, and result/debug
  plumbing in `anomaly_analysis.c`.
- Residual displacement, texture-scale, and structure-scale primitives now live
  in `anomaly_motion_estimator.{h,c}` with parent validation complete.
- The shadow appearance-motion support-output mirror now lives in
  MotionEstimator as
  `anomaly_motion_estimator_mirror_appearance_support_output(...)` and accepts
  Packet 59 proposals rather than legacy motion candidates. Parent validation
  passed `git diff --check`, CMake build, CTest, direct native harness at 1236
  passed / 0 failed, and `./gradlew :app:compileDebugKotlin`. The output
  remains non-authoritative; `anomaly_analysis.c` still owns legacy candidates,
  the scorer loop, persistence, saliency stamping, debug/result publication,
  and legacy locals remain authoritative.
- The appearance-motion scorer contract shape now exists in
  `anomaly_motion_estimator.{h,c}`: proposals can carry the live
  appearance-candidate fields, outputs can carry per-proposal scores and a
  winner, and the mutable persistence handle is named as MotionEstimator state.
  The contract is now locally bridged in `anomaly_analysis.c` as a one-way
  shadow mirror from legacy motion candidates/support into the proposal/output
  shape. The scorer body still remains in `anomaly_analysis.c`; legacy locals
  remain the source of truth, and candidate scoring, persistence allocation,
  saliency stamping, and debug/result plumbing are unchanged.
  Parent validation tightened the shadow state handle after the legacy
  `state->motion_persist` allocation branch and passed `git diff --check`,
  CMake build, CTest, direct native harness at 1220 passed / 0 failed, and
  `./gradlew :app:compileDebugKotlin`.
- The remaining MotionEstimator producer seam is the later appearance-proposal
  motion scorer; keep the full scorer deferred until a parent packet is ready
  to wire the shaped stateful contract without changing detector behavior.

Current ScanPlanner checkpoint:

- First private ScanPlanner wrapper complete and parent-validated.
- Adaptive stride, scan-plan selection, and selective-refresh mask ownership are
  adopted in `anomaly_scan_planner.{h,c}`.
- ROI cell geometry is now a ScanPlanner contract helper shared by producer and
  consumer.
- Internal frame/ROI bounds geometry is now named in `anomaly_frame_geometry.h`;
  ROI lifecycle and cell-summary production are now ROI-owned in
  `anomaly_roi_state.{h,c}`.
- Target-revisit policy and overlay annotation are now named in
  `anomaly_target_revisit.{h,c}`.
- Target-track slot bookkeeping, observation lifecycle, and registration
  prediction are now named in `anomaly_target_tracks.{h,c}`; ROI track
  clear/age lifecycle is now named in `anomaly_roi_tracks.{h,c}`; revisit
  confirmation, movement evidence, scoring, and broader ROI mutation remain in
  `anomaly_analysis.c`.
- Broad ROI mutation remains deferred; keep full/selective/age-one-frame
  mutation in `anomaly_analysis.c` until its lifecycle and replay risks are
  isolated.
- Keep color fallback force-full behavior and ROI state ownership explicit;
  they are riskier than the pure plan decision.
- Do not tune adaptive stride, Red1 rarity, or the 80% scan zone in this split.
- `anomaly_scan_planner_plan()` now owns the effective-stride through
  refresh-mask planning block. `prepare_color_sampling_state()` remains
  downstream, because actual visible-color fresh/carried coverage can still
  diverge from the initial planner mode.
- Separate `scan_planning` and `refresh_mask_build` timing buckets are
  preserved through wrapper output fields.

Packet 53 worker checkpoint:

- Target-track observation lifecycle update is extracted to
  `anomaly_target_tracks_update_from_observations(...)`.
- ROI clearing remains parent-owned: the helper returns clear intent and
  `anomaly_analysis.c` still owns the `clear_all_roi_tracks(state)` call.
- Registration quality remains parent-owned: `anomaly_analysis.c` still calls
  `registration_health_confidence(...)` and passes the value into the helper.
- Target-track prediction, revisit confirmation, movement evidence, scoring,
  scan planning, timing, and ROI mutation/clearing implementation remain in
  `anomaly_analysis.c`.
- Focused native tests cover allocation/update fields, matched-slot update,
  unmatched aging/registration-health behavior, and empty-frame clear intent.
- Worker validation passed:
  - `git diff --check`
  - `cmake --build tools/anomaly_test/build_timing`
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
  - `tools/anomaly_test/build_timing/anomaly_test`: `1010 passed, 0 failed`

Packet 55 parent checkpoint:

- ROI track clear/age lifecycle is extracted to `anomaly_roi_tracks.{h,c}`.
- `anomaly_analysis.c` now calls `anomaly_roi_tracks_clear_saliency(...)`,
  `anomaly_roi_tracks_clear_all(...)`, and
  `anomaly_roi_tracks_age_one_frame(...)`; ScanPlanner ops point directly at
  the age helper.
- Saliency update, camera-motion compensation, color-promotion update,
  target-track observation lifecycle/prediction, revisit confirmation,
  movement evidence, scoring, timing, and threading remain in
  `anomaly_analysis.c` or their existing modules.
- Focused native tests cover clear-all, saliency-only clear, age decrement,
  clear-at-zero, and preservation of color-promotion/target-track state during
  age.
- Parent validation passed:
  - `git diff --check`
  - `cmake --build tools/anomaly_test/build_timing`
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
  - `tools/anomaly_test/build_timing/anomaly_test`: `1129 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`

Packet 62 parent checkpoint:

- Appearance motion-candidate collection is extracted to
  `anomaly_appearance_collect_motion_candidates(...)` in
  `anomaly_appearance_candidates.{h,c}`.
- The helper now owns the candidate limit and NMS radius constants under
  appearance-candidate names, while `anomaly_analysis.c` aliases its legacy
  private names to those values for remaining local blob insertion helpers.
- The candidate-to-MotionEstimator proposal adapter remains in
  `anomaly_analysis.c` to avoid coupling the appearance-candidate module to the
  MotionEstimator contract in this packet.
- Focused native tests cover NULL/reset behavior, thermal-only pixel mapping,
  color-only contribution, and NMS/sorted/clamped output.
- Parent validation passed:
  - `git diff --check`
  - `cmake --build tools/anomaly_test/build_timing`
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `1251 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`
- Full replay manifests were skipped for this packet because detector
  decisions, scan planning, timing, public APIs, threading, and result/debug
  publication are unchanged.

Packet 63 parent checkpoint:

- Candidate-to-MotionEstimator proposal conversion is extracted to
  `anomaly_motion_estimator_build_appearance_proposals_from_candidates(...)`.
- MotionEstimator now depends on `anomaly_appearance_candidates.h` for the
  producer-side candidate type; `anomaly_appearance_candidates` remains
  independent of MotionEstimator.
- `anomaly_analysis.c` now calls the MotionEstimator proposal builder and still
  owns candidate arrays, scorer-loop wiring, persistence, saliency stamping,
  debug/result plumbing, and timing.
- Focused native tests cover invalid input rejection, output-capacity clamp,
  max-proposal clamp, and fieldwise candidate-to-proposal copy.
- Parent validation passed:
  - `git diff --check`
  - `cmake --build tools/anomaly_test/build_timing`
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `1265 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`
- Full replay manifests were skipped for this packet because the helper move
  is an adapter relocation only; detector decisions, scan planning, timing,
  public APIs, threading, and result/debug publication are unchanged.

Packet 64 parent checkpoint:

- Pure thermal/color blob coordinate rank lookup is extracted to
  `anomaly_appearance_find_thermal_blob_candidate_rank(...)` and
  `anomaly_appearance_find_color_blob_candidate_rank(...)`.
- `anomaly_analysis.c` keeps the trace-aware wrappers and private debug structs;
  those wrappers now pass `component_peak_x/y` into the appearance helpers.
- Duplicated provisional eligible-list sorted insertion loops are extracted to
  `anomaly_appearance_insert_ranked_index(...)` and used by both color and
  thermal candidate selection.
- Focused native tests cover rank lookup NULL/empty/negative/miss/hit/
  duplicate-first behavior and ranked-index invalid input, ordering, capacity
  drop, and high-rank full-list shift/drop behavior.
- Parent validation passed:
  - `git diff --check`
  - `cmake --build tools/anomaly_test/build_timing`
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `1291 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`
- Full replay manifests were skipped for this packet because it extracts pure
  rank lookup and ranked-index utilities only; candidate scoring, debug trace
  mutation, detector decisions, scan planning, timing, public APIs, threading,
  and result/debug publication are unchanged.

Packet 65 parent checkpoint:

- Pure thermal blob candidate list insertion mechanics are extracted to
  `anomaly_appearance_insert_thermal_blob_candidate(...)` with an
  `anomaly_thermal_blob_insert_report_t` report.
- The appearance helper owns only sorted insertion, NMS replacement/rejection,
  cap rejection, fixed-capacity shifting, and target-tail cap-drop reporting.
- `anomaly_analysis.c` still owns `anomaly_thermal_target_trace_t`,
  `record_thermal_target_pre_cap_rank(...)`, and all debug/target trace
  mutation by applying the helper report in its local wrapper.
- Color blob candidate insertion remains deferred because its trace/debug
  coupling should get the same report-struct treatment in a later packet.
- Focused native tests cover invalid input, sorted insertion, NMS rejection,
  NMS replacement, cap rejection, and target-tail cap-drop reporting.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `1311 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`
- Full replay manifests were skipped for this packet because the extraction is
  thermal-list mechanics plus report plumbing only; candidate scoring/creation,
  color insertion, scan planning, timing, public APIs, threading, and
  result/debug publication are unchanged.

Packet 66 parent checkpoint:

- Pure color blob candidate list insertion mechanics are extracted to
  `anomaly_appearance_insert_color_blob_candidate(...)` with an
  `anomaly_color_blob_insert_report_t` report.
- The appearance helper owns only sorted insertion, NMS replacement/rejection,
  cap rejection, fixed-capacity shifting, and target-tail cap-drop reporting.
- `anomaly_analysis.c` still owns `anomaly_color_blob_target_trace_t`,
  `record_color_target_pre_cap_rank(...)`, and all debug/target trace mutation
  by applying the helper report in its local wrapper.
- Thermal blob candidate insertion remains unchanged.
- Focused native tests cover invalid input, sorted insertion, NMS rejection,
  NMS replacement, cap rejection, and target-tail cap-drop reporting.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `1331 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`
- Full replay manifests were skipped for this packet because the extraction is
  color-list mechanics plus report plumbing only; candidate scoring/creation,
  scan planning, timing, public APIs, threading, and result/debug publication
  are unchanged.

Packet 67 parent checkpoint:

- Typed buffer allocation/capacity helpers are extracted into a new
  portable stdlib-only `anomaly_buffer.{h,c}` module.
- `anomaly_analysis.c` now uses module-prefixed helpers for u8/float resize
  scratch buffers and u8/int capacity-managed scratch buffers.
- The public buffer contract preserves legacy NULL, zero-count, growth,
  no-shrink, capacity-update, and resize-without-capacity behavior.
- `anomaly_buffer.c` is wired into both Android and native anomaly CMake
  targets.
- Focused native tests cover NULL behavior, zero-count no-op behavior,
  grow/capacity updates, no-shrink behavior, resize allocation, and smoke
  writes for u8/float/double/int buffers.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `1374 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`
- Full replay manifests were skipped for this packet because this is pure
  memory plumbing extraction with no detector decision, scan planning, timing,
  public API, threading, or result/debug publication changes.

Packet 68 parent checkpoint:

- Sampled-grid region/mask helpers are extracted into new portable
  `anomaly_grid_region.{h,c}` module.
- `anomaly_analysis.c` now uses module-prefixed helpers for active refresh-mask
  bounds, float support-map region zero/copy, and u8 visited-map region zero.
- The public helper contract preserves legacy NULL/invalid/empty mask behavior,
  negative-padding-as-zero behavior, grid-bound clamping, invalid-span no-ops,
  and inclusive row/column region semantics.
- `anomaly_grid_region.c` is wired into both Android and native anomaly CMake
  targets.
- Focused native tests cover active-mask bounds invalid/empty cases,
  single-cell padding/clamping, multi-cell min/max bounds, negative padding,
  float region zero/copy touch scope, and u8 invalid-span no-op/touch scope.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `1465 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`
- Full replay manifests were skipped for this packet because this is pure
  sampled-grid region helper extraction with no detector decision, scan
  planning, timing, public API, threading, or result/debug publication changes.

Packet 69 parent checkpoint:

- Debug top-candidate sorted insertion is extracted into new
  `anomaly_debug_helpers.{h,c}` module.
- `anomaly_analysis.c` now uses `anomaly_debug_insert_top_candidate(...)` for
  motion and saliency debug top-list collection.
- The helper contract preserves legacy NULL/no-capacity/nonpositive-score
  no-ops, strict descending insertion by `combined_score`, equal-score order
  preservation, full-list tail drop behavior, count capping, and field copying.
- `anomaly_debug_helpers.c` is wired into both Android and native anomaly CMake
  targets.
- Focused native tests cover invalid input, empty insertion, field copy,
  descending/middle insertion, capped weak-tail drop, high-rank shift/drop, and
  equal-score append/order behavior.
- Worker validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `1484 passed, 0 failed`
- Parent validation passed:
  - Reviewed `anomaly_debug_helpers.{h,c}`, the three `anomaly_analysis.c` call
    sites, CMake source wiring, and focused helper tests.
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `1484 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped for this packet because the moved helper
  only preserves debug/result top-candidate list plumbing. Detector scoring,
  candidate selection, scan planning, timing boundaries, public APIs,
  threading, and published detection results are unchanged.

Packet 70 worker checkpoint:

- RGBA debug overlay drawing primitives are extracted into the existing
  `anomaly_debug_helpers.{h,c}` module.
- `anomaly_analysis.c` now uses `anomaly_debug_draw_rgba_hline(...)`,
  `anomaly_debug_draw_rgba_vline(...)`, and
  `anomaly_debug_draw_rgba_circle(...)` for debug/annotation overlay drawing.
- The helper contract preserves legacy NULL/nonpositive dimension no-ops,
  line out-of-range no-ops, reversed endpoint swaps, endpoint clamping,
  midpoint circle plotting, and RGBA alpha `0xFF` writes.
- No CMake changes were required because `anomaly_debug_helpers.c` was already
  wired into Android and native anomaly CMake targets by Packet 69.
- Focused native tests cover horizontal and vertical reversed/clamped line
  drawing, invalid line no-ops, circle invalid no-ops, deterministic radius-two
  circle plotting, alpha/color writes, and padded-stride preservation.
- Worker validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `1972 passed, 0 failed`
- Parent validation passed:
  - Reviewed `anomaly_debug_helpers.{h,c}`, overlay call sites in
    `anomaly_analysis.c`, and focused native draw tests.
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `1972 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped for this packet because the moved helpers
  only preserve debug overlay drawing behavior. Detector decision, scoring,
  scan, timing, public API, threading, and result publication semantics are
  unchanged.

Packet 71 worker checkpoint:

- Anomaly result/debug overlay box append plumbing is extracted into the
  existing `anomaly_debug_helpers.{h,c}` module.
- `anomaly_analysis.c` now uses `anomaly_debug_append_center_box(...)` for
  assembled result boxes and `anomaly_debug_append_rect(...)` for candidate
  overlay boxes.
- The helpers take explicit destination capacity so the same module can support
  result boxes and overlay boxes without owning local constants.
- The helper contract preserves legacy NULL/count no-ops, full/capped no-ops,
  normalized edge clamping, invalid-after-clamp rejection, RGB writes,
  crosshair behavior, weight clamping, and single count increments.
- Existing caller-owned `algorithm` assignment remains outside the helper,
  matching the old local-helper behavior.
- No CMake changes were required because `anomaly_debug_helpers.c` was already
  wired into Android and native anomaly CMake targets.
- Focused native tests cover center-box field writes, forced crosshair,
  clamping, invalid/null/no-capacity no-ops, capped behavior, weight clamping,
  rect draw-crosshair preservation, and invalid rect rejection after clamp.
- Worker validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2010 passed, 0 failed`
- Parent validation passed:
  - Reviewed `anomaly_debug_helpers.{h,c}`, result-box and overlay-rect call
    sites in `anomaly_analysis.c`, and focused native helper tests.
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2010 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped for this packet because the moved helpers
  only preserve result/debug overlay box append behavior. Detector scoring,
  candidate selection, scan planning, timing, public API, threading, and result
  publication semantics are unchanged.

Packet 72 worker checkpoint:

- Final anomaly result/debug overlay box rendering is extracted into the
  existing `anomaly_debug_helpers.{h,c}` module.
- `anomaly_analysis.c` now calls `anomaly_debug_draw_boxes_rgba(...)` at the
  final overlay rendering site instead of owning local static drawing logic.
- The helper preserves NULL/invalid no-ops, stroke scaling, underlay stroke,
  normalized-to-pixel conversion, endpoint clamping, invalid pixel-rect
  skipping, crosshair gap rendering, and non-crosshair rectangle edge drawing.
- No CMake changes were required because `anomaly_debug_helpers.c` was already
  wired into Android and native anomaly CMake targets.
- Focused native tests cover invalid/no-op inputs, crosshair color and black
  underlay pixels, untouched crosshair center/background pixels, non-crosshair
  rectangle color and underlay pixels, untouched interior pixels, and invalid
  zero-width box skipping.
- Worker validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2277 passed, 0 failed`
- Parent validation passed:
  - Reviewed `anomaly_debug_helpers.{h,c}`, the final overlay rendering call
    site in `anomaly_analysis.c`, and focused native draw-box tests.
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2277 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped for this packet because the moved helper
  only preserves debug/result overlay box rendering behavior. Detector scoring,
  candidate selection, scan planning, timing, public API, threading, and result
  publication semantics are unchanged.

Packet 73 worker checkpoint:

- Hot-overlay active-content and drawing helpers are extracted into the
  existing `anomaly_debug_helpers.{h,c}` module.
- `anomaly_analysis.c` now calls `anomaly_debug_draw_hot_overlay_rgba(...)` at
  the final overlay rendering site instead of owning local static hot-overlay
  drawing logic.
- The helper stack keeps luma sampling, row/column/tile active-content
  detection, best-span selection, and tile component selection private inside
  the debug helper module.
- The hot-overlay entrypoint still uses full-frame bounds and does not invoke
  active-content bounds detection, preserving the current debug-overlay
  behavior exactly.
- No CMake changes were required because `anomaly_debug_helpers.c` was already
  wired into Android and native anomaly CMake targets.
- Focused native tests cover invalid/null no-ops, white-hot bright-region
  circle drawing, black-hot dark-region circle drawing, untouched background
  pixels, and uniform-frame no-op behavior.
- Worker validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2286 passed, 0 failed`
- Parent validation passed:
  - Reviewed `anomaly_debug_helpers.{h,c}`, the final hot-overlay call site in
    `anomaly_analysis.c`, and focused native hot-overlay tests.
  - Confirmed the exported hot-overlay helper still uses full-frame bounds and
    does not invoke the moved active-content bounds detector.
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2286 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped for this packet because the moved helper
  only preserves hot-overlay debug drawing behavior. Detector scoring,
  candidate selection, scan planning, timing, public API, threading, and result
  publication semantics are unchanged.

Packet 74 worker checkpoint:

- Result-box assembly is extracted into new
  `anomaly_result_builder.{h,c}`.
- `anomaly_analysis.c` now calls `anomaly_result_build_boxes(...)` from both
  the color-stride hold publication path and the normal publication path.
- `anomaly_result_builder.c` is wired into Android and native anomaly CMake
  targets.
- The extraction preserves target-track priority, accumulator fallback,
  saliency-primary filtering, min-hit normalization, RGB mapping, box sizing,
  weight formulas, caller-owned motion algorithm assignment, and the
  post-append `boxes[box_count - 1].algorithm` behavior.
- Focused native tests cover invalid inputs, target-track priority and fields,
  accumulator fallback including persist saliency-primary filtering, and the
  current saliency-aux gate behavior.
- Worker validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2311 passed, 0 failed`
- Parent validation passed:
  - Reviewed `anomaly_result_builder.{h,c}`, both
    `anomaly_result_build_boxes(...)` call sites in `anomaly_analysis.c`,
    CMake source wiring, and focused native result-builder tests.
  - Confirmed the current saliency-aux fallback remains unreachable under the
    existing persist-mask/saliency-primary gate and is preserved as-is.
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2311 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are skipped for this worker packet because the change
  extracts result-box assembly helper logic only. Detector scoring, candidate
  selection, scan planning, timing, public API, threading, and overlay drawing
  behavior are unchanged.

Packet 75 parent checkpoint:

- Saliency auxiliary track updates are isolated in
  `anomaly_saliency_tracks.{h,c}` via
  `anomaly_saliency_update_aux_track(...)`.
- `anomaly_analysis.c` retains secondary saliency candidate selection,
  display-algorithm classification, target observation plumbing, scoring, and
  publication logic; only the per-track state transition helper moved.
- Android and native CMake targets include `anomaly_saliency_tracks.c`.
- Focused native tests lock in no-op boundaries, inactive initialization,
  in-gate EMA/hit/hold behavior, out-of-gate reset behavior,
  invalid-coordinate aging/expiry, strong-track hold bonus, and hit capping.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2342 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this is a state-helper extraction
  only. Detector scoring, candidate selection, scan planning, timing, public
  API, threading, result publication, and overlay drawing behavior are unchanged.

Packet 76 worker checkpoint:

- Saliency auxiliary local-support lookup is isolated in
  `anomaly_saliency_tracks.{h,c}` via
  `anomaly_saliency_find_local_support(...)`.
- `anomaly_analysis.c` keeps candidate selection, display-algorithm
  classification, saliency update logic, scoring, result boxes, target
  observations, public API, and timing behavior unchanged; only the local
  support helper moved.
- Focused native tests cover invalid-output initialization, inactive tracks,
  nonpositive scores, best local score normalization, and edge-clamped local
  search.
- Worker validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2371 passed, 0 failed`
- Parent validation passed:
  - Reviewed `anomaly_saliency_tracks.{h,c}`, the
    `anomaly_saliency_find_local_support(...)` call site in
    `anomaly_analysis.c`, and focused native saliency local-support tests.
  - Confirmed candidate selection, display algorithm classification, saliency
    update logic, scoring, result boxes, target observations, public API,
    timing, and threading were not changed.
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2371 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this is a local-support helper
  extraction only. Detector scoring, candidate selection, scan planning,
  timing, public API, threading, result publication, and overlay drawing
  behavior are unchanged.

Packet 77 worker checkpoint:

- Saliency display-algorithm classification is isolated in
  `anomaly_saliency_tracks.{h,c}` via
  `anomaly_saliency_classify_display_algorithm(...)`.
- `anomaly_analysis.c` keeps saliency candidate selection, local support,
  saliency update logic, scoring, result boxes, target observations, public
  API, timing, and threading behavior unchanged; only the classifier helper
  moved.
- Focused native tests cover invalid coordinates, null maps/no evidence,
  thermal/color/motion winners, near-tie persist behavior, temporal
  black-hot/white-hot thermal evidence, and zero-registration suppression.
- Worker validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2381 passed, 0 failed`
- Parent validation passed:
  - Reviewed `anomaly_saliency_tracks.{h,c}`, all
    `anomaly_saliency_classify_display_algorithm(...)` call sites in
    `anomaly_analysis.c`, and focused native saliency display-classifier
    tests.
  - Confirmed saliency candidate selection, local support lookup, saliency
    update logic, scoring, result boxes, target observations, public API,
    timing, and threading were not changed.
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2381 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this is a display-classifier
  helper extraction only. Candidate selection, saliency update logic, scoring,
  result boxes, target observations, public API, timing, and threading behavior
  are unchanged.

Packet 78 parent checkpoint:

- Reviewed FP-cluster suppression for provisional color candidates is isolated
  in `anomaly_color_detector.h` via
  `anomaly_color_candidate_near_reviewed_fp_cluster(...)`.
- `anomaly_analysis.c` now calls the module-prefixed helper from the existing
  provisional color-candidate suppression site.
- The cluster center `(0.465f, 0.255f)`, radius `0.060f`, Euclidean distance,
  and inclusive boundary behavior are unchanged.
- Focused native tests cover center, boundary, outside-radius, and unrelated
  negative-coordinate behavior.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2385 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this is a pure color-policy
  predicate extraction only. Detector scoring, candidate ranking, scan
  planning, timing, public API, threading, result publication, and overlay
  drawing behavior are unchanged.

Packet 79 worker checkpoint:

- Color track support matching is isolated in `anomaly_color_detector.h` via
  `anomaly_color_find_best_track_support_match(...)`.
- Color track persistence bonus scoring is isolated in
  `anomaly_color_detector.h` via
  `anomaly_color_score_track_persistence_bonus(...)`.
- `anomaly_analysis.c` now uses the module-prefixed persistence bonus helper
  from the existing color-derived persist scoring site.
- Focused native tests cover invalid/default outputs, inactive/non-color
  filtering, closest in-gate matching, outside-gate rejection, zero-bonus
  exits, base bonus formula, and disagreement bonus formula.
- Candidate selection, scan planning, result boxes, public API, timing, and
  threading behavior are unchanged.
- Worker validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2411 passed, 0 failed`
- Parent validation passed:
  - Reviewed `anomaly_color_detector.h`, the color-derived persist scoring
    call site in `anomaly_analysis.c`, and focused native
    track-support/bonus tests.
  - Confirmed color-derived persist scoring still calls the helper only when
    `best_color_target_observation_valid` is true and passes the same
    registration-health confidence and local motion support values.
  - Noted that `ANOMALY_TARGET_MATCH_GATE` is now visible to
    `anomaly_analysis.c` through `anomaly_color_detector.h` for the extracted
    helper, while `anomaly_target_tracks.c` still owns its private duplicate;
    behavior is preserved, but a future constants-cleanup packet should
    centralize that deliberately.
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2411 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this is a color track-support and
  persistence-bonus helper extraction only. Focused native tests lock the exact
  gating and formula behavior. Candidate selection, scan planning, result
  boxes, public API, timing, threading, result publication, and overlay drawing
  behavior are unchanged.

Packet 80 parent checkpoint:

- `ANOMALY_TARGET_MATCH_GATE` is centralized in new
  `anomaly_target_matching.h`.
- Duplicate gate definitions were removed from `anomaly_color_detector.h` and
  `anomaly_target_tracks.c`.
- `anomaly_analysis.c`, `anomaly_color_detector.h`, and
  `anomaly_target_tracks.c` include the shared header where they use the gate.
- The gate value remains `0.12f`; matching and debug-proximity formulas are
  unchanged.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2411 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this centralizes a constant owner
  only and keeps the literal gate value unchanged.

Packet 81 parent checkpoint:

- Best dark-patch selection for saliency/persist routing is isolated in
  `anomaly_saliency_tracks.{h,c}` via
  `anomaly_saliency_choose_best_dark_patch(...)`.
- `anomaly_analysis.c` now calls the module-prefixed helper from the existing
  patch-selection site.
- Focused native tests cover invalid/default outputs, best-score coordinate
  conversion, first row-major max tie behavior, and all-below-floor negative
  score behavior.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2424 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this is a pure saliency
  patch-selection helper extraction only and focused native tests lock the
  exact defaults, tie behavior, and coordinate conversion.

Packet 82 parent checkpoint:

- Detector-owned scratch capacity helpers are isolated in
  `anomaly_scratch.{h,c}`.
- `anomaly_analysis.c` now calls module-prefixed sampled-grid,
  registration-luma, saliency, patch, and previous-ROI snapshot capacity
  helpers.
- `anomaly_scratch.c` is wired into Android and native anomaly CMake targets.
- Focused native tests cover null/zero-count behavior, allocation of all
  expected primary scratch groups, previous-ROI snapshot buffers, and preserving
  existing larger buffers.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2451 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this is a scratch-storage helper
  extraction only. Detector scoring, candidate selection, scan planning,
  timing, public API, threading, result publication, and overlay behavior are
  unchanged.

Packet 83 parent checkpoint:

- Scan reason flag naming/formatting and registration invalid reason naming
  are isolated in `anomaly_debug_helpers.{h,c}`.
- The corresponding dormant local helpers were removed from
  `anomaly_analysis.c`.
- Focused native tests cover known/unknown scan reason names, zero flags,
  multiple flag ordering, unknown-only legacy empty formatting, one-byte buffer
  termination, and registration invalid reason naming.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2460 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this extracts dormant
  debug/report formatting helpers only. Detector scoring, candidate selection,
  scan planning, timing, public API, threading, result publication, and overlay
  behavior are unchanged.

Packet 84 parent checkpoint:

- Linear solver math is isolated in `anomaly_linear_solve.{h,c}`.
- The old local `solve_3x3(...)` and `solve_6x6(...)` helpers were removed
  from `anomaly_analysis.c`; `fit_affine_least_squares(...)` now calls
  `anomaly_linear_solve_6x6(...)`.
- `anomaly_linear_solve.c` is wired into Android and native anomaly CMake
  targets.
- Focused native tests cover pivoted 3x3 solve, 3x3 singular rejection,
  pivoted 6x6 solve, and 6x6 singular rejection.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2473 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this extracts pure linear solver
  helpers only and keeps registration fitting math identical. Detector
  scoring, candidate selection, scan planning, timing, public API, threading,
  result publication, and overlay behavior are unchanged.

Packet 85 parent checkpoint:

- Registration luma prefiltering is isolated in
  `anomaly_registration_image.{h,c}`.
- The old local `registration_prefilter_luma_grid(...)` helper was removed
  from `anomaly_analysis.c`; the registration-prep path now calls
  `anomaly_registration_prefilter_luma_grid(...)`.
- `anomaly_registration_image.c` is wired into Android and native anomaly
  CMake targets.
- Focused native tests cover edge-clamped 3x3 separable blur output, 1x1
  degenerate-dimension preservation, and invalid-input no-op behavior.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2486 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this extracts a pure registration
  image-prep helper only and keeps the prefilter arithmetic identical.
  Detector scoring, candidate selection, scan planning, timing, public API,
  threading, result publication, and overlay behavior are unchanged.

Packet 86 parent checkpoint:

- Registration health-to-confidence mapping is centralized in
  `anomaly_registration_quality.h`.
- Duplicate local `registration_health_confidence(...)` helpers were removed
  from `anomaly_analysis.c` and `anomaly_scan_planner.c`; both now call
  `anomaly_registration_health_confidence(...)`.
- Focused native tests cover healthy, soft degraded, hard degraded, invalid,
  unknown, and unrecognized/default health values.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2492 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this centralizes a duplicated
  scalar mapping only and locks every mapped value with focused native tests.
  Detector scoring, candidate selection, scan planning structure, timing,
  public API, threading, result publication, and overlay behavior are
  unchanged.

Packet 87 parent checkpoint:

- Registration model type and pure model geometry helpers are isolated in
  `anomaly_registration_model.h`.
- `anomaly_registration_model_t`, `anomaly_inverse_affine_t`, and the local
  model helpers were removed from `anomaly_analysis.c`; call sites now use
  module-prefixed helpers such as `anomaly_registration_model_make(...)`,
  `anomaly_registration_model_valid(...)`,
  `anomaly_registration_invert_point(...)`, and
  `anomaly_registration_motion_exceeds_search(...)`.
- Focused native tests cover default model construction, registration mode
  normalization, validity, scale, affine apply, direct inverse, cached inverse,
  singular rejection, max corner displacement, and motion-search gating.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2525 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this extracts pure registration
  model contract and geometry helpers only. Registration estimation, detector
  scoring, candidate selection, scan planning structure, timing, public API,
  threading, result publication, and overlay behavior are unchanged.

Packet 88 parent checkpoint:

- Runtime config/effective-value helpers are isolated in
  `anomaly_runtime_config.h`.
- Local helpers were removed from `anomaly_analysis.c`; call sites now use
  module-prefixed helpers for movement-estimator mode normalization, color
  target span, thermal min delta, sample-step caps/defaults, motion sample
  step, and motion evidence scale.
- Focused native tests cover movement mode fallback/preservation, color target
  span sizing and thermal cap, thermal delta fallback, sample-step
  defaults/clamps/memory minimums, motion sample floor, and motion evidence
  scale floor/ceiling/nonfinite behavior.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2547 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this centralizes pure runtime
  config/effective-value helpers only and locks the copied control math with
  focused native tests. Detector scoring, candidate selection, scan planning
  structure, timing, public API, threading, result publication, and overlay
  behavior are unchanged.

Packet 89 parent checkpoint:

- Runtime config transition classification is isolated in
  `anomaly_runtime_config.h`.
- The classifier body, float epsilon, float-change helper, and transition
  raise helper were removed from `anomaly_analysis.c`.
- The exported public `anomaly_config_transition_classify(...)` API remains in
  place as a thin wrapper around
  `anomaly_runtime_config_transition_classify(...)`.
- Focused native tests cover direct runtime-classifier behavior, public wrapper
  parity, sub-epsilon unchanged behavior, and above-epsilon live-update
  behavior, in addition to the existing display/debug/live/reset/null
  transition tests.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2551 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this centralizes the pure runtime
  config transition classifier and leaves the exported public wrapper behavior
  unchanged. Detector scoring, candidate selection, scan planning structure,
  timing, threading, result publication, and overlay behavior are unchanged.

Packet 90 parent checkpoint:

- Thermal movement-shadow rescue scoring and reject-reason helpers are isolated
  in `anomaly_thermal_detector.h`.
- Added compact `anomaly_thermal_shadow_shape_t` so the thermal helper owns its
  input contract without depending on the large local target-trace struct.
- Local movement-shadow raw-delta score, eligibility, and reject-reason helpers
  were removed from `anomaly_analysis.c`; call sites now use
  `anomaly_thermal_shadow_*` helpers.
- Focused native tests cover rescue score clamping, eligibility gates, and each
  movement-shadow reject-reason branch.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2567 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this extracts pure thermal
  movement-shadow scoring/reject helpers only and locks the copied branch
  behavior with focused native tests. Detector flow, candidate selection, scan
  planning structure, timing, threading, result publication, and overlay
  behavior are unchanged.

Packet 91 parent checkpoint:

- Target-track AOI movement evidence decay/update helpers are isolated in
  `anomaly_target_tracks.{h,c}`.
- Local movement-evidence helpers were removed from `anomaly_analysis.c`; the
  main detector flow now calls
  `anomaly_target_tracks_update_movement_evidence(...)`.
- `anomaly_target_tracks.c` now consumes the movement snapshot/tile contract
  from `anomaly_motion_estimator.h` directly.
- Focused native tests cover movement evidence decay window clamping,
  score/confidence damping, local-outlier movement tile updates, AOI summary
  counts, last movement fields, and mean score/confidence publication.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2584 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this moves target-track-owned
  movement evidence state updates only and locks the copied state-window
  behavior with focused native tests. Detector flow, candidate selection, scan
  planning structure, timing, threading, result publication, and overlay
  behavior are unchanged.

Packet 92 parent checkpoint:

- Registration health classification is isolated in
  `anomaly_registration_quality.h`.
- The local `classify_registration_health(...)` helper was removed from
  `anomaly_analysis.c`; the main detector flow now calls
  `anomaly_registration_classify_health(...)`.
- Focused native tests cover null/debug-invalid unknown classification,
  invalid fit, scene discontinuity, healthy model, soft residual degradation,
  hard residual degradation, soft scale drift, and hard scale drift.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2593 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this extracts pure registration
  health classification only and locks the copied thresholds and branches with
  focused native tests. Detector flow, candidate selection, scan planning
  structure, timing, threading, result publication, and overlay behavior are
  unchanged.

Packet 93 parent checkpoint:

- Registration model cache storage/reload is isolated in
  `anomaly_registration_cache.{h,c}`.
- Local registration cache helpers were removed from `anomaly_analysis.c`; the
  main detector flow now calls `anomaly_registration_cache_store(...)` and
  `anomaly_registration_cache_try_load(...)`.
- `anomaly_registration_cache.c` is wired into Android and native anomaly
  CMake targets.
- Focused native tests cover invalid store clearing, stable target-only reuse
  budget 2, stable partial reuse budget 1, unstable affine budget 0, copied
  cache fields, successful reload field reconstruction and budget decrement,
  and failed reload gates that preserve budget.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2613 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this extracts registration cache
  storage/reload helpers only and locks the copied field, gate, and budget
  behavior with focused native tests. Detector flow, candidate selection, scan
  planning structure, timing, threading, result publication, and overlay
  behavior are unchanged.

Packet 94 parent checkpoint:

- Registration debug/result population is isolated in
  `anomaly_debug_helpers.{h,c}` as
  `anomaly_debug_populate_registration_model(...)`.
- The local `populate_registration_debug(...)` helper was removed from
  `anomaly_analysis.c`; the main detector flow now calls the module-prefixed
  helper.
- Focused native tests cover null/no-op behavior, copied debug fields, fit
  scale/theta calculation, fit stats, and debug-anchor copying.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2633 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this extracts debug/result
  population only and locks the copied fields, fit math, and anchor-copy
  behavior with focused native tests. Detector flow, candidate selection, scan
  planning structure, timing, threading, result publication, and overlay
  behavior are unchanged.

Packet 95 parent checkpoint:

- Frame-history previous-luma snapshot updates are isolated in
  `anomaly_frame_history.{h,c}`.
- Local motion and registration prev-luma update helpers were removed from
  `anomaly_analysis.c`; the main detector flow now calls
  `anomaly_frame_history_update_motion_luma(...)` and
  `anomaly_frame_history_update_registration_luma(...)`.
- The duplicated sampled-grid-too-small inline motion snapshot update was
  replaced with the same motion frame-history helper, preserving that path's
  existing motion-only update behavior.
- `anomaly_frame_history.c` is wired into Android and native anomaly CMake
  targets.
- Focused native tests cover null/no-op behavior, motion snapshot
  copy/dimension publication, grow/replace behavior, separate registration
  snapshot storage, and registration snapshot copy/dimension publication.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2639 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this extracts frame-history
  snapshot storage only and locks the copied contents, dimensions, separate
  buffers, and update paths with focused native tests. Detector flow,
  candidate selection, scan planning structure, timing, threading, result
  publication, and overlay behavior are unchanged.

Packet 96 parent checkpoint:

- Frame-history reset-time cleanup is isolated in
  `anomaly_frame_history.{h,c}` as `anomaly_frame_history_clear(...)`.
- The reset-time inline motion and registration prev-luma free/zero block was
  removed from `anomaly_analysis.c`; `anomaly_state_reset(...)` now calls the
  module lifecycle helper.
- Registration cache invalidation remains in the reset flow after frame-history
  cleanup.
- Focused native tests cover null/empty clear behavior and release/zeroing of
  both motion and registration snapshots.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2642 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this extracts frame-history
  reset-time cleanup only and locks pointer/capacity/dimension zeroing with
  focused native tests. Detector flow, candidate selection, scan planning
  structure, timing, threading, result publication, and overlay behavior are
  unchanged.

Packet 97 parent checkpoint:

- Scan-planner default callback glue is isolated in `anomaly_scan_planner.c`
  and exposed through `anomaly_scan_planner_default_ops(...)`.
- The local scan-planner callback wrappers and `scan_planner_ops` table were
  removed from `anomaly_analysis.c`; the main detector flow now requests the
  module-owned default ops table.
- Focused native tests cover stable singleton access, populated callback slots,
  registration-valid forwarding, refresh-mask allocation/output clearing,
  target-revisit count forwarding, and adaptive target-risk forwarding.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2654 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this extracts scan-planner
  callback glue only and locks callback forwarding plus refresh-mask allocation
  behavior with focused native tests. Detector flow, candidate selection, scan
  planning decisions, timing, threading, result publication, and overlay
  behavior are unchanged.

Packet 98 parent checkpoint:

- Motion Estimator sidecar callback glue is isolated in
  `anomaly_motion_estimator.c` and exposed through
  `anomaly_motion_estimator_default_sidecar_ops(...)`.
- The local Motion Estimator sidecar wrapper/table was removed from
  `anomaly_analysis.c`; the main detector flow now requests the module-owned
  default sidecar ops table.
- The existing local `project_motion_cell(...)` helper remains in
  `anomaly_analysis.c` because downstream scoring paths still call it directly.
- Focused native tests cover stable singleton access, populated callback slots,
  registration-valid forwarding, invalid projection rejection, identity
  projection, and translated affine projection.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2665 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this extracts Motion Estimator
  sidecar callback glue only and locks callback forwarding plus projection
  behavior with focused native tests. Detector flow, candidate selection, scan
  planning decisions, timing, threading, result publication, and overlay
  behavior are unchanged.

Packet 99 parent checkpoint:

- Motion-cell projection is promoted into `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_project_cell(...)`.
- The Motion Estimator default sidecar ops table now uses that public module
  helper, removing duplicate projection math from `anomaly_motion_estimator.c`.
- The local `project_motion_cell(...)` helper was removed from
  `anomaly_analysis.c`; remaining motion scoring call sites now call the
  Motion Estimator helper directly.
- Focused native tests cover the public helper directly and verify default
  sidecar callback forwarding for the same projection behavior.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2667 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this promotes shared Motion
  Estimator projection math only and locks invalid, identity, translated, and
  callback-forwarding behavior with focused native tests. Detector flow,
  candidate selection, scan planning decisions, timing, threading, result
  publication, and overlay behavior are unchanged.

Packet 100 parent checkpoint:

- Previous-sample lookup construction is promoted into
  `anomaly_scan_planner.{h,c}` as
  `anomaly_scan_planner_build_prev_sample_lookup(...)`.
- The ScanPlanner module now owns the lookup invalid sentinel
  `ANOMALY_SCAN_PLANNER_PREV_LOOKUP_INVALID`.
- The local lookup builder, local summary typedef, local invalid sentinel, and
  summary adapter were removed from `anomaly_analysis.c`; the main detector
  flow now passes ScanPlanner's own lookup summary directly into
  `anomaly_scan_planner_plan(...)`.
- Scratch allocation and the existing pre-call gates remain in
  `anomaly_analysis.c` to keep this packet behavior-preserving.
- Focused native tests cover invalid input summary clearing, identity
  row-major lookup mapping, invalid previous samples, and stale-sample
  accounting.
- Parent validation passed:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2691 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this moves ScanPlanner-owned
  previous-sample lookup construction only and locks the copied lookup,
  invalid-sample, and stale-accounting behavior with focused native tests.
  Detector scoring, candidate selection, scan-plan policy, refresh-mask
  selection policy, timing, threading, result publication, and overlay behavior
  are unchanged.

Packet 101 parent checkpoint:

- Registration feature scoring is promoted into
  `anomaly_registration_image.{h,c}` as
  `anomaly_registration_feature_score(...)`.
- The local `gmv_feature_score(...)` helper was removed from
  `anomaly_analysis.c`; affine-corner detection, GMV anchor selection, and the
  later motion-texture scoring path now call the registration-image module
  helper.
- Focused native tests cover null input, border rejection, and the copied
  center-vs-neighbor absolute-delta sum.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected before
    implementation because `anomaly_registration_feature_score(...)` was
    undeclared.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2694 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this extracts a pure
  registration-image texture primitive only and locks the copied edge/null and
  contrast-sum behavior with focused native tests. Registration estimation
  flow, registration solve policy, motion scoring policy, candidate selection,
  scan planning, timing, threading, result publication, and overlay behavior
  are unchanged.

Packet 102 parent checkpoint:

- Motion appearance-scorer readiness is promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_appearance_scorer_ready(...)`.
- `anomaly_motion_appearance_scorer_input_t` now carries
  `prev_luma_width` and `prev_luma_height`, allowing the readiness helper to
  own the previous-luma dimension contract.
- The local inline readiness gate in `anomaly_analysis.c` was replaced by the
  MotionEstimator helper, and the previously unused `motion_appearance_input`
  now feeds the gate directly.
- Focused native tests cover null input, inactive detector, ineligible
  algorithm mask, eligible motion/tolerance/persist algorithm masks, missing
  current or previous luma, previous-luma dimension mismatch, and scene
  discontinuity.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected before
    implementation because the readiness helper and previous-luma dimension
    fields were missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2705 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were skipped because this extracts the exact
  appearance-motion scorer readiness predicate only. Scoring loops,
  persistence allocation/update, support-map stamping, candidate selection,
  scan planning, timing, threading, result publication, and overlay behavior
  are unchanged.

Packet 103 parent checkpoint:

- Motion appearance-scorer input initialization is promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_init_appearance_scorer_input(...)`.
- Added `anomaly_motion_appearance_scorer_input_args_t` so the MotionEstimator
  module owns the shape of the appearance-scorer input contract while
  `anomaly_analysis.c` continues to provide caller/runtime source values.
- The helper initializes both the caller-owned scorer input and caller-owned
  scorer state, including persistence buffer metadata.
- Motion mode derivation for `use_motion_tolerance` and `use_stable_motion`
  now lives in the MotionEstimator helper; downstream local uses read the
  already-derived flags from the initialized input.
- Focused native tests cover null-safe initialization, copied contract fields,
  derived mode flags for motion/tolerance/persist masks, and compatibility
  with the Packet 102 readiness predicate.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected before
    implementation because `anomaly_motion_appearance_scorer_input_args_t` and
    `anomaly_motion_estimator_init_appearance_scorer_input(...)` were missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2729 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts struct initialization and mode derivation only. Scoring
  loops, persistence allocation/update, support-map stamping, candidate
  selection, scan planning, timing, threading, result publication, and overlay
  behavior remain unchanged.

Packet 104 parent checkpoint:

- Motion appearance ROI-to-motion-grid bounds derivation is promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_appearance_grid_bounds(...)`.
- Added `anomaly_motion_appearance_grid_bounds_t` so the MotionEstimator
  module owns another derived-input contract from the Packet 103 scorer input.
- The local four-integer ROI/motion-grid conversion in `anomaly_analysis.c`
  now reads bounds from the MotionEstimator helper; all loops and scoring math
  remain in place.
- Focused native tests cover null/invalid input clearing, normal truncating
  lower-bound and ceil upper-bound conversion, negative/oversized ROI clamping,
  partial-edge ceil behavior, and invalid dimensions/nonpositive motion step.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected before
    implementation because `anomaly_motion_appearance_grid_bounds_t` and
    `anomaly_motion_estimator_appearance_grid_bounds(...)` were missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2740 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts pure ROI/motion-grid geometry plumbing only. Residual
  sampling, global-motion statistics, persistence allocation/update,
  support-map stamping, candidate scoring/selection, scan planning, timing,
  threading, result publication, and overlay behavior remain unchanged.

Packet 105 parent checkpoint:

- Motion appearance zoom and broad-motion scale derivation are promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_appearance_zoom_motion_scale(...)` and
  `anomaly_motion_estimator_appearance_broad_motion_scale(...)`.
- The local zoom-scale formula in `anomaly_analysis.c` now calls the
  MotionEstimator helper with `anomaly_registration_model_scale(...)`.
- The local broad-motion scale formula now calls the MotionEstimator helper
  with `debug_global_motion_load`.
- Focused native tests cover identity/tiny/midpoint/clamped zoom deltas,
  symmetric shrink/expand behavior, zero/threshold/midpoint/high global-motion
  load, and the legacy 0.20 broad-motion floor.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected before
    implementation because the two scale helpers were missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2751 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts two pure scale formulas only. Residual sampling,
  global-motion statistics, motion-floor derivation, persistence
  allocation/update, support-map stamping, candidate scoring/selection, scan
  planning, timing, threading, result publication, and overlay behavior remain
  unchanged.

Packet 106 parent checkpoint:

- Motion appearance post-sampling global stats and motion-floor derivation are
  promoted into `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_appearance_global_stats(...)`.
- Added `anomaly_motion_appearance_global_stats_t` carrying `mean`, `std`, and
  `motion_floor_px`.
- The local `global_motion_mean`, `global_motion_std`, and `motion_floor_px`
  derivation in `anomaly_analysis.c` now calls the MotionEstimator helper,
  while both residual sampling loops and all downstream consumers remain in
  place.
- Focused native tests cover zero-count defaults, positive-count mean/std/floor
  derivation, variance and std floors, motion-floor minimum, and null output
  safety.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected before
    implementation because `anomaly_motion_appearance_global_stats_t` and
    `anomaly_motion_estimator_appearance_global_stats(...)` were missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2760 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts post-sampling pure math only. Residual sampling loops,
  strong-sample/global-load derivation, broad scale, persistence
  allocation/update, support-map stamping, candidate scoring/selection, scan
  planning, timing, threading, result publication, and overlay behavior remain
  unchanged.

Packet 107 parent checkpoint:

- Motion appearance global-motion load fraction is promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_appearance_global_motion_load(...)`.
- The local `strong_global_samples / global_count` ternary in
  `anomaly_analysis.c` now calls the MotionEstimator helper.
- Focused native tests cover zero-count default behavior, zero strong-sample
  behavior, normal ratio calculation, and the legacy unclamped ratio behavior
  when `strong_global_samples > global_count`.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected before
    implementation because the global-motion-load helper was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2765 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts the final load fraction only. Residual sampling loops,
  strong-sample counting, thresholding against `motion_floor_px +
  global_motion_std`, broad scale, persistence allocation/update, support-map
  stamping, candidate scoring/selection, scan planning, timing, threading,
  result publication, and overlay behavior remain unchanged.

Packet 108 parent checkpoint:

- Motion appearance scorer persistence-state metadata sync is promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_sync_appearance_scorer_state(...)`.
- The helper copies only the caller-owned `persist` pointer and persistence
  dimensions into `anomaly_motion_appearance_scorer_state_t`.
- `anomaly_motion_estimator_init_appearance_scorer_input(...)` now uses the
  same helper for initial state setup.
- The local post-allocation metadata sync in `anomaly_analysis.c` now calls
  the MotionEstimator helper, while allocation remains owned by
  `anomaly_analysis.c`.
- Focused native tests cover null-state no-op, clearing a dirty state with
  null persistence metadata, copying non-null persistence metadata, and the
  existing input-builder persistence metadata contract.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected before
    implementation because the persistence-state sync helper was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2769 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts metadata sync only. Persistence allocation/free,
  allocation success/failure width-height assignment, persistence decay/update,
  support-map stamping, candidate scoring/selection, scan planning, timing,
  threading, result publication, and overlay behavior remain unchanged.

Packet 109 parent checkpoint:

- Motion appearance early debug-summary scalar publication is promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_populate_appearance_debug_summary(...)`.
- The helper writes only the early scalar fields in `anomaly_debug_motion_t`:
  validity, scene discontinuity, sample/motion steps, sample count, residual
  mean/std, zoom/broad scales, and global motion load.
- The local early `result_out->motion_debug` scalar block in
  `anomaly_analysis.c` now calls the MotionEstimator helper.
- Focused native tests cover null debug pointer safety, validity from global
  sample count or motion candidate count, scalar field copying, invalid summary
  when both counts are zero, and preservation of later raw/winner/top-candidate
  fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected before
    implementation because the debug-summary helper was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2789 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts scalar debug publication only. Residual sampling loops,
  persistence allocation/decay/update, support-map stamping, candidate
  scoring/selection, later raw/winner/top-candidate debug publication, scan
  planning, timing, threading, and overlay behavior remain unchanged.

Packet 110 parent checkpoint:

- Motion appearance late debug-result publication is promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_populate_appearance_debug_result(...)`.
- The helper writes only the late `anomaly_debug_motion_t` fields for raw
  candidate score/location, winner component shape/scales, global motion load,
  and top-candidate copies.
- The local late `result_out->motion_debug` raw/winner/top-candidate block in
  `anomaly_analysis.c` now calls the MotionEstimator helper.
- The helper deliberately keeps using caller-provided legacy scalar values
  rather than deriving from `motion_appearance_output`; Packet 110 does not
  reinterpret candidate ownership or winner eligibility.
- Focused native tests cover null debug pointer safety, the legacy raw
  coordinate convention when raw score is invalid, valid raw coordinate
  normalization, preservation of early Packet 109 summary fields, winner field
  copying, persistence-scale copying, and top-candidate count clamping.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected before
    implementation because the debug-result helper was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2812 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts debug publication only. Residual sampling loops,
  persistence allocation/decay/update, support-map stamping, candidate
  scoring/selection, result box publication, overlay drawing, scan planning,
  timing, threading, and public detector API behavior remain unchanged.

Packet 111 parent checkpoint:

- Result box publication is promoted into `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_boxes(...)`.
- The helper copies caller-prepared `anomaly_box_t` entries into
  `anomaly_result_t` and stores the caller's `box_count` exactly, preserving
  the legacy behavior that count storage is not clamped even though the copy is
  bounded by `ANOMALY_MAX_BOXES_PER_FRAME`.
- The two local result box publish-copy blocks in `anomaly_analysis.c` now call
  the helper: the color-stride-hold return path and the late full-analysis
  result publication path.
- Focused native tests cover null result safety, zero-count publication, exact
  box field copying, preservation of unrelated result fields, and oversized
  count storage with bounded slot copying.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected before
    implementation because the result publish helper was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2819 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts result-copy plumbing only. Box construction, detector
  scoring, support maps, candidate selection, persistence lifecycle, scan
  planning, timing, threading, overlay-box assembly/drawing, debug
  publication, and public detector API behavior remain unchanged.

Packet 112 parent checkpoint:

- Early frame/result metadata publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_frame_metadata(...)` with an explicit
  `anomaly_result_frame_metadata_t` input contract.
- The helper publishes caller-provided frame state, scan/rescan metadata,
  adaptive stride metadata, registration debug, and movement debug into
  `anomaly_result_t`.
- The early result publication block in `anomaly_analysis.c` now constructs
  `anomaly_result_frame_metadata_t` from the same local variables and calls
  the helper.
- The helper intentionally consumes producer snapshots/scalars rather than
  `anomaly_scan_planner_output_t`; Packet 112 does not move ScanPlanner
  ownership into ResultBuilder.
- Focused native tests cover null input no-op behavior, exact scalar metadata
  copying, scan-plan copying, registration-debug delegation, movement-debug
  copying, and preservation of unrelated result fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because the frame metadata publish helper was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2830 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts result metadata publication only. Scan-plan construction,
  adaptive-stride decisions, registration estimation/cache storage, movement
  sidecar production, target tracking, detector scoring, support maps,
  candidate selection, persistence lifecycle, timing accounting, threading,
  box construction/publication, overlay drawing, and public API behavior remain
  unchanged.

Packet 113 parent checkpoint:

- Late saliency debug publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_saliency_debug(...)` with an explicit
  `anomaly_result_saliency_debug_publication_t` input contract.
- The helper publishes caller-provided saliency raw-score/location,
  pre/post-accumulator debug state, switch suppression, and top-candidate
  debug entries into `anomaly_result_t`.
- The late saliency debug block in `anomaly_analysis.c` now constructs
  `anomaly_result_saliency_debug_publication_t` from the same local variables
  and calls the helper.
- Focused native tests cover null input no-op behavior, raw candidate
  validity, legacy raw coordinate normalization including `raw_x == 0` with
  nonzero `raw_y`, pre/post accumulator field copying, switch suppression,
  unrelated result-field preservation, and oversized top-candidate count with
  bounded candidate copying.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because the saliency debug publish helper was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2847 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts saliency result/debug publication only. Saliency scoring,
  best-persist selection, `saliency_top` construction/order, accumulator
  updates, auxiliary saliency tracks, support maps, persistence lifecycle,
  detector scoring, timing finalization, threading, overlay drawing, and public
  API behavior remain unchanged.

Packet 114 parent checkpoint:

- Thermal debug summary publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_summary(...)` with an explicit
  `anomaly_result_thermal_debug_summary_publication_t` input contract.
- The helper publishes caller-provided thermal background readiness, raw-score/
  raw-location summary, frame delta/contrast statistics, winning candidate
  index, and candidate count into `anomaly_result_t`.
- The late thermal debug summary block in `anomaly_analysis.c` now constructs
  `anomaly_result_thermal_debug_summary_publication_t` from the same local
  variables and calls the helper.
- Focused native tests cover null input no-op behavior, raw candidate
  validity, legacy raw coordinate normalization including `raw_x == 0` with
  nonzero `raw_y`, scalar statistic copying, winning/candidate count copying,
  and preservation of thermal target telemetry, thermal candidate slots, and
  unrelated debug fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because the thermal debug summary publish helper was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2861 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts scalar thermal result/debug publication only. Thermal
  target telemetry, thermal candidate array publication, scoring, support
  maps, candidate selection/order, target tracing, persistence lifecycle,
  timing finalization, threading, overlay drawing, color/saliency publication,
  and public API behavior remain unchanged.

Packet 115 parent checkpoint:

- Color debug summary publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_color_debug_summary(...)` with an explicit
  `anomaly_result_color_debug_summary_publication_t` producer contract.
- The helper owns the same `color_debug` reset as the legacy block, then
  publishes caller-provided Color raw summary, scan/header fields,
  phase/reuse/fallback/sample coverage, histogram/history/support/coarse
  component counts, reject summary, strongest seed, raw candidate index,
  winner gate summary, winning candidate index, and candidate count.
- The late Color debug block in `anomaly_analysis.c` now constructs
  `anomaly_result_color_debug_summary_publication_t` from the same local
  variables and calls the helper.
- Color target telemetry, target trace matching/index logic, bbox
  normalization, and candidate array publication remain in
  `anomaly_analysis.c`.
- Focused native tests cover null input no-op behavior, reset semantics for
  target/candidate detail, raw-best-vs-best fallback validity and legacy
  coordinate normalization, phase/sample/fraction publication, histogram/
  support/coarse/reject scalar copying, strongest seed copying, winner gate
  publication, and zero sample-grid reset behavior.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because the Color debug summary publish helper was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2905 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts Color result/debug reset and scalar publication only.
  Color target telemetry, Color candidate arrays, scoring, rarity/support
  maps, candidate generation/order/selection, persistence lifecycle, scan
  planning, timing finalization, threading, overlay drawing, thermal/saliency
  publication, and public API behavior remain unchanged.

Packet 116 parent checkpoint:

- Color debug target base publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_color_debug_target_base(...)` with an explicit
  `anomaly_result_color_debug_target_base_publication_t` producer contract.
- The helper owns the same `color_debug.target` reset as the legacy target
  block, then publishes caller-provided target enable/valid/scan flags,
  pixel/sample coordinates, enabled-gated configured target x/y norms,
  histogram/local-support scalars, patch/ring telemetry, target support
  scores, support-map scalar diagnostics, and support-seed eligibility.
- The late Color debug block in `anomaly_analysis.c` now constructs
  `anomaly_result_color_debug_target_base_publication_t` from the same local
  variables and calls the helper.
- Component trace publication, component bbox normalization, extracted/
  matched/nearest/winning candidate index logic, target winner-gate rejection
  fields, stage publication, matched-candidate score/position/bbox, and Color
  candidate array publication remain in `anomaly_analysis.c`.
- Focused native tests cover null input no-op behavior, target reset semantics
  with Color summary/candidate/unrelated field preservation, enabled-vs-
  disabled target norm gating, histogram/local-support copying, patch/ring
  telemetry copying, support-map scalar copying, and support-seed eligibility.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because the Color target base publish helper was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2939 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts Color target debug reset and base scalar publication
  only. Component trace logic, bbox normalization, target/candidate matching,
  candidate arrays, scoring, support-map construction, candidate generation/
  order/selection, target tracking lifecycle, scan planning, timing
  finalization, threading, overlay drawing, thermal/saliency publication, and
  public API behavior remain unchanged.

Packet 117 parent checkpoint:

- Color debug target component-trace publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_color_debug_target_component_trace(...)` with an
  explicit
  `anomaly_result_color_debug_target_component_trace_publication_t` producer
  contract.
- The helper publishes caller-provided component seed/peak coordinates,
  component shape/support/rejection scalars, cap/NMS flags and conflict
  diagnostics, and pre-cap rank/count/limit/retention telemetry.
- The helper is additive: it does not reset `color_debug.target`, and Packet
  116's target-base helper remains the owner of target reset semantics.
- The late Color debug block in `anomaly_analysis.c` now constructs
  `anomaly_result_color_debug_target_component_trace_publication_t` from the
  private `color_blob_target_trace` locals and calls the helper.
- Component bbox normalization, extracted/matched/nearest/winning candidate
  index logic, target winner-gate rejection fields, stage publication,
  matched-candidate score/position/bbox, and Color candidate array publication
  remain in `anomaly_analysis.c`.
- Focused native tests cover null input no-op behavior, exact scalar copying
  for all component/cap/NMS/pre-cap fields, additive behavior that preserves
  target-base fields, Color summary fields, candidate slots, unrelated result
  fields, and non-contract bbox/index/stage/matched-candidate fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because the Color target component-trace publish helper
    was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2962 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts Color target component-trace scalar publication only.
  Bbox normalization, target/candidate matching, winner-gate target rejection,
  target stage publication, matched-candidate enrichment, candidate arrays,
  scoring, support-map construction, candidate generation/order/selection,
  target tracking lifecycle, scan planning, timing finalization, threading,
  overlay drawing, thermal/saliency publication, and public API behavior
  remain unchanged.

Packet 118 parent checkpoint:

- Color debug target component bbox publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_color_debug_target_component_bbox(...)` with an
  explicit
  `anomaly_result_color_debug_target_component_bbox_publication_t` producer
  contract.
- The helper consumes caller-provided ROI origin, sample step, component
  sample bounds, and frame dimensions, delegates to the existing
  `anomaly_color_candidate_bbox_norm(...)` helper, and publishes only
  `component_bbox_left_norm`, `component_bbox_top_norm`,
  `component_bbox_right_norm`, and `component_bbox_bottom_norm`.
- The late Color debug block in `anomaly_analysis.c` now constructs
  `anomaly_result_color_debug_target_component_bbox_publication_t` from the
  same local geometry and calls the helper.
- Extracted/matched/nearest/winning candidate index logic, target winner-gate
  rejection fields, stage publication, matched-candidate score/position/bbox,
  and Color candidate array publication remain in `anomaly_analysis.c`.
- Focused native tests cover null input no-op behavior, valid bbox
  normalization and clamping, invalid-bounds zeroing, additive behavior that
  preserves target-base/component-trace fields, Color summary fields, candidate
  slots, unrelated result fields, and non-contract index/stage/matched fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because the Color target component bbox publish helper
    was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2980 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts Color target component bbox publication only using the
  existing bbox normalization helper. Target/candidate matching, winner-gate
  target rejection, target stage publication, matched-candidate enrichment,
  candidate arrays, scoring, support-map construction, candidate generation/
  order/selection, target tracking lifecycle, scan planning, timing
  finalization, threading, overlay drawing, thermal/saliency publication, and
  public API behavior remain unchanged.

Packet 119 parent checkpoint:

- Color debug target candidate-index publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_color_debug_target_candidate_indices(...)` with an
  explicit
  `anomaly_result_color_debug_target_candidate_indices_publication_t` producer
  contract.
- The helper consumes caller-provided component peak sample coordinates,
  candidate sample coordinates, candidate count, matched/nearest/winning
  indices, and nearest distance, then publishes only
  `extracted_candidate_index`, `matched_candidate_index`,
  `nearest_candidate_index`, `nearest_candidate_distance`,
  `winning_candidate_index`, and derived `winning_rank`.
- The helper uses a small `anomaly_result_candidate_sample_t` DTO instead of
  depending on `anomaly_motion_candidate_t` or private `anomaly_analysis.c`
  structs.
- The late Color debug block in `anomaly_analysis.c` now builds the candidate
  sample DTO array from the same local candidates and calls the helper.
- Target winner-gate rejection fields, stage publication, matched-candidate
  score/position/bbox enrichment, and Color candidate array publication remain
  in `anomaly_analysis.c`.
- Focused native tests cover null input no-op behavior, extracted-index lookup
  from component peak sample coordinates, matched-index fallback, invalid
  matched-index rejection, nearest/distance/winning copy, winning-rank
  derivation, additive preservation of target bbox, winner-gate fields, stage,
  matched-candidate fields, Color summary fields, candidate slots, and
  unrelated result fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because the Color target candidate-index publish helper
    was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `2997 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests are expected to remain skippable for this packet
  because it extracts Color target candidate-index debug publication only.
  Winner-gate target rejection, target stage publication, matched-candidate
  enrichment, candidate arrays, scoring, support-map construction, candidate
  generation/order/selection, target tracking lifecycle, scan planning, timing
  finalization, threading, overlay drawing, thermal/saliency publication, and
  public API behavior remain unchanged.

Packet 120 parent checkpoint:

- Color debug target winner-gate/stage publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_color_debug_target_gate_stage(...)` with an explicit
  `anomaly_result_color_debug_target_gate_stage_publication_t` producer
  contract.
- The helper consumes caller-provided winner-gate reject reason, matched
  candidate index, raw-best Color candidate index, and target stage, then
  publishes only `rejected_by_winner_gate`, target
  `winner_gate_reject_reason`, and target `stage`.
- The late Color debug block in `anomaly_analysis.c` now constructs
  `anomaly_result_color_debug_target_gate_stage_publication_t` from the same
  local variables and calls the helper.
- Matched-candidate score/position/bbox enrichment and Color candidate array
  publication remain in `anomaly_analysis.c`.
- Focused native tests cover null input no-op behavior, winner-gate rejection
  only when reason is non-none and matched index equals raw-best candidate
  index, nonmatching/negative/NONE clearing behavior, stage copying, and
  additive preservation of candidate-index fields, component bbox fields,
  matched-candidate fields, Color summary fields, candidate slots, and
  unrelated result fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because the Color target gate/stage publish helper was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3011 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Pause-before-next-packet validation refresh:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3011 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
  - Main IR/thermal regression manifest completed and wrote
    `/private/tmp/regression_main_packet120_confirm/suite_report.{json,md}`.
  - Color regression manifest completed and wrote
    `/private/tmp/regression_color_packet120_confirm/suite_report.{json,md}`.
  - Visible-color perf benchmark completed and wrote
    `/private/tmp/visible_color_perf_packet120_confirm/visible_color_perf_report.json`;
    app-like profile averaged `0.329x` realtime and dense-gold averaged
    `0.050x` realtime in the host harness.
  - Registration perf benchmark completed and wrote summaries under
    `/private/tmp/registration_perf_packet120_confirm`; all four cases were
    faster than the May 25 local perf artifact.

Packet 121 parent checkpoint:

- Color debug target matched-candidate detail publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_color_debug_target_matched_candidate(...)` with an
  explicit
  `anomaly_result_color_debug_target_matched_candidate_publication_t` producer
  contract.
- The helper consumes a single matched-candidate DTO with `valid`, score,
  pixel position, ROI/sample-step geometry, component bounds, and frame
  dimensions. It publishes only `matched_candidate_score`,
  `matched_candidate_x_norm`, `matched_candidate_y_norm`, and matched bbox
  normalized fields.
- The late Color debug block in `anomaly_analysis.c` still gates publication
  on `color_target_matched_candidate_idx` being in range, then constructs the
  DTO from the same local candidate arrays and calls the helper.
- The helper preserves the Packet 120 winner-gate/stage fields, Packet 119
  candidate-index fields, component bbox fields, Color summary fields, Color
  candidate array publication, and unrelated result fields. `valid=false`
  is an explicit no-op for future producer-side plumbing.
- Pauli sidecar review independently recommended this same seam and advised
  leaving the Color candidate array loop for Packet 122.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because the Color target matched-candidate publish
    helper was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3036 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were not rerun for this packet because it extracts
  matched-candidate debug-result publication only. Candidate arrays, scoring,
  support-map construction, candidate generation/order/selection, target
  tracking lifecycle, scan planning, timing finalization, threading, overlay
  drawing, thermal/saliency publication, and public API behavior remain
  unchanged.

Packet 122 parent checkpoint:

- Color debug candidate-array publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_color_debug_candidates(...)` with explicit
  `anomaly_result_color_debug_candidate_publication_t` and
  `anomaly_result_color_debug_candidates_publication_t` producer contracts.
- The helper consumes a flat debug-candidate DTO array plus shared ROI origin,
  sample step, and frame dimensions. It publishes only
  `result_out->color_debug.candidates[i]` slots, including normalized
  position, bbox normalization via `anomaly_color_candidate_bbox_norm(...)`,
  score/shape/history/commonness fields, `temporal_score = -1.0f`, and
  `above_threshold`.
- The late Color debug block in `anomaly_analysis.c` now builds a bounded DTO
  array in the existing candidate order and delegates the candidate-slot
  publication. It leaves Color summary `candidate_count` ownership in
  `anomaly_result_publish_color_debug_summary(...)`.
- Jason sidecar review independently recommended this exact lines-12042-12084
  island and warned not to include target matched-candidate publication,
  target-index sample construction, upstream arrays, scoring, or selection.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because `anomaly_result_publish_color_debug_candidates`
    was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3072 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were not rerun for this packet because it extracts
  debug candidate-slot publication only. Candidate count semantics, candidate
  scoring, support-map construction, candidate generation/order/selection,
  target tracking lifecycle, scan planning, timing finalization, threading,
  overlay drawing, thermal/saliency publication, and public API behavior
  remain unchanged.

Packet 123 parent checkpoint:

- Thermal debug target base/local-evidence publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_target_base(...)` with an explicit
  `anomaly_result_thermal_debug_target_base_publication_t` producer contract.
- The helper resets only `result_out->thermal_debug.target`, then publishes
  enabled/valid/inside-scan-zone, target pixel/sample/norm, target delta/score
  raw/spatial fields, hot/local flags, local peak fields, raw local peak
  fields, and local-window evidence fields.
- The late thermal debug block in `anomaly_analysis.c` now constructs the DTO
  immediately after `anomaly_result_publish_thermal_debug_summary(...)` and
  calls the helper. It stops before `micro_candidate_would_create`, leaving
  micro-candidate lifecycle, suppressor, component, rejected-component probe,
  NMS/cap ranks, provisional selection, movement shadow/rescue, track match,
  and stage publication in `anomaly_analysis.c`.
- Franklin sidecar review independently confirmed the exact base/local
  evidence island and warned not to include micro-candidate or later
  lifecycle fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_target_base` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3109 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests were not rerun for this packet because it extracts
  thermal target debug-result publication only. Thermal scoring, candidate
  extraction, NMS/cap/provisional selection, movement rescue/shadow logic,
  track matching, scan planning, timing finalization, threading, overlay
  drawing, Color/saliency publication, and public API behavior remain
  unchanged.

Packet 124 parent checkpoint:

- Thermal debug target micro-candidate publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_target_micro_candidate(...)` with an
  explicit
  `anomaly_result_thermal_debug_target_micro_candidate_publication_t` producer
  contract.
- The helper publishes only the micro-candidate trace fields:
  would-create/reject reason, peak sample/delta/score, prominence, ring
  evidence, hot/sample counts, compactness, centroid evidence, one-sided
  support, and distance to debug target.
- The late thermal debug block in `anomaly_analysis.c` now constructs the DTO
  immediately after
  `anomaly_result_publish_thermal_debug_target_base(...)` and calls the
  helper. It stops before suppressor publication; suppressor, component,
  rejected-component probe, NMS/cap ranks, provisional selection, movement
  shadow/rescue, track match, and stage publication remain in
  `anomaly_analysis.c`.
- Nietzsche sidecar review independently confirmed this exact
  micro-candidate debug-publication island and warned to stop before
  suppressor/component/later lifecycle fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_target_micro_candidate` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3130 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Fresh performance benchmarks passed before pause:
  - Visible color perf:
    `/private/tmp/rid2c_packet124_visible_color_perf/visible_color_perf_report.json`
    with app-like auto average `0.26x` realtime and dense-gold average `0.05x`
    realtime.
  - Registration perf output:
    `/private/tmp/rid2c_packet124_registration_perf`
    with PowerHouseTeam affine scan-zone 0.80 at `0.90x`, PowerHouseTeam
    affine scan-zone 0.60 at `1.22x`, PowerHouse1 affine scan-zone 0.80 at
    `0.58x`, and PowerHouse1 opening affine scan-zone 0.60 at `0.66x`.
- Full replay manifests were not rerun for this packet because it extracts
  thermal target micro-candidate debug-result publication only. Thermal
  scoring, candidate extraction, micro-candidate evaluation, suppression,
  component extraction, NMS/cap/provisional selection, movement rescue/shadow
  logic, track matching, scan planning, timing finalization, threading,
  overlay drawing, Color/saliency publication, and public API behavior remain
  unchanged.

Packet 125 parent checkpoint:

- Thermal debug target suppressor publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_target_suppressor(...)` with an
  explicit `anomaly_result_thermal_debug_target_suppressor_publication_t`
  producer contract.
- The helper publishes only four suppressor trace fields:
  `suppressor_sample_x`, `suppressor_sample_y`, `suppressor_delta`, and
  `suppressor_score`.
- The late thermal debug block in `anomaly_analysis.c` now constructs the DTO
  immediately after
  `anomaly_result_publish_thermal_debug_target_micro_candidate(...)` and calls
  the helper. It resumes direct publication at `component_seed_x`, leaving
  component/rejection, nearby rejected component probe, NMS/cap ranks,
  provisional selection, movement shadow/rescue, track match, stage
  publication, and thermal candidate-array publication in `anomaly_analysis.c`.
- Jason sidecar review independently identified the exact suppressor-only
  island and warned not to include `component_seed_x` or any later lifecycle
  fields. The packet was narrowed to match that review before validation.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3139 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because it extracts suppressor debug-result publication only. Thermal
  scoring, candidate extraction, micro-candidate evaluation, suppression
  decisions, component extraction/rejection, NMS/cap/provisional selection,
  movement rescue/shadow logic, track matching, scan planning, timing
  finalization, threading, overlay drawing, Color/saliency publication, and
  public API behavior remain unchanged.

Packet 126 parent checkpoint:

- Thermal debug target component/rejection publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_target_component_trace(...)` with an
  explicit
  `anomaly_result_thermal_debug_target_component_trace_publication_t` producer
  contract.
- The helper publishes only selected component trace and rejection fields:
  component seed/peak samples, area/span/fill, peak/mean delta, quality,
  `component_rejected`, and `rejection_gate`.
- The late thermal debug block in `anomaly_analysis.c` now constructs the DTO
  immediately after
  `anomaly_result_publish_thermal_debug_target_suppressor(...)` and calls the
  helper. It resumes direct publication at `nearby_rejected_component_valid`,
  leaving nearby rejected component probe, NMS/cap ranks, provisional
  selection, movement shadow/rescue, track match, stage publication, and
  thermal candidate-array publication in `anomaly_analysis.c`.
- Jason sidecar review independently confirmed this exact component/rejection
  island and warned not to include nearby rejected component, NMS/cap,
  provisional, movement, track, stage, or candidate-array fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_target_component_trace` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3153 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because it extracts component/rejection debug-result publication
  only. Thermal scoring, candidate extraction, micro-candidate evaluation,
  suppression decisions, component extraction/rejection decisions, nearby
  rejected-component selection, NMS/cap/provisional selection, movement
  rescue/shadow logic, track matching, scan planning, timing finalization,
  threading, overlay drawing, Color/saliency publication, and public API
  behavior remain unchanged.

Packet 127 parent checkpoint:

- Thermal debug target nearby rejected-component publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_target_nearby_rejected_component(...)`
  with an explicit
  `anomaly_result_thermal_debug_target_nearby_rejected_component_publication_t`
  producer contract.
- The helper publishes only nearby rejected-component probe fields:
  valid/contains-target/gate, seed/peak samples, area/span/fill, peak/mean
  delta, quality, and distance.
- The late thermal debug block in `anomaly_analysis.c` now constructs the DTO
  immediately after
  `anomaly_result_publish_thermal_debug_target_component_trace(...)` and
  calls the helper. It resumes direct publication at `dropped_by_cap`, leaving
  NMS/cap ranks, provisional selection, movement shadow/rescue, track match,
  stage publication, and thermal candidate-array publication in
  `anomaly_analysis.c`.
- Jason sidecar review independently confirmed this exact nearby
  rejected-component island and warned not to include NMS/cap, provisional,
  movement, track, stage, or candidate-array fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_target_nearby_rejected_component`
    was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3168 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because it extracts nearby rejected-component debug-result
  publication only. Thermal scoring, candidate extraction, micro-candidate
  evaluation, suppression decisions, component extraction/rejection decisions,
  nearby rejected-component selection, NMS/cap/provisional selection,
  movement rescue/shadow logic, track matching, scan planning, timing
  finalization, threading, overlay drawing, Color/saliency publication, and
  public API behavior remain unchanged.

Packet 128 parent checkpoint:

- Thermal debug target NMS/cap rank publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_target_nms_cap(...)` with an explicit
  `anomaly_result_thermal_debug_target_nms_cap_publication_t` producer
  contract.
- The helper publishes only already-computed NMS/cap/rank fields:
  dropped-by-cap/NMS flags, NMS replacement flag, conflict rank/sample,
  pre-cap rank/count/limit/retention rank, extracted rank, and winning rank.
- The late thermal debug block in `anomaly_analysis.c` now constructs the DTO
  immediately after
  `anomaly_result_publish_thermal_debug_target_nearby_rejected_component(...)`
  and calls the helper. It resumes direct publication at
  `provisional_candidate_index`, leaving provisional selection, movement
  shadow/rescue, track match, stage publication, and thermal candidate-array
  publication in `anomaly_analysis.c`.
- Jason sidecar review independently confirmed this exact NMS/cap publication
  island and warned not to include provisional, movement, track, stage, or
  candidate-array fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_target_nms_cap` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3179 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because it extracts NMS/cap debug-result publication only. Thermal
  scoring, candidate extraction/order, micro-candidate evaluation,
  suppression decisions, component extraction/rejection decisions, nearby
  rejected-component selection, NMS/cap decisions, provisional selection,
  movement rescue/shadow logic, track matching, scan planning, timing
  finalization, threading, overlay drawing, Color/saliency publication, and
  public API behavior remain unchanged.

Packet 129 parent checkpoint:

- Thermal debug target provisional selection publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_target_provisional(...)` with an
  explicit `anomaly_result_thermal_debug_target_provisional_publication_t`
  producer contract.
- The helper publishes only already-computed provisional selection fields:
  candidate index, score floor, final score, score/shape eligibility,
  candidate/selected rank, selected score, and near-existing skip.
- The late thermal debug block in `anomaly_analysis.c` now constructs the DTO
  immediately after
  `anomaly_result_publish_thermal_debug_target_nms_cap(...)` and calls the
  helper. It resumes direct publication at `raw_delta_rescue_score`, leaving
  raw-delta rescue, movement shadow/rescue, track match, stage publication,
  and thermal candidate-array publication in `anomaly_analysis.c`.
- Jason sidecar review independently confirmed this exact provisional
  publication island and warned not to include raw-delta rescue, movement,
  track, stage, or candidate-array fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_target_provisional` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3193 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because it extracts provisional debug-result publication only.
  Thermal scoring, candidate extraction/order, NMS/cap decisions, provisional
  selection logic, raw-delta rescue, movement rescue/shadow logic, track
  matching, scan planning, timing finalization, threading, overlay drawing,
  Color/saliency publication, and public API behavior remain unchanged.

Packet 130 parent checkpoint:

- Thermal debug target raw-delta rescue score publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_target_raw_delta_rescue(...)` with an
  explicit
  `anomaly_result_thermal_debug_target_raw_delta_rescue_publication_t`
  producer contract.
- The helper publishes only one already-computed scalar:
  `raw_delta_rescue_score`.
- The late thermal debug block in `anomaly_analysis.c` now constructs the DTO
  immediately after
  `anomaly_result_publish_thermal_debug_target_provisional(...)` and calls the
  helper. It resumes direct publication at `movement_residual_px`, leaving
  movement diagnostics, movement rescue/shadow flags, track match, stage
  publication, and thermal candidate-array publication in `anomaly_analysis.c`.
- Jason sidecar review independently confirmed the one-field raw-delta rescue
  island and specifically recommended not folding in movement diagnostics.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_target_raw_delta_rescue` was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3200 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because it extracts a single raw-delta rescue debug-result scalar
  only. Thermal scoring, candidate extraction/order, provisional selection
  logic, rescue eligibility, movement rescue/shadow logic, track matching,
  scan planning, timing finalization, threading, overlay drawing,
  Color/saliency publication, and public API behavior remain unchanged.

Packet 131 parent checkpoint:

- Thermal debug target movement diagnostics publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_target_movement_diagnostics(...)` with
  an explicit
  `anomaly_result_thermal_debug_target_movement_diagnostics_publication_t`
  producer contract.
- The helper publishes only already-computed target movement diagnostics:
  residual px, independent score, confidence, motion support, and movement
  layer class.
- The late thermal debug block in `anomaly_analysis.c` now constructs the DTO
  immediately after
  `anomaly_result_publish_thermal_debug_target_raw_delta_rescue(...)` and
  calls the helper. It resumes direct publication at
  `local_peak_movement_residual_px`, leaving local-peak movement diagnostics,
  rescue eligibility/flags, movement shadow/rescue flags, track match, stage
  publication, and thermal candidate-array publication in
  `anomaly_analysis.c`.
- Jason sidecar review independently confirmed this exact five-field target
  movement diagnostics island and warned not to include local-peak movement,
  rescue flags, movement shadow, track, stage, or candidate-array fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_target_movement_diagnostics` was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3212 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because it extracts target movement diagnostics debug-result
  publication only. Movement scoring, rescue eligibility, movement
  rescue/shadow decisions, track matching, scan planning, timing finalization,
  threading, overlay drawing, Color/saliency publication, and public API
  behavior remain unchanged.

Packet 132 parent checkpoint:

- Thermal debug target local-peak movement diagnostics publication is promoted
  into `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_target_local_peak_movement(...)` with
  an explicit
  `anomaly_result_thermal_debug_target_local_peak_movement_publication_t`
  producer contract.
- The helper publishes only already-computed local-peak movement diagnostics:
  residual px, independent score, confidence, motion support, and movement
  layer class.
- The late thermal debug block in `anomaly_analysis.c` now constructs the DTO
  immediately after
  `anomaly_result_publish_thermal_debug_target_movement_diagnostics(...)` and
  calls the helper. It resumes direct publication at
  `raw_delta_rescue_eligible`, leaving rescue eligibility/flags, movement
  shadow/rescue flags, track match, stage publication, and thermal
  candidate-array publication in `anomaly_analysis.c`.
- Jason sidecar review independently confirmed this exact five-field
  local-peak movement diagnostics island and warned not to include rescue
  eligibility, movement shadow, track, stage, or candidate-array fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_target_local_peak_movement` was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3223 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because it extracts local-peak movement diagnostics debug-result
  publication only. Movement scoring, rescue eligibility, movement
  rescue/shadow decisions, track matching, scan planning, timing finalization,
  threading, overlay drawing, Color/saliency publication, and public API
  behavior remain unchanged.

Packet 133 parent checkpoint:

- Thermal debug target rescue/movement flag publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_target_rescue_movement_flags(...)`
  with an explicit
  `anomaly_result_thermal_debug_target_rescue_movement_flags_publication_t`
  producer contract.
- The helper publishes only eight already-computed debug booleans:
  `raw_delta_rescue_eligible`, `movement_tile_valid`,
  `movement_independent`, `movement_parallax`,
  `would_promote_movement_rescue`,
  `local_peak_movement_tile_valid`,
  `local_peak_movement_independent`, and
  `local_peak_movement_parallax`.
- The late thermal debug block in `anomaly_analysis.c` now constructs the DTO
  immediately after
  `anomaly_result_publish_thermal_debug_target_local_peak_movement(...)` and
  calls the helper. It resumes direct publication at
  `movement_shadow_motion_support`, leaving movement shadow/rescue publish
  fields, movement rescue reject reason, track match, stage publication, and
  thermal candidate-array publication in `anomaly_analysis.c`.
- Jason sidecar review independently confirmed this exact eight-flag island
  and warned not to include movement shadow support/penalty/thermal/clutter
  flags, movement publish flags, rescue reject reason, track, stage, or
  candidate-array fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_target_rescue_movement_flags` was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3231 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Pause-point replay/perf validation was refreshed at the user's request:
  - IR regression manifest:
    `/private/tmp/ir_regression_packet133_pause/suite_report.md`; command
    exited successfully. Redesigned incremental profile reported precision
    `0.994`, recall `0.599`, track hits `4/4`, and `1.437x` aggregate
    realtime. Dense full-scan gold reported precision `1.000`, recall
    `0.362`, track hits `4/4`, and `0.581x` aggregate realtime.
  - Visible-color performance benchmark:
    `/private/tmp/visible_color_perf_packet133_pause/visible_color_perf_report.json`;
    app-like auto averaged `0.33x` realtime, `97.36 ms` total,
    `44.31 ms` color scoring, and `23.20 ms` sampled-grid prep. Dense-gold
    averaged `0.05x` realtime, `659.14 ms` total, `367.30 ms` color
    scoring, and `193.49 ms` sampled-grid prep.
  - Registration performance benchmark:
    `/private/tmp/registration_perf_packet133_pause`; fixed cases reported
    `1.23x`, `1.60x`, `0.74x`, and `0.88x` realtime.
- The refreshed perf numbers remain in the known host/noisy envelope for this
  lane and do not indicate a Packet 133-specific regression. The packet
  extracts debug-result boolean publication only; it does not alter rescue
  eligibility computation, movement scoring, movement shadow/rescue decisions,
  track matching, scan planning, timing finalization, threading, overlay
  drawing, Color/saliency publication, or public API behavior.

Packet 134 parent checkpoint:

- Thermal debug target movement shadow/rescue publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_target_movement_shadow_rescue(...)`
  with an explicit
  `anomaly_result_thermal_debug_target_movement_shadow_rescue_publication_t`
  producer contract.
- The helper publishes only seven already-computed debug fields:
  `movement_shadow_motion_support`,
  `movement_shadow_parallax_penalty`,
  `movement_shadow_thermal_support`, `movement_shadow_clutter_veto`,
  `movement_rescue_would_publish`, `movement_boost_would_publish`, and
  `movement_rescue_reject_reason`.
- The late thermal debug block in `anomaly_analysis.c` now constructs the DTO
  immediately after
  `anomaly_result_publish_thermal_debug_target_rescue_movement_flags(...)`
  and calls the helper. It resumes direct publication at
  `matched_track_index`, leaving track match, stage publication, and thermal
  candidate-array publication in `anomaly_analysis.c`.
- Pauli sidecar review independently confirmed this exact seven-field island,
  recommended the same DTO/helper name, and warned to stop before
  `matched_track_index`, the remaining matched-track fields, stage, and the
  thermal candidate array.
- TDD red check:
  - The first red build exposed a test enum spelling mistake, which was fixed
    to use the existing `ANOMALY_MOVEMENT_SHADOW_REJECT_PARALLAX` enum.
  - `cmake --build tools/anomaly_test/build_timing` then failed as expected
    after test/header wiring because
    `anomaly_result_publish_thermal_debug_target_movement_shadow_rescue` was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3239 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 133 refreshed the pause-point IR, visible-color, and
  registration gates immediately before this helper-only packet. Packet 134
  extracts movement shadow/rescue debug-result publication only. It does not
  alter rescue eligibility computation, movement scoring, movement
  shadow/rescue decisions, track matching, scan planning, timing finalization,
  threading, overlay drawing, Color/saliency publication, or public API
  behavior.

Packet 135 parent checkpoint:

- Thermal debug target matched-track publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_target_track_match(...)` with an
  explicit
  `anomaly_result_thermal_debug_target_track_match_publication_t` producer
  contract.
- The helper publishes only six already-computed debug fields:
  `matched_track_index`, `matched_track_id`, `matched_track_hit_count`,
  `matched_track_miss_count`, `matched_track_hold_count`, and
  `matched_track_publish_confirmed`.
- The late thermal debug block in `anomaly_analysis.c` now constructs the DTO
  immediately after
  `anomaly_result_publish_thermal_debug_target_movement_shadow_rescue(...)`
  and calls the helper. It resumes direct publication at `stage`, leaving
  stage publication and thermal candidate-array publication in
  `anomaly_analysis.c`.
- Leibniz sidecar review independently confirmed this exact six-field island,
  recommended the same DTO/helper name, and warned to stop before `stage` and
  the thermal candidate array. The sidecar also noted the live tree was dirty
  and that implementation should work with existing anomaly-file changes.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_target_track_match` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3247 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 133 refreshed the pause-point IR, visible-color, and
  registration gates immediately before these helper-only packets. Packet 135
  extracts matched-track debug-result publication only. It does not alter track
  matching computation, stage lifecycle updates, candidate extraction/order,
  rescue/shadow logic, scan planning, timing finalization, threading, overlay
  drawing, Color/saliency publication, or public API behavior.

Packet 136 parent checkpoint:

- Thermal debug target stage publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_target_stage(...)` with an explicit
  `anomaly_result_thermal_debug_target_stage_publication_t` producer contract.
- The helper publishes only the already-computed `stage` field.
- The late thermal debug block in `anomaly_analysis.c` now constructs the DTO
  immediately after
  `anomaly_result_publish_thermal_debug_target_track_match(...)` and calls the
  helper. It stops before the thermal candidate-array publication loop, which
  remains in `anomaly_analysis.c`.
- Confucius sidecar review independently confirmed this exact one-field seam,
  recommended the same DTO/helper name, and warned not to touch candidate
  publication, bbox normalization, nearest-track lookup, rescue fields,
  scoring/order, stage lifecycle computation, track matching, timing, overlays,
  or Color/saliency publication.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_target_stage` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3254 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 133 refreshed the pause-point IR, visible-color, and
  registration gates immediately before these helper-only packets. Packet 136
  extracts stage debug-result publication only. It does not alter stage
  lifecycle computation, candidate publication, candidate extraction/order,
  track matching, rescue/shadow logic, scan planning, timing finalization,
  threading, overlay drawing, Color/saliency publication, or public API
  behavior.

Packet 137 parent checkpoint:

- Thermal debug candidate base publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_candidates_base(...)` with explicit
  `anomaly_result_thermal_debug_candidate_base_publication_t` and
  `anomaly_result_thermal_debug_candidates_base_publication_t` producer
  contracts.
- The helper publishes only base thermal candidate debug fields and
  normalization/defaults: `valid`, pixel/x/y norms, bbox norms, base/final/
  temporal scores, area/span/fill/center/quality/isolation fields,
  score/history/apparent-size/isolation-track/context/parent scales, rank
  fields, patch/motion support, singleton score scale, retention rank, plus
  legacy defaults `movement_layer_class = ANOMALY_MOVEMENT_LAYER_UNKNOWN` and
  nearest-track index/id/hit count `-1`.
- The late thermal debug block in `anomaly_analysis.c` now builds a capped DTO
  array immediately after
  `anomaly_result_publish_thermal_debug_target_stage(...)`, calls the helper,
  and then resumes the existing enrichment tail at the movement snapshot query.
- Fermat sidecar review independently recommended splitting the candidate loop
  and confirmed Packet 137 should extract only the base publication slice,
  stopping before movement snapshot lookup, nearest-track lookup,
  near-debug-target, raw-delta rescue scoring/eligibility/promotion,
  singleton blob, and above-threshold fields.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_candidates_base` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3278 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 137 extracts base debug-result publication only. It
  does not alter candidate ordering, scoring, extraction, allocation behavior,
  movement snapshot lookup, nearest-track lookup, near-target flags, raw-delta
  rescue scoring, scan planning, timing finalization, threading, overlay
  drawing, Color/saliency publication, or public API behavior.

Packet 138 parent checkpoint:

- Thermal debug candidate movement-tile publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_candidates_movement(...)` with
  explicit
  `anomaly_result_thermal_debug_candidate_movement_publication_t` and
  `anomaly_result_thermal_debug_candidates_movement_publication_t` producer
  contracts.
- The helper publishes only per-candidate movement debug fields when a movement
  tile is present: `movement_tile_valid`, `movement_residual_px`,
  `movement_independent_score`, `movement_confidence`,
  `movement_layer_class`, `movement_independent`, and `movement_parallax`.
- The late thermal debug block in `anomaly_analysis.c` still performs the
  movement snapshot query and tile interpretation locally, builds a capped DTO
  array, calls the helper, and then resumes direct enrichment at the
  nearest-track lookup.
- Wegener sidecar review independently confirmed this boundary and warned not
  to pass `anomaly_debug_movement_tile_t` into the result builder or move
  nearest-track lookup, near-target flags, raw-delta rescue scoring,
  singleton/threshold flags, movement query/scoring, timing, or estimator
  logic.
- TDD red check:
  - The first red build exposed a test enum spelling mistake, which was fixed
    to use the existing `ANOMALY_MOVEMENT_LAYER_UNSTABLE` enum.
  - `cmake --build tools/anomaly_test/build_timing` then failed as expected
    after test/header wiring because
    `anomaly_result_publish_thermal_debug_candidates_movement` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3293 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 138 extracts movement debug-result publication only.
  It does not alter movement query/scoring, estimator logic, candidate ordering
  or extraction, nearest-track lookup, near-target flags, raw-delta rescue
  scoring, scan planning, timing finalization, threading, overlay drawing,
  Color/saliency publication, or public API behavior.

Packet 139 parent checkpoint:

- Thermal debug candidate nearest-track publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_candidates_nearest_track(...)` with
  explicit
  `anomaly_result_thermal_debug_candidate_nearest_track_publication_t` and
  `anomaly_result_thermal_debug_candidates_nearest_track_publication_t`
  producer contracts.
- The helper publishes only per-candidate nearest-track debug fields when a
  nearest track exists: `nearest_track_distance`, `nearest_track_index`,
  `nearest_track_id`, `nearest_track_hit_count`, and `near_tracked_target`.
- The late thermal debug block in `anomaly_analysis.c` still performs the
  nearest-track search over `state->target_tracks` and the near-track gate
  calculation locally, builds a capped DTO array, calls the helper, and then
  resumes direct enrichment at `near_debug_target`.
- Mencius sidecar review independently confirmed this boundary and warned not
  to move the nearest-track search itself, `near_debug_target`, raw-delta
  rescue fields, singleton/threshold flags, candidate extraction/order,
  movement query/scoring, scan planning, timing, or lifecycle behavior.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_candidates_nearest_track` was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3307 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 139 extracts nearest-track debug-result publication
  only. It does not alter nearest-track search, near-debug-target, raw-delta
  rescue scoring, candidate ordering or extraction, movement query/scoring,
  scan planning, timing finalization, threading, overlay drawing,
  Color/saliency publication, or public API behavior.

Packet 140 parent checkpoint:

- Thermal debug candidate near-debug-target publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_candidates_near_debug(...)` with
  explicit
  `anomaly_result_thermal_debug_candidate_near_debug_publication_t` and
  `anomaly_result_thermal_debug_candidates_near_debug_publication_t`
  producer contracts.
- The helper publishes only `near_debug_target` for entries marked
  `near_debug_valid`.
- The late thermal debug block in `anomaly_analysis.c` still performs the
  `thermal_target_trace.enabled && thermal_target_trace.valid` decision,
  target-in-bbox test, dx/dy distance calculation, and match-gate comparison
  locally, builds a capped DTO array, calls the helper, and then resumes
  direct enrichment at raw-delta rescue scoring.
- Feynman sidecar review independently confirmed this boundary and warned not
  to move the geometry calculation, target enabled/valid decision,
  raw-delta rescue score/eligibility/promotion, `singleton_blob`, or
  `above_threshold`.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_candidates_near_debug` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3319 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Pause-before-next-packet replay/perf validation was refreshed:
  - IR regression manifest:
    `/private/tmp/ir_regression_packet140_pause/suite_report.md`; redesigned
    incremental profile reported precision `0.994`, recall `0.599`, track
    hits `4/4`, and `1.223x` aggregate realtime. Dense full-scan gold
    reported precision `1.000`, recall `0.362`, track hits `4/4`, and
    `0.482x` aggregate realtime.
  - Visible-color regression manifest:
    `/private/tmp/color_regression_packet140_pause/suite_report.md`; baseline
    profile retained the current Red1 reviewed result, and dense-gold reported
    precision `1.000`, recall `1.000`, track hits `1/1`, and `0.041x`
    realtime.
  - Clean sequential visible-color performance benchmark:
    `/private/tmp/visible_color_perf_packet140_pause_sequential/visible_color_perf_report.json`;
    app-like auto averaged `0.35x` realtime, `93.15 ms` total,
    `42.13 ms` color scoring, and `22.06 ms` sampled-grid prep. Dense-gold
    averaged `0.05x` realtime, `650.63 ms` total, `361.98 ms` color scoring,
    and `190.55 ms` sampled-grid prep.
  - Clean sequential registration performance benchmark:
    `/private/tmp/registration_perf_packet140_pause_sequential`; fixed cases
    reported `1.40x`, `1.69x`, `0.85x`, and `1.01x` realtime.
- An initial concurrent replay/perf sweep also exited successfully, but its
  early app-like Color and registration timing was treated as contaminated by
  parallel host load. The sequential benchmark outputs above are the pause
  point timing evidence.
- The refreshed gates are green for this pause point. Packet 140 extracts
  near-debug debug-result publication only; it does not alter candidate
  ordering/extraction, movement query/scoring, nearest-track search, raw-delta
  rescue scoring, singleton/threshold flags, scan planning, timing
  finalization, threading, overlay drawing, Color/saliency publication, or
  public API behavior.

Packet 141 parent checkpoint:

- Thermal debug candidate raw-delta rescue publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_candidates_raw_delta_rescue(...)` with
  explicit
  `anomaly_result_thermal_debug_candidate_raw_delta_rescue_publication_t` and
  `anomaly_result_thermal_debug_candidates_raw_delta_rescue_publication_t`
  producer contracts.
- The helper publishes exactly three already-computed per-candidate fields:
  `raw_delta_rescue_score`, `raw_delta_rescue_eligible`, and
  `would_promote_movement_rescue`.
- The late thermal debug block in `anomaly_analysis.c` still performs the
  raw-delta rescue score calculation, eligibility calculation, and `>= 0.62f`
  promotion decision locally, builds a capped DTO array, calls the helper, and
  then resumes direct enrichment at `singleton_blob` and `above_threshold`.
- Godel sidecar review independently confirmed this boundary, recommended no
  per-entry valid flag because the legacy loop publishes the rescue triplet
  for every debug candidate entry, and warned not to move scoring inputs,
  scoring helpers, promotion policy, singleton/threshold flags, candidate
  extraction/order, movement, scan, timing, or API behavior.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_candidates_raw_delta_rescue` was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3335 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 141 extracts raw-delta rescue debug-result publication
  only. It does not alter raw-delta rescue scoring inputs, score/eligibility
  helpers, promotion policy, candidate ordering/extraction, movement
  query/scoring, nearest-track search, near-debug logic, singleton/threshold
  flags, scan planning, timing finalization, threading, overlay drawing,
  Color/saliency publication, or public API behavior. Packet 140 refreshed the
  pause-point IR, visible-color, and registration replay/perf gates.

Packet 142 parent checkpoint:

- Thermal debug candidate final-flag publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_thermal_debug_candidates_final_flags(...)` with
  explicit
  `anomaly_result_thermal_debug_candidate_final_flags_publication_t` and
  `anomaly_result_thermal_debug_candidates_final_flags_publication_t`
  producer contracts.
- The helper publishes exactly two already-computed per-candidate fields:
  `singleton_blob` and `above_threshold`.
- The late thermal debug block in `anomaly_analysis.c` still reads the
  existing `thermal_candidate_singleton_blob_debug[]` and
  `thermal_candidate_above_threshold[]` arrays, builds a capped DTO array,
  calls the helper, and then proceeds to Color debug publication.
- Darwin sidecar review independently confirmed this boundary and warned not
  to move raw-delta rescue fields, candidate base/bbox/rank/score fields,
  movement fields, track fields, near-debug fields, singleton/threshold source
  computations, candidate ordering/extraction, scoring, threshold policy,
  movement, track, target, scan, timing, overlay, Color, saliency, or API
  behavior.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_thermal_debug_candidates_final_flags` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3348 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 142 extracts final-flag debug-result publication only.
  It does not alter singleton/threshold source computation, raw-delta rescue
  publication, candidate ordering/extraction, movement query/scoring,
  nearest-track search, near-debug logic, scan planning, timing finalization,
  threading, overlay drawing, Color/saliency publication, or public API
  behavior. Packet 140 refreshed the pause-point IR, visible-color, and
  registration replay/perf gates.

Packet 143 parent checkpoint:

- Saliency debug background readiness publication is folded into the existing
  `anomaly_result_publish_saliency_debug(...)` result-builder contract by
  adding `bg_ready` to `anomaly_result_saliency_debug_publication_t`.
- The helper now publishes `saliency_debug.bg_ready` alongside the existing
  saliency raw score, accumulator, suppression, and top-candidate debug
  fields.
- The direct early `result_out->saliency_debug.bg_ready = bg_valid` write in
  `anomaly_analysis.c` was removed; the late saliency debug DTO now carries
  `.bg_ready = bg_valid` immediately before
  `anomaly_result_publish_saliency_debug(...)`.
- Bacon sidecar review confirmed this as a pure result-publication contract
  cleanup and warned not to move `bg_valid` computation, thermal temporal
  stats, `thermal_delta_map`, saliency scoring, best-persist selection,
  accumulator updates, top-candidate construction/order, support maps, boxes,
  timing, overlay, Color debug, or thermal summary plumbing.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing &&
    tools/anomaly_test/build_timing/anomaly_test`: built successfully, then
    failed as expected on the new saliency background-readiness assertion
    because the helper did not yet copy `bg_ready`.
- Focused native coverage now also verifies that `bg_ready = false` overwrites
  a stale true result field and that scalar saliency fields still publish
  before the helper's existing null top-candidate early return.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3355 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 143 only moves a saliency debug-result readiness flag
  into an existing publication contract. It does not alter background
  readiness computation, saliency scoring, top-candidate selection, target
  tracking, scan planning, timing finalization, threading, overlay drawing,
  thermal/color publication, or public API behavior. Packet 140 refreshed the
  pause-point IR, visible-color, and registration replay/perf gates.

Packet 144 parent checkpoint:

- Late movement debug publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_movement_debug(...)`.
- The helper publishes the already-computed `anomaly_debug_movement_t`
  sidecar exactly, using only null guards and
  `result_out->movement_debug = *movement_debug`.
- The late post-`anomaly_target_tracks_update_movement_evidence(...)` direct
  assignment in `anomaly_analysis.c` now calls
  `anomaly_result_publish_movement_debug(result_out, &movement_sidecar)`.
- Motion estimation, movement snapshot creation, scan planner input, frame
  metadata publication, target-track movement evidence update, movement tile
  interpretation, and AOI evidence computation remain in `anomaly_analysis.c`
  and the movement/target modules.
- Gauss sidecar review confirmed this boundary, recommended using
  `anomaly_debug_movement_t` as the payload rather than adding a ceremony DTO,
  and warned not to move registration fields, frame metadata, movement
  snapshot state, target-track state, tile interpretation, or any
  recomputation.
- TDD red checks:
  - The first red attempt exposed stale test assumptions about movement tile
    fields and was corrected to use the live `anomaly_debug_movement_tile_t`
    contract.
  - `cmake --build tools/anomaly_test/build_timing` then failed as expected
    after test/header wiring because `anomaly_result_publish_movement_debug`
    was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3359 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 144 only moves already-computed movement debug
  publication through the result-builder contract. It does not alter motion
  estimation, movement snapshot creation, target movement evidence, scan
  planning, scoring, timing finalization, threading, overlay drawing,
  thermal/color/saliency publication, or public API behavior. Packet 140
  refreshed the pause-point IR, visible-color, and registration replay/perf
  gates.

Packet 145 parent checkpoint:

- Scan-plan publication is promoted into `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_scan_plan(...)`.
- The helper publishes the already-mutated `anomaly_scan_plan_t` exactly,
  using only null guards and `result_out->scan_plan = *scan_plan`.
- Four direct `result_out->scan_plan = scan_plan` writes in
  `anomaly_analysis.c` now call
  `anomaly_result_publish_scan_plan(result_out, &scan_plan)`.
- The early color fallback partial publication
  `result_out->scan_plan.reason_flags |= color_fallback_reason_flags` was
  removed because the local `scan_plan.reason_flags` already includes those
  flags immediately before the full scan-plan publish.
- `anomaly_result_publish_frame_metadata(...)` now delegates its scan-plan
  copy to the same helper while keeping its `rescan_mode` publication
  unchanged.
- The paired direct `result_out->rescan_mode = rescan_mode` fallback write in
  `anomaly_analysis.c` remains in place for a later packet.
- Singer sidecar review confirmed the four producer sites and the redundant
  color fallback partial-publication removal, and warned not to move scan-plan
  mutation logic, ScanPlanner ownership, rescan-mode publication, scoring,
  candidate selection, timing, or public API behavior.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because `anomaly_result_publish_scan_plan` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3363 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 145 only moves already-computed scan-plan publication
  through the result-builder contract. It does not alter scan-plan mutation
  logic, ScanPlanner behavior, rescan-mode publication, scoring, candidate
  selection, target tracking, timing finalization, threading, overlay drawing,
  thermal/color/saliency publication, or public API behavior. Packet 140
  refreshed the pause-point IR, visible-color, and registration replay/perf
  gates.

Packet 146 parent checkpoint:

- Rescan-mode publication is promoted into `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_rescan_mode(...)`.
- The helper publishes only the already-decided `anomaly_rescan_mode_t`
  scalar, using a null guard plus `result_out->rescan_mode = rescan_mode`.
- `anomaly_result_publish_frame_metadata(...)` now delegates rescan-mode
  copying to the same helper before delegating scan-plan copying.
- The remaining fallback direct write in `anomaly_analysis.c` now calls
  `anomaly_result_publish_rescan_mode(result_out, rescan_mode)` while leaving
  the paired local `scan_plan.mode` mutation and scan-plan publication
  unchanged.
- Gibbs sidecar review confirmed this as the correct narrow boundary and
  warned not to move scan-plan fields, adaptive stride fields, registration
  health, frame flags, movement debug, boxes, timing, enum validation, or any
  synchronization between `rescan_mode` and `scan_plan.mode`.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because `anomaly_result_publish_rescan_mode` was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3367 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Pause-before-next-packet replay/perf validation was refreshed:
  - IR regression manifest:
    `/private/tmp/ir_regression_packet146_pause/suite_report.md`;
    redesigned incremental reported precision `0.994`, recall `0.599`,
    track hits `4/4`, and `1.516x` aggregate realtime. Dense full-scan gold
    reported precision `1.000`, recall `0.362`, track hits `4/4`, and
    `0.617x` aggregate realtime. The current-detector baseline reported
    precision `0.965`, recall `0.197`, track hits `3/4`, and `2.819x`
    aggregate realtime.
  - Visible-color regression manifest:
    `/private/tmp/color_regression_packet146_pause/suite_report.md`;
    baseline retained the current Red1 reviewed posture with recall `0.000`,
    TP `0`, FP `0`, miss `15`, track hits `0/1`, and `0.325x` realtime.
    Dense-gold reported precision `1.000`, recall `1.000`, TP `15`, FP `0`,
    miss `0`, track hits `1/1`, and `0.048x` realtime.
  - Clean sequential visible-color performance benchmark:
    `/private/tmp/visible_color_perf_packet146_pause_sequential/visible_color_perf_report.json`;
    app-like auto averaged `0.35x` realtime, `93.15 ms` total,
    `42.19 ms` color scoring, and `21.96 ms` sampled-grid prep. Dense-gold
    averaged `0.05x` realtime, `649.69 ms` total, `361.69 ms` color scoring,
    and `190.37 ms` sampled-grid prep.
  - Clean sequential registration performance benchmark:
    `/private/tmp/registration_perf_packet146_pause_sequential`; fixed cases
    reported `1.41x`, `1.71x`, `0.86x`, and `1.03x` realtime.
- The refreshed gates are green for this pause point. Packet 146 extracts
  rescan-mode result publication only; it does not alter scan-plan mutation,
  ScanPlanner behavior, fallback decisions, scoring, candidate selection,
  tracking, timing finalization, threading, overlay drawing,
  thermal/color/saliency publication, or public API behavior.

Packet 147 parent checkpoint:

- Motion appearance debug publication is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_publish_motion_appearance_debug_summary(...)` and
  `anomaly_result_publish_motion_appearance_debug_result(...)`.
- The two result-builder helpers take explicit summary/result publication
  payloads and delegate to the existing MotionEstimator debug-formatting
  helpers, preserving the current normalization, valid-flag, and top-candidate
  clamp behavior without duplicating it.
- The early motion-appearance global summary call site in `anomaly_analysis.c`
  now builds
  `anomaly_result_motion_appearance_debug_summary_publication_t` locally and
  publishes through the result builder.
- The final motion appearance result call site in `anomaly_analysis.c` now
  builds
  `anomaly_result_motion_appearance_debug_result_publication_t` locally and
  publishes through the result builder.
- Kierkegaard sidecar review confirmed this as the narrow Packet 147 boundary
  and warned to keep MotionEstimator scoring, global stats, candidate
  selection, top-candidate construction, `best_motion_*` state, persistence
  maps, support maps, tracking, boxes, timing, scan/rescan state,
  thermal/color/saliency debug, and remaining thermal-candidate result reads
  outside the packet.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_publish_motion_appearance_debug_summary` and
    `anomaly_result_publish_motion_appearance_debug_result` were missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3391 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Direct result-output audit:
  - `rg -n "&result_out->|result_out->" app/src/main/cpp/anomaly_analysis.c`
    now finds only four local `thermal_debug.candidates[i]` reads used to
    derive later thermal candidate debug publications.
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 147 only routes already-computed motion appearance
  debug publication through the result-builder contract. It does not alter
  MotionEstimator scoring, candidate ordering, support maps, sampling
  lifecycle, tracking, boxes, timing finalization, scan planning, threading,
  overlay drawing, thermal/color/saliency publication, or public API
  behavior. Packet 146 refreshed the pause-point IR, visible-color, and
  registration replay/perf gates.

Packet 148 parent checkpoint:

- Thermal debug candidate snapshot access is promoted into
  `anomaly_result_builder.{h,c}` as
  `anomaly_result_copy_thermal_debug_candidate(...)`.
- The helper is deliberately boring: null/bounds guards plus exact copy of the
  current staged `anomaly_debug_thermal_candidate_t` into caller-owned storage.
  It does not recompute bbox/norms, validate semantic candidate validity, or
  expose thermal summary/target fields.
- The four remaining local direct reads of
  `result_out->thermal_debug.candidates[i]` in `anomaly_analysis.c` now copy a
  local snapshot through the helper before deriving movement, nearest-track,
  near-debug, and raw-delta rescue publication DTOs.
- Curie sidecar review confirmed this as a safe result-boundary cleanup and
  warned to keep raw-delta rescue computation, nearest-track lookup,
  movement-snapshot queries, state target tracks, thermal scratch arrays,
  candidate ordering, support maps, scoring, and publication behavior outside
  the packet.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_result_copy_thermal_debug_candidate` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3412 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Direct result-output audit:
  - `rg -n "result_out->thermal_debug\\.candidates\\[[^]]+\\]|&result_out->|result_out->" app/src/main/cpp/anomaly_analysis.c`
    returned no matches.
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 148 only copies already-staged thermal candidate debug
  state through the result-builder contract. It does not alter thermal
  candidate extraction/order, movement query behavior, nearest-track search,
  raw-delta rescue formulas, scoring, support maps, target tracking, timing
  finalization, scan planning, threading, overlay drawing, public API
  behavior, or any published result values. Packet 146 refreshed the
  pause-point IR, visible-color, and registration replay/perf gates.

Packet 149 parent checkpoint:

- MotionEstimator movement-mode normalization is promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_normalize_movement_mode(...)`.
- The helper preserves the existing sidecar contract exactly: `NULL` config,
  unknown values, and invalid values normalize to
  `ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE`, while layered active and layered
  shadow pass through unchanged.
- `anomaly_motion_estimator_estimate_sidecar(...)` now routes its mode
  selection through the MotionEstimator helper instead of a private local
  duplicate.
- Beauvoir sidecar review confirmed the behavior-preserving boundary and
  flagged two useful refinements. The final packet keeps normalization
  MotionEstimator-owned instead of including the broader runtime-config helper
  chain, and adds direct sidecar characterization coverage for mode
  publication before readiness early returns.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_motion_estimator_normalize_movement_mode` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3424 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Dependency audit:
  - `rg -n "anomaly_runtime_config.h|normalize_sidecar_movement_mode|anomaly_motion_estimator_normalize_movement_mode|sidecar_publishes_normalized" app/src/main/cpp/anomaly_motion_estimator.c app/src/main/cpp/anomaly_motion_estimator.h tools/anomaly_test/test_anomaly.c`
    confirmed `anomaly_motion_estimator.c` no longer includes
    `anomaly_runtime_config.h`, the old private
    `normalize_sidecar_movement_mode` symbol is gone, and the sidecar uses the
    new MotionEstimator helper.
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 149 only names and centralizes the existing
  MotionEstimator movement-mode enum normalization. It does not alter the
  sidecar work loop, registration inputs, movement tile queries, candidate
  scoring, support maps, sampling lifecycle, target tracking, timing
  finalization, scan planning, threading, overlay drawing, public API
  behavior, or published result values. The pause-point replay/perf gates were
  refreshed immediately before this packet.

Packet 150 parent checkpoint:

- MotionEstimator sidecar input readiness is promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_sidecar_input_ready(...)`.
- The helper names the pre-loop sidecar input contract that was previously
  embedded inside `anomaly_motion_estimator_estimate_sidecar(...)`: nonlegacy
  normalized movement mode, current/previous luma presence, usable motion-grid
  and frame dimensions, positive motion step, complete sidecar ops, and valid
  registration according to the injected registration-valid callback.
- `anomaly_motion_estimator_estimate_sidecar(...)` still publishes the
  normalized mode and default parallax suppression scale before returning for
  null input, legacy mode, or readiness failure. The ROI-derived grid-bounds
  guard and the sidecar work loop remain in place after the readiness helper.
- Heisenberg sidecar review confirmed no behavior blocker in the route through
  `estimate_sidecar(...)`. The review noted that Packet 149's exported
  movement-mode normalizer is a broader API than Packet 150 itself; the parent
  kept it because that was the deliberate Packet 149 contract. The review also
  asked for additional readiness predicate leaves, which were added for
  `motion_h <= 2`, `height <= 1`, missing `project_cell`, and missing
  `registration_valid`.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_motion_estimator_sidecar_input_ready` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3437 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 150 only names the existing MotionEstimator sidecar
  pre-loop readiness predicate and routes the sidecar through it. It does not
  alter ROI grid math, the sidecar sampling loop, registration solving,
  movement tile classification, candidate scoring, support maps, sampling
  lifecycle, target tracking, timing finalization, scan planning, threading,
  overlay drawing, public API behavior, or published result values.

Packet 151 parent checkpoint:

- MotionEstimator sidecar ROI-to-motion-grid bounds are promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_sidecar_grid_bounds(...)` plus the
  `anomaly_motion_sidecar_grid_bounds_t` DTO.
- The helper preserves the existing sidecar geometry exactly: lower ROI bounds
  use C integer division and clamp to the one-cell patch-search border, upper
  ROI bounds use `(roi + motion_step - 1) / motion_step` and clamp to the same
  sidecar border, and collapsed bounds return false before the sidecar
  sampling loop runs.
- `anomaly_motion_estimator_estimate_sidecar(...)` now calls the helper after
  the readiness helper and before the sidecar work loop. The downstream local
  `roi_mgx0`, `roi_mgx1`, `roi_mgy0`, and `roi_mgy1` values are populated
  from the returned DTO and consumed by the same loop as before.
- Hilbert sidecar review found no blocking issues and specifically confirmed
  the ROI math, output-clearing behavior, and separation from the appearance
  scorer's different grid-bounds helper.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_motion_estimator_sidecar_grid_bounds` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3454 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 151 only names and routes the existing MotionEstimator
  sidecar ROI grid-bounds calculation. It does not alter the sidecar sampling
  loop, registration solving or projection callbacks, residual displacement
  callbacks, movement tile classification, candidate scoring, support maps,
  sampling lifecycle, target tracking, timing finalization, scan planning,
  threading, overlay drawing, public API behavior, or published result values.

Packet 152 parent checkpoint:

- MotionEstimator sidecar tile-center normalization is promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_sidecar_tile_center_norm(...)`.
- The helper preserves the existing tile publication arithmetic exactly:
  `mx * motion_step / width` and `my * motion_step / height`, each clamped
  through the existing `clamp01f(...)` behavior.
- `anomaly_motion_estimator_estimate_sidecar(...)` now routes only
  `tile->center_x_norm` and `tile->center_y_norm` publication through the
  helper. Tile validity, displacement, residual, confidence, layer class, and
  the sidecar sampling/classification loop are unchanged.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_motion_estimator_sidecar_tile_center_norm` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3465 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Parent audit:
  - `rg -n "center_x_norm =|center_y_norm =|sidecar_tile_center_norm" app/src/main/cpp/anomaly_motion_estimator.c tools/anomaly_test/test_anomaly.c`
    confirmed the sidecar production assignment routes through
    `anomaly_motion_estimator_sidecar_tile_center_norm(...)`; the remaining
    direct center assignments are test fixtures.
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 152 only names and routes the existing MotionEstimator
  sidecar tile-center normalization arithmetic. It does not alter the sidecar
  sampling loop, registration solving or projection callbacks, residual
  displacement callbacks, movement tile classification, candidate scoring,
  support maps, sampling lifecycle, target tracking, timing finalization, scan
  planning, threading, overlay drawing, public API behavior, or published
  result values.

Pause-before-next-packet replay/perf validation was refreshed at the user's
request:

- IR regression manifest:
  `/private/tmp/ir_regression_packet152_pause/suite_report.md`; redesigned
  incremental profile reported precision `0.994`, recall `0.599`, track hits
  `4/4`, and `1.512x` aggregate realtime. Dense full-scan gold reported
  precision `1.000`, recall `0.362`, track hits `4/4`, and `0.614x`
  aggregate realtime.
- Visible-color regression manifest:
  `/private/tmp/color_regression_packet152_pause/suite_report.md`; baseline
  Red1 retained the current reviewed result with recall `0.000` and `0.327x`
  realtime. Dense-gold reported precision `1.000`, recall `1.000`, track hits
  `1/1`, and `0.048x` realtime.
- Clean sequential visible-color performance benchmark:
  `/private/tmp/visible_color_perf_packet152_pause_sequential/visible_color_perf_report.json`;
  app-like auto averaged `0.35x` realtime, `92.92 ms` total, `42.29 ms` color
  scoring, and `21.94 ms` sampled-grid prep. Dense-gold averaged `0.05x`
  realtime, `649.15 ms` total, `361.93 ms` color scoring, and `189.86 ms`
  sampled-grid prep.
- Clean sequential registration performance benchmark:
  `/private/tmp/registration_perf_packet152_pause_sequential`; fixed cases
  reported `1.41x`, `1.71x`, `0.85x`, and `1.02x` realtime.
- The refreshed tests, replay manifests, and performance benchmarks are green
  for this pause point. Packet 152 only extracts sidecar tile-center
  normalization; the refreshed perf numbers remain in the known host/noisy
  envelope and do not indicate a packet-specific throughput regression.

Packet 153 parent checkpoint:

- MotionEstimator sidecar tile layer classification is promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_sidecar_classify_layer(...)`.
- The helper preserves the existing sidecar classification order exactly:
  background first, coherent-near second, local-outlier third, and unstable as
  the fallback. The inclusive threshold behavior is unchanged:
  `flow <= motion_step * 0.45 && residual <= 12.0`,
  `neighbor_delta <= motion_step * 1.25 && flow <= motion_step * 2.75`, and
  `residual >= 18.0 && flow >= motion_step * 0.75`.
- `anomaly_motion_estimator_estimate_sidecar(...)` now routes only the
  layer-class decision through the helper. The counter increments still map
  one-to-one from the selected layer class, and tile validity, center
  normalization, displacement, residual, confidence, sampling, projection, and
  residual-search behavior are unchanged.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_motion_estimator_sidecar_classify_layer` was missing.
- Pascal sidecar review found no blocking issues and confirmed branch order,
  inclusive thresholds, counter mapping, invalid `motion_step` handling, and
  internal-header exposure are appropriate for this extraction.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3471 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 153 only names and routes the existing MotionEstimator
  sidecar layer-class decision. It does not alter the sidecar sampling loop,
  registration solving or projection callbacks, residual displacement search,
  tile coordinate publication, tile confidence, movement aggregate metrics,
  candidate scoring, support maps, sampling lifecycle, target tracking, timing
  finalization, scan planning, threading, overlay drawing, public API behavior,
  or published result values.

Packet 154 parent checkpoint:

- MotionEstimator sidecar parallax suppression-scale calculation is promoted
  into `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_sidecar_parallax_suppression_scale(...)`.
- The helper preserves the existing final aggregate calculation exactly: no
  suppression at `parallax_load <= 0.25`, no suppression when
  `local_outlier_load >= 0.20`, otherwise
  `1.0 - 0.45 * clampf((parallax_load - 0.25) / 0.45, 0.0, 1.0)`.
- `anomaly_motion_estimator_estimate_sidecar(...)` now routes only
  `movement_out->parallax_suppression_scale` publication through the helper.
  Parallax/local-outlier load computation, confidence, aggregate counts,
  fractions, tile publication, sampling, projection, and residual-search
  behavior are unchanged.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_motion_estimator_sidecar_parallax_suppression_scale` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3475 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 154 only names and routes the existing MotionEstimator
  sidecar final suppression-scale scalar calculation. It does not alter
  sidecar sampling, registration solving or projection callbacks, residual
  displacement search, tile classification, tile confidence, movement
  aggregate load computation, candidate scoring, support maps, sampling
  lifecycle, target tracking, timing finalization, scan planning, threading,
  overlay drawing, public API behavior, or published result values.

Packet 155 parent checkpoint:

- MotionEstimator sidecar tile confidence calculation is promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_sidecar_tile_confidence(...)`.
- The helper preserves the existing tile publication formula exactly:
  `clampf(1.0 - residual_px / 64.0 + min(flow_px / (motion_step * 8.0), 0.25), 0.0, 1.0)`.
- `anomaly_motion_estimator_estimate_sidecar(...)` now routes only
  `tile->confidence` publication through the helper. Tile validity, center
  normalization, displacement, residual, layer classification, sampling,
  projection, residual-search behavior, and aggregate metrics are unchanged.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_motion_estimator_sidecar_tile_confidence` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3480 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Parent audit:
  - `rg -n "sidecar_tile_confidence|tile->confidence =|fminf\\(flow_px" app/src/main/cpp/anomaly_motion_estimator.c app/src/main/cpp/anomaly_motion_estimator.h tools/anomaly_test/test_anomaly.c`
    confirmed the only production tile-confidence assignment routes through
    `anomaly_motion_estimator_sidecar_tile_confidence(...)`.
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 155 only names and routes the existing MotionEstimator
  sidecar tile-confidence scalar calculation. It does not alter sidecar
  sampling, registration solving or projection callbacks, residual
  displacement search, tile coordinate publication, tile classification,
  movement aggregate load computation, candidate scoring, support maps,
  sampling lifecycle, target tracking, timing finalization, scan planning,
  threading, overlay drawing, public API behavior, or published result values.

Packet 156 parent checkpoint:

- MotionEstimator sidecar tile displacement publication is promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_sidecar_tile_displacement_px(...)`.
- The helper preserves the existing tile publication arithmetic exactly:
  `dx_px = best_dx * motion_step` and `dy_px = best_dy * motion_step`.
- `anomaly_motion_estimator_estimate_sidecar(...)` now routes only
  `tile->dx_px` and `tile->dy_px` publication through the helper. Tile
  validity, center normalization, residual, confidence, layer classification,
  sampling, projection, residual-search behavior, flow magnitude, and
  aggregate metrics are unchanged.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_motion_estimator_sidecar_tile_displacement_px` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3489 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Parent audit:
  - `rg -n "sidecar_tile_displacement|tile->dx_px =|tile->dy_px =|best_dx \\*" app/src/main/cpp/anomaly_motion_estimator.c app/src/main/cpp/anomaly_motion_estimator.h tools/anomaly_test/test_anomaly.c`
    confirmed production tile displacement publication routes through
    `anomaly_motion_estimator_sidecar_tile_displacement_px(...)`; the remaining
    `best_dx` expression is the unchanged flow-magnitude calculation.
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 156 only names and routes the existing MotionEstimator
  sidecar tile-displacement scalar conversion. It does not alter sidecar
  sampling, registration solving or projection callbacks, residual
  displacement search, tile coordinate publication, tile residual/confidence,
  tile classification, movement aggregate load computation, candidate scoring,
  support maps, sampling lifecycle, target tracking, timing finalization, scan
  planning, threading, overlay drawing, public API behavior, or published
  result values.

Packet 157 parent checkpoint:

- MotionEstimator sidecar displacement magnitude is promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_sidecar_displacement_magnitude_px(...)`.
- The helper preserves the existing Euclidean grid-displacement magnitude
  arithmetic exactly: `sqrt(dx * dx + dy * dy) * motion_step`.
- `anomaly_motion_estimator_estimate_sidecar(...)` now routes only tile
  `flow_px` and previous-flow `neighbor_delta` magnitude calculations through
  the helper. Tile publication, residual calculation, layer classification
  thresholds, aggregate counters/fractions, confidence, and suppression-scale
  publication are unchanged.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_motion_estimator_sidecar_displacement_magnitude_px` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3493 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 157 only names and routes the existing MotionEstimator
  sidecar displacement-magnitude scalar calculation. It does not alter
  sidecar sampling, registration solving or projection callbacks, residual
  displacement search, tile coordinate/displacement publication, tile
  residual/confidence, tile classification thresholds, movement aggregate
  load formulas, candidate scoring, support maps, sampling lifecycle, target
  tracking, timing finalization, scan planning, threading, overlay drawing,
  public API behavior, or published result values.

Packet 158 parent checkpoint:

- MotionEstimator movement-snapshot tile flow magnitude is promoted into
  `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_tile_flow_magnitude_px(...)`.
- The helper preserves the existing tile pixel-flow arithmetic exactly:
  `sqrt(tile->dx_px * tile->dx_px + tile->dy_px * tile->dy_px)`.
- `anomaly_motion_estimator_tile_independent_score(...)` now routes only its
  flow-magnitude input through the helper. Residual scoring, flow-score
  scaling, layer scoring, independent/parallax classification gates, sidecar
  production, and all published movement fields are unchanged.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_motion_estimator_tile_flow_magnitude_px` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3497 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 158 only names and routes an existing MotionEstimator
  snapshot scalar query used by the independent-motion score. It does not
  alter sidecar sampling, registration solving or projection callbacks,
  residual displacement search, tile coordinate/displacement publication,
  tile residual/confidence, tile classification thresholds, movement
  aggregate load formulas, candidate scoring, support maps, sampling
  lifecycle, target tracking, timing finalization, scan planning, threading,
  overlay drawing, public API behavior, or published result values.

Packet 159 parent checkpoint:

- MotionEstimator movement-snapshot tile residual independent score is
  promoted into `anomaly_motion_estimator.{h,c}` as
  `anomaly_motion_estimator_tile_residual_independent_score(...)`.
- The helper preserves the existing residual evidence arithmetic exactly:
  `clampf((tile->residual_px - 12.0f) / 28.0f, 0.0f, 1.0f)`.
- `anomaly_motion_estimator_tile_independent_score(...)` now routes only its
  residual-score input through the helper. Flow magnitude, flow-score scaling,
  layer scoring, independent/parallax classification gates, sidecar
  production, and all published movement fields are unchanged.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_motion_estimator_tile_residual_independent_score` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3503 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 159 only names and routes an existing MotionEstimator
  snapshot scalar query used by the independent-motion score. It does not
  alter sidecar sampling, registration solving or projection callbacks,
  residual displacement search, tile coordinate/displacement publication,
  tile residual/confidence, tile classification thresholds, movement
  aggregate load formulas, candidate scoring, support maps, sampling
  lifecycle, target tracking, timing finalization, scan planning, threading,
  overlay drawing, public API behavior, or published result values.

Packet 160 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_frame_input_ready(...)` in
  `anomaly_detector.{h,c}`.
- The helper names the current processable frame predicate for the standalone
  boundary: non-null `ANOMALY_FRAME_FORMAT_RGBA8888`, non-null `rgba`,
  positive `rgba_stride`, positive `width`, and positive `height`.
- `anomaly_detector_process(...)` now routes only its frame-readiness branch
  through the helper while preserving the existing invalid-input fallback to
  `anomaly_process_frame(...)` with null RGBA and the caller-supplied source
  timestamp when present.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because `anomaly_detector_frame_input_ready` was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3510 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 160 only names and routes the detector facade frame
  input-readiness predicate. It does not alter frame processing for valid RGBA
  frames, invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

Packet 161 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_process_args_t` and
  `anomaly_detector_process_with_args(...)`.
- The structured process args bundle names the standalone call contract as
  state, frame, config, and result output.
- `anomaly_detector_process(...)` now builds an args struct and forwards to
  `anomaly_detector_process_with_args(...)`. The structured entry point owns
  the existing valid-frame and invalid-input forwarding behavior.
- `anomaly_detector_internal.h` now reuses the public facade header instead of
  keeping a duplicate private process-args definition.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because `anomaly_detector_process_with_args` was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3525 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 161 only names and routes the detector facade
  process-argument bundle. It does not alter valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

Packet 162 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_process_args_frame_ready(...)`.
- The helper names the structured-call subset required to process frame
  pixels: non-null process args, non-null detector state, and a ready frame.
  Config and result output are intentionally not part of this predicate because
  the current core already treats those as optional/fallback-capable inputs.
- `anomaly_detector_process_with_args(...)` now routes only its valid-frame
  branch through the helper while preserving existing invalid-input fallback
  behavior.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because `anomaly_detector_process_args_frame_ready` was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3531 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 162 only names and routes the detector facade
  structured-args frame-readiness predicate. It does not alter valid-frame
  processing, invalid-input result initialization, scoring, sampling,
  registration, MovementEstimator behavior, result boxes, overlay drawing,
  threading, or published result values.

Packet 163 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_process_args_make(...)`.
- The helper is the canonical constructor for the structured process-args
  bundle: state, frame, config, and result output.
- `anomaly_detector_process(...)` now uses the constructor before forwarding
  to `anomaly_detector_process_with_args(...)`.
- The constructor preserves optional null fields so current fallback paths for
  null config, null result output, null state, or null frame remain expressible.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because `anomaly_detector_process_args_make` was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3539 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 163 only names and routes the detector facade
  structured-args constructor. It does not alter valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

Packet 164 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_process_args_may_annotate_frame(...)`.
- The helper names the current in-place annotation predicate for structured
  calls: frame-ready args with a non-null config may annotate the input RGBA
  frame when either `show_hot_overlay` is true or anomaly detection is enabled.
- This is intentionally a "may annotate" contract: enabled detection can draw
  result boxes when publishable boxes exist, and hot overlay can draw even when
  detection is disabled.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_detector_process_args_may_annotate_frame` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3545 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 164 only names the detector facade annotation
  ownership predicate. It does not alter valid-frame processing, invalid-input
  result initialization, scoring, sampling, registration, MovementEstimator
  behavior, result boxes, overlay drawing, threading, or published result
  values.

Packet 165 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_annotation_t`,
  `anomaly_detector_annotation_view_t`, and
  `anomaly_detector_result_annotations(...)`.
- The helper names the current standalone annotation-output view over
  `anomaly_result_t`: null, zero, or negative result box counts expose an
  empty view; positive counts expose `result->boxes`; oversized counts are
  bounded to `ANOMALY_MAX_BOXES_PER_FRAME` so consumers cannot walk past the
  public fixed-capacity annotation array.
- This is intentionally a read-only view. It does not change when boxes are
  produced, which boxes are published, or whether the current core draws
  overlays in-place on RGBA input frames.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because `anomaly_detector_result_annotations` was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3553 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 165 only names a bounded detector facade view over
  already-published result boxes. It does not alter valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result box construction, overlay drawing,
  threading, or published result values.

Packet 166 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_frame_output_t` and
  `anomaly_detector_process_args_frame_output(...)`.
- The helper names the current annotated-frame output contract for structured
  calls. Ready args expose the same RGBA buffer, stride, dimensions, and
  source timestamp that the current core processes and may annotate in-place.
- The output also exposes `annotations_may_be_in_place`, which reuses
  `anomaly_detector_process_args_may_annotate_frame(...)` so standalone
  consumers can distinguish a plain frame output from a frame that may contain
  detector or hot-overlay annotations under the current config.
- Unready frames and null args expose an empty output view.
- This is intentionally a read-only contract helper. It does not process,
  copy, allocate, draw, or change the current in-place RGBA ownership model.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because `anomaly_detector_process_args_frame_output`
    was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3561 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 166 only names a detector facade output view over
  already-processed input frame storage and existing annotation policy. It
  does not alter valid-frame processing, invalid-input result initialization,
  scoring, sampling, registration, MovementEstimator behavior, result boxes,
  overlay drawing, threading, or published result values.

Packet 167 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_process_output_t` and
  `anomaly_detector_process_output(...)`.
- The helper names the current combined processed-output contract for
  standalone consumers: a frame output view plus a bounded annotation view.
- The implementation composes
  `anomaly_detector_process_args_frame_output(...)` and
  `anomaly_detector_result_annotations(...)`. It does not introduce another
  processing path.
- Null args and null results produce the same empty frame/annotation views as
  the underlying helpers.
- This is intentionally a read-only contract helper. It does not process,
  copy, allocate, draw, change RGBA ownership, or alter result publication.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because `anomaly_detector_process_output` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3568 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 167 only composes two existing read-only facade views
  into one output contract. It does not alter valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

Packet 168 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_process_frame(...)`.
- The helper is the first one-frame facade call shaped like the intended
  standalone runtime: it accepts `anomaly_detector_process_args_t`, runs the
  existing structured processing path, optionally writes the legacy box-count
  return value, and returns `anomaly_detector_process_output_t`.
- The implementation calls `anomaly_detector_process_with_args(...)`, then
  composes the processed output with `anomaly_detector_process_output(...)`
  using `args->result_out`.
- Existing positional and structured int-return process APIs remain available
  and unchanged.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because `anomaly_detector_process_frame` was missing.
- Test correction during validation:
  - Initial green run failed because the new wrapper test assumed the gray
    synthetic frame would publish at least one annotation box.
  - Root cause was the existing Packet 165 contract: zero publishable boxes
    intentionally produce an empty annotation view.
  - The test now compares the wrapper annotations to
    `anomaly_detector_result_annotations(&output_result)`, preserving the
    bounded-view contract for zero and nonzero results.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3587 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 168 only wraps the existing structured process path
  and composes already-published output views. It does not alter valid-frame
  processing, invalid-input result initialization, scoring, sampling,
  registration, MovementEstimator behavior, result boxes, overlay drawing,
  threading, or published result values.

Packet 169 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_process_frame_input(...)`.
- The helper provides a positional one-frame frame-in/annotated-frame-out
  facade for adapters that do not need to explicitly construct
  `anomaly_detector_process_args_t`.
- The implementation constructs args with
  `anomaly_detector_process_args_make(...)` and delegates to
  `anomaly_detector_process_frame(...)`.
- Existing positional and structured int-return process APIs remain available
  and unchanged.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because `anomaly_detector_process_frame_input` was
    missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3605 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 169 only constructs the existing args bundle and
  delegates to the already-validated one-frame process/output wrapper. It does
  not alter valid-frame processing, invalid-input result initialization,
  scoring, sampling, registration, MovementEstimator behavior, result boxes,
  overlay drawing, threading, or published result values.

Packet 170 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_default_window_frames(...)` and
  `anomaly_detector_config_make_realtime_default(...)`.
- `anomaly_detector_default_window_frames(...)` names the standalone native
  default realtime window: 0.5 seconds, rounded to the nearest frame, with
  invalid frame rates falling back to 30 fps and low positive rates clamped to
  at least one frame.
- `anomaly_detector_config_make_realtime_default(...)` builds a native
  realtime config for a selected algorithm mask and frame rate. At 30 fps it
  sets `frame_stride` and `adaptive_max_stride_frames` to 15.
- The helper uses existing native constants for threshold, min area, scan
  zone, min hits, thermal delta, small target size, and color frontend mode.
- This packet does not route Android preferences or existing production
  callers through the new helper; Android app defaults remain owned by
  `AnomalyConfig`/`AnomalyPrefs`.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_detector_default_window_frames` and
    `anomaly_detector_config_make_realtime_default` were missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3630 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 170 only adds unused facade config-construction
  helpers. It does not alter existing config flow, valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

Packet 171 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_annotation_cadence_allows_update(...)`.
- The helper names the standalone annotation-output cadence policy tied to the
  half-second realtime window: frame ordinal zero may publish, and later
  visible annotation-state changes are allowed only on cadence-window
  boundaries.
- With the Packet 170 default 30 fps window, cadence 15 allows updates at
  ordinals 0, 15, 30, and so on.
- Negative frame ordinals cannot update; invalid cadence values fall back to
  every-frame updates so adapters avoid modulo/division by bad input.
- This packet only exposes the cadence policy for standalone adapters. It does
  not route existing core overlay drawing or result publication through the
  helper.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_detector_annotation_cadence_allows_update` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3639 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 171 only adds an unused read-only facade policy helper.
  It does not alter existing config flow, valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

Packet 172 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_annotation_cadence_state_t`,
  `anomaly_detector_annotation_cadence_state_init(...)`, and
  `anomaly_detector_annotation_cadence_update_visibility(...)`.
- The helper pair provides the stateful standalone adapter primitive for the
  no-flicker annotation-output contract: adapters may compute desired
  annotation visibility on every frame, but visible state is held between
  cadence-boundary frames.
- First nonnegative frame initializes to the desired visibility and records the
  frame ordinal.
- Desired appearance/disappearance inside the cadence window returns the held
  visible state without mutating the state.
- Desired changes at cadence boundaries mutate and return the new visible
  state.
- Null state and negative frame ordinals fall back to the desired visibility
  without mutating state.
- This packet only exposes the standalone adapter state helper. It does not
  route existing core overlay drawing or result publication through the helper.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_detector_annotation_cadence_state_init` and
    `anomaly_detector_annotation_cadence_update_visibility` were missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3654 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 172 only adds unused facade state helpers. It does not
  alter existing config flow, valid-frame processing, invalid-input result
  initialization, scoring, sampling, registration, MovementEstimator behavior,
  result boxes, overlay drawing, threading, or published result values.

Packet 173 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_annotation_cadence_snapshot_state_t`,
  `anomaly_detector_annotation_cadence_snapshot_state_init(...)`, and
  `anomaly_detector_annotation_cadence_update_snapshot(...)`.
- The helper pair extends the no-flicker cadence contract from visibility to
  annotation boxes. Standalone adapters can hold a stable annotation view
  between cadence boundaries, refresh boxes at allowed boundaries, and clear
  boxes only when disappearance is allowed.
- The snapshot state owns a fixed `ANOMALY_MAX_BOXES_PER_FRAME` copy of the
  visible annotations, plus the existing visibility cadence state.
- Null state returns the desired bounded view directly; negative frame ordinal
  returns the desired bounded view without mutating state.
- This packet only exposes standalone adapter state helpers. It does not route
  existing core overlay drawing or result publication through the helper.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_detector_annotation_cadence_snapshot_state_init` and
    `anomaly_detector_annotation_cadence_update_snapshot` were missing.
- Validation fixes after implementation:
  - Initial post-implementation harness run failed because the snapshot helper
    only copied boxes when visibility changed; root cause was missing refresh
    behavior for same-visible cadence boundaries.
  - A second run failed because cadence-boundary refresh copied boxes but did
    not record the boundary frame ordinal; the snapshot helper now records the
    frame ordinal when it refreshes boxes at an allowed boundary.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3661 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 173 only adds unused facade snapshot helpers. It does
  not alter existing config flow, valid-frame processing, invalid-input result
  initialization, scoring, sampling, registration, MovementEstimator behavior,
  result boxes, overlay drawing, threading, or published result values.

Packet 174 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_process_output_apply_annotation_cadence(...)`.
- The helper applies the Packet 173 annotation snapshot cadence state directly
  to an `anomaly_detector_process_output_t`.
- The helper preserves the frame output by value and replaces only the
  annotation view with the cadence-stabilized snapshot view.
- This gives standalone adapters a stable processed-output shape without
  reimplementing the manual `output.annotations =
  anomaly_detector_annotation_cadence_update_snapshot(...)` composition.
- This packet only exposes adapter-facing composition. It does not route
  existing core overlay drawing or result publication through the helper.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_detector_process_output_apply_annotation_cadence` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3668 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 174 only adds an unused facade composition helper. It
  does not alter existing config flow, valid-frame processing, invalid-input
  result initialization, scoring, sampling, registration, MovementEstimator
  behavior, result boxes, overlay drawing, threading, or published result
  values.

Packet 175 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_process_frame_apply_annotation_cadence(...)`.
- The helper processes one structured frame through
  `anomaly_detector_process_frame(...)`, reports the raw detector box count to
  the optional caller output, then applies
  `anomaly_detector_process_output_apply_annotation_cadence(...)` to the
  returned process output.
- This gives standalone adapters a single call for frame-in/frame-output plus
  cadence-stabilized annotations while preserving the raw result count for
  telemetry or legacy compatibility.
- This packet only exposes adapter-facing composition. It does not route
  existing core overlay drawing or result publication through the helper.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_detector_process_frame_apply_annotation_cadence` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3673 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 175 only adds an unused facade composition helper. It
  does not alter existing config flow, valid-frame processing, invalid-input
  result initialization, scoring, sampling, registration, MovementEstimator
  behavior, result boxes, overlay drawing, threading, or published result
  values.

Packet 176 parent checkpoint:

- The consumer-facing detector facade now exposes
  `anomaly_detector_runtime_t`,
  `anomaly_detector_runtime_init(...)`,
  `anomaly_detector_runtime_cleanup(...)`, and
  `anomaly_detector_runtime_process_frame(...)`.
- The runtime owns detector state, realtime default config, result storage,
  annotation cadence snapshot state, frame ordinal, cadence window, and the
  last raw detector box count.
- Runtime processing builds structured args internally, calls the Packet 175
  one-frame cadence-stabilized process helper, stores the raw detector count,
  advances the frame ordinal, and returns the stable process output.
- This gives standalone adapters an initialize-once/sequential-frame facade
  while preserving the existing lower-level state/config/result process APIs.
- This packet only adds an unused standalone runtime facade. It does not route
  existing core overlay drawing, Android bridge processing, or result
  publication through the runtime.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because `anomaly_detector_runtime_init`,
    `anomaly_detector_runtime_cleanup`, and
    `anomaly_detector_runtime_process_frame` were missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3685 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 176 only adds an unused standalone runtime facade. It
  does not alter existing config flow, valid-frame processing, invalid-input
  result initialization, scoring, sampling, registration, MovementEstimator
  behavior, result boxes, overlay drawing, threading, or published result
  values.

Packet 177 parent checkpoint:

- The consumer-facing detector runtime now exposes
  `anomaly_detector_runtime_init_with_config(...)`.
- The helper initializes an owned runtime from a caller-supplied
  `anomaly_detector_config_t` plus an explicit annotation cadence window.
- It copies the config, initializes owned detector state, clears owned result
  storage, resets annotation cadence snapshot state, starts the frame ordinal
  at zero, stores a cadence window clamped to at least one frame, and clears
  the last raw detector box count.
- This gives standalone adapters and future detector variants a supplied-config
  runtime entry point without forcing them back into lower-level
  state/config/result plumbing.
- This packet only adds an unused runtime initialization helper. It does not
  route existing core overlay drawing, Android bridge processing, or result
  publication through the runtime.
- TDD red check:
  - First red attempt failed early because the test used a non-existent
    movement-estimator enum name; the test was corrected to use
    `ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE`.
  - `cmake --build tools/anomaly_test/build_timing`: then failed as expected
    after test/header wiring because
    `anomaly_detector_runtime_init_with_config` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3691 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 177 only adds an unused runtime initialization helper.
  It does not alter existing config flow, valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

Packet 178 parent checkpoint:

- The consumer-facing detector runtime now exposes
  `anomaly_detector_runtime_apply_config(...)`.
- The helper classifies the current runtime config versus a caller-supplied
  config with `anomaly_config_transition_classify(...)`, applies the config and
  cadence window, and returns the transition classification to the caller.
- Display-only, debug-only, live-update, and unchanged transitions preserve the
  owned runtime sequence state.
- Reset-sensitive transitions call `anomaly_detector_state_reset(...)` and
  clear owned result storage, annotation cadence snapshot state, frame ordinal,
  and last raw detector box count.
- This gives standalone adapters a controlled config-apply path without
  duplicating reset-sensitive bookkeeping around the owned runtime.
- This packet only adds an unused runtime config-apply helper. It does not
  route existing core overlay drawing, Android bridge processing, or result
  publication through the runtime.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_detector_runtime_apply_config` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3700 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 178 only adds an unused runtime config-apply helper.
  It does not alter existing config flow, valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

Packet 179 parent checkpoint:

- The consumer-facing detector runtime now exposes
  `anomaly_detector_runtime_process_result_t` and
  `anomaly_detector_runtime_process_frame_result(...)`.
- The richer runtime process result includes the processed output, processed
  frame ordinal, raw detector box count, stable annotation count, cadence
  window, and stabilized annotation visibility.
- The existing `anomaly_detector_runtime_process_frame(...)` output-only
  helper now delegates to the richer result helper and returns `.output`.
- This gives standalone adapters per-frame runtime metadata without reaching
  into `anomaly_detector_runtime_t` internals after each frame.
- This packet only adds an unused runtime process-result helper and preserves
  the existing output-only runtime process API shape.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_detector_runtime_process_frame_result` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3706 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 179 only adds an unused runtime process-result helper.
  It does not alter existing config flow, valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

Packet 180 parent checkpoint:

- The consumer-facing detector runtime now exposes read-only accessors for
  current config, current result, stable annotations, next frame ordinal,
  cadence window, and last raw detector box count.
- The helpers are:
  `anomaly_detector_runtime_config(...)`,
  `anomaly_detector_runtime_result(...)`,
  `anomaly_detector_runtime_stable_annotations(...)`,
  `anomaly_detector_runtime_frame_ordinal(...)`,
  `anomaly_detector_runtime_cadence_frames(...)`, and
  `anomaly_detector_runtime_last_box_count(...)`.
- This gives standalone adapters observation points without coupling them to
  the public `anomaly_detector_runtime_t` field layout after every frame.
- This packet only adds unused read-only runtime accessors. It does not route
  existing core overlay drawing, Android bridge processing, or result
  publication through the runtime.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because the six runtime accessor symbols were missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3711 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 180 only adds unused read-only runtime accessors. It
  does not alter existing config flow, valid-frame processing, invalid-input
  result initialization, scoring, sampling, registration, MovementEstimator
  behavior, result boxes, overlay drawing, threading, or published result
  values.

Packet 181 parent checkpoint:

- The Android FFmpeg bridge now applies the detector facade annotation cadence
  to normal operator overlays.
- `analyze_rgba_frame_locked(...)` still computes raw detector results every
  analyzed frame, but normal overlay clones are drawn from the stable
  annotation snapshot produced by
  `anomaly_detector_result_apply_annotation_cadence(...)`.
- The bridge preserves a clean copy of the converted RGBA frame, lets the
  detector analyze the original RGBA input, restores the clean frame, and draws
  only the stable annotation boxes before cloning the overlay.
- Hot-overlay and candidate-blob troubleshooting modes keep the legacy raw
  in-place draw path so diagnostic overlays remain frame-exact.
- Cadence state is reset on session startup, anomaly tracking resets, and
  anomaly RGBA resource/dimension resets.
- TDD red check:
  - Initial helper declaration failed too early because it was declared before
    the snapshot-state typedef; the declaration was moved below the typedef.
  - `cmake --build tools/anomaly_test/build_timing`: then failed as expected
    after test/header wiring because
    `anomaly_detector_result_apply_annotation_cadence` was missing.
  - The first green run exposed that first-observation visibility is immediate;
    the test was corrected to seed an already-initialized invisible cadence
    state when checking mid-window transient suppression.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3716 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 181 changes app-visible overlay publication cadence but does
  not alter detector scoring, sampling, registration, candidate extraction,
  MovementEstimator behavior, raw result boxes, or threading. The focused
  native cadence tests plus Android native compile covered the changed path.

Packet 182 parent checkpoint:

- The Android FFmpeg bridge now applies visibility-only annotation cadence to
  normal operator overlays.
- `anomaly_detector_result_apply_annotation_visibility_cadence(...)` rate
  limits annotation appearance/disappearance while copying current raw boxes
  whenever annotations are already visible.
- This corrects the Packet 181 snapshot behavior that could hold a moving ROI
  marker at an older coordinate for the full cadence window.
- Within-window disappearances still hold the last visible moving box until a
  cadence boundary clears it, preserving the no-brief-flash operator rule.
- The previous `anomaly_detector_result_apply_annotation_cadence(...)`
  snapshot helper remains available for consumers that explicitly need frozen
  cadence snapshots.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_detector_result_apply_annotation_visibility_cadence` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3721 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 182 changes app-visible overlay publication semantics at the
  bridge/facade boundary but does not alter detector scoring, sampling,
  registration, candidate extraction, MovementEstimator behavior, raw result
  boxes, or threading. Focused native cadence tests plus Android native compile
  covered the changed path.

Packet 183 parent checkpoint:

- Normal operator overlays now smooth small same-algorithm ROI coordinate jitter
  at the facade publication boundary.
- `anomaly_detector_result_apply_annotation_visibility_cadence(...)` still
  rate-limits appearance/disappearance, but visible boxes now ease toward the
  latest raw result when the new box is near the previous displayed box.
- Large coordinate jumps or algorithm changes snap to the latest raw box so a
  real target switch is not smeared across the frame.
- The bridge can draw the current held annotation snapshot onto skipped AD
  worker frames, avoiding bare-frame blink-off when analysis is intentionally
  bypassed for local-file stride or AD pressure.
- Troubleshooting hot/candidate overlay modes continue to bypass held/smoothed
  display overlays so diagnostic views remain raw.
- TDD red check:
  - `cmake --build tools/anomaly_test/build_timing`: failed as expected after
    test/header wiring because
    `anomaly_detector_annotation_cadence_snapshot_view` was missing.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3728 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 183 changes display publication smoothing and skipped-frame
  overlay hold behavior but does not alter detector scoring, sampling,
  registration, candidate extraction, MovementEstimator behavior, raw result
  boxes, or tracking state.

Packet 184 parent checkpoint:

- The video harness now has an explicit app-display replay mode:
  `--app-display-output`.
- In that mode `anomaly_video_test` still runs the raw detector once per frame,
  restores the clean decoded RGBA frame, applies
  `anomaly_detector_result_apply_annotation_visibility_cadence(...)`, redraws
  only the app-visible published boxes, and writes those displayed boxes to the
  CSV/video artifacts.
- Summary JSON records `output_stream` and `display_cadence_frames`.
- Added `tools/anomaly_test/analyze_display_stability.py` to compute objective
  display metrics from a detection CSV: visible-frame ratio, blink gaps,
  short cadence-window gaps, greedy track count, and ROI jump statistics.
- Red1 app-display closed-loop output:
  - Annotated video: `/tmp/red1_app_display_annotated.mp4`
  - CSV: `/tmp/red1_app_display_detections.csv`
  - Summary: `/tmp/red1_app_display_summary.json`
  - Stability: `/tmp/red1_app_display_stability.json`
- Red1 app-display review score against `Red1.review.json` remained precision
  `0.0` / recall `0.0`: the display stream removes blink gaps but still tracks
  a reviewed false-positive object and misses the reviewed target.
- Red1 app-display stability metrics: visible-frame ratio `0.9026`, gap count
  `0`, short-gap count `0`, max jump `0.0296`, p95 jump `0.0087`.
- Parent validation passed after implementation:
  - `git diff --check`: passed
  - `cmake --build tools/anomaly_test/build_timing`: passed
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`: `1/1`
  - `tools/anomaly_test/build_timing/anomaly_test`: `3728 passed, 0 failed`
  - `./gradlew :app:compileDebugKotlin`: passed
- This packet adds harness/reporting coverage for app-visible output and does
  not alter Android runtime behavior, detector scoring, candidate extraction,
  MovementEstimator behavior, or target tracking.

Packet 185 parent checkpoint:

- Root cause for the Packet 184 Red1 app-display miss was a harness/default
  boundary mismatch, not annotation cadence: Kotlin `AnomalyConfig.toNativeConfig`
  maps Color appearance with legacy UI/default color frontend to native
  `ANOMALY_COLOR_FRONTEND_FRESH_RGBA`, while `anomaly_video_test --app-defaults`
  was still forcing `ANOMALY_COLOR_FRONTEND_LEGACY` unless explicitly
  overridden.
- `anomaly_video_test` app-parity derivation now mirrors the Kotlin runtime
  policy: Color appearance derives `fresh-rgba`; non-Color app-parity remains
  legacy unless `--color-frontend` is explicitly supplied.
- The standalone detector realtime default now chooses `fresh-rgba` whenever
  the requested default algorithm mask includes `ANOMALY_ALGO_COLOR`; non-color
  defaults remain legacy. This keeps the publishable detector facade aligned
  with the runtime Color boundary.
- TDD red checks:
  - Rebuilt native harness failed with the updated facade expectation:
    `detector facade default config: color algorithm uses fresh RGBA frontend`.
  - A short Red1 harness replay with
    `--app-defaults --app-appearance color` printed `color = legacy`.
- Green checks after implementation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3728 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed.
  - Short Red1 app-parity replay printed `color = fresh-rgba`.
- Red1 app-display closed-loop after the parity fix:
  - Annotated video: `/tmp/red1_app_display_fresh_annotated.mp4`
  - CSV: `/tmp/red1_app_display_fresh_detections.csv`
  - Summary: `/tmp/red1_app_display_fresh_summary.json`
  - Stability: `/tmp/red1_app_display_fresh_stability.json`
  - Review score: precision `1.0`, recall `0.5333333333333333`, true-positive
    annotations `8`, missed annotations `7`, false-positive annotations `0`.
  - Stability metrics: visible-frame ratio `0.9026`, gap count `0`,
    short-gap count `0`, primary target-track max jump `0.0105`, primary
    target-track p95 jump `0.0042`.
- Interpretation: the closed-loop app-display stream no longer locks onto the
  reviewed false-positive blob near `(0.464, 0.25)` under app-parity settings;
  it first publishes the reviewed target around `0.5s`, consistent with the
  half-second appearance/disappearance cadence.

Packet 186 parent checkpoint:

- Runtime boundary audit after Packet 185:
  - Native `start_session(...)` initializes anomaly processing disabled.
  - `anomaly_processing_enabled_locked(...)` requires enabled config,
    unpaused thermal state, no runtime disable, and nonzero algorithm mask.
  - `start_ad_thread_if_needed_locked(...)` returns before starting the AD
    worker when anomaly processing is not enabled.
  - `FfmpegProbeService.applyAnomalyConfigToSession(...)` sends the
    Kotlin-derived `NativeAnomalyConfig` after render-session creation.
  - Therefore local Red1 frames should not be analyzed with the native
    startup legacy color default before the Kotlin config supplies the
    Color/fresh-rgba frontend.
- Added an app-side observability guardrail: `FfmpegProbeService` now includes
  `colorFrontend=<native value>` in both existing troubleshooting config logs,
  including the "new session" path. The native bridge already logs
  `colorFrontend` for enabled local-file sessions.
- Validation:
  - `./gradlew :app:compileDebugKotlin`: passed.
- This packet changes logging only. It does not change detector scoring,
  startup gating, threading, app policy, harness behavior, or overlay
  publication.

Packet 187 parent checkpoint:

- Extracted the annotation publication/cadence seam from
  `anomaly_detector.c` into `anomaly_detector_annotation.c` plus the
  narrower `anomaly_detector_annotation.h` contract.
- `anomaly_detector.h` remains the umbrella consumer facade and includes the
  annotation header, so existing detector facade call sites keep the same
  public surface.
- Wired the new module into both native build paths:
  - Android `ffmpeg_bridge` CMake target.
  - Harness `anomaly_test` and `anomaly_video_test` CMake targets.
- TDD red check:
  - Added the harness include for `anomaly_detector_annotation.h`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected with
    `fatal error: 'anomaly_detector_annotation.h' file not found`.
- Parent validation passed after implementation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3728 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed.
- Replay manifest rationale:
  - Full Red1/app-display replay and performance manifests were not rerun for
    this packet because it only moves already-covered annotation publication
    helpers into a separate module. It does not change detector scoring, seed
    ordering, support maps, candidate extraction, sampling-state lifecycle,
    MovementEstimator behavior, target tracking, or app policy.

Packet 188 parent checkpoint:

- `anomaly_runtime_budget.{h,c}` now owns the first standalone R2CAD runtime
  budget contract.
- `anomaly_detector_runtime_budget_t` carries adapter-supplied realtime
  telemetry without depending on Android, FFmpeg, JNI, or render queue state.
- `anomaly_detector_runtime_budget_processing_mode(...)` maps startup,
  adapter pressure, and render backlog thresholds to Cursory or Thorough mode.
- Runtime threshold normalization now treats below-default runtime thresholds
  as malformed and clamps them to the default minimums: startup skip `0.25s`,
  cursory backlog `0.25s`, thorough backlog `0.5s`, and max backlog at least
  `0.5s` plus at least the thorough threshold.
- `anomaly_detector.h` includes the focused runtime-budget contract as part of
  the umbrella detector facade, but `anomaly_detector.c` does not own the
  policy implementation.
- This packet intentionally does not wire the policy into Android, FFmpeg,
  scoring, MotionEstimator, candidate extraction, support maps, target
  tracking, or threading. It is contract plumbing for the future standalone
  threaded runtime.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3773 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed.
- Full replay manifests and performance benchmarks were not rerun because this
  packet adds pure facade policy helpers only and does not alter detector
  execution behavior.

Packet 189 parent checkpoint:

- Extracted the live AD worker queue-pressure policy from `ffmpeg_bridge.c`
  into `anomaly_runtime_pressure.{h,c}`.
- The new module owns pure helpers for queue-depth threshold rounding/clamping,
  default pressure policy construction, pressure mode selection with recovery
  hysteresis, per-frame bypass decisions, and mode naming.
- `ffmpeg_bridge.c` remains the Android/FFmpeg adapter owner for queue storage,
  locks, condition variables, worker lifecycle, AVFrame ownership, render
  forwarding, telemetry counters, and `"live-ad-pressure"` fallback logging.
- The bridge now routes its existing pressure helper wrappers through the
  focused module, preserving the current threaded runtime behavior without
  pretending ME and AD are unthreaded today.
- Wired `anomaly_runtime_pressure.c` into both native build paths:
  - Android `ffmpeg_bridge` CMake target.
  - Harness `anomaly_test` and `anomaly_video_test` CMake targets.
- TDD red check:
  - Added the harness include/tests for `anomaly_runtime_pressure.h`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected with
    `fatal error: 'anomaly_runtime_pressure.h' file not found`.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3807 passed, 0 failed`.
  - `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest`:
    passed.
  - `./gradlew :app:compileDebugKotlin`: passed.
- Full replay manifests and performance benchmarks were not rerun because this
  packet extracts existing queue-pressure policy only. It does not change
  detector scoring, candidate extraction, support-map logic, sampling-state
  lifecycle, MotionEstimator behavior, target tracking, AVFrame forwarding, or
  app policy.

Packet 190 parent checkpoint:

- Added `anomaly_runtime_handoff.{h,c}` as a host-testable runtime contract for
  the AD worker's current analyze-versus-forward decision.
- The new module owns pure frame metadata readiness, stale-generation checks,
  handoff action selection, and reason naming.
- `ffmpeg_bridge.c` converts a dequeued `render_queue_slot_t` to handoff
  metadata and routes the existing skip/analyze branch through the helper.
- `ffmpeg_bridge.c` still owns queue storage, pthread synchronization, AVFrame
  ownership, overlay frame ownership, render forwarding, worker counters,
  cleanup, and app logging.
- This packet intentionally does not split MotionEstimator into a separate
  worker thread or introduce an ME evidence queue. MotionEstimator still runs
  synchronously inside `anomaly_process_frame()` on the AD worker path.
- Wired `anomaly_runtime_handoff.c` into both native build paths:
  - Android `ffmpeg_bridge` CMake target.
  - Harness `anomaly_test` and `anomaly_video_test` CMake targets.
- TDD red check:
  - Added the harness include/tests for `anomaly_runtime_handoff.h`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected with
    `fatal error: 'anomaly_runtime_handoff.h' file not found`.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3824 passed, 0 failed`.
  - `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest`:
    passed.
  - `./gradlew :app:compileDebugKotlin`: passed.
- Full replay manifests and performance benchmarks were not rerun because this
  packet extracts existing AD worker handoff policy only. It does not change
  detector scoring, candidate extraction, support-map logic, sampling-state
  lifecycle, MotionEstimator behavior, target tracking, AVFrame forwarding, or
  app policy.

Packet 191 parent checkpoint:

- Extended `anomaly_runtime_handoff.{h,c}` with a pure AD worker outcome-delta
  helper for processed, skipped, forwarded-without-analysis, and annotated
  frame counters.
- `ffmpeg_bridge.c` now applies those deltas to the existing session counters
  after the worker computes `overlay_present`.
- The session counters, queue storage, pthread synchronization, AVFrame
  ownership, overlay frame ownership, render forwarding, cleanup, and app
  logging all remain in `ffmpeg_bridge.c`.
- This packet intentionally does not split MotionEstimator into a separate
  worker thread or introduce an ME evidence queue.
- TDD red check:
  - Added native harness tests for
    `anomaly_runtime_handoff_outcome_for_decision(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected because
    `anomaly_runtime_handoff_outcome_t` and the helper did not exist.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3828 passed, 0 failed`.
  - `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest`:
    passed.
  - `./gradlew :app:compileDebugKotlin`: passed.
- Full replay manifests and performance benchmarks were not rerun because this
  packet extracts existing AD worker counter-delta policy only. It does not
  change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, AVFrame
  forwarding, or app policy.

Packet 192 parent checkpoint:

- Extended `anomaly_runtime_pressure.{h,c}` with the pure
  `anomaly_runtime_pressure_backlog_frame_capacity(...)` helper.
- `ffmpeg_bridge.c` now routes the local-file AD sidecar queue budget through
  the runtime pressure contract while keeping queue storage, frame ownership,
  old-frame drops, counters, traces, locks, and logging in the bridge.
- This preserves the existing local sidecar policy: a 500 ms queue budget,
  source-interval fallback to the render default, a minimum of 2 frames, and
  clamping to `AD_INPUT_QUEUE_HARD_CAPACITY`.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_runtime_pressure_backlog_frame_capacity(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3834 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks were not rerun because this
  packet extracts existing queue-capacity arithmetic only. It does not change
  detector scoring, candidate extraction, support-map logic, sampling-state
  lifecycle, MotionEstimator behavior, target tracking, AVFrame forwarding,
  threading, or app policy.

Packet 193 parent checkpoint:

- Extended `anomaly_runtime_pressure.{h,c}` with the pure
  `anomaly_runtime_pressure_oldest_drop_count_for_admission(...)` helper.
- `ffmpeg_bridge.c` now asks the runtime pressure contract how many oldest
  local-file AD sidecar frames must be dropped before admitting a new frame.
- The bridge still owns the actual queue storage, `AVFrame` cleanup, queue-head
  advancement, depth mutation, counters, trace counters, locks, and logging.
- This preserves the existing local sidecar admission rule: if the queue is at
  or above the desired depth, drop enough oldest frames so the new admission
  lands at the desired depth.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_runtime_pressure_oldest_drop_count_for_admission(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3841 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks were not rerun because this
  packet extracts existing local sidecar admission arithmetic only. It does not
  change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

Packet 194 parent checkpoint:

- Extended `anomaly_runtime_pressure.{h,c}` with the explicit
  `anomaly_runtime_pressure_policy_make(...)` constructor.
- `anomaly_runtime_pressure_policy_make_default(...)` now delegates to the
  explicit constructor for the built-in pressure defaults.
- `ffmpeg_bridge.c` now constructs the live AD pressure policy in one call,
  passing the bridge's adapter constants for recover depth and pressure
  thresholds instead of making a default policy and mutating the fields.
- This keeps adapter-owned constants in the bridge while making pressure policy
  construction itself host-testable inside the runtime pressure contract.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_runtime_pressure_policy_make(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the constructor was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3843 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks were not rerun because this
  packet extracts existing pressure-policy construction only. It does not
  change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

Packet 195 parent checkpoint:

- Extended `anomaly_runtime_pressure.{h,c}` with
  `anomaly_runtime_pressure_queue_storage_capacity(...)`.
- The helper owns the pure bounded AD input queue storage-capacity calculation:
  normalize requested minimum capacity, honor existing capacity when sufficient,
  start from the initial capacity, grow by doubling below 4096 and by 1024
  above that, clamp to the hard cap, and return 0 when the request cannot fit.
- `ffmpeg_bridge.c` now routes AD input queue capacity selection through the
  runtime pressure helper while keeping allocation, queue copy/move,
  `AVFrame` ownership, queue storage, locks, counters, and traces in the
  bridge.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_runtime_pressure_queue_storage_capacity(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3850 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks were not rerun because this
  packet extracts existing AD input queue storage-capacity arithmetic only. It
  does not change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

Packet 196 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_trim_keep_latest_frames(...)`.
- `ffmpeg_bridge.c` now routes the render queue trim keep-latest calculation
  through the runtime budget helper.
- The bridge still owns render queue storage, trim mutation, `AVFrame` cleanup,
  counters, traces, locks, and logging.
- This preserves the existing render trim policy: use explicit source interval
  and target latency when provided, fall back to session/default timing when
  needed, round up target latency by source interval, and clamp keep-latest to
  the existing 8..36 frame bounds.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_trim_keep_latest_frames(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3857 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks were not rerun because this
  packet extracts existing render queue trim arithmetic only. It does not
  change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

Packet 197 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_render_queue_hard_cap(...)`.
- `ffmpeg_bridge.c` now routes render queue hard-cap sizing through the runtime
  budget helper after deriving the existing fallback keep-latest value from the
  session.
- The bridge still owns render queue allocation, storage, mutation, `AVFrame`
  cleanup, counters, traces, locks, and logging.
- This preserves the existing hard-cap policy: if the supplied keep-latest value
  is below the minimum, fall back to the computed session keep-latest value,
  then size the queue as the maximum of double keep-latest, keep-latest plus the
  extra frame margin, and the minimum hard cap, clamped to the maximum hard cap.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_render_queue_hard_cap(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3865 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks were not rerun because this
  packet extracts existing render queue hard-cap arithmetic only. It does not
  change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

Packet 198 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_target_latency_ms(...)`.
- `ffmpeg_bridge.c` now routes target latency sizing through the runtime budget
  helper using the bridge-owned stall estimate, proven gap, and existing
  latency constants.
- The bridge still owns stall/gap measurement, session state, render queues,
  counters, traces, locks, logging, and `AVFrame` ownership.
- This preserves the existing target latency policy: use the stall estimate
  when positive, otherwise the stall floor; let a larger proven gap override
  the stall estimate; double the chosen stall/gap value; add the processing
  margin; and clamp to the existing min/max target latency bounds.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_target_latency_ms(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3872 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks were not rerun because this
  packet extracts existing target latency arithmetic only. It does not change
  detector scoring, candidate extraction, support-map logic, sampling-state
  lifecycle, MotionEstimator behavior, target tracking, `AVFrame` forwarding,
  threading, or app policy.

Packet 199 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_update_source_interval_estimate(...)` and
  `anomaly_detector_runtime_budget_source_interval_estimate_t`.
- `ffmpeg_bridge.c` now routes decode-delta source interval estimate updates
  through the runtime budget helper using the bridge-owned current interval,
  confidence, decode delta, default interval, EMA percent, confidence step, and
  interval bounds.
- The bridge still owns cadence sample collection, session state, render queues,
  counters, traces, locks, logging, and `AVFrame` ownership.
- This preserves the existing source interval update policy: if confidence is
  not yet positive, trust the decode delta directly; otherwise apply the
  existing weighted EMA with rounding; clamp the interval to the existing
  min/max bounds; and increment confidence by 5 up to 100.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_update_source_interval_estimate(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3880 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks were not rerun because this
  packet extracts existing source interval estimate arithmetic only. It does
  not change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

Packet 200 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_apply_pts_source_interval(...)`.
- `ffmpeg_bridge.c` now routes PTS-derived source interval application through
  the runtime budget helper using the bridge-owned current interval,
  confidence, PTS interval, force-direct flag, default interval, confidence
  thresholds, blend percentages, and interval bounds.
- The bridge still owns PTS observation, cadence sample admission, session
  state, render queues, counters, traces, locks, logging, and `AVFrame`
  ownership.
- This preserves the existing PTS interval application policy: force-direct or
  low-confidence observations use the PTS interval directly; otherwise the
  helper chooses the near/far blend percentage from the interval delta, applies
  the weighted average with rounding, nudges one millisecond toward the PTS
  interval when rounding stalls, clamps to the existing min/max bounds, and
  raises confidence to at least 80.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_apply_pts_source_interval(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3888 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks were not rerun because this
  packet extracts existing PTS source interval application arithmetic only. It
  does not change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

Packet 201 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_update_stall_estimate_ms(...)`.
- `ffmpeg_bridge.c` now routes inter-burst stall estimate updates through the
  runtime budget helper using the bridge-owned current stall estimate, observed
  gap, stall floor, rise/decay EMA percentages, and maximum stall bound.
- The bridge still owns gap observation, timestamp updates, session state,
  render queues, counters, traces, locks, logging, and `AVFrame` ownership.
- This preserves the existing stall estimate policy: use the stall floor when
  the current estimate is invalid, use the rise EMA when the observed gap is at
  or above the current estimate, use the decay EMA otherwise, round the weighted
  average, and clamp to the existing stall floor and maximum target latency
  bounds.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_update_stall_estimate_ms(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3896 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks were not rerun because this
  packet extracts existing stall estimate arithmetic only. It does not change
  detector scoring, candidate extraction, support-map logic, sampling-state
  lifecycle, MotionEstimator behavior, target tracking, `AVFrame` forwarding,
  threading, or app policy.

Packet 202 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_update_proven_gap_ms(...)`.
- `ffmpeg_bridge.c` now routes proven-gap updates through the runtime budget
  helper using the bridge-owned current proven gap, observed gap, stall floor,
  blend EMA percentage, and maximum gap bound.
- The bridge still owns gap observation, trigger guards, timestamp updates,
  session state, render queues, counters, traces, locks, logging, and `AVFrame`
  ownership.
- This preserves the existing proven-gap policy: use the stall floor when the
  current proven gap is invalid, adopt larger observed gaps directly, blend
  smaller observed gaps with the existing 15% EMA, round the weighted average,
  and clamp to the existing stall floor and maximum target latency bounds.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_update_proven_gap_ms(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3904 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks were not rerun because this
  packet extracts existing proven-gap update arithmetic only. It does not
  change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

Packet 203 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_decay_toward_floor_ms(...)`.
- `ffmpeg_bridge.c` now routes both stale stall-estimate decay and stale
  proven-gap decay through the shared runtime budget helper.
- The bridge still owns the grace/interval guards, timestamp updates, debug-log
  thresholds, session state, render queues, counters, traces, locks, logging,
  and `AVFrame` ownership.
- This preserves the existing decay policy: blend the current value toward the
  configured floor using the supplied EMA percentage, round the weighted
  average, clamp to the floor, and leave all call-site timing/log decisions in
  the adapter.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_decay_toward_floor_ms(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3911 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks were not rerun because this
  packet extracts existing stall/proven-gap decay arithmetic only. It does not
  change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

Packet 204 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_desired_render_interval_ms(...)`.
- `ffmpeg_bridge.c` now routes the live render controller's desired interval
  and smoothing arithmetic through the runtime budget helper.
- The bridge still owns local-file fast paths, PTS relock, stall-active
  updates, session state, render queues, counters, traces, locks, logging, and
  `AVFrame` ownership.
- This preserves the existing render interval policy: calculate proportional
  adjustment from buffered-span error, cap faster adjustment by backlog ratio,
  preserve a slower interval during active stalls near the target, clamp to the
  existing source-relative interval bounds, and smooth toward the desired
  interval with the existing EMA.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_desired_render_interval_ms(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3917 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing render interval controller arithmetic
  only. It does not change detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

Packet 205 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_decode_delta_is_gap(...)` and
  `anomaly_detector_runtime_budget_decode_delta_is_plausible_cadence(...)`.
- `ffmpeg_bridge.c` now routes decode-delta gap classification and plausible
  cadence sample classification through the runtime budget helpers using the
  bridge-owned current source interval and existing render cadence constants.
- The bridge still owns sample collection, timestamp updates, PTS relock
  decisions, session state, render queues, counters, traces, locks, logging,
  local-file fast paths, and `AVFrame` ownership.
- This preserves the existing decode-delta classification policy: gap
  detection requires the configured gap floor and the rounded seven-quarter
  source-interval threshold; plausible cadence samples must stay within the
  configured absolute sample bounds and the rounded source-relative percentage
  window.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_decode_delta_is_gap(...)` and
    `anomaly_detector_runtime_budget_decode_delta_is_plausible_cadence(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helpers were missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3929 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing decode-delta classification arithmetic
  only. It does not change detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  render queue mutation, `AVFrame` forwarding, threading, or app policy.

Packet 206 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_decode_stall_active(...)`.
- `ffmpeg_bridge.c` now routes the live render controller's stall-active
  predicate through the runtime budget helper using the bridge-owned current
  time, last decode time, source interval, and existing gap-floor constant.
- The bridge still owns decode timestamps, mutable `session->stall_active`,
  PTS relock decisions, render interval selection, session state, render
  queues, counters, traces, locks, logging, local-file fast paths, and
  `AVFrame` ownership.
- This preserves the existing live decode-stall policy: when no valid decode
  timestamp exists, stall is inactive; otherwise stall becomes active when the
  silence duration reaches the greater of three source intervals and the
  configured render gap floor.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_decode_stall_active(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3935 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing decode-stall predicate arithmetic only.
  It does not change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, render
  queue mutation, `AVFrame` forwarding, threading, or app policy.

Packet 207 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_current_render_interval_ms(...)`.
- `ffmpeg_bridge.c` now routes current render interval selection through the
  runtime budget helper using the bridge-owned smoothed interval, source
  interval, and existing default FPS/min/max interval constants.
- The bridge still owns the `ffmpeg_session_t` null guard, render scheduling,
  render queue storage and mutation, source interval updates, smoothed interval
  mutation, counters, traces, locks, logging, local-file fast paths, and
  `AVFrame` ownership.
- This preserves the existing current render interval policy: prefer the
  positive smoothed interval, otherwise use the positive source interval,
  otherwise use the rounded default-FPS interval, then clamp to the existing
  render interval bounds.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_current_render_interval_ms(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3941 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing current render interval fallback/clamp
  arithmetic only. It does not change detector scoring, candidate extraction,
  support-map logic, sampling-state lifecycle, MotionEstimator behavior, target
  tracking, render queue mutation, `AVFrame` forwarding, threading, or app
  policy.

Packet 208 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_pts_interval_from_span_ms(...)`.
- `ffmpeg_bridge.c` now routes the final PTS interval-from-span arithmetic in
  `queue_pts_interval_ms_locked(...)` through the runtime budget helper using
  the bridge-derived first/last PTS timestamps, valid timestamp count, and
  existing render interval bounds.
- The bridge still owns render queue traversal, queue storage, timestamp
  filtering, monotonicity validation, max-sample selection, PTS relock
  decisions, session state, counters, traces, locks, logging, local-file fast
  paths, and `AVFrame` ownership.
- This preserves the existing PTS interval policy: require at least two valid
  increasing timestamps, divide the span by frame gaps with millisecond
  rounding, then clamp the result to the configured render interval bounds.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_pts_interval_from_span_ms(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3948 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing PTS interval-from-span arithmetic only.
  It does not change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, render
  queue traversal/mutation, `AVFrame` forwarding, threading, or app policy.

Packet 209 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_buffered_span_ms(...)`.
- `ffmpeg_bridge.c` now routes the final buffered-span conversion in
  `buffered_span_ms_locked(...)` through the runtime budget helper using the
  bridge-derived first and last PTS timestamps.
- The bridge still owns render queue traversal, queue storage, timestamp
  filtering, monotonicity validation, session state, counters, traces, locks,
  logging, local-file fast paths, and `AVFrame` ownership.
- This preserves the existing buffered-span policy: require a positive first
  timestamp and a later last timestamp, then convert the microsecond span to
  milliseconds by integer truncation.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_buffered_span_ms(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3953 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing buffered-span conversion arithmetic only.
  It does not change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, render
  queue traversal/mutation, `AVFrame` forwarding, threading, or app policy.

Packet 210 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_interval_from_fps(...)`.
- `ffmpeg_bridge.c` now routes `interval_from_fps(...)` through the runtime
  budget helper using the existing render interval min/max bounds.
- The bridge still owns FFmpeg `AVRational` validation, stream avg/r-frame-rate
  selection, source labels, session state, local-file setup, counters, traces,
  locks, logging, and `AVFrame` ownership.
- This preserves the existing FPS interval policy: reject FPS values at or
  below 1.0, round `1000 / fps` to the nearest millisecond, and clamp to the
  configured render interval bounds.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_interval_from_fps(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3960 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing FPS-to-render-interval arithmetic only.
  It does not change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, FFmpeg
  stream probing, render queue traversal/mutation, `AVFrame` forwarding,
  threading, or app policy.

Packet 211 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_local_playback_target_interval_ms(...)`.
- `ffmpeg_bridge.c` now routes local-file playback target interval selection
  through the runtime budget helper using the bridge-owned nominal interval,
  PTS delta, default FPS, render interval bounds, and existing max reasonable
  interval cap.
- The bridge still owns local-file detection, playback pacing sleeps, pause
  and step state, PTS repair/history state, render timing samples, session
  state, counters, traces, locks, logging, and `AVFrame` ownership.
- This preserves the existing local playback pacing policy: use a positive PTS
  delta when no nominal interval is available, otherwise accept only PTS
  deltas between half and twice nominal clamped by the minimum render interval
  and max reasonable interval cap, falling back to nominal or the rounded
  default-FPS interval.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_local_playback_target_interval_ms(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3966 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed, with the existing
    `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing local playback target-interval arithmetic
  only. It does not change detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  local-file playback sleeps/state, render queue traversal/mutation,
  `AVFrame` forwarding, threading, or app policy.

Packet 212 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_normalize_local_playback_pts_us(...)` and
  its small result struct carrying the normalized PTS plus whether a
  non-monotonic repair happened.
- `ffmpeg_bridge.c` now routes local-file playback PTS normalization through
  the runtime budget helper using the bridge-owned raw PTS, last valid PTS,
  nominal interval, source interval, and default FPS.
- The bridge still owns local-file detection, `last_valid_pts_us`, repair
  counters, repair log throttling, session state, traces, locks, rendering,
  and `AVFrame` ownership.
- This preserves the existing local playback PTS policy: non-local sources
  bypass the helper; missing raw PTS advances from the last valid PTS when one
  exists without incrementing the repair counter; non-monotonic positive PTS
  values advance by the selected interval and continue to increment/log the
  bridge-owned repair counter; monotonic positive PTS values pass through.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_normalize_local_playback_pts_us(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3972 passed, 0 failed`.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing local playback PTS normalization
  arithmetic only. It does not change detector scoring, candidate extraction,
  support-map logic, sampling-state lifecycle, MotionEstimator behavior,
  target tracking, local-file playback state, render queue traversal/mutation,
  `AVFrame` forwarding, threading, or app policy.

Packet 213 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_local_playback_timing_indices(...)` and
  `anomaly_detector_runtime_budget_local_playback_timing_span_is_valid(...)`.
- `ffmpeg_bridge.c` now routes local-playback timing history oldest/newest
  ring-index selection and timing-span validity through runtime budget helpers.
- The bridge still owns the local-playback timing sample arrays, sample writes,
  JNI debug-stat output slots, session state, traces, locks, rendering, and
  `AVFrame` ownership.
- This preserves the existing local playback timing snapshot policy: require
  at least two samples; select the oldest and newest history entries using the
  ring-buffer next/count/capacity values; reject missing or non-increasing PTS
  and render-time spans before filling the debug-stat outputs.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_local_playback_timing_indices(...)` and
    `anomaly_detector_runtime_budget_local_playback_timing_span_is_valid(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because both helpers were missing.
- Parent validation:
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3983 passed, 0 failed`.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing local playback timing-history index and
  span-validity arithmetic only. It does not change detector scoring,
  candidate extraction, support-map logic, sampling-state lifecycle,
  MotionEstimator behavior, target tracking, local-file playback state, render
  queue traversal/mutation, `AVFrame` forwarding, threading, or app policy.

Packet 214 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_local_playback_history_slot(...)` and its
  small result struct carrying the slot index, clamped history offset, and
  validity flag.
- `ffmpeg_bridge.c` now routes local-playback frame-history replay offset
  clamping and ring-slot selection through the runtime budget helper before
  cloning the stored frame.
- The bridge still owns the frame-history slots, sample insertion, replay
  state, frame cloning, source timestamp output, session state, traces, locks,
  rendering, and `AVFrame` ownership.
- This preserves the existing local playback history replay policy: reject
  empty history, clamp negative offsets to the newest frame, clamp offsets past
  the available history to the oldest available frame, normalize the next
  index, and walk backward through the fixed-size ring buffer to select the
  replay slot.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_local_playback_history_slot(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `3992 passed, 0 failed`.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing local playback frame-history replay slot
  arithmetic only. It does not change detector scoring, candidate extraction,
  support-map logic, sampling-state lifecycle, MotionEstimator behavior,
  target tracking, local-file playback state, render queue traversal/mutation,
  `AVFrame` forwarding, threading, or app policy.

Packet 215 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_local_playback_step_forward(...)`,
  `anomaly_detector_runtime_budget_local_playback_step_back(...)`, and the
  shared step decision result struct.
- `ffmpeg_bridge.c` now routes local-playback forward/back step-control
  arithmetic through the runtime budget helpers before applying the resulting
  state changes.
- The bridge still owns JNI entrypoints, local-file/session validation, pause
  state application, render-sync checks, render queue clearing, condition
  signaling, frame-history cloning, render calls, reset side effects, locks,
  traces, logging, and `AVFrame` ownership.
- This preserves the existing step policy: a single forward step while
  replaying history walks toward live history and renders from the stored
  frame; other forward steps leave replay mode, optionally reset detector
  tracking, and saturating-add to the decoder step budget; back steps only
  render when history exists and clamp at the oldest available history frame.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_local_playback_step_forward(...)` and
    `anomaly_detector_runtime_budget_local_playback_step_back(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because both helpers were missing.
- Parent validation:
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `4000 passed, 0 failed`.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing local playback step-control arithmetic
  only. It does not change detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  local-file playback state application, render queue traversal/mutation,
  `AVFrame` forwarding, threading, or app policy.

Packet 216 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_local_playback_append(...)` and its result
  struct carrying the slot index, next index, count, and validity flag.
- `ffmpeg_bridge.c` now routes local-playback timing sample append arithmetic
  and frame-history append arithmetic through the shared runtime budget helper.
- The bridge still owns timing sample arrays, frame-history slots, cloned
  frames, slot clearing, sample payload writes, local playback state, traces,
  locks, logging, and `AVFrame` ownership.
- This preserves the existing append policy: write the current next slot,
  advance next modulo the fixed capacity, increment count until capacity, and
  keep count capped once the ring is full.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_local_playback_append(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `4006 passed, 0 failed`.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing local playback ring append arithmetic
  only. It does not change detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  local-file playback state application, render queue traversal/mutation,
  `AVFrame` forwarding, threading, or app policy.

Packet 217 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_advance_render_due_ms(...)`.
- `ffmpeg_bridge.c` now routes render dequeue due-time advancement through the
  runtime budget helper after a frame is removed from the render queue.
- The bridge still owns render queue storage, dequeue eligibility, frame
  transfer, latency counters, trace counters, queue-head/depth mutation, render
  thread state, locks, logging, and `AVFrame` ownership.
- This preserves the existing scheduling policy: advance by one interval from
  the scheduled due time, and if that next due time is still at or behind
  `now`, skip enough intervals to schedule the next future render tick.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_advance_render_due_ms(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `4012 passed, 0 failed`.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing render due-time advance arithmetic only.
  It does not change detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  render queue traversal/mutation, `AVFrame` forwarding, threading, or app
  policy.

Packet 218 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_queue_tail_index(...)`.
- `ffmpeg_bridge.c` now routes render queue and AD input queue tail-index
  calculation through the runtime budget helper.
- The bridge still owns render/AD queue storage, capacity management, enqueue
  mutation, dequeue mutation, frame cloning, trace counters, locks, logging,
  and `AVFrame` ownership.
- This preserves the existing queue append indexing policy: return zero for
  invalid capacity, otherwise compute `(head + depth) % capacity`, with the
  helper additionally normalizing malformed negative or oversized inputs.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_queue_tail_index(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `4019 passed, 0 failed`.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing queue tail-index arithmetic only. It
  does not change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, render
  or AD queue traversal/mutation, `AVFrame` forwarding, threading, or app
  policy.

Packet 219 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_queue_pop_state(...)`.
- `ffmpeg_bridge.c` now routes render stale-drop, render dequeue, AD input
  dequeue, and local AD sidecar oldest-drop head/depth state updates through
  the runtime budget helper.
- The bridge still owns render/AD queue storage, slot clearing, frame transfer,
  enqueue/dequeue/drop eligibility, trace counters, locks, logging, and
  `AVFrame` ownership.
- This preserves the existing queue pop policy: advance head by one modulo
  capacity, decrement depth, and reset head to zero when the queue becomes
  empty. The helper also normalizes malformed negative or oversized inputs for
  host-test coverage.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_queue_pop_state(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `4027 passed, 0 failed`.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing queue pop-state arithmetic only. It does
  not change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, render
  or AD queue storage, slot clearing, frame transfer, `AVFrame` forwarding,
  threading, or app policy.

Packet 220 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_queue_trim_state(...)`.
- `ffmpeg_bridge.c` now routes render live-edge trim drop-count and resulting
  head/depth state through the runtime budget helper.
- The bridge still owns render queue storage, slot clearing, trim eligibility,
  drop-count application, warning logs, locks, and `AVFrame` ownership.
- This preserves the existing render trim policy: when depth exceeds
  `keep_latest`, drop the oldest `depth - keep_latest` slots, advance head by
  that drop count modulo capacity, and leave depth at `keep_latest`. The helper
  also normalizes malformed negative or oversized inputs for host-test
  coverage.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_queue_trim_state(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `4035 passed, 0 failed`.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing render queue trim-state arithmetic only.
  It does not change detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  render queue storage, slot clearing, frame transfer, `AVFrame` forwarding,
  threading, or app policy.

Packet 221 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_render_queue_storage_capacity(...)`.
- `ffmpeg_bridge.c` now routes render queue storage-capacity selection through
  the runtime budget helper.
- The bridge still owns render queue allocation, slot copying, storage
  replacement, queue-head reset, locks, and `AVFrame` ownership.
- This preserves the existing render queue growth policy: normalize the
  requested minimum to at least one, keep existing capacity when sufficient,
  start from the initial capacity, double while below the growth threshold,
  and grow by the fixed step once at or above the threshold. The helper also
  normalizes malformed current, initial, threshold, and step inputs for
  host-test coverage.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_render_queue_storage_capacity(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `4043 passed, 0 failed`.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing render queue storage-capacity arithmetic
  only. It does not change detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  render queue allocation, slot copying, frame transfer, `AVFrame` forwarding,
  threading, or app policy.

Packet 222 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_queue_offset_index(...)`.
- `ffmpeg_bridge.c` now routes render/AD queue offset-to-slot indexing through
  the runtime budget helper for PTS traversal, buffered-span traversal, queue
  clearing, render trim clearing, pending-overlay lookup, and queue storage
  copy loops.
- The bridge still owns traversal bounds, queue storage, slot clearing, frame
  transfer, slot copying, monotonicity checks, locks, logging, and `AVFrame`
  ownership.
- This preserves the existing queue offset policy: calculate `(head + offset)
  % capacity` for valid capacity, with the helper also normalizing malformed
  negative or oversized heads and negative offsets for host-test coverage.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_queue_offset_index(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `4050 passed, 0 failed`.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing queue offset-index arithmetic only. It
  does not change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  traversal bounds, queue storage, slot clearing, frame transfer, `AVFrame`
  forwarding, threading, or app policy.

Packet 223 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_local_playback_advance(...)`.
- `ffmpeg_bridge.c` now routes the local-file playback wait loop's
  pause/step-budget advance decision through the runtime budget helper.
- The bridge still owns local-file source detection, session-running checks,
  mutex locking, session state application, sleep/retry behavior, JNI
  pause/step commands, render signaling, and `AVFrame` ownership.
- This preserves the existing wait policy: unpaused playback advances without
  consuming step budget; paused playback with a positive step budget consumes
  exactly one step and advances; paused playback without steps waits. The
  helper also normalizes malformed negative step budgets for host-test
  coverage.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_local_playback_advance(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `4056 passed, 0 failed`.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing local playback pause/step advance
  decision arithmetic only. It does not change detector scoring, candidate
  extraction, support-map logic, sampling-state lifecycle, MotionEstimator
  behavior, target tracking, JNI pause/step commands, sleep/retry behavior,
  render signaling, threading, or app policy.

Packet 224 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_startup_observation(...)`.
- `ffmpeg_bridge.c` now routes the render dequeue startup observation
  observe/finalize decision through the runtime budget helper.
- The bridge still owns startup observation activation, current time capture,
  periodic logging, final startup estimate application, session state
  mutation, render due-time reset, locks, and `AVFrame` ownership.
- This preserves the existing startup observation policy: inactive observation
  does nothing; active observation remains in the observe phase while elapsed
  time is below the configured window; at or beyond the window the bridge
  finalizes startup estimates and schedules rendering. The helper also
  normalizes malformed negative elapsed time and nonpositive observe windows
  for host-test coverage.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_startup_observation(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `4062 passed, 0 failed`.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing render startup observe/finalize decision
  arithmetic only. It does not change detector scoring, candidate extraction,
  support-map logic, sampling-state lifecycle, MotionEstimator behavior,
  target tracking, startup estimate application, logging, render signaling,
  threading, or app policy.

Packet 225 parent checkpoint:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_render_lag(...)`.
- `ffmpeg_bridge.c` now routes render dequeue lag, lag-budget, severe-lag,
  and periodic lag-log decision arithmetic through the runtime budget helper.
- The bridge still owns current time capture, render dequeue eligibility,
  log timestamp mutation, any logging side effects, queue/frame transfer,
  trace counters, locks, and `AVFrame` ownership.
- This preserves the existing render lag policy: compute lag from `now -
  scheduled_due`, compute lag budget from `interval - lag`, mark severe lag
  at two source intervals, and update the lag log timestamp when either
  severe-lag or periodic-log conditions are met. The helper also normalizes
  malformed negative intervals for host-test coverage.
- TDD red check:
  - Added native harness tests and header wiring for
    `anomaly_detector_runtime_budget_render_lag(...)`.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected at link
    time because the helper was missing.
- Parent validation:
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: `4069 passed, 0 failed`.
- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing render lag decision arithmetic only. It
  does not change detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, render
  dequeue eligibility, log timestamp mutation, queue/frame transfer, threading,
  or app policy.

Packet 226 parent checkpoint:

- Added app-visible ROI stability publication in
  `anomaly_detector_annotation.{h,c}`.
- The new `anomaly_detector_result_apply_annotation_stability(...)` helper
  tracks recent ROI continuity by algorithm and normalized center, requires
  a strict majority of the render-window frames with a three-observation floor, and
  returns only stable slots capped at four ROIs.
- Once a slot qualifies, it remains published for one render-backlog window;
  already-lit slots keep output priority over newly qualifying slots to prevent
  cap churn, and their display boxes ease toward new raw centers instead of
  snapping between nearby candidates.
- Routed the Android FFmpeg bridge app-visible overlay path and
  `anomaly_video_test --app-display-output` through the stability helper.
- Added positional-consistency reinforcement for confirmed thermal target tracks
  so healthy registration/movement evidence can carry a visually persistent
  target through the short render-backlog window without relying on short-lived
  raw hits.
- Kept detector scoring, candidate extraction, render queue ownership, FFmpeg
  frame ownership, and Kotlin configuration unchanged.
- TDD red check:
  - Added focused native harness tests for transient suppression, strict-majority
    window eligibility, four-ROI cap/ranking, missing-slot age-out, positional
    thermal carry, low-confidence positional reinforcement, and registration-only
    prediction for carried thermal targets.
  - Added follow-up tests for backlog-window publication latch, already-lit ROI
    priority over newcomers, and output-only smoothing of lit ROI motion.
  - `cmake --build tools/anomaly_test/build_timing` failed as expected because
    `anomaly_detector_result_apply_annotation_stability(...)` was missing.
- Validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `tools/anomaly_test/build_timing/anomaly_test`: `4231 passed, 0 failed`.
  - `./gradlew :app:compileDebugKotlin`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, including `anomaly_video_powerhouse1_app_local_opening_recall`.
  - Manual app-visible CSV checks: max simultaneous ROIs stayed at `3` for
    PowerHouse1, `3` for PowerHouse2, and `4` for PowerHouse3; no frames
    exceeded the four-ROI cap.
  - PowerHouse1 opening app-local recall:
    `reviewed_recall=0.453704`, `thermal_matched_tracks=1`,
    `motion_event_count=110`.
  - Display stability metrics improved from current to final app-visible output:
    PowerHouse1 short tracks `<15` frames `15 -> 1`, one-frame tracks `0 -> 0`;
    PowerHouse2 short tracks `26 -> 3`, one-frame tracks `3 -> 0`;
  PowerHouse3 short tracks `135 -> 3`, one-frame tracks `59 -> 0`.

Packet 227 parent checkpoint:

- Added Person Relevance as shared, optional semantic evidence for emitted
  Target Color, Color Outlier, and IR target ROIs; it is not a fourth detector.
- Added operator modes `Off`, `Evaluate`, and experimental `Assist`. `Off` is
  the default, `Evaluate` is scoring-neutral, and `Assist` can add only a
  bounded positive relevance bonus.
- Added a dedicated capacity-one/latest-wins Person worker with top-two target
  admission, runtime-pressure drops, generation/sequence/source-age/track-ID
  staleness checks, and explicit frame-reference teardown.
- Decode, render, UI, and AD admission never wait for LiteRT inference. Crop
  conversion and the synchronous LiteRT call run only on the Person worker.
- Added a pinned EfficientDet-Lite0 COCO model, verified identity and SHA-256,
  Apache-2.0 provenance, strict tensor validation, one-thread CPU fallback,
  and neutral failure behavior.
- Extended local playback review schema v2 with optional normalized Person
  evidence while preserving schema-v1 parsing and local-only review behavior.
- Added deterministic native contract/scheduler tests, Kotlin engine/policy/
  serialization tests, fixtures, and `personRelevanceQualification`, wired into
  `releaseVerification`.
- Validation:
  - Native CTest: `7/7` passed.
  - `./gradlew :app:testDebugUnitTest :app:personRelevanceQualification
    --no-parallel`: passed.
  - `./gradlew :app:releaseCheck --no-parallel`: passed, including release
    lint/R8, APK assembly, and Crashlytics mapping/native-symbol uploads.
  - Native arm64 Color qualification: passed with unchanged detection output.
  - The unchanged-HEAD and modified-tree `anomaly_video_test` executables are
    byte-for-byte identical under the same CMake 3.22 arm64 toolchain, proving
    no host detector instruction or benchmark change in `Off` mode.
- No Android device was connected for this checkpoint. S25U inference latency,
  heat, battery, and reviewed aerial-person quality remain required promotion
  evidence; `Assist` therefore remains explicitly experimental.

Packet 228 parent checkpoint:

- Removed the legacy automatic appearance mode from configuration, persistence, stream
  controls, runtime resolution, and operator documentation.
- Color is now the default appearance mode. Infrared remains an explicit
  operator selection.
- Existing saved automatic-mode values and unknown persisted values migrate to Color;
  saved Infrared selections remain Infrared.
- Removed captured-video appearance sampling and grayscale classification from
  `StreamsViewModel`, eliminating work that could select the wrong detector for
  mixed-color golden-hour footage.
- Preserved the separate automatic pixel-sampling Detail control; it is not an
  appearance mode.
- Validation:
  - `./gradlew :app:testDebugUnitTest --no-parallel --console=plain`: passed.
  - `./gradlew :app:releaseCheck --no-parallel --console=plain`: passed,
    including Color realtime and Person Relevance qualifications, lint/R8,
    release APK assembly, and Crashlytics mapping/native-symbol uploads.
  - `git diff --check`: passed.

Packet 229 parent checkpoint:

- Fixed a flaky `colorRealtimeQualification` target-color microbenchmark that
  could fail when macOS descheduled the probe between its baseline and stress
  cases.
- Changed only the synthetic target-color performance probe from monotonic wall
  time to process CPU time. The reviewed Red-clip qualification continues to
  measure end-to-end wall-clock realtime performance.
- Production detector code, thresholds, output, and app runtime behavior are
  unchanged.
- Validation:
  - Target-color CTest: passed.
  - Color qualification Python tests: `23` passed.
  - `./gradlew :app:colorRealtimeQualification --no-parallel`: passed.
  - `./gradlew :app:releaseCheck --no-parallel`: passed, including release
    lint/R8, APK assembly, and Crashlytics mapping/native-symbol uploads.
  - `git diff --check`: passed.
