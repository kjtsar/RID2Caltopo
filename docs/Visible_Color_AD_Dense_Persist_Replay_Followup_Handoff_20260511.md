# Visible Color AD Dense Persist Replay Follow-Up Handoff (2026-05-11)

This note follows up on
[Visible_Color_AD_Dense_Verifier_Followup_Handoff_20260511.md](/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Dense_Verifier_Followup_Handoff_20260511.md)
after wiring dense color candidate geometry more directly into persistence and
target revisit tracking.

## Executive Summary

The dense-state persistence follow-up is now implemented in code and native unit
tests still pass, but replay/perf validation exposed an important split result:

- app-like visible-color replay on `Red1.mp4` still works and keeps healthy
  affine lock with strong target-only reuse
- the fresh-RGBA visible-color path is currently much slower than the saved
  May 10 visible-color perf baseline
- the measured slowdown is concentrated in `sampled_grid_prep` and
  `color_scoring`, not in `target_tracking`

Interpretation:

- the dense persistence/revisit wiring itself looks functionally safe so far
- the current visible-color code state should not be treated as perf-safe until
  the frontend/runtime regression is understood

## Code State Added In This Thread

Dense color candidates now feed tracked observations more directly:

- dense bbox-derived footprint
- dense centroid-backed observation center
- dense support radius
- a modest persistence bonus when a current color winner agrees with a prior
  color/persist track under healthy registration
- reuse of dense geometry for the persist observation when color wins the
  derived persist cue

Files changed in this thread:

- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c)
- [tools/anomaly_test/test_anomaly.c](/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/test_anomaly.c)

## Native Verification

Rebuilt and reran native tests:

```sh
cmake -S tools/anomaly_test -B tools/anomaly_test/build
cmake --build tools/anomaly_test/build
./tools/anomaly_test/build/anomaly_test
```

Result:

- `81 passed, 0 failed`

The new regression test confirms the active color target track now follows the
dense candidate bbox instead of falling back to the older generic square
footprint.

## Replay Validation Artifacts

### 1. App-like replay on Red1 using legacy frontend

Purpose:

- verify planner/registration behavior under app-parity color settings

Command:

```sh
tools/anomaly_test/build_timing/anomaly_video_test \
  app/src/test/resources/vidcap/Red1.mp4 \
  --no-video \
  --app-defaults \
  --app-appearance color \
  --app-motion off \
  --app-saliency off \
  --registration affine \
  --stride 1 \
  --time-start 0.0 \
  --time-end 5.1 \
  --summary-json /tmp/red1_dense_followup_summary_20260511.json \
  --color-debug-jsonl /tmp/red1_dense_followup_color_debug_20260511.jsonl \
  -c /tmp/red1_dense_followup_detections_20260511.csv
```

Important result:

- frontend used: `legacy`
- frames processed: `153`
- frames with boxes: `21`
- rescan modes: `full=39`, `partial=29`, `target-only=85`
- registration invalid reasons: `debug-input-unavailable=1`, otherwise valid
- average total frame time: `32.83 ms`
- average color scoring time: `12.41 ms`

Use this only as a planner/registration sanity check. It did not exercise the
fresh dense verifier path.

Artifacts:

- `/tmp/red1_dense_followup_summary_20260511.json`
- `/tmp/red1_dense_followup_color_debug_20260511.jsonl`
- `/tmp/red1_dense_followup_detections_20260511.csv`

### 2. App-like replay on Red1 using fresh-RGBA frontend

Purpose:

- validate the dense verifier path under app-like visible-color replay

Command:

```sh
tools/anomaly_test/build_timing/anomaly_video_test \
  app/src/test/resources/vidcap/Red1.mp4 \
  --no-video \
  --app-defaults \
  --app-appearance color \
  --app-motion off \
  --app-saliency off \
  --color-frontend fresh-rgba \
  --registration affine \
  --stride 1 \
  --time-start 0.0 \
  --time-end 5.1 \
  --summary-json /tmp/red1_dense_followup_freshrgba_summary_20260511.json \
  --color-debug-jsonl /tmp/red1_dense_followup_freshrgba_color_debug_20260511.jsonl \
  -c /tmp/red1_dense_followup_freshrgba_detections_20260511.csv
```

Important result:

- frontend used: `fresh-rgba`
- frames processed: `153`
- frames with boxes: `66`
- total box events: `71`
- rescan modes: `full=39`, `partial=28`, `target-only=86`
- scan reasons remained dominated by:
  - `stale-high=38`
  - `target-only-eligible=86`
  - `partial-eligible=28`
- registration invalid reasons: `debug-input-unavailable=1`, otherwise valid
- average total frame time: `79.87 ms`
- average registration solve time: `5.41 ms`
- average sampled-grid prep time: `22.90 ms`
- average color scoring time: `41.45 ms`
- average target tracking time: effectively `0.00 ms`

What this validates:

- affine lock remained healthy for essentially the whole reviewed clip
- target-only revisit remained active for most frames
- the dense persistence/revisit handoff did not break basic replay behavior

What this did not validate:

- no distinct `periodic-full-refresh` scan reason was observed in this short
  replay
- this run alone does not prove the current perf is acceptable

Artifacts:

- `/tmp/red1_dense_followup_freshrgba_summary_20260511.json`
- `/tmp/red1_dense_followup_freshrgba_color_debug_20260511.jsonl`
- `/tmp/red1_dense_followup_freshrgba_detections_20260511.csv`

## Visible-Color Perf Validation

### Saved May 10 benchmark reference

Reference report:

- [tools/anomaly_test/out/visible-color-perf-20260510-red1-fix/visible_color_perf_report.json](/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/visible-color-perf-20260510-red1-fix/visible_color_perf_report.json)

Key saved aggregates:

- `visible-color-app-like-auto`
  - realtime factor: `0.997`
  - avg total: `29.52 ms`
  - avg sampled-grid prep: `7.10 ms`
  - avg color scoring: `8.83 ms`
- `visible-color-dense-gold`
  - realtime factor: `0.162`
  - avg total: `300.94 ms`
  - avg sampled-grid prep: `82.00 ms`
  - avg color scoring: `130.05 ms`

### Current May 11 app-like auto replay matrix

Completed current summaries:

- [visible-color-app-like-auto_Color1-0.0s-10.0s_summary.json](/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/visible-color-perf-20260511-dense-persist-followup/visible-color-app-like-auto_Color1-0.0s-10.0s_summary.json)
- [visible-color-app-like-auto_Color2-0.0s-10.0s_summary.json](/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/visible-color-perf-20260511-dense-persist-followup/visible-color-app-like-auto_Color2-0.0s-10.0s_summary.json)
- [visible-color-app-like-auto_Color3-0.0s-10.0s_summary.json](/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/visible-color-perf-20260511-dense-persist-followup/visible-color-app-like-auto_Color3-0.0s-10.0s_summary.json)

Computed averages from those three summaries:

- realtime factor: `0.359`
- avg total: `89.23 ms`
- avg sampled-grid prep: `22.68 ms`
- avg color scoring: `47.05 ms`
- avg refresh-mask build: `0.65 ms`

Compared with the saved May 10 app-like aggregate:

- total frame time: `29.52 ms -> 89.23 ms` (`~3.0x` slower)
- sampled-grid prep: `7.10 ms -> 22.68 ms` (`~3.2x` slower)
- color scoring: `8.83 ms -> 47.05 ms` (`~5.3x` slower)
- realtime factor: `0.997 -> 0.359`

This is a material visible-color perf regression.

### Current dense-gold spot check

Completed current dense artifact:

- [visible-color-dense-gold_Color1-0.0s-10.0s_summary.json](/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/visible-color-perf-20260511-dense-persist-followup/visible-color-dense-gold_Color1-0.0s-10.0s_summary.json)

Observed on `Color1` alone:

- realtime factor: `0.042`
- avg total: `782.25 ms`
- avg sampled-grid prep: `198.45 ms`
- avg color scoring: `486.90 ms`
- avg refresh-mask build: `5.79 ms`

Compared with the saved May 10 dense-gold aggregate:

- total frame time is already far above `300.94 ms`
- color scoring is already far above `130.05 ms`

Even this one-case spot check is enough to say the dense visible-color path is
currently well outside the earlier performance envelope.

## Best Current Interpretation

Most important inference:

- the new dense persistence/revisit wiring is probably not the main runtime
  culprit

Why:

- the replay slowdown is showing up almost entirely in:
  - `sampled_grid_prep`
  - `color_scoring`
- `target_tracking` remains effectively zero-cost in the same summaries

That does not prove the current thread added zero cost, but it strongly
suggests the next perf investigation should start in the visible-color frontend
and candidate-generation path, not in the new tracked-observation plumbing.

## Recommended Next Thread

Do this next, in order:

1. Treat the fresh-RGBA replay behavior as functionally acceptable enough to
   keep the dense persistence handoff code for now.
2. Pause further visible-color tuning until the perf regression is isolated.
3. Start the perf investigation in:
   - sampled-grid preparation
   - dense verifier/candidate extraction
   - fresh-RGBA color scoring flow
4. Re-run the full visible-color perf benchmark after any perf-oriented change
   and compare against:
   - `tools/anomaly_test/out/visible-color-perf-20260510-red1-fix/visible_color_perf_report.json`
5. If needed, add temporary timing sub-buckets inside the fresh color path so
   the cost split between:
   - sampling
   - support-map build
   - dense local verification
   - candidate extraction/ranking
   is explicit rather than inferred

## Suggested New-Thread Prompt

Use something close to this:

> Continue from
> [Visible_Color_AD_Dense_Persist_Replay_Followup_Handoff_20260511.md](/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Dense_Persist_Replay_Followup_Handoff_20260511.md).
> Preserve the dense persistence/revisit wiring unless replay evidence shows it
> is functionally wrong. Focus next on the visible-color perf regression.
> Use the saved May 10 perf report as the comparison baseline, and start by
> instrumenting or isolating the cost inside sampled-grid prep and color
> scoring rather than target tracking.
