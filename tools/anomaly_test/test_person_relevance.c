#include "anomaly_person_relevance.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "CHECK failed at %s:%d: %s\n", \
                __FILE__, __LINE__, #condition); \
        return false; \
    } \
} while (0)

typedef struct {
    int calls;
    bool succeed;
    anomaly_person_relevance_backend_result_batch_t results;
} fake_backend_t;

static const anomaly_person_relevance_model_identity_t VALID_MODEL = {
    .model_name = "fixture-person",
    .model_version = "1.0",
    .model_sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    .input_tensor = "uint8[1,192,192,3]",
    .quantization = "uint8",
    .runtime_name = "fixture-runtime",
    .runtime_version = "1.0",
    .accelerator = "host",
};

static bool fake_backend(
        void *context,
        const anomaly_person_relevance_model_identity_t *model,
        const anomaly_person_relevance_candidate_batch_t *candidates,
        anomaly_person_relevance_backend_result_batch_t *results_out) {
    fake_backend_t *fake = (fake_backend_t *)context;
    fake->calls++;
    if (model != &VALID_MODEL || candidates == NULL) return false;
    *results_out = fake->results;
    return fake->succeed;
}

static anomaly_person_relevance_config_t config_for(
        anomaly_person_relevance_mode_t mode) {
    anomaly_person_relevance_config_t config = {
        .mode = mode,
        .minimum_person_confidence = 0.60f,
        .maximum_confidence_bonus = 0.20f,
    };
    return config;
}

static anomaly_person_relevance_candidate_t candidate(
        uint32_t id,
        uint32_t observation_index,
        uint32_t provenance,
        float left,
        float right) {
    anomaly_person_relevance_candidate_t value = {
        .candidate_id = id,
        .observation_index = observation_index,
        .provenance_mask = provenance,
        .left_norm = left,
        .top_norm = 0.20f,
        .right_norm = right,
        .bottom_norm = 0.60f,
    };
    return value;
}

static bool test_bounded_batch_and_model_identity(void) {
    CHECK(anomaly_person_relevance_model_identity_valid(&VALID_MODEL));
    anomaly_person_relevance_model_identity_t invalid = VALID_MODEL;
    invalid.model_sha256 = "not-a-sha";
    CHECK(!anomaly_person_relevance_model_identity_valid(&invalid));

    anomaly_person_relevance_candidate_batch_t batch;
    anomaly_person_relevance_candidate_batch_init(&batch);
    for (uint32_t i = 0; i < ANOMALY_PERSON_RELEVANCE_MAX_CANDIDATES; i++) {
        anomaly_person_relevance_candidate_t value = candidate(
                i + 1u, i,
                i % 3u == 0u ? ANOMALY_PERSON_PROVENANCE_TARGET_COLOR :
                i % 3u == 1u ? ANOMALY_PERSON_PROVENANCE_COLOR_UNIQUENESS :
                               ANOMALY_PERSON_PROVENANCE_THERMAL_IR,
                0.10f, 0.30f);
        CHECK(anomaly_person_relevance_candidate_batch_append(&batch, &value));
    }
    anomaly_person_relevance_candidate_t overflow = candidate(
            99u, 0u, ANOMALY_PERSON_PROVENANCE_TARGET_COLOR, 0.10f, 0.30f);
    CHECK(!anomaly_person_relevance_candidate_batch_append(&batch, &overflow));
    CHECK(batch.count == ANOMALY_PERSON_RELEVANCE_MAX_CANDIDATES);

    anomaly_person_relevance_candidate_batch_t invalid_batch;
    anomaly_person_relevance_candidate_batch_init(&invalid_batch);
    anomaly_person_relevance_candidate_t invalid_candidate = candidate(
            1u, 0u, 0u, 0.30f, 0.10f);
    CHECK(!anomaly_person_relevance_candidate_batch_append(
            &invalid_batch, &invalid_candidate));
    CHECK(invalid_batch.count == 0u);
    return true;
}

static bool test_off_skips_backend_and_preserves_observations(void) {
    anomaly_person_relevance_candidate_batch_t batch;
    anomaly_person_relevance_candidate_batch_init(&batch);
    anomaly_person_relevance_candidate_t value = candidate(
            7u, 0u, ANOMALY_PERSON_PROVENANCE_THERMAL_IR, 0.10f, 0.30f);
    CHECK(anomaly_person_relevance_candidate_batch_append(&batch, &value));

    fake_backend_t fake = {.succeed = true};
    fake.results.count = 1u;
    fake.results.results[0] = (anomaly_person_relevance_backend_result_t){
        .candidate_id = 7u, .evidence_id = 70u,
        .valid = true, .person_confidence = 0.95f,
    };
    anomaly_person_relevance_decision_batch_t decisions;
    anomaly_person_relevance_config_t config =
            config_for(ANOMALY_PERSON_RELEVANCE_OFF);
    anomaly_person_relevance_evaluate(
            &config, &VALID_MODEL, &batch, fake_backend, &fake, &decisions);
    CHECK(fake.calls == 0);

    anomaly_target_observation_t observations[2];
    memset(observations, 0xA5, sizeof(observations));
    anomaly_target_observation_t before[2];
    memcpy(before, observations, sizeof(before));
    anomaly_person_relevance_application_t applied =
            anomaly_person_relevance_apply(
                    &config, &batch, &decisions, observations, 2u);
    CHECK(applied.observation_count == 0u);
    CHECK(memcmp(before, observations, sizeof(before)) == 0);
    return true;
}

static bool test_shadow_reports_positive_without_applying(void) {
    anomaly_person_relevance_candidate_batch_t batch;
    anomaly_person_relevance_candidate_batch_init(&batch);
    anomaly_person_relevance_candidate_t value = candidate(
            8u, 0u,
            ANOMALY_PERSON_PROVENANCE_TARGET_COLOR |
            ANOMALY_PERSON_PROVENANCE_COLOR_UNIQUENESS,
            0.10f, 0.30f);
    CHECK(anomaly_person_relevance_candidate_batch_append(&batch, &value));

    fake_backend_t fake = {.succeed = true};
    fake.results.count = 1u;
    fake.results.results[0] = (anomaly_person_relevance_backend_result_t){
        .candidate_id = 8u, .evidence_id = 80u,
        .valid = true, .person_confidence = 0.90f,
    };
    anomaly_person_relevance_config_t config =
            config_for(ANOMALY_PERSON_RELEVANCE_SHADOW);
    anomaly_person_relevance_decision_batch_t decisions;
    anomaly_person_relevance_evaluate(
            &config, &VALID_MODEL, &batch, fake_backend, &fake, &decisions);
    CHECK(fake.calls == 1);
    CHECK(decisions.count == 1u);
    CHECK(decisions.decisions[0].kind ==
          ANOMALY_PERSON_RELEVANCE_DECISION_POSITIVE);
    CHECK(decisions.decisions[0].provenance_mask == value.provenance_mask);

    anomaly_target_observation_t observation = {
        .valid = true, .confidence = 0.40f,
    };
    anomaly_target_observation_t before = observation;
    anomaly_person_relevance_apply(
            &config, &batch, &decisions, &observation, 1u);
    CHECK(memcmp(&before, &observation, sizeof(before)) == 0);
    return true;
}

static bool test_positive_only_is_bounded_and_neutral_for_bad_results(void) {
    anomaly_person_relevance_candidate_batch_t batch;
    anomaly_person_relevance_candidate_batch_init(&batch);
    anomaly_person_relevance_candidate_t values[] = {
        candidate(1u, 0u, ANOMALY_PERSON_PROVENANCE_TARGET_COLOR, 0.10f, 0.30f),
        candidate(2u, 1u, ANOMALY_PERSON_PROVENANCE_COLOR_UNIQUENESS, 0.30f, 0.50f),
        candidate(3u, 2u, ANOMALY_PERSON_PROVENANCE_THERMAL_IR, 0.50f, 0.70f),
        candidate(4u, 3u, ANOMALY_PERSON_PROVENANCE_THERMAL_IR, 0.70f, 0.90f),
    };
    for (size_t i = 0; i < sizeof(values) / sizeof(values[0]); i++) {
        CHECK(anomaly_person_relevance_candidate_batch_append(&batch, &values[i]));
    }

    fake_backend_t fake = {.succeed = true};
    fake.results.count = 3u;
    fake.results.results[0] = (anomaly_person_relevance_backend_result_t){
        .candidate_id = 1u, .evidence_id = 10u,
        .valid = true, .person_confidence = 0.90f,
    };
    fake.results.results[1] = (anomaly_person_relevance_backend_result_t){
        .candidate_id = 2u, .evidence_id = 20u,
        .valid = true, .person_confidence = 0.59f,
    };
    fake.results.results[2] = (anomaly_person_relevance_backend_result_t){
        .candidate_id = 3u, .evidence_id = 30u,
        .valid = true, .person_confidence = NAN,
    };
    anomaly_person_relevance_config_t config =
            config_for(ANOMALY_PERSON_RELEVANCE_POSITIVE_ONLY);
    anomaly_person_relevance_decision_batch_t decisions;
    anomaly_person_relevance_evaluate(
            &config, &VALID_MODEL, &batch, fake_backend, &fake, &decisions);
    CHECK(decisions.decisions[0].kind == ANOMALY_PERSON_RELEVANCE_DECISION_POSITIVE);
    CHECK(decisions.decisions[1].kind == ANOMALY_PERSON_RELEVANCE_DECISION_LOW_CONFIDENCE);
    CHECK(decisions.decisions[2].kind == ANOMALY_PERSON_RELEVANCE_DECISION_INVALID);
    CHECK(decisions.decisions[3].kind == ANOMALY_PERSON_RELEVANCE_DECISION_MISSING);

    anomaly_target_observation_t observations[4] = {
        {.valid = true, .confidence = 0.90f},
        {.valid = true, .confidence = 0.30f},
        {.valid = true, .confidence = 0.30f},
        {.valid = true, .confidence = 0.30f},
    };
    anomaly_person_relevance_application_t applied =
            anomaly_person_relevance_apply(
                    &config, &batch, &decisions, observations, 4u);
    CHECK(applied.observation_count == 1u);
    CHECK(fabsf(observations[0].confidence - 1.0f) < 0.00001f);
    CHECK(fabsf(observations[1].confidence - 0.30f) < 0.00001f);
    CHECK(fabsf(observations[2].confidence - 0.30f) < 0.00001f);
    CHECK(fabsf(observations[3].confidence - 0.30f) < 0.00001f);
    CHECK(applied.total_confidence_bonus <= config.maximum_confidence_bonus);
    return true;
}

static bool test_overlap_and_shared_evidence_apply_once(void) {
    anomaly_person_relevance_candidate_batch_t batch;
    anomaly_person_relevance_candidate_batch_init(&batch);
    anomaly_person_relevance_candidate_t target = candidate(
            11u, 0u, ANOMALY_PERSON_PROVENANCE_TARGET_COLOR, 0.20f, 0.50f);
    anomaly_person_relevance_candidate_t uniqueness = candidate(
            12u, 1u, ANOMALY_PERSON_PROVENANCE_COLOR_UNIQUENESS, 0.22f, 0.52f);
    CHECK(anomaly_person_relevance_candidate_batch_append(&batch, &target));
    CHECK(anomaly_person_relevance_candidate_batch_append(&batch, &uniqueness));

    fake_backend_t fake = {.succeed = true};
    fake.results.count = 2u;
    fake.results.results[0] = (anomaly_person_relevance_backend_result_t){
        .candidate_id = 11u, .evidence_id = 101u,
        .valid = true, .person_confidence = 0.80f,
    };
    fake.results.results[1] = (anomaly_person_relevance_backend_result_t){
        .candidate_id = 12u, .evidence_id = 101u,
        .valid = true, .person_confidence = 0.95f,
    };
    anomaly_person_relevance_config_t config =
            config_for(ANOMALY_PERSON_RELEVANCE_POSITIVE_ONLY);
    anomaly_person_relevance_decision_batch_t decisions;
    anomaly_person_relevance_evaluate(
            &config, &VALID_MODEL, &batch, fake_backend, &fake, &decisions);

    anomaly_target_observation_t observations[2] = {
        {.valid = true, .confidence = 0.20f},
        {.valid = true, .confidence = 0.20f},
    };
    anomaly_person_relevance_application_t applied =
            anomaly_person_relevance_apply(
                    &config, &batch, &decisions, observations, 2u);
    CHECK(applied.observation_count == 1u);
    CHECK(fabsf(observations[0].confidence - 0.20f) < 0.00001f);
    CHECK(fabsf(observations[1].confidence - 0.39f) < 0.00001f);
    return true;
}

static bool test_invalid_identity_and_backend_failure_are_neutral(void) {
    anomaly_person_relevance_candidate_batch_t batch;
    anomaly_person_relevance_candidate_batch_init(&batch);
    anomaly_person_relevance_candidate_t value = candidate(
            1u, 0u, ANOMALY_PERSON_PROVENANCE_THERMAL_IR, 0.10f, 0.30f);
    CHECK(anomaly_person_relevance_candidate_batch_append(&batch, &value));
    anomaly_person_relevance_config_t config =
            config_for(ANOMALY_PERSON_RELEVANCE_POSITIVE_ONLY);
    anomaly_person_relevance_decision_batch_t decisions;
    fake_backend_t fake = {.succeed = false};

    anomaly_person_relevance_model_identity_t invalid_model = VALID_MODEL;
    invalid_model.runtime_version = NULL;
    anomaly_person_relevance_evaluate(
            &config, &invalid_model, &batch, fake_backend, &fake, &decisions);
    CHECK(fake.calls == 0);
    CHECK(decisions.decisions[0].kind == ANOMALY_PERSON_RELEVANCE_DECISION_INVALID);

    anomaly_person_relevance_evaluate(
            &config, &VALID_MODEL, &batch, fake_backend, &fake, &decisions);
    CHECK(fake.calls == 1);
    CHECK(decisions.decisions[0].kind ==
          ANOMALY_PERSON_RELEVANCE_DECISION_BACKEND_UNAVAILABLE);
    anomaly_target_observation_t observation = {
        .valid = true, .confidence = 0.50f,
    };
    anomaly_target_observation_t before = observation;
    anomaly_person_relevance_apply(
            &config, &batch, &decisions, &observation, 1u);
    CHECK(memcmp(&before, &observation, sizeof(before)) == 0);
    return true;
}

int main(void) {
    struct {
        const char *name;
        bool (*run)(void);
    } tests[] = {
        {"bounded batch and model identity", test_bounded_batch_and_model_identity},
        {"off skips backend and preserves observations", test_off_skips_backend_and_preserves_observations},
        {"shadow reports positive without applying", test_shadow_reports_positive_without_applying},
        {"positive-only bounded neutral fusion", test_positive_only_is_bounded_and_neutral_for_bad_results},
        {"overlap and shared evidence apply once", test_overlap_and_shared_evidence_apply_once},
        {"invalid identity and backend failure neutral", test_invalid_identity_and_backend_failure_are_neutral},
    };
    for (size_t i = 0; i < sizeof(tests) / sizeof(tests[0]); i++) {
        if (!tests[i].run()) {
            fprintf(stderr, "FAIL: %s\n", tests[i].name);
            return 1;
        }
        printf("PASS: %s\n", tests[i].name);
    }
    return 0;
}
