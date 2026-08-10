# Dense Mode Normalization Checkpoint

## Current Status

The redesign scaffolding is in place:

- registration runs every frame
- planner-driven selective refresh is now active
- explicit target tracks exist
- regression suite and reporting are working

Current reviewed regression summary on the PowerHouseTeam excerpts:

- current baseline:
  recall `0.199`, precision `1.000`, realtime `3.224x`
- dense full-scan gold:
  recall `0.041`, precision `1.000`, realtime `1.343x`
- redesigned incremental:
  recall `0.456`, precision `0.987`, realtime `3.669x`

## Main Diagnosis

The dense `--pixel-step 1` comparator is not a valid apples-to-apples control
yet.

Reason:

- the detector is still calibrated in sampled-grid units in several thermal
  appearance paths
- changing from Auto / coarse sampled grids to `pixel_step=1` changes the
  effective real-pixel neighborhood and blob-growth scale
- this likely suppresses recall in dense mode for calibration reasons rather
  than proving dense scanning is worse

## Key Code Locations

### Sample-step defaulting

- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:293)

### Thermal local-statistics window

- [app/src/main/cpp/anomaly_analysis.h](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h:47)
- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:6324)

Problem:

- `ANOMALY_THERMAL_WIN_RADIUS` is fixed in sampled pixels
- at `sample_step=4`, the real window is about 28x28 px
- at `sample_step=1`, the real window is about 7x7 px

### Thermal representative / broad-context logic

- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:2068)
- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:2243)
- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:2398)
- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:2520)

Problem:

- these use `sample_step`-derived cell radii rather than a normalized real-pixel
  target scale

### Thermal small-target ranking and span thresholds

- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:38)
- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:6710)
- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:6715)

Problem:

- apparent-size heuristics still assume the upstream sampled-grid geometry that
  produced the blob

## Recommended Next Pass

Do a narrow normalization pass before any broad retuning.

### Goal

Keep thermal neighborhood and context scales approximately stable in real-pixel
terms across `pixel_step` values.

### First implementation targets

1. Replace fixed sampled-cell thermal window radius usage with an effective
   radius helper derived from a target real-pixel span.

2. Normalize representative blob radius and broad-context radii in the same
   way, using target real-pixel scales converted into sampled-grid cells.

3. Leave score threshold, `min_hits`, and scan zone unchanged at first.

### Suggested approach

Add small helpers, for example:

- `effective_thermal_window_radius_cells(sample_step)`
- `effective_thermal_representative_radius_cells(sample_step)`
- `effective_thermal_context_radius_cells(sample_step)`

These should aim to preserve approximately the same real-pixel footprint that
the current Auto/coarse mode sees on reviewed clips.

## Important Constraint

Do not mix this pass with unrelated planner, target-track, or telemetry changes.
This should be a clean calibration-normalization pass so the regression outcome
is easy to interpret.

## Validation After Next Pass

Run:

- `cmake --build build` in `tools/anomaly_test`
- `./build/anomaly_test`
- `python3 tools/anomaly_test/run_regression_suite.py`

Main question:

- does dense `pixel_step=1` recover toward the redesigned incremental mode or
  at least stop catastrophically underperforming?

Secondary question:

- does redesigned incremental remain strong after the normalization pass?

## Followup: Dense Scan-Zone Sweep

Date:

- 2026-05-06

Scope:

- reviewed regression suite only
- dense full-scan only
- fixed detector args except for `scan_zone`
- no heuristic, threshold, `min_hits`, or planner changes

Dense profiles run:

- `-p bh -a 6 -t 2.8 -m 2 -s 0.6 --registration affine --stride 1 --pixel-step 1`
- `-p bh -a 6 -t 2.8 -m 2 -s 0.5 --registration affine --stride 1 --pixel-step 1`
- `-p bh -a 6 -t 2.8 -m 2 -s 0.4 --registration affine --stride 1 --pixel-step 1`

Reviewed aggregate results:

- `scan_zone=0.6`: recall `0.047`, precision `1.000`, realtime `3.031x`,
  `TP 8 / FP 0 / misses 163`
- `scan_zone=0.5`: recall `0.023`, precision `1.000`, realtime `4.226x`,
  `TP 4 / FP 0 / misses 167`
- `scan_zone=0.4`: recall `0.000`, precision `n/a`, realtime `6.236x`,
  `TP 0 / FP 0 / misses 171`

Crop-adjusted target-excerpt context:

- positives still inside centered ROI:
  `156/171` at `0.6`, `144/171` at `0.5`, `99/171` at `0.4`
- dense recall on positives that remained inside the ROI:
  `7/156 = 0.045` at `0.6`, `4/144 = 0.028` at `0.5`,
  `0/99 = 0.000` at `0.4`

Interpretation:

- smaller scan zones materially improved runtime
- smaller scan zones did not improve dense full-scan recall
- the recall drop is not explained only by crop-out; dense mode remains weak
  even on targets that stay inside the smaller ROIs
- this does not support the theory that dense full-scan is mainly suffering
  from clutter/context at `scan_zone=0.6`

Decision:

- the next pass should focus on dense-mode small-target
  ranking/normalization heuristics rather than another scan-zone-only sweep

## Added Considerations For Next Investigation

Date:

- 2026-05-07

New concerns to explicitly evaluate before the next selection-pass change:

- permissive dense contrast / blob-growth may be merging a real small target
  into a larger nearby warm component, shifting the extracted peak away from
  the annotated target
- area/span-first ordering, top-8 capping, or local NMS may then drop the
  merged-or-smaller target candidate even when a target-related component was
  extracted
- tree hits appear to be returning with the latest change, so context handling
  needs to be checked as a potential source of false attraction
- current context weighting is approximately `37%`; verify whether this is
  over-penalizing true targets, under-penalizing tree structure, or both

Working hypothesis after the first dense target-trace review:

- dense misses are not explained mainly by blob-growth gates rejecting the
  target outright
- many misses appear to be extracted target-related components that do not
  survive the final thermal candidate ordering / capping path
- however, component merging and peak drift remain live suspects and should be
  measured alongside ranking
