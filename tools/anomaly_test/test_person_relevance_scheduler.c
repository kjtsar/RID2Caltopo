#include "anomaly_person_relevance_scheduler.h"

#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

typedef struct {
    atomic_int references;
} fake_frame_t;

typedef struct {
    pthread_mutex_t lock;
    pthread_cond_t cond;
    bool block_backend;
    atomic_bool backend_entered;
    uint32_t seen_ids[8];
    size_t seen_count;
} fake_backend_t;

static const anomaly_person_relevance_model_identity_t MODEL = {
    .model_name = "fake-person",
    .model_version = "1",
    .model_sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    .input_tensor = "rgb",
    .quantization = "none",
    .runtime_name = "test",
    .runtime_version = "1",
    .accelerator = "cpu",
};

static void sleep_ms(long milliseconds) {
    struct timespec delay = {
        .tv_sec = milliseconds / 1000,
        .tv_nsec = (milliseconds % 1000) * 1000000,
    };
    nanosleep(&delay, NULL);
}

static void *retain_frame(void *frame) {
    fake_frame_t *fake = frame;
    atomic_fetch_add(&fake->references, 1);
    return frame;
}

static void release_frame(void *frame) {
    fake_frame_t *fake = frame;
    atomic_fetch_sub(&fake->references, 1);
}

static bool fake_backend(
        void *context,
        void *frame,
        const anomaly_person_relevance_model_identity_t *model,
        const anomaly_person_relevance_candidate_batch_t *candidates,
        anomaly_person_relevance_decision_batch_t *decisions_out) {
    fake_backend_t *backend = context;
    assert(frame != NULL);
    assert(model == &MODEL || strcmp(model->model_name, MODEL.model_name) == 0);
    pthread_mutex_lock(&backend->lock);
    atomic_store(&backend->backend_entered, true);
    pthread_cond_broadcast(&backend->cond);
    while (backend->block_backend) pthread_cond_wait(&backend->cond, &backend->lock);
    for (size_t i = 0; i < candidates->count; i++) {
        backend->seen_ids[backend->seen_count++] = candidates->candidates[i].candidate_id;
    }
    pthread_mutex_unlock(&backend->lock);
    memset(decisions_out, 0, sizeof(*decisions_out));
    decisions_out->count = candidates->count;
    for (size_t i = 0; i < candidates->count; i++) {
        decisions_out->decisions[i] = (anomaly_person_relevance_decision_t) {
            .candidate_id = candidates->candidates[i].candidate_id,
            .observation_index = candidates->candidates[i].observation_index,
            .provenance_mask = candidates->candidates[i].provenance_mask,
            .evidence_id = 100u + candidates->candidates[i].candidate_id,
            .kind = ANOMALY_PERSON_RELEVANCE_DECISION_POSITIVE,
            .person_confidence = 0.8f,
        };
    }
    return true;
}

static anomaly_person_scheduler_snapshot_t snapshot(
        uint64_t generation,
        uint64_t sequence,
        int64_t timestamp_us,
        uint32_t candidate_id,
        uint64_t revision) {
    anomaly_person_scheduler_snapshot_t value;
    memset(&value, 0, sizeof(value));
    value.generation = generation;
    value.frame_sequence = sequence;
    value.source_timestamp_us = timestamp_us;
    value.candidate_count = 1;
    value.candidates[0] = (anomaly_person_scheduler_candidate_t) {
        .track_id = 7,
        .track_revision = revision,
        .candidate = {
            .candidate_id = candidate_id,
            .observation_index = 0,
            .provenance_mask = ANOMALY_PERSON_PROVENANCE_COLOR_UNIQUENESS,
            .left_norm = 0.1f,
            .top_norm = 0.1f,
            .right_norm = 0.2f,
            .bottom_norm = 0.3f,
        },
    };
    return value;
}

static void init_backend(fake_backend_t *backend) {
    memset(backend, 0, sizeof(*backend));
    assert(pthread_mutex_init(&backend->lock, NULL) == 0);
    assert(pthread_cond_init(&backend->cond, NULL) == 0);
}

static void destroy_backend(fake_backend_t *backend) {
    pthread_cond_destroy(&backend->cond);
    pthread_mutex_destroy(&backend->lock);
}

static void wait_for_completed(
        anomaly_person_relevance_scheduler_t *scheduler,
        uint64_t count) {
    for (int i = 0; i < 500; i++) {
        anomaly_person_scheduler_metrics_t metrics;
        anomaly_person_scheduler_metrics(scheduler, &metrics);
        if (metrics.completed >= count) return;
        sleep_ms(1);
    }
    assert(!"person scheduler timed out");
}

static void test_off_is_identity_and_does_not_retain(void) {
    anomaly_person_relevance_scheduler_t scheduler;
    anomaly_person_relevance_config_t config = {
        .mode = ANOMALY_PERSON_RELEVANCE_OFF,
        .minimum_person_confidence = 0.5f,
        .maximum_confidence_bonus = 0.2f,
    };
    fake_frame_t frame = {0};
    assert(anomaly_person_scheduler_init(
            &scheduler, &config, NULL, NULL, NULL, retain_frame, release_frame));
    anomaly_person_scheduler_snapshot_t offer = snapshot(1, 1, 1000, 1, 1);
    assert(!anomaly_person_scheduler_offer(
            &scheduler, ANOMALY_PERSON_PRESSURE_NORMAL, &frame, &offer));
    assert(atomic_load(&frame.references) == 0);
    anomaly_person_scheduler_metrics_t metrics;
    anomaly_person_scheduler_metrics(&scheduler, &metrics);
    assert(metrics.offered == 0);
    anomaly_person_scheduler_destroy(&scheduler);
}

static void test_shadow_is_neutral_and_positive_only_applies(void) {
    for (int pass = 0; pass < 2; pass++) {
        fake_backend_t backend;
        init_backend(&backend);
        fake_frame_t frame = {0};
        anomaly_person_relevance_config_t config = {
            .mode = pass == 0 ? ANOMALY_PERSON_RELEVANCE_SHADOW
                              : ANOMALY_PERSON_RELEVANCE_POSITIVE_ONLY,
            .minimum_person_confidence = 0.5f,
            .maximum_confidence_bonus = 0.2f,
        };
        anomaly_person_relevance_scheduler_t scheduler;
        assert(anomaly_person_scheduler_init(
                &scheduler, &config, &MODEL, fake_backend, &backend,
                retain_frame, release_frame));
        anomaly_person_scheduler_snapshot_t offer = snapshot(1, 10, 1000, 3, 4);
        assert(anomaly_person_scheduler_offer(
                &scheduler, ANOMALY_PERSON_PRESSURE_NORMAL, &frame, &offer));
        wait_for_completed(&scheduler, 1);
        anomaly_person_scheduler_snapshot_t current = snapshot(1, 11, 2000, 9, 4);
        anomaly_target_observation_t observation = {.valid = true, .confidence = 0.4f};
        anomaly_person_relevance_application_t applied =
                anomaly_person_scheduler_apply_latest(
                        &scheduler, &current, 5000, &observation, 1);
        if (pass == 0) {
            assert(applied.observation_count == 0);
            assert(observation.confidence == 0.4f);
        } else {
            assert(applied.observation_count == 1);
            assert(observation.confidence > 0.55f && observation.confidence < 0.57f);
        }
        anomaly_person_scheduler_destroy(&scheduler);
        assert(atomic_load(&frame.references) == 0);
        destroy_backend(&backend);
    }
}

static void test_pressure_and_stale_results_are_neutral(void) {
    fake_backend_t backend;
    init_backend(&backend);
    fake_frame_t frame = {0};
    anomaly_person_relevance_config_t config = {
        .mode = ANOMALY_PERSON_RELEVANCE_POSITIVE_ONLY,
        .minimum_person_confidence = 0.5f,
        .maximum_confidence_bonus = 0.2f,
    };
    anomaly_person_relevance_scheduler_t scheduler;
    assert(anomaly_person_scheduler_init(
            &scheduler, &config, &MODEL, fake_backend, &backend,
            retain_frame, release_frame));
    anomaly_person_scheduler_snapshot_t offer = snapshot(2, 20, 1000, 4, 2);
    assert(!anomaly_person_scheduler_offer(
            &scheduler, ANOMALY_PERSON_PRESSURE_ELEVATED, &frame, &offer));
    assert(atomic_load(&frame.references) == 0);
    assert(anomaly_person_scheduler_offer(
            &scheduler, ANOMALY_PERSON_PRESSURE_NORMAL, &frame, &offer));
    wait_for_completed(&scheduler, 1);
    anomaly_person_scheduler_snapshot_t current = snapshot(3, 21, 2000, 4, 2);
    anomaly_target_observation_t observation = {.valid = true, .confidence = 0.4f};
    anomaly_person_relevance_application_t applied =
            anomaly_person_scheduler_apply_latest(
                    &scheduler, &current, 5000, &observation, 1);
    assert(applied.observation_count == 0);
    assert(observation.confidence == 0.4f);
    anomaly_person_scheduler_metrics_t metrics;
    anomaly_person_scheduler_metrics(&scheduler, &metrics);
    assert(metrics.pressure_drops == 1);
    assert(metrics.stale_discarded == 1);
    anomaly_person_scheduler_destroy(&scheduler);
    destroy_backend(&backend);
}

static void test_offer_drops_immediately_on_contention(void) {
    fake_backend_t backend;
    init_backend(&backend);
    fake_frame_t frame = {0};
    anomaly_person_relevance_config_t config = {
        .mode = ANOMALY_PERSON_RELEVANCE_SHADOW,
        .minimum_person_confidence = 0.5f,
        .maximum_confidence_bonus = 0.2f,
    };
    anomaly_person_relevance_scheduler_t scheduler;
    assert(anomaly_person_scheduler_init(
            &scheduler, &config, &MODEL, fake_backend, &backend,
            retain_frame, release_frame));
    anomaly_person_scheduler_snapshot_t offer = snapshot(1, 1, 1000, 1, 1);
    pthread_mutex_lock(&scheduler.lock);
    assert(!anomaly_person_scheduler_offer(
            &scheduler, ANOMALY_PERSON_PRESSURE_NORMAL, &frame, &offer));
    pthread_mutex_unlock(&scheduler.lock);
    assert(atomic_load(&frame.references) == 0);
    anomaly_person_scheduler_metrics_t metrics;
    anomaly_person_scheduler_metrics(&scheduler, &metrics);
    assert(metrics.contention_drops == 1);
    anomaly_person_scheduler_destroy(&scheduler);
    destroy_backend(&backend);
}

static void test_latest_pending_replaces_and_shutdown_releases(void) {
    fake_backend_t backend;
    init_backend(&backend);
    backend.block_backend = true;
    fake_frame_t frame = {0};
    anomaly_person_relevance_config_t config = {
        .mode = ANOMALY_PERSON_RELEVANCE_SHADOW,
        .minimum_person_confidence = 0.5f,
        .maximum_confidence_bonus = 0.2f,
    };
    anomaly_person_relevance_scheduler_t scheduler;
    assert(anomaly_person_scheduler_init(
            &scheduler, &config, &MODEL, fake_backend, &backend,
            retain_frame, release_frame));
    anomaly_person_scheduler_snapshot_t one = snapshot(1, 1, 1000, 1, 1);
    anomaly_person_scheduler_snapshot_t two = snapshot(1, 2, 2000, 2, 1);
    anomaly_person_scheduler_snapshot_t three = snapshot(1, 3, 3000, 3, 1);
    assert(anomaly_person_scheduler_offer(
            &scheduler, ANOMALY_PERSON_PRESSURE_NORMAL, &frame, &one));
    for (int i = 0; i < 500 && !atomic_load(&backend.backend_entered); i++) {
        sleep_ms(1);
    }
    assert(atomic_load(&backend.backend_entered));
    assert(anomaly_person_scheduler_offer(
            &scheduler, ANOMALY_PERSON_PRESSURE_NORMAL, &frame, &two));
    assert(anomaly_person_scheduler_offer(
            &scheduler, ANOMALY_PERSON_PRESSURE_NORMAL, &frame, &three));
    pthread_mutex_lock(&backend.lock);
    backend.block_backend = false;
    pthread_cond_broadcast(&backend.cond);
    pthread_mutex_unlock(&backend.lock);
    wait_for_completed(&scheduler, 2);
    anomaly_person_scheduler_metrics_t metrics;
    anomaly_person_scheduler_metrics(&scheduler, &metrics);
    assert(metrics.replacements == 1);
    anomaly_person_scheduler_destroy(&scheduler);
    assert(atomic_load(&frame.references) == 0);
    assert(backend.seen_count == 2);
    assert(backend.seen_ids[0] == 1);
    assert(backend.seen_ids[1] == 3);
    destroy_backend(&backend);
}

static void test_box_snapshots_only_select_target_tracks_and_shadow_is_identity(void) {
    anomaly_state_t state = {0};
    anomaly_config_t detector_config = {.min_hits = 2};
    state.target_tracks[0] = (anomaly_target_track_t) {
        .active = true, .publish_confirmed = true, .hit_count = 2,
        .id = 11, .algorithm = ANOMALY_ALGO_COLOR,
        .center_x_norm = 0.15f, .center_y_norm = 0.2f,
    };
    state.target_tracks[1] = (anomaly_target_track_t) {
        .active = true, .publish_confirmed = true, .hit_count = 2,
        .id = 12, .algorithm = ANOMALY_ALGO_THERMAL,
        .center_x_norm = 0.4f, .center_y_norm = 0.4f,
    };
    anomaly_box_t boxes[3] = {
        {
            .left_norm = 0.1f, .top_norm = 0.1f,
            .right_norm = 0.2f, .bottom_norm = 0.3f,
            .weight = 0.4f, .algorithm = ANOMALY_ALGO_COLOR,
        },
        {
            .left_norm = 0.3f, .top_norm = 0.2f,
            .right_norm = 0.5f, .bottom_norm = 0.6f,
            .weight = 0.8f, .algorithm = ANOMALY_ALGO_THERMAL,
        },
        {
            .left_norm = 0.5f, .top_norm = 0.5f,
            .right_norm = 0.6f, .bottom_norm = 0.6f,
            .weight = 1.0f, .algorithm = ANOMALY_ALGO_MOTION,
        },
    };
    anomaly_person_scheduler_snapshot_t offer;
    assert(anomaly_person_scheduler_snapshot_from_boxes(
                   4u, 20u, 1000, &state, &detector_config,
                   boxes, 3u, &offer) == 2u);
    assert(offer.candidates[0].track_id == 12u);
    assert(offer.candidates[1].track_id == 11u);

    fake_backend_t backend;
    init_backend(&backend);
    fake_frame_t frame = {0};
    anomaly_person_relevance_config_t config = {
        .mode = ANOMALY_PERSON_RELEVANCE_SHADOW,
        .minimum_person_confidence = 0.5f,
        .maximum_confidence_bonus = 0.2f,
    };
    anomaly_person_relevance_scheduler_t scheduler;
    assert(anomaly_person_scheduler_init(
            &scheduler, &config, &MODEL, fake_backend, &backend,
            retain_frame, release_frame));
    assert(anomaly_person_scheduler_offer(
            &scheduler, ANOMALY_PERSON_PRESSURE_NORMAL, &frame, &offer));
    wait_for_completed(&scheduler, 1);
    anomaly_person_scheduler_snapshot_t current;
    assert(anomaly_person_scheduler_snapshot_from_boxes(
                   4u, 21u, 2000, &state, &detector_config,
                   boxes, 3u, &current) == 2u);
    anomaly_box_t before[3];
    memcpy(before, boxes, sizeof(boxes));
    anomaly_person_relevance_application_t applied =
            anomaly_person_scheduler_consume_latest_boxes(
                    &scheduler, &current, 5000, boxes, 3u);
    assert(applied.observation_count == 0u);
    assert(memcmp(before, boxes, sizeof(boxes)) == 0);
    anomaly_person_scheduler_destroy(&scheduler);
    assert(atomic_load(&frame.references) == 0);
    destroy_backend(&backend);
}

static void test_box_positive_only_bonus_is_bounded_and_stale_is_neutral(void) {
    fake_backend_t backend;
    init_backend(&backend);
    fake_frame_t frame = {0};
    anomaly_person_relevance_config_t config = {
        .mode = ANOMALY_PERSON_RELEVANCE_POSITIVE_ONLY,
        .minimum_person_confidence = 0.5f,
        .maximum_confidence_bonus = 0.2f,
    };
    anomaly_person_relevance_scheduler_t scheduler;
    assert(anomaly_person_scheduler_init(
            &scheduler, &config, &MODEL, fake_backend, &backend,
            retain_frame, release_frame));
    anomaly_box_t box = {
        .left_norm = 0.1f, .top_norm = 0.1f,
        .right_norm = 0.2f, .bottom_norm = 0.3f,
        .weight = 0.9f, .algorithm = ANOMALY_ALGO_COLOR,
    };
    anomaly_state_t state = {0};
    anomaly_config_t detector_config = {.min_hits = 2};
    state.target_tracks[0] = (anomaly_target_track_t) {
        .active = true, .publish_confirmed = true, .hit_count = 2,
        .id = 7, .algorithm = ANOMALY_ALGO_COLOR,
        .center_x_norm = 0.15f, .center_y_norm = 0.2f,
    };
    anomaly_person_scheduler_snapshot_t offer;
    assert(anomaly_person_scheduler_snapshot_from_boxes(
                   1u, 10u, 1000, &state, &detector_config,
                   &box, 1u, &offer) == 1u);
    assert(anomaly_person_scheduler_offer(
            &scheduler, ANOMALY_PERSON_PRESSURE_NORMAL, &frame, &offer));
    wait_for_completed(&scheduler, 1);
    anomaly_person_scheduler_snapshot_t current;
    assert(anomaly_person_scheduler_snapshot_from_boxes(
                   1u, 11u, 2000, &state, &detector_config,
                   &box, 1u, &current) == 1u);
    anomaly_person_relevance_application_t applied =
            anomaly_person_scheduler_consume_latest_boxes(
                    &scheduler, &current, 5000, &box, 1u);
    assert(applied.observation_count == 1u);
    assert(box.weight == 1.0f);

    box.weight = 0.4f;
    assert(anomaly_person_scheduler_offer(
            &scheduler, ANOMALY_PERSON_PRESSURE_NORMAL, &frame, &current));
    wait_for_completed(&scheduler, 2);
    state.target_tracks[0].id = 8;
    anomaly_person_scheduler_snapshot_t stale_current;
    assert(anomaly_person_scheduler_snapshot_from_boxes(
                   1u, 12u, 3000, &state, &detector_config,
                   &box, 1u, &stale_current) == 1u);
    applied = anomaly_person_scheduler_consume_latest_boxes(
            &scheduler, &stale_current, 5000, &box, 1u);
    assert(applied.observation_count == 0u);
    assert(box.weight == 0.4f);
    anomaly_person_scheduler_metrics_t metrics;
    anomaly_person_scheduler_metrics(&scheduler, &metrics);
    assert(metrics.bonuses_applied == 1u);
    assert(metrics.stale_discarded == 1u);
    anomaly_person_scheduler_destroy(&scheduler);
    destroy_backend(&backend);
}

int main(void) {
    test_off_is_identity_and_does_not_retain();
    test_shadow_is_neutral_and_positive_only_applies();
    test_pressure_and_stale_results_are_neutral();
    test_offer_drops_immediately_on_contention();
    test_latest_pending_replaces_and_shutdown_releases();
    test_box_snapshots_only_select_target_tracks_and_shadow_is_identity();
    test_box_positive_only_bonus_is_bounded_and_stale_is_neutral();
    puts("person relevance scheduler tests passed");
    return 0;
}
