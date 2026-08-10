# Visible Color AD Clean Summary (2026-05-11)

This note is the clean summary for the next thread. It is intended to replace
ad-hoc interpretation of the older, overloaded handoff documents.

## What We Are Actually Trying To Build

The detector should focus on **small visually unique stand-outs** that a pilot
could easily miss on a small controller screen in bright daylight.

We are **not** interested in large obvious blobs.

## Hard Requirements

These are the current requirements and should be treated as design constraints,
not soft preferences.

1. **Large blobs are out of scope**

- only blobs **smaller than the configured Small Target setting** should ever
  be considered detector candidates
- large visible blobs should not merely be ranked lower; they should be
  excluded from candidacy

2. **Blob uniqueness should come from the most unique pixel in the blob**

- when scoring color uniqueness for a blob, use the color of the **most
  uniquely colored pixel inside that blob**
- do not let a larger or more averaged blob color dilute that uniqueness
  measurement

3. **No more coarse sampling**

- do not go back to coarse sampled-grid blob formation
- for regions that are **new** or **not AR-locked**, use **every-pixel
  sampling**
- AR-locked / revisited regions can still use the cheaper revisit logic, but
  fresh blob formation should be dense

4. **Dense boundary growth should use 8-neighbor contrast logic**

- when applying the contrast threshold to decide blob boundaries in dense
  per-pixel sampling, consider **all eight bordering pixels**
- do not limit dense boundary growth to only four-neighbor logic if that would
  split or distort diagonal continuity

## Important Architecture Clarification

The current implementation does **not** satisfy the target design above.

What the code still is today:

- a **coarse-first** detector with **dense local verification**

What we actually want:

- a **dense pixel-first** detector for fresh / unlocked regions
- only after a compact dense blob is found should revisit / AR-lock logic take
  over as an optimization

Do not describe the current code as if it already implements the intended
dense fresh-region detector. It does not.

## What We Learned From Red1

The recent visible-color work focused on `Red1.mp4`, especially late duplicate
false positives on a large fairly homogeneous blob around:

- `x ≈ 51%`
- `y ≈ 58-59%`

Key finding:

- that nuisance blob is **not** the kind of thing we want to detect at all
- it should be excluded because it is too large, even before arguing about its
  exact uniqueness score

Another important finding:

- the true red target can be more color-unique than the nuisance blob
- so the core failure is not just a bad uniqueness threshold
- the larger issue is that the detector has still been willing to entertain
  blobs that are already outside the small-target mission

## What Recent Code Changes Did

Recent threads added:

- more fresh-mode winner telemetry
- a hard fresh winner gate
- a stronger "small must dominate" scoring adjustment

Those were useful for diagnosis, but they are still layered on top of the
older coarse-first architecture.

They are **not** the final design.

## What Still Needs To Happen

The next thread should move toward the intended architecture directly.

Priority order:

1. implement dense every-pixel blob formation for fresh / unlocked regions
2. reject any blob that exceeds the Small Target setting before treating it as
   a candidate
3. measure blob color uniqueness from the most unique pixel in the blob
4. use 8-neighbor dense boundary logic when applying the contrast threshold
5. keep AR-lock / revisit as the optimization path after a valid compact dense
   blob is found

## What Not To Do Next

- do not restart from old parent/child coarse-fanout planning
- do not propose another coarse sampled-grid refinement
- do not treat large blobs as valid candidates that merely need harsher
  ranking
- do not present a global uniqueness-threshold bump as the main solution
- do not describe the present code as already dense pixel-first

## Plain-English Next Thread Mission

Build the fresh-region visible-color detector we actually want:

- every-pixel, not coarse-sampled
- compact blobs only
- anything larger than Small Target is not a candidate
- uniqueness comes from the blob's most uniquely colored pixel
- dense blob growth uses 8-neighbor contrast continuity
- AR-lock is the optimization layer, not the primary blob-construction method
