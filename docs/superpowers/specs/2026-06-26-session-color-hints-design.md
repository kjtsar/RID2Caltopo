# Session Color Hints for Color AD

## Goal

Add session-scoped subject color hints to Color AD so an operator can enter simple witness-style descriptors such as "white swim trunks" or "red shirt and blue pants." Hints should improve the odds of detecting target-like visible-color regions in hard clips such as Floristan without weakening the current default behavior when no hint is present.

## Context

Current Color AD intentionally favors globally unique, target-plausible color blobs. That remains the default. Floristan showed a different field case: the subject's visible clothing/body colors were not globally rare in the frame, while the river created many moving and locally varied background regions. Movement evidence was not a reliable positive signal there; it was more useful as a warning that water/flow/parallax could create false positives.

Users usually receive color information second- or third-hand, so the UI should not present a precision color picker. The useful operator input is a small set of coarse color families.

## User Experience

In anomaly settings, add a `Subject colors` control visible for Color appearance mode. It should use compact multi-select chips with simple labels:

- `Dark/Black`
- `White/Light`
- `Red`
- `Orange/Yellow`
- `Green`
- `Blue`
- `Skin tone`

The control is session based. It should apply to the current stream/playback anomaly config and should reset with the same session/config lifecycle as the other transient anomaly settings. It should not create a permanent global preference unless a future design explicitly adds that.

Operators may select multiple colors. This supports descriptions like "red shirt and blue pants" or "white shorts and dark hair."

## Detector Behavior

With no subject colors selected, Color AD must keep the existing uniqueness-first behavior. Hints are inactive, and ranking/gating should remain equivalent aside from telemetry fields reporting that hints are disabled.

With one or more subject colors selected, matching hinted-color candidates may enter a narrow inclusive rescue path. A hint match is not enough by itself. The candidate must still look target-like:

- small enough for the target-size model
- compact enough to avoid broad water/foam/texture regions
- locally distinct enough to stand out from its immediate neighborhood
- not rejected by existing hard gates for obvious clutter
- compatible with existing cadence and app-visible publication rules

Global uniqueness still matters. A globally rare hinted-color candidate should rank highly, but a common hinted color such as white can still be considered when it is locally distinct and target-plausible.

## Multi-Color Proximity

When two or more selected color families appear in close proximity, the detector should score the group as more subject-like than isolated single-color hits. This is the first-class rule for "red shirt and blue pants" style reports.

The proximity bonus should:

- apply only among provisional target-like candidates that already pass basic size and compactness checks
- use normalized/frame-aware distance so the radius scales with expected subject size
- produce a bounded boost rather than bypassing all gates
- prefer compact clusters over scattered same-frame color matches
- avoid creating multiple published boxes for one subject-shaped color cluster

The combined region may be represented internally as a primary candidate plus nearby supporting color-family evidence. Publication can remain a single box around the primary/merged target region for the first implementation.

## Data Model

Represent selected subject colors as a bitmask in Kotlin and native config. Suggested flags:

- `DARK_BLACK = 1 << 0`
- `WHITE_LIGHT = 1 << 1`
- `RED = 1 << 2`
- `ORANGE_YELLOW = 1 << 3`
- `GREEN = 1 << 4`
- `BLUE = 1 << 5`
- `SKIN_TONE = 1 << 6`

Add the bitmask to:

- `AnomalyConfig`
- `NativeAnomalyConfig`
- `FfmpegBridge.updateAnomalyConfig(...)`
- `nativeUpdateAnomalyConfig(...)`
- `anomaly_config_t`
- `anomaly_video_test` app-default/config mapping

The default value is `0`, meaning no hints.

## Native Scoring Shape

Add a cheap color-family classifier at candidate level, not a full-frame pass. It should classify provisional color candidates using data already available in the fresh color path, such as candidate center U/V/luma, local ring contrast, and existing histogram/commonness values. `Skin tone` should be a broad semantic family rather than a precise color; it can cover common exposed-skin ranges across lighting variation, but it should still require local distinctness and target-plausibility gates.

For each provisional candidate, compute:

- matched subject-color-family mask
- hint match score
- local distinctness score
- optional nearby hinted-color support score

Then fold those scores into Color AD after hard candidate formation but before final ranking/publication. This keeps work bounded to existing candidate lists and avoids scanning arbitrary full-frame pixels for every color family.

## Budget Guardrails

The implementation must not add another dense full-frame pass. Budget rules:

- classify only provisional/dense-verified color candidates
- cap proximity checks to a small candidate set already retained by the color path
- keep pair/group scoring O(n^2) only over that capped set
- reuse existing local ring and histogram/commonness metrics
- add timing/telemetry fields for hint classification and proximity scoring if measurable as separate stage buckets
- keep no-hint mode on the current fast path

If hints are enabled and no candidate matches a selected family, the detector should pay only the bounded candidate-classification cost and publish nothing extra.

## Telemetry

Host harness and debug JSON should expose enough to qualify behavior:

- selected subject-color bitmask
- candidate matched color-family mask
- hint match score
- proximity support count
- proximity support score
- whether a candidate entered the inclusive hint-rescue path
- hint-rescue rejection reason
- hint/proximity timing if it becomes a distinct measurable stage

These fields should make Floristan-style failures explainable without requiring visual guessing.

## Tests And Qualification

Focused tests should cover:

- bitmask plumbing defaults to disabled
- semantic color-family classification for representative U/V/luma samples
- no-hint ranking remains equivalent
- single hinted-color rescue remains gated by compactness/local distinctness
- multi-color proximity boosts nearby selected colors above isolated selected colors
- proximity does not boost distant same-frame colors
- app-default harness metadata reports hint config

Behavior qualification should include:

- Floristan app-parity replay with `White/Light` and corrected review interpretation
- Red1/Red2 visible-color reviewed regressions to guard current strengths
- color regression suite
- visible-color performance benchmarks, with special attention to max frame time
- focused Gradle tests for config/Kotlin bridge plumbing
- broad gate such as `./gradlew :app:releaseCheck` before claiming app-bound readiness

## Non-Goals

This does not add a shape detector. It does not add an eyedropper, arbitrary RGB picker, or user-authored complex color rules. It does not make movement evidence mandatory for color-hint rescue, because river search clips can make movement ambiguous or misleading. It does not make selected colors sufficient for publication without target-plausibility gates.

## Open Implementation Notes

The first implementation should prefer a single native scoring seam near existing Color AD candidate ranking. If that area is too large to edit safely, split out helper functions for color-family classification and proximity scoring with focused native tests before integrating them into the main loop.
