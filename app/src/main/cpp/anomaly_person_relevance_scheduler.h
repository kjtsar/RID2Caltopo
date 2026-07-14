#ifndef ANOMALY_PERSON_RELEVANCE_SCHEDULER_H
#define ANOMALY_PERSON_RELEVANCE_SCHEDULER_H

#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "anomaly_person_relevance.h"

#define ANOMALY_PERSON_SCHEDULER_MAX_CANDIDATES 2u

typedef enum {
    ANOMALY_PERSON_PRESSURE_NORMAL = 0,
    ANOMALY_PERSON_PRESSURE_ELEVATED = 1,
} anomaly_person_scheduler_pressure_t;

typedef struct {
    uint64_t track_id;
    uint64_t track_revision;
    anomaly_person_relevance_candidate_t candidate;
} anomaly_person_scheduler_candidate_t;

typedef struct {
    uint64_t generation;
    uint64_t frame_sequence;
    int64_t source_timestamp_us;
    size_t candidate_count;
    anomaly_person_scheduler_candidate_t
            candidates[ANOMALY_PERSON_SCHEDULER_MAX_CANDIDATES];
} anomaly_person_scheduler_snapshot_t;

typedef void *(*anomaly_person_scheduler_frame_retain_fn)(void *frame);
typedef void (*anomaly_person_scheduler_frame_release_fn)(void *frame);

typedef bool (*anomaly_person_scheduler_backend_fn)(
        void *context,
        void *retained_frame,
        const anomaly_person_relevance_model_identity_t *model,
        const anomaly_person_relevance_candidate_batch_t *candidates,
        anomaly_person_relevance_decision_batch_t *decisions_out);

typedef struct {
    uint64_t offered;
    uint64_t admitted;
    uint64_t contention_drops;
    uint64_t pressure_drops;
    uint64_t replacements;
    uint64_t completed;
    uint64_t stale_discarded;
    uint64_t backend_failures;
    uint64_t positive_decisions;
    uint64_t bonuses_applied;
    uint64_t inference_wall_time_us;
} anomaly_person_scheduler_metrics_t;

typedef struct {
    anomaly_person_scheduler_snapshot_t snapshot;
    anomaly_person_relevance_decision_batch_t decisions;
    void *frame;
} anomaly_person_scheduler_work_t;

typedef struct {
    pthread_mutex_t lock;
    pthread_cond_t cond;
    pthread_t thread;
    bool sync_ready;
    bool thread_started;
    bool stop;
    bool pending_valid;
    bool completed_valid;
    anomaly_person_relevance_config_t config;
    anomaly_person_relevance_model_identity_t model;
    anomaly_person_scheduler_backend_fn backend;
    void *backend_context;
    anomaly_person_scheduler_frame_retain_fn retain_frame;
    anomaly_person_scheduler_frame_release_fn release_frame;
    anomaly_person_scheduler_work_t pending;
    anomaly_person_scheduler_work_t completed_result;
    atomic_uint_fast64_t offered;
    atomic_uint_fast64_t admitted;
    atomic_uint_fast64_t contention_drops;
    atomic_uint_fast64_t pressure_drops;
    atomic_uint_fast64_t replacements;
    atomic_uint_fast64_t completed;
    atomic_uint_fast64_t stale_discarded;
    atomic_uint_fast64_t backend_failures;
    atomic_uint_fast64_t positive_decisions;
    atomic_uint_fast64_t bonuses_applied;
    atomic_uint_fast64_t inference_wall_time_us;
} anomaly_person_relevance_scheduler_t;

size_t anomaly_person_scheduler_snapshot_from_boxes(
        uint64_t generation,
        uint64_t frame_sequence,
        int64_t source_timestamp_us,
        const anomaly_state_t *state,
        const anomaly_config_t *config,
        const anomaly_box_t *boxes,
        size_t box_count,
        anomaly_person_scheduler_snapshot_t *snapshot_out);

bool anomaly_person_scheduler_init(
        anomaly_person_relevance_scheduler_t *scheduler,
        const anomaly_person_relevance_config_t *config,
        const anomaly_person_relevance_model_identity_t *model,
        anomaly_person_scheduler_backend_fn backend,
        void *backend_context,
        anomaly_person_scheduler_frame_retain_fn retain_frame,
        anomaly_person_scheduler_frame_release_fn release_frame);

bool anomaly_person_scheduler_offer(
        anomaly_person_relevance_scheduler_t *scheduler,
        anomaly_person_scheduler_pressure_t pressure,
        void *borrowed_frame,
        const anomaly_person_scheduler_snapshot_t *snapshot);

anomaly_person_relevance_application_t anomaly_person_scheduler_apply_latest(
        anomaly_person_relevance_scheduler_t *scheduler,
        const anomaly_person_scheduler_snapshot_t *current,
        int64_t maximum_source_age_us,
        anomaly_target_observation_t *observations,
        size_t observation_count);

anomaly_person_relevance_application_t anomaly_person_scheduler_consume_latest_boxes(
        anomaly_person_relevance_scheduler_t *scheduler,
        const anomaly_person_scheduler_snapshot_t *current,
        int64_t maximum_source_age_us,
        anomaly_box_t *boxes,
        size_t box_count);

void anomaly_person_scheduler_metrics(
        const anomaly_person_relevance_scheduler_t *scheduler,
        anomaly_person_scheduler_metrics_t *metrics_out);

void anomaly_person_scheduler_destroy(
        anomaly_person_relevance_scheduler_t *scheduler);

#endif
