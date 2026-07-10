# Anomaly Detection Test Harness

Unit tests for the anomaly detection algorithms used in RID2Caltopo's drone
video analysis feature.  Tests run on a standard Mac or Linux host — no
Android device or FFmpeg installation required.

## Why this exists

The detection algorithms live in a single C file
(`app/src/main/cpp/anomaly_analysis.c`) with no JNI or FFmpeg dependencies.
This harness compiles that same file directly so there is only one
implementation to maintain.  If you change the algorithm, re-run these tests
before building for Android.

## Directory layout

```
tools/anomaly_test/
├── CMakeLists.txt       — standalone build; references ../../app/src/main/cpp/
├── test_anomaly.c       — synthetic unit tests (no video files needed)
├── README.md            — this file
└── build/               — cmake output; git-ignored
```

The algorithms being tested are in:

```
app/src/main/cpp/
├── anomaly_analysis.h   — public API (config, state, result structs + function declarations)
└── anomaly_analysis.c   — implementation (all algorithms, no Android/JNI deps)
```

`ffmpeg_bridge.c` is a thin wrapper that reads the Kotlin config under its
lock and calls `anomaly_process_frame()` — it contains no algorithm logic.

Important nuance: app behavior is not determined by the C file alone. The
Android app also derives native detector settings from higher-level Kotlin
preferences (`AnomalyConfig -> NativeAnomalyConfig`). If you want harness runs
to resemble app runs, use the app-parity flags described below rather than the
raw native defaults.

## Build and run

```sh
cd tools/anomaly_test
cmake -B build
cmake --build build
./build/anomaly_test
```

Expected output:
```
Running anomaly detection unit tests...

Results: <all tests passed>, 0 failed
```

CTest is also wired up:
```sh
cd tools/anomaly_test/build && ctest
```

## Optional stage timing build

For performance investigation, the harness can compile in coarse per-stage
timing with a shipping-safe CMake flag. It is off by default.

```sh
cd tools/anomaly_test
cmake -B build_timing -DANOMALY_DEBUG_TIMING=ON
cmake --build build_timing
./build_timing/anomaly_test
```

This enables timing inside `anomaly_process_frame()` and surfaces it through:

- `anomaly_video_test` stderr
- `--summary-json` as a `stage_timing` object

Release/default harness builds remain timing-off unless you pass
`-DANOMALY_DEBUG_TIMING=ON`.

## What the synthetic tests cover

| Test | What it verifies |
|---|---|
| `test_similarity_pure_translation` | fit_similarity_2d recovers exact tx/ty for a pure pan |
| `test_similarity_pure_rotation_90` | fit_similarity_2d recovers a=0, b=1 for 90° yaw |
| `test_similarity_degenerate_n1` | n=1 point → valid=false, no crash |
| `test_similarity_degenerate_collinear` | degenerate anchor geometry → no crash |
| `test_uniform_no_detection` | uniform gray frame → zero boxes |
| `test_thermal_hotspot_detected` | bright center pixel → thermal box near center |
| `test_color_outlier_detected` | red pixel in gray scene → color outlier box |
| `test_black_hot_thermal` | dark pixel in bright scene → black-hot thermal box |
| `test_high_threshold_no_detection` | mild outlier fires at threshold=1.8 but not at 15 |
| `test_min_hits_gate` | min_hits=2 → no box on hit 1, box appears on hit 2 |
| `test_scan_zone_excludes_corner` | scan_zone=0.5 → corner outlier ignored |
| `test_motion_static_scene` | same frame twice → no motion box |
| `test_motion_moving_patch` | bright patch moves 40 px → motion box fires |
| `test_accumulator_hold_after_miss` | box persists for HOLD_FRAMES after detection disappears |
| `test_frame_stride_skips` | stride=3 → frames 1 & 2 skipped, frame 3 analyzed |

## Testing with real captured video

Real video catches things synthetic frames cannot: codec artefacts, rolling
shutter, compression noise, lighting changes, and the specific motion
profiles of each drone model.

### The video driver — `anomaly_video_test`

The driver (`anomaly_video_test.c`) takes an MP4 directly — no manual frame
extraction needed.  It produces two output files in the same directory as the
input:

| Output | Purpose |
|---|---|
| `<clip>_annotated.mp4` | Original video with detection boxes drawn on every frame |
| `<clip>_detections.csv` | One row per visible box per frame, with a blank **label** column |

Use `--app-display-output` when you want the annotated video and CSV to reflect
the app-visible publication path instead of raw detector boxes. This restores
the clean decoded frame, applies annotation cadence/ROI smoothing, redraws the
displayed boxes, and writes those displayed boxes to the CSV.

**Quick start for IR / black-hot footage:**
```sh
cd tools/anomaly_test
cmake --build build
./build/anomaly_video_test path/to/clip.mp4 --registration affine --stride 1 -p bh -a 6 -t 2.8
```

`-p bh` = black-hot polarity  
`-a 6`  = thermal + motion only (skip color outlier, which is less useful in IR)  
`-t 2.8` = app-like threshold for the default 60% sensitivity setting (higher = fewer detections)

**Quick start with stage timing enabled:**
```sh
cd tools/anomaly_test
cmake -B build_timing -DANOMALY_DEBUG_TIMING=ON
cmake --build build_timing
./build_timing/anomaly_video_test path/to/clip.mp4 --registration affine --stride 1 -p bh -a 6 -t 2.8 --summary-json /tmp/clip_summary.json --no-video
```

When timing is compiled in, the end-of-run stderr summary includes:

- `avg-total` / `max-total`: whole analyzed-frame detector time
- per-stage `avg` / `max` timings in milliseconds

The matching summary JSON includes:

- `stage_timing.compiled`
- `stage_timing.frame_count`
- `stage_timing.avg_total_ms`
- `stage_timing.max_total_ms`
- `stage_timing.stages.<stage>.avg_ms`
- `stage_timing.stages.<stage>.max_ms`

**All options:**
```
-o <file.mp4>    Annotated video  (default: <input>_annotated.mp4)
-c <file.csv>    Detection log    (default: <input>_detections.csv)
--no-video       CSV only; skip annotated video output
--app-display-output
                 Write/draw app-visible annotations after cadence and ROI smoothing

-t <float>       Score threshold  (default: 1.8 in the native struct; app default at 60% sensitivity is ~2.8)
-m <int>         Min consecutive hits before showing box (default: 2)
-s <float>       Scan zone 0.5–1.0 (default: 0.80)
-a <int>         Algorithm mask: 1=color 2=thermal 4=motion (default: 7=all)
-p <wh|bh>       Thermal polarity: wh=white-hot bh=black-hot (default: wh)
--registration <gmv|affine>
                  Camera registration backend (default: gmv)
--stride <int>   Analyze every Nth frame (default: 1)
--pixel-step <n>
                 Override appearance sampling step; `1` is the dense gold-mode
                 setting and `0` keeps the detector's Auto detail policy
--min-delta <f>  Thermal minimum absolute luma delta (default: 10.0)
```

Requires `ffmpeg` and `ffprobe` on PATH.

### Side-by-side GMV vs affine

```sh
python3 tools/anomaly_test/compare_registration_backends.py \
  path/to/clip.mp4 \
  path/to/clip.review.json \
  -p bh -a 6 -t 2.8 -m 2 -s 0.6
```

This helper runs `anomaly_video_test` twice, once with `--registration gmv`
and once with `--registration affine`, writes two detection CSVs, and then
feeds both into `review_eval.py` so you can compare the same reviewed clip
without re-entering the detector settings.

### Focused registration-starvation replay

```sh
python3 tools/anomaly_test/run_focused_registration_experiments.py \
  --output-dir tools/anomaly_test/out/focused_registration \
  --stride 1 -p bh -a 6 -t 2.8 -m 2 -s 0.8
```

This runner executes the current four-way focused matrix used for selective
refresh debugging:

- `PowerHouseTeam` `0.0s–10.0s` with `affine`
- `PowerHouseTeam` `0.0s–10.0s` with `gmv`
- `PowerHouse1` `0.0s–4.8s` with `affine`
- `PowerHouse1` `0.0s–4.8s` with `gmv`

It prints a compact summary of:

- `rescan_modes.full/partial/target_only`
- `scan_reason_counts.reg-invalid`
- dominant `registration_reason_counts`
- top-level `realtime_factor`

To run the same matrix with timing enabled:

```sh
python3 tools/anomaly_test/run_focused_registration_experiments.py \
  --binary tools/anomaly_test/build_timing/anomaly_video_test \
  --output-dir /tmp/focused_registration_timing \
  --stride 1 -p bh -a 6 -t 2.8 -m 2 -s 0.8
```

Open any generated `*_summary.json` and inspect the `stage_timing` block to
compare where runtime is going across the four focused replays.

### Scene-coverage shadow evaluation

Add `--scene-coverage-shadow-jsonl <file>` to an app-parity replay to record the
shadow scheduler mask, authoritative rescan mode, and raw detector boxes for
each analyzed frame. Evaluate that evidence with:

```sh
python3 tools/anomaly_test/analyze_scene_coverage_shadow.py \
  /tmp/red-shadow.jsonl \
  --strict \
  --output /tmp/red-shadow-report.json
```

Coverage is defined by the raw candidate center's 8x6 block. The report keeps
same-frame misses separate from misses whose block receives a later shadow
selection. The strict gate fails closed when JSONL evidence is malformed, has
no authoritative full-scan frames, has no raw full-scan candidates, or leaves
a candidate block unselected beyond its configured frame/time latency bound.
It does not enable or alter authoritative scan scheduling.

### Registration-first benchmark driver

For the current optimization pass, there is also a fixed sequential benchmark
driver that runs the agreed harness cases and prints the key comparison fields
in one place:

```sh
python3 tools/anomaly_test/run_registration_perf_benchmarks.py \
  --binary tools/anomaly_test/build_timing/anomaly_video_test \
  --output-dir /tmp/registration_perf_bench
```

The current built-in cases are:

- `PowerHouseTeam` affine at `scan_zone=0.80`
- `PowerHouseTeam` affine at `scan_zone=0.60`
- `PowerHouse1` affine at `scan_zone=0.80`
- `PowerHouse1` opening-window guardrail (`1.0s-4.0s`) at `scan_zone=0.60`

The report includes:

- realtime factor
- frame count

### Visible-color performance benchmark driver

For visible-light color-path work, there is also a fixed benchmark driver that
uses app-like color-only settings and writes one aggregate JSON report for
before/after comparisons:

```sh
python3 tools/anomaly_test/run_visible_color_perf_benchmarks.py \
  --binary tools/anomaly_test/build_timing/anomaly_video_test \
  --output-dir /tmp/visible_color_perf_bench
```

The default matrix compares:

- `visible-color-legacy-auto` — legacy coarse color-only profile
- `visible-color-app-dense-stride` — app-visible Color realtime defaults
- `visible-color-dense-gold` — dense every-frame comparison profile

Across fixed `0.0s–10.0s` windows from:

- `Color1.mp4`
- `Color2.mp4`
- `Color3.mp4`

The report includes per-case and aggregate:

- `realtime_factor`
- `stage_timing.avg_total_ms`
- `sampled_grid_prep`
- `color_scoring`
- `scan_planning`
- `refresh_mask_build`

The aggregate JSON is written to:

- `/tmp/visible_color_perf_bench/visible_color_perf_report.json`

### App-parity mode

`anomaly_video_test` now supports an opt-in app-parity mode that reproduces the
same Kotlin-side config derivation used by the Android app before
`FfmpegBridge.nativeUpdateAnomalyConfig(...)`.

Use this when you want the harness to behave like the app, not like a raw
native-detector experiment.

Example: app-default thermal replay

```sh
./build/anomaly_video_test path/to/clip.mp4 \
  --app-defaults \
  --no-video
```

Example: app-like visible-color run

```sh
./build/anomaly_video_test path/to/clip.mp4 \
  --app-defaults \
  --app-appearance color \
  --app-motion off \
  --app-saliency off
```

Useful app-parity flags:

- `--app-defaults` starts from the app's `AnomalyConfig` defaults.
- `--app-appearance <auto|thermal|color>` mirrors the app appearance selection.
- `--app-motion <on|off>` toggles the motion algorithm the way the app does.
- `--app-saliency <on|off>` toggles persistent dark patch / unified saliency.
- `--app-sensitivity <0..1>` uses the app's logarithmic threshold mapping.
- `--app-motion-sensitivity <0..1>` uses the app's motion evidence scaling.

For color appearance, app parity maps the app's legacy UI/default color
frontend selection to the native `fresh-rgba` frontend, matching
`AnomalyConfig.toNativeConfig(...)`. Pass `--color-frontend legacy` only when
you intentionally want the old native color path for comparison.

When app-parity mode is enabled, the harness prints both the high-level app
inputs and the derived native detector settings at startup and records the
parity metadata into the CSV header.

### Color realtime qualification

Use the focused Red clip qualifier when changing visible-color realtime
defaults, target-candidate limits, app-display smoothing, or Color stride
policy:

```sh
python3 tools/anomaly_test/run_color_realtime_qualification.py \
  --binary tools/anomaly_test/build/anomaly_video_test \
  --output-dir /tmp/color_realtime_qualification \
  --fail-on-regression
```

The runner replays app-visible Color defaults over:

- `Red1.mp4` with `Red1.review.json`
- `Red2.mp4` with `tools/anomaly_test/reviews/Red2.review.json`

For each clip it compares `--app-color-target-candidates 1` against `4`,
runs a second candidate-`1` timing probe to reduce short-clip cold-start noise,
writes per-run CSV/summary artifacts, and emits
`color_realtime_qualification_report.json`. Both reviewed Red clips are scored
with `review_eval.py`; Red2 also includes a geometry check for the small patio
target so the candidate-limit base case remains tied to target behavior instead
of only box counts. With `--fail-on-regression`, the command exits non-zero if
candidate limit `1` diverges from limit `4`, repeated candidate-`1` probes
diverge from each other, either reviewed Red clip drops below realtime, or Red2
no longer matches the expected
patio-target track.

The report also lists visible-color source clips that are still awaiting
review excerpts. Generate contact sheets and per-clip duration/fps/frame-count
metadata for that backlog with:

```sh
python3 tools/anomaly_test/generate_visible_color_review_prep.py \
  --output-dir /tmp/visible_color_review_prep
```

Each pending clip is marked `needs_review_excerpt` until a target-bearing
interval is selected, a review sidecar is created, and that excerpt is added to
the realtime qualification gate.

The prep report points to one contact sheet and one ffprobe metadata JSON per
pending source clip so review work can start from the same manifest entries the
qualification gate reports.

## Preparing visible-color regression clips

Visible-light profiling should live beside, not inside, the existing black-hot
suite so we can compare color-path changes without perturbing the thermal
baseline.

Seed manifest:

```sh
tools/anomaly_test/regression_suite_color_manifest.json
```

It already contains two starter profiles:

- `visible-color-baseline` — color + motion, affine registration, stride 1
- `visible-color-dense-gold` — same, but with `--pixel-step 1` for dense profiling

When new visible-light clips are added under
`app/src/test/resources/vidcap/`, the next steps are:

1. Add each clip to `source_clips` in `regression_suite_color_manifest.json`.
2. Review useful excerpts and add them under `excerpts`.
3. Run the suite with the color manifest instead of the black-hot one.

Example:

```sh
python3 tools/anomaly_test/run_regression_suite.py \
  --manifest tools/anomaly_test/regression_suite_color_manifest.json \
  --binary tools/anomaly_test/build_timing/anomaly_video_test \
  --output-dir /tmp/color_regression
```

`Red1` and `Red2` are the current reviewed visible-color correctness guardrails
in this suite. If a change touches color scoring, color candidate selection, or
selective refresh behavior, rerun the color manifest and check
`red1-opening-target-track` plus `red2-tablet-patio-target` before considering
the change safe.

For squinter-facing display checks, run the app-display stream and summarize
its temporal stability:

```sh
tools/anomaly_test/build_timing/anomaly_video_test \
  app/src/test/resources/vidcap/Red1.mp4 \
  --app-defaults --app-appearance color --app-motion on --app-saliency off \
  --app-display-output \
  --summary-json /tmp/red1_app_display_summary.json \
  -c /tmp/red1_app_display_detections.csv \
  -o /tmp/red1_app_display_annotated.mp4

python3 tools/anomaly_test/analyze_display_stability.py \
  /tmp/red1_app_display_detections.csv \
  --frame-count 154 \
  --cadence-frames 15 \
  --json-out /tmp/red1_app_display_stability.json

python3 tools/anomaly_test/review_eval.py \
  app/src/test/resources/vidcap/Red1.review.json \
  /tmp/red1_app_display_detections.csv \
  --time-window 0.25 \
  --json
```

The stability summary reports visible-frame ratio, blink gaps, short gaps
within the cadence window, and greedy per-track jump statistics. Pair it with
`review_eval.py` so a smooth but wrong ROI is still counted as a miss.

- `scan_reason_counts.reg-invalid`
- dominant registration invalid reason
- `rescan_modes.full/partial/target_only`
- `stage_timing.avg_total_ms`
- average `registration_prep`, `registration_solve`, `thermal_scoring`,
  `scan_planning`, and `refresh_mask_build`

### Stage timing interpretation

The current timing buckets are coarse pipeline stages, not line-by-line
profiling:

- `registration_prep`: motion-grid luma extraction plus registration prefilter
- `registration_solve`: GMV or affine matching / fit
- `scan_planning`: ROI-wide selective refresh planner
- `refresh_mask_build`: sampled ROI refresh-mask selection for partial/target-only scans
- `sampled_grid_prep`: sampled luma grid, integral-image setup, and color-tile statistics
- `thermal_scoring`: thermal/background-model passes and thermal candidate work
- `color_scoring`: color outlier scoring plus color support/blob post-processing
- `motion_scoring`: residual motion scoring after camera registration
- `saliency_scoring`: unified saliency / persist scoring
- `target_tracking`: track propagation, accumulator follow-up, revisit annotation
- `overlay_draw`: hot-overlay and box drawing

A few reading rules help avoid bad conclusions:

- `avg_total_ms` is the best first number for throughput. It is the coarse
  whole-frame detector time for analyzed frames.
- Stage totals are directional, not exact accounting. They may not sum exactly
  to `avg_total_ms` because some glue code and early-return paths sit outside
  the named buckets.
- `max_ms` is useful for finding spikes, allocator churn, or warmup outliers,
  but do not treat one-frame maxima as representative steady-state cost.
- A stage showing `0.00 ms` usually means that cue was disabled for the run,
  not that the code path is universally free.
- Compare runs using the same clip, stride, registration backend, and detector
  settings. Timing numbers are not portable across different footage or
  resolutions.

### Reviewing detections and labelling

1. Open `<clip>_annotated.mp4` in QuickTime (or any player).
2. Open `<clip>_detections.csv` in Numbers or Excel — the `time_s` column
   is your scrub target.
3. For each row, jump to that timestamp in the video and fill in the
   **label** column:
   - `G` — good detection / true positive (the subject is there)
   - `B` — bad detection / false positive (noise, background, irrelevant object)
   - `?` — unsure; come back to it

The CSV header lines document the exact settings used, so the file is
self-contained if you re-run with different parameters.

### Reviewed regression suite

The first-pass suite lives in
`tools/anomaly_test/regression_suite_manifest.json`.

- Full source clips stay in `app/src/test/resources/vidcap/`.
- Derived regression entries are metadata-only: each excerpt points at a full
  source clip plus a `[start_s, end_s]` review window, so we do not have to
  check in extra clipped MP4s just to score them.
- `review_status=reviewed` means the excerpt has a review JSON and should be
  scored in CI / regression runs.
- `review_status=pending_review` means the clip is preserved in the suite but
  still needs human excerpt selection and labels before it can contribute to
  quality metrics.

The manifest now defines three comparison profiles so each reviewed excerpt can
be scored against the plan's required baselines:

- `current-detector-baseline`: legacy stride-heavy comparison point
- `dense-full-scan-gold`: per-frame analysis with `--pixel-step 1`
- `redesigned-incremental`: per-frame incremental mode with Auto detail

Run the current suite with:

```sh
python3 tools/anomaly_test/run_regression_suite.py
```

That writes per-profile, per-excerpt detector CSVs plus a suite JSON/Markdown
report under `tools/anomaly_test/out/regression/`.

### Reviewed label format and scoring

The scorer accepts the existing local-playback review sidecars
(`*.review.json`) and uses `review_kind` as the semantic contract:

- `correct_detection`: target present and detector should cover it
- `missed_target`: target present but previously missed; still scored as a
  required positive target for regressions
- `false_positive`: detector should not cover this point

Optional future-proof fields:

- `track_id`: pins a point to a specific target track for latency scoring
- `scenario`: scenario/category label for aggregation
- `object_type`: e.g. `person`, `tree`, `artifact`
- `note`: freeform operator context

The scorer reports:

- reviewed true positives
- reviewed false positives
- reviewed misses
- latency to first containing box for each positive track
- harness runtime / realtime factor via `--summary-json`

### Tuning the detector

The CSV + annotated video loop is the primary tuning tool.  Common
adjustments:

| Too many false positives | Too many missed detections |
|---|---|
| Raise `-t` (threshold) | Lower `-t` |
| Raise `-m` (min hits) | Lower `-m` |
| Lower `-s` (scan zone) to trim edges | Raise `-s` |
| Remove `-a 1` (color algo) | Add more algorithm bits |

Once you've found a threshold where most labels in the CSV are `G`, that's
your operating point.  Note the settings — you'll use them when extracting
regression frames in the next step.

### Step 4 — tune and add regression tests

When you find a frame sequence where the detector behaves wrongly (false
positive, false negative, or unstable box), save the raw RGBA files
alongside the test source and add a test case that loads them.  A helper
like this keeps it concise:

```c
// Load a raw RGBA file; returns NULL if the file is missing (test is skipped).
static uint8_t *load_rgba(const char *path, int w, int h) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    uint8_t *buf = malloc((size_t)w * h * 4);
    if (fread(buf, 1, (size_t)w * h * 4, f) != (size_t)w * h * 4) {
        free(buf); fclose(f); return NULL;
    }
    fclose(f);
    return buf;
}
```

Keep the regression frames small (crop to the region of interest, or use a
low-resolution capture) so they don't bloat the repository.  A 320×240
two-frame sequence is usually enough to pin down a specific bug.

## Adding a new algorithm

1. Add a new `ANOMALY_ALGO_*` bit constant in `anomaly_analysis.h`.
2. Implement scoring inside `anomaly_process_frame()` in `anomaly_analysis.c`
   following the pattern of the thermal/color/motion passes — compute a
   best-score pixel, fill `raw_cx[ai]` / `raw_cy[ai]`, and let the shared
   accumulator logic handle temporal stabilization.
3. Expose the new algorithm in `AnomalyAlgorithm.kt` and wire `nativeMask` to
   match the new `ANOMALY_ALGO_*` bit.
4. Add a synthetic test in `test_anomaly.c` that verifies the happy path and
   the no-detection case for the new algorithm.
5. Build and run the test harness before pushing.

## Key algorithm parameters (quick reference)

| Parameter | Default | Effect |
|---|---|---|
| `score_threshold` | 1.8σ native / ~2.8 app default | Min score to register a detection. The app's 60% sensitivity slider maps to ~2.8; the standalone harness default remains the raw native value 1.8 unless you pass `-t` |
| `min_hits` | 2 | Consecutive analyzed frames a detection must persist before a box is drawn. Eliminates single-frame false positives |
| `scan_zone` | 0.80 | Centered fraction of the frame that is scanned. Values < 1.0 exclude wide-angle lens distortion at the edges |
| `frame_stride` | 3 | Analyze every Nth frame. Higher values reduce CPU; combine with min_hits (total latency ≈ stride × min_hits × frame interval) |
| `thermal_min_delta` | 10.0 luma units | Minimum absolute thermal contrast before either the warmup spatial score or the steady-state temporal score is considered |
| `ANOMALY_ACC_HOLD_FRAMES` | 8 | Analyzed frames a box stays visible after the detection signal disappears |
| `ANOMALY_GMV_SEARCH_RADIUS` | 20 | Block-match search radius (in motion-grid cells) for camera motion estimation |
| `ANOMALY_GMV_RESIDUAL_THRESH` | 0.05 | Max mean fit residual before declaring a scene discontinuity and wiping accumulators |
