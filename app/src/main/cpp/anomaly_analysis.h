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

// ── Registration backend selector ──────────────────────────────────────────
#define ANOMALY_REGISTRATION_GMV    1
#define ANOMALY_REGISTRATION_AFFINE 2

// ── Default tuning knobs ───────────────────────────────────────────────────
#define ANOMALY_DEFAULT_FRAME_STRIDE      10
#define ANOMALY_DEFAULT_SCORE_THRESHOLD   1.8f
#define ANOMALY_DEFAULT_MIN_AREA_FRACTION 0.0015f
#define ANOMALY_SCAN_ZONE_DEFAULT         0.80f
#define ANOMALY_SMALL_TARGET_SCREEN_FRACTION_DEFAULT (1.0f / 200.0f)
#define ANOMALY_DEFAULT_MIN_HITS          2
#define ANOMALY_FULL_RESCAN_INTERVAL_US   333000LL
#define ANOMALY_FULL_RESCAN_INTERVAL_FRAMES 20
#define ANOMALY_SALIENCY_EXTRA_TRACKS     1
#define ANOMALY_MAX_TARGET_TRACKS         6

#define ANOMALY_COLOR_U_BINS 12
#define ANOMALY_COLOR_V_BINS 12
#define ANOMALY_COLOR_HIST_BINS (ANOMALY_COLOR_U_BINS * ANOMALY_COLOR_V_BINS)
#define ANOMALY_COLOR_HISTORY_DECAY_SHIFT 1
#define ANOMALY_COLOR_HISTORY_UPDATE_SHIFT 3
#define ANOMALY_COLOR_HISTORY_RECOVERY_FRAMES 6
#define ANOMALY_COLOR_HISTORY_RECOVERY_SEED_SHIFT 4
#define ANOMALY_COLOR_RARITY_MIN 0.03125f
#define ANOMALY_COLOR_RARITY_SCALE 72.0f
#define ANOMALY_COLOR_TEMPORAL_RESCUE_RADIUS_CELLS 3
#define ANOMALY_COLOR_TEMPORAL_RESCUE_SCORE_BASE 0.68f
#define ANOMALY_COLOR_TEMPORAL_RESCUE_SCORE_RANGE 0.92f

// ── Visible-color frontend modes ────────────────────────────────────────────
#define ANOMALY_COLOR_FRONTEND_LEGACY     0
#define ANOMALY_COLOR_FRONTEND_FRESH_RGBA 1
#define ANOMALY_COLOR_FRONTEND_FRESH_YUV  2
#define ANOMALY_STRIDE_MODE_FIXED         0
#define ANOMALY_STRIDE_MODE_ADAPTIVE      1

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

// Thermal scoring window calibration anchor.
// The runtime converts this sampled-grid radius into an effective cell radius
// derived from sample_step so HD/FHD dense mode keeps approximately the same
// real-pixel neighborhood as the reviewed coarse path.
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
#define ANOMALY_GMV_PATCH_HALF       4
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
#define ANOMALY_MOVEMENT_GRID_COLS 8
#define ANOMALY_MOVEMENT_GRID_ROWS 6
#define ANOMALY_MOVEMENT_TILE_COUNT (ANOMALY_MOVEMENT_GRID_COLS * ANOMALY_MOVEMENT_GRID_ROWS)
#define ANOMALY_AOI_MOVEMENT_WINDOW_FRAMES 30

// ── Temporal accumulator tuning ────────────────────────────────────────────
#define ANOMALY_ACC_EMA_ALPHA    0.30f
#define ANOMALY_ACC_GATE_RADIUS  0.15f
#define ANOMALY_ACC_HOLD_FRAMES  8
#define ANOMALY_ACC_MAX_HITS     10
#define ANOMALY_MOTION_PRESENCE_WINDOW 3
#define ANOMALY_MOTION_PRESENCE_MIN_HITS 2
#define ANOMALY_COLOR_PROMOTION_TRACKS 4
#define ANOMALY_COLOR_PROMOTION_GATE_RADIUS 0.028f
#define ANOMALY_COLOR_PROMOTION_HOLD_FRAMES 14
#define ANOMALY_COLOR_PROMOTION_MAX_HITS 8

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
#define ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES 8
#define ANOMALY_DEBUG_TOP_COLOR_CANDIDATES 8
#define ANOMALY_GMV_MAX_DEBUG_ANCHORS (ANOMALY_GMV_ZONE_GRID * ANOMALY_GMV_ZONE_GRID)

#ifndef ANOMALY_DEBUG_TIMING
#define ANOMALY_DEBUG_TIMING 0
#endif

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
    int   registration_mode;
    int   movement_estimator_mode;
    int   stride_mode;
    int   frame_stride;
    int   adaptive_min_stride_frames;
    int   adaptive_max_stride_frames;
    float adaptive_max_stride_seconds;
    int   pixel_step;
    float score_threshold;
    float motion_evidence_scale;
    float min_area_fraction;
    int   thermal_polarity;
    float scan_zone;
    int   min_hits;
    float thermal_min_delta;
    float small_target_screen_fraction;
    int   color_frontend_mode;
    bool  thermal_debug_target_enabled;
    float thermal_debug_target_x_norm;
    float thermal_debug_target_y_norm;
    bool  color_debug_target_enabled;
    float color_debug_target_x_norm;
    float color_debug_target_y_norm;
} anomaly_config_t;

typedef struct {
    int   valid_count;
    int   fresh_count;
    int   carried_count;
    int   newly_exposed_count;
    int   stale_count;
    float registration_quality;
    uint32_t scan_flags;
    float max_thermal_score;
    float max_motion_support;
    float max_color_score;
} anomaly_roi_cell_summary_t;

typedef struct {
    bool  valid;
    int   roi_x0;
    int   roi_y0;
    int   roi_x1;
    int   roi_y1;
    int   width;
    int   height;
    int   sample_step;
    float *last_luma;
    float *thermal_score;
    float *temporal_score;
    float *color_luma;
    float *color_u;
    float *color_v;
    float *color_raw_score;       // current-frame front-end color evidence
    float *color_contrast_weight; // current-frame blob cohesion weight
    uint8_t *color_u_bin;
    uint8_t *color_v_bin;
    uint8_t *valid_mask;
    uint8_t *fresh_mask;
    uint8_t *carried_mask;
    uint8_t *new_exposed_mask;
    uint8_t *color_valid_mask;
    uint8_t *color_phase_x;
    uint8_t *color_phase_y;
    float   *reg_confidence;
    uint8_t *coverage_age;
    size_t   pixel_capacity;
    int      cell_size_px;
    int      cell_cols;
    int      cell_rows;
    anomaly_roi_cell_summary_t *cell_summaries;
    size_t   cell_capacity;
} anomaly_roi_state_t;

typedef struct {
    bool  active;
    int   id;
    bool  publish_confirmed;
    float confidence;
    int   hit_count;
    int   miss_count;
    int   hold_count;
    float center_x_norm;
    float center_y_norm;
    float half_w_norm;
    float half_h_norm;
    float support_radius_norm;
    float last_registration_quality;
    bool  forced_revisit;
    int   algorithm;
    bool  fresh_observation;
    int   movement_window_frames;
    int   movement_valid_frames;
    int   movement_independent_frames;
    int   movement_parallax_frames;
    float movement_independent_score_sum;
    float movement_confidence_sum;
    float last_movement_dx_px;
    float last_movement_dy_px;
    float last_movement_residual_px;
    float last_movement_independent_score;
} anomaly_target_track_t;

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
    float    color_promotion_cx[ANOMALY_COLOR_PROMOTION_TRACKS];
    float    color_promotion_cy[ANOMALY_COLOR_PROMOTION_TRACKS];
    int      color_promotion_hits[ANOMALY_COLOR_PROMOTION_TRACKS];
    int      color_promotion_hold[ANOMALY_COLOR_PROMOTION_TRACKS];
    bool     color_promotion_active[ANOMALY_COLOR_PROMOTION_TRACKS];
    // Previous-frame luma grid for motion estimation
    uint8_t *prev_luma;
    int      prev_luma_width;
    int      prev_luma_height;
    size_t   prev_luma_capacity;
    uint8_t *prev_registration_luma;
    int      prev_registration_luma_width;
    int      prev_registration_luma_height;
    size_t   prev_registration_luma_capacity;
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
    uint8_t *color_recent_hist;
    size_t   color_recent_hist_bins;
    uint8_t *scratch_color_hist;
    size_t   scratch_color_hist_bins;
    int      color_history_recovery_frames;
    anomaly_roi_state_t roi_state;
    anomaly_target_track_t target_tracks[ANOMALY_MAX_TARGET_TRACKS];
    int      next_target_track_id;
    bool     cached_registration_valid;
    int      cached_registration_mode;
    int      cached_registration_sample_step;
    int      cached_registration_motion_step;
    int      cached_registration_anchor_count;
    int      cached_registration_tracked_match_count;
    int      cached_registration_invalid_reason;
    int      cached_registration_health;
    int      cached_registration_last_rescan_mode;
    int      cached_registration_reuse_budget;
    float    cached_registration_affine[6];
    float    cached_registration_similarity_a;
    float    cached_registration_similarity_b;
    float    cached_registration_similarity_tx;
    float    cached_registration_similarity_ty;
    float    cached_registration_similarity_mean_residual;
    float    cached_registration_fit_det;
    float    cached_registration_fit_min_scale;
    float    cached_registration_fit_max_scale;
    float    cached_registration_fit_anchor_residual_std;
    float    cached_registration_fit_anchor_residual_max;
    float    cached_registration_fit_motion_dx_std;
    float    cached_registration_fit_motion_dy_std;
    float    cached_registration_fit_quadrant_residual_spread;
    int      publish_hold_frames;
    int      publish_stable_frames;
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
    uint8_t *scratch_registration_luma;
    uint8_t *scratch_registration_tmp;
    size_t   scratch_registration_luma_capacity;
    uint8_t *scratch_u8;
    size_t   scratch_u8_capacity;
    float   *scratch_sg_luma;
    float   *scratch_ii_sum;
    float   *scratch_ii_sum2;
    size_t   scratch_sampled_grid_capacity;
    float   *scratch_saliency_spatial;
    float   *scratch_saliency_color;
    float   *scratch_saliency_motion;
    float   *scratch_saliency_registration;
    float   *scratch_thermal_delta;
    size_t   scratch_saliency_capacity;
    float   *scratch_patch_score;
    float   *scratch_patch_selection;
    size_t   scratch_patch_capacity;
    float   *scratch_prev_roi_last_luma;
    float   *scratch_prev_roi_thermal_score;
    float   *scratch_prev_roi_temporal_score;
    float   *scratch_prev_roi_color_luma;
    float   *scratch_prev_roi_color_u;
    float   *scratch_prev_roi_color_v;
    float   *scratch_prev_roi_color_raw_score;
    float   *scratch_prev_roi_color_contrast_weight;
    uint8_t *scratch_prev_roi_color_u_bin;
    uint8_t *scratch_prev_roi_color_v_bin;
    uint8_t *scratch_prev_roi_valid_mask;
    uint8_t *scratch_prev_roi_coverage_age;
    uint8_t *scratch_prev_roi_color_valid_mask;
    uint8_t *scratch_prev_roi_color_phase_x;
    uint8_t *scratch_prev_roi_color_phase_y;
    size_t   scratch_prev_roi_capacity;
    uint8_t *scratch_refresh_mask;
    size_t   scratch_refresh_mask_capacity;
    int     *scratch_prev_sample_lookup;
    size_t   scratch_prev_sample_lookup_capacity;
    int     *scratch_i32;
    size_t   scratch_i32_capacity;
    uint64_t color_phase_counter;
    int64_t  last_full_refresh_source_ts_us;
    int64_t  last_full_refresh_frame_counter;
    int      last_color_full_scan_coarse_count;
    float    fresh_color_distinctness_ratio;
    int      adaptive_effective_stride;
    int      adaptive_stable_frames;
    int      adaptive_drop_hold_frames;
    int      adaptive_target_rich_frames;
    float    adaptive_motion_load_ema;
    int64_t  adaptive_last_source_ts_us;
    float    adaptive_frame_interval_ema_us;
    uint32_t adaptive_reason_flags;
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
    bool  valid;
    int   pixel_x;
    int   pixel_y;
    float x_norm;
    float y_norm;
    float bbox_left_norm;
    float bbox_top_norm;
    float bbox_right_norm;
    float bbox_bottom_norm;
    float base_score;
    float final_score;
    float temporal_score;
    float area;
    float span;
    float fill;
    float center_share;
    float quality;
    float isolation_rank;
    float peak_delta;
    float mean_delta;
    float score_scale;
    float history_scale;
    float apparent_size_scale;
    float isolation_track_scale;
    float context_scale;
    float parent_scale;
    float area_rank;
    float span_rank;
    float center_rank;
    float quality_rank;
    float patch_support;
    float motion_support;
    float singleton_score_scale;
    float retention_rank;
    float raw_delta_rescue_score;
    float movement_residual_px;
    float movement_independent_score;
    float movement_confidence;
    int   movement_layer_class;
    float nearest_track_distance;
    int   nearest_track_index;
    int   nearest_track_id;
    int   nearest_track_hit_count;
    bool  raw_delta_rescue_eligible;
    bool  movement_tile_valid;
    bool  movement_independent;
    bool  movement_parallax;
    bool  would_promote_movement_rescue;
    bool  near_tracked_target;
    bool  near_debug_target;
    bool  singleton_blob;
    bool  above_threshold;
} anomaly_debug_thermal_candidate_t;

typedef struct {
    bool  valid;
    int   pixel_x;
    int   pixel_y;
    float x_norm;
    float y_norm;
    float bbox_left_norm;
    float bbox_top_norm;
    float bbox_right_norm;
    float bbox_bottom_norm;
    float base_score;
    float final_score;
    float temporal_score;
    float area;
    float span;
    float fill;
    float center_share;
    float quality;
    float isolation_score;
    float ring_fraction;
    float support_mass;
    float contrast_weight;
    int   hist_key;
    float hist_current_count;
    float hist_recent_count;
    float hist_rarity_score;
    float small_target_span_ratio;
    float small_target_area_ratio;
    float scene_commonness;
    float retention_rank;
    bool  above_threshold;
} anomaly_debug_color_candidate_t;

typedef enum {
    ANOMALY_COLOR_BLOB_REJECT_NONE = 0,
    ANOMALY_COLOR_BLOB_REJECT_AREA = 1,
    ANOMALY_COLOR_BLOB_REJECT_RING = 2,
    ANOMALY_COLOR_BLOB_REJECT_SUPPORT_MASS = 3,
    ANOMALY_COLOR_BLOB_REJECT_QUALITY = 4,
} anomaly_color_blob_reject_reason_t;

typedef enum {
    ANOMALY_COLOR_WINNER_GATE_NONE = 0,
    ANOMALY_COLOR_WINNER_GATE_SIZE = 1,
    ANOMALY_COLOR_WINNER_GATE_COMMONNESS = 2,
    ANOMALY_COLOR_WINNER_GATE_SIZE_AND_COMMONNESS = 3,
} anomaly_color_winner_gate_reason_t;

typedef struct {
    bool  valid;
    int   sample_x;
    int   sample_y;
    float score;
    int   hist_key;
    float hist_current_count;
    float hist_recent_count;
    float hist_rarity_score;
    int   local_support_count;
} anomaly_debug_color_seed_t;

typedef enum {
    ANOMALY_COLOR_TARGET_STAGE_NONE = 0,
    ANOMALY_COLOR_TARGET_STAGE_OUTSIDE_SCAN_ZONE = 1,
    ANOMALY_COLOR_TARGET_STAGE_INVALID_SAMPLE = 2,
    ANOMALY_COLOR_TARGET_STAGE_RARITY_REJECTED = 3,
    ANOMALY_COLOR_TARGET_STAGE_LOCAL_SUPPORT_REJECTED = 4,
    ANOMALY_COLOR_TARGET_STAGE_SUPPORT_MAP_REJECTED = 5,
    ANOMALY_COLOR_TARGET_STAGE_NO_CANDIDATE = 6,
    ANOMALY_COLOR_TARGET_STAGE_EXTRACTED = 7,
    ANOMALY_COLOR_TARGET_STAGE_WINNER = 8,
} anomaly_debug_color_target_stage_t;

typedef struct {
    bool  enabled;
    bool  valid;
    bool  inside_scan_zone;
    bool  refresh_skipped;
    bool  sampled_this_frame;
    bool  carried_from_history;
    int   pixel_x;
    int   pixel_y;
    int   sample_x;
    int   sample_y;
    float x_norm;
    float y_norm;
    int   hist_key;
    float hist_current_count;
    float hist_recent_count;
    float hist_rarity_score;
    int   local_support_count;
    int   patch_valid_count;
    int   coherent_patch_cell_count;
    int   coherent_patch_fresh_cell_count;
    bool  coherent_patch_multicell;
    float patch_mean_u;
    float patch_mean_v;
    float patch_mean_luma;
    float ring_mean_u;
    float ring_mean_v;
    float ring_mean_luma;
    float ring_chroma_contrast;
    float ring_luma_contrast;
    int   ring_neighbor_count;
    float pre_support_score;
    float support_score;
    float support_map_local_peak;
    float support_map_ring_mean;
    float support_map_density;
    float support_map_distinctness_ratio;
    float support_map_compact_prominence;
    float support_map_core_share;
    float support_map_seed_floor;
    bool  support_seed_eligible;
    int   component_seed_x;
    int   component_seed_y;
    int   component_peak_x;
    int   component_peak_y;
    float component_area;
    float component_span;
    float component_fill;
    float component_peak_support;
    float component_mean_support;
    float component_quality;
    float component_ring_fraction;
    float component_support_mass;
    bool  component_rejected;
    int   component_rejection_reason;
    float component_bbox_left_norm;
    float component_bbox_top_norm;
    float component_bbox_right_norm;
    float component_bbox_bottom_norm;
    bool  dropped_by_cap;
    bool  dropped_by_nms;
    bool  replaced_by_nms;
    bool  rejected_by_winner_gate;
    int   nms_conflict_rank;
    int   nms_conflict_sample_x;
    int   nms_conflict_sample_y;
    int   pre_cap_rank;
    int   pre_cap_candidate_count;
    int   pre_cap_limit;
    float pre_cap_retention_rank;
    int   winning_rank;
    int   winner_gate_reject_reason;
    int   extracted_candidate_index;
    int   matched_candidate_index;
    int   nearest_candidate_index;
    float nearest_candidate_distance;
    int   winning_candidate_index;
    float matched_candidate_score;
    float matched_candidate_x_norm;
    float matched_candidate_y_norm;
    float matched_bbox_left_norm;
    float matched_bbox_top_norm;
    float matched_bbox_right_norm;
    float matched_bbox_bottom_norm;
    anomaly_debug_color_target_stage_t stage;
} anomaly_debug_color_target_t;

typedef enum {
    ANOMALY_THERMAL_TARGET_STAGE_NONE = 0,
    ANOMALY_THERMAL_TARGET_STAGE_NOT_HOT = 1,
    ANOMALY_THERMAL_TARGET_STAGE_SUPPRESSED_BY_NEIGHBOR = 2,
    ANOMALY_THERMAL_TARGET_STAGE_MERGED_INTO_COMPONENT = 3,
    ANOMALY_THERMAL_TARGET_STAGE_REJECTED_BY_GATE = 4,
    ANOMALY_THERMAL_TARGET_STAGE_EXTRACTED = 5,
} anomaly_debug_thermal_target_stage_t;

typedef enum {
    ANOMALY_THERMAL_TARGET_GATE_NONE = 0,
    ANOMALY_THERMAL_TARGET_GATE_MAX_AREA = 1,
    ANOMALY_THERMAL_TARGET_GATE_RING_HOT = 2,
    ANOMALY_THERMAL_TARGET_GATE_SIDE_HOT = 3,
    ANOMALY_THERMAL_TARGET_GATE_SUPPORT_MASS = 4,
    ANOMALY_THERMAL_TARGET_GATE_SUPPORT_NEAR = 5,
    ANOMALY_THERMAL_TARGET_GATE_ZERO_QUALITY = 6,
} anomaly_debug_thermal_target_gate_t;

typedef enum {
    ANOMALY_THERMAL_MICRO_REJECT_NONE = 0,
    ANOMALY_THERMAL_MICRO_REJECT_NO_HOT_PEAK = 1,
    ANOMALY_THERMAL_MICRO_REJECT_NOT_LOCAL_MAX = 2,
    ANOMALY_THERMAL_MICRO_REJECT_WEAK_PROMINENCE = 3,
    ANOMALY_THERMAL_MICRO_REJECT_RING_HOT = 4,
    ANOMALY_THERMAL_MICRO_REJECT_TOO_MANY_HOT = 5,
    ANOMALY_THERMAL_MICRO_REJECT_LOW_COMPACTNESS = 6,
    ANOMALY_THERMAL_MICRO_REJECT_EDGE_LIKE = 7,
    ANOMALY_THERMAL_MICRO_REJECT_CENTROID_DRIFT = 8,
    ANOMALY_THERMAL_MICRO_REJECT_TOO_FAR = 9,
} anomaly_debug_thermal_micro_reject_t;

typedef enum {
    ANOMALY_MOVEMENT_SHADOW_REJECT_NONE = 0,
    ANOMALY_MOVEMENT_SHADOW_REJECT_NO_MOVEMENT_TILE = 1,
    ANOMALY_MOVEMENT_SHADOW_REJECT_PARALLAX = 2,
    ANOMALY_MOVEMENT_SHADOW_REJECT_NOT_INDEPENDENT = 3,
    ANOMALY_MOVEMENT_SHADOW_REJECT_WEAK_THERMAL = 4,
    ANOMALY_MOVEMENT_SHADOW_REJECT_RING_HOT = 5,
    ANOMALY_MOVEMENT_SHADOW_REJECT_LOCAL_MEAN_HOT = 6,
    ANOMALY_MOVEMENT_SHADOW_REJECT_TOO_MANY_HOT = 7,
    ANOMALY_MOVEMENT_SHADOW_REJECT_LOW_COMPACTNESS = 8,
    ANOMALY_MOVEMENT_SHADOW_REJECT_EDGE_LIKE = 9,
    ANOMALY_MOVEMENT_SHADOW_REJECT_CENTROID_DRIFT = 10,
    ANOMALY_MOVEMENT_SHADOW_REJECT_NO_LOCAL_SHAPE = 11,
    ANOMALY_MOVEMENT_SHADOW_REJECT_NO_MOTION_SUPPORT = 12,
} anomaly_debug_movement_shadow_reject_t;

typedef struct {
    bool  enabled;
    bool  valid;
    bool  inside_scan_zone;
    int   pixel_x;
    int   pixel_y;
    int   sample_x;
    int   sample_y;
    float x_norm;
    float y_norm;
    float target_delta;
    float target_score;
    float target_raw_delta;
    float target_raw_score;
    float target_temporal_margin;
    float target_spatial_abs_delta;
    float target_spatial_std;
    float target_spatial_score;
    bool  hot_eligible;
    bool  started_component;
    bool  local_max;
    int   local_peak_radius;
    int   local_peak_sample_x;
    int   local_peak_sample_y;
    float local_peak_delta;
    float local_peak_score;
    float local_peak_distance;
    int   local_peak_raw_sample_x;
    int   local_peak_raw_sample_y;
    float local_peak_raw_delta;
    float local_peak_raw_score;
    float local_peak_raw_distance;
    float local_peak_raw_temporal_margin;
    float local_peak_raw_spatial_abs_delta;
    float local_peak_raw_spatial_std;
    float local_peak_raw_spatial_score;
    bool  local_peak_is_component_seed;
    int   local_window_sample_count;
    int   local_window_hot_count;
    float local_window_raw_delta_sum;
    float local_window_raw_delta_mean;
    float local_window_weighted_centroid_dx;
    float local_window_weighted_centroid_dy;
    bool  micro_candidate_would_create;
    anomaly_debug_thermal_micro_reject_t micro_candidate_reject_reason;
    int   micro_candidate_peak_sample_x;
    int   micro_candidate_peak_sample_y;
    float micro_candidate_peak_delta;
    float micro_candidate_peak_score;
    float micro_candidate_prominence;
    float micro_candidate_ring_mean;
    float micro_candidate_ring_hot_fraction;
    int   micro_candidate_hot_count;
    int   micro_candidate_sample_count;
    float micro_candidate_compactness;
    float micro_candidate_centroid_dx;
    float micro_candidate_centroid_dy;
    float micro_candidate_centroid_offset;
    float micro_candidate_one_sided_support;
    float micro_candidate_distance_to_debug_target;
    int   suppressor_sample_x;
    int   suppressor_sample_y;
    float suppressor_delta;
    float suppressor_score;
    int   component_seed_x;
    int   component_seed_y;
    int   component_peak_x;
    int   component_peak_y;
    float component_area;
    float component_span;
    float component_fill;
    float component_peak_delta;
    float component_mean_delta;
    float component_quality;
    bool  component_rejected;
    anomaly_debug_thermal_target_gate_t rejection_gate;
    bool  nearby_rejected_component_valid;
    bool  nearby_rejected_component_contains_target;
    anomaly_debug_thermal_target_gate_t nearby_rejected_component_gate;
    int   nearby_rejected_component_seed_x;
    int   nearby_rejected_component_seed_y;
    int   nearby_rejected_component_peak_x;
    int   nearby_rejected_component_peak_y;
    float nearby_rejected_component_area;
    float nearby_rejected_component_span;
    float nearby_rejected_component_fill;
    float nearby_rejected_component_peak_delta;
    float nearby_rejected_component_mean_delta;
    float nearby_rejected_component_quality;
    float nearby_rejected_component_distance;
    bool  dropped_by_cap;
    bool  dropped_by_nms;
    bool  replaced_by_nms;
    int   nms_conflict_rank;
    int   nms_conflict_sample_x;
    int   nms_conflict_sample_y;
    int   pre_cap_rank;
    int   pre_cap_candidate_count;
    int   pre_cap_limit;
    float pre_cap_retention_rank;
    int   extracted_rank;
    int   winning_rank;
    int   provisional_candidate_index;
    float provisional_score_floor;
    float provisional_final_score;
    bool  provisional_score_eligible;
    bool  provisional_shape_eligible;
    float provisional_candidate_rank;
    int   provisional_selected_rank;
    float provisional_selected_score;
    bool  provisional_near_existing_skip;
    float raw_delta_rescue_score;
    float movement_residual_px;
    float movement_independent_score;
    float movement_confidence;
    float movement_motion_support;
    int   movement_layer_class;
    float local_peak_movement_residual_px;
    float local_peak_movement_independent_score;
    float local_peak_movement_confidence;
    float local_peak_movement_motion_support;
    int   local_peak_movement_layer_class;
    bool  raw_delta_rescue_eligible;
    bool  movement_tile_valid;
    bool  movement_independent;
    bool  movement_parallax;
    bool  would_promote_movement_rescue;
    bool  local_peak_movement_tile_valid;
    bool  local_peak_movement_independent;
    bool  local_peak_movement_parallax;
    bool  movement_shadow_motion_support;
    bool  movement_shadow_parallax_penalty;
    bool  movement_shadow_thermal_support;
    bool  movement_shadow_clutter_veto;
    bool  movement_rescue_would_publish;
    bool  movement_boost_would_publish;
    anomaly_debug_movement_shadow_reject_t movement_rescue_reject_reason;
    int   matched_track_index;
    int   matched_track_id;
    int   matched_track_hit_count;
    int   matched_track_miss_count;
    int   matched_track_hold_count;
    bool  matched_track_publish_confirmed;
    anomaly_debug_thermal_target_stage_t stage;
} anomaly_debug_thermal_target_t;

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
    bool  bg_ready;
    bool  raw_candidate_valid;
    float raw_score;
    float raw_x_norm;
    float raw_y_norm;
    float frame_delta_mean;
    float frame_delta_norm;
    float frame_blob_contrast_mean;
    float frame_blob_contrast_std;
    int   winning_candidate_index;
    int   candidate_count;
    anomaly_debug_thermal_candidate_t candidates[ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES];
    anomaly_debug_thermal_target_t target;
} anomaly_debug_thermal_t;

typedef struct {
    bool  raw_candidate_valid;
    float raw_score;
    float raw_x_norm;
    float raw_y_norm;
    float target_span_px;
    int   target_span_cells;
    int   max_blob_area_budget;
    int   active_phase_index;
    int   active_phase_x;
    int   active_phase_y;
    bool  selective_reuse_active;
    bool  forced_full_refresh;
    uint32_t fallback_reason_flags;
    int   fresh_sample_count;
    int   carried_sample_count;
    int   unsampled_new_exposed_count;
    float fresh_sample_fraction;
    float carried_sample_fraction;
    float unsampled_new_exposed_fraction;
    int   histogram_valid_sample_count;
    bool  history_reset_applied;
    int   history_recovery_frames_remaining;
    float history_recent_scale;
    int   nonzero_histogram_bins;
    float max_histogram_current_count;
    float max_histogram_recent_count;
    int   rarity_seed_count;
    int   support_seed_count;
    float support_peak_score;
    int   coarse_component_count;
    int   coarse_oversized_count;
    int   dense_verify_component_count;
    int   adaptive_source_coarse_count;
    float fresh_distinctness_ratio;
    int   blob_reject_area_count;
    int   blob_reject_ring_count;
    int   blob_reject_support_mass_count;
    int   blob_reject_quality_count;
    int   blob_examined_count;
    int   strongest_reject_reason;
    float strongest_reject_peak_support;
    float strongest_reject_area;
    float strongest_reject_span;
    float strongest_reject_ring_fraction;
    float strongest_reject_support_mass;
    float strongest_reject_quality;
    anomaly_debug_color_seed_t strongest_seed;
    int   raw_candidate_index;
    bool  winner_gate_active;
    int   winner_gate_reject_reason;
    float winner_gate_max_span;
    float winner_gate_max_area;
    float winner_gate_min_rarity;
    float winner_gate_max_commonness;
    int   winning_candidate_index;
    int   candidate_count;
    anomaly_debug_color_candidate_t candidates[ANOMALY_DEBUG_TOP_COLOR_CANDIDATES];
    anomaly_debug_color_target_t target;
} anomaly_debug_color_t;

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
    int   invalid_reason;
    int   tracked_match_count;
    float fit_a;
    float fit_b;
    float fit_tx;
    float fit_ty;
    float fit_scale;
    float fit_theta_deg;
    float fit_mean_residual;
    float fit_det;
    float fit_min_scale;
    float fit_max_scale;
    float fit_anchor_residual_std;
    float fit_anchor_residual_max;
    float fit_motion_dx_std;
    float fit_motion_dy_std;
    float fit_quadrant_residual_spread;
    anomaly_debug_gmv_anchor_t anchors[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
} anomaly_debug_gmv_t;

typedef enum {
    ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE = 0,
    ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_SHADOW = 1,
    ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE = 2,
} anomaly_movement_estimator_mode_t;

typedef enum {
    ANOMALY_MOVEMENT_LAYER_UNKNOWN = 0,
    ANOMALY_MOVEMENT_LAYER_BACKGROUND = 1,
    ANOMALY_MOVEMENT_LAYER_COHERENT_NEAR = 2,
    ANOMALY_MOVEMENT_LAYER_UNSTABLE = 3,
    ANOMALY_MOVEMENT_LAYER_LOCAL_OUTLIER = 4,
} anomaly_movement_layer_class_t;

typedef struct {
    bool  valid;
    float center_x_norm;
    float center_y_norm;
    float dx_px;
    float dy_px;
    float residual_px;
    float confidence;
    int   layer_class;
} anomaly_debug_movement_tile_t;

typedef struct {
    bool  valid;
    int   mode;
    int   sample_count;
    int   tile_cols;
    int   tile_rows;
    int   background_count;
    int   coherent_near_count;
    int   unstable_count;
    int   local_outlier_count;
    float background_fraction;
    float coherent_near_fraction;
    float unstable_fraction;
    float local_outlier_fraction;
    float residual_mean_px;
    float residual_std_px;
    float local_flow_mean_px;
    float local_flow_std_px;
    float parallax_load;
    float local_outlier_load;
    float confidence;
    float parallax_suppression_scale;
    int   aoi_query_count;
    int   aoi_valid_count;
    int   aoi_independent_count;
    int   aoi_parallax_count;
    int   aoi_unstable_count;
    float aoi_independent_score_mean;
    float aoi_confidence_mean;
    anomaly_debug_movement_tile_t tiles[ANOMALY_MOVEMENT_TILE_COUNT];
} anomaly_debug_movement_t;

typedef enum {
    ANOMALY_REG_INVALID_REASON_NONE = 0,
    ANOMALY_REG_INVALID_REASON_DEBUG_INPUT_UNAVAILABLE = 1,
    ANOMALY_REG_INVALID_REASON_GMV_TOO_FEW_ANCHORS = 2,
    ANOMALY_REG_INVALID_REASON_GMV_FIT_INVALID = 3,
    ANOMALY_REG_INVALID_REASON_GMV_RESIDUAL_TOO_HIGH = 4,
    ANOMALY_REG_INVALID_REASON_GMV_MOTION_TOO_LARGE = 5,
    ANOMALY_REG_INVALID_REASON_GMV_SCALE_OUT_OF_RANGE = 6,
    ANOMALY_REG_INVALID_REASON_AFFINE_ROI_DEGENERATE = 7,
    ANOMALY_REG_INVALID_REASON_AFFINE_TOO_FEW_CORNERS = 8,
    ANOMALY_REG_INVALID_REASON_AFFINE_TOO_FEW_MATCHES = 9,
    ANOMALY_REG_INVALID_REASON_AFFINE_FIT_FAILED = 10,
    ANOMALY_REG_INVALID_REASON_AFFINE_RESIDUAL_TOO_HIGH = 11,
    ANOMALY_REG_INVALID_REASON_AFFINE_MOTION_TOO_LARGE = 12,
    ANOMALY_REG_INVALID_REASON_AFFINE_SCALE_OUT_OF_RANGE = 13,
    ANOMALY_REG_INVALID_REASON_AFFINE_NEGATIVE_DET = 14,
} anomaly_registration_invalid_reason_t;

typedef enum {
    ANOMALY_REG_HEALTH_UNKNOWN = 0,
    ANOMALY_REG_HEALTH_INVALID = 1,
    ANOMALY_REG_HEALTH_HARD_DEGRADED = 2,
    ANOMALY_REG_HEALTH_SOFT_DEGRADED = 3,
    ANOMALY_REG_HEALTH_HEALTHY = 4,
} anomaly_registration_health_t;

typedef enum {
    ANOMALY_RESCAN_MODE_UNSET = 0,
    ANOMALY_RESCAN_MODE_FULL = 1,
    ANOMALY_RESCAN_MODE_PARTIAL = 2,
    ANOMALY_RESCAN_MODE_TARGET_ONLY = 3,
    ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP = 4,
} anomaly_rescan_mode_t;

#define ANOMALY_SCAN_REASON_NO_APPEARANCE_REFRESH  0x0001u
#define ANOMALY_SCAN_REASON_NO_SAMPLES             0x0002u
#define ANOMALY_SCAN_REASON_PREV_STATE_INVALID     0x0004u
#define ANOMALY_SCAN_REASON_SCENE_DISCONTINUITY    0x0008u
#define ANOMALY_SCAN_REASON_REG_INVALID            0x0010u
#define ANOMALY_SCAN_REASON_REG_HARD_DEGRADED      0x0020u
#define ANOMALY_SCAN_REASON_WARP_LOW               0x0040u
#define ANOMALY_SCAN_REASON_NEW_EXPOSED_HIGH       0x0080u
#define ANOMALY_SCAN_REASON_STALE_HIGH             0x0100u
#define ANOMALY_SCAN_REASON_SAMPLE_STEP_MISMATCH   0x0200u
#define ANOMALY_SCAN_REASON_TARGET_ONLY_ELIGIBLE   0x0400u
#define ANOMALY_SCAN_REASON_PARTIAL_ELIGIBLE       0x0800u
#define ANOMALY_SCAN_REASON_MASK_BUILD_FAILED      0x1000u
#define ANOMALY_SCAN_REASON_MASK_EMPTY             0x2000u
#define ANOMALY_SCAN_REASON_MASK_TOO_BROAD         0x4000u
#define ANOMALY_SCAN_REASON_PERIODIC_FULL_REFRESH  0x8000u

#define ANOMALY_ADAPTIVE_STRIDE_REASON_REG_INVALID         0x0001u
#define ANOMALY_ADAPTIVE_STRIDE_REASON_REG_DEGRADED        0x0002u
#define ANOMALY_ADAPTIVE_STRIDE_REASON_SCENE_DISCONTINUITY 0x0004u
#define ANOMALY_ADAPTIVE_STRIDE_REASON_MOVEMENT_LOAD       0x0008u
#define ANOMALY_ADAPTIVE_STRIDE_REASON_TARGET_TRACK        0x0010u
#define ANOMALY_ADAPTIVE_STRIDE_REASON_WEAK_TARGET_LOCK    0x0020u
#define ANOMALY_ADAPTIVE_STRIDE_REASON_TARGET_RICH_RECENT  0x0040u
#define ANOMALY_ADAPTIVE_STRIDE_REASON_STABLE_WINDOW       0x0080u

typedef struct {
    bool  valid;
    anomaly_rescan_mode_t mode;
    int   sampled_width;
    int   sampled_height;
    int   total_samples;
    int   carried_samples;
    int   newly_exposed_samples;
    int   stale_samples;
    int   target_revisit_track_count;
    float warped_valid_fraction;
    float newly_exposed_fraction;
    float stale_fraction;
    uint32_t reason_flags;
    int   refresh_mask_selected_samples;
    float refresh_mask_selected_fraction;
    int   provisional_candidate_count;
    int   provisional_candidate_selected_count;
    int   revisit_confirmation_count;
    int   revisit_salience_boost_count;
    int   revisit_independent_motion_boost_count;
    int   revisit_global_motion_reject_count;
    int   suppressed_offgate_winner_count;
} anomaly_scan_plan_t;

typedef enum {
    ANOMALY_TIMING_STAGE_REGISTRATION_PREP = 0,
    ANOMALY_TIMING_STAGE_REGISTRATION_SOLVE = 1,
    ANOMALY_TIMING_STAGE_MOVEMENT_ESTIMATOR = 2,
    ANOMALY_TIMING_STAGE_SCAN_PLANNING = 3,
    ANOMALY_TIMING_STAGE_REFRESH_MASK_BUILD = 4,
    ANOMALY_TIMING_STAGE_SAMPLED_GRID_PREP = 5,
    ANOMALY_TIMING_STAGE_THERMAL_SCORING = 6,
    ANOMALY_TIMING_STAGE_COLOR_SCORING = 7,
    ANOMALY_TIMING_STAGE_MOTION_SCORING = 8,
    ANOMALY_TIMING_STAGE_SALIENCY_SCORING = 9,
    ANOMALY_TIMING_STAGE_TARGET_TRACKING = 10,
    ANOMALY_TIMING_STAGE_OVERLAY_DRAW = 11,
    ANOMALY_TIMING_STAGE_COUNT = 12,
} anomaly_timing_stage_t;

typedef struct {
    bool compiled;
    int64_t total_us;
    int64_t stage_us[ANOMALY_TIMING_STAGE_COUNT];
} anomaly_debug_timing_t;

// Returned from anomaly_process_frame(); caller may inspect detections.
typedef struct {
    int           box_count;
    anomaly_box_t boxes[ANOMALY_MAX_BOXES_PER_FRAME];
    bool          had_discontinuity;  // true when a scene cut was detected
    bool          registration_ran_this_frame;
    bool          appearance_refresh_ran_this_frame;
    anomaly_registration_health_t registration_health;
    anomaly_rescan_mode_t rescan_mode;
    anomaly_scan_plan_t scan_plan;
    int adaptive_effective_stride;
    int adaptive_stable_frames;
    int adaptive_drop_hold_frames;
    float adaptive_motion_load;
    uint32_t adaptive_reason_flags;
    anomaly_debug_gmv_t gmv_debug;
    anomaly_debug_movement_t movement_debug;
    anomaly_debug_motion_t motion_debug;
    anomaly_debug_thermal_t thermal_debug;
    anomaly_debug_color_t color_debug;
    anomaly_debug_saliency_t saliency_debug;
    anomaly_debug_timing_t timing;
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
