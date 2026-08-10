# Visible Color AD Rescan Policy Handoff (2026-05-11)

This note hands off the current scan-policy work for visible-light Color AD.

It is specifically about:

- replacing overly coarse per-scan skipping with a small-target-aware sample cap
- relying more on affine-registration-driven region reuse
- forcing periodic full rescans instead of indefinitely reusing old coverage
- preparing the codebase for the next step: dense local verification around
  uniquely colored seeds

## Executive Summary

This thread completed the scan-scheduling half of the proposed redesign, but
not the dense local verification half.

What is now true in code:

- auto `sample_step` is capped so it cannot exceed half of the configured
  small-target span
- the visible-color fresh path no longer phase-hops within sampled cells
- appearance-only partial scans no longer use the old checkerboard fallback
  when nothing needs refresh
- full rescans are forced periodically at about `333 ms` using `source_ts_us`
- motion/persist scans still keep the sparse fallback so motion discovery is
  not starved between full rescans

What is not done yet:

- no dense all-pixels local verification stage around rare-color seeds
- no new candidate record containing centroid/extents/color uniqueness beyond
  the existing candidate fields
- no explicit “reject oversized or non-unique seed immediately, then stop”
  dense verifier

So the current checkpoint should be treated as:

- a completed scan-policy checkpoint
- not yet the final color-target detector design you described

## Files Changed In This Thread

- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c)
- [app/src/main/cpp/anomaly_analysis.h](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h)
- [tools/anomaly_test/test_anomaly.c](/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/test_anomaly.c)
- [tools/anomaly_test/anomaly_video_test.c](/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/anomaly_video_test.c)

## What Changed

### 1. Small-target-aware sample-step cap

`effective_sample_step(...)` now clamps auto sampling so the grid spacing does
not exceed half of the configured small-target span, with the current floor
still held at `2` for the existing reviewed detector regime.

Intent:

- stop the auto path from becoming coarser than the target size assumptions
- avoid missing small targets purely because the sampled grid is too sparse

Important nuance:

- this is still a sampled-grid detector
- it is less coarse than before for larger live frames
- it is not yet a dense per-pixel detector

### 2. No more color phase-hopping in fresh sampling

The fresh color path previously rotated the sampled location inside each
sampled cell across frames using `color_phase_counter`.

That behavior has been disabled for now:

- the sample point is now the cell center
- `advance_color_sampling_phase(...)` is effectively a no-op

Intent:

- remove the “eventually we’ll hit that pixel” assumption
- make each sampled cell spatially stable across frames
- keep selective reuse and debugging easier to reason about

### 3. Periodic full refresh using real frame time

The analyzer entrypoint already receives `source_ts_us`, but the old code
discarded it.

This thread now uses it to force periodic full rescans:

- default interval: `ANOMALY_FULL_RESCAN_INTERVAL_US = 333000`
- fallback frame-count interval when timestamps are unavailable:
  `ANOMALY_FULL_RESCAN_INTERVAL_FRAMES = 20`

New scan reason flag:

- `ANOMALY_SCAN_REASON_PERIODIC_FULL_REFRESH`

Intent:

- avoid indefinite target-only / partial reuse under healthy affine lock
- satisfy the “every Nth new frame, default about 333 ms” requirement

### 4. Appearance-only partial scans no longer checkerboard-refresh by default

The old selective refresh builder had a checkerboard fallback that could force
partial refreshes even when the mask logic found nothing meaningful to scan.

That fallback is now restricted:

- appearance-only scans: no checkerboard fallback
- motion / motion-tolerance / persist scans: sparse fallback preserved

Intent:

- align appearance scanning with the new “rescan regions when needed, not
  arbitrary pixels every time” policy
- preserve motion discovery behavior that still depends on sparse refresh

## What Was Verified

Native unit tests were rebuilt and run after the changes.

Commands used:

```sh
cmake -S tools/anomaly_test -B tools/anomaly_test/build
cmake --build tools/anomaly_test/build
./tools/anomaly_test/build/anomaly_test
```

Final result:

- `72 passed, 0 failed`

New test coverage added:

- small-target sample-step cap
- periodic full refresh after stable target-only reuse
- updated partial-mode expectations so “no arbitrary appearance refresh” is
  treated as valid behavior

## Current Intended Behavior

The current policy should be read like this:

1. Use affine registration to reuse previously scanned regions when lock is
   healthy.
2. Revisit active candidate regions every frame they remain relevant.
3. Rescan new exposure and stale regions when partial refresh selects them.
4. Force a full rescan when:
   - registration is invalid or hard degraded
   - warp coverage is too low
   - newly exposed coverage is too high
   - stale coverage is too high
   - sample-step compatibility breaks
   - the periodic full-refresh timer reaches about `333 ms`

This is closer to the requested model:

- do not skip random pixels every scan
- do skip rescanning regions that were already scanned recently

But again, this does not yet solve the seed-verification problem by itself.

## Remaining Work For The Next Thread

The next thread should focus on the dense local verifier around rare-color
seeds.

Recommended order:

### Step 1: keep this scan-policy checkpoint stable

Do not immediately re-open the sample-step or periodic-refresh policy unless:

- a concrete regression appears in reviewed replay, or
- runtime blows up in app-like visible-color playback

If a future thread changes this area, it should preserve:

- the small-target sample cap
- periodic full refresh via `source_ts_us`
- no color phase-hopping

unless there is a measured reason not to.

### Step 2: add dense local verification around rare-color seeds

The next implementation target should be:

- after coarse rarity identifies a plausible seed
- run a dense pixel-level scan in a bounded window around that seed
- decide whether it forms a self-consistent compact blob
- reject it immediately if:
  - it is not locally unique enough
  - it does not form a coherent blob
  - it exceeds target-size bounds

That dense stage should produce or directly populate:

- centroid
- extents
- representative color
- uniqueness / rarity score
- final candidate score

### Step 3: keep only the top few candidates

The user-requested direction was:

- record only the top four or so candidates per frame

Current code still allows:

- up to `8` color candidates internally
- `4` published boxes total

Next thread should decide whether to:

- lower internal color candidate cap to `4`, or
- keep more internal candidates but publish only the top `4`

That decision should be made deliberately and validated against motion/persist
interactions.

### Step 4: connect dense verification to target persistence

Once a dense-verified candidate exists, the next thread should ensure the
persist / revisit logic uses:

- predicted location from affine registration
- prior centroid and extents
- score boost for persistence under good lock
- score boost for motion inconsistent with pure camera motion

That is conceptually aligned with the existing track/revisit scaffolding, but
the dense candidate payload will need to be threaded through more explicitly.

## Suggested Verification For The Next Thread

At minimum, after any next-step code change:

```sh
cmake -S tools/anomaly_test -B tools/anomaly_test/build
cmake --build tools/anomaly_test/build
./tools/anomaly_test/build/anomaly_test
```

Then run at least one app-like visible-color replay or harness replay that
exercises:

- healthy affine lock with stable target-only reuse
- periodic full refresh after about `333 ms`
- small visible target in a large frame
- a false-positive textured region that should fail dense verification

Recommended code areas to inspect while doing that work:

- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:1243)
- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:2230)
- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:8753)
- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:9040)
- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:9791)
- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:10013)
- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:10313)
- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:10530)

## Risks / Watch Items

- The current `sample_step` floor is still `2`, so the detector is not yet
  allowed to go fully dense under the auto policy.
- Motion/persist still preserve sparse fallback behavior; if a future thread
  removes that too early, motion discovery may regress.
- The periodic full-refresh timer currently marks any full refresh as the last
  reset point; if a future thread adds more full-refresh reasons, verify the
  cadence still behaves as intended.
- The dense verifier could increase runtime sharply if it is not tightly gated
  to a small set of rare-color seeds.

## Suggested New-Thread Prompt

Use something close to this:

> Continue the visible-color AD rescan-policy follow-up from
> [Visible_Color_AD_Rescan_Policy_Handoff_20260511.md](/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Rescan_Policy_Handoff_20260511.md).
> Keep the new small-target sample-step cap, no phase-hopping, and periodic
> full-refresh behavior intact. Implement the next stage: dense local
> verification around rare-color seeds so uniquely colored compact targets can
> be admitted even when the coarse sampled grid alone is insufficient. Reject
> non-unique or oversized regions immediately, preserve only the top few
> candidates, and rerun the native anomaly test suite before reporting.
