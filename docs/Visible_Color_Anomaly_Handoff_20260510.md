# Visible-Color Anomaly Detector Handoff (2026-05-10)

This note is intended as a practical handoff for the next thread working on
visible-light Color AD.

It documents:

- the current architectural shape of the color detector
- what this thread confirmed about Red1 failure modes
- which recent experiments were diagnostically useful
- which recent code paths should not be treated as a keeper
- the recommended reset point and next work order

## Executive Summary

The best concise summary is:

- the original Red1 failure diagnosis was correct
- the rarity gate really was rejecting the reviewed red target for the wrong
  reason
- once rarity was partially improved, the next blocker moved downstream into
  support-map and blob behavior
- however, the experimental fixes attempted in this thread did not produce a
  trustworthy final detector improvement
- before more tuning, the next thread should restore to a known-good source
  baseline and re-apply only clearly valid fixes

At the end of this thread, the working tree should be treated as experimental,
not as a clean checkpoint.

## Current Color AD Architecture

Reference:

- `/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_Anomaly_Detector_Architecture.md`

The current visible-color path in `app/src/main/cpp/anomaly_analysis.c`
operates in this order:

1. sampled-grid prep and persistent color state
2. histogram-based rarity scoring on sampled UV bins
3. local support gating
4. support-map formation with patch aggregation
5. connected blob extraction and ranking
6. winner selection only from threshold-clearing color blobs

Important current implementation facts:

- the color path no longer behaves like the old "best odd pixel" design
- it depends heavily on sampled-grid state, selective refresh, and ROI carry
- the post-rarity stages matter a lot; rarity alone is not enough to predict
  success

## What This Thread Confirmed

### 1. The reviewed Red1 target was genuinely being suppressed by family rarity

The reviewed center target often died at `rarity_rejected` because the UV
family rarity score used an equal-weight `3x3` family sum. The target lived
inside a crowded red neighborhood, so neighboring saturated bins crushed the
family rarity even when the target itself had:

- strong local support
- visible patch coherence
- useful ring contrast

This root-cause diagnosis was real.

### 2. A rarity-side experiment could move the target past the gate

The most useful rarity-side experiment was:

- center-weighted family rarity instead of equal-weight `3x3`
- tightly gated contrast rescue using only rescue excess above a baseline

In the best run from that line of work, sampled target frames shifted from
mostly `rarity_rejected` to a mix that included `extracted` and
`no_candidate`.

That proved the diagnosis, but it still did not produce enough final relevant
detections to count as a usable fix.

### 3. Once rarity improves, the next blocker is blob topology

The most important downstream finding was:

- once the target survives rarity, the next failure is usually not rarity
- the next failure is support/blob topology

The richer JSON from the Red1 traces showed three recurring downstream
patterns:

- the target region can be over-merged into a too-large component
- it can fragment into nearby blobs that miss the reviewed center
- or a target-adjacent blob can be extracted but lose ranking / threshold

## Most Useful Diagnostics From This Thread

The following output directories were the most useful:

- `/tmp/red1_family_debug2`
- `/tmp/red1_fix_try4b`
- `/tmp/red1_fix_try5`
- `/tmp/red1_fix_try6`

Best blob-stage diagnostic trace:

- `/tmp/red1_fix_try4b/visible-color-baseline/red1-opening-target-track/color_debug.jsonl`

Most useful sampled target frames to compare:

- `37`
- `57`
- `129`

The best comparison pattern was:

- inspect a `no_candidate` target frame
- compare it with a nearby `extracted` target frame
- inspect support seeds, blob reject reasons, and candidate bbox/score drift

## Important Debugging Lesson: Inspect-Only Telemetry Was Not Fully Inspect-Only

This thread also uncovered a real instrumentation hazard.

The color-target debug path was supposed to be inspect-only, but one branch in
the current code made contrast rescue behave differently when the debug target
trace was enabled. In other words:

- enabling target tracing could change detector behavior
- so some experimental runs looked better than they really were

That means any future color experiment must verify:

- with target tracing enabled
- with target tracing disabled

and confirm both produce the same detector decisions.

## What Not To Keep From This Thread

Two categories of changes from this thread should not be treated as a final
solution.

### 1. Aggressive blob-growth / compact-boost tuning

A more aggressive support/blob experiment was able to create many more color
detections in harness runs, but after the instrumentation issue was removed it
did not hold up as a trustworthy improvement.

Symptoms:

- it produced many off-target detections
- reviewed Red1 recall collapsed back to `0.0` in the relevant reruns
- app-like color-only replay still produced no useful boxes

So that branch was diagnostically informative but is not a keeper.

### 2. Color-only selective-refresh overrides

This thread also encountered color-only logic that:

- downgraded `TARGET_ONLY` to `PARTIAL`
- and in some cases forced `PARTIAL` to `FULL`

Those overrides made the target-only unit tests fail and also distorted what
the live color path was really doing.

They should not be used as a substitute for fixing the actual color detector.

## Stable Facts To Carry Forward

The next thread should carry forward these conclusions, not the experimental
parameter values:

1. Red1 failure is real and reproducible.
2. The family-rarity gate was suppressing the correct target for the wrong
   reason.
3. After rarity partially improves, the larger unsolved problem becomes
   support/blob extraction.
4. Target-trace instrumentation must not perturb detector behavior.
5. Any proposed improvement has to be validated against:
   - unit harness health
   - reviewed Red1 replay
   - app-like color-only replay

## Recommended Reset Point

Do not continue from the current experimental working state as though it were
clean.

Recommended starting point for the next thread:

- restore to the last known-good source baseline in the project snapshot or
  another known-good commit/worktree state

Then re-apply only clearly valid changes one by one.

In particular, the next thread should separate:

- real correctness fixes
- diagnostic-only instrumentation
- speculative detector tuning

## Recommended Next Work Order

### Step 1: restore a known-good baseline

Before more tuning:

- restore a source state with a passing harness
- verify the harness first
- then rerun Red1 before making new detector edits

### Step 2: keep instrumentation non-perturbing

Before trusting any new result:

- verify target tracing does not change output
- verify JSONL / debug hooks do not affect scoring or selection

### Step 3: focus on `build_color_support_map()` and `extract_color_blob_candidates()`

Primary file:

- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c`

Primary downstream questions:

- Is the target region over-merged into a too-large component?
- Is it fragmenting into nearby blobs that do not contain the reviewed center?
- Is the correct fragment extracted but outranked or left below threshold?

### Step 4: compare target and near-target frames explicitly

Use Red1 target frames like:

- `37`
- `57`
- `129`

Compare them with nearby target-containing extracted frames and inspect:

- support seed count
- strongest reject reason
- candidate area/span/fill
- candidate bbox relative to reviewed center
- candidate final score vs threshold

### Step 5: do not spend the next thread mainly on more luma added to rarity

That direction is now secondary.

The bigger unresolved issue is support/blob topology, not just more rarity-side
boosting.

## Validation Targets For The Next Thread

Any future color-AD change should be evaluated in at least three modes:

1. Unit harness

- `tools/anomaly_test/build/anomaly_test`

2. Reviewed Red1 harness replay

- `tools/anomaly_test/run_regression_suite.py`
- manifest:
  `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/regression_suite_color_manifest.json`

3. App-like color-only replay

Use settings aligned with the live Red1 run observed in `log.txt`:

- color-only algorithm mask
- affine registration
- stride `1`
- scan zone `0.80`
- threshold about `2.95`

This matters because a harness profile that enables motion support and uses a
slightly different threshold can look better than the actual live color-only
path.

## Final Recommendation

The next thread should not begin by trying to salvage the current experimental
blob tuning.

It should begin by:

1. restoring a known-good baseline
2. ensuring debug instrumentation is behavior-neutral
3. attacking support-map and blob-topology behavior directly
4. validating against both reviewed Red1 and app-like color-only replay

That is the shortest path to a trustworthy visible-color detector fix.
