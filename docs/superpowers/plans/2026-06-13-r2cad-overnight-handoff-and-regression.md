# R2CAD Overnight Handoff And Regression Notes

Date: 2026-06-13

## Current State

This thread is still usable, but the current work is also at a clean handoff
checkpoint if a fresh thread becomes preferable.

Completed and parent-validated packets in this runtime-contract lane:

- Packet 188: `anomaly_runtime_budget.{h,c}` owns the first standalone
  Cursory/Thorough runtime budget contract.
- Packet 189: `anomaly_runtime_pressure.{h,c}` owns live AD queue-pressure
  thresholding, pressure mode selection, recovery, bypass decisions, and mode
  names.
- Packet 190: `anomaly_runtime_handoff.{h,c}` owns pure AD worker frame
  metadata readiness, stale-generation checks, analyze/forward decisions, and
  reason names.
- Packet 191: `anomaly_runtime_handoff.{h,c}` also owns pure AD worker outcome
  counter deltas for processed, skipped, forwarded-without-analysis, and
  annotated frames.

Important runtime boundary:

- The current Android live path is already threaded:
  `FFmpeg decode -> AD input queue -> AD worker -> render queue -> render thread`.
- MotionEstimator is modularized behind `anomaly_motion_estimator.{h,c}`, but
  it still runs synchronously inside `anomaly_process_frame()` on the AD worker
  path.
- Do not start the separate ME worker/evidence queue split until the full
  regression gate below has run and the handoff docs have been updated with the
  results.

Bridge ownership remains intentionally intact:

- `ffmpeg_bridge.c` still owns queue storage, pthread synchronization, AVFrame
  ownership, overlay frame ownership, render forwarding, worker counters,
  cleanup, JNI/session lifecycle, and app logging.
- The new runtime modules are pure policy/contract helpers. They should not
  include FFmpeg, JNI, pthreads, or mutable app session state.

## Current Focused Validation

Latest focused validation after Packet 191:

```bash
git diff --check
cmake --build tools/anomaly_test/build_timing
ctest --test-dir tools/anomaly_test/build_timing --output-on-failure
tools/anomaly_test/build_timing/anomaly_test
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest
./gradlew :app:compileDebugKotlin
```

Observed result:

- `ctest`: `1/1` passed.
- `tools/anomaly_test/build_timing/anomaly_test`: `3828 passed, 0 failed`.
- Both Gradle commands completed with `BUILD SUCCESSFUL`.

This focused validation is enough for the bounded helper-extraction packets,
but it is not enough to claim full replay/performance parity.

## Full Regression Gate

Run this gate before either:

- handing the work off to a fresh thread as behavior-equivalent, or
- starting a larger ownership move such as a separate ME worker/evidence queue.

Use `/private/tmp` output directories so the repo stays clean:

```bash
git diff --check
cmake --build tools/anomaly_test/build_timing
ctest --test-dir tools/anomaly_test/build_timing --output-on-failure
tools/anomaly_test/build_timing/anomaly_test
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest
./gradlew :app:compileDebugKotlin
python3 tools/anomaly_test/run_regression_suite.py \
  --binary tools/anomaly_test/build_timing/anomaly_video_test \
  --manifest tools/anomaly_test/regression_suite_manifest.json \
  --out-dir /private/tmp/rid2c_runtime_contracts_ir_regression
python3 tools/anomaly_test/run_regression_suite.py \
  --binary tools/anomaly_test/build_timing/anomaly_video_test \
  --manifest tools/anomaly_test/regression_suite_color_manifest.json \
  --out-dir /private/tmp/rid2c_runtime_contracts_color_regression
python3 tools/anomaly_test/run_visible_color_perf_benchmarks.py \
  --binary tools/anomaly_test/build_timing/anomaly_video_test \
  --output-dir /private/tmp/rid2c_runtime_contracts_visible_color_perf
python3 tools/anomaly_test/run_registration_perf_benchmarks.py \
  --binary tools/anomaly_test/build_timing/anomaly_video_test \
  --output-dir /private/tmp/rid2c_runtime_contracts_registration_perf
```

After the full gate runs, update:

- `docs/AnomalyDetector_Modularization_Parent.md`
- `docs/AnomalyDetector_Modularization_Child_Packets.md` if a new packet is
  created or the handoff status changes.
- This file, replacing this section with exact pass/fail evidence and report
  paths.

## Current Files To Review

Runtime-contract source files added or changed in this lane:

- `app/src/main/cpp/anomaly_runtime_budget.h`
- `app/src/main/cpp/anomaly_runtime_budget.c`
- `app/src/main/cpp/anomaly_runtime_pressure.h`
- `app/src/main/cpp/anomaly_runtime_pressure.c`
- `app/src/main/cpp/anomaly_runtime_handoff.h`
- `app/src/main/cpp/anomaly_runtime_handoff.c`
- `app/src/main/cpp/ffmpeg_bridge.c`
- `app/src/main/cpp/CMakeLists.txt`
- `tools/anomaly_test/CMakeLists.txt`
- `tools/anomaly_test/test_anomaly.c`

Direction/context docs updated in this lane:

- `AD_Guideance.md`
- `docs/Current_Anomaly_Detector.md`
- `docs/AnomalyDetector_Modularization_Parent.md`
- `docs/AnomalyDetector_Modularization_Child_Packets.md`
- `docs/superpowers/plans/2026-06-12-r2cad-runtime-budget-contract.md`
- `docs/superpowers/plans/2026-06-13-r2cad-runtime-pressure-policy.md`
- `docs/superpowers/plans/2026-06-13-r2cad-runtime-frame-handoff-contract.md`
- `docs/superpowers/plans/2026-06-13-r2cad-runtime-handoff-outcome-contract.md`

The workspace has many unrelated untracked files. Review the relevant paths
above rather than treating `git status --short` as a clean packet list.

## Next Safe Work

Safe next packets while staying in the bounded lane:

- Add more host-testable runtime contracts around local-playback sidecar
  completion/late-attachment policy, if it can stay pure.
- Name render publication decision policy only if it does not move queue,
  pthread, or AVFrame ownership.
- Add source-guard tests that assert the bridge remains the owner of AD queue
  storage and AVFrame lifetime while pure helpers stay FFmpeg/JNI-free.

Hold off on:

- separate ME worker/evidence queue implementation,
- moving queue storage out of `ffmpeg_bridge.c`,
- moving render publication ownership out of `ffmpeg_bridge.c`,
- claiming full behavior parity without the full regression gate.
