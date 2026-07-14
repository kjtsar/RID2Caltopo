#ifndef ANOMALY_PERSON_RELEVANCE_H
#define ANOMALY_PERSON_RELEVANCE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "anomaly_target_observations.h"

#define ANOMALY_PERSON_RELEVANCE_MAX_CANDIDATES 8u
#define ANOMALY_PERSON_RELEVANCE_MAX_CONFIDENCE_BONUS 0.25f

typedef enum {
    ANOMALY_PERSON_RELEVANCE_OFF = 0,
    ANOMALY_PERSON_RELEVANCE_SHADOW = 1,
    ANOMALY_PERSON_RELEVANCE_POSITIVE_ONLY = 2,
} anomaly_person_relevance_mode_t;

typedef enum {
    ANOMALY_PERSON_PROVENANCE_TARGET_COLOR = 1u << 0,
    ANOMALY_PERSON_PROVENANCE_COLOR_UNIQUENESS = 1u << 1,
    ANOMALY_PERSON_PROVENANCE_THERMAL_IR = 1u << 2,
} anomaly_person_relevance_provenance_t;

#define ANOMALY_PERSON_PROVENANCE_ALL \
    (ANOMALY_PERSON_PROVENANCE_TARGET_COLOR | \
     ANOMALY_PERSON_PROVENANCE_COLOR_UNIQUENESS | \
     ANOMALY_PERSON_PROVENANCE_THERMAL_IR)

typedef struct {
    anomaly_person_relevance_mode_t mode;
    float minimum_person_confidence;
    float maximum_confidence_bonus;
} anomaly_person_relevance_config_t;

typedef struct {
    // Strings are caller-owned and must remain valid for the backend call.
    const char *model_name;
    const char *model_version;
    const char *model_sha256;
    const char *input_tensor;
    const char *quantization;
    const char *runtime_name;
    const char *runtime_version;
    const char *accelerator;
} anomaly_person_relevance_model_identity_t;

typedef struct {
    uint32_t candidate_id;
    uint32_t observation_index;
    uint32_t provenance_mask;
    float left_norm;
    float top_norm;
    float right_norm;
    float bottom_norm;
} anomaly_person_relevance_candidate_t;

typedef struct {
    size_t count;
    anomaly_person_relevance_candidate_t
            candidates[ANOMALY_PERSON_RELEVANCE_MAX_CANDIDATES];
} anomaly_person_relevance_candidate_batch_t;

typedef struct {
    uint32_t candidate_id;
    uint64_t evidence_id;
    bool valid;
    float person_confidence;
} anomaly_person_relevance_backend_result_t;

typedef struct {
    size_t count;
    anomaly_person_relevance_backend_result_t
            results[ANOMALY_PERSON_RELEVANCE_MAX_CANDIDATES];
} anomaly_person_relevance_backend_result_batch_t;

typedef bool (*anomaly_person_relevance_backend_fn)(
        void *context,
        const anomaly_person_relevance_model_identity_t *model,
        const anomaly_person_relevance_candidate_batch_t *candidates,
        anomaly_person_relevance_backend_result_batch_t *results_out);

typedef enum {
    ANOMALY_PERSON_RELEVANCE_DECISION_MISSING = 0,
    ANOMALY_PERSON_RELEVANCE_DECISION_POSITIVE = 1,
    ANOMALY_PERSON_RELEVANCE_DECISION_LOW_CONFIDENCE = 2,
    ANOMALY_PERSON_RELEVANCE_DECISION_INVALID = 3,
    ANOMALY_PERSON_RELEVANCE_DECISION_BACKEND_UNAVAILABLE = 4,
} anomaly_person_relevance_decision_kind_t;

typedef struct {
    uint32_t candidate_id;
    uint32_t observation_index;
    uint32_t provenance_mask;
    uint64_t evidence_id;
    anomaly_person_relevance_decision_kind_t kind;
    float person_confidence;
} anomaly_person_relevance_decision_t;

typedef struct {
    size_t count;
    anomaly_person_relevance_decision_t
            decisions[ANOMALY_PERSON_RELEVANCE_MAX_CANDIDATES];
} anomaly_person_relevance_decision_batch_t;

typedef struct {
    size_t observation_count;
    float total_confidence_bonus;
} anomaly_person_relevance_application_t;

void anomaly_person_relevance_candidate_batch_init(
        anomaly_person_relevance_candidate_batch_t *batch);

bool anomaly_person_relevance_candidate_batch_append(
        anomaly_person_relevance_candidate_batch_t *batch,
        const anomaly_person_relevance_candidate_t *candidate);

bool anomaly_person_relevance_model_identity_valid(
        const anomaly_person_relevance_model_identity_t *model);

void anomaly_person_relevance_evaluate(
        const anomaly_person_relevance_config_t *config,
        const anomaly_person_relevance_model_identity_t *model,
        const anomaly_person_relevance_candidate_batch_t *candidates,
        anomaly_person_relevance_backend_fn backend,
        void *backend_context,
        anomaly_person_relevance_decision_batch_t *decisions_out);

// Applies at most one strongest bonus per observation and per evidence ID.
anomaly_person_relevance_application_t anomaly_person_relevance_apply(
        const anomaly_person_relevance_config_t *config,
        const anomaly_person_relevance_candidate_batch_t *candidates,
        const anomaly_person_relevance_decision_batch_t *decisions,
        anomaly_target_observation_t *observations,
        size_t observation_count);

#endif
