# IR Anomaly App-Local Playback Handoff (2026-06-14)

This handoff captures the state after the PowerHouse1 local playback regression
investigation. It is intended for the next thread that takes ownership of
restoring useful IR anomaly detection and playback performance.

## Current Truth

This is not solved.

The Android app and host harness now agree on the current app-local playback
failure mode for:

- `app/src/test/resources/vidcap/PowerHouse1.mp4`
- `app/src/test/resources/vidcap/PowerHouse1.review.json`

The app-visible symptom remains:

- no useful opening IR detection on the reviewed PowerHouse1 target window
- late thermal boxes near `10.8s`
- motion false positives near the start
- playback/analysis still slower than desired

The latest host app-parity opening gate reports:

- `0/108` true-positive reviewed annotations
- `0/1` matched target tracks
- `15` app-visible box frames, all motion and away from the reviewed target
- roughly `0.84x` realtime on the 4.8 second opening excerpt in the most recent
  focused run

## Harness Parity Repair

The host harness previously used the wrong local playback stride for
`--app-defaults`. The Android local captured playback path applies
`defaultAnomalyConfig.forLocalPlaybackReview()`, which keeps local review at
fixed stride `2` when defaults are otherwise untouched.

The harness now uses the same app-local playback posture:

```text
--app-defaults --app-display-output
stride=fixed fixed=2
threshold=4.81
mask=6
registration=affine
polarity=black-hot
motion=on
saliency=off
pixelStep=0
```

The app-local profile was also added to
`tools/anomaly_test/regression_suite_manifest.json` as
`app-local-playback-defaults`.

## New Gates

The next owner should treat these as the minimum local gates before claiming
progress.

Build:

```sh
cmake --build tools/anomaly_test/build_timing
```

Harness parity sanity check:

```sh
ctest --test-dir tools/anomaly_test/build_timing \
  -R anomaly_video_app_defaults_local_playback_config \
  --output-on-failure
```

This must pass and proves `--app-defaults` is using local playback stride `2`.

Current failing functional gate:

```sh
ctest --test-dir tools/anomaly_test/build_timing \
  -R anomaly_video_powerhouse1_app_local_opening_recall \
  --output-on-failure
```

This currently fails with `0/108` reviewed recall and `0/1` matched tracks. It
should not be weakened or removed. The next thread should make this pass by
improving actual app-equivalent detection behavior.

Full app-local reviewed suite:

```sh
python3 tools/anomaly_test/run_regression_suite.py \
  --manifest tools/anomaly_test/regression_suite_manifest.json \
  --binary tools/anomaly_test/build_timing/anomaly_video_test \
  --out-dir /private/tmp/r2c_ad_app_parity_suite \
  --report-json /private/tmp/r2c_ad_app_parity_suite/suite_report.json \
  --report-md /private/tmp/r2c_ad_app_parity_suite/suite_report.md
```

The latest app-local profile result before this handoff:

- reviewed excerpts: `3`
- TP annotations: `67/279`
- FP annotations: `0/92`
- reviewed precision: `1.000`
- reviewed recall: `0.240`
- track hits: `2/4`
- realtime factor: about `2.47x` over the reviewed suite
- PowerHouse1 opening target window: `0/108`, `0/1` tracks

## App Log Evidence

The latest `log.txt` run applied the same local config:

```text
enabled=true mask=6 reg=2 strideMode=0 stride=2
adaptiveMin=2 adaptiveMaxFrames=33 adaptiveMaxSec=1.0
pixelStep=0 threshold=4.81 minHits=2 scanZone=0.50
colorFrontend=0
```

The runtime queue work is visible in the log:

- AD local sidecar queue capped around `q=5/5`
- render queue trims to live edge around `keep=22`
- render control target latency around `700ms`

The app-visible overlay output still misses the opening reviewed target:

- motion overlays around `2.033s` through `2.800s`
- late thermal overlays around `10.800s` through `11.500s`
- late thermal box location around `l=0.427 t=0.382 r=0.465 b=0.420`

## Important Negative Results

Do not repeat these as blind fixes without new evidence:

- Lowering the thermal score threshold scale from `0.62` to `0.58` did not
  recover PowerHouse1 opening recall and slowed the focused run.
- Aligning thermal provisional candidate scoring to the scaled thermal
  threshold increased early false-positive output and still produced `0/108`
  opening recall.
- Increasing `ANOMALY_MAX_THERMAL_CANDIDATES` from `8` to `12` did not recover
  app-visible opening recall.
- Allowing strong unsettled singleton thermal candidates to stamp target
  history did not recover app-visible opening recall.

These experiments were backed out.

## Current Detector Evidence

Raw detector output also misses the opening reviewed target. The problem is not
only app-visible smoothing.

Thermal debug around the reviewed opening target shows that the target is not
completely invisible:

- near-target candidates exist intermittently
- several frames near `2.9s` to `3.5s` show a compact candidate near
  `x=0.316..0.318`, `y=0.313..0.324`
- at `3.167s`, the debug target has a near candidate around
  `x=0.316832`, `y=0.316960`, final score about `3.09`, and
  `above_threshold=true`
- the same window still does not produce a stable app-visible IR sequence

This suggests the next investigation should focus on the path from intermittent
near-target thermal evidence to stable published boxes:

- candidate ranking and capping
- accumulator continuity
- thermal publish holdoff and singleton suppression
- interaction with motion false positives
- target-track publication and confirmation gates
- why frames with near-target `above_threshold=true` do not create visible
  target recall in the reviewed gate

## Working Theory

The regression is likely in detector publication/continuity rather than in
Android file playback or harness plumbing.

The opening PowerHouse1 person behaves like a compact black-hot singleton. It
appears intermittently in thermal debug, but the detector does not maintain or
publish it across enough app-visible frames. Meanwhile, unrelated motion boxes
can become the visible output around the same early window.

Treat this as a detector continuity/reacquisition problem until proven
otherwise.

## Next Thread Instructions

Start from the failing app-local harness gate. Do not rely on new app logs
unless the harness and app diverge again.

Recommended order:

1. Reproduce the failing gate:
   `anomaly_video_powerhouse1_app_local_opening_recall`.
2. Generate thermal debug JSONL for the opening window and summarize only the
   target path and near-target candidates.
3. Trace why near-target thermal candidates with `above_threshold=true` do not
   become stable published boxes.
4. Add or tighten a focused host test before changing detector behavior.
5. After any detector change, run:
   - focused PowerHouse1 opening gate
   - app-local reviewed regression suite
   - native unit tests / CTest
   - focused Gradle config/runtime tests if app-facing config changes
6. Reject changes that improve one frame or one metric while worsening
   PowerHouseTeam false positives or runtime materially.

Do not claim functionality is restored until the app-local PowerHouse1 opening
gate passes and the app-local reviewed suite improves or remains neutral on
false positives and runtime.
