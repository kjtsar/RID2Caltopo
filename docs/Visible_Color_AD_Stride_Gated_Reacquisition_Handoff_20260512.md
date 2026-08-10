# Visible Color AD Stride-Gated Reacquisition Handoff (2026-05-12)

This handoff captures the current checkpoint after restoring the intended
stride-gated Color Anomaly Detector control loop in the app path.

## Current Truth

The app is now applying the intended full-refresh cadence:

- latest `log.txt` run at `12May123435`
- `Applying anomaly config ... stride=10`
- native `anomaly config applied ... stride=10`
- color summaries include the new `regdbg[...]` field

Affine registration is not the active failure in that run:

- registration reports `reg=healthy` after the first frame
- fitted scale is effectively neutral:
  - early target-only frames: `scale=0.9992`, residual about `0.0010`
  - cadence frame 120: `scale=1.0005`, residual about `0.0004`
- `new=0.00` and `warped_valid_fraction=1.00` in the logged summaries

The control loop is mostly doing the intended thing:

- AD is invoked every frame for registration / track maintenance
- cadence full scan appears on full-refresh frames
- interim frames examine selective targets, usually capped at `examined=6`
- full/dense cadence frames still show `examined=32`

The live blocker has moved from control-loop/perf wiring to Red1 reacquisition:

- latest current-code app run has no `render posted overlay` lines
- logged `anomaly frame result` entries all have `boxCount=0`
- strong color seeds exist, but blob candidates are usually rejected as `area`
- common interim reject shape:
  - `targetSpanPx=7.3`
  - `targetCells=2`
  - `maxArea=16`
  - `examined=6`
  - `strongestReject=area`
  - `area=0`
  - `span=5.0` to `6.3`

Interpretation:

- AR is staying locked in the latest run.
- Full-frame analysis is no longer happening every frame.
- The top-6 interim cap is active.
- The next bug is candidate/reacquisition quality under stride 10, especially
  why plausible compact Red1 seeds are converted into zero-area / over-span
  `area` rejects instead of surviving as small-target candidates.

## Active Code Changes To Preserve

Do not revert these as a batch; they are the current starting point.

Native/app control-loop changes:

- `app/src/main/cpp/anomaly_analysis.h`
  - default native frame stride is now `10`
- `app/src/main/java/org/ncssar/rid2caltopo/video/anomaly/AnomalyModels.kt`
  - default `AnomalyConfig.frameStride` is now `10`
  - native clamp is `1..10`
- `app/src/main/java/org/ncssar/rid2caltopo/video/anomaly/AnomalyPrefs.kt`
  - persisted legacy/default-like configs with `frameStride=1` migrate to `10`
- `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt`
  - stride cycle now includes up to `10`
- `app/src/main/java/org/ncssar/rid2caltopo/video/StreamTile.kt`
  - UI clamp/slider supports `1..10`
- `app/src/main/cpp/ffmpeg_bridge.c`
  - native clamp supports `1..10`
  - color summaries include `regdbg[...]`
  - color dropout lines now include `mode`, scan plan, reasons, and compact
    registration telemetry for the next run

Native scan-planning changes:

- `frameStride` is now treated as a full-frame refresh cadence, not as the
  detector invocation cadence
- AR and scan planning still run on analyzed frames
- non-cadence frames can run target-only or partial color work
- stale-high is logged but no longer independently forces full
- scene discontinuity, invalid/hard-degraded registration, low warp coverage,
  high newly exposed fraction, sample-step mismatch, and cadence/periodic full
  still force full
- fresh-RGBA no longer forces full analysis every frame
- non-full dense seed retention is capped at 6
- target-only candidate extraction hard-rejects blobs beyond the configured
  small-target size instead of ranking them down

## Validation Already Run

Build / tests:

- follow-up telemetry/accounting patch passed:
  - `cmake --build tools/anomaly_test/build_timing`
  - `tools/anomaly_test/build_timing/anomaly_test`
  - result: `87 passed, 0 failed`
  - `./gradlew :app:externalNativeBuildDebug`
  - result: `BUILD SUCCESSFUL`
- follow-up target support-map telemetry patch passed:
  - `cmake --build tools/anomaly_test/build_timing`
  - `tools/anomaly_test/build_timing/anomaly_test`
  - result: `87 passed, 0 failed`
  - `./gradlew :app:externalNativeBuildDebug`
  - result: `BUILD SUCCESSFUL`
- `./gradlew :app:externalNativeBuildDebug` passed after adding current
  dropout breadcrumbs
- timing harness native suite previously passed:
  - `cmake --build tools/anomaly_test/build_timing`
  - `tools/anomaly_test/build_timing/anomaly_test`
  - result: `83 passed, 0 failed`
- focused Kotlin config test previously passed:
  - `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.anomaly.AnomalyConfigTest`

App-log evidence:

- current run is the `12May123435` run in `log.txt`
- it proves `stride=10` and `regdbg[...]`
- it does not prove ROI recovery; it proves the opposite for this run:
  no overlays / no boxes

## 2026-05-12 Follow-Up Patch

The first follow-up did not relax any detector thresholds or small-target
guards. It fixed debug/accounting for dense verifier rejects:

- dense verifier span/area rejects now propagate measured dense area into
  `strongestReject=area ... area=...`
- zero-area area rejects in the `12May123435` log are therefore interpreted as
  missing telemetry, not proof that the dense component had no pixels
- a native regression fixture now rejects an elongated same-color dense
  component while asserting that both measured area and span survive into color
  debug output

Next app playback should re-check the same Red1 dropout lines. If
`strongestReject=area` remains dominant with nonzero measured area, the next
bug is true over-span dense growth / candidate geometry, not zero-area
component creation.

## 2026-05-12 Local Replay Follow-Up

Local app-parity replay now reproduces the current app failure quickly:

```sh
tools/anomaly_test/build_timing/anomaly_video_test \
  app/src/test/resources/vidcap/Red1.mp4 \
  --no-video \
  --app-defaults \
  --app-appearance color \
  --app-motion off \
  --app-saliency off \
  --color-frontend fresh-rgba \
  --time-start 0.0 \
  --time-end 5.1 \
  --summary-json /tmp/red1_support_metrics_summary.json \
  --color-debug-jsonl /tmp/red1_support_metrics_color_debug.jsonl \
  --color-target-csv tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv \
  -c /tmp/red1_support_metrics_detections.csv
```

Result:

- `0` detection frames / `0` boxes
- rescan modes: `full=16`, `partial=117`, `target-only=20`
- registration remained healthy except the first debug-input-unavailable frame
- legacy frontend under the same app-parity stride still produced boxes, so the
  current blocker is fresh-RGBA candidate construction / retention rather than
  the stride-gated control loop itself

New target support-map telemetry is now included in color JSONL rows:

- `support_map_local_peak`
- `support_map_ring_mean`
- `support_map_density`
- `support_map_distinctness_ratio`
- `support_map_compact_prominence`
- `support_map_core_share`
- `support_map_seed_floor`

Key Red1 replay findings:

- target stage counts: `none=92`, `rarity_rejected=51`,
  `support_map_rejected=6`, `no_candidate=4`
- target support-eligible frames are rare (`4` of `61` valid target rows)
- the previous `area=0` area rejects now report nonzero measured areas, e.g.
  frame 1 `area=11.44 span=5.33`; zero-area was missing telemetry
- target support-map density is often `1.0`, with ring mean close to local
  peak, so fresh distinctness/prominence suppresses many reviewed target rows:
  - frame 30: pre `2.76`, post `0.449`, peak `2.76`, ring `2.41`,
    density `1.0`, distinctness `1.15`, prominence `0.11`
  - frame 50: pre `2.65`, post `0.264`, peak `2.76`, ring `2.53`,
    density `1.0`, distinctness `1.09`, prominence `0.02`
- frame 117 is the cleanest next probe: target post-support is strong
  (`2.41`) but no candidate survives; strongest reject is elsewhere as
  `area=31.44 span=6.33`

Interpretation:

- the live blocker is no longer "zero-area geometry"
- the remaining split is:
  1. target pre-support is frequently crushed by fresh support-map
     distinctness because the local ring is similarly colored/supported
  2. when target support does survive, dense extraction/top-k/area-reject
     clutter can still prevent target candidate retention
- do not relax the small-target area/span invariant as the first move; inspect
  whether fresh support should recognize a compact coherent patch with a
  strong surrounding color boundary even when the immediate support ring is
  also active

## Next Thread Mission

Fix Red1 reacquisition under the restored stride-gated loop.

Concrete first questions:

1. Why do interim strong seeds frequently become `area=0` / `span=5-6.3`
   area rejects when `targetCells=2` and `maxArea=16`?
2. Is the interim target-only / sparse mask clipping the dense component so
   the candidate geometry is measured as a long, zero-area fragment?
3. Does the cadence-full path still have enough information to rebuild a
   valid Red1 candidate, or are full frames now also failing for the same
   geometry reason?
4. Should target-only candidate geometry be measured in the local refreshed
   neighborhood differently, while still enforcing the small-target maximum?

Recommended first implementation direction:

- keep the stride-gated control loop intact
- use the new dropout `mode=... plan[...] regdbg[...]` breadcrumb from the next
  app run to separate target-only, partial, and cadence-full failures
- inspect dense candidate extraction around `strongestReject=area`
- add the minimum native/harness telemetry needed to see component extents,
  area accounting, and whether refresh-mask clipping is producing zero-area
  blobs
- fix geometry/accounting if proven, rather than relaxing the small-target
  invariant

## Guardrails

- Do not revert to `stride=1` as a correctness workaround.
- Do not stop invoking AD every analyzed frame; AR and target aging need frame
  continuity.
- Do not relax the small-target invariant broadly.
- Do not rescue large blobs by ranking.
- Preserve the top-6 non-full interim cap unless a log proves it is the direct
  cause of missed true-target reacquisition.
- Keep `regdbg[...]` and the dropout scan-plan breadcrumb until at least one
  post-fix app playback confirms restored ROI behavior.

## Useful Log Lines

Current-code fingerprints:

- `12May123435.449 ... Applying anomaly config ... stride=10`
- `12May123435.449 ... anomaly config applied ... stride=10`
- `12May123435.818 ... mode=target-only ... regdbg[scale=0.9992 ...]`
- `12May123442.398 ... frame=120 ... mode=full ... reasons=stale-high ... regdbg[scale=1.0005 ...]`

Failure fingerprints:

- no `render posted overlay` lines in the current run
- no `boxCount=1` in the current run
- many dropout lines with:
  - `examined=6`
  - `strongestReject=area`
  - `area=0`
  - `span=5.0` to `6.3`
