#include "anomaly_person_relevance.h"

#include <math.h>
#include <string.h>

static bool nonempty(const char *value) {
    return value != NULL && value[0] != '\0';
}

static bool sha256_valid(const char *value) {
    if (value == NULL) return false;
    for (size_t i = 0; i < 64u; i++) {
        char ch = value[i];
        bool digit = ch >= '0' && ch <= '9';
        bool lower = ch >= 'a' && ch <= 'f';
        bool upper = ch >= 'A' && ch <= 'F';
        if (!digit && !lower && !upper) return false;
    }
    return value[64] == '\0';
}

static bool config_valid(const anomaly_person_relevance_config_t *config) {
    if (config == NULL) return false;
    if (config->mode != ANOMALY_PERSON_RELEVANCE_OFF &&
        config->mode != ANOMALY_PERSON_RELEVANCE_SHADOW &&
        config->mode != ANOMALY_PERSON_RELEVANCE_POSITIVE_ONLY) {
        return false;
    }
    return isfinite(config->minimum_person_confidence) &&
           config->minimum_person_confidence >= 0.0f &&
           config->minimum_person_confidence <= 1.0f &&
           isfinite(config->maximum_confidence_bonus) &&
           config->maximum_confidence_bonus >= 0.0f &&
           config->maximum_confidence_bonus <=
                   ANOMALY_PERSON_RELEVANCE_MAX_CONFIDENCE_BONUS;
}

static bool candidate_valid(
        const anomaly_person_relevance_candidate_t *candidate) {
    if (candidate == NULL || candidate->candidate_id == 0u ||
        candidate->provenance_mask == 0u ||
        (candidate->provenance_mask & ~ANOMALY_PERSON_PROVENANCE_ALL) != 0u) {
        return false;
    }
    if (!isfinite(candidate->left_norm) ||
        !isfinite(candidate->top_norm) ||
        !isfinite(candidate->right_norm) ||
        !isfinite(candidate->bottom_norm)) {
        return false;
    }
    return candidate->left_norm >= 0.0f &&
           candidate->top_norm >= 0.0f &&
           candidate->right_norm <= 1.0f &&
           candidate->bottom_norm <= 1.0f &&
           candidate->right_norm > candidate->left_norm &&
           candidate->bottom_norm > candidate->top_norm;
}

static int candidate_index_for_id(
        const anomaly_person_relevance_candidate_batch_t *candidates,
        uint32_t candidate_id) {
    if (candidates == NULL ||
        candidates->count > ANOMALY_PERSON_RELEVANCE_MAX_CANDIDATES) {
        return -1;
    }
    for (size_t i = 0; i < candidates->count; i++) {
        if (candidates->candidates[i].candidate_id == candidate_id) {
            return (int)i;
        }
    }
    return -1;
}

static bool candidate_batch_valid(
        const anomaly_person_relevance_candidate_batch_t *candidates) {
    if (candidates == NULL ||
        candidates->count > ANOMALY_PERSON_RELEVANCE_MAX_CANDIDATES) {
        return false;
    }
    for (size_t i = 0; i < candidates->count; i++) {
        if (!candidate_valid(&candidates->candidates[i])) return false;
        for (size_t j = 0; j < i; j++) {
            if (candidates->candidates[i].candidate_id ==
                candidates->candidates[j].candidate_id) {
                return false;
            }
        }
    }
    return true;
}

static void set_all_decision_kinds(
        anomaly_person_relevance_decision_batch_t *decisions,
        anomaly_person_relevance_decision_kind_t kind) {
    for (size_t i = 0; i < decisions->count; i++) {
        decisions->decisions[i].kind = kind;
    }
}

void anomaly_person_relevance_candidate_batch_init(
        anomaly_person_relevance_candidate_batch_t *batch) {
    if (batch != NULL) memset(batch, 0, sizeof(*batch));
}

bool anomaly_person_relevance_candidate_batch_append(
        anomaly_person_relevance_candidate_batch_t *batch,
        const anomaly_person_relevance_candidate_t *candidate) {
    if (batch == NULL || !candidate_valid(candidate) ||
        batch->count >= ANOMALY_PERSON_RELEVANCE_MAX_CANDIDATES) {
        return false;
    }
    if (candidate_index_for_id(batch, candidate->candidate_id) >= 0) {
        return false;
    }
    batch->candidates[batch->count++] = *candidate;
    return true;
}

bool anomaly_person_relevance_model_identity_valid(
        const anomaly_person_relevance_model_identity_t *model) {
    return model != NULL &&
           nonempty(model->model_name) &&
           nonempty(model->model_version) &&
           sha256_valid(model->model_sha256) &&
           nonempty(model->input_tensor) &&
           nonempty(model->quantization) &&
           nonempty(model->runtime_name) &&
           nonempty(model->runtime_version) &&
           nonempty(model->accelerator);
}

void anomaly_person_relevance_evaluate(
        const anomaly_person_relevance_config_t *config,
        const anomaly_person_relevance_model_identity_t *model,
        const anomaly_person_relevance_candidate_batch_t *candidates,
        anomaly_person_relevance_backend_fn backend,
        void *backend_context,
        anomaly_person_relevance_decision_batch_t *decisions_out) {
    if (decisions_out == NULL) return;
    memset(decisions_out, 0, sizeof(*decisions_out));
    if (candidates != NULL &&
        candidates->count <= ANOMALY_PERSON_RELEVANCE_MAX_CANDIDATES) {
        decisions_out->count = candidates->count;
        for (size_t i = 0; i < candidates->count; i++) {
            decisions_out->decisions[i].candidate_id =
                    candidates->candidates[i].candidate_id;
            decisions_out->decisions[i].observation_index =
                    candidates->candidates[i].observation_index;
            decisions_out->decisions[i].provenance_mask =
                    candidates->candidates[i].provenance_mask;
            decisions_out->decisions[i].kind =
                    ANOMALY_PERSON_RELEVANCE_DECISION_MISSING;
        }
    }

    if (config != NULL && config->mode == ANOMALY_PERSON_RELEVANCE_OFF) {
        return;
    }
    if (!config_valid(config) || !candidate_batch_valid(candidates) ||
        !anomaly_person_relevance_model_identity_valid(model)) {
        set_all_decision_kinds(
                decisions_out, ANOMALY_PERSON_RELEVANCE_DECISION_INVALID);
        return;
    }
    if (backend == NULL) {
        set_all_decision_kinds(
                decisions_out,
                ANOMALY_PERSON_RELEVANCE_DECISION_BACKEND_UNAVAILABLE);
        return;
    }

    anomaly_person_relevance_backend_result_batch_t backend_results;
    memset(&backend_results, 0, sizeof(backend_results));
    if (!backend(backend_context, model, candidates, &backend_results)) {
        set_all_decision_kinds(
                decisions_out,
                ANOMALY_PERSON_RELEVANCE_DECISION_BACKEND_UNAVAILABLE);
        return;
    }
    if (backend_results.count > ANOMALY_PERSON_RELEVANCE_MAX_CANDIDATES) {
        set_all_decision_kinds(
                decisions_out, ANOMALY_PERSON_RELEVANCE_DECISION_INVALID);
        return;
    }

    bool seen[ANOMALY_PERSON_RELEVANCE_MAX_CANDIDATES] = {false};
    for (size_t i = 0; i < backend_results.count; i++) {
        const anomaly_person_relevance_backend_result_t *result =
                &backend_results.results[i];
        int index = candidate_index_for_id(candidates, result->candidate_id);
        if (index < 0) continue;
        anomaly_person_relevance_decision_t *decision =
                &decisions_out->decisions[index];
        if (seen[index]) {
            decision->kind = ANOMALY_PERSON_RELEVANCE_DECISION_INVALID;
            decision->evidence_id = 0u;
            decision->person_confidence = 0.0f;
            continue;
        }
        seen[index] = true;
        if (!result->valid || result->evidence_id == 0u ||
            !isfinite(result->person_confidence) ||
            result->person_confidence < 0.0f ||
            result->person_confidence > 1.0f) {
            decision->kind = ANOMALY_PERSON_RELEVANCE_DECISION_INVALID;
            continue;
        }
        decision->evidence_id = result->evidence_id;
        decision->person_confidence = result->person_confidence;
        decision->kind = result->person_confidence <
                                 config->minimum_person_confidence
                ? ANOMALY_PERSON_RELEVANCE_DECISION_LOW_CONFIDENCE
                : ANOMALY_PERSON_RELEVANCE_DECISION_POSITIVE;
    }
}

anomaly_person_relevance_application_t anomaly_person_relevance_apply(
        const anomaly_person_relevance_config_t *config,
        const anomaly_person_relevance_candidate_batch_t *candidates,
        const anomaly_person_relevance_decision_batch_t *decisions,
        anomaly_target_observation_t *observations,
        size_t observation_count) {
    anomaly_person_relevance_application_t applied = {0};
    if (config == NULL ||
        config->mode != ANOMALY_PERSON_RELEVANCE_POSITIVE_ONLY) {
        return applied;
    }
    if (!config_valid(config) || !candidate_batch_valid(candidates) ||
        decisions == NULL || observations == NULL ||
        decisions->count > ANOMALY_PERSON_RELEVANCE_MAX_CANDIDATES) {
        return applied;
    }

    bool selected[ANOMALY_PERSON_RELEVANCE_MAX_CANDIDATES] = {false};
    for (size_t i = 0; i < decisions->count; i++) {
        const anomaly_person_relevance_decision_t *decision =
                &decisions->decisions[i];
        int candidate_index = candidate_index_for_id(
                candidates, decision->candidate_id);
        if (candidate_index < 0 ||
            decision->kind != ANOMALY_PERSON_RELEVANCE_DECISION_POSITIVE ||
            decision->evidence_id == 0u ||
            !isfinite(decision->person_confidence) ||
            decision->person_confidence < config->minimum_person_confidence ||
            decision->person_confidence > 1.0f) {
            continue;
        }
        const anomaly_person_relevance_candidate_t *candidate =
                &candidates->candidates[candidate_index];
        if (decision->observation_index != candidate->observation_index ||
            decision->provenance_mask != candidate->provenance_mask ||
            candidate->observation_index >= observation_count ||
            !observations[candidate->observation_index].valid) {
            continue;
        }

        selected[i] = true;
        for (size_t j = 0; j < i; j++) {
            if (!selected[j]) continue;
            const anomaly_person_relevance_decision_t *prior =
                    &decisions->decisions[j];
            int prior_candidate_index = candidate_index_for_id(
                    candidates, prior->candidate_id);
            if (prior_candidate_index < 0) continue;
            const anomaly_person_relevance_candidate_t *prior_candidate =
                    &candidates->candidates[prior_candidate_index];
            bool same_observation = prior_candidate->observation_index ==
                                    candidate->observation_index;
            bool same_evidence = prior->evidence_id == decision->evidence_id;
            if (!same_observation && !same_evidence) continue;

            bool current_wins = decision->person_confidence >
                                prior->person_confidence;
            if (decision->person_confidence == prior->person_confidence) {
                current_wins = decision->candidate_id < prior->candidate_id;
            }
            if (current_wins) {
                selected[j] = false;
            } else {
                selected[i] = false;
                break;
            }
        }
    }

    for (size_t i = 0; i < decisions->count; i++) {
        if (!selected[i]) continue;
        const anomaly_person_relevance_decision_t *decision =
                &decisions->decisions[i];
        int candidate_index = candidate_index_for_id(
                candidates, decision->candidate_id);
        if (candidate_index < 0) continue;
        size_t observation_index =
                candidates->candidates[candidate_index].observation_index;
        float before = observations[observation_index].confidence;
        if (!isfinite(before) || before < 0.0f || before >= 1.0f) continue;
        float bonus = config->maximum_confidence_bonus *
                      decision->person_confidence;
        if (bonus > 1.0f - before) bonus = 1.0f - before;
        if (bonus <= 0.0f) continue;
        observations[observation_index].confidence = before + bonus;
        applied.observation_count++;
        applied.total_confidence_bonus += bonus;
    }
    return applied;
}
