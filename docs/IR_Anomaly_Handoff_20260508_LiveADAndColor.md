# IR Anomaly Handoff: Live AD Backpressure + Color Profiling Setup

## Why This Is A Good Handoff Point

Yes. This is a sensible place to hand off.

We now have:

- the threaded live AD architecture behaving stably in real flight use
- a staged backpressure policy that degrades gracefully instead of hard-freezing
- a long live-flight log showing the policy in action
- a separate color-regression seed manifest ready for the next profiling pass
- three new visible-light clips staged in `app/src/test/resources/vidcap/`

The next thread should not need to replay the whole freeze investigation.

## Current Live-Streaming Ground Truth

The original hard freeze was caused by throughput mismatch, not deadlock:

- decode/enqueue could outpace the AD worker during bursty RTMP/TCP live delivery
- MediaMTX `reader is too slow` warnings were downstream symptoms
- adding a hard cap to the AD input queue prevented the catastrophic freeze

That established the real problem:

- the live path needs bounded-latency degradation under burst load
- it cannot assume AD will always keep up frame-for-frame

## What Changed In Native Code

Main file:

- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/ffmpeg_bridge.c`

### 1. Overload transition bookkeeping was fixed

`disable_anomaly_runtime()` no longer pre-sets `anomaly_runtime_disabled`
before `reconfigure_anomaly_mode()` evaluates the transition.

That fix restored correct transition behavior for hard overload fallback:

- queue/state reset through the normal path
- generation bump
- `anomaly_paused_overload` dispatch when hard fallback actually occurs

### 2. Live AD pressure modes were added inside the AD worker

The AD worker now has staged pressure modes:

- `normal`
- `analyze-alternate`
- `bypass-alternate`
- `bypass-all`

Important design detail:

- `analyze-alternate` does not bypass the whole detector
- instead it temporarily doubles effective `frame_stride`
- that preserves per-frame registration / target tracking continuity while only
  refreshing the heavier appearance path every other frame

This reuses the detector's existing appearance-stride-skip behavior rather than
inventing a separate GMV-only side path.

### 3. Queue sizing / recovery was tuned for burst tolerance

Current queue-related constants:

- `AD_INPUT_QUEUE_INITIAL_CAPACITY = 24`
- `AD_INPUT_QUEUE_HARD_CAPACITY = 24`
- `AD_INPUT_QUEUE_DEFAULT_BACKLOG_MS = 750`
- `AD_PRESSURE_RECOVER_DEPTH = 2`

Current percentage thresholds:

- `AD_PRESSURE_ANALYZE_ALTERNATE_PCT = 50`
- `AD_PRESSURE_BYPASS_ALTERNATE_PCT = 66`
- `AD_PRESSURE_BYPASS_ALL_PCT = 80`

With a 24-frame queue, that maps to:

- `12` frames: `analyze-alternate`
- `16` frames: `bypass-alternate`
- `20` frames: `bypass-all`
- recover to `normal` when queue drains back to `2`

Rationale:

- larger queue gives more burst absorption
- keeping at least two frames before relaxing helps avoid flapping
- this is intended to reduce the chance of needless shutdown when FFmpeg gets a
  short run of cheap decodes

## Latest Flight Result

The latest long live-flight log is the key validation artifact:

- `/Users/kjt/Projects/RID2Caltopo/log.txt`

What it showed:

- the new pressure modes were exercised repeatedly
- `analyze-alternate`, `bypass-alternate`, and `bypass-all` all engaged
- the system recovered back to `normal` repeatedly
- there were no hard overload trips in that run
- there were no `ad input queue full ... disabling anomaly path` events
- playback stayed healthy enough that the operator did not notice obvious AD degradation during flight

In other words:

- the staged live policy appears to be doing useful work
- hard overload fallback is still present as a last-resort safety net
- but the system is no longer depending on that safety net for routine bursts

## What The Latest Log Suggested Quantitatively

From the current `log.txt` pass:

- `mode=analyze-alternate` appeared many times
- `mode=bypass-alternate` appeared many times
- `mode=bypass-all` appeared occasionally
- hard overload fallback did not trigger in that run

The shape of the behavior matters more than exact counts:

- queue rises under burst
- pressure mode escalates
- queue drains
- mode returns to `normal`

That is the intended steady-state behavior for live.

## Color Profiling Direction Is The Preferred Next Focus

The user explicitly prefers color-path profiling next.

That is a good next step because:

- we spent much more effort on the IR path already
- color cost is still largely unprofiled
- live stability is now better enough that profiling should be more actionable

## New Visible-Light Clips Added

The following files now exist:

- `/Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Color1.mp4`
- `/Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Color2.mp4`
- `/Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Color3.mp4`

No reviewed excerpts or manifest entries have been added for them yet.

## Color Harness Prep Already Added

To keep visible-light work separate from the black-hot regression suite, a new
seed manifest was created:

- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/regression_suite_color_manifest.json`

And the harness README was updated with color-suite guidance:

- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/README.md`

Current color manifest state:

- contains starter profiles only
- `source_clips` is empty
- `excerpts` is empty

Starter profiles:

- `visible-color-baseline`
- `visible-color-dense-gold`

This was done intentionally so the next thread can add the new color clips
cleanly without perturbing the existing IR baseline manifest.

## Recommended First Tasks For The Next Thread

### 1. Add the new color clips to the color manifest

Update:

- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/regression_suite_color_manifest.json`

Add `Color1`, `Color2`, and `Color3` under `source_clips`.

Do not mix them into the existing black-hot manifest.

### 2. Decide whether to review full clips or carve excerpts first

Likely best path:

- inspect each color clip
- identify short reviewed windows with representative detections / misses
- add those as `excerpts`

This keeps profiling targeted and comparable, just like the IR suite evolved.

### 3. Run timing-enabled harness passes on the color profiles

Use:

- `tools/anomaly_test/build_timing/anomaly_video_test`
- `tools/anomaly_test/run_regression_suite.py`

Goal:

- determine whether visible-light runtime is dominated by:
  - sampled-grid prep
  - color-outlier scoring
  - registration support work
  - overlay cost
  - or some interaction across those stages

### 4. Preserve the current live backpressure policy while profiling

Do not start retuning the live thresholds again before learning where the color
path cost really sits.

The live policy is currently in a decent place:

- stable
- burst-tolerant
- operator-visible quality seems acceptable

## Questions The Next Thread Should Answer

1. For the new color clips, what is the realtime factor and stage timing per profile?
2. Is the color path materially heavier than IR on the same registration posture?
3. Does `--pixel-step 1` explode runtime on visible-light more than expected?
4. Are there obvious cheap wins in the color path that preserve detection quality?
5. After color profiling, do the current live pressure thresholds still feel right?

## Suggested Commands For The Next Thread

Build timing harness:

```sh
cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test
cmake -B build_timing -DANOMALY_DEBUG_TIMING=ON
cmake --build build_timing
```

Run color suite once manifest entries exist:

```sh
python3 /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/run_regression_suite.py \
  --manifest /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/regression_suite_color_manifest.json \
  --binary /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/build_timing/anomaly_video_test \
  --output-dir /tmp/color_regression
```

## Files Most Relevant To Next Thread

- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/ffmpeg_bridge.c`
- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c`
- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h`
- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/README.md`
- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/run_regression_suite.py`
- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/regression_suite_manifest.json`
- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/regression_suite_color_manifest.json`
- `/Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Color1.mp4`
- `/Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Color2.mp4`
- `/Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Color3.mp4`
- `/Users/kjt/Projects/RID2Caltopo/log.txt`

## Bottom Line

The current thread got the live architecture from "can freeze under burst
load" to "degrades gracefully and stayed operational through a real flight."

That is a strong checkpoint.

The next thread should primarily:

- preserve the current live behavior
- onboard the new color clips into the harness
- gather color-path timing data before doing deeper tuning
