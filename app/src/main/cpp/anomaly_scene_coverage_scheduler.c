#include "anomaly_scene_coverage_scheduler.h"

#include "anomaly_analysis.h"
#include "anomaly_scan_planner.h"

#include <float.h>
#include <limits.h>
#include <string.h>

typedef struct {
    int mapped_samples;
    int new_samples;
    bool target_revisit;
    bool locally_untrusted;
    bool due;
    int priority;
    float score;
} scene_coverage_block_observation_t;

static int clamp_int(int value, int low, int high) {
    if (value < low) return low;
    if (value > high) return high;
    return value;
}

static int block_index_for_sample(int x, int y, int width, int height) {
    int bx = clamp_int((x * ANOMALY_SCENE_COVERAGE_COLS) / width,
                       0,
                       ANOMALY_SCENE_COVERAGE_COLS - 1);
    int by = clamp_int((y * ANOMALY_SCENE_COVERAGE_ROWS) / height,
                       0,
                       ANOMALY_SCENE_COVERAGE_ROWS - 1);
    return by * ANOMALY_SCENE_COVERAGE_COLS + bx;
}

static int morton_code_for_block(int index) {
    int x = index % ANOMALY_SCENE_COVERAGE_COLS;
    int y = index / ANOMALY_SCENE_COVERAGE_COLS;
    int code = 0;
    for (int bit = 0; bit < 3; bit++) {
        code |= ((x >> bit) & 1) << (bit * 2);
        code |= ((y >> bit) & 1) << (bit * 2 + 1);
    }
    return code;
}

static int selected_distance_sq(int index, uint64_t selected_mask) {
    if (selected_mask == 0u) return INT_MAX;
    int x = index % ANOMALY_SCENE_COVERAGE_COLS;
    int y = index / ANOMALY_SCENE_COVERAGE_COLS;
    int best = INT_MAX;
    for (int other = 0; other < ANOMALY_SCENE_COVERAGE_BLOCK_COUNT; other++) {
        if ((selected_mask & (UINT64_C(1) << other)) == 0u) continue;
        int dx = x - (other % ANOMALY_SCENE_COVERAGE_COLS);
        int dy = y - (other / ANOMALY_SCENE_COVERAGE_COLS);
        int distance = dx * dx + dy * dy;
        if (distance < best) best = distance;
    }
    return best;
}

static int count_mask_bits(uint64_t mask) {
    int count = 0;
    while (mask != 0u) {
        count += (int)(mask & UINT64_C(1));
        mask >>= 1;
    }
    return count;
}

static int estimated_samples_for_mask(uint64_t mask, int width, int height) {
    int total = 0;
    for (int block = 0; block < ANOMALY_SCENE_COVERAGE_BLOCK_COUNT; block++) {
        if ((mask & (UINT64_C(1) << block)) == 0u) continue;
        int bx = block % ANOMALY_SCENE_COVERAGE_COLS;
        int by = block / ANOMALY_SCENE_COVERAGE_COLS;
        int x0 = (bx * width) / ANOMALY_SCENE_COVERAGE_COLS;
        int x1 = ((bx + 1) * width) / ANOMALY_SCENE_COVERAGE_COLS;
        int y0 = (by * height) / ANOMALY_SCENE_COVERAGE_ROWS;
        int y1 = ((by + 1) * height) / ANOMALY_SCENE_COVERAGE_ROWS;
        total += (x1 - x0) * (y1 - y0);
    }
    return total;
}

static bool previous_sample_is_target_revisit(
        const anomaly_roi_state_t *previous_roi,
        int previous_x,
        int previous_y) {
    if (previous_roi == NULL || previous_roi->cell_summaries == NULL ||
        previous_roi->cell_cols <= 0 || previous_roi->cell_rows <= 0 ||
        previous_roi->width <= 0 || previous_roi->height <= 0) {
        return false;
    }
    int cell_x = clamp_int(
        (previous_x * previous_roi->cell_cols) / previous_roi->width,
        0,
        previous_roi->cell_cols - 1);
    int cell_y = clamp_int(
        (previous_y * previous_roi->cell_rows) / previous_roi->height,
        0,
        previous_roi->cell_rows - 1);
    int cell_index = cell_y * previous_roi->cell_cols + cell_x;
    return (previous_roi->cell_summaries[cell_index].scan_flags &
            ANOMALY_SCAN_FLAG_TARGET_REVISIT) != 0u;
}

static const anomaly_debug_movement_tile_t *movement_tile_for_block(
        const anomaly_debug_movement_t *movement,
        int block) {
    if (movement == NULL || !movement->valid ||
        movement->tile_cols <= 0 || movement->tile_rows <= 0) {
        return NULL;
    }
    int bx = block % ANOMALY_SCENE_COVERAGE_COLS;
    int by = block / ANOMALY_SCENE_COVERAGE_COLS;
    int tx = clamp_int((bx * movement->tile_cols) / ANOMALY_SCENE_COVERAGE_COLS,
                       0,
                       movement->tile_cols - 1);
    int ty = clamp_int((by * movement->tile_rows) / ANOMALY_SCENE_COVERAGE_ROWS,
                       0,
                       movement->tile_rows - 1);
    int tile_index = ty * movement->tile_cols + tx;
    if (tile_index < 0 || tile_index >= ANOMALY_MOVEMENT_TILE_COUNT) return NULL;
    return &movement->tiles[tile_index];
}

static int choose_next_block(
        const scene_coverage_block_observation_t observations[ANOMALY_SCENE_COVERAGE_BLOCK_COUNT],
        uint64_t selected_mask) {
    int best = -1;
    for (int block = 0; block < ANOMALY_SCENE_COVERAGE_BLOCK_COUNT; block++) {
        if ((selected_mask & (UINT64_C(1) << block)) != 0u) continue;
        if (best < 0 || observations[block].priority > observations[best].priority ||
            (observations[block].priority == observations[best].priority &&
             observations[block].score > observations[best].score + 1e-6f)) {
            best = block;
            continue;
        }
        if (observations[block].priority != observations[best].priority ||
            observations[block].score + 1e-6f < observations[best].score) {
            continue;
        }
        int distance = selected_distance_sq(block, selected_mask);
        int best_distance = selected_distance_sq(best, selected_mask);
        if (distance > best_distance ||
            (distance == best_distance &&
             morton_code_for_block(block) < morton_code_for_block(best))) {
            best = block;
        }
    }
    return best;
}

void anomaly_scene_coverage_state_init(anomaly_scene_coverage_state_t *state) {
    anomaly_scene_coverage_state_reset(state);
}

void anomaly_scene_coverage_state_reset(anomaly_scene_coverage_state_t *state) {
    if (state == NULL) return;
    memset(state, 0, sizeof(*state));
    state->mode = ANOMALY_SCENE_COVERAGE_FULL_REQUIRED;
}

bool anomaly_scene_coverage_scheduler_observe(
        anomaly_scene_coverage_state_t *state,
        const anomaly_scene_coverage_input_t *input,
        anomaly_scene_coverage_shadow_t *shadow_out) {
    if (shadow_out != NULL) memset(shadow_out, 0, sizeof(*shadow_out));
    if (state == NULL || input == NULL || shadow_out == NULL || input->policy == NULL ||
        input->sampled_width <= 0 || input->sampled_height <= 0) {
        if (shadow_out != NULL) {
            shadow_out->mode = ANOMALY_SCENE_COVERAGE_FULL_REQUIRED;
            shadow_out->reason_flags = ANOMALY_SCENE_COVERAGE_REASON_INVALID_INPUT;
        }
        return false;
    }

    const anomaly_scene_coverage_policy_t *policy = input->policy;
    int locked_budget = clamp_int(policy->locked_budget_blocks,
                                  1,
                                  ANOMALY_SCENE_COVERAGE_BLOCK_COUNT);
    int recovery_budget = clamp_int(policy->recovery_budget_blocks,
                                    locked_budget,
                                    ANOMALY_SCENE_COVERAGE_BLOCK_COUNT);
    int relock_frames = policy->relock_healthy_frames > 0
        ? policy->relock_healthy_frames : 1;
    int total_samples = input->sampled_width * input->sampled_height;
    bool prior_valid = state->valid;
    bool grid_mismatch = prior_valid &&
        (state->sampled_width != input->sampled_width ||
         state->sampled_height != input->sampled_height);
    anomaly_scene_coverage_state_t prior = *state;
    scene_coverage_block_observation_t observations[ANOMALY_SCENE_COVERAGE_BLOCK_COUNT];
    anomaly_scene_coverage_block_state_t carried[ANOMALY_SCENE_COVERAGE_BLOCK_COUNT];
    bool carried_initialized[ANOMALY_SCENE_COVERAGE_BLOCK_COUNT] = {false};
    memset(observations, 0, sizeof(observations));
    memset(carried, 0, sizeof(carried));

    uint32_t reasons = 0u;
    bool lookup_usable = prior_valid && !grid_mismatch &&
        input->prev_sample_lookup != NULL && input->previous_roi != NULL &&
        input->previous_roi->width > 0 && input->previous_roi->height > 0;
    int computed_new_samples = 0;
    if (lookup_usable) {
        int previous_width = input->previous_roi->width;
        int previous_height = input->previous_roi->height;
        for (int y = 0; y < input->sampled_height; y++) {
            for (int x = 0; x < input->sampled_width; x++) {
                int current_index = y * input->sampled_width + x;
                int current_block = block_index_for_sample(
                    x, y, input->sampled_width, input->sampled_height);
                int previous_index = input->prev_sample_lookup[current_index];
                if (previous_index < 0 ||
                    previous_index >= previous_width * previous_height) {
                    observations[current_block].new_samples++;
                    computed_new_samples++;
                    continue;
                }
                int previous_x = previous_index % previous_width;
                int previous_y = previous_index / previous_width;
                int previous_block = block_index_for_sample(
                    previous_x, previous_y, previous_width, previous_height);
                const anomaly_scene_coverage_block_state_t *source =
                    &prior.blocks[previous_block];
                anomaly_scene_coverage_block_state_t *destination =
                    &carried[current_block];
                if (!carried_initialized[current_block]) {
                    *destination = *source;
                    carried_initialized[current_block] = true;
                } else {
                    if (source->shadow_last_selected_frame <
                        destination->shadow_last_selected_frame) {
                        destination->shadow_last_selected_frame =
                            source->shadow_last_selected_frame;
                    }
                    if (source->shadow_last_selected_source_ts_us > 0 &&
                        (destination->shadow_last_selected_source_ts_us <= 0 ||
                         source->shadow_last_selected_source_ts_us <
                            destination->shadow_last_selected_source_ts_us)) {
                        destination->shadow_last_selected_source_ts_us =
                            source->shadow_last_selected_source_ts_us;
                    }
                    if (source->coverage_debt > destination->coverage_debt) {
                        destination->coverage_debt = source->coverage_debt;
                    }
                }
                observations[current_block].mapped_samples++;
                if (previous_sample_is_target_revisit(
                        input->previous_roi, previous_x, previous_y)) {
                    observations[current_block].target_revisit = true;
                }
            }
        }
    } else {
        computed_new_samples = total_samples;
        reasons |= ANOMALY_SCENE_COVERAGE_REASON_LOOKUP_MISSING;
    }

    int summary_new_samples = input->prev_lookup_summary != NULL
        ? input->prev_lookup_summary->newly_exposed_samples : computed_new_samples;
    int summary_carried_samples = input->prev_lookup_summary != NULL
        ? input->prev_lookup_summary->carried_samples : total_samples - computed_new_samples;
    float newly_exposed_fraction = total_samples > 0
        ? (float)summary_new_samples / (float)total_samples : 1.0f;
    float warped_valid_fraction = total_samples > 0
        ? (float)summary_carried_samples / (float)total_samples : 0.0f;

    int64_t latest_prior_ts = 0;
    for (int block = 0; block < ANOMALY_SCENE_COVERAGE_BLOCK_COUNT; block++) {
        if (prior.blocks[block].shadow_last_selected_source_ts_us > latest_prior_ts) {
            latest_prior_ts = prior.blocks[block].shadow_last_selected_source_ts_us;
        }
    }
    bool source_time_usable = input->source_ts_us > 0 &&
        (latest_prior_ts <= 0 || input->source_ts_us > latest_prior_ts);
    if (!source_time_usable) {
        reasons |= ANOMALY_SCENE_COVERAGE_REASON_TIMESTAMP_FALLBACK;
    }

    uint64_t mandatory_mask = 0u;
    uint64_t hard_mandatory_mask = 0u;
    int64_t maximum_age_us = 0;
    int maximum_age_frames = 0;
    float maximum_debt = 0.0f;
    for (int block = 0; block < ANOMALY_SCENE_COVERAGE_BLOCK_COUNT; block++) {
        if (!carried_initialized[block]) memset(&carried[block], 0, sizeof(carried[block]));
        int block_samples = observations[block].mapped_samples + observations[block].new_samples;
        float new_fraction = block_samples > 0
            ? (float)observations[block].new_samples / (float)block_samples : 0.0f;
        carried[block].coverage_debt += 1.0f + 2.0f * new_fraction;

        const anomaly_debug_movement_tile_t *tile =
            movement_tile_for_block(input->movement, block);
        if (tile != NULL &&
            (!tile->valid || tile->confidence < policy->minimum_movement_confidence ||
             tile->layer_class == ANOMALY_MOVEMENT_LAYER_UNSTABLE ||
             tile->layer_class == ANOMALY_MOVEMENT_LAYER_LOCAL_OUTLIER)) {
            observations[block].locally_untrusted = true;
        }

        int age_frames = carried[block].shadow_last_selected_frame > 0
            ? (int)(input->frame_counter - carried[block].shadow_last_selected_frame)
            : INT_MAX / 4;
        int64_t age_us = source_time_usable &&
            carried[block].shadow_last_selected_source_ts_us > 0
            ? input->source_ts_us - carried[block].shadow_last_selected_source_ts_us
            : 0;
        observations[block].due =
            (policy->max_age_frames > 0 && age_frames >= policy->max_age_frames) ||
            (source_time_usable && policy->max_age_us > 0 && age_us >= policy->max_age_us);
        if (age_frames < INT_MAX / 4 && age_frames > maximum_age_frames) {
            maximum_age_frames = age_frames;
        }
        if (age_us > maximum_age_us) maximum_age_us = age_us;
        if (carried[block].coverage_debt > maximum_debt) {
            maximum_debt = carried[block].coverage_debt;
        }

        if (observations[block].new_samples > 0) observations[block].priority = 4;
        else if (observations[block].target_revisit) observations[block].priority = 3;
        else if (observations[block].locally_untrusted) observations[block].priority = 2;
        else if (observations[block].due) observations[block].priority = 1;
        observations[block].score = carried[block].coverage_debt +
            new_fraction * 8.0f + (float)age_frames * 0.01f;
        if (observations[block].priority > 0) {
            mandatory_mask |= UINT64_C(1) << block;
        }
        if (observations[block].new_samples > 0 || observations[block].due) {
            hard_mandatory_mask |= UINT64_C(1) << block;
        }
    }
    if (mandatory_mask != 0u) {
        bool has_target = false;
        bool has_untrusted = false;
        bool has_due = false;
        for (int block = 0; block < ANOMALY_SCENE_COVERAGE_BLOCK_COUNT; block++) {
            has_target |= observations[block].target_revisit;
            has_untrusted |= observations[block].locally_untrusted;
            has_due |= observations[block].due;
        }
        if (has_target) reasons |= ANOMALY_SCENE_COVERAGE_REASON_TARGET_REVISIT;
        if (has_untrusted) reasons |= ANOMALY_SCENE_COVERAGE_REASON_LOCAL_UNTRUSTED;
        if (has_due) reasons |= ANOMALY_SCENE_COVERAGE_REASON_MAX_AGE;
    }

    bool hard_required = false;
    if (!prior_valid) {
        hard_required = true;
        reasons |= ANOMALY_SCENE_COVERAGE_REASON_STARTUP;
    }
    if (input->scene_discontinuity) {
        hard_required = true;
        reasons |= ANOMALY_SCENE_COVERAGE_REASON_SCENE_DISCONTINUITY;
    }
    if (input->registration_health == ANOMALY_REG_HEALTH_INVALID ||
        input->registration_health == ANOMALY_REG_HEALTH_HARD_DEGRADED) {
        hard_required = true;
        reasons |= ANOMALY_SCENE_COVERAGE_REASON_REGISTRATION_HARD;
    }
    if (grid_mismatch) {
        hard_required = true;
        reasons |= ANOMALY_SCENE_COVERAGE_REASON_GRID_MISMATCH;
    }
    if (input->registration_attempted && !lookup_usable) hard_required = true;
    if (lookup_usable && warped_valid_fraction < 0.80f) {
        hard_required = true;
        reasons |= ANOMALY_SCENE_COVERAGE_REASON_WARP_LOW;
    }
    if (lookup_usable && newly_exposed_fraction > 0.25f) {
        hard_required = true;
        reasons |= ANOMALY_SCENE_COVERAGE_REASON_NEW_EXPOSED_HIGH;
    }
    if (!hard_required && count_mask_bits(hard_mandatory_mask) > recovery_budget) {
        hard_required = true;
        reasons |= ANOMALY_SCENE_COVERAGE_REASON_MANDATORY_OVER_BUDGET;
    }

    bool recovery_condition = false;
    if (input->registration_health == ANOMALY_REG_HEALTH_SOFT_DEGRADED) {
        recovery_condition = true;
        reasons |= ANOMALY_SCENE_COVERAGE_REASON_REGISTRATION_SOFT;
    }
    if (!input->registration_attempted) {
        recovery_condition = true;
        reasons |= ANOMALY_SCENE_COVERAGE_REASON_REGISTRATION_GAP;
    }
    if (lookup_usable && newly_exposed_fraction > 0.20f) {
        recovery_condition = true;
        reasons |= ANOMALY_SCENE_COVERAGE_REASON_NEW_EXPOSED_HIGH;
    }
    if (input->movement == NULL || !input->movement->valid ||
        input->movement->confidence < policy->minimum_movement_confidence) {
        recovery_condition = true;
        reasons |= ANOMALY_SCENE_COVERAGE_REASON_MOVEMENT_WEAK;
    }

    anomaly_scene_coverage_mode_t mode;
    if (hard_required) {
        mode = ANOMALY_SCENE_COVERAGE_FULL_REQUIRED;
        state->healthy_recovery_frames = 0;
    } else if (recovery_condition || prior.mode == ANOMALY_SCENE_COVERAGE_FULL_REQUIRED) {
        mode = ANOMALY_SCENE_COVERAGE_RECOVERY;
        if (recovery_condition) state->healthy_recovery_frames = 0;
        else state->healthy_recovery_frames = prior.healthy_recovery_frames + 1;
        reasons |= ANOMALY_SCENE_COVERAGE_REASON_RECOVERY_HYSTERESIS;
    } else if (prior.mode == ANOMALY_SCENE_COVERAGE_RECOVERY &&
               prior.healthy_recovery_frames + 1 < relock_frames) {
        mode = ANOMALY_SCENE_COVERAGE_RECOVERY;
        state->healthy_recovery_frames = prior.healthy_recovery_frames + 1;
        reasons |= ANOMALY_SCENE_COVERAGE_REASON_RECOVERY_HYSTERESIS;
    } else {
        mode = ANOMALY_SCENE_COVERAGE_LOCKED_INCREMENTAL;
        state->healthy_recovery_frames = relock_frames;
    }

    uint64_t selected_mask = 0u;
    int budget = mode == ANOMALY_SCENE_COVERAGE_FULL_REQUIRED
        ? ANOMALY_SCENE_COVERAGE_BLOCK_COUNT
        : (mode == ANOMALY_SCENE_COVERAGE_RECOVERY ? recovery_budget : locked_budget);
    for (int selected = 0; selected < budget; selected++) {
        int next = choose_next_block(observations, selected_mask);
        if (next < 0) break;
        selected_mask |= UINT64_C(1) << next;
    }

    state->valid = true;
    state->mode = mode;
    state->sampled_width = input->sampled_width;
    state->sampled_height = input->sampled_height;
    for (int block = 0; block < ANOMALY_SCENE_COVERAGE_BLOCK_COUNT; block++) {
        state->blocks[block] = carried[block];
        if ((selected_mask & (UINT64_C(1) << block)) != 0u) {
            state->blocks[block].shadow_last_selected_frame = input->frame_counter;
            if (source_time_usable) {
                state->blocks[block].shadow_last_selected_source_ts_us = input->source_ts_us;
            }
            state->blocks[block].coverage_debt = 0.0f;
        }
    }

    shadow_out->valid = true;
    shadow_out->mode = mode;
    shadow_out->selected_block_mask = selected_mask;
    shadow_out->mandatory_block_mask = mandatory_mask;
    shadow_out->selected_blocks = count_mask_bits(selected_mask);
    shadow_out->mandatory_blocks = count_mask_bits(mandatory_mask);
    shadow_out->estimated_selected_samples = estimated_samples_for_mask(
        selected_mask, input->sampled_width, input->sampled_height);
    shadow_out->selected_fraction = total_samples > 0
        ? (float)shadow_out->estimated_selected_samples / (float)total_samples : 0.0f;
    shadow_out->max_coverage_debt = maximum_debt;
    shadow_out->max_age_us = maximum_age_us;
    shadow_out->max_age_frames = maximum_age_frames;
    shadow_out->newly_exposed_fraction = newly_exposed_fraction;
    shadow_out->reason_flags = reasons;
    return true;
}
