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

**Quick start for IR / black-hot footage:**
```sh
cd tools/anomaly_test
cmake --build build
./build/anomaly_video_test path/to/clip.mp4 --registration affine --stride 1 -p bh -a 6 -t 2.8
```

`-p bh` = black-hot polarity  
`-a 6`  = thermal + motion only (skip color outlier, which is less useful in IR)  
`-t 2.8` = app-like threshold for the default 60% sensitivity setting (higher = fewer detections)

**All options:**
```
-o <file.mp4>    Annotated video  (default: <input>_annotated.mp4)
-c <file.csv>    Detection log    (default: <input>_detections.csv)
--no-video       CSV only; skip annotated video output

-t <float>       Score threshold  (default: 1.8 in the native struct; app default at 60% sensitivity is ~2.8)
-m <int>         Min consecutive hits before showing box (default: 2)
-s <float>       Scan zone 0.5–1.0 (default: 0.60)
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
| `scan_zone` | 0.60 | Centered fraction of the frame that is scanned. Values < 1.0 exclude wide-angle lens distortion at the edges |
| `frame_stride` | 3 | Analyze every Nth frame. Higher values reduce CPU; combine with min_hits (total latency ≈ stride × min_hits × frame interval) |
| `thermal_min_delta` | 10.0 luma units | Minimum absolute thermal contrast before either the warmup spatial score or the steady-state temporal score is considered |
| `ANOMALY_ACC_HOLD_FRAMES` | 8 | Analyzed frames a box stays visible after the detection signal disappears |
| `ANOMALY_GMV_SEARCH_RADIUS` | 20 | Block-match search radius (in motion-grid cells) for camera motion estimation |
| `ANOMALY_GMV_RESIDUAL_THRESH` | 0.05 | Max mean fit residual before declaring a scene discontinuity and wiping accumulators |
