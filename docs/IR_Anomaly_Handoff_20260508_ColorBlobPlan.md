# IR Anomaly Handoff: Color Blob Redesign Plan

## Why This Handoff Exists

This note captures the current state of the visible-light anomaly-detector work
so the next thread can start implementation without replaying the live AD
stability work or the initial color-path investigation.

The main conclusion is:

- the current color anomaly path is still architecturally much simpler than the
  IR / thermal path
- it is scoring "odd sampled pixels" rather than "target-sized rare patches"
- that likely explains the high false-positive rate in color mode

## Current Live / IR Context

The live anomaly backpressure work is in a decent place and should stay stable
while color work proceeds.

Reference:

- `/Users/kjt/Projects/RID2Caltopo/docs/IR_Anomaly_Handoff_20260508_LiveADAndColor.md`

That handoff established:

- live AD no longer hard-freezes under burst load
- staged pressure modes are behaving sensibly in flight
- color profiling is the preferred next focus

## What Was Added In This Thread

### 1. Color manifest source clips were populated

Updated:

- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/regression_suite_color_manifest.json`

Added full-source entries for:

- `Color1.mp4`
- `Color2.mp4`
- `Color3.mp4`

`excerpts` is still intentionally empty because these clips do not yet have
reviewed windows.

### 2. Timing baseline runs were produced for the color clips

The timing-enabled harness build is available at:

- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/build_timing/anomaly_video_test`

Baseline outputs were written under:

- `/tmp/color_regression_baseline/visible-color-baseline/`
- `/tmp/color_regression_baseline/visible-color-dense-gold/`

Important harness limitation discovered during the run:

- `tools/anomaly_test/run_regression_suite.py` only executes reviewed
  `excerpts`
- because the color manifest currently has no excerpts, the suite runner does
  not yet exercise the full clips directly
- for the baseline pass, the binary was run clip-by-clip instead

## Baseline Findings

### Auto-detail color profile

Profile:

- `-a 5 -t 2.8 -m 2 -s 0.8 --registration affine --stride 1`

Results:

- `Color1`: `2.15x` realtime, `261` total box events
- `Color2`: `2.05x` realtime, `807` total box events
- `Color3`: `1.41x` realtime, `73` total box events

Interpretation:

- desktop harness throughput is still faster than realtime in Auto detail
- but the detector is firing extremely often
- this supports the user's observation that color mode is producing too many
  false positives

### Dense color profile

Profile:

- same as above, plus `--pixel-step 1`

Results:

- `Color1`: `0.26x` realtime, avg total `124.67 ms`
- `Color2`: `0.26x` realtime, avg total `124.64 ms`
- `Color3`: `0.25x` realtime, avg total `127.37 ms`

Dense-mode stage timings were consistently dominated by a broad appearance /
refresh cost mix rather than one isolated stage:

- `registration_solve`: about `8-11 ms`
- `scan_planning`: about `15 ms`
- `refresh_mask_build`: about `15 ms`
- `sampled_grid_prep`: about `15 ms`
- `thermal_scoring`: about `18 ms`
- `color_scoring`: about `8.6-8.9 ms`

Interpretation:

- the user's "slower than render" observation for color mode is consistent with
  dense sampling
- the cost problem is not just `color_scoring`; dense appearance processing as a
  whole gets expensive

## Most Important Architectural Finding

The current color anomaly path is not yet doing the thing the user wants.

Desired behavior:

- scan the frame for a small target-sized patch of color / intensity that is
  not represented elsewhere in the frame or recent frame history

Current behavior in native code:

- sample the ROI at `sample_step`
- convert each sampled pixel to chroma (`u`, `v`)
- compare that one sampled pixel to the mean/std of a coarse local tile
- keep the best single pixel as the color winner

Relevant code:

- sample-step default: `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c`
- local tile stats: same file
- color scoring loop: same file

Specifically:

- `effective_sample_step()` defaults to `4` at `1280x720+` unless overridden
- the color cue uses per-sampled-pixel `u` / `v` deviation from an `8x8` tile
  distribution
- there is no connected-component extraction for color
- there is no target-sized blob scoring for color
- there is no temporal color-history model
- there is no explicit "uniqueness versus the rest of frame" test

In short:

- color is still "best odd pixel"
- thermal is already much closer to "best target-like small cluster"

## Why The IR / Thermal Path Matters Here

The thermal path already has the structure that color likely needs.

`extract_thermal_blob_candidates()` in:

- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c`

already does the following:

- flood-fills connected hot samples into components
- measures `area`, `span`, `fill`, `center_share`, `peak_delta`, `mean_delta`
- evaluates ring contamination and surrounding support mass
- rejects components that are too broad, too embedded, or too unlike a small
  target
- biases ranking toward target-sized compact blobs

That makes the thermal blob path the best template for a color redesign.

## Recommended Redesign Direction

### Phase 1: replace color best-pixel scoring with a color support map

Do not pick a winner directly from per-pixel chroma Z-scores.

Instead:

- compute a target-sized patch descriptor for each sampled cell
- include at least luma plus chroma, not chroma alone
- score how rare that patch is relative to local context and broader frame
  context
- write the result into a `color_support_map`

### Phase 2: extract connected color blobs

Add a color blob extractor modeled on `extract_thermal_blob_candidates()`.

For each connected component above support threshold, compute:

- `area`
- `span`
- `fill`
- `center_share`
- patch rarity score
- surrounding-ring similarity / contamination
- nearby support mass

Then reject candidates that are:

- too large
- too diffuse
- too broadly surrounded by similar color
- too weakly separated from the frame's ordinary variation

### Phase 3: add temporal color normalization

Add a short recent-frame baseline for color rarity so a candidate must beat:

- an absolute floor
- the current frame's own noise floor
- recent ordinary color-difference variation

This is conceptually similar to what the thermal path already does with the
temporal background / per-frame normalization idea, but it should operate on
patch-level color rarity rather than hot/cold luma deltas.

## Practical First Implementation Pass

The first implementation pass should stay intentionally narrow:

1. Keep the existing live/backpressure behavior unchanged.
2. Keep the current color manifest separation unchanged.
3. Do not add temporal color history yet.
4. Replace color best-pixel selection with:
   - a `color_support_map`
   - connected-component extraction
   - target-sized blob ranking and gating
5. Preserve the existing downstream tracking / accumulator plumbing as much as
   possible by feeding it a blob winner instead of a single odd pixel winner.

Reason:

- this gets the architecture closer to the right shape without taking on both
  blob redesign and temporal normalization in one step

## Concrete Implementation Targets For Next Thread

### A. Add a color blob candidate type and extractor

In:

- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c`

Likely mirror the thermal candidate pattern:

- candidate struct with bbox + area/span/fill + quality fields
- extraction helper parallel to `extract_thermal_blob_candidates()`
- insertion / ranking helper for top color candidates

Minimum first-pass fields are likely:

- peak sample location
- peak pixel location
- bbox min/max sample coords
- `area`
- `span`
- `fill`
- `base_score`
- `final_score`
- reserved room for future temporal-normalized score

### B. Build color support around target-sized patch rarity

Use the configured target footprint as the anchor.

Current target-size hook:

- `cfg->min_area_fraction`

Current target-track sizing reference:

- same file, where `target_half_side` is derived from `min_area_fraction`

The color support score should be patch-based, not single-pixel-based.

Good reuse target from the thermal path:

- mirror the flood-fill growth, ring contamination checks, support-mass checks,
  target-size scaling, and dense-mode retention-rank pattern from the thermal
  blob extractor rather than inventing a fresh selection path

The most directly reusable thermal helpers / patterns are:

- target-size normalization helpers
- candidate ranking / capped insertion
- similarity-bounded component growth
- ring and surrounding-support rejection gates
- multiplicative component quality scoring
- dense-mode retention rank for `sample_step <= 1`

### C. Add color debug output similar to thermal debug

Needed so tuning does not become guesswork.

Useful fields:

- winning color candidate index
- candidate count
- bbox
- area/span/fill
- local rarity score
- frame-normalized score
- future temporal-normalized score
- rejection gate

Best first-pass shape:

- add a dedicated color debug payload beside the existing thermal debug payload
- export top color blob candidates with bbox and component metrics
- keep external anomaly boxes unchanged for now

### D. Only after blob extraction is working, add recent-frame color history

Do this as a second pass.

Possible shapes:

- EMA of framewide patch-difference stats
- short rolling window of recent candidate / background descriptor spread

The key contract:

- candidate should exceed recent ordinary variation, not just a fixed
  threshold

## Open Design Questions

1. What exact patch descriptor should color use first?
   Most likely: local means of `luma`, `u`, and `v`, possibly with simple local
   spread terms.

2. What is the cheapest reasonable "rarity versus rest of frame" proxy?
   Full all-pairs patch comparison is probably too expensive.

3. Should the first blob pass use dense `pixel_step 1` for quality validation
   only, while live color remains Auto detail?

4. After blob extraction exists, does the current color threshold retain any
   useful meaning, or should it be reinterpreted as blob-quality threshold?

## Minimum Refactor Shape

The cleanest narrow first pass appears to be:

1. keep the existing sampled-grid and tile-stat prep
2. keep `scratch_saliency_color` as the per-cell color support map
3. reuse existing scratch `u8` and `i32` buffers for `visited` and BFS queue
4. replace only the current color "best pixel wins" block with:
   - color support-map construction
   - connected color-component extraction
   - top color-blob ranking
   - `best_color_x/y` assignment from the winning blob
5. leave downstream publish / tracking / box assembly unchanged for the first
   pass

That gives the next thread a relatively low-risk internal refactor:

- same external contract
- much better target-shape semantics
- clear place to add temporal color normalization later

## Suggested Next Thread Workflow

1. Read this handoff and the live/color handoff from earlier on 2026-05-08.
2. Inspect the current color scoring loop and `extract_thermal_blob_candidates()`.
3. Implement a `color_support_map` plus blob extraction without temporal color
   history first.
4. Run the same three `Color*.mp4` clips in:
   - Auto detail
   - dense `--pixel-step 1`
5. Compare:
   - box event counts
   - stage timing
   - whether detections collapse from "almost every frame" to a much smaller
     set of compact candidates
6. Only then add temporal color normalization.

## Implementation Update After First Blob Pass

Date:

- `2026-05-08`

The first-pass color blob redesign described above has now been implemented in:

- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c`
- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h`
- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/anomaly_video_test.c`

What changed:

- color per-cell scoring now mixes tile-relative chroma rarity with a luma
  rarity term
- the old "best sampled pixel wins" path was replaced with:
  - patch-shaped `color_support_map` construction
  - connected-component color blob extraction
  - blob ranking/gating with target-size, fill, ring, and support-mass
    heuristics
  - `best_color_x/y` assignment from the winning blob
- downstream publish / tracking / anomaly-box plumbing was intentionally left
  unchanged for the first pass
- a dedicated `color_debug` payload and harness debug dump were added so color
  candidates can be inspected similarly to thermal candidates

Verification completed in this thread:

- `tools/anomaly_test/build_timing/anomaly_test` passed `67/67`
- `tools/anomaly_test/build_timing/anomaly_video_test` rebuilt successfully

## First Smoke Result

Using:

- `Color1.mp4`
- profile `-a 5 -t 2.8 -m 2 -s 0.8 --registration affine --stride 1`

Comparison against the saved baseline:

- baseline:
  - realtime `2.145x`
  - `185` detection frames
  - `261` total boxes
- first blob-pass smoke:
  - realtime `1.713x`
  - `62` detection frames
  - `67` total boxes

Interpretation:

- false targets were reduced substantially on the first visible smoke run
- the runtime cost increased somewhat, but host throughput remained faster than
  realtime in Auto detail
- this is directionally promising enough that the next focus should be
  optimization and better measurement, not another architectural rewrite yet

## Important Timing Caveat

The current stage timing now under-reports the true cost of the color path.

Reason:

- `ANOMALY_TIMING_STAGE_COLOR_SCORING` still measures only the per-cell
  color-rarity loop
- the new post-pass work:
  - `build_color_support_map()`
  - `extract_color_blob_candidates()`
  - top-candidate selection
  currently runs outside the existing color timing bucket

Observed symptom on the first smoke run:

- saved stage timing still showed `color_scoring` around `0.49 ms`
- but realtime factor dropped from `2.145x` to `1.713x`
- so the added cost is currently being hidden inside untimed work

This should be fixed before drawing strong optimization conclusions from
per-stage telemetry.

## Optimization Direction For Next Thread

The IR / thermal performance work suggests a good optimization order.

Reference:

- `/Users/kjt/Projects/RID2Caltopo/docs/IR_Anomaly_Handoff_20260507_Perf.md`

The thermal path got its biggest wins from:

- removing dominant hotspots first
- using reuse / skip logic in stable frames
- focusing on planner / mask / prep after registration solve stopped
  dominating

For the color blob path, the likely next optimization steps are:

1. Fix telemetry first.
   - fold color support-map build + blob extraction into the color timing
     stage, or add a dedicated timing bucket if that is cleaner
   - do this before chasing micro-optimizations

2. Make color post-processing selective-refresh aware.
   - the initial per-cell color score loop already skips
     `appearance_refresh_mask[idx] == 0`
   - but `build_color_support_map()` and `extract_color_blob_candidates()`
     currently still iterate the whole sampled ROI
   - the thermal-path incremental work showed that preserving full-ROI loops in
     partial / target-only frames can erase expected runtime gains

3. Shrink post-pass work to the active bounds instead of the full ROI.
   - derive min/max active sampled coordinates from the refresh mask
   - run color support + blob extraction only over those bounds when safe

4. Avoid the current extra whole-frame pass structure where possible.
   - right now color does:
     - sampled-grid prep
     - per-cell color rarity write
     - full support-map pass
     - full blob-extraction pass
   - likely next win:
     - fuse support-map formation with candidate seeding, or at least eliminate
       one of the extra ROI sweeps

5. Add cheap early exits before blob extraction.
   - if no cell exceeds a seed threshold, skip the blob pass entirely
   - if active refreshed area is tiny and no local maxima survive, keep the old
     empty result quickly

6. Reuse IR-style stable-frame ideas carefully.
   - not full detector-result reuse yet
   - but safe one-frame reuse of color candidate lists or active support bounds
     may be possible in very stable target-only / partial cases
   - only after timing proves post-pass color work is a meaningful hotspot

## Most Likely Hotspots In The New Color Path

The new code paths most worth profiling first are:

- `build_color_support_map()`
- `extract_color_blob_candidates()`
- the temporary full-ROI scratch clears / scans around them

By contrast, the saved `color_scoring` bucket by itself is no longer the whole
story.

## Recommended Next Thread Workflow

1. Add accurate timing coverage for the new color support/blob work.
2. Run at least `Color1/2/3` Auto-detail timing again with the updated
   instrumentation.
3. Check how often the scan planner is in `FULL`, `PARTIAL`, and
   `TARGET_ONLY`.
4. Make color support/blob extraction honor selective-refresh bounds.
5. Re-run the same clips and compare:
   - realtime factor
   - true color-stage cost
   - detection-frame / total-box deltas
6. Only after that decide whether a deeper fusion/reuse pass is necessary.

## Relevant Files

- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c`
- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h`
- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/regression_suite_color_manifest.json`
- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/README.md`
- `/Users/kjt/Projects/RID2Caltopo/docs/IR_Anomaly_Handoff_20260508_LiveADAndColor.md`

## Follow-up Validation After Timing Coverage + Refresh-Bounds Pass

Two follow-up changes were then implemented in
`/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c`:

1. `ANOMALY_TIMING_STAGE_COLOR_SCORING` was expanded to include:
   - the per-cell color scoring loop
   - `build_color_support_map()`
   - `extract_color_blob_candidates()`
   - color candidate selection
2. The color support/blob post-pass was made selective-refresh aware by:
   - deriving active sampled-grid bounds from `appearance_refresh_mask`
   - padding those bounds for the color support neighborhood
   - limiting support-map and blob-extraction sweeps to those bounds during
     `PARTIAL` and `TARGET_ONLY`

Timing-enabled validation was rerun on `2026-05-08` with:

```sh
/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/build_timing/anomaly_video_test \
  /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Color1.mp4 \
  -a 5 -t 2.8 -m 2 -s 0.8 --registration affine --stride 1 \
  --summary-json /tmp/color1_colorblob_timing_summary.json --no-video
```

and the same detector args for `Color2.mp4` and `Color3.mp4`.

### Auto-detail timing results after the fix

- `Color1`
  - realtime: `1.886x`
  - frames: `189`
  - detection frames: `62`
  - total boxes: `67`
  - rescan mix: `full=47`, `partial=43`, `target_only=99`
  - avg total: `15.26 ms`
  - avg `color_scoring`: `2.19 ms`
- `Color2`
  - realtime: `1.706x`
  - frames: `590`
  - detection frames: `388`
  - total boxes: `489`
  - rescan mix: `full=148`, `partial=45`, `target_only=397`
  - avg total: `17.89 ms`
  - avg `color_scoring`: `2.43 ms`
- `Color3`
  - realtime: `1.306x`
  - frames: `54`
  - detection frames: `35`
  - total boxes: `41`
  - rescan mix: `full=14`, `partial=5`, `target_only=35`
  - avg total: `19.84 ms`
  - avg `color_scoring`: `2.24 ms`

### What changed relative to the earlier caveat

- the saved `color_scoring` bucket is no longer falsely tiny
- the visible color stage now shows up consistently around `2.2-2.4 ms`
  average on the current Auto-detail smokes
- all three visible clips remained faster than realtime after the timing and
  selective-refresh-bounds pass

### Current interpretation

- the telemetry problem is fixed enough to trust `color_scoring` as the full
  color-path bucket for this iteration
- selective refresh is still dominated by `TARGET_ONLY` on the visible clips,
  so shrinking color post-pass work to the active bounds was the right
  correctness-preserving optimization to do before deeper fusion
- the next likely dominant cost is still registration solve, not color, on
  these Auto-detail runs

### Small follow-up optimization after the rerun

A cheap early exit was then added so color blob extraction is skipped when the
support-map pass never produces a viable seed-level support value.

Quick recheck on `Color1` after that guard:

- realtime: `1.904x` vs prior `1.886x`
- avg total: `15.09 ms` vs prior `15.26 ms`
- avg `color_scoring`: `2.19 ms` vs prior `2.19 ms`
- detection frames: unchanged at `62`
- total boxes: unchanged at `67`

So the guard appears behavior-neutral on the first visible smoke while shaving
off a small amount of useless post-pass work.

### Additional follow-up: tighter extraction seed bounds

The next small optimization was to let the support-map pass return the bounds
of cells that actually reached blob-seed support, then run blob extraction only
inside that tighter seed region instead of the whole active refresh bounds.

This was rechecked on `Color2`, which is the longest visible clip here and the
one with the heaviest `TARGET_ONLY` mix.

`Color2` progression across the three post-blob optimization steps:

- after timing fix + selective-refresh bounds:
  - realtime: `1.706x`
  - analysis wall: `11.533 s`
  - avg total: `17.89 ms`
  - avg `color_scoring`: `2.43 ms`
- after early-exit:
  - realtime: `1.735x`
  - analysis wall: `11.339 s`
  - avg total: `17.63 ms`
  - avg `color_scoring`: `2.40 ms`
- after seed-bounds extraction tightening:
  - realtime: `1.785x`
  - analysis wall: `11.021 s`
  - avg total: `17.13 ms`
  - avg `color_scoring`: `2.37 ms`

Across those steps, `Color2` detection behavior stayed unchanged at:

- frames: `590`
- detection frames: `388`
- total boxes: `489`

That makes the current optimization staircase look credible:

- telemetry fix first
- then remove obviously wasted blob work
- then shrink blob extraction to the support-bearing region

All three were behavior-stable on the checked visible smokes.

### One experiment that did not hold up

An attempted follow-up optimization tried to avoid copying the computed color
support map back into the main color saliency buffer and instead feed later
downstream users directly from the support-map scratch buffer.

That experiment was not behavior-preserving:

- `Color2` dropped from `388` to `369` detection frames
- total boxes dropped from `489` to `465`
- runtime also got worse rather than better

So that change was backed out. The current known-good state keeps:

- the timing fix
- selective-refresh bounds for color post-pass
- early-exit before blob extraction
- seed-bounds tightening for blob extraction

but does **not** remove the color support-map copy-back step.

### Recommended next optimization pass

1. Look for an extra pass reduction between support-map formation and candidate
   seeding rather than deeper architectural fusion first.
2. If visible-light runtime becomes the priority, profile `Color2` first,
   because it has the longest run here and the highest average
   `color_scoring` bucket of the three rerun clips.
