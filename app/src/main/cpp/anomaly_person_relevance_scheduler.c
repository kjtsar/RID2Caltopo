#include "anomaly_person_relevance_scheduler.h"

#include <math.h>
#include <string.h>
#include <time.h>

static uint64_t monotonic_us(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0u;
    return (uint64_t)ts.tv_sec * 1000000u + (uint64_t)ts.tv_nsec / 1000u;
}

static bool snapshot_valid(const anomaly_person_scheduler_snapshot_t *snapshot) {
    if (snapshot == NULL || snapshot->generation == 0u ||
        snapshot->frame_sequence == 0u || snapshot->candidate_count == 0u ||
        snapshot->candidate_count > ANOMALY_PERSON_SCHEDULER_MAX_CANDIDATES) {
        return false;
    }
    anomaly_person_relevance_candidate_batch_t batch;
    anomaly_person_relevance_candidate_batch_init(&batch);
    for (size_t i = 0; i < snapshot->candidate_count; i++) {
        const anomaly_person_scheduler_candidate_t *candidate =
                &snapshot->candidates[i];
        if (candidate->track_id == 0u || candidate->track_revision == 0u ||
            !anomaly_person_relevance_candidate_batch_append(
                    &batch, &candidate->candidate)) {
            return false;
        }
    }
    return true;
}

size_t anomaly_person_scheduler_snapshot_from_boxes(
        uint64_t generation,
        uint64_t frame_sequence,
        int64_t source_timestamp_us,
        const anomaly_state_t *state,
        const anomaly_config_t *config,
        const anomaly_box_t *boxes,
        size_t box_count,
        anomaly_person_scheduler_snapshot_t *snapshot_out) {
    if (snapshot_out == NULL) return 0u;
    memset(snapshot_out, 0, sizeof(*snapshot_out));
    snapshot_out->generation = generation;
    snapshot_out->frame_sequence = frame_sequence;
    snapshot_out->source_timestamp_us = source_timestamp_us;
    if (generation == 0u || frame_sequence == 0u || state == NULL ||
        config == NULL || boxes == NULL) return 0u;

    bool selected[ANOMALY_MAX_BOXES_PER_FRAME] = {false};
    size_t bounded_count = box_count < ANOMALY_MAX_BOXES_PER_FRAME
            ? box_count : ANOMALY_MAX_BOXES_PER_FRAME;
    while (snapshot_out->candidate_count < ANOMALY_PERSON_SCHEDULER_MAX_CANDIDATES) {
        int best = -1;
        for (size_t i = 0; i < bounded_count; i++) {
            const anomaly_box_t *box = &boxes[i];
            bool eligible_algorithm = box->algorithm == ANOMALY_ALGO_COLOR ||
                                      box->algorithm == ANOMALY_ALGO_THERMAL;
            if (selected[i] || !eligible_algorithm) {
                continue;
            }
            bool has_published_track = false;
            int min_hits = config->min_hits < 1 ? 1 : config->min_hits;
            for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
                const anomaly_target_track_t *track = &state->target_tracks[ti];
                if (track->active && track->publish_confirmed &&
                    track->hit_count >= min_hits && track->id > 0 &&
                    track->algorithm == box->algorithm) {
                    has_published_track = true;
                    break;
                }
            }
            if (!has_published_track) continue;
            if (best < 0 || box->weight > boxes[best].weight) best = (int)i;
        }
        if (best < 0) break;
        selected[best] = true;
        const anomaly_box_t *box = &boxes[best];
        const anomaly_target_track_t *matched_track = NULL;
        float best_distance = INFINITY;
        float box_center_x = (box->left_norm + box->right_norm) * 0.5f;
        float box_center_y = (box->top_norm + box->bottom_norm) * 0.5f;
        int min_hits = config->min_hits < 1 ? 1 : config->min_hits;
        for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
            const anomaly_target_track_t *track = &state->target_tracks[ti];
            if (!track->active || !track->publish_confirmed ||
                track->hit_count < min_hits || track->id <= 0 ||
                track->algorithm != box->algorithm) {
                continue;
            }
            float dx = track->center_x_norm - box_center_x;
            float dy = track->center_y_norm - box_center_y;
            float distance = dx * dx + dy * dy;
            if (distance < best_distance) {
                best_distance = distance;
                matched_track = track;
            }
        }
        if (matched_track == NULL) continue;
        uint64_t track_id = (uint64_t)matched_track->id;
        uint32_t candidate_id = (uint32_t)(track_id & UINT32_MAX);
        if (candidate_id == 0u) candidate_id = (uint32_t)best + 1u;
        uint32_t provenance = box->algorithm == ANOMALY_ALGO_THERMAL
                ? ANOMALY_PERSON_PROVENANCE_THERMAL_IR
                : ANOMALY_PERSON_PROVENANCE_COLOR_UNIQUENESS;
        if (box->algorithm == ANOMALY_ALGO_COLOR &&
            config->target_color_family_mask != 0u) {
            provenance |= ANOMALY_PERSON_PROVENANCE_TARGET_COLOR;
        }
        snapshot_out->candidates[snapshot_out->candidate_count++] =
                (anomaly_person_scheduler_candidate_t) {
                    .track_id = track_id,
                    .track_revision = track_id,
                    .candidate = {
                        .candidate_id = candidate_id,
                        .observation_index = (uint32_t)best,
                        .provenance_mask = provenance,
                        .left_norm = box->left_norm,
                        .top_norm = box->top_norm,
                        .right_norm = box->right_norm,
                        .bottom_norm = box->bottom_norm,
                    },
                };
    }
    return snapshot_out->candidate_count;
}

static void release_work(
        anomaly_person_relevance_scheduler_t *scheduler,
        anomaly_person_scheduler_work_t *work) {
    if (work->frame != NULL && scheduler->release_frame != NULL) {
        scheduler->release_frame(work->frame);
    }
    memset(work, 0, sizeof(*work));
}

static anomaly_person_relevance_candidate_batch_t contract_batch(
        const anomaly_person_scheduler_snapshot_t *snapshot) {
    anomaly_person_relevance_candidate_batch_t batch;
    anomaly_person_relevance_candidate_batch_init(&batch);
    for (size_t i = 0; i < snapshot->candidate_count; i++) {
        (void)anomaly_person_relevance_candidate_batch_append(
                &batch, &snapshot->candidates[i].candidate);
    }
    return batch;
}

static void *worker_main(void *context) {
    anomaly_person_relevance_scheduler_t *scheduler = context;
    for (;;) {
        anomaly_person_scheduler_work_t work;
        memset(&work, 0, sizeof(work));
        pthread_mutex_lock(&scheduler->lock);
        while (!scheduler->stop && !scheduler->pending_valid) {
            pthread_cond_wait(&scheduler->cond, &scheduler->lock);
        }
        if (scheduler->stop) {
            pthread_mutex_unlock(&scheduler->lock);
            break;
        }
        work = scheduler->pending;
        memset(&scheduler->pending, 0, sizeof(scheduler->pending));
        scheduler->pending_valid = false;
        pthread_mutex_unlock(&scheduler->lock);

        anomaly_person_relevance_candidate_batch_t candidates =
                contract_batch(&work.snapshot);
        uint64_t started_at_us = monotonic_us();
        bool ok = scheduler->backend != NULL &&
                  scheduler->backend(
                          scheduler->backend_context,
                          work.frame,
                          &scheduler->model,
                          &candidates,
                          &work.decisions);
        if (ok && work.decisions.count > ANOMALY_PERSON_RELEVANCE_MAX_CANDIDATES) {
            ok = false;
        }
        uint64_t finished_at_us = monotonic_us();
        if (finished_at_us >= started_at_us) {
            atomic_fetch_add_explicit(
                    &scheduler->inference_wall_time_us,
                    finished_at_us - started_at_us,
                    memory_order_relaxed);
        }
        if (!ok) {
            atomic_fetch_add_explicit(
                    &scheduler->backend_failures, 1u, memory_order_relaxed);
            memset(&work.decisions, 0, sizeof(work.decisions));
        } else {
            for (size_t i = 0; i < work.decisions.count; i++) {
                if (work.decisions.decisions[i].kind ==
                    ANOMALY_PERSON_RELEVANCE_DECISION_POSITIVE) {
                    atomic_fetch_add_explicit(
                            &scheduler->positive_decisions, 1u, memory_order_relaxed);
                }
            }
        }
        if (work.frame != NULL && scheduler->release_frame != NULL) {
            scheduler->release_frame(work.frame);
            work.frame = NULL;
        }

        pthread_mutex_lock(&scheduler->lock);
        if (scheduler->completed_valid) {
            release_work(scheduler, &scheduler->completed_result);
        }
        scheduler->completed_result = work;
        scheduler->completed_valid = true;
        atomic_fetch_add_explicit(
                &scheduler->completed, 1u, memory_order_relaxed);
        pthread_mutex_unlock(&scheduler->lock);
    }
    return NULL;
}

bool anomaly_person_scheduler_init(
        anomaly_person_relevance_scheduler_t *scheduler,
        const anomaly_person_relevance_config_t *config,
        const anomaly_person_relevance_model_identity_t *model,
        anomaly_person_scheduler_backend_fn backend,
        void *backend_context,
        anomaly_person_scheduler_frame_retain_fn retain_frame,
        anomaly_person_scheduler_frame_release_fn release_frame) {
    if (scheduler == NULL || config == NULL) return false;
    memset(scheduler, 0, sizeof(*scheduler));
    atomic_init(&scheduler->offered, 0u);
    atomic_init(&scheduler->admitted, 0u);
    atomic_init(&scheduler->contention_drops, 0u);
    atomic_init(&scheduler->pressure_drops, 0u);
    atomic_init(&scheduler->replacements, 0u);
    atomic_init(&scheduler->completed, 0u);
    atomic_init(&scheduler->stale_discarded, 0u);
    atomic_init(&scheduler->backend_failures, 0u);
    atomic_init(&scheduler->positive_decisions, 0u);
    atomic_init(&scheduler->bonuses_applied, 0u);
    atomic_init(&scheduler->inference_wall_time_us, 0u);
    scheduler->config = *config;
    if (model != NULL) scheduler->model = *model;
    scheduler->backend = backend;
    scheduler->backend_context = backend_context;
    scheduler->retain_frame = retain_frame;
    scheduler->release_frame = release_frame;
    if (config->mode == ANOMALY_PERSON_RELEVANCE_OFF) return true;
    if (backend == NULL || retain_frame == NULL || release_frame == NULL ||
        !anomaly_person_relevance_model_identity_valid(model)) {
        return false;
    }
    if (pthread_mutex_init(&scheduler->lock, NULL) != 0) return false;
    if (pthread_cond_init(&scheduler->cond, NULL) != 0) {
        pthread_mutex_destroy(&scheduler->lock);
        return false;
    }
    scheduler->sync_ready = true;
    if (pthread_create(&scheduler->thread, NULL, worker_main, scheduler) != 0) {
        pthread_cond_destroy(&scheduler->cond);
        pthread_mutex_destroy(&scheduler->lock);
        scheduler->sync_ready = false;
        return false;
    }
    scheduler->thread_started = true;
    return true;
}

bool anomaly_person_scheduler_offer(
        anomaly_person_relevance_scheduler_t *scheduler,
        anomaly_person_scheduler_pressure_t pressure,
        void *borrowed_frame,
        const anomaly_person_scheduler_snapshot_t *snapshot) {
    if (scheduler == NULL ||
        scheduler->config.mode == ANOMALY_PERSON_RELEVANCE_OFF) {
        return false;
    }
    atomic_fetch_add_explicit(&scheduler->offered, 1u, memory_order_relaxed);
    if (pressure != ANOMALY_PERSON_PRESSURE_NORMAL) {
        atomic_fetch_add_explicit(
                &scheduler->pressure_drops, 1u, memory_order_relaxed);
        return false;
    }
    if (!scheduler->sync_ready || borrowed_frame == NULL ||
        !snapshot_valid(snapshot)) {
        return false;
    }
    if (pthread_mutex_trylock(&scheduler->lock) != 0) {
        atomic_fetch_add_explicit(
                &scheduler->contention_drops, 1u, memory_order_relaxed);
        return false;
    }
    if (scheduler->stop) {
        pthread_mutex_unlock(&scheduler->lock);
        return false;
    }
    void *retained = scheduler->retain_frame(borrowed_frame);
    if (retained == NULL) {
        pthread_mutex_unlock(&scheduler->lock);
        return false;
    }
    if (scheduler->pending_valid) {
        release_work(scheduler, &scheduler->pending);
        atomic_fetch_add_explicit(
                &scheduler->replacements, 1u, memory_order_relaxed);
    }
    scheduler->pending.snapshot = *snapshot;
    scheduler->pending.frame = retained;
    scheduler->pending_valid = true;
    atomic_fetch_add_explicit(&scheduler->admitted, 1u, memory_order_relaxed);
    pthread_cond_signal(&scheduler->cond);
    pthread_mutex_unlock(&scheduler->lock);
    return true;
}

static int matching_candidate_index(
        const anomaly_person_scheduler_snapshot_t *snapshot,
        uint64_t track_id,
        uint64_t track_revision) {
    for (size_t i = 0; i < snapshot->candidate_count; i++) {
        if (snapshot->candidates[i].track_id == track_id &&
            snapshot->candidates[i].track_revision == track_revision) {
            return (int)i;
        }
    }
    return -1;
}

anomaly_person_relevance_application_t anomaly_person_scheduler_apply_latest(
        anomaly_person_relevance_scheduler_t *scheduler,
        const anomaly_person_scheduler_snapshot_t *current,
        int64_t maximum_source_age_us,
        anomaly_target_observation_t *observations,
        size_t observation_count) {
    anomaly_person_relevance_application_t neutral = {0};
    if (scheduler == NULL || current == NULL ||
        scheduler->config.mode == ANOMALY_PERSON_RELEVANCE_OFF ||
        !scheduler->sync_ready) {
        return neutral;
    }
    if (pthread_mutex_trylock(&scheduler->lock) != 0) return neutral;
    if (!scheduler->completed_valid) {
        pthread_mutex_unlock(&scheduler->lock);
        return neutral;
    }
    anomaly_person_scheduler_work_t result = scheduler->completed_result;
    memset(&scheduler->completed_result, 0, sizeof(scheduler->completed_result));
    scheduler->completed_valid = false;
    pthread_mutex_unlock(&scheduler->lock);

    bool stale = !snapshot_valid(current) ||
                 result.snapshot.generation != current->generation ||
                 result.snapshot.frame_sequence > current->frame_sequence ||
                 (maximum_source_age_us >= 0 &&
                  current->source_timestamp_us > result.snapshot.source_timestamp_us &&
                  current->source_timestamp_us - result.snapshot.source_timestamp_us >
                          maximum_source_age_us);
    anomaly_person_relevance_candidate_batch_t mapped;
    anomaly_person_relevance_candidate_batch_init(&mapped);
    anomaly_person_relevance_decision_batch_t mapped_decisions;
    memset(&mapped_decisions, 0, sizeof(mapped_decisions));
    if (!stale) {
        for (size_t i = 0; i < result.snapshot.candidate_count; i++) {
            const anomaly_person_scheduler_candidate_t *prior =
                    &result.snapshot.candidates[i];
            int current_index = matching_candidate_index(
                    current, prior->track_id, prior->track_revision);
            if (current_index < 0) continue;
            anomaly_person_relevance_candidate_t candidate =
                    current->candidates[current_index].candidate;
            if (!anomaly_person_relevance_candidate_batch_append(&mapped, &candidate)) {
                continue;
            }
            for (size_t j = 0; j < result.decisions.count; j++) {
                if (result.decisions.decisions[j].candidate_id !=
                    prior->candidate.candidate_id) {
                    continue;
                }
                anomaly_person_relevance_decision_t decision =
                        result.decisions.decisions[j];
                decision.candidate_id = candidate.candidate_id;
                decision.observation_index = candidate.observation_index;
                decision.provenance_mask = candidate.provenance_mask;
                mapped_decisions.decisions[mapped_decisions.count++] = decision;
                break;
            }
        }
        stale = mapped.count == 0u;
    }
    if (stale) {
        atomic_fetch_add_explicit(
                &scheduler->stale_discarded, 1u, memory_order_relaxed);
        return neutral;
    }
    return anomaly_person_relevance_apply(
            &scheduler->config,
            &mapped,
            &mapped_decisions,
            observations,
            observation_count);
}

anomaly_person_relevance_application_t anomaly_person_scheduler_consume_latest_boxes(
        anomaly_person_relevance_scheduler_t *scheduler,
        const anomaly_person_scheduler_snapshot_t *current,
        int64_t maximum_source_age_us,
        anomaly_box_t *boxes,
        size_t box_count) {
    anomaly_person_relevance_application_t neutral = {0};
    if (scheduler == NULL || current == NULL || boxes == NULL ||
        scheduler->config.mode == ANOMALY_PERSON_RELEVANCE_OFF ||
        !scheduler->sync_ready) {
        return neutral;
    }
    if (pthread_mutex_trylock(&scheduler->lock) != 0) return neutral;
    if (!scheduler->completed_valid) {
        pthread_mutex_unlock(&scheduler->lock);
        return neutral;
    }
    anomaly_person_scheduler_work_t result = scheduler->completed_result;
    memset(&scheduler->completed_result, 0, sizeof(scheduler->completed_result));
    scheduler->completed_valid = false;
    pthread_mutex_unlock(&scheduler->lock);

    bool stale = !snapshot_valid(current) ||
                 result.snapshot.generation != current->generation ||
                 result.snapshot.frame_sequence > current->frame_sequence ||
                 (maximum_source_age_us >= 0 &&
                  current->source_timestamp_us > result.snapshot.source_timestamp_us &&
                  current->source_timestamp_us - result.snapshot.source_timestamp_us >
                          maximum_source_age_us);
    bool identity_match = false;
    if (!stale) {
        for (size_t i = 0; i < result.snapshot.candidate_count; i++) {
            if (matching_candidate_index(
                        current,
                        result.snapshot.candidates[i].track_id,
                        result.snapshot.candidates[i].track_revision) >= 0) {
                identity_match = true;
                break;
            }
        }
        stale = !identity_match;
    }
    if (!stale && scheduler->config.mode == ANOMALY_PERSON_RELEVANCE_POSITIVE_ONLY) {
        for (size_t i = 0; i < result.snapshot.candidate_count; i++) {
            const anomaly_person_scheduler_candidate_t *prior =
                    &result.snapshot.candidates[i];
            int current_index = matching_candidate_index(
                    current, prior->track_id, prior->track_revision);
            if (current_index < 0) continue;
            const anomaly_person_relevance_candidate_t *candidate =
                    &current->candidates[current_index].candidate;
            if (candidate->observation_index >= box_count) continue;
            for (size_t j = 0; j < result.decisions.count; j++) {
                const anomaly_person_relevance_decision_t *decision =
                        &result.decisions.decisions[j];
                if (decision->candidate_id != prior->candidate.candidate_id ||
                    decision->kind != ANOMALY_PERSON_RELEVANCE_DECISION_POSITIVE ||
                    decision->evidence_id == 0u ||
                    decision->observation_index != prior->candidate.observation_index ||
                    decision->provenance_mask != prior->candidate.provenance_mask ||
                    !isfinite(decision->person_confidence) ||
                    decision->person_confidence <
                            scheduler->config.minimum_person_confidence ||
                    decision->person_confidence > 1.0f) {
                    continue;
                }
                anomaly_box_t *box = &boxes[candidate->observation_index];
                float bonus = scheduler->config.maximum_confidence_bonus *
                              decision->person_confidence;
                if (bonus > 1.0f - box->weight) bonus = 1.0f - box->weight;
                if (bonus > 0.0f) {
                    box->weight += bonus;
                    neutral.observation_count += 1u;
                    neutral.total_confidence_bonus += bonus;
                    atomic_fetch_add_explicit(
                            &scheduler->bonuses_applied, 1u, memory_order_relaxed);
                }
                break;
            }
        }
    } else if (!stale) {
        // SHADOW deliberately consumes and records completed evidence without
        // touching geometry, count, algorithm, color, or weight.
        return neutral;
    }
    if (stale) {
        atomic_fetch_add_explicit(
                &scheduler->stale_discarded, 1u, memory_order_relaxed);
    }
    return neutral;
}

void anomaly_person_scheduler_metrics(
        const anomaly_person_relevance_scheduler_t *scheduler,
        anomaly_person_scheduler_metrics_t *metrics_out) {
    if (metrics_out == NULL) return;
    memset(metrics_out, 0, sizeof(*metrics_out));
    if (scheduler == NULL) return;
    metrics_out->offered = atomic_load_explicit(&scheduler->offered, memory_order_relaxed);
    metrics_out->admitted = atomic_load_explicit(&scheduler->admitted, memory_order_relaxed);
    metrics_out->contention_drops = atomic_load_explicit(&scheduler->contention_drops, memory_order_relaxed);
    metrics_out->pressure_drops = atomic_load_explicit(&scheduler->pressure_drops, memory_order_relaxed);
    metrics_out->replacements = atomic_load_explicit(&scheduler->replacements, memory_order_relaxed);
    metrics_out->completed = atomic_load_explicit(&scheduler->completed, memory_order_relaxed);
    metrics_out->stale_discarded = atomic_load_explicit(&scheduler->stale_discarded, memory_order_relaxed);
    metrics_out->backend_failures = atomic_load_explicit(&scheduler->backend_failures, memory_order_relaxed);
    metrics_out->positive_decisions = atomic_load_explicit(&scheduler->positive_decisions, memory_order_relaxed);
    metrics_out->bonuses_applied = atomic_load_explicit(&scheduler->bonuses_applied, memory_order_relaxed);
    metrics_out->inference_wall_time_us = atomic_load_explicit(&scheduler->inference_wall_time_us, memory_order_relaxed);
}

void anomaly_person_scheduler_destroy(
        anomaly_person_relevance_scheduler_t *scheduler) {
    if (scheduler == NULL) return;
    if (!scheduler->sync_ready) {
        memset(scheduler, 0, sizeof(*scheduler));
        return;
    }
    pthread_mutex_lock(&scheduler->lock);
    scheduler->stop = true;
    pthread_cond_signal(&scheduler->cond);
    pthread_mutex_unlock(&scheduler->lock);
    if (scheduler->thread_started) pthread_join(scheduler->thread, NULL);
    pthread_mutex_lock(&scheduler->lock);
    if (scheduler->pending_valid) release_work(scheduler, &scheduler->pending);
    if (scheduler->completed_valid) release_work(scheduler, &scheduler->completed_result);
    pthread_mutex_unlock(&scheduler->lock);
    pthread_cond_destroy(&scheduler->cond);
    pthread_mutex_destroy(&scheduler->lock);
    memset(scheduler, 0, sizeof(*scheduler));
}
