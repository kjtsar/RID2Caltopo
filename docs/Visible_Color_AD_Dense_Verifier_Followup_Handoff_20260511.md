# Visible Color AD Dense Verifier Follow-Up Handoff (2026-05-11)

This note follows up on
[Visible_Color_AD_Rescan_Policy_Handoff_20260511.md](/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Rescan_Policy_Handoff_20260511.md).

It captures the state after adding dense local verification for visible-light
color candidates.

## Executive Summary

The visible-color path is now past the scan-policy-only checkpoint.

What is now true in code:

- the rescan-policy checkpoint remains intact:
  - small-target-aware `sample_step` cap
  - no color phase-hopping
  - periodic full refresh at about `333 ms`
- coarse rarity/support seeds now go through a bounded dense pixel-space
  verifier before becoming retained color candidates
- sparse sampled-grid impostors can now be rejected even if they looked
  compact at coarse resolution
- internal retained color candidates are capped at `4`

What is still not finished:

- replay/performance validation in app-like visible-color footage
- explicit dense-candidate payload threading through persistence/revisit logic
- deliberate validation that the new `4`-candidate internal cap is the right
  tradeoff versus keeping more internal candidates
- a cleaner first-class published/debug-facing dense candidate record if that
  becomes important outside the current internal scoring path

## Files Changed In This Follow-Up

- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c)
- [tools/anomaly_test/test_anomaly.c](/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/test_anomaly.c)

## What Changed

### 1. Dense local verification around rare-color seeds

The color path already had:

- rarity seeding
- support-map construction
- sampled-grid blob extraction

This follow-up adds a dense verifier after the coarse blob stage and before
candidate retention:

- build a bounded pixel-space window around the coarse seed/component
- flood-fill only locally similar pixels in that window
- reject immediately if the dense region is:
  - too small to be a real blob
  - too spread out for the configured target size
  - too sparse inside its bounding box
  - too non-unique relative to the surrounding ring

Intent:

- stop a few isolated colored pixels from masquerading as a compact target just
  because they align on the sampled grid
- make centroid/extents come from dense pixels rather than only coarse support

### 2. Candidate geometry is now informed by dense pixels

For accepted color candidates, the dense verifier now contributes:

- pixel-space centroid
- tighter extents
- dense fill estimate
- dense ring uniqueness signal

This is currently used internally to refine retained candidate geometry and
scoring rather than being exposed as a brand-new public candidate type.

### 3. Internal color candidate retention reduced to four

The internal visible-color candidate cap now matches the user-requested “top
few candidates” direction more closely:

- previous internal cap: `8`
- current internal cap: `4`

This should still be treated as provisional until replay validates that
motion/persist interactions do not suffer from the reduced internal pool.

### 4. Regression coverage added for coarse-grid impostors

A new native test constructs a false-positive pattern where isolated red pixels
can line up with adjacent sampled cells and appear blob-like at coarse
resolution.

Expected result now:

- no published detection
- no retained color candidate

That test passes with the current implementation.

## What Was Verified

Native unit tests were rebuilt and run after the follow-up.

Commands used:

```sh
cmake -S tools/anomaly_test -B tools/anomaly_test/build
cmake --build tools/anomaly_test/build
./tools/anomaly_test/build/anomaly_test
```

Final result:

- `74 passed, 0 failed`

## Current Intended Behavior

The visible-color detector should now be read like this:

1. Use affine registration and selective reuse to avoid rescanning already
   covered regions unnecessarily.
2. Force a full appearance refresh periodically at about `333 ms`.
3. Use coarse rarity/support logic to find plausible color seeds.
4. Before retaining a color candidate, run dense local pixel verification in a
   bounded window.
5. Reject non-unique, oversized, sparse, or incoherent dense regions
   immediately.
6. Retain only the top `4` internal color candidates.

This means the detector is no longer relying purely on sampled-grid blob
structure for visible-color admission.

## Remaining Work For The Next Thread

### Step 1: validate on app-like visible-color replay

Run at least one replay or harness sequence that exercises:

- healthy affine lock with stable target-only reuse
- periodic full refresh after about `333 ms`
- a small real colored target in a large frame
- a textured/colored false-positive region that should fail dense verification

The unit suite is necessary but not sufficient here.

### Step 2: connect dense candidate state more directly to persistence

The next step should thread dense-verified state through target persistence and
revisit behavior more explicitly:

- predicted location from affine registration
- prior dense centroid and extents
- score boost for persistence under good lock
- score boost when apparent motion disagrees with pure camera motion

The scaffolding exists, but the dense candidate is not yet treated as a
first-class tracked object end-to-end.

### Step 3: validate the internal `4`-candidate cap deliberately

Current code now keeps only `4` internal color candidates.

That may be right, but it should be validated rather than assumed,
especially when:

- color competes with motion/persist hypotheses
- multiple colored distractors are present
- a weaker true target coexists with stronger but rejectable clutter

### Step 4: decide whether to expose a richer dense candidate record

Internally the retained color candidate now benefits from dense verification,
but the code still does not expose a dedicated published/debug payload with all
of the following as a first-class concept:

- representative color
- explicit uniqueness score
- dense centroid
- dense extents
- dense acceptance/rejection rationale

That may not be necessary immediately, but it is the next obvious cleanup if
future tuning becomes difficult.

## Suggested Verification For The Next Thread

At minimum:

```sh
cmake -S tools/anomaly_test -B tools/anomaly_test/build
cmake --build tools/anomaly_test/build
./tools/anomaly_test/build/anomaly_test
```

Then run visible-color replay/perf checks focused on:

- runtime impact of the dense verifier
- stability under healthy registration reuse
- resistance to textured color clutter
- persistence quality across partial rescans and periodic full rescans

## Risks / Watch Items

- The dense verifier is bounded, but replay should confirm it does not create a
  noticeable runtime spike in cluttered scenes.
- The new internal `4`-candidate cap may be too aggressive in edge cases.
- Dense verification currently improves retained candidate geometry and scoring,
  but persistence/revisit still deserves a more explicit dense-state handoff.
- Some debug/output structures still reflect the older coarse-grid worldview
  more than a full dense-candidate model.

## Suggested New-Thread Prompt

Use something close to this:

> Continue the visible-color AD follow-up from
> [Visible_Color_AD_Dense_Verifier_Followup_Handoff_20260511.md](/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Dense_Verifier_Followup_Handoff_20260511.md).
> Keep the current scan-policy behavior intact and preserve the dense local
> verifier around rare-color seeds. Focus next on replay/perf validation and on
> threading dense candidate centroid/extents more explicitly into persistence
> and revisit logic under affine registration.
