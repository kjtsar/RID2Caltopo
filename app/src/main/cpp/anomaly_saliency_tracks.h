#ifndef ANOMALY_SALIENCY_TRACKS_H
#define ANOMALY_SALIENCY_TRACKS_H

#include "anomaly_analysis.h"

void anomaly_saliency_update_aux_track(
        anomaly_state_t *state,
        int              track_idx,
        float            raw_cx,
        float            raw_cy,
        float            gate,
        float            alpha);

bool anomaly_saliency_find_local_support(
        const anomaly_state_t *state,
        int                    track_idx,
        const float           *patch_selection_map,
        int                    sg_w,
        int                    sg_h,
        int                    roi_x0,
        int                    roi_y0,
        int                    sample_step,
        int                    width,
        int                    height,
        float                 *x_norm_out,
        float                 *y_norm_out,
        float                 *score_out);

void anomaly_saliency_choose_best_dark_patch(
        const float *selection_map,
        int          sg_w,
        int          sg_h,
        int          roi_x0,
        int          roi_y0,
        int          sample_step,
        float       *best_score_out,
        int         *best_x_out,
        int         *best_y_out);

int anomaly_saliency_classify_display_algorithm(
        const float *saliency_spatial_map,
        const float *saliency_color_map,
        const float *saliency_motion_map,
        const float *saliency_registration_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        int          sx,
        int          sy,
        bool         bg_valid,
        bool         black_hot,
        float        thermal_min_delta,
        float        delta_mean,
        float        delta_norm);

#endif // ANOMALY_SALIENCY_TRACKS_H
