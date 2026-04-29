// anomaly_video_test.c — Run anomaly detection on a captured MP4 and produce:
//
//   1. An annotated MP4 with detection boxes drawn on every frame
//   2. A CSV detection log with one row per visible box per frame
//
// The CSV has a blank "label" column at the right edge.  Open it in
// Numbers or Excel alongside the annotated video, scrub to each flagged
// timestamp, and fill in G (good / true-positive), B (bad / false-positive),
// or ? (unsure).  Those labels become the ground truth for regression tests.
//
// Build:  cmake --build build
// Usage:  ./build/anomaly_video_test <input.mp4> [options]
//         ./build/anomaly_video_test PowerHouse3.mp4 -p bh -t 2.8
//
// Requires ffmpeg and ffprobe on PATH.

#include "anomaly_analysis.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

// ── helpers ────────────────────────────────────────────────────────────────

static const char *algo_label(int algo) {
    switch (algo) {
        case ANOMALY_ALGO_COLOR:   return "color";
        case ANOMALY_ALGO_THERMAL: return "thermal";
        case ANOMALY_ALGO_MOTION:  return "motion";
        default:                   return "unknown";
    }
}

// Remove extension and directory prefix to get a bare basename.
static void basename_noext(const char *path, char *out, size_t out_sz) {
    const char *slash = strrchr(path, '/');
    const char *name  = slash ? slash + 1 : path;
    const char *dot   = strrchr(name, '.');
    size_t len = dot ? (size_t)(dot - name) : strlen(name);
    if (len >= out_sz) len = out_sz - 1;
    memcpy(out, name, len);
    out[len] = '\0';
}

static void usage(const char *prog) {
    fprintf(stderr,
        "Usage: %s <input.mp4> [options]\n"
        "\n"
        "Output files (written alongside the input by default):\n"
        "  -o <file.mp4>    Annotated video  (default: <input>_annotated.mp4)\n"
        "  -c <file.csv>    Detection log    (default: <input>_detections.csv)\n"
        "  --no-video       Skip annotated video (CSV only)\n"
        "\n"
        "Detector settings:\n"
        "  -t <float>       Score threshold  (default: %.1f)\n"
        "  -m <int>         Min hits to show box (default: %d)\n"
        "  -s <float>       Scan zone 0.5–1.0    (default: %.2f)\n"
        "  -a <int>         Algorithm mask 1=color 2=thermal 4=motion (default: 7)\n"
        "  -p <wh|bh>       Thermal polarity: wh=white-hot bh=black-hot (default: wh)\n"
        "  --stride <int>   Analyze every Nth frame (default: 1)\n"
        "\n"
        "Tip for IR/thermal footage: add  -p bh -a 6  (thermal + motion, black-hot)\n",
        prog,
        (double)ANOMALY_DEFAULT_SCORE_THRESHOLD,
        ANOMALY_DEFAULT_MIN_HITS,
        (double)ANOMALY_SCAN_ZONE_DEFAULT);
}

// ── main ───────────────────────────────────────────────────────────────────

int main(int argc, char **argv) {
    if (argc < 2 || !strcmp(argv[1], "-h") || !strcmp(argv[1], "--help")) {
        usage(argv[0]);
        return argc < 2 ? 1 : 0;
    }

    const char *input = argv[1];
    char output_video[1024] = "";
    char output_csv[1024]   = "";
    int  write_video = 1;

    anomaly_config_t cfg = {
        .enabled           = true,
        .algorithm_mask    = ANOMALY_ALGO_COLOR | ANOMALY_ALGO_THERMAL | ANOMALY_ALGO_MOTION,
        .frame_stride      = 1,
        .score_threshold   = ANOMALY_DEFAULT_SCORE_THRESHOLD,
        .min_area_fraction = ANOMALY_DEFAULT_MIN_AREA_FRACTION,
        .thermal_polarity  = ANOMALY_THERMAL_WHITE_HOT,
        .scan_zone         = ANOMALY_SCAN_ZONE_DEFAULT,
        .min_hits          = ANOMALY_DEFAULT_MIN_HITS,
    };

    for (int i = 2; i < argc; i++) {
        if      (!strcmp(argv[i], "-o")       && i+1 < argc) snprintf(output_video, sizeof(output_video), "%s", argv[++i]);
        else if (!strcmp(argv[i], "-c")       && i+1 < argc) snprintf(output_csv,   sizeof(output_csv),   "%s", argv[++i]);
        else if (!strcmp(argv[i], "-t")       && i+1 < argc) cfg.score_threshold   = (float)atof(argv[++i]);
        else if (!strcmp(argv[i], "-m")       && i+1 < argc) cfg.min_hits          = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-s")       && i+1 < argc) cfg.scan_zone         = (float)atof(argv[++i]);
        else if (!strcmp(argv[i], "-a")       && i+1 < argc) cfg.algorithm_mask    = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--stride") && i+1 < argc) cfg.frame_stride      = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-p")       && i+1 < argc) {
            cfg.thermal_polarity = strcmp(argv[++i], "bh") == 0
                                   ? ANOMALY_THERMAL_BLACK_HOT : ANOMALY_THERMAL_WHITE_HOT;
        }
        else if (!strcmp(argv[i], "--no-video")) write_video = 0;
        else { fprintf(stderr, "Unknown option: %s\n\n", argv[i]); usage(argv[0]); return 1; }
    }

    // ── Auto-detect dimensions and fps with ffprobe ──────────────────────
    int    W = 0, H = 0;
    double fps = 30.0, duration_s = 0.0;
    {
        char cmd[2048];
        snprintf(cmd, sizeof(cmd),
            "ffprobe -v error -select_streams v:0 "
            "-show_entries stream=width,height,r_frame_rate,duration "
            "-of csv=p=0 \"%s\" 2>/dev/null", input);
        FILE *p = popen(cmd, "r");
        if (!p) { fprintf(stderr, "Error: ffprobe failed — is ffprobe on PATH?\n"); return 1; }
        int fps_n = 30, fps_d = 1;
        fscanf(p, "%d,%d,%d/%d,%lf", &W, &H, &fps_n, &fps_d, &duration_s);
        pclose(p);
        if (fps_d > 0) fps = (double)fps_n / (double)fps_d;
    }
    if (W <= 0 || H <= 0) {
        fprintf(stderr, "Error: could not read video info from \"%s\"\n", input);
        return 1;
    }
    int total_frames = (duration_s > 0) ? (int)(duration_s * fps + 0.5) : 0;

    // ── Build default output paths in the same directory as the input ────
    char dir_prefix[512] = "";
    {
        const char *slash = strrchr(input, '/');
        if (slash) {
            size_t dlen = (size_t)(slash - input) + 1;
            if (dlen >= sizeof(dir_prefix)) dlen = sizeof(dir_prefix) - 1;
            memcpy(dir_prefix, input, dlen);
            dir_prefix[dlen] = '\0';
        }
    }
    char base[256];
    basename_noext(input, base, sizeof(base));

    if (!output_video[0])
        snprintf(output_video, sizeof(output_video), "%s%s_annotated.mp4", dir_prefix, base);
    if (!output_csv[0])
        snprintf(output_csv, sizeof(output_csv), "%s%s_detections.csv", dir_prefix, base);

    // ── Print run summary ────────────────────────────────────────────────
    fprintf(stderr, "\n");
    fprintf(stderr, "Input      : %s\n", input);
    fprintf(stderr, "Dimensions : %dx%d @ %.2f fps", W, H, fps);
    if (total_frames > 0) fprintf(stderr, "  (~%d frames, %.0fs)", total_frames, duration_s);
    fprintf(stderr, "\n");
    fprintf(stderr, "CSV        : %s\n", output_csv);
    if (write_video) fprintf(stderr, "Video      : %s\n", output_video);
    fprintf(stderr, "\n");
    fprintf(stderr, "Detector settings:\n");
    fprintf(stderr, "  threshold  = %.2f\n", (double)cfg.score_threshold);
    fprintf(stderr, "  min_hits   = %d\n",   cfg.min_hits);
    fprintf(stderr, "  scan_zone  = %.2f\n", (double)cfg.scan_zone);
    fprintf(stderr, "  algorithm  = %d (%s%s%s)\n", cfg.algorithm_mask,
        (cfg.algorithm_mask & ANOMALY_ALGO_COLOR)   ? "color "   : "",
        (cfg.algorithm_mask & ANOMALY_ALGO_THERMAL) ? "thermal " : "",
        (cfg.algorithm_mask & ANOMALY_ALGO_MOTION)  ? "motion"   : "");
    fprintf(stderr, "  polarity   = %s\n",
        cfg.thermal_polarity == ANOMALY_THERMAL_BLACK_HOT ? "black-hot" : "white-hot");
    fprintf(stderr, "  stride     = %d\n",   cfg.frame_stride);
    fprintf(stderr, "\n");

    // ── Allocate frame buffer ────────────────────────────────────────────
    size_t frame_bytes = (size_t)W * H * 4;
    uint8_t *rgba = malloc(frame_bytes);
    if (!rgba) { fprintf(stderr, "Out of memory\n"); return 1; }

    // ── Open CSV ─────────────────────────────────────────────────────────
    FILE *csv = fopen(output_csv, "w");
    if (!csv) { fprintf(stderr, "Cannot write %s\n", output_csv); return 1; }

    // Header lines document the settings so the file is self-contained.
    fprintf(csv, "# input: %s\n", input);
    fprintf(csv, "# threshold: %.2f  min_hits: %d  scan_zone: %.2f  "
                 "algo: %d  polarity: %s  stride: %d\n",
            (double)cfg.score_threshold, cfg.min_hits, (double)cfg.scan_zone,
            cfg.algorithm_mask,
            cfg.thermal_polarity == ANOMALY_THERMAL_BLACK_HOT ? "bh" : "wh",
            cfg.frame_stride);
    fprintf(csv, "# label column: G=good/true-positive  "
                 "B=bad/false-positive  ?=unsure  (leave blank to skip)\n");
    fprintf(csv, "frame,time_s,algorithm,cx_norm,cy_norm,"
                 "box_w_norm,box_h_norm,weight,label\n");

    // ── Open FFmpeg input pipe ───────────────────────────────────────────
    char in_cmd[2048];
    snprintf(in_cmd, sizeof(in_cmd),
        "ffmpeg -i \"%s\" -pix_fmt rgba -f rawvideo pipe:1 2>/dev/null", input);
    FILE *in_pipe = popen(in_cmd, "r");
    if (!in_pipe) { fprintf(stderr, "Error: ffmpeg decode pipe failed\n"); return 1; }

    // ── Open FFmpeg output pipe (annotated video) ────────────────────────
    FILE *out_pipe = NULL;
    if (write_video) {
        char out_cmd[2048];
        snprintf(out_cmd, sizeof(out_cmd),
            "ffmpeg -f rawvideo -pix_fmt rgba -video_size %dx%d -framerate %.4f "
            "-i pipe:0 -vf format=yuv420p -c:v libx264 -crf 23 -movflags +faststart "
            "-y \"%s\" 2>/dev/null",
            W, H, fps, output_video);
        out_pipe = popen(out_cmd, "w");
        if (!out_pipe) { fprintf(stderr, "Error: ffmpeg encode pipe failed\n"); return 1; }
    }

    // ── Process frames ───────────────────────────────────────────────────
    anomaly_state_t state;
    anomaly_state_init(&state);

    int    frame_num        = 0;
    int    detection_frames = 0;
    int    total_boxes      = 0;
    time_t t_start          = time(NULL);

    while (fread(rgba, 1, frame_bytes, in_pipe) == frame_bytes) {
        frame_num++;
        double time_s = (double)(frame_num - 1) / fps;

        anomaly_result_t result;
        anomaly_process_frame(&state, &cfg, rgba, W * 4, W, H, 0, &result);

        if (result.box_count > 0) {
            detection_frames++;
            for (int i = 0; i < result.box_count; i++) {
                const anomaly_box_t *b = &result.boxes[i];
                float cx = (b->left_norm  + b->right_norm)  * 0.5f;
                float cy = (b->top_norm   + b->bottom_norm) * 0.5f;
                float bw = b->right_norm  - b->left_norm;
                float bh = b->bottom_norm - b->top_norm;
                fprintf(csv, "%d,%.3f,%s,%.4f,%.4f,%.4f,%.4f,%.2f,\n",
                        frame_num, time_s, algo_label(b->algorithm),
                        (double)cx, (double)cy, (double)bw, (double)bh,
                        (double)b->weight);
                total_boxes++;
            }
        }

        // Progress line: overwrite in place.
        if (frame_num % 30 == 0 || frame_num == 1) {
            int elapsed = (int)(time(NULL) - t_start);
            if (total_frames > 0) {
                int eta = (frame_num > 0 && elapsed > 0)
                          ? (int)((double)elapsed / frame_num * (total_frames - frame_num))
                          : 0;
                fprintf(stderr, "\r  frame %5d / %d  (%.1fs)  "
                                "detections: %d frames  ETA: %ds     ",
                        frame_num, total_frames, time_s, detection_frames, eta);
            } else {
                fprintf(stderr, "\r  frame %5d  (%.1fs)  detections: %d frames   ",
                        frame_num, time_s, detection_frames);
            }
            fflush(stderr);
        }

        if (out_pipe) fwrite(rgba, 1, frame_bytes, out_pipe);
    }
    fprintf(stderr, "\n\n");

    // ── Tear down ────────────────────────────────────────────────────────
    pclose(in_pipe);
    if (out_pipe) pclose(out_pipe);
    fclose(csv);
    anomaly_state_cleanup(&state);
    free(rgba);

    // ── Summary ──────────────────────────────────────────────────────────
    int elapsed = (int)(time(NULL) - t_start);
    fprintf(stderr, "Done in %ds.\n", elapsed);
    fprintf(stderr, "  Frames processed : %d\n", frame_num);
    fprintf(stderr, "  Frames with boxes: %d (%.1f%%)\n",
            detection_frames,
            frame_num > 0 ? 100.0 * detection_frames / frame_num : 0.0);
    fprintf(stderr, "  Total box events : %d\n", total_boxes);
    fprintf(stderr, "  CSV written      : %s\n", output_csv);
    if (write_video)
        fprintf(stderr, "  Video written    : %s\n", output_video);
    fprintf(stderr, "\n");
    fprintf(stderr, "Next step: open the CSV in Numbers/Excel and watch the\n");
    fprintf(stderr, "annotated video alongside it.  For each row, fill in the\n");
    fprintf(stderr, "label column:  G = good detection,  B = false positive,\n");
    fprintf(stderr, "               ? = unsure.\n\n");

    return 0;
}
