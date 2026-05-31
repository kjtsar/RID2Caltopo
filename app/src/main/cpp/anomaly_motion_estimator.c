// Internal MotionEstimator sidecar implementation.
#include "anomaly_motion_estimator.h"

#include "anomaly_analysis_internal.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

static inline int normalize_sidecar_movement_mode(const anomaly_config_t *cfg) {
    if (cfg == NULL) return ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE;
    if (cfg->movement_estimator_mode == ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE) {
        return ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE;
    }
    if (cfg->movement_estimator_mode == ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_SHADOW) {
        return ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_SHADOW;
    }
    return ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE;
}

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

float anomaly_motion_estimator_texture_scale(int texture_score) {
    if (texture_score <= 8) return 0.0f;
    if (texture_score >= 24) return 1.0f;
    return (float)(texture_score - 8) / 16.0f;
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
    float residual_score = clampf((tile->residual_px - 12.0f) / 28.0f, 0.0f, 1.0f);
    float flow_px = sqrtf(tile->dx_px * tile->dx_px + tile->dy_px * tile->dy_px);
    float flow_score = clampf(flow_px / 24.0f, 0.0f, 1.0f);
    float layer_score = tile->layer_class == ANOMALY_MOVEMENT_LAYER_LOCAL_OUTLIER ? 1.0f :
        (tile->layer_class == ANOMALY_MOVEMENT_LAYER_UNSTABLE ? 0.35f : 0.0f);
    return clampf((0.45f * residual_score) + (0.35f * flow_score) +
                  (0.20f * layer_score), 0.0f, 1.0f);
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
    int mode = input != NULL ? normalize_sidecar_movement_mode(input->cfg) :
        ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE;
    movement_out->mode = mode;
    movement_out->parallax_suppression_scale = 1.0f;
    if (input == NULL || mode == ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE) {
        return;
    }

    const anomaly_motion_estimator_sidecar_ops_t *ops = input->ops;
    if (input->curr_luma == NULL || input->prev_luma == NULL ||
        input->motion_w <= 2 || input->motion_h <= 2 ||
        input->width <= 1 || input->height <= 1 || input->motion_step <= 0 ||
        ops == NULL || ops->project_cell == NULL ||
        ops->find_residual_displacement == NULL || ops->registration_valid == NULL ||
        !ops->registration_valid(input->registration)) {
        return;
    }

    int roi_mgx0 = clamp_i32(input->roi_x0 / input->motion_step, 1, input->motion_w - 2);
    int roi_mgx1 = clamp_i32((input->roi_x1 + input->motion_step - 1) / input->motion_step, 2, input->motion_w - 1);
    int roi_mgy0 = clamp_i32(input->roi_y0 / input->motion_step, 1, input->motion_h - 2);
    int roi_mgy1 = clamp_i32((input->roi_y1 + input->motion_step - 1) / input->motion_step, 2, input->motion_h - 1);
    if (roi_mgx1 <= roi_mgx0 || roi_mgy1 <= roi_mgy0) return;

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
    float prev_dx = 0.0f;
    float prev_dy = 0.0f;
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
            float flow_px = sqrtf((float)(best_dx * best_dx + best_dy * best_dy)) * (float)input->motion_step;
            residual_sum += residual_px;
            residual_sum2 += residual_px * residual_px;
            flow_sum += flow_px;
            flow_sum2 += flow_px * flow_px;
            valid_count++;

            float neighbor_delta = 0.0f;
            if (have_prev_flow) {
                float ddx = (float)best_dx - prev_dx;
                float ddy = (float)best_dy - prev_dy;
                neighbor_delta = sqrtf(ddx * ddx + ddy * ddy) * (float)input->motion_step;
            }
            prev_dx = (float)best_dx;
            prev_dy = (float)best_dy;
            have_prev_flow = true;

            int layer_class = ANOMALY_MOVEMENT_LAYER_UNKNOWN;
            if (flow_px <= (float)input->motion_step * 0.45f && residual_px <= 12.0f) {
                background++;
                layer_class = ANOMALY_MOVEMENT_LAYER_BACKGROUND;
            } else if (neighbor_delta <= (float)input->motion_step * 1.25f &&
                       flow_px <= (float)input->motion_step * 2.75f) {
                coherent_near++;
                layer_class = ANOMALY_MOVEMENT_LAYER_COHERENT_NEAR;
            } else if (residual_px >= 18.0f && flow_px >= (float)input->motion_step * 0.75f) {
                local_outlier++;
                layer_class = ANOMALY_MOVEMENT_LAYER_LOCAL_OUTLIER;
            } else {
                unstable++;
                layer_class = ANOMALY_MOVEMENT_LAYER_UNSTABLE;
            }

            int tile_idx = gy * grid_cols + gx;
            if (tile_idx >= 0 && tile_idx < ANOMALY_MOVEMENT_TILE_COUNT) {
                anomaly_debug_movement_tile_t *tile = &movement_out->tiles[tile_idx];
                tile->valid = true;
                tile->center_x_norm = clamp01f((float)(mx * input->motion_step) / (float)input->width);
                tile->center_y_norm = clamp01f((float)(my * input->motion_step) / (float)input->height);
                tile->dx_px = (float)best_dx * (float)input->motion_step;
                tile->dy_px = (float)best_dy * (float)input->motion_step;
                tile->residual_px = residual_px;
                tile->confidence = clampf(
                    1.0f - (residual_px / 64.0f) + fminf(flow_px / ((float)input->motion_step * 8.0f), 0.25f),
                    0.0f,
                    1.0f);
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
    if (movement_out->parallax_load > 0.25f && movement_out->local_outlier_load < 0.20f) {
        float t = (movement_out->parallax_load - 0.25f) / 0.45f;
        movement_out->parallax_suppression_scale = 1.0f - (0.45f * clampf(t, 0.0f, 1.0f));
    } else {
        movement_out->parallax_suppression_scale = 1.0f;
    }
}
