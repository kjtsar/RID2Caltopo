#ifndef ANOMALY_COLOR_SHADOW_EVIDENCE_H
#define ANOMALY_COLOR_SHADOW_EVIDENCE_H

#include "anomaly_analysis.h"

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    ANOMALY_COLOR_EVIDENCE_DOMAIN_INVALID = 0,
    ANOMALY_COLOR_EVIDENCE_DOMAIN_DENSE_PIXEL_EXACT = 1,
    ANOMALY_COLOR_EVIDENCE_DOMAIN_SAMPLED_GRID_BBOX = 2,
} anomaly_color_evidence_domain_t;

typedef struct {
    uint32_t histogram[ANOMALY_COLOR_HIST_BINS];
    uint32_t sample_count;
    int predominant_u_bin;
    int predominant_v_bin;
    uint32_t predominant_family_count;
    float predominant_family_share;
    float normalized_entropy;
    float purity;
} anomaly_color_shadow_signature_t;

typedef struct {
    bool valid;
    anomaly_color_evidence_domain_t blob_domain;
    anomaly_color_evidence_domain_t ring_domain;
    anomaly_color_evidence_domain_t sampled_grid_contribution_domain;
    anomaly_color_shadow_signature_t blob_signature;
    anomaly_color_shadow_signature_t ring_signature;
    uint32_t sampled_grid_contribution_count;
    float excluded_background_rarity;
    float normalized_rarity_factor;
    float local_ring_divergence;
    float chroma_reliability;
    bool temporal_valid;
    float temporal_consistency;
    float composite_uniqueness;
} anomaly_color_shadow_evidence_t;

#endif
