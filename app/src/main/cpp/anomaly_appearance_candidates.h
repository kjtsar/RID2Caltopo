#ifndef ANOMALY_APPEARANCE_CANDIDATES_H
#define ANOMALY_APPEARANCE_CANDIDATES_H

#include <stdbool.h>

#define ANOMALY_APPEARANCE_MOTION_CANDIDATE_MAX 4
#define ANOMALY_APPEARANCE_MOTION_CANDIDATE_NMS_RADIUS 2

typedef struct {
    int sg_x;
    int sg_y;
    int pixel_x;
    int pixel_y;
    float proposal_score;
    float thermal_score;
    float color_score;
} anomaly_motion_candidate_t;

typedef struct {
    anomaly_motion_candidate_t candidate;
    float retention_rank;
    bool retention_rank_valid;
    float area;
    float span;
    float fill;
    float center_share;
    float quality;
    float peak_delta;
    float mean_delta;
    int min_x;
    int min_y;
    int max_x;
    int max_y;
} anomaly_thermal_blob_candidate_t;

typedef struct {
    bool valid;
    bool inserted;
    bool replaced_existing_by_nms;
    bool rejected_by_nms;
    bool rejected_by_cap;
    bool target_tail_dropped_by_cap;
    int candidate_count_before;
    int insert_rank;
    int pre_cap_rank;
    int nms_conflict_rank;
    int nms_conflict_sample_x;
    int nms_conflict_sample_y;
} anomaly_thermal_blob_insert_report_t;

typedef struct {
    anomaly_motion_candidate_t candidate;
    float retention_rank;
    bool retention_rank_valid;
    float color_uniqueness_rank;
    float hist_rarity_score;
    float area;
    float span;
    float fill;
    float center_share;
    float quality;
    float peak_support;
    float mean_support;
    float isolation_score;
    float ring_fraction;
    float support_mass;
    int min_x;
    int min_y;
    int max_x;
    int max_y;
} anomaly_color_blob_candidate_t;

typedef struct {
    bool valid;
    bool inserted;
    bool replaced_existing_by_nms;
    bool rejected_by_nms;
    bool rejected_by_cap;
    bool target_tail_dropped_by_cap;
    int candidate_count_before;
    int insert_rank;
    int pre_cap_rank;
    int nms_conflict_rank;
    int nms_conflict_sample_x;
    int nms_conflict_sample_y;
} anomaly_color_blob_insert_report_t;

void anomaly_appearance_collect_motion_candidates(
        const float *thermal_map,
        const float *color_map,
        int          sg_w,
        int          sg_h,
        int          roi_x0,
        int          roi_y0,
        int          sample_step,
        anomaly_motion_candidate_t *out_candidates,
        int         *out_count);

int anomaly_thermal_blob_candidate_compare_rank(
        const anomaly_thermal_blob_candidate_t *lhs,
        const anomaly_thermal_blob_candidate_t *rhs);

int anomaly_appearance_find_thermal_blob_candidate_rank(
        const anomaly_thermal_blob_candidate_t *top,
        int top_count,
        int sg_x,
        int sg_y);

void anomaly_appearance_insert_thermal_blob_candidate(
        anomaly_thermal_blob_candidate_t *top,
        int *top_count,
        const anomaly_thermal_blob_candidate_t *candidate,
        int max_candidates,
        int nms_radius,
        int target_rank_before,
        bool candidate_is_target,
        anomaly_thermal_blob_insert_report_t *out_report);

float anomaly_color_blob_candidate_compact_rank(float area, float span);

int anomaly_color_blob_candidate_compare_rank(
        const anomaly_color_blob_candidate_t *lhs,
        const anomaly_color_blob_candidate_t *rhs);

int anomaly_appearance_find_color_blob_candidate_rank(
        const anomaly_color_blob_candidate_t *top,
        int top_count,
        int sg_x,
        int sg_y);

void anomaly_appearance_insert_color_blob_candidate(
        anomaly_color_blob_candidate_t *top,
        int *top_count,
        const anomaly_color_blob_candidate_t *candidate,
        int max_candidates,
        int nms_radius,
        int target_rank_before,
        bool candidate_is_target,
        anomaly_color_blob_insert_report_t *out_report);

void anomaly_appearance_insert_ranked_index(
        int index,
        float rank,
        int *indices,
        float *ranks,
        int *count,
        int capacity);

#endif
