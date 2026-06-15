#include "anomaly_appearance_candidates.h"

#include <math.h>
#include <stddef.h>
#include <stdlib.h>

void anomaly_appearance_collect_motion_candidates(
        const float *thermal_map,
        const float *color_map,
        int          sg_w,
        int          sg_h,
        int          roi_x0,
        int          roi_y0,
        int          sample_step,
        anomaly_motion_candidate_t *out_candidates,
        int         *out_count) {
    if (out_count != NULL) *out_count = 0;
    if (out_candidates == NULL || out_count == NULL || sg_w <= 0 || sg_h <= 0) return;

    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            float thermal_score = thermal_map != NULL ? thermal_map[idx] : -1.0f;
            if (thermal_score < 0.0f) thermal_score = 0.0f;
            float color_score = color_map != NULL ? color_map[idx] : 0.0f;
            if (color_score < 0.0f) color_score = 0.0f;
            float proposal_score = thermal_score;
            if (color_score > 0.0f) {
                proposal_score += thermal_score > 0.0f ? (0.60f * color_score)
                                                       : (0.85f * color_score);
            }
            if (proposal_score <= 0.0f) continue;

            bool is_peak = true;
            for (int ny = sy - 1; ny <= sy + 1 && is_peak; ny++) {
                if (ny < 0 || ny >= sg_h) continue;
                for (int nx = sx - 1; nx <= sx + 1; nx++) {
                    if (nx < 0 || nx >= sg_w || (nx == sx && ny == sy)) continue;
                    size_t nidx = (size_t)ny * (size_t)sg_w + (size_t)nx;
                    float nthermal = thermal_map != NULL ? thermal_map[nidx] : -1.0f;
                    if (nthermal < 0.0f) nthermal = 0.0f;
                    float ncolor = color_map != NULL ? color_map[nidx] : 0.0f;
                    if (ncolor < 0.0f) ncolor = 0.0f;
                    float neighbor_score = nthermal;
                    if (ncolor > 0.0f) {
                        neighbor_score += nthermal > 0.0f ? (0.60f * ncolor)
                                                           : (0.85f * ncolor);
                    }
                    if (neighbor_score > proposal_score) {
                        is_peak = false;
                        break;
                    }
                }
            }
            if (!is_peak) continue;

            int insert_at = *out_count;
            bool rejected = false;
            for (int i = 0; i < *out_count; i++) {
                int ddx = abs(out_candidates[i].sg_x - sx);
                int ddy = abs(out_candidates[i].sg_y - sy);
                if (ddx <= ANOMALY_APPEARANCE_MOTION_CANDIDATE_NMS_RADIUS &&
                    ddy <= ANOMALY_APPEARANCE_MOTION_CANDIDATE_NMS_RADIUS) {
                    if (proposal_score > out_candidates[i].proposal_score) {
                        out_candidates[i].sg_x = sx;
                        out_candidates[i].sg_y = sy;
                        out_candidates[i].pixel_x = roi_x0 + sx * sample_step;
                        out_candidates[i].pixel_y = roi_y0 + sy * sample_step;
                        out_candidates[i].proposal_score = proposal_score;
                        out_candidates[i].thermal_score = thermal_score;
                        out_candidates[i].color_score = color_score;
                    }
                    rejected = true;
                    break;
                }
                if (proposal_score > out_candidates[i].proposal_score) {
                    insert_at = i;
                    break;
                }
            }
            if (rejected) continue;
            if (insert_at >= ANOMALY_APPEARANCE_MOTION_CANDIDATE_MAX) continue;

            int move_limit = *out_count < ANOMALY_APPEARANCE_MOTION_CANDIDATE_MAX
                ? *out_count
                : (ANOMALY_APPEARANCE_MOTION_CANDIDATE_MAX - 1);
            for (int i = move_limit; i > insert_at; i--) {
                out_candidates[i] = out_candidates[i - 1];
            }
            if (*out_count < ANOMALY_APPEARANCE_MOTION_CANDIDATE_MAX) (*out_count)++;

            out_candidates[insert_at].sg_x = sx;
            out_candidates[insert_at].sg_y = sy;
            out_candidates[insert_at].pixel_x = roi_x0 + sx * sample_step;
            out_candidates[insert_at].pixel_y = roi_y0 + sy * sample_step;
            out_candidates[insert_at].proposal_score = proposal_score;
            out_candidates[insert_at].thermal_score = thermal_score;
            out_candidates[insert_at].color_score = color_score;
        }
    }
}

int anomaly_thermal_blob_candidate_compare_rank(
        const anomaly_thermal_blob_candidate_t *lhs,
        const anomaly_thermal_blob_candidate_t *rhs) {
    if (lhs == NULL && rhs == NULL) return 0;
    if (lhs == NULL) return 1;
    if (rhs == NULL) return -1;

    if (lhs->retention_rank_valid && rhs->retention_rank_valid) {
        if (lhs->retention_rank > rhs->retention_rank) return -1;
        if (lhs->retention_rank < rhs->retention_rank) return 1;
    }

    if (!lhs->retention_rank_valid && !rhs->retention_rank_valid) {
        bool lhs_compact_score_fallback =
            lhs->area >= 2.0f && lhs->area <= 4.0f &&
            lhs->span <= 3.0f &&
            lhs->quality >= 0.35f &&
            lhs->candidate.thermal_score >= 1.0f;
        bool rhs_compact_score_fallback =
            rhs->area >= 2.0f && rhs->area <= 4.0f &&
            rhs->span <= 3.0f &&
            rhs->quality >= 0.35f &&
            rhs->candidate.thermal_score >= 1.0f;
        if (lhs_compact_score_fallback || rhs_compact_score_fallback) {
            if (lhs_compact_score_fallback && !rhs_compact_score_fallback) return -1;
            if (!lhs_compact_score_fallback && rhs_compact_score_fallback) return 1;
            if (lhs->candidate.thermal_score > rhs->candidate.thermal_score) return -1;
            if (lhs->candidate.thermal_score < rhs->candidate.thermal_score) return 1;
        }
    }

    if (lhs->area < rhs->area) return -1;
    if (lhs->area > rhs->area) return 1;

    if (lhs->span < rhs->span) return -1;
    if (lhs->span > rhs->span) return 1;

    if (lhs->candidate.thermal_score > rhs->candidate.thermal_score) return -1;
    if (lhs->candidate.thermal_score < rhs->candidate.thermal_score) return 1;

    if (lhs->peak_delta > rhs->peak_delta) return -1;
    if (lhs->peak_delta < rhs->peak_delta) return 1;

    if (lhs->quality > rhs->quality) return -1;
    if (lhs->quality < rhs->quality) return 1;

    return 0;
}

int anomaly_appearance_find_thermal_blob_candidate_rank(
        const anomaly_thermal_blob_candidate_t *top,
        int top_count,
        int sg_x,
        int sg_y) {
    if (top == NULL || top_count <= 0 || sg_x < 0 || sg_y < 0) return -1;
    for (int i = 0; i < top_count; i++) {
        if (top[i].candidate.sg_x == sg_x &&
            top[i].candidate.sg_y == sg_y) {
            return i;
        }
    }
    return -1;
}

void anomaly_appearance_insert_thermal_blob_candidate(
        anomaly_thermal_blob_candidate_t *top,
        int *top_count,
        const anomaly_thermal_blob_candidate_t *candidate,
        int max_candidates,
        int nms_radius,
        int target_rank_before,
        bool candidate_is_target,
        anomaly_thermal_blob_insert_report_t *out_report) {
    anomaly_thermal_blob_insert_report_t report = {
        .valid = false,
        .inserted = false,
        .replaced_existing_by_nms = false,
        .rejected_by_nms = false,
        .rejected_by_cap = false,
        .target_tail_dropped_by_cap = false,
        .candidate_count_before = top_count != NULL ? *top_count : -1,
        .insert_rank = -1,
        .pre_cap_rank = -1,
        .nms_conflict_rank = -1,
        .nms_conflict_sample_x = -1,
        .nms_conflict_sample_y = -1,
    };
    if (top == NULL || top_count == NULL || candidate == NULL ||
        max_candidates <= 0 || nms_radius < 0 || *top_count < 0) {
        if (out_report != NULL) *out_report = report;
        return;
    }

    report.valid = true;
    report.candidate_count_before = *top_count;

    int insert_at = *top_count;
    for (int i = 0; i < *top_count; i++) {
        int ddx = abs(top[i].candidate.sg_x - candidate->candidate.sg_x);
        int ddy = abs(top[i].candidate.sg_y - candidate->candidate.sg_y);
        if (ddx <= nms_radius && ddy <= nms_radius) {
            report.pre_cap_rank = i;
            report.nms_conflict_rank = i;
            if (anomaly_thermal_blob_candidate_compare_rank(candidate, &top[i]) < 0) {
                if (candidate_is_target) {
                    report.nms_conflict_sample_x = top[i].candidate.sg_x;
                    report.nms_conflict_sample_y = top[i].candidate.sg_y;
                } else {
                    report.nms_conflict_sample_x = candidate->candidate.sg_x;
                    report.nms_conflict_sample_y = candidate->candidate.sg_y;
                }
                top[i] = *candidate;
                report.inserted = true;
                report.replaced_existing_by_nms = true;
            } else {
                report.rejected_by_nms = true;
                report.nms_conflict_sample_x = top[i].candidate.sg_x;
                report.nms_conflict_sample_y = top[i].candidate.sg_y;
            }
            if (out_report != NULL) *out_report = report;
            return;
        }
        if (anomaly_thermal_blob_candidate_compare_rank(candidate, &top[i]) < 0) {
            insert_at = i;
            break;
        }
    }

    report.pre_cap_rank = insert_at;
    report.insert_rank = insert_at;
    if (insert_at >= max_candidates) {
        report.rejected_by_cap = true;
        if (out_report != NULL) *out_report = report;
        return;
    }

    if (target_rank_before == (max_candidates - 1) &&
        *top_count >= max_candidates &&
        insert_at <= target_rank_before &&
        !candidate_is_target) {
        report.target_tail_dropped_by_cap = true;
    }

    int move_limit = *top_count < max_candidates
        ? *top_count
        : (max_candidates - 1);
    for (int i = move_limit; i > insert_at; i--) {
        top[i] = top[i - 1];
    }
    if (*top_count < max_candidates) (*top_count)++;
    top[insert_at] = *candidate;
    report.inserted = true;

    if (out_report != NULL) *out_report = report;
}

float anomaly_color_blob_candidate_compact_rank(float area, float span) {
    float area_rank;
    if (area <= 1.5f) area_rank = 0.18f;
    else if (area <= 4.5f) area_rank = 1.00f;
    else if (area <= 9.5f) area_rank = 0.92f;
    else if (area <= 16.5f) area_rank = 0.58f;
    else area_rank = 0.18f;

    float span_rank;
    if (span <= 1.5f) span_rank = 0.16f;
    else if (span <= 3.5f) span_rank = 1.00f;
    else if (span <= 5.5f) span_rank = 0.88f;
    else if (span <= 8.5f) span_rank = 0.50f;
    else span_rank = 0.16f;

    return 0.55f * area_rank + 0.45f * span_rank;
}

int anomaly_color_blob_candidate_compare_rank(
        const anomaly_color_blob_candidate_t *lhs,
        const anomaly_color_blob_candidate_t *rhs) {
    if (lhs == NULL && rhs == NULL) return 0;
    if (lhs == NULL) return 1;
    if (rhs == NULL) return -1;

    if (lhs->color_uniqueness_rank > 0.0f || rhs->color_uniqueness_rank > 0.0f) {
        if (lhs->color_uniqueness_rank > rhs->color_uniqueness_rank) return -1;
        if (lhs->color_uniqueness_rank < rhs->color_uniqueness_rank) return 1;
    }
    if (lhs->hist_rarity_score > 0.0f || rhs->hist_rarity_score > 0.0f) {
        if (lhs->hist_rarity_score > rhs->hist_rarity_score) return -1;
        if (lhs->hist_rarity_score < rhs->hist_rarity_score) return 1;
    }
    if (lhs->retention_rank_valid || rhs->retention_rank_valid) {
        float lhs_rank = lhs->retention_rank_valid ? lhs->retention_rank : 0.0f;
        float rhs_rank = rhs->retention_rank_valid ? rhs->retention_rank : 0.0f;
        if (lhs_rank > rhs_rank) return -1;
        if (lhs_rank < rhs_rank) return 1;
    }
    if (lhs->candidate.color_score > rhs->candidate.color_score) return -1;
    if (lhs->candidate.color_score < rhs->candidate.color_score) return 1;
    if (lhs->quality > rhs->quality) return -1;
    if (lhs->quality < rhs->quality) return 1;
    float lhs_compact_rank = anomaly_color_blob_candidate_compact_rank(lhs->area, lhs->span);
    float rhs_compact_rank = anomaly_color_blob_candidate_compact_rank(rhs->area, rhs->span);
    if (lhs_compact_rank > rhs_compact_rank) return -1;
    if (lhs_compact_rank < rhs_compact_rank) return 1;
    if (lhs->center_share > rhs->center_share) return -1;
    if (lhs->center_share < rhs->center_share) return 1;
    if (lhs->peak_support > rhs->peak_support) return -1;
    if (lhs->peak_support < rhs->peak_support) return 1;
    return 0;
}

int anomaly_appearance_find_color_blob_candidate_rank(
        const anomaly_color_blob_candidate_t *top,
        int top_count,
        int sg_x,
        int sg_y) {
    if (top == NULL || top_count <= 0 || sg_x < 0 || sg_y < 0) return -1;
    for (int i = 0; i < top_count; i++) {
        if (top[i].candidate.sg_x == sg_x &&
            top[i].candidate.sg_y == sg_y) {
            return i;
        }
    }
    return -1;
}

void anomaly_appearance_insert_color_blob_candidate(
        anomaly_color_blob_candidate_t *top,
        int *top_count,
        const anomaly_color_blob_candidate_t *candidate,
        int max_candidates,
        int nms_radius,
        int target_rank_before,
        bool candidate_is_target,
        anomaly_color_blob_insert_report_t *out_report) {
    anomaly_color_blob_insert_report_t report = {
        .valid = false,
        .inserted = false,
        .replaced_existing_by_nms = false,
        .rejected_by_nms = false,
        .rejected_by_cap = false,
        .target_tail_dropped_by_cap = false,
        .candidate_count_before = top_count != NULL ? *top_count : -1,
        .insert_rank = -1,
        .pre_cap_rank = -1,
        .nms_conflict_rank = -1,
        .nms_conflict_sample_x = -1,
        .nms_conflict_sample_y = -1,
    };
    if (top == NULL || top_count == NULL || candidate == NULL ||
        max_candidates <= 0 || nms_radius < 0 || *top_count < 0) {
        if (out_report != NULL) *out_report = report;
        return;
    }

    report.valid = true;
    report.candidate_count_before = *top_count;

    int insert_at = *top_count;
    for (int i = 0; i < *top_count; i++) {
        int ddx = abs(top[i].candidate.sg_x - candidate->candidate.sg_x);
        int ddy = abs(top[i].candidate.sg_y - candidate->candidate.sg_y);
        if (ddx <= nms_radius && ddy <= nms_radius) {
            report.pre_cap_rank = i;
            report.nms_conflict_rank = i;
            if (anomaly_color_blob_candidate_compare_rank(candidate, &top[i]) < 0) {
                if (candidate_is_target) {
                    report.nms_conflict_sample_x = top[i].candidate.sg_x;
                    report.nms_conflict_sample_y = top[i].candidate.sg_y;
                } else {
                    report.nms_conflict_sample_x = candidate->candidate.sg_x;
                    report.nms_conflict_sample_y = candidate->candidate.sg_y;
                }
                top[i] = *candidate;
                report.inserted = true;
                report.replaced_existing_by_nms = true;
            } else {
                report.rejected_by_nms = true;
                report.nms_conflict_sample_x = top[i].candidate.sg_x;
                report.nms_conflict_sample_y = top[i].candidate.sg_y;
            }
            if (out_report != NULL) *out_report = report;
            return;
        }
        if (anomaly_color_blob_candidate_compare_rank(candidate, &top[i]) < 0) {
            insert_at = i;
            break;
        }
    }

    report.pre_cap_rank = insert_at;
    report.insert_rank = insert_at;
    if (insert_at >= max_candidates) {
        report.rejected_by_cap = true;
        if (out_report != NULL) *out_report = report;
        return;
    }

    if (target_rank_before == (max_candidates - 1) &&
        *top_count >= max_candidates &&
        insert_at <= target_rank_before &&
        !candidate_is_target) {
        report.target_tail_dropped_by_cap = true;
    }

    int move_limit = *top_count < max_candidates
        ? *top_count
        : (max_candidates - 1);
    for (int i = move_limit; i > insert_at; i--) {
        top[i] = top[i - 1];
    }
    if (*top_count < max_candidates) (*top_count)++;
    top[insert_at] = *candidate;
    report.inserted = true;

    if (out_report != NULL) *out_report = report;
}

void anomaly_appearance_insert_ranked_index(
        int index,
        float rank,
        int *indices,
        float *ranks,
        int *count,
        int capacity) {
    if (indices == NULL || ranks == NULL || count == NULL || capacity <= 0) return;
    if (*count < 0) return;

    int current_count = *count;
    if (current_count > capacity) current_count = capacity;
    int insert_at = current_count;
    while (insert_at > 0 && rank > ranks[insert_at - 1]) {
        if (insert_at < capacity) {
            ranks[insert_at] = ranks[insert_at - 1];
            indices[insert_at] = indices[insert_at - 1];
        }
        insert_at--;
    }
    if (insert_at < capacity) {
        ranks[insert_at] = rank;
        indices[insert_at] = index;
    }
    if (*count < capacity) (*count)++;
}

int anomaly_appearance_select_thermal_provisional_reserve(
        const int *eligible_indices,
        int eligible_count,
        int keep_count,
        const anomaly_thermal_provisional_reserve_candidate_t *candidates,
        int candidate_count) {
    if (eligible_indices == NULL || candidates == NULL ||
        eligible_count <= 0 || candidate_count <= 0 || keep_count < 0) {
        return -1;
    }
    if (keep_count > eligible_count) keep_count = eligible_count;

    int best_index = -1;
    float best_score = -1.0f;
    for (int ri = keep_count; ri < eligible_count; ri++) {
        int ci = eligible_indices[ri];
        if (ci < 0 || ci >= candidate_count) continue;
        const anomaly_thermal_provisional_reserve_candidate_t *candidate =
            &candidates[ci];
        if (!candidate->valid || candidate->near_reviewed_fp_cluster) continue;
        if (!isfinite(candidate->final_score) ||
            !isfinite(candidate->score_threshold) ||
            !isfinite(candidate->movement_confidence)) {
            continue;
        }
        if (!candidate->movement_tile_valid ||
            !candidate->movement_parallax ||
            candidate->movement_independent ||
            candidate->movement_confidence < 0.90f) {
            continue;
        }
        if (candidate->final_score < candidate->score_threshold + 1.25f) continue;
        if (candidate->final_score > candidate->score_threshold + 2.00f) continue;
        if (candidate->area < 1.5f || candidate->area > 2.5f) continue;
        if (candidate->span < 1.5f || candidate->span > 2.5f) continue;
        if (candidate->fill < 0.35f || candidate->fill > 0.70f) continue;
        if (candidate->center_share < 0.54f) continue;
        if (candidate->quality < 0.60f) continue;
        if (candidate->patch_support < candidate->score_threshold + 1.15f) continue;

        float score =
            0.40f * (candidate->final_score - candidate->score_threshold) +
            0.25f * candidate->patch_support +
            0.20f * candidate->center_share +
            0.15f * candidate->quality;
        if (score > best_score) {
            best_score = score;
            best_index = ci;
        }
    }
    return best_index;
}
