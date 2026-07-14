# Color Anomaly Detector

Last reviewed against the current implementation: 2026-07-12.

## Purpose and Scope

This document is the technical reference for visible-light anomaly detection
in RID2Caltopo. It describes current app behavior, the authoritative native
detection pipeline, the separate Target Colors capability, runtime scheduling,
ROI publication, diagnostic instrumentation, and release qualification.

For a non-technical introduction intended for SAR operators, see
[Anomaly Detector: An Introduction for SAR Users](Anomaly_Detector_Introduction_for_SAR.md).
Infrared-specific design is documented separately in
[IR Anomaly Detector](IR_Anomaly_Detector.md).

The primary implementation files are:

- [`AnomalyModels.kt`](../app/src/main/java/org/ncssar/rid2caltopo/video/anomaly/AnomalyModels.kt)
- [`AnomalyPrefs.kt`](../app/src/main/java/org/ncssar/rid2caltopo/video/anomaly/AnomalyPrefs.kt)
- [`StreamTile.kt`](../app/src/main/java/org/ncssar/rid2caltopo/video/StreamTile.kt)
- [`anomaly_analysis.c`](../app/src/main/cpp/anomaly_analysis.c)
- [`anomaly_color_detector.c`](../app/src/main/cpp/anomaly_color_detector.c)
- [`anomaly_target_color_detector.c`](../app/src/main/cpp/anomaly_target_color_detector.c)
- [`anomaly_appearance_candidates.c`](../app/src/main/cpp/anomaly_appearance_candidates.c)
- [`anomaly_scene_coverage_scheduler.c`](../app/src/main/cpp/anomaly_scene_coverage_scheduler.c)
- [`anomaly_video_test.c`](../tools/anomaly_test/anomaly_video_test.c)

## Current Behavioral Contract

Visible-light detection has two deliberately separate paths:

1. **Color Outlier** finds compact, target-sized blobs whose color is unusual
   relative to the current scene and recent color history. It does not require
   the operator to know the subject's color.
2. **Target Colors** searches directly for compact regions containing one or
   more operator-selected color families. It runs only when target colors are
   explicitly selected.

The separation is intentional. Selecting no Target Colors leaves the
uniqueness-based Color Outlier path unchanged. Target Colors does not replace
the authoritative uniqueness rank, and the normal Color Outlier detector does
not require a selected color.

Both paths produce target observations that enter the shared tracking and ROI
publication system. A published ROI is evidence for human review, not an
object classification or a confirmed person detection.

## App Configuration

`AnomalyConfig` is the app-side source of truth. Relevant current defaults are:

| Setting | Default |
| --- | --- |
| Detection | Off |
| Appearance | Color |
| Motion | On |
| Saliency | Off |
| Sensitivity | `0.59` |
| Motion evidence sensitivity | `0.60` |
| Scan zone | Center `50%` of the frame |
| Minimum hits | `2` |
| Registration | Affine |
| Movement estimator | Layered Active |
| Small-target screen fraction | `1/200` of the frame diagonal |
| Color target candidate limit | `1` |
| Target Colors | None |

When Color appearance is selected from an otherwise unmodified realtime
configuration, the app applies Color-specific realtime scheduling:

- Adaptive stride mode
- Minimum full-analysis interval of 30 source frames
- Hard cap of 60 source frames
- Maximum full-analysis interval of 2 seconds
- Dense color sampling (`pixel_step = 1`)

The adaptive controller may analyze sooner when target, registration, or scene
conditions require it. These values describe the app's current realtime
posture, not a promise that every video source will have identical timing.

Although the stored `ColorFrontendMode` default is `Legacy`,
`AnomalyConfig.toNativeConfig()` maps Color appearance with that untouched
setting to the native **Fresh RGBA** frontend. This mapping is important when
comparing app behavior with host-harness experiments.

Target Colors are session-scoped. `AnomalyPrefs` clears the selection rather
than restoring it as a long-lived global preference, preventing a prior
mission's subject description from silently affecting a later search.

## App-to-Native Flow

The visible-light runtime follows this path:

1. `StreamTile` presents the Anomaly Detector controls.
2. `StreamsViewModel` owns the active per-stream configuration.
3. `AnomalyConfig.toNativeConfig()` resolves appearance, algorithms,
   sensitivity, stride, detail, registration, candidate limits, and target
   color mask.
4. `FfmpegBridge` transfers the native configuration to the active video
   session.
5. The AD worker converts decoded frames to RGBA and calls the native analyzer.
6. The analyzer returns raw evidence, tracked target observations, and zero or
   more publishable boxes.
7. The annotation layer smooths and cadence-limits visible ROI transitions
   before the rendered frame is displayed.

The app and host replay harness share the same native detector. Harness claims
about app behavior should use both `--app-defaults` and
`--app-display-output`; raw detector boxes alone do not reproduce the app's
visible annotation policy.

## Authoritative Color Outlier Pipeline

The Color Outlier detector is blob-based. It no longer publishes the single
strangest sampled pixel. The current pipeline is:

1. Resolve the centered scan zone and current rescan plan.
2. Prepare current-frame color samples and chroma histograms.
3. Score color rarity, local support, contrast, and current/recent scene
   commonness.
4. Identify candidate seed regions.
5. Form connected color blobs and perform dense local verification.
6. Reject oversized, diffuse, ring-like, weak, or broadly supported blobs.
7. Rank the surviving target-sized candidates.
8. Apply winner, persistence, and publication gates.
9. Convert accepted candidates into target observations for shared tracking.

### Candidate Formation

Fresh RGBA mode uses current-frame color evidence for candidate formation.
Color is represented in chroma bins so brightness changes do not wholly define
uniqueness. The detector also keeps current and recent scene histograms to
distinguish a genuinely unusual blob from a color that is common throughout
the view.

Coarse evidence locates plausible regions, while dense verification checks
the local source pixels before a candidate is accepted. Candidate measurements
include:

- Area and span relative to the configured small-target envelope
- Fill and compactness
- Peak and mean support
- Center concentration
- Isolation from surrounding support
- Ring fraction and nearby support mass
- Histogram rarity and scene commonness
- Local chroma and luma contrast

Large or diffuse colored regions are intentionally poor candidates even when
their color is unusual. The detector is optimized for small objects that are
easy to miss on a controller display.

### Authoritative Uniqueness Ranking

`color_uniqueness_rank` is the leading authoritative ordering signal when it
is available. It combines candidate color rarity with suppression for colors
that are common in the scene. Candidate ordering then considers, in order:

1. Color uniqueness rank
2. Histogram rarity
3. Valid retention rank
4. Final color score
5. Candidate quality
6. Compact target-size preference
7. Center share and peak support

This ordering preserves the accepted uniqueness-first behavior: among
plausible small blobs, the more uniquely colored candidate should win before a
merely stronger but common-colored candidate.

Candidate formation and publication remain separate. A blob may appear in
debug telemetry yet fail the final score, shape, persistence, or minimum-hit
gate and therefore produce no visible ROI.

### Persistence and Provisional Candidates

Color candidates can contribute to shared target tracks. On a full scan, the
detector may retain a bounded number of strong sub-threshold Color candidates
as non-publishing provisional observations. The app default retains one; the
supported range is one through four.

Provisional observations preserve reacquisition opportunities without drawing
a box immediately. They must later receive confirming evidence before they can
contribute to a published ROI. This distinction prevents the candidate limit
from becoming a direct "number of boxes" setting.

## Target Colors Pipeline

Target Colors is an additive, opt-in detector implemented separately from
Color Outlier scoring. It is active only when:

- The resolved algorithm set includes Color, and
- The selected target-color mask is nonzero, and
- The current scan plan is eligible for a full or target-only evaluation.

The app supports these color families:

- Red, orange, yellow, green, blue, purple, and pink
- Brown
- Black, grey, and white

The classifier uses value and chroma for neutral colors and hue-family mapping
for clear chromatic colors. Low-clarity colors can remain unclassified rather
than becoming weak target evidence.

The detector performs a lightweight sampled full-frame pass, suppresses
selected colors that are pervasive and visually consistent with the
background, groups adjacent selected-color samples into components, and scores
each component for:

- Number of distinct selected families
- Support count and density
- Compactness
- Contrast with the frame
- Size plausibility

Multi-color proximity is naturally rewarded because a compact connected
component containing multiple selected families receives stronger evidence
than repeated samples from only one family. The native path can retain up to
two Target Color ROIs per evaluation.

Accepted Target Color components become confirming Color observations in the
shared target tracker. Observations that duplicate an already accepted nearby
target are suppressed.

The direct pass intentionally does not use the Color Outlier histogram-rarity
and candidate-ranking machinery. This keeps explicit subject-color search
independent from the normal uniqueness detector and avoids changing no-target
behavior.

## Motion, Registration, and Shared Tracking

Color appearance can run with Motion enabled. Affine registration estimates
global camera movement so local residual motion and target positions can be
interpreted in a stabilized scene. The Layered Active movement estimator adds
parallax-aware support and suppression for regions whose apparent movement is
likely caused by the moving drone rather than an independently moving target.

Registration quality affects:

- Whether prior color state can be trusted
- Prediction and matching of target observations
- Selective revisit safety
- Persistence and minimum-hit accumulation
- Whether temporal diagnostic evidence is valid

A scene discontinuity, registration failure, or incompatible geometry resets
or weakens state rather than carrying stale evidence into a new view.

Color, Target Colors, Motion, Thermal, and Saliency observations share the
target tracking and result-building path. Nearby observations may reinforce
one tracked region instead of producing separate boxes.

## Scan Planning and Realtime Behavior

Color analysis is substantially more expensive than basic frame forwarding.
The detector therefore separates full discovery work from cheaper tracking and
revisit work.

Current runtime controls include:

- Fixed or adaptive stride
- Full, partial, target-only, or skip rescan decisions
- Registration-aware state reuse
- Bounded provisional candidate retention
- AD worker queue-pressure degradation
- App-visible annotation cadence and smoothing

The app's Color realtime defaults favor dense candidate quality while reducing
how often the expensive full discovery pass runs. Target tracking and motion
estimation allow useful work between those scans, but a rapidly appearing and
disappearing object can still be missed between discovery opportunities.

### Scene-Coverage Scheduler Status

`anomaly_scene_coverage_scheduler` is currently **shadow-only**. It divides the
sampled scene into an 8 by 6 block grid and records which blocks an
incremental policy would select based on camera movement, newly exposed scene,
registration confidence, target revisits, age, and accumulated coverage debt.

Its output is diagnostic telemetry. It does not currently replace the
authoritative rescan planner or change published detections. Promotion requires
reviewed evidence that incremental scene coverage improves discovery latency
without starving newly visible terrain in moving-drone footage.

## Composite Uniqueness Status

The detector computes a newer composite uniqueness score for existing Color
candidates. Its components include:

- Candidate-excluded background rarity
- Predominant-color share, entropy, and purity
- Divergence from the local background ring
- Chroma reliability
- Motion-aligned temporal consistency when registration is healthy

This score is currently **shadow-only instrumentation**. It is exported for
analysis but does not reorder candidates, alter winner gates, create tracks, or
change published ROIs. The authoritative `color_uniqueness_rank` remains in
control.

The shadow path was deliberately left unpromoted because current reviewed
evidence showed one useful ranking opportunity but no broad, repeatable lift
and insufficient reviewed negative examples. This instrumentation should not
be described as a shipping detection improvement until a future promotion gate
is satisfied.

## ROI Publication and Display

Native candidate acceptance does not directly imply a visible rectangle. A
candidate must pass the relevant score and shape gates, accumulate required
hits, and survive target matching and result publication.

The app-visible annotation layer then:

- Smooths box geometry toward nearby same-algorithm observations
- Holds short evidence gaps to reduce flicker
- Limits visibility transitions to the annotation cadence
- Clears stale state on stream resets and playback discontinuities

As a result, app-visible boxes may appear later than the first raw candidate,
remain briefly after evidence weakens, or be wider than the underlying blob.
Harness validation must distinguish raw candidate evidence, raw native boxes,
and final app-visible annotations.

## Operator Controls

The main controls that affect Color behavior are:

- **Appearance:** Infrared or Color
- **Target Colors:** Multi-select color-family picker, available in Color and
  disabled for Infrared
- **Sensitivity:** Maps logarithmically to the native score threshold
- **Motion Evidence:** Controls motion contribution and support strength
- **Scan Zone:** Centered fraction of the frame eligible for normal anomaly
  scanning
- **Min Hits:** Required repeated support before normal publication
- **Small:** Maximum target-size envelope used in Color candidate rejection and
  ranking
- **Color Candidates:** Number of provisional Color targets retained for
  revisit, default one
- **Stride, Adaptive Min, and Adaptive Max:** Discovery cadence controls
- **Detail:** Pixel sampling step; the automatic detail value resolves to dense
  sampling for Color
- **Registration and Movement Estimator:** Camera-motion compensation controls

`Reset to Realtime Defaults` preserves the selected appearance and thermal
polarity while restoring tested general settings. When the resolved appearance
is Color, it also applies the Color-specific adaptive scheduling posture.

Debug controls such as Show Candidate Blobs and Troubleshooting Debug are for
diagnosis. Their output should not be interpreted as confirmed detections.

## Known Limitations

- Very small subjects can occupy too few source pixels to form a coherent
  candidate.
- Compression, haze, shadow, exposure, and white balance can change apparent
  color families.
- Common terrain colors can be correctly classified yet remain poor target
  evidence.
- Fast camera motion or weak registration reduces temporal and motion support.
- Full discovery work is cadence-limited for realtime performance, so brief
  targets can appear between scans.
- Occlusion and large scene changes can break target continuity.
- Target Colors uses broad semantic families rather than calibrated camera
  color profiles or exact RGB matching.
- Composite uniqueness and scene-coverage scheduling remain diagnostic and do
  not improve authoritative detections yet.
- Current reviewed Color qualification is centered on Red1 and Red2; broader
  reviewed clips and negative examples remain valuable.

## Diagnostics and Qualification

### Native and Harness Coverage

The native suite covers color classification, blob scoring, candidate ranking,
target observations, tracking, shadow evidence, scene coverage, and result
contracts. CTest covers the portable harness targets.

`anomaly_video_test` supports:

- App-derived configuration with `--app-defaults`
- App-visible annotation behavior with `--app-display-output`
- Explicit appearance, Motion, Saliency, frontend, detail, and stride controls
- Target-color family selection
- Candidate and timing JSON/CSV output
- Composite uniqueness and scene-coverage shadow telemetry

See [`tools/anomaly_test/README.md`](../tools/anomaly_test/README.md) for current
commands and artifact formats.

### Color Realtime Qualification

The Gradle task

```sh
./gradlew :app:colorRealtimeQualification
```

builds the optimized host detector and Target Color performance probe, replays
the reviewed Red1 and Red2 fixtures through the app-parity and app-display
paths, and evaluates correctness, determinism, geometry, and realtime behavior.

The Target Color probe covers no-selection, common-color backgrounds, compact
single- and multi-family subjects, and broad multi-family backgrounds. Its gate
checks sustained average and p95 performance relative to a same-run baseline,
ROI correctness, and an absolute catastrophic-latency ceiling.

The repository release gate includes this qualification:

```sh
./gradlew :app:releaseCheck
```

Behavioral changes to Color Outlier ranking, Target Colors, app-visible ROI
handling, scheduling, or native configuration should not be accepted from
helper tests alone. They require the focused Color qualification and broader
release checks appropriate to their scope.

## Maintenance Rules

Update this document whenever a change alters:

- App defaults or app-to-native Color mapping
- Authoritative candidate formation or ordering
- Target Color families, activation, scoring, or ROI count
- Tracking, minimum-hit, or app-visible annotation behavior
- Realtime scheduling or scene-coverage promotion status
- Composite uniqueness promotion status
- Required qualification gates

Dated plans and handoff notes may explain how a decision was reached, but this
document should describe only the behavior present in the current repository.
