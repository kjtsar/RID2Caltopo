#include "anomaly_target_color_detector.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

static void set_pixel(uint8_t *buf, int stride, int x, int y,
                      uint8_t r, uint8_t g, uint8_t b) {
    uint8_t *p = buf + (size_t)y * (size_t)stride + (size_t)x * 4u;
    p[0] = r;
    p[1] = g;
    p[2] = b;
    p[3] = 255u;
}

static void fill(uint8_t *buf, int w, int h, uint8_t r, uint8_t g, uint8_t b) {
    int stride = w * 4;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            set_pixel(buf, stride, x, y, r, g, b);
        }
    }
}

static void fill_rect(uint8_t *buf, int w, int x0, int y0, int x1, int y1,
                      uint8_t r, uint8_t g, uint8_t b) {
    int stride = w * 4;
    for (int y = y0; y < y1; y++) {
        for (int x = x0; x < x1; x++) {
            set_pixel(buf, stride, x, y, r, g, b);
        }
    }
}

static void fill_green_mottle(uint8_t *buf, int w, int h) {
    int stride = w * 4;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int v = ((x * 17) ^ (y * 31) ^ ((x / 23) * 11) ^ ((y / 19) * 7)) & 63;
            uint8_t g = (uint8_t)(112 + v);
            uint8_t r = (uint8_t)(38 + (v / 3));
            uint8_t b = (uint8_t)(34 + (v / 4));
            set_pixel(buf, stride, x, y, r, g, b);
        }
    }
}

static void fill_vertical_bands(
        uint8_t *buf,
        int w,
        int h,
        const uint8_t colors[][3],
        int color_count) {
    int stride = w * 4;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int band = (x * color_count) / w;
            if (band >= color_count) band = color_count - 1;
            set_pixel(buf, stride, x, y,
                      colors[band][0], colors[band][1], colors[band][2]);
        }
    }
}

static double now_s(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + ((double)ts.tv_nsec / 1000000000.0);
}

static int compare_double(const void *left, const void *right) {
    double a = *(const double *)left;
    double b = *(const double *)right;
    return (a > b) - (a < b);
}

static void run_case(const char *name, uint8_t *rgba, int w, int h, uint32_t mask) {
    anomaly_target_color_scratch_t scratch;
    anomaly_target_color_result_t result;
    anomaly_target_color_scratch_init(&scratch);
    anomaly_target_color_result_init(&result);

    const int warmup = 20;
    const int iters = 250;
    for (int i = 0; i < warmup; i++) {
        if (!anomaly_target_color_detect_rgba(rgba, w * 4, w, h, mask, &scratch, &result)) {
            fprintf(stderr, "detect failed during warmup: %s\n", name);
            exit(2);
        }
    }

    double min_ms = 1e9;
    double max_ms = 0.0;
    double sum_ms = 0.0;
    double samples_ms[iters];
    for (int i = 0; i < iters; i++) {
        double t0 = now_s();
        if (!anomaly_target_color_detect_rgba(rgba, w * 4, w, h, mask, &scratch, &result)) {
            fprintf(stderr, "detect failed: %s\n", name);
            exit(2);
        }
        double ms = (now_s() - t0) * 1000.0;
        if (ms < min_ms) min_ms = ms;
        if (ms > max_ms) max_ms = ms;
        sum_ms += ms;
        samples_ms[i] = ms;
    }

    qsort(samples_ms, (size_t)iters, sizeof(samples_ms[0]), compare_double);
    const int p95_index = ((95 * iters + 99) / 100) - 1;
    const double p95_ms = samples_ms[p95_index];

    printf("%-30s avg_ms=%.3f min_ms=%.3f max_ms=%.3f p95_ms=%.3f sampled=%d selected=%d components=%d rois=%d\n",
           name,
           sum_ms / (double)iters,
           min_ms,
           max_ms,
           p95_ms,
           result.sampled_pixels,
           result.selected_pixel_count,
           result.candidate_component_count,
           result.roi_count);
    anomaly_target_color_scratch_cleanup(&scratch);
}

int main(void) {
    const int w = 1280;
    const int h = 720;
    uint8_t *rgba = (uint8_t *)malloc((size_t)w * (size_t)h * 4u);
    if (rgba == NULL) {
        fprintf(stderr, "failed to allocate probe frame\n");
        return 2;
    }

    fill(rgba, w, h, 86, 86, 86);
    run_case("no selected colors", rgba, w, h, 0u);

    fill(rgba, w, h, 86, 86, 86);
    run_case("gray, searching green", rgba, w, h, ANOMALY_TARGET_COLOR_GREEN);

    fill(rgba, w, h, 52, 125, 48);
    run_case("uniform green frame", rgba, w, h, ANOMALY_TARGET_COLOR_GREEN);

    fill(rgba, w, h, 52, 125, 48);
    fill_rect(rgba, w, 620, 340, 660, 380, 118, 232, 36);
    run_case("green plus lime subject", rgba, w, h, ANOMALY_TARGET_COLOR_GREEN);

    fill_green_mottle(rgba, w, h);
    run_case("mottled green frame", rgba, w, h, ANOMALY_TARGET_COLOR_GREEN);

    fill_green_mottle(rgba, w, h);
    fill_rect(rgba, w, 620, 340, 660, 380, 118, 232, 36);
    run_case("mottled green plus lime", rgba, w, h, ANOMALY_TARGET_COLOR_GREEN);

    static const uint8_t red_green[][3] = {
        {224, 32, 28},
        {28, 190, 52},
    };
    fill_vertical_bands(rgba, w, h, red_green, 2);
    run_case("broad red green background", rgba, w, h,
             ANOMALY_TARGET_COLOR_RED | ANOMALY_TARGET_COLOR_GREEN);

    fill(rgba, w, h, 86, 86, 86);
    fill_rect(rgba, w, 600, 330, 632, 370, 224, 32, 28);
    fill_rect(rgba, w, 632, 330, 664, 370, 28, 190, 52);
    run_case("compact red green subject", rgba, w, h,
             ANOMALY_TARGET_COLOR_RED | ANOMALY_TARGET_COLOR_GREEN);

    static const uint8_t red_green_blue[][3] = {
        {224, 32, 28},
        {28, 190, 52},
        {28, 72, 224},
    };
    fill_vertical_bands(rgba, w, h, red_green_blue, 3);
    run_case("broad red green blue background", rgba, w, h,
             ANOMALY_TARGET_COLOR_RED |
             ANOMALY_TARGET_COLOR_GREEN |
             ANOMALY_TARGET_COLOR_BLUE);

    fill(rgba, w, h, 60, 60, 60);
    fill_rect(rgba, w, 590, 330, 618, 370, 224, 32, 28);
    fill_rect(rgba, w, 618, 330, 646, 370, 28, 190, 52);
    fill_rect(rgba, w, 646, 330, 674, 370, 28, 72, 224);
    run_case("compact red green blue subject", rgba, w, h,
             ANOMALY_TARGET_COLOR_RED |
             ANOMALY_TARGET_COLOR_GREEN |
             ANOMALY_TARGET_COLOR_BLUE);

    free(rgba);
    return 0;
}
