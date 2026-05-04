// anomaly_analysis.h — Standalone anomaly detection for SAR drone video.
//
// No JNI, FFmpeg, or Android dependencies.  All inputs/outputs are plain C
// (RGBA pixel buffers, simple structs).  This makes it possible to unit-test
// the algorithms directly with a native host binary.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

// ── Algorithm selector bits ────────────────────────────────────────────────
#define ANOMALY_ALGO_COLOR    0x01
#define ANOMALY_ALGO_THERMAL  0x02
#define ANOMALY_ALGO_MOTION   0x04
#define ANOMALY_ALGO_PERSIST  0x08  // Experimental multi-cue saliency path
#define ANOMALY_ALGO_MOTION_TOLERANCE 0x10  // Experimental residual-displacement motion path

// ── Thermal polarity ───────────────────────────────────────────────────────
#define ANOMALY_THERMAL_WHITE_HOT 1
#define ANOMALY_THERMAL_BLACK_HOT 2

// ── Default tuning knobs ───────────────────────────────────────────────────
#define ANOMALY_DEFAULT_FRAME_STRIDE      3
#define ANOMALY_DEFAULT_SCORE_THRESHOLD   1.8f
#define ANOMALY_DEFAULT_MIN_AREA_FRACTION 0.0015f
#define ANOMALY_SCAN_ZONE_DEFAULT         0.60f
#define ANOMALY_DEFAULT_MIN_HITS          2
#define ANOMALY_SALIENCY_EXTRA_TRACKS     1

// ── Local tile normalization ───────────────────────────────────────────────
// The ROI is divided into a LOCAL_TILE_SIZE × LOCAL_TILE_SIZE grid.  Mean and
// std are computed per tile so that scoring is relative to the immediate
// neighbourhood rather than the whole frame.  This is the "needle in a
// haystack" fix: a person at 600 ft whose legs are 3° warmer than surrounding
// grass scores highly within their tile even if a hot rock elsewhere in the
// frame would dominate the global statistics.
// Minimum samples per tile before falling back to global stats.
#define ANOMALY_LOCAL_TILE_SIZE    8
#define ANOMALY_LOCAL_TILE_MIN_N   4

// Thermal scoring window radius (in sampled-pixel units).
// The integral-image window for thermal detection is (2R+1)×(2R+1) sampled
// pixels.  At the HD/FHD decimation step of 4 px, R=3 → 7×7 samples covering
// roughly 28×28 real pixels — small enough to stay within a single clearing
// yet large enough for 49-sample statistics.
// Increase R to smooth out noise; decrease it for more spatial precision.
#define ANOMALY_THERMAL_WIN_RADIUS  3

// Minimum absolute luma difference (0–255 scale) required before computing a
// thermal Z-score.  Local tile normalization makes near-uniform regions
// dangerous: a tile that is homogeneous white (cold sky, open clearing) has
// an extremely small σ, so even a 2-count sensor-noise spike or HEVC block
// artifact produces a gigantic Z-score.  This gate ensures we never flag a
// pixel unless it is genuinely darker (black-hot) or brighter (white-hot)
// than its tile mean by at least this many luma units.
// Empirically: SAR subjects at 600 ft produce 15–40 unit differences;
// HEVC compression artifacts in flat regions are typically < 10 units.
#define ANOMALY_THERMAL_MIN_DELTA  10.0f

// ── GMV / similarity-transform tuning ─────────────────────────────────────
#define ANOMALY_GMV_SEARCH_RADIUS   20
#define ANOMALY_GMV_PATCH_HALF       3
#define ANOMALY_GMV_RESIDUAL_THRESH  0.05f
#define ANOMALY_GMV_MIN_SCALE        0.70f
#define ANOMALY_GMV_MAX_SCALE        1.43f
#define ANOMALY_GMV_MIN_TEXTURE_SCORE 20
#define ANOMALY_GMV_MIN_MATCH_MARGIN  4
#define ANOMALY_GMV_ZONE_GRID         4
#define ANOMALY_GMV_MIN_ANCHORS       6

// ── Motion-parallax suppression tuning ────────────────────────────────────
// Forest canopy parallax tends to create smooth residual motion over a local
// neighborhood, while a true moving subject is more likely to produce a
// compact local outlier. Suppress candidates whose center residual does not
// stand out enough from nearby residual motion.
#define ANOMALY_MOTION_NEIGHBOR_MARGIN_PX        1.50f
#define ANOMALY_MOTION_NEIGHBOR_MARGIN_SCALE     0.35f
#define ANOMALY_MOTION_NEIGHBOR_COHERENCE_PX     1.25f
#define ANOMALY_MOTION_NEIGHBOR_MIN_SCALE        0.12f
#define ANOMALY_MOTION_NEIGHBOR_MAX_BONUS        1.75f
#define ANOMALY_MOTION_COMPONENT_TARGET_AREA_FRAC 0.010f
#define ANOMALY_MOTION_COMPONENT_MAX_AREA_FRAC    0.035f
#define ANOMALY_MOTION_COMPONENT_TARGET_SPAN_FRAC 0.12f
#define ANOMALY_MOTION_COMPONENT_MAX_SPAN_FRAC    0.28f
#define ANOMALY_MOTION_COMPONENT_TARGET_FILL_RATIO 0.62f
#define ANOMALY_MOTION_COMPONENT_MIN_FILL_RATIO    0.38f
#define ANOMALY_MOTION_COMPONENT_SPARSE_FILL_RATIO 0.30f
#define ANOMALY_MOTION_COMPONENT_FRAGMENT_FILL_VETO 0.34f
#define ANOMALY_MOTION_COMPONENT_COMPACT_ASPECT    1.9f
#define ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_MAX 4
#define ANOMALY_MOTION_COMPONENT_FRAGMENT_SPAN_VETO 0.030f
#define ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_HARD_MAX 8
#define ANOMALY_MOTION_COMPONENT_MIN_TONE_COHERENCE 0.85f
#define ANOMALY_MOTION_COMPONENT_TARGET_TONE_COHERENCE 1.40f
#define ANOMALY_MOTION_HOMOGENEOUS_MASS_DELTA 8.0f
#define ANOMALY_MOTION_HOMOGENEOUS_MASS_PAD 5
#define ANOMALY_MOTION_HOMOGENEOUS_MASS_SOFT_COUNT 12
#define ANOMALY_MOTION_HOMOGENEOUS_MASS_HARD_COUNT 20
#define ANOMALY_MOTION_HOMOGENEOUS_MASS_SOFT_FRAC 0.32f
#define ANOMALY_MOTION_HOMOGENEOUS_MASS_HARD_FRAC 0.50f

// ── Temporal accumulator tuning ────────────────────────────────────────────
#define ANOMALY_ACC_EMA_ALPHA    0.30f
#define ANOMALY_ACC_GATE_RADIUS  0.15f
#define ANOMALY_ACC_HOLD_FRAMES  8
#define ANOMALY_ACC_MAX_HITS     10
#define ANOMALY_MOTION_PRESENCE_WINDOW 3
#define ANOMALY_MOTION_PRESENCE_MIN_HITS 2

// ── Thermal background model (one-sided EMA) ───────────────────────────────
// The background represents "what this pixel looks like when no warm body is
// present."  It adapts quickly toward colder/brighter values (legitimate scene
// changes) but very slowly toward warmer/darker values (so a subject is never
// absorbed into the background model over the typical search dwell time).
//
// Score = (bg_luma - current_luma) / ANOMALY_THERMAL_BG_NORM
// A pixel that is persistently warmer than its own history scores high every
// frame; a one-shot noise spike decays within a few frames.
//
// ALPHA_COOL: per-frame EMA step toward colder (brighter in BH).
//   0.15 → adapts ~50% of the way in 4 analyzed frames (~0.4 s at stride=1).
// ALPHA_WARM: per-frame EMA step toward warmer (darker in BH).
//   0.01 → takes ~69 analyzed frames (~7 s at stride=1) before a subject
//   is 50% absorbed.  Lowering this value also slows adaptation of warm
//   background objects (vegetation, rocks), creating more persistent false
//   positives rather than fewer — so 0.01 is a good practical balance.
// NORM: fixed luma-unit denominator.  Tune to set "1σ" of the temporal score.
//   12 means a 12-unit persistent delta → score 1.0; 24 units → score 2.0.
// WARMUP: analyzed frames before temporal scoring activates (background must
//   stabilise before we trust it).
#define ANOMALY_THERMAL_BG_ALPHA_COOL  0.15f
#define ANOMALY_THERMAL_BG_ALPHA_WARM  0.01f
#define ANOMALY_THERMAL_BG_NORM        12.0f
#define ANOMALY_THERMAL_BG_WARMUP      8

#define ANOMALY_MAX_BOXES_PER_FRAME 4
#define ANOMALY_DEBUG_TOP_CANDIDATES 5
#define ANOMALY_GMV_MAX_DEBUG_ANCHORS (ANOMALY_GMV_ZONE_GRID * ANOMALY_GMV_ZONE_GRID)

// ── Types ──────────────────────────────────────────────────────────────────

typedef struct {
    float left_norm, top_norm, right_norm, bottom_norm;
    uint8_t r, g, b;
    uint8_t draw_crosshair;
    float weight;       // stroke scale 0–1: thin on first visible hit, full at sustained
    int   algorithm;    // which detector fired: ANOMALY_ALGO_COLOR/THERMAL/MOTION/PERSIST
} anomaly_box_t;

// All config values read from Kotlin via JNI; must be accessed under g_lock
// in the bridge but are passed by value into anomaly_process_frame().
typedef struct {
    bool  enabled;
    bool  show_hot_overlay;
    bool  show_candidate_blobs;
    int   algorithm_mask;
    int   frame_stride;
    int   pixel_step;
    float score_threshold;
    float motion_evidence_scale;
    float min_area_fraction;
    int   thermal_polarity;
    float scan_zone;
    int   min_hits;
    float thermal_min_delta;
} anomaly_config_t;

// Per-stream mutable state owned by the decode thread (no locking needed).
typedef struct {
    int64_t  frame_counter;
    // Per-algorithm temporal accumulators:
    //   0=color, 1=thermal, 2=motion, 3=multi-cue saliency
    float    acc_cx[4];
    float    acc_cy[4];
    int      acc_hits[4];
    int      acc_hold[4];
    bool     acc_active[4];
    uint8_t  acc_presence_mask[4];
    // Previous-frame luma grid for motion estimation
    uint8_t *prev_luma;
    int      prev_luma_width;
    int      prev_luma_height;
    size_t   prev_luma_capacity;
    // Decaying motion persistence map at motion-grid resolution.
    float   *motion_persist;
    int      motion_persist_w;
    int      motion_persist_h;
    // One-sided EMA thermal background model.
    // Stored at sampled-grid resolution (one float per sample point).
    // Adapts fast toward colder, slowly toward warmer — subjects are never
    // absorbed.  Score = (bg - current) / ANOMALY_THERMAL_BG_NORM.
    float   *bg_luma;
    int      bg_sg_w;       // sampled-grid width matching bg_luma
    int      bg_sg_h;       // sampled-grid height matching bg_luma
    int      bg_warmup;     // analyzed frames since last background reset
    // Short-lived prior for compact thermal candidates at sampled-grid resolution.
    float   *thermal_target_persist;
    int      thermal_target_persist_w;
    int      thermal_target_persist_h;
    float    saliency_aux_cx[ANOMALY_SALIENCY_EXTRA_TRACKS];
    float    saliency_aux_cy[ANOMALY_SALIENCY_EXTRA_TRACKS];
    int      saliency_aux_hits[ANOMALY_SALIENCY_EXTRA_TRACKS];
    int      saliency_aux_hold[ANOMALY_SALIENCY_EXTRA_TRACKS];
    bool     saliency_aux_active[ANOMALY_SALIENCY_EXTRA_TRACKS];
    int      saliency_display_algorithm;
    int      saliency_aux_display_algorithm[ANOMALY_SALIENCY_EXTRA_TRACKS];
    // Reusable scratch buffers to avoid per-frame allocation churn.
    uint8_t *scratch_luma;
    size_t   scratch_luma_capacity;
    double  *scratch_sg_luma;
    double  *scratch_ii_sum;
    double  *scratch_ii_sum2;
    size_t   scratch_sampled_grid_capacity;
    float   *scratch_saliency_spatial;
    float   *scratch_saliency_color;
    float   *scratch_saliency_motion;
    float   *scratch_saliency_registration;
    size_t   scratch_saliency_capacity;
    float   *scratch_patch_score;
    float   *scratch_patch_selection;
    size_t   scratch_patch_capacity;
} anomaly_state_t;

typedef struct {
    bool  valid;
    int   pixel_x;
    int   pixel_y;
    float x_norm;
    float y_norm;
    float spatial_score;
    float temporal_score;
    float combined_score;
} anomaly_debug_candidate_t;

typedef struct {
    bool  bg_ready;
    bool  raw_candidate_valid;
    float raw_score;
    float raw_x_norm;
    float raw_y_norm;
    float tracked_score_pre;
    bool  acc_pre_active;
    int   acc_pre_hits;
    float acc_pre_x_norm;
    float acc_pre_y_norm;
    bool  acc_post_active;
    int   acc_post_hits;
    float acc_post_x_norm;
    float acc_post_y_norm;
    bool  switch_suppressed;
    int   top_candidate_count;
    anomaly_debug_candidate_t top_candidates[ANOMALY_DEBUG_TOP_CANDIDATES];
} anomaly_debug_saliency_t;

typedef struct {
    bool  valid;
    bool  scene_discontinuity;
    int   sample_step;
    int   motion_step;
    int   sample_count;
    float residual_mean;
    float residual_std;
    float zoom_motion_scale;
    float broad_motion_scale;
    float global_motion_load;
    bool  raw_candidate_valid;
    float raw_score;
    float raw_x_norm;
    float raw_y_norm;
    float winner_component_area_frac;
    float winner_component_span_frac;
    float winner_component_fill_ratio;
    float winner_texture_scale;
    float winner_structure_scale;
    float winner_support_scale;
    float winner_persistence_scale;
    int   top_candidate_count;
    anomaly_debug_candidate_t top_candidates[ANOMALY_DEBUG_TOP_CANDIDATES];
} anomaly_debug_motion_t;

typedef struct {
    bool  valid;
    int   zone_gx;
    int   zone_gy;
    int   pixel_x;
    int   pixel_y;
    float x_norm;
    float y_norm;
    int   texture_score;
    int   match_dx;
    int   match_dy;
    int   best_sad;
    int   second_best_sad;
} anomaly_debug_gmv_anchor_t;

typedef struct {
    bool  valid;
    bool  scene_discontinuity;
    int   sample_step;
    int   motion_step;
    int   anchor_count;
    float fit_a;
    float fit_b;
    float fit_tx;
    float fit_ty;
    float fit_scale;
    float fit_theta_deg;
    float fit_mean_residual;
    anomaly_debug_gmv_anchor_t anchors[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
} anomaly_debug_gmv_t;

// Returned from anomaly_process_frame(); caller may inspect detections.
typedef struct {
    int           box_count;
    anomaly_box_t boxes[ANOMALY_MAX_BOXES_PER_FRAME];
    bool          had_discontinuity;  // true when a scene cut was detected
    anomaly_debug_gmv_t gmv_debug;
    anomaly_debug_motion_t motion_debug;
    anomaly_debug_saliency_t saliency_debug;
} anomaly_result_t;

// 2-D similarity transform: curr→prev mapping.
//   prev_x = a*curr_x - b*curr_y + tx
//   prev_y = b*curr_x + a*curr_y + ty
typedef struct {
    float a, b, tx, ty;
    float mean_residual;
    bool  valid;
} similarity_2d_t;

typedef struct {
    bool  valid;
    bool  inside_scan_zone;
    bool  used_temporal_score;
    bool  bg_ready;
    int   sample_x;
    int   sample_y;
    int   pixel_x;
    int   pixel_y;
    float sample_luma;
    float spatial_mean;
    float spatial_std;
    float spatial_abs_delta;
    float spatial_score;
    float temporal_delta;
    float temporal_mean;
    float temporal_norm;
    float temporal_score;
    float effective_score;
    float thermal_min_delta;
} anomaly_probe_t;

// ── API ────────────────────────────────────────────────────────────────────

// Zero-initialize state (no heap allocation; call before first use).
void anomaly_state_init(anomaly_state_t *state);

// Reset temporal accumulators and discard prev_luma (e.g. on config change).
void anomaly_state_reset(anomaly_state_t *state);

// Full cleanup — same as reset but intended for final teardown.
void anomaly_state_cleanup(anomaly_state_t *state);

// Process one RGBA frame.
//   - Increments internal frame counter; skips stride-gated frames.
//   - Draws detection boxes in-place on rgba (may be NULL in tests if you
//     only care about the returned result).
//   - result_out: filled when non-NULL; valid even when box_count == 0.
//   - Returns the number of boxes drawn (0 = nothing visible this frame).
int anomaly_process_frame(
    anomaly_state_t        *state,
    const anomaly_config_t *cfg,
    uint8_t                *rgba,
    int                     rgba_stride,
    int                     width,
    int                     height,
    int64_t                 source_ts_us,
    anomaly_result_t       *result_out
);

// Exposed for direct unit testing of the math.
similarity_2d_t fit_similarity_2d(
    const float *src_x, const float *src_y,
    const float *dst_x, const float *dst_y,
    int n
);

bool anomaly_probe_thermal_point(
    const anomaly_state_t  *state,
    const anomaly_config_t *cfg,
    const uint8_t          *rgba,
    int                     rgba_stride,
    int                     width,
    int                     height,
    float                   point_x_norm,
    float                   point_y_norm,
    anomaly_probe_t        *probe_out
);
