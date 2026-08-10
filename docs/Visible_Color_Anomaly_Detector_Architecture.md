# Visible-Light Color Anomaly Detector Architecture

Current implementation note: for the consolidated current-state detector
reference, including app defaults, FFmpeg threading, scan planning, IR,
motion, saliency, and runtime caveats, start with
[Current_Anomaly_Detector.md](/Users/kjt/Projects/RID2Caltopo/docs/Current_Anomaly_Detector.md).

This note documents the current visible-light color anomaly path in
`app/src/main/cpp/anomaly_analysis.c` and `app/src/main/cpp/anomaly_analysis.h`.
It is meant as an engineering reference for future tuning and refactoring work.

The description below is intentionally code-accurate to the current
implementation, even where older handoff notes described the intended behavior
in slightly broader terms.

## Scope

The color path lives inside `anomaly_process_frame()` in
`app/src/main/cpp/anomaly_analysis.c`.

## Production vs Experimental

- IR / thermal detection still enters the analyzer through the same RGBA-fed
  `anomaly_process_frame()` path.
- Playback, overlay rendering, and the FFmpeg display conversion path are
  unchanged by the color frontend work in this branch.
- Visible-color frontend experiments are controlled by the internal
  `color_frontend_mode` setting:
  - `legacy`
  - `fresh_rgba`
  - `fresh_yuv`
- At the current analyzer boundary, `fresh_yuv` falls back to `fresh_rgba`
  because the public detector entrypoint is still RGBA-based.
- Any color experiment must be validated with color debug target tracing both
  enabled and disabled to confirm instrumentation does not perturb decisions.

The main stages are:

1. sampled-grid preparation
2. histogram-based chroma rarity scoring plus rescue seeding
3. support-map formation from front-end color evidence
4. blob extraction with cohesion-aware joining
5. candidate ranking and optional post-candidate temporal boost
6. downstream use in motion proposals, saliency, and persist logic

Relevant helper functions and structs:

- `effective_sample_step()`
- `build_color_support_map()`
- `extract_color_blob_candidates()`
- `collect_motion_candidates()`
- `classify_saliency_display_algorithm()`
- `anomaly_color_blob_candidate_t`
- `anomaly_debug_color_t`

## High-Level Data Flow

The current color path no longer tries to pick the single strangest sampled
pixel and publish it directly. Instead, it builds a sampled-grid rarity field,
converts that field into a patch-shaped support map, extracts connected blobs
from the support map, ranks those blobs for target plausibility, and only then
chooses a winning color candidate.

At a glance:

1. Downsample the ROI into a sampled grid (`sg_luma` plus sampled color state).
2. Score each sampled cell for histogram rarity and local support.
3. Turn isolated front-end color evidence into patch support.
4. Flood-fill connected supported cells into color blobs.
5. Reject large, diffuse, or poorly isolated blobs.
6. Rank the remaining blobs and publish the winner as `best_color_x/y`.

## 1. Sampled-Grid Prep

The color path operates on the same sampled ROI grid used by the rest of the
detector. `effective_sample_step()` currently defaults to:

- `4` for `1280x720` and above
- `2` below that
- an explicit `cfg->pixel_step` overrides the default

During `ANOMALY_TIMING_STAGE_SAMPLED_GRID_PREP`, the code still builds the
frame-local sampled luma grid (`sg_luma`) and luma integral images, but color
sampling is a separate path with two operating modes.

`prepare_color_sampling_state()` maintains persistent per-cell color state in
`anomaly_roi_state_t`:

- `color_luma`, `color_u`, `color_v`
- `color_valid_mask`
- `color_phase_x`, `color_phase_y`
- `color_raw_score`
- `color_contrast_weight`

In `legacy` mode:

- selective refresh may affine-carry prior color samples forward
- refreshed cells are resampled from the current frame
- non-refreshed cells may be affine-carried from the prior ROI state
- newly exposed cells that cannot be carried are marked invalid

In `fresh_rgba` mode:

- the active ROI color grid is fully resampled every analyzed frame
- prior color state is not used as front-end color evidence
- temporal logic is applied only after color candidates are formed

So the production legacy path remains stateful, while the experimental fresh
path is intentionally current-frame-first.

What this stage is trying to measure or suppress:

- Measure a cheap, stable sampled representation of the ROI rather than work on
  every source pixel.
- Preserve enough local color structure for small visible targets.
- Keep the color path aligned with the same grid used later by thermal, motion,
  saliency, ROI-state updates, and debug output.

## 2. Histogram Rarity and Rescue Seeding

After prep, the color path computes a raw per-cell rarity score inside the main
sampled-grid loop.

For each sampled cell:

- sampled RGB is converted to detector-side `u` and `v`
- `u/v` bins are accumulated into a frame histogram
- a family-rarity LUT is built from the current-frame histogram plus recent
  history
- local UV support and contrast rescue are used to turn rarity into front-end
  color evidence
- the result is written into `saliency_color_map`

Important current terminology:

- `color_raw_score` stores front-end color evidence inputs such as rarity
- `color_contrast_weight` is now used as a blob cohesion weight, not as a
  separate rarity term
- in fresh frontend mode, pre-support temporal rescue is disabled and temporal
  influence is moved to post-candidate ranking

What this stage is trying to measure or suppress:

- Measure cells whose color is unusual relative to their local neighborhood.
- Add a weaker brightness rarity term so very odd bright/dark patches can still
  help color scoring.
- Suppress ordinary scene color variation by using family rarity plus local
  support instead of directly publishing the strongest odd sample.

## 3. Color Support-Map Formation

Raw front-end color evidence is still too pixel-centric, so
`build_color_support_map()` converts `saliency_color_map` into a patch-support
field.

The support radius comes from `color_support_patch_radius()`, which scales from
the configured target size and `sample_step`, then clamps to `4` cells.

For each active sampled cell with positive raw score, the code looks at the
square patch around that cell and computes:

- `center`: raw score at the center cell
- `mean`: mean positive raw support in the patch
- `density`: fraction of patch cells with support at least `0.35`
- `ring_mean`: average support on the patch perimeter

Current support formula:

- `patch_support = 0.55 * center + 0.75 * mean + 0.90 * density - 0.30 * ring_mean`
- result is clamped to `[0, 4]`

Current-frame blob cohesion now contributes only here and during blob joining.
Legacy mode keeps this weight at `1.0` so production behavior stays compatible
while fresh frontend mode uses current-frame local color similarity.

Seed bookkeeping also happens here:

- peak support in the active region
- count of cells with support at least `0.55`
- min/max sampled coordinates of those seed-strength cells

What this stage is trying to measure or suppress:

- Measure whether a rare-color response looks like a small supported patch
  rather than a single odd sample.
- Reward local agreement and compact support density.
- Suppress broad fields of similar color via the perimeter penalty.

This stage is the architectural bridge from "rare cell" to "target-like patch."

## 4. Blob Extraction and Ranking

`extract_color_blob_candidates()` flood-fills connected supported cells into
candidate color blobs.

Growth starts only from support seeds at `>= 0.55`. Neighbor cells join if they
clear:

- a minimum support floor (`join_floor`)
- a similarity band relative to the current cell and running blob mean
- in fresh frontend mode, a current-frame color cohesion check relative to the
  neighboring sampled color cells

For each connected component, the extractor measures:

- `area`
- `span`
- `fill`
- `peak_support`
- `mean_support`
- `center_share`
- `ring_fraction`
- `support_mass`
- `isolation_score`

Hard rejections currently include:

- blob area above the allowed target-sized cap
- `ring_fraction >= 0.36`
- very high nearby support mass combined with too much near support

The scoring logic then favors blobs that are:

- target-sized
- compact
- filled rather than sparse
- center-peaked
- isolated from surrounding similar support

The final per-blob score is stored in `candidate.color_score`. In fresh
frontend mode, an additional post-candidate temporal boost may be applied to a
current-frame blob near the active persisted track. Dense `sample_step <= 1`
mode also computes a `retention_rank`, which is used as the primary ranking
signal before area/span/score tie-breakers.

What this stage is trying to measure or suppress:

- Measure connected target-like colored patches, not isolated weird pixels.
- Suppress diffuse colored regions, edges of large colored objects, and broad
  scene areas that are unusual but not target-like.
- Bias selection toward compact small blobs that match the intended visible
  target scale.

## 5. Color Candidate Selection

After blob extraction, the detector copies candidate metrics into debug-facing
arrays and chooses the published color winner.

Selection behavior is:

- only blobs whose `color_score >= cfg->score_threshold` are eligible to become
  the winning color candidate
- among thresholded blobs, `compare_color_blob_rank()` chooses the winner
- if no blob clears threshold, the best sub-threshold blob still becomes the raw
  `best_color` fallback

If a winning blob exists:

- `best_color`
- `best_color_x`
- `best_color_y`
- `best_color_candidate_idx`

are assigned from that blob's peak cell.

Debug output is exported through `anomaly_debug_color_t` in
`anomaly_analysis.h`, including:

- winning candidate index
- candidate count
- candidate bbox
- base and final score
- area/span/fill
- quality and isolation metrics

## How This Differs From the Older Per-Pixel Color Outlier Path

The older color path effectively stopped after per-cell rarity scoring and kept
the strongest sampled pixel as the color winner.

The current path is different in three important ways:

1. The per-cell score is now an intermediate representation, not the final
   decision.
2. Candidate extraction is component-based, so spatial support matters.
3. Final ranking is target-shaped and isolation-aware, not just "highest odd
   pixel wins."

In practice, this changes the detector from:

- "find the strangest sampled color response"

to:

- "find a compact, locally rare, target-sized patch of supported color"

That is the main architectural reason the current path should be less sensitive
to one-off color spikes and textured scene clutter than the older approach.

## Selective Refresh Interaction

The color path is now selective-refresh aware in four separate places.

### Stateful color reuse

`prepare_color_sampling_state()` can affine-carry prior color samples forward
for cells outside the refresh mask, instead of resampling the whole ROI every
frame.

That reuse is phase-aware (`color_phase_x/y`, `color_phase_counter`) and only
applies while the prior ROI state and registration remain valid.

### Per-cell scoring

The main sampled-grid scoring loop skips cells where
`appearance_refresh_mask[idx] == 0`.

That avoids recomputing raw color rarity for stale cells outside the refreshed
appearance region.

The current normalization and local contrast model also stay fresh-frame
anchored during selective refresh:

- rarity stats are built from refreshed cells only
- contrast weights are built from refreshed-neighbor comparisons only
- cells without enough refreshed local support fall to a low contrast weight

### Active bounds for post-pass work

Before the blob post-pass, the code derives active sampled-grid bounds from the
refresh mask via `compute_active_mask_bounds()`.

Those bounds are padded by the color support radius so support-map formation can
still see the local neighborhood it needs.

### Reduced blob-pass work

`build_color_support_map()` only sweeps the active bounded region, then returns
tighter seed bounds for cells that actually reached seed-level support.

`extract_color_blob_candidates()` only runs if:

- peak support reached `0.55`
- seed count is non-zero
- valid seed bounds exist

And when it does run, it only scans the seed-bounded region instead of the full
sampled ROI.

This means the current post-pass has:

- selective-refresh-aware bounds
- an early exit before blob extraction
- tighter seed-bounds extraction for the blob sweep

## How Color Support Feeds Motion Candidate Logic

Color support is not just for the color detector itself.

`collect_motion_candidates()` uses both thermal and color support maps to create
motion proposal seeds:

- proposal starts from thermal support
- color adds `0.60 * color_score` when thermal is also present
- color adds `0.85 * color_score` when thermal is absent

So a strong color-only patch can still become a motion proposal seed, and a
joint thermal-plus-color patch becomes a stronger motion proposal than thermal
alone.

Later, when motion support is computed, a reliable motion candidate can boost
the current color winner:

- `motion_candidates[ci].color_score` feeds the motion-based color boost logic
- a strong motion-supported candidate can raise `best_color` or replace the
  current color winner if it is well separated and materially stronger

## How Color Support Feeds Saliency and Persist

### Unified saliency

When `ANOMALY_ALGO_PERSIST` is enabled, unified saliency uses color support as
part of the spatial evidence term.

At each sampled cell:

- thermal spatial evidence contributes directly
- color contributes as `0.60 * color_support`
- motion contributes to temporal evidence
- registration support scales the result

So color is a secondary spatial cue inside saliency, not an independent
temporal cue.

`classify_saliency_display_algorithm()` also uses the same weighted color term
when deciding whether a persisted/saliency track should be displayed as thermal,
color, motion, or generic persist.

### Persist candidate derivation

Later in the frame, the code derives a published `best_persist` candidate from
the winning appearance candidates.

For color:

- start from `best_color`
- add `0.50 * support`
- add `0.20 * motion_support`

This `support` term comes from the saliency patch-selection map, not directly
from the raw color support map, but the color winner can only get here because
the blob-based color path already produced a spatially supported winner.

## Current Optimization State

At a high level, the color blob path is in a better measured and cheaper state
than the first blob-pass landing, but it still preserves the current
dataflow-compatible copy behavior.

Current state:

- timing coverage now includes the blob post-pass, not just the raw per-cell
  rarity loop
- selective-refresh-aware active bounds are implemented for the support/blob
  pass
- an early exit skips blob extraction when no viable support seeds exist
- tighter seed-bounds extraction limits the blob sweep to the support-bearing
  region

One important non-optimization:

- an attempted copy-elision optimization was reverted because it changed
  behavior

That matches the current code structure in `build_color_support_map()`: support
is first written into a scratch buffer and then copied back into
`saliency_color_map` when the buffers differ. Downstream users still expect
`saliency_color_map` to hold the support result after the post-pass.

The timing and optimization history is captured in:

- `docs/IR_Anomaly_Handoff_20260508_ColorBlobPlan.md`
- `docs/IR_Anomaly_Handoff_20260508_LiveADAndColor.md`

## Practical Summary

The current visible-light color detector is best thought of as a
sampled-grid, support-map, and blob-ranking pipeline:

- sampled-grid prep makes the ROI cheap to analyze
- per-cell scoring finds locally rare color responses
- support-map formation turns those responses into patch evidence
- blob extraction chooses compact target-like connected regions
- the winning blob then feeds color publication, motion proposals, saliency,
  and persist logic

That is the core architectural change from the earlier per-pixel color outlier
path, and it is the right baseline to preserve when doing future tuning or
further optimization work.
