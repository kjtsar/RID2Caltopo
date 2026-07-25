#include "r2c_anomaly_apple.h"

#include "anomaly_detector.h"

#include <math.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

struct R2CAnomalyRuntime {
    anomaly_detector_runtime_t detector;
    uint8_t *rgba;
    size_t rgba_capacity;
    bool show_hot_overlay;
    int thermal_polarity;
};

static int r2c_clamp_i32(int value, int minimum, int maximum) {
    if (value < minimum) return minimum;
    if (value > maximum) return maximum;
    return value;
}

static double r2c_rgba_luma(const uint8_t *pixel) {
    return (0.2126 * (double)pixel[0])
        + (0.7152 * (double)pixel[1])
        + (0.0722 * (double)pixel[2]);
}

static bool r2c_hot_overlay_geometry(
        const uint8_t *rgba,
        int width,
        int height,
        int thermal_polarity,
        float *center_x,
        float *center_y,
        float *radius,
        float *stroke) {
    if (rgba == NULL || width <= 0 || height <= 0) return false;
    double sum_luma = 0;
    double hottest_luma = 0;
    int hottest_x = 0;
    int hottest_y = 0;
    bool black_hot = thermal_polarity == ANOMALY_THERMAL_BLACK_HOT;
    bool first = true;
    for (int y = 0; y < height; y++) {
        const uint8_t *row = rgba + ((size_t)y * (size_t)width * 4u);
        for (int x = 0; x < width; x++) {
            double luma = r2c_rgba_luma(row + (x * 4));
            sum_luma += luma;
            if (first || (black_hot && luma < hottest_luma) || (!black_hot && luma > hottest_luma)) {
                hottest_luma = luma;
                hottest_x = x;
                hottest_y = y;
                first = false;
            }
        }
    }
    if (first) return false;
    double mean_luma = sum_luma / (double)(width * height);
    if (black_hot ? hottest_luma >= mean_luma : hottest_luma <= mean_luma) return false;

    int min_dim = width < height ? width : height;
    int vicinity = r2c_clamp_i32((int)lround((double)min_dim * 0.028), 10, 24);
    int hot_left = hottest_x;
    int hot_right = hottest_x;
    int hot_top = hottest_y;
    int hot_bottom = hottest_y;
    int hot_count = 0;
    for (int y = hottest_y - vicinity; y <= hottest_y + vicinity; y++) {
        if (y < 0 || y >= height) continue;
        const uint8_t *row = rgba + ((size_t)y * (size_t)width * 4u);
        for (int x = hottest_x - vicinity; x <= hottest_x + vicinity; x++) {
            if (x < 0 || x >= width) continue;
            int dx = x - hottest_x;
            int dy = y - hottest_y;
            if ((dx * dx) + (dy * dy) > (vicinity * vicinity)) continue;
            double luma = r2c_rgba_luma(row + (x * 4));
            if (black_hot ? luma >= mean_luma : luma <= mean_luma) continue;
            hot_count++;
            if (x < hot_left) hot_left = x;
            if (x > hot_right) hot_right = x;
            if (y < hot_top) hot_top = y;
            if (y > hot_bottom) hot_bottom = y;
        }
    }
    int center_x_pixels = (hot_left + hot_right) / 2;
    int center_y_pixels = (hot_top + hot_bottom) / 2;
    int half_width = (hot_right - hot_left + 1) / 2;
    int half_height = (hot_bottom - hot_top + 1) / 2;
    int content_radius = (int)ceil(sqrt((double)((half_width * half_width) + (half_height * half_height))));
    int padding = r2c_clamp_i32((int)lround((double)min_dim * 0.008), 4, 8);
    int radius_pixels = r2c_clamp_i32(content_radius + padding, 8, min_dim / 5);
    int stroke_pixels = r2c_clamp_i32(2 + (hot_count / 24), 2, 5);
    *center_x = (float)center_x_pixels / (float)width;
    *center_y = (float)center_y_pixels / (float)height;
    *radius = (float)radius_pixels / (float)min_dim;
    *stroke = (float)stroke_pixels / (float)min_dim;
    return true;
}

R2CAnomalyRuntime *R2CAnomalyCreate(int32_t algorithm_mask, float frame_rate_fps) {
    R2CAnomalyRuntime *runtime = calloc(1, sizeof(*runtime));
    if (runtime == NULL) return NULL;
    anomaly_detector_runtime_init(&runtime->detector, algorithm_mask, frame_rate_fps);
    return runtime;
}

void R2CAnomalyDestroy(R2CAnomalyRuntime *runtime) {
    if (runtime == NULL) return;
    anomaly_detector_runtime_cleanup(&runtime->detector);
    free(runtime->rgba);
    free(runtime);
}

int32_t R2CAnomalyApplyConfiguration(
        R2CAnomalyRuntime *runtime,
        const R2CAnomalyConfiguration *configuration) {
    if (runtime == NULL || configuration == NULL) return -1;
    anomaly_detector_config_t config =
            anomaly_detector_config_make_realtime_default(configuration->algorithm_mask, 30.0f);
    config.enabled = configuration->enabled != 0;
    config.show_hot_overlay = configuration->show_hot_overlay != 0;
    config.show_candidate_blobs = configuration->show_candidate_blobs != 0;
    config.algorithm_mask = configuration->algorithm_mask;
    config.registration_mode = configuration->registration_mode;
    config.movement_estimator_mode = configuration->movement_estimator_mode;
    config.stride_mode = configuration->stride_mode;
    config.frame_stride = configuration->frame_stride > 0 ? configuration->frame_stride : 1;
    config.adaptive_min_stride_frames = configuration->adaptive_min_stride_frames > 0
            ? configuration->adaptive_min_stride_frames : 2;
    config.adaptive_max_stride_seconds = configuration->adaptive_max_stride_seconds;
    config.adaptive_max_stride_frames = (int)(30.0f * configuration->adaptive_max_stride_seconds);
    if (config.adaptive_max_stride_frames < config.adaptive_min_stride_frames) {
        config.adaptive_max_stride_frames = config.adaptive_min_stride_frames;
    }
    config.pixel_step = configuration->pixel_step;
    config.score_threshold = configuration->score_threshold;
    config.motion_evidence_scale = configuration->motion_evidence_scale;
    config.min_area_fraction = configuration->min_area_fraction;
    config.thermal_polarity = configuration->thermal_polarity;
    config.scan_zone = configuration->scan_zone;
    config.min_hits = configuration->min_hits;
    config.thermal_min_delta = configuration->thermal_min_delta;
    config.small_target_screen_fraction = configuration->small_target_screen_fraction;
    config.color_frontend_mode = configuration->color_frontend_mode;
    config.color_target_candidate_limit = configuration->color_target_candidate_limit;
    config.target_color_family_mask = configuration->target_color_family_mask;
    runtime->show_hot_overlay = config.show_hot_overlay;
    runtime->thermal_polarity = config.thermal_polarity;
    return (int32_t)anomaly_detector_runtime_apply_config(
            &runtime->detector, &config, config.frame_stride);
}

int32_t R2CAnomalyProcessBGRA(
        R2CAnomalyRuntime *runtime,
        const uint8_t *bgra,
        int32_t bytes_per_row,
        int32_t width,
        int32_t height,
        int64_t timestamp_us,
        R2CAnomalyFrameResult *result_out) {
    if (runtime == NULL || bgra == NULL || result_out == NULL ||
        width <= 0 || height <= 0 || bytes_per_row < width * 4) return -1;

    size_t rgba_size = (size_t)width * (size_t)height * 4u;
    if (rgba_size > runtime->rgba_capacity) {
        uint8_t *replacement = realloc(runtime->rgba, rgba_size);
        if (replacement == NULL) return -2;
        runtime->rgba = replacement;
        runtime->rgba_capacity = rgba_size;
    }

    for (int32_t y = 0; y < height; y++) {
        const uint8_t *source = bgra + (size_t)y * (size_t)bytes_per_row;
        uint8_t *destination = runtime->rgba + (size_t)y * (size_t)width * 4u;
        for (int32_t x = 0; x < width; x++) {
            destination[x * 4] = source[x * 4 + 2];
            destination[x * 4 + 1] = source[x * 4 + 1];
            destination[x * 4 + 2] = source[x * 4];
            destination[x * 4 + 3] = source[x * 4 + 3];
        }
    }

    float hot_center_x = 0;
    float hot_center_y = 0;
    float hot_radius = 0;
    float hot_stroke = 0;
    bool hot_overlay_valid = runtime->show_hot_overlay && r2c_hot_overlay_geometry(
            runtime->rgba,
            width,
            height,
            runtime->thermal_polarity,
            &hot_center_x,
            &hot_center_y,
            &hot_radius,
            &hot_stroke);

    anomaly_frame_input_t frame = {
        .rgba = runtime->rgba,
        .rgba_stride = width * 4,
        .width = width,
        .height = height,
        .source_timestamp_us = timestamp_us,
        .frame_format = ANOMALY_FRAME_FORMAT_RGBA8888,
    };
    anomaly_detector_runtime_process_result_t result =
            anomaly_detector_runtime_process_frame_result(&runtime->detector, &frame);
    memset(result_out, 0, sizeof(*result_out));
    result_out->frame_ordinal = result.frame_ordinal;
    result_out->raw_box_count = result.raw_box_count;
    result_out->stable_box_count = result.stable_box_count;
    result_out->hot_overlay_valid = hot_overlay_valid ? 1 : 0;
    result_out->hot_center_x = hot_center_x;
    result_out->hot_center_y = hot_center_y;
    result_out->hot_radius = hot_radius;
    result_out->hot_stroke = hot_stroke;
    int count = result.output.annotations.box_count;
    if (count > R2C_ANOMALY_MAX_BOXES) count = R2C_ANOMALY_MAX_BOXES;
    result_out->annotation_count = count;
    for (int index = 0; index < count; index++) {
        const anomaly_box_t *source = &result.output.annotations.boxes[index];
        result_out->boxes[index] = (R2CAnomalyBox) {
            .left = source->left_norm,
            .top = source->top_norm,
            .right = source->right_norm,
            .bottom = source->bottom_norm,
            .weight = source->weight,
            .red = source->r,
            .green = source->g,
            .blue = source->b,
            .draw_crosshair = source->draw_crosshair,
            .algorithm = source->algorithm,
        };
    }
    return 0;
}

int32_t R2CAnomalyFrameResultCopyBox(
        const R2CAnomalyFrameResult *result,
        int32_t index,
        R2CAnomalyBox *box_out) {
    if (result == NULL || box_out == NULL || index < 0 ||
        index >= result->annotation_count || index >= R2C_ANOMALY_MAX_BOXES) return -1;
    *box_out = result->boxes[index];
    return 0;
}
