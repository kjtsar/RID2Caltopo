// Internal MotionEstimator sidecar implementation.
#include "anomaly_motion_estimator.h"

#include "anomaly_analysis_internal.h"
#include "anomaly_registration_model.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

bool anomaly_motion_estimator_find_residual_displacement(
        const uint8_t *curr_luma,
        const uint8_t *prev_luma,
        int            motion_w,
        int            motion_h,
        int            mx,
        int            my,
        int            pred_x,
        int            pred_y,
        int            patch_half,
        int            search_radius,
        int           *best_dx_out,
        int           *best_dy_out,
        int           *best_sad_out) {
    if (curr_luma == NULL || prev_luma == NULL) return false;
    if (mx < patch_half || mx >= motion_w - patch_half ||
        my < patch_half || my >= motion_h - patch_half) {
        return false;
    }

    long best_sad = 0x7FFFFFFFL;
    long second_best_sad = 0x7FFFFFFFL;
    int best_dx = 0;
    int best_dy = 0;
    int best_dist2 = 0x7FFFFFFF;
    bool found = false;

    for (int dy = -search_radius; dy <= search_radius; dy++) {
        for (int dx = -search_radius; dx <= search_radius; dx++) {
            int cx = pred_x + dx;
            int cy = pred_y + dy;
            if (cx < patch_half || cx >= motion_w - patch_half ||
                cy < patch_half || cy >= motion_h - patch_half) {
                continue;
            }
            long sad = 0;
            for (int ky = -patch_half; ky <= patch_half; ky++) {
                for (int kx = -patch_half; kx <= patch_half; kx++) {
                    int curr_v = curr_luma[(my + ky) * motion_w + (mx + kx)];
                    int prev_v = prev_luma[(cy + ky) * motion_w + (cx + kx)];
                    int d = curr_v - prev_v;
                    sad += d < 0 ? -d : d;
                }
            }
            int dist2 = dx * dx + dy * dy;
            if (sad < best_sad || (sad == best_sad && dist2 < best_dist2)) {
                second_best_sad = best_sad;
                best_sad = sad;
                best_dx = dx;
                best_dy = dy;
                best_dist2 = dist2;
                found = true;
            } else if (sad < second_best_sad) {
                second_best_sad = sad;
            }
        }
    }

    if (!found) return false;
    if (second_best_sad < 0x7FFFFFFFL) {
        long sad_margin = second_best_sad - best_sad;
        if (sad_margin < 12) return false;
        if ((double)second_best_sad < (double)best_sad * 1.08) return false;
    }
    if (abs(best_dx) >= search_radius || abs(best_dy) >= search_radius) {
        return false;
    }
    if (best_dx_out != NULL) *best_dx_out = best_dx;
    if (best_dy_out != NULL) *best_dy_out = best_dy;
    if (best_sad_out != NULL) *best_sad_out = best_sad >= 0x7FFFFFFFL ? -1 : (int)best_sad;
    return true;
}

bool anomaly_motion_estimator_project_cell(
        const anomaly_motion_estimator_registration_t *registration,
        int                                            width,
        int                                            height,
        int                                            motion_step,
        int                                            motion_w,
        int                                            motion_h,
        int                                            mx,
        int                                            my,
        int                                           *px_idx_out,
        int                                           *py_idx_out) {
    if (registration == NULL || width <= 1 || height <= 1 || motion_step <= 0 ||
        motion_w <= 0 || motion_h <= 0) {
        return false;
    }
    float fw = (float)(width - 1);
    float fh = (float)(height - 1);
    float cx_n = (float)(mx * motion_step) / fw;
    float cy_n = (float)(my * motion_step) / fh;
    float px_n = 0.0f;
    float py_n = 0.0f;
    anomaly_registration_apply_point(registration, cx_n, cy_n, &px_n, &py_n);
    int px_idx = (int)(px_n * fw / (float)motion_step + 0.5f);
    int py_idx = (int)(py_n * fh / (float)motion_step + 0.5f);
    if (px_idx < 0 || px_idx >= motion_w || py_idx < 0 || py_idx >= motion_h) return false;
    if (px_idx_out != NULL) *px_idx_out = px_idx;
    if (py_idx_out != NULL) *py_idx_out = py_idx;
    return true;
}

static bool default_registration_valid(
        const anomaly_motion_estimator_registration_t *registration) {
    return anomaly_registration_model_valid(registration);
}

static const anomaly_motion_estimator_sidecar_ops_t default_sidecar_ops = {
    .project_cell = anomaly_motion_estimator_project_cell,
    .find_residual_displacement = anomaly_motion_estimator_find_residual_displacement,
    .registration_valid = default_registration_valid,
};

const anomaly_motion_estimator_sidecar_ops_t *anomaly_motion_estimator_default_sidecar_ops(void) {
    return &default_sidecar_ops;
}

int anomaly_motion_estimator_normalize_movement_mode(const anomaly_config_t *cfg) {
    if (cfg == NULL) return ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE;
    if (cfg->movement_estimator_mode == ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE) {
        return ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE;
    }
    if (cfg->movement_estimator_mode == ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_SHADOW) {
        return ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_SHADOW;
    }
    return ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE;
}

bool anomaly_motion_estimator_sidecar_input_ready(
        const anomaly_motion_estimator_sidecar_input_t *input) {
    if (input == NULL) return false;
    if (anomaly_motion_estimator_normalize_movement_mode(input->cfg) ==
        ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE) {
        return false;
    }
    const anomaly_motion_estimator_sidecar_ops_t *ops = input->ops;
    if (input->curr_luma == NULL || input->prev_luma == NULL ||
        input->motion_w <= 2 || input->motion_h <= 2 ||
        input->width <= 1 || input->height <= 1 || input->motion_step <= 0 ||
        ops == NULL || ops->project_cell == NULL ||
        ops->find_residual_displacement == NULL || ops->registration_valid == NULL ||
        !ops->registration_valid(input->registration)) {
        return false;
    }
    return true;
}

bool anomaly_motion_estimator_sidecar_grid_bounds(
        const anomaly_motion_estimator_sidecar_input_t *input,
        anomaly_motion_sidecar_grid_bounds_t          *out) {
    if (out != NULL) {
        memset(out, 0, sizeof(*out));
    }
    if (input == NULL || out == NULL ||
        input->motion_step <= 0 ||
        input->motion_w <= 2 ||
        input->motion_h <= 2) {
        return false;
    }

    int roi_mgx0 = clamp_i32(input->roi_x0 / input->motion_step, 1, input->motion_w - 2);
    int roi_mgx1 = clamp_i32(
            (input->roi_x1 + input->motion_step - 1) / input->motion_step,
            2,
            input->motion_w - 1);
    int roi_mgy0 = clamp_i32(input->roi_y0 / input->motion_step, 1, input->motion_h - 2);
    int roi_mgy1 = clamp_i32(
            (input->roi_y1 + input->motion_step - 1) / input->motion_step,
            2,
            input->motion_h - 1);
    if (roi_mgx1 <= roi_mgx0 || roi_mgy1 <= roi_mgy0) {
        return false;
    }
    out->x0 = roi_mgx0;
    out->x1 = roi_mgx1;
    out->y0 = roi_mgy0;
    out->y1 = roi_mgy1;
    return true;
}

bool anomaly_motion_estimator_sidecar_tile_center_norm(
        int    mx,
        int    my,
        int    motion_step,
        int    width,
        int    height,
        float *x_norm_out,
        float *y_norm_out) {
    if (x_norm_out == NULL || y_norm_out == NULL ||
        motion_step <= 0 || width <= 0 || height <= 0) {
        return false;
    }
    *x_norm_out = clamp01f((float)(mx * motion_step) / (float)width);
    *y_norm_out = clamp01f((float)(my * motion_step) / (float)height);
    return true;
}

int anomaly_motion_estimator_sidecar_classify_layer(
        float flow_px,
        float residual_px,
        float neighbor_delta_px,
        int   motion_step) {
    if (motion_step <= 0) return ANOMALY_MOVEMENT_LAYER_UNKNOWN;
    if (flow_px <= (float)motion_step * 0.45f && residual_px <= 12.0f) {
        return ANOMALY_MOVEMENT_LAYER_BACKGROUND;
    }
    if (neighbor_delta_px <= (float)motion_step * 1.25f &&
        flow_px <= (float)motion_step * 2.75f) {
        return ANOMALY_MOVEMENT_LAYER_COHERENT_NEAR;
    }
    if (residual_px >= 18.0f && flow_px >= (float)motion_step * 0.75f) {
        return ANOMALY_MOVEMENT_LAYER_LOCAL_OUTLIER;
    }
    return ANOMALY_MOVEMENT_LAYER_UNSTABLE;
}

float anomaly_motion_estimator_sidecar_parallax_suppression_scale(
        float parallax_load,
        float local_outlier_load) {
    if (parallax_load > 0.25f && local_outlier_load < 0.20f) {
        float t = (parallax_load - 0.25f) / 0.45f;
        return 1.0f - (0.45f * clampf(t, 0.0f, 1.0f));
    }
    return 1.0f;
}

float anomaly_motion_estimator_sidecar_tile_confidence(
        float residual_px,
        float flow_px,
        int   motion_step) {
    if (motion_step <= 0) return 0.0f;
    return clampf(
            1.0f - (residual_px / 64.0f) +
                fminf(flow_px / ((float)motion_step * 8.0f), 0.25f),
            0.0f,
            1.0f);
}

bool anomaly_motion_estimator_sidecar_tile_displacement_px(
        int    dx,
        int    dy,
        int    motion_step,
        float *dx_px_out,
        float *dy_px_out) {
    if (dx_px_out == NULL || dy_px_out == NULL || motion_step <= 0) {
        return false;
    }
    *dx_px_out = (float)dx * (float)motion_step;
    *dy_px_out = (float)dy * (float)motion_step;
    return true;
}

float anomaly_motion_estimator_sidecar_displacement_magnitude_px(
        int dx,
        int dy,
        int motion_step) {
    if (motion_step <= 0) return 0.0f;
    return sqrtf((float)(dx * dx + dy * dy)) * (float)motion_step;
}

float anomaly_motion_estimator_texture_scale(int texture_score) {
    if (texture_score <= 8) return 0.0f;
    if (texture_score >= 24) return 1.0f;
    return (float)(texture_score - 8) / 16.0f;
}

float anomaly_motion_estimator_appearance_zoom_motion_scale(float registration_scale) {
    float zoom_delta = fabsf(registration_scale - 1.0f);
    float zoom_motion_scale = 1.0f;
    if (zoom_delta > 0.004f) {
        zoom_motion_scale = 1.0f - ((zoom_delta - 0.004f) / 0.014f);
        if (zoom_motion_scale < 0.0f) zoom_motion_scale = 0.0f;
    }
    return zoom_motion_scale;
}

float anomaly_motion_estimator_appearance_broad_motion_scale(float global_motion_load) {
    float broad_motion_scale = 1.0f;
    if (global_motion_load > 0.12f) {
        broad_motion_scale = 1.0f - ((global_motion_load - 0.12f) / 0.18f);
        if (broad_motion_scale < 0.20f) broad_motion_scale = 0.20f;
    }
    return broad_motion_scale;
}

float anomaly_motion_estimator_appearance_global_motion_load(
        int strong_global_samples,
        int global_count) {
    return global_count > 0
        ? ((float)strong_global_samples / (float)global_count)
        : 0.0f;
}

void anomaly_motion_estimator_appearance_global_stats(
        double                                    global_sum,
        double                                    global_sum2,
        int                                       global_count,
        int                                       motion_step,
        anomaly_motion_appearance_global_stats_t *out) {
    if (out == NULL) return;

    out->mean = 0.0f;
    out->std = (float)motion_step * 0.5f;
    out->motion_floor_px = (float)motion_step;
    if (global_count <= 0) return;

    out->mean = (float)(global_sum / (double)global_count);
    double variance = (global_sum2 / (double)global_count) -
        ((double)out->mean * (double)out->mean);
    out->std = sqrtf((float)fmax(variance, 0.04));
    if (out->std < (float)motion_step * 0.35f) {
        out->std = (float)motion_step * 0.35f;
    }
    out->motion_floor_px = out->mean + (0.75f * out->std);
    if (out->motion_floor_px < (float)motion_step * 0.85f) {
        out->motion_floor_px = (float)motion_step * 0.85f;
    }
}

void anomaly_motion_estimator_sync_appearance_scorer_state(
        anomaly_motion_appearance_scorer_state_t *state,
        float                                    *persist,
        int                                       persist_w,
        int                                       persist_h) {
    if (state == NULL) return;
    state->persist = persist;
    state->persist_w = persist_w;
    state->persist_h = persist_h;
}

void anomaly_motion_estimator_populate_appearance_debug_summary(
        anomaly_debug_motion_t *debug,
        bool                    scene_discontinuity,
        int                     sample_step,
        int                     motion_step,
        int                     global_count,
        int                     motion_candidate_count,
        float                   global_motion_mean,
        float                   global_motion_std,
        float                   zoom_motion_scale,
        float                   broad_motion_scale,
        float                   global_motion_load) {
    if (debug == NULL) return;
    debug->valid = global_count > 0 || motion_candidate_count > 0;
    debug->scene_discontinuity = scene_discontinuity;
    debug->sample_step = sample_step;
    debug->motion_step = motion_step;
    debug->sample_count = global_count;
    debug->residual_mean = global_motion_mean;
    debug->residual_std = global_motion_std;
    debug->zoom_motion_scale = zoom_motion_scale;
    debug->broad_motion_scale = broad_motion_scale;
    debug->global_motion_load = global_motion_load;
}

void anomaly_motion_estimator_populate_appearance_debug_result(
        anomaly_debug_motion_t          *debug,
        float                            raw_score,
        int                              raw_x,
        int                              raw_y,
        float                            frame_w,
        float                            frame_h,
        float                            winner_component_area_frac,
        float                            winner_component_span_frac,
        float                            winner_component_fill_ratio,
        float                            zoom_motion_scale,
        float                            broad_motion_scale,
        float                            global_motion_load,
        float                            winner_texture_scale,
        float                            winner_structure_scale,
        float                            winner_support_scale,
        float                            winner_persistence_scale,
        const anomaly_debug_candidate_t *top_candidates,
        int                              top_candidate_count) {
    if (debug == NULL) return;
    debug->raw_candidate_valid = raw_score >= 0.0f;
    debug->raw_score = raw_score;
    debug->raw_x_norm = (raw_x > 0 || raw_y > 0) && frame_w != 0.0f
        ? ((float)raw_x / frame_w)
        : 0.0f;
    debug->raw_y_norm = (raw_x > 0 || raw_y > 0) && frame_h != 0.0f
        ? ((float)raw_y / frame_h)
        : 0.0f;
    debug->winner_component_area_frac = winner_component_area_frac;
    debug->winner_component_span_frac = winner_component_span_frac;
    debug->winner_component_fill_ratio = winner_component_fill_ratio;
    debug->zoom_motion_scale = zoom_motion_scale;
    debug->broad_motion_scale = broad_motion_scale;
    debug->global_motion_load = global_motion_load;
    debug->winner_texture_scale = winner_texture_scale;
    debug->winner_structure_scale = winner_structure_scale;
    debug->winner_support_scale = winner_support_scale;
    debug->winner_persistence_scale = winner_persistence_scale;

    int clamped_count = top_candidate_count;
    if (clamped_count < 0) clamped_count = 0;
    if (clamped_count > ANOMALY_DEBUG_TOP_CANDIDATES) {
        clamped_count = ANOMALY_DEBUG_TOP_CANDIDATES;
    }
    debug->top_candidate_count = clamped_count;
    if (top_candidates == NULL) return;
    for (int i = 0; i < clamped_count; i++) {
        debug->top_candidates[i] = top_candidates[i];
    }
}

float anomaly_motion_estimator_structure_scale(
        const uint8_t *luma,
        int            w,
        int            h,
        int            x,
        int            y) {
    if (luma == NULL || x <= 1 || x >= w - 2 || y <= 1 || y >= h - 2) {
        return 0.0f;
    }

    float sum_gxx = 0.0f;
    float sum_gyy = 0.0f;
    float sum_gxy = 0.0f;
    for (int ky = -1; ky <= 1; ky++) {
        for (int kx = -1; kx <= 1; kx++) {
            int sx = x + kx;
            int sy = y + ky;
            float gx = (float)luma[sy * w + (sx + 1)] - (float)luma[sy * w + (sx - 1)];
            float gy = (float)luma[(sy + 1) * w + sx] - (float)luma[(sy - 1) * w + sx];
            sum_gxx += gx * gx;
            sum_gyy += gy * gy;
            sum_gxy += gx * gy;
        }
    }

    float tr = sum_gxx + sum_gyy;
    if (tr < 1e-3f) return 0.0f;
    float det_term = (sum_gxx - sum_gyy) * (sum_gxx - sum_gyy) + 4.0f * sum_gxy * sum_gxy;
    float root = sqrtf(fmaxf(det_term, 0.0f));
    float minor = 0.5f * (tr - root);
    float corner_ratio = minor / tr;
    if (corner_ratio <= 0.03f) return 0.0f;
    if (corner_ratio >= 0.16f) return 1.0f;
    return (corner_ratio - 0.03f) / 0.13f;
}

anomaly_motion_movement_snapshot_t anomaly_motion_estimator_make_movement_snapshot(
        const anomaly_debug_movement_t *movement) {
    anomaly_motion_movement_snapshot_t snapshot;
    memset(&snapshot, 0, sizeof(snapshot));
    snapshot.movement = movement;
    snapshot.suppression_scale = 1.0f;
    if (movement == NULL || !movement->valid) {
        return snapshot;
    }
    snapshot.valid = true;
    snapshot.sample_count = movement->sample_count;
    snapshot.tile_cols = movement->tile_cols;
    snapshot.tile_rows = movement->tile_rows;
    snapshot.confidence = movement->confidence;
    snapshot.parallax_load = movement->parallax_load;
    snapshot.local_outlier_load = movement->local_outlier_load;
    snapshot.suppression_scale = movement->parallax_suppression_scale;
    return snapshot;
}

bool anomaly_motion_estimator_query_snapshot_at_norm(
        const anomaly_motion_movement_snapshot_t *snapshot,
        float                                    x_norm,
        float                                    y_norm,
        anomaly_debug_movement_tile_t           *tile_out) {
    if (snapshot == NULL || tile_out == NULL || !snapshot->valid ||
        snapshot->movement == NULL || snapshot->sample_count <= 0 ||
        snapshot->tile_cols <= 0 || snapshot->tile_rows <= 0) {
        return false;
    }
    const anomaly_debug_movement_t *movement = snapshot->movement;
    float best_dist2 = 1.0e9f;
    int best_idx = -1;
    for (int i = 0; i < ANOMALY_MOVEMENT_TILE_COUNT; i++) {
        const anomaly_debug_movement_tile_t *tile = &movement->tiles[i];
        if (!tile->valid) continue;
        float dx = tile->center_x_norm - x_norm;
        float dy = tile->center_y_norm - y_norm;
        float d2 = dx * dx + dy * dy;
        if (d2 < best_dist2) {
            best_dist2 = d2;
            best_idx = i;
        }
    }
    if (best_idx < 0) return false;

    float col_span = snapshot->tile_cols > 0 ? 1.0f / (float)snapshot->tile_cols : 1.0f;
    float row_span = snapshot->tile_rows > 0 ? 1.0f / (float)snapshot->tile_rows : 1.0f;
    float max_dist = fmaxf(col_span, row_span) * 1.75f;
    if (best_dist2 > max_dist * max_dist) return false;
    *tile_out = movement->tiles[best_idx];
    return true;
}

float anomaly_motion_estimator_tile_independent_score(
        const anomaly_debug_movement_tile_t *tile) {
    if (tile == NULL || !tile->valid) return 0.0f;
    float residual_score = anomaly_motion_estimator_tile_residual_independent_score(tile);
    float flow_px = anomaly_motion_estimator_tile_flow_magnitude_px(tile);
    float flow_score = clampf(flow_px / 24.0f, 0.0f, 1.0f);
    float layer_score = tile->layer_class == ANOMALY_MOVEMENT_LAYER_LOCAL_OUTLIER ? 1.0f :
        (tile->layer_class == ANOMALY_MOVEMENT_LAYER_UNSTABLE ? 0.35f : 0.0f);
    return clampf((0.45f * residual_score) + (0.35f * flow_score) +
                  (0.20f * layer_score), 0.0f, 1.0f);
}

float anomaly_motion_estimator_tile_residual_independent_score(
        const anomaly_debug_movement_tile_t *tile) {
    if (tile == NULL || !tile->valid) return 0.0f;
    return clampf((tile->residual_px - 12.0f) / 28.0f, 0.0f, 1.0f);
}

float anomaly_motion_estimator_tile_flow_magnitude_px(
        const anomaly_debug_movement_tile_t *tile) {
    if (tile == NULL || !tile->valid) return 0.0f;
    return sqrtf(tile->dx_px * tile->dx_px + tile->dy_px * tile->dy_px);
}

bool anomaly_motion_estimator_tile_is_parallax_like(
        const anomaly_debug_movement_tile_t *tile) {
    if (tile == NULL || !tile->valid) return false;
    return tile->layer_class == ANOMALY_MOVEMENT_LAYER_BACKGROUND ||
           tile->layer_class == ANOMALY_MOVEMENT_LAYER_COHERENT_NEAR;
}

bool anomaly_motion_estimator_tile_is_independent(
        const anomaly_debug_movement_tile_t *tile,
        float                                independent_score) {
    if (tile == NULL || !tile->valid) return false;
    return tile->layer_class == ANOMALY_MOVEMENT_LAYER_LOCAL_OUTLIER &&
           independent_score >= 0.50f;
}

float anomaly_motion_estimator_nearest_candidate_support_norm(
        const float *support,
        const int   *support_x,
        const int   *support_y,
        int          count,
        int          frame_w,
        int          frame_h,
        float        x_norm,
        float        y_norm,
        float        max_dist_norm) {
    if (support == NULL || support_x == NULL || support_y == NULL ||
        count <= 0 || frame_w <= 0 || frame_h <= 0) {
        return -1.0f;
    }
    float best = -1.0f;
    float fw = fmaxf((float)frame_w, 1.0f);
    float fh = fmaxf((float)frame_h, 1.0f);
    float max_dist2 = max_dist_norm * max_dist_norm;
    for (int i = 0; i < count; i++) {
        if (support[i] <= 0.0f) continue;
        float cx = (float)support_x[i] / fw;
        float cy = (float)support_y[i] / fh;
        float dx = cx - x_norm;
        float dy = cy - y_norm;
        if ((dx * dx + dy * dy) > max_dist2) continue;
        if (support[i] > best) best = support[i];
    }
    return best;
}

void anomaly_motion_estimator_stamp_support(
        float *saliency_motion_map,
        float *saliency_registration_map,
        int    sg_w,
        int    sg_h,
        int    sg_x,
        int    sg_y,
        float  support,
        float  registration_scale) {
    if (saliency_motion_map == NULL || sg_w <= 0 || sg_h <= 0 || support <= 0.0f) return;
    for (int oy = -1; oy <= 1; oy++) {
        int sy = sg_y + oy;
        if (sy < 0 || sy >= sg_h) continue;
        for (int ox = -1; ox <= 1; ox++) {
            int sx = sg_x + ox;
            if (sx < 0 || sx >= sg_w) continue;
            float scale = (ox == 0 && oy == 0) ? 1.0f : 0.55f;
            float stamped = support * scale;
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (stamped > saliency_motion_map[idx]) saliency_motion_map[idx] = stamped;
            if (saliency_registration_map != NULL && registration_scale < saliency_registration_map[idx]) {
                saliency_registration_map[idx] = registration_scale;
            }
        }
    }
}

void anomaly_motion_estimator_init_appearance_scorer_output(
        anomaly_motion_appearance_scorer_output_t *out) {
    if (out == NULL) return;
    memset(out, 0, sizeof(*out));
    out->zoom_motion_scale = 1.0f;
    out->broad_motion_scale = 1.0f;
}

void anomaly_motion_estimator_init_appearance_scorer_input(
        anomaly_motion_appearance_scorer_input_t              *input,
        anomaly_motion_appearance_scorer_state_t              *state,
        const anomaly_motion_appearance_scorer_input_args_t   *args) {
    if (state != NULL) {
        memset(state, 0, sizeof(*state));
    }
    if (input == NULL) return;
    memset(input, 0, sizeof(*input));
    input->state = state;
    if (args == NULL) return;

    anomaly_motion_estimator_sync_appearance_scorer_state(
            state,
            args->persist,
            args->persist_w,
            args->persist_h);

    input->cfg = args->cfg;
    input->registration = args->registration;
    input->curr_luma = args->curr_luma;
    input->prev_luma = args->prev_luma;
    input->prev_luma_width = args->prev_luma_width;
    input->prev_luma_height = args->prev_luma_height;
    input->width = args->width;
    input->height = args->height;
    input->motion_w = args->motion_w;
    input->motion_h = args->motion_h;
    input->motion_step = args->motion_step;
    input->motion_count = args->motion_count;
    input->roi_x0 = args->roi_x0;
    input->roi_x1 = args->roi_x1;
    input->roi_y0 = args->roi_y0;
    input->roi_y1 = args->roi_y1;
    input->anomaly_detection_active = args->anomaly_detection_active;
    input->scene_discontinuity = args->scene_discontinuity;
    input->use_motion_tolerance =
            args->cfg != NULL &&
            (args->cfg->algorithm_mask & ANOMALY_ALGO_MOTION_TOLERANCE) != 0;
    input->use_stable_motion =
            args->cfg != NULL &&
            (args->cfg->algorithm_mask & ANOMALY_ALGO_MOTION) != 0 &&
            !input->use_motion_tolerance;
    input->motion_evidence_scale = args->motion_evidence_scale;
    input->saliency_motion_map = args->saliency_motion_map;
    input->saliency_registration_map = args->saliency_registration_map;
    input->sg_w = args->sg_w;
    input->sg_h = args->sg_h;
    input->sample_step = args->sample_step;
    input->proposal_count = args->proposal_count;
    input->proposals = args->proposals;
}

bool anomaly_motion_estimator_appearance_scorer_ready(
        const anomaly_motion_appearance_scorer_input_t *input) {
    if (input == NULL || input->cfg == NULL) return false;
    if (!input->anomaly_detection_active) return false;
    if ((input->cfg->algorithm_mask &
         (ANOMALY_ALGO_MOTION | ANOMALY_ALGO_MOTION_TOLERANCE | ANOMALY_ALGO_PERSIST)) == 0) {
        return false;
    }
    if (input->curr_luma == NULL || input->prev_luma == NULL) return false;
    if (input->prev_luma_width != input->motion_w ||
        input->prev_luma_height != input->motion_h) {
        return false;
    }
    if (input->scene_discontinuity) return false;
    return true;
}

bool anomaly_motion_estimator_appearance_grid_bounds(
        const anomaly_motion_appearance_scorer_input_t *input,
        anomaly_motion_appearance_grid_bounds_t        *out) {
    if (out != NULL) {
        memset(out, 0, sizeof(*out));
    }
    if (input == NULL || out == NULL ||
        input->motion_step <= 0 ||
        input->motion_w <= 0 ||
        input->motion_h <= 0) {
        return false;
    }

    int roi_mgx0 = input->roi_x0 / input->motion_step;
    int roi_mgx1 = (input->roi_x1 + input->motion_step - 1) / input->motion_step;
    int roi_mgy0 = input->roi_y0 / input->motion_step;
    int roi_mgy1 = (input->roi_y1 + input->motion_step - 1) / input->motion_step;
    roi_mgx0 = roi_mgx0 < 0 ? 0 : roi_mgx0;
    roi_mgx1 = roi_mgx1 > input->motion_w ? input->motion_w : roi_mgx1;
    roi_mgy0 = roi_mgy0 < 0 ? 0 : roi_mgy0;
    roi_mgy1 = roi_mgy1 > input->motion_h ? input->motion_h : roi_mgy1;

    out->x0 = roi_mgx0;
    out->x1 = roi_mgx1;
    out->y0 = roi_mgy0;
    out->y1 = roi_mgy1;
    return true;
}

bool anomaly_motion_estimator_appearance_score_is_winner_eligible(
        const anomaly_motion_appearance_score_t *score) {
    return score != NULL && score->valid && score->score > 0.0f;
}

int anomaly_motion_estimator_build_appearance_proposals_from_candidates(
        const anomaly_motion_candidate_t              *candidates,
        int                                            count,
        anomaly_motion_appearance_proposal_t          *out,
        int                                            out_capacity) {
    if (candidates == NULL || out == NULL || count <= 0 || out_capacity <= 0) {
        return 0;
    }
    int limit = count;
    if (limit > out_capacity) limit = out_capacity;
    if (limit > ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS) {
        limit = ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS;
    }
    for (int i = 0; i < limit; i++) {
        out[i].sg_x = candidates[i].sg_x;
        out[i].sg_y = candidates[i].sg_y;
        out[i].pixel_x = candidates[i].pixel_x;
        out[i].pixel_y = candidates[i].pixel_y;
        out[i].proposal_score = candidates[i].proposal_score;
        out[i].thermal_score = candidates[i].thermal_score;
        out[i].color_score = candidates[i].color_score;
    }
    return limit;
}

void anomaly_motion_estimator_mirror_appearance_support_output(
        const anomaly_motion_appearance_proposal_t       *proposals,
        int                                               proposal_count,
        const float                                      *support,
        const int                                        *support_x,
        const int                                        *support_y,
        float                                             global_motion_mean,
        float                                             global_motion_std,
        float                                             global_motion_load,
        float                                             zoom_motion_scale,
        float                                             broad_motion_scale,
        anomaly_motion_appearance_scorer_output_t        *out) {
    anomaly_motion_estimator_init_appearance_scorer_output(out);
    if (out == NULL) return;

    out->global_motion_mean = global_motion_mean;
    out->global_motion_std = global_motion_std;
    out->global_motion_load = global_motion_load;
    out->zoom_motion_scale = zoom_motion_scale;
    out->broad_motion_scale = broad_motion_scale;

    if (proposals == NULL || support == NULL || proposal_count <= 0) {
        return;
    }
    int limit = proposal_count;
    if (limit > ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS) {
        limit = ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS;
    }
    out->score_count = limit;
    out->valid = limit > 0;

    for (int i = 0; i < limit; i++) {
        anomaly_motion_appearance_score_t *score = &out->scores[i];
        score->valid = support[i] > 0.0f;
        score->score = support[i];
        if (support_x != NULL && support_y != NULL &&
            (support_x[i] != 0 || support_y[i] != 0)) {
            score->pixel_x = support_x[i];
            score->pixel_y = support_y[i];
        } else {
            score->pixel_x = proposals[i].pixel_x;
            score->pixel_y = proposals[i].pixel_y;
        }
        if (anomaly_motion_estimator_appearance_score_is_winner_eligible(score) &&
            (!anomaly_motion_estimator_appearance_score_is_winner_eligible(&out->winner) ||
             score->score > out->winner.score)) {
            out->winner = *score;
        }
    }
}

void anomaly_motion_estimator_estimate_sidecar(
        const anomaly_motion_estimator_sidecar_input_t *input,
        anomaly_debug_movement_t                      *movement_out) {
    if (movement_out == NULL) return;
    memset(movement_out, 0, sizeof(*movement_out));
    int mode = input != NULL ? anomaly_motion_estimator_normalize_movement_mode(input->cfg) :
        ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE;
    movement_out->mode = mode;
    movement_out->parallax_suppression_scale = 1.0f;
    if (input == NULL || mode == ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE) {
        return;
    }

    if (!anomaly_motion_estimator_sidecar_input_ready(input)) {
        return;
    }
    const anomaly_motion_estimator_sidecar_ops_t *ops = input->ops;

    anomaly_motion_sidecar_grid_bounds_t bounds;
    if (!anomaly_motion_estimator_sidecar_grid_bounds(input, &bounds)) {
        return;
    }
    int roi_mgx0 = bounds.x0;
    int roi_mgx1 = bounds.x1;
    int roi_mgy0 = bounds.y0;
    int roi_mgy1 = bounds.y1;

    const int grid_cols = ANOMALY_MOVEMENT_GRID_COLS;
    const int grid_rows = ANOMALY_MOVEMENT_GRID_ROWS;
    const int disp_patch_half = 1;
    const int disp_search_radius = 2;
    float residual_sum = 0.0f;
    float residual_sum2 = 0.0f;
    float flow_sum = 0.0f;
    float flow_sum2 = 0.0f;
    int valid_count = 0;
    int coherent_near = 0;
    int unstable = 0;
    int local_outlier = 0;
    int background = 0;
    int prev_dx = 0;
    int prev_dy = 0;
    bool have_prev_flow = false;

    for (int gy = 0; gy < grid_rows; gy++) {
        int my = roi_mgy0 + ((roi_mgy1 - roi_mgy0) * (gy * 2 + 1)) / (grid_rows * 2);
        my = clamp_i32(my, 1, input->motion_h - 2);
        for (int gx = 0; gx < grid_cols; gx++) {
            int mx = roi_mgx0 + ((roi_mgx1 - roi_mgx0) * (gx * 2 + 1)) / (grid_cols * 2);
            mx = clamp_i32(mx, 1, input->motion_w - 2);
            int px_idx = 0;
            int py_idx = 0;
            if (!ops->project_cell(
                    input->registration,
                    input->width,
                    input->height,
                    input->motion_step,
                    input->motion_w,
                    input->motion_h,
                    mx,
                    my,
                    &px_idx,
                    &py_idx)) {
                unstable++;
                continue;
            }
            int best_dx = 0;
            int best_dy = 0;
            int best_sad = 0;
            if (!ops->find_residual_displacement(
                    input->curr_luma, input->prev_luma, input->motion_w, input->motion_h,
                    mx, my, px_idx, py_idx,
                    disp_patch_half, disp_search_radius,
                    &best_dx, &best_dy, &best_sad)) {
                unstable++;
                continue;
            }
            float residual_px = (float)abs(
                (int)input->curr_luma[my * input->motion_w + mx] -
                (int)input->prev_luma[py_idx * input->motion_w + px_idx]);
            float flow_px =
                anomaly_motion_estimator_sidecar_displacement_magnitude_px(
                        best_dx,
                        best_dy,
                        input->motion_step);
            residual_sum += residual_px;
            residual_sum2 += residual_px * residual_px;
            flow_sum += flow_px;
            flow_sum2 += flow_px * flow_px;
            valid_count++;

            float neighbor_delta = 0.0f;
            if (have_prev_flow) {
                neighbor_delta =
                    anomaly_motion_estimator_sidecar_displacement_magnitude_px(
                            best_dx - prev_dx,
                            best_dy - prev_dy,
                            input->motion_step);
            }
            prev_dx = best_dx;
            prev_dy = best_dy;
            have_prev_flow = true;

            int layer_class = ANOMALY_MOVEMENT_LAYER_UNKNOWN;
            layer_class = anomaly_motion_estimator_sidecar_classify_layer(
                    flow_px,
                    residual_px,
                    neighbor_delta,
                    input->motion_step);
            if (layer_class == ANOMALY_MOVEMENT_LAYER_BACKGROUND) {
                background++;
            } else if (layer_class == ANOMALY_MOVEMENT_LAYER_COHERENT_NEAR) {
                coherent_near++;
            } else if (layer_class == ANOMALY_MOVEMENT_LAYER_LOCAL_OUTLIER) {
                local_outlier++;
            } else {
                unstable++;
            }

            int tile_idx = gy * grid_cols + gx;
            if (tile_idx >= 0 && tile_idx < ANOMALY_MOVEMENT_TILE_COUNT) {
                anomaly_debug_movement_tile_t *tile = &movement_out->tiles[tile_idx];
                tile->valid = true;
                anomaly_motion_estimator_sidecar_tile_center_norm(
                        mx,
                        my,
                        input->motion_step,
                        input->width,
                        input->height,
                        &tile->center_x_norm,
                        &tile->center_y_norm);
                anomaly_motion_estimator_sidecar_tile_displacement_px(
                        best_dx,
                        best_dy,
                        input->motion_step,
                        &tile->dx_px,
                        &tile->dy_px);
                tile->residual_px = residual_px;
                tile->confidence = anomaly_motion_estimator_sidecar_tile_confidence(
                        residual_px,
                        flow_px,
                        input->motion_step);
                tile->layer_class = layer_class;
            }
        }
    }

    movement_out->valid = valid_count > 0;
    movement_out->sample_count = valid_count;
    movement_out->tile_cols = grid_cols;
    movement_out->tile_rows = grid_rows;
    movement_out->background_count = background;
    movement_out->coherent_near_count = coherent_near;
    movement_out->unstable_count = unstable;
    movement_out->local_outlier_count = local_outlier;
    if (valid_count <= 0) return;

    float inv_count = 1.0f / (float)valid_count;
    movement_out->residual_mean_px = residual_sum * inv_count;
    float residual_var = residual_sum2 * inv_count -
        movement_out->residual_mean_px * movement_out->residual_mean_px;
    movement_out->residual_std_px = sqrtf(fmaxf(residual_var, 0.0f));
    movement_out->local_flow_mean_px = flow_sum * inv_count;
    float flow_var = flow_sum2 * inv_count -
        movement_out->local_flow_mean_px * movement_out->local_flow_mean_px;
    movement_out->local_flow_std_px = sqrtf(fmaxf(flow_var, 0.0f));
    movement_out->background_fraction = (float)background * inv_count;
    movement_out->coherent_near_fraction = (float)coherent_near * inv_count;
    movement_out->unstable_fraction = (float)unstable * inv_count;
    movement_out->local_outlier_fraction = (float)local_outlier * inv_count;
    movement_out->parallax_load = clampf(
        movement_out->coherent_near_fraction + (0.5f * movement_out->unstable_fraction),
        0.0f,
        1.0f);
    movement_out->local_outlier_load = clampf(movement_out->local_outlier_fraction, 0.0f, 1.0f);
    movement_out->confidence = clampf(
        movement_out->background_fraction + movement_out->coherent_near_fraction +
            (0.5f * movement_out->local_outlier_fraction),
        0.0f,
        1.0f);
    movement_out->parallax_suppression_scale =
        anomaly_motion_estimator_sidecar_parallax_suppression_scale(
                movement_out->parallax_load,
                movement_out->local_outlier_load);
}
