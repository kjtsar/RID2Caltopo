#ifndef ANOMALY_REGISTRATION_MODEL_H
#define ANOMALY_REGISTRATION_MODEL_H

#include "anomaly_analysis.h"

#include <math.h>
#include <string.h>

typedef struct anomaly_registration_model_t {
    int mode;
    float affine[6];  // prev = A(curr): [m00 m01 m02; m10 m11 m12]
    similarity_2d_t similarity;
    bool scene_discontinuity;
    bool debug_valid;
    int sample_step;
    int motion_step;
    int anchor_count;
    int tracked_match_count;
    anomaly_registration_invalid_reason_t invalid_reason;
    float fit_det;
    float fit_min_scale;
    float fit_max_scale;
    float fit_anchor_residual_std;
    float fit_anchor_residual_max;
    float fit_motion_dx_std;
    float fit_motion_dy_std;
    float fit_quadrant_residual_spread;
    anomaly_debug_gmv_anchor_t anchors[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
} anomaly_registration_model_t;

typedef struct {
    float m00;
    float m01;
    float m02;
    float m10;
    float m11;
    float m12;
    bool valid;
} anomaly_inverse_affine_t;

static inline int anomaly_registration_normalize_mode(const anomaly_config_t *cfg) {
    if (cfg != NULL && cfg->registration_mode == ANOMALY_REGISTRATION_AFFINE) {
        return ANOMALY_REGISTRATION_AFFINE;
    }
    return ANOMALY_REGISTRATION_GMV;
}

static inline anomaly_registration_model_t anomaly_registration_model_make(
        int mode,
        int sample_step,
        int motion_step) {
    anomaly_registration_model_t model;
    memset(&model, 0, sizeof(model));
    model.mode = mode;
    model.sample_step = sample_step;
    model.motion_step = motion_step;
    model.affine[0] = 1.0f;
    model.affine[4] = 1.0f;
    model.similarity.a = 1.0f;
    model.similarity.valid = false;
    model.invalid_reason = ANOMALY_REG_INVALID_REASON_NONE;
    return model;
}

static inline bool anomaly_registration_model_valid(const anomaly_registration_model_t *model) {
    return model != NULL && model->similarity.valid;
}

static inline float anomaly_registration_model_scale(const anomaly_registration_model_t *model) {
    if (model == NULL) return 1.0f;
    return sqrtf(model->similarity.a * model->similarity.a +
                 model->similarity.b * model->similarity.b);
}

static inline void anomaly_registration_apply_point(
        const anomaly_registration_model_t *model,
        float x,
        float y,
        float *out_x,
        float *out_y) {
    if (out_x == NULL || out_y == NULL) return;
    if (model == NULL) {
        *out_x = x;
        *out_y = y;
        return;
    }
    *out_x = model->affine[0] * x + model->affine[1] * y + model->affine[2];
    *out_y = model->affine[3] * x + model->affine[4] * y + model->affine[5];
}

static inline bool anomaly_registration_invert_point(
        const anomaly_registration_model_t *model,
        float x,
        float y,
        float *out_x,
        float *out_y) {
    if (model == NULL || out_x == NULL || out_y == NULL) return false;
    float det = model->affine[0] * model->affine[4] - model->affine[1] * model->affine[3];
    if (fabsf(det) < 1e-6f) return false;
    float dx = x - model->affine[2];
    float dy = y - model->affine[5];
    *out_x = ( model->affine[4] * dx - model->affine[1] * dy) / det;
    *out_y = (-model->affine[3] * dx + model->affine[0] * dy) / det;
    return true;
}

static inline anomaly_inverse_affine_t anomaly_registration_inverse_affine(
        const anomaly_registration_model_t *model) {
    anomaly_inverse_affine_t inv;
    memset(&inv, 0, sizeof(inv));
    if (model == NULL) return inv;
    float det = model->affine[0] * model->affine[4] - model->affine[1] * model->affine[3];
    if (fabsf(det) < 1e-6f) return inv;
    float inv_det = 1.0f / det;
    inv.m00 =  model->affine[4] * inv_det;
    inv.m01 = -model->affine[1] * inv_det;
    inv.m02 = (model->affine[1] * model->affine[5] - model->affine[4] * model->affine[2]) * inv_det;
    inv.m10 = -model->affine[3] * inv_det;
    inv.m11 =  model->affine[0] * inv_det;
    inv.m12 = (model->affine[3] * model->affine[2] - model->affine[0] * model->affine[5]) * inv_det;
    inv.valid = true;
    return inv;
}

static inline bool anomaly_registration_invert_point_fast(
        const anomaly_inverse_affine_t *inv,
        float                           x,
        float                           y,
        float                          *out_x,
        float                          *out_y) {
    if (inv == NULL || !inv->valid || out_x == NULL || out_y == NULL) return false;
    *out_x = inv->m00 * x + inv->m01 * y + inv->m02;
    *out_y = inv->m10 * x + inv->m11 * y + inv->m12;
    return true;
}

static inline float anomaly_registration_max_corner_displacement(
        const anomaly_registration_model_t *model,
        int                                 width,
        int                                 height) {
    if (model == NULL || width <= 1 || height <= 1) return 0.0f;
    static const float points[5][2] = {
        {0.0f, 0.0f},
        {1.0f, 0.0f},
        {0.0f, 1.0f},
        {1.0f, 1.0f},
        {0.5f, 0.5f},
    };
    float max_disp = 0.0f;
    for (int i = 0; i < 5; i++) {
        float x1 = 0.0f;
        float y1 = 0.0f;
        anomaly_registration_apply_point(model, points[i][0], points[i][1], &x1, &y1);
        float dx = x1 - points[i][0];
        float dy = y1 - points[i][1];
        float disp = sqrtf(dx * dx + dy * dy);
        if (disp > max_disp) max_disp = disp;
    }
    return max_disp;
}

static inline bool anomaly_registration_motion_exceeds_search(
        const anomaly_registration_model_t *model,
        int                                 width,
        int                                 height,
        float                               fraction) {
    if (model == NULL || width <= 1 || height <= 1 || model->motion_step <= 0) return false;
    float fw = (float)(width - 1);
    float fh = (float)(height - 1);
    float search_dx = ((float)(ANOMALY_GMV_SEARCH_RADIUS * model->motion_step)) / fw;
    float search_dy = ((float)(ANOMALY_GMV_SEARCH_RADIUS * model->motion_step)) / fh;
    float search_limit = sqrtf(search_dx * search_dx + search_dy * search_dy) * fraction;
    return anomaly_registration_max_corner_displacement(model, width, height) > search_limit;
}

#endif // ANOMALY_REGISTRATION_MODEL_H
