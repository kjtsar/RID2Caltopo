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

Results: 30 passed, 0 failed
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

### Step 1 — capture a clip

Record a short clip (10–30 seconds) from the RID2Caltopo streams screen while
flying, or save an existing RTMP recording.  MP4 or MOV work fine.

### Step 2 — extract raw RGBA frames

```sh
ffmpeg -i your_clip.mp4 \
       -vf fps=10 \
       -pix_fmt rgba \
       frames/frame_%04d.rgba
```

Also save the frame dimensions (you'll need them in the driver):
```sh
ffprobe -v error -select_streams v:0 \
        -show_entries stream=width,height \
        -of csv=p=0 your_clip.mp4
```

`fps=10` keeps the frame count manageable.  Adjust to match the original
frame rate if you need the timing to be realistic.

### Step 3 — write a video driver

Add a new source file alongside `test_anomaly.c`, for example
`test_video.c`:

```c
#include "anomaly_analysis.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// Run all frames in a directory through the detector and report detections.
// Usage: anomaly_video_test <frame_dir> <width> <height> [score_threshold]
int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <frame_dir> <width> <height> [threshold]\n", argv[0]);
        return 1;
    }
    const char *dir   = argv[1];
    int W             = atoi(argv[2]);
    int H             = atoi(argv[3]);
    float threshold   = argc > 4 ? atof(argv[4]) : ANOMALY_DEFAULT_SCORE_THRESHOLD;

    anomaly_config_t cfg = {
        .enabled           = true,
        .algorithm_mask    = ANOMALY_ALGO_THERMAL | ANOMALY_ALGO_COLOR | ANOMALY_ALGO_MOTION,
        .frame_stride      = 1,
        .score_threshold   = threshold,
        .min_area_fraction = ANOMALY_DEFAULT_MIN_AREA_FRACTION,
        .thermal_polarity  = ANOMALY_THERMAL_WHITE_HOT,
        .scan_zone         = ANOMALY_SCAN_ZONE_DEFAULT,
        .min_hits          = ANOMALY_DEFAULT_MIN_HITS,
    };

    anomaly_state_t state;
    anomaly_state_init(&state);

    size_t frame_bytes = (size_t)W * H * 4;
    uint8_t *rgba = malloc(frame_bytes);

    int frame_num = 1, detections = 0;
    char path[512];
    while (1) {
        snprintf(path, sizeof(path), "%s/frame_%04d.rgba", dir, frame_num);
        FILE *f = fopen(path, "rb");
        if (!f) break;
        if (fread(rgba, 1, frame_bytes, f) != frame_bytes) { fclose(f); break; }
        fclose(f);

        anomaly_result_t result;
        int boxes = anomaly_process_frame(&state, &cfg, rgba, W * 4, W, H, 0, &result);
        if (boxes > 0) {
            printf("frame %4d: %d box(es)", frame_num, boxes);
            for (int i = 0; i < boxes; i++) {
                const anomaly_box_t *b = &result.boxes[i];
                printf("  [cx=%.2f cy=%.2f w=%.2f h=%.2f]",
                       (b->left_norm + b->right_norm) * 0.5f,
                       (b->top_norm  + b->bottom_norm) * 0.5f,
                       b->right_norm - b->left_norm,
                       b->bottom_norm - b->top_norm);
            }
            printf("\n");
            detections++;
        }
        frame_num++;
    }

    printf("\nProcessed %d frames, %d with detections.\n", frame_num - 1, detections);
    anomaly_state_cleanup(&state);
    free(rgba);
    return 0;
}
```

Add it to `CMakeLists.txt`:
```cmake
add_executable(anomaly_video_test
    test_video.c
    ../../app/src/main/cpp/anomaly_analysis.c
)
target_include_directories(anomaly_video_test PRIVATE ../../app/src/main/cpp)
target_link_libraries(anomaly_video_test m)
```

Then run:
```sh
cmake --build build
mkdir frames
ffmpeg -i your_clip.mp4 -vf fps=10 -pix_fmt rgba frames/frame_%04d.rgba
./build/anomaly_video_test frames 1920 1080 2.8
```

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
| `score_threshold` | 1.8σ | Min Z-score to register a detection. Log-mapped from the 0–100% sensitivity slider: 0%→15σ (very quiet), 60%→~2.8σ, 100%→1.0σ |
| `min_hits` | 2 | Consecutive analyzed frames a detection must persist before a box is drawn. Eliminates single-frame false positives |
| `scan_zone` | 0.80 | Centered fraction of the frame that is scanned. Values < 1.0 exclude wide-angle lens distortion at the edges |
| `frame_stride` | 3 | Analyze every Nth frame. Higher values reduce CPU; combine with min_hits (total latency ≈ stride × min_hits × frame interval) |
| `ANOMALY_ACC_HOLD_FRAMES` | 8 | Analyzed frames a box stays visible after the detection signal disappears |
| `ANOMALY_GMV_SEARCH_RADIUS` | 20 | Block-match search radius (in motion-grid cells) for camera motion estimation |
| `ANOMALY_GMV_RESIDUAL_THRESH` | 0.05 | Max mean fit residual before declaring a scene discontinuity and wiping accumulators |
