# Visible Color AD Clean Handoff (2026-05-12)

This document is the clean handoff after the `2026-05-11` to `2026-05-12`
parent-thread investigation cycle. It is intended to replace ad-hoc retelling
of what was tried in the child-thread loop.

## Current Checkpoint Truth

The current tree has one accepted repair and no active experimental child
diffs from this cycle.

Accepted repair still active:

- scope the post-dense `color_small_target_priority_scale()` penalty to fresh
  frontend modes only, so legacy/native baseline fixtures are not crushed by
  fresh-era small-target scoring

Current native baseline after that repair:

- `./build/anomaly_test`: `83 passed, 0 failed`
- `ctest --output-on-failure`: pass

No later experiment child from this cycle remains active in the tree.

## Design Target Still Unchanged

The intended detector is still:

- dense pixel-first for fresh / unlocked regions
- compact blobs only
- anything larger than `Small Target` is not a candidate
- blob uniqueness comes from the most unique pixel inside the blob
- dense boundary growth uses 8-neighbor continuity
- AR-lock / revisit remains the optimization layer, not the primary fresh
  blob-construction method

The code still does **not** implement that design yet.

## Architecture Pivot Checkpoint

The next implementation lane should replace support-map micro-tuning with a
dense blob-first fresh path.

Required behavior for that lane:

- construct compact dense color blobs directly from fresh / unlocked pixels
- reject any same-colored blob larger than `Small Target` before candidacy
- rank each surviving blob from its most unique pixel, not from a broad
  support-map plateau
- preserve AR-lock / revisit as a later optimization layer after candidate
  blobs already exist

Worker B added public-API native tests in `tools/anomaly_test/test_anomaly.c`
to pin those expectations for the implementation worker.

Current implementation checkpoint from this thread:

- fresh mode now bypasses `build_color_support_map(...)` for candidate
  construction
- legacy mode still keeps the support-map path
- fresh candidate extraction now grows 8-neighbor components from direct color
  evidence and uses raw rarity to choose the component peak / uniqueness anchor
- fresh mode now uses exact-bin rarity for seeding rather than family rarity
- fresh raw-evidence seeding has a separate seed floor from the old aggregated
  support-map floor
- fresh compact candidates no longer die on the old support-map ring /
  support-mass hard rejects before dense verification; those legacy support
  rejects stay scoped to the legacy path
- the internal color blob candidate now carries the peak raw rarity so fresh
  candidate ranking/debug can follow the blob's most unique pixel

Validation status:

- `cmake --build build` passed
- `./build/anomaly_test` passed: `100 passed, 0 failed`
- Red1 fresh-rgba smoke still produced no output boxes; target debug still has
  exactly one reviewed frame at `winner`, so the canary repair did not yet solve
  Red1 continuity

Latest Red1 smoke artifacts:

- `/tmp/red1_fresh_blobfirst_after_canary_summary.json`
- `/tmp/red1_fresh_blobfirst_after_canary_color_debug.jsonl`
- `/tmp/red1_fresh_blobfirst_after_canary_detections.csv`

Latest Red1 target stage counts from the smoke:

- `winner=1`
- `no_candidate=12`
- `rarity_rejected=46`
- `local_support_rejected=2`
- `none=92`

Next bounded step:

- the native blob-first canaries are now green; debug Red1 continuity next
- first inspect why refresh-skipped reviewed target rows report `hist_key=-1`,
  rarity `0`, and local support `0`, because this accounts for `46 / 61`
  reviewed target failures
- then split the sampled `no_candidate` cases into target component `AREA`
  rejects versus no traced component despite seed eligibility

## What This Parent Cycle Proved

### 1. The baseline regression was real and was repaired

Before the repair child, the live checkpoint was already red at the native
test gate:

- `63 passed, 17 failed`

The accepted repair restored the native baseline to green without reopening the
broader visible-color architecture question.

### 2. Several narrow seams are now exhausted as first-next moves

These were tried and rejected:

- earlier fresh growth-guard activation
- fresh support-map ring-penalty relief
- fresh compact-prominence/core-share bonus
- bounded plateau seed handoff as tried here
- narrow late ring/support-mass reject relaxation for compact surfaced blobs

They either:

- produced no change in reviewed target extraction, or
- merely redistributed reject reasons, or
- increased off-target winners / seed pressure without improving the reviewed
  target

### 3. Plateau seeding exposed hidden compact target-local blobs

This was the most informative failed experiment.

What changed:

- hidden compact target-local components became visible often enough to be
  explicitly traced
- many former silent `no_candidate reason0` / `support_map_rejected` cases
  turned into explicit compact component rejects

What did **not** change:

- reviewed fresh target extraction stayed `0`
- reviewed fresh matched target frames stayed `0`
- target stage counts stayed:
  - `support_map_rejected=29`
  - `no_candidate=25`
  - `rarity_rejected=6`
  - `local_support_rejected=1`

What got worse:

- off-target winners increased
- total box events increased from `3` to `13`

Interpretation:

- the target-local compact structure is often present
- surfacing it alone is not enough
- the surfaced compact blobs still die before candidate extraction

### 4. The newly surfaced compact target-local blobs mostly die on late reject gates

Once plateau seeding surfaced them, the dominant late failures were:

- `ring`
- `support_mass`

with `area` as a smaller secondary failure class.

Later ranking is **not** the live blocker on that surfaced cohort, because no
surfaced target-local component survived into an extracted or matched
candidate.

## Best Current Interpretation

This parent cycle spent the obvious micro-seam experiments productively.

The strongest current read is:

- the detector still has a broader geometry/support/topology mismatch in the
  fresh path
- the next useful thread should **not** begin with another tiny threshold or
  weighting tweak
- the next thread should reassess the fresh-region geometry path more broadly,
  using the new evidence that:
  - hidden compact target-local blobs can be surfaced
  - but the present support / seed / handoff / compact reject flow still does
    not convert them into reviewed target extraction

## Recommended Next Thread Mission

Start from the clean design target and the accepted baseline repair, then do a
broader architecture reassessment of the fresh support/geometry path rather
than another threshold nudge.

That next thread should:

1. treat the green native baseline as fixed truth to preserve
2. treat the micro-seams above as already exhausted for now
3. use the plateau experiment as the key evidence:
   - compact target-local blobs exist
   - the current flow surfaces them but still rejects them before extraction
4. propose the next architecture move in terms of fresh geometry/support
   topology, not just different constants

## Useful Artifact Set

Accepted baseline-repair proof:

- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/build/anomaly_test`
- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/build/Testing/Temporary/LastTest.log`

Key replay / postmortem artifacts from this cycle:

- `/tmp/red1_app_fresh_summary_child_dense_growth_v2.json`
- `/tmp/red1_app_fresh_color_debug_child_dense_growth_v2.jsonl`
- `/private/tmp/visible_color_perf_child_dense_growth_v2/visible_color_perf_report.json`
- `/tmp/red1_app_fresh_summary_child_peak_plateau_seed_v1_before.json`
- `/tmp/red1_app_fresh_color_debug_child_peak_plateau_seed_v1_before.jsonl`
- `/tmp/red1_app_fresh_summary_child_peak_plateau_seed_v1.json`
- `/tmp/red1_app_fresh_color_debug_child_peak_plateau_seed_v1.jsonl`
- `/tmp/red1_app_fresh_summary_child_compact_reject_gate_v1_before.json`
- `/tmp/red1_app_fresh_color_debug_child_compact_reject_gate_v1_before.jsonl`
- `/tmp/red1_app_fresh_summary_child_compact_reject_gate_v1.json`
- `/tmp/red1_app_fresh_color_debug_child_compact_reject_gate_v1.jsonl`

## What Not To Do First Next Thread

- do not restart from generic coarse-fanout planning
- do not reopen the already-fixed legacy native-baseline regression first
- do not start with another tiny support-map weighting tweak
- do not start with another tiny growth-guard timing tweak
- do not start with another narrow late ring/support threshold relaxation
- do not treat increased off-target winners without target extraction as
  progress
