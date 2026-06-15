# R2CAD Stable ROI Publication Design

## Problem

R2CAD currently exposes ROI annotations after detector publication gates and an
annotation cadence layer. The cadence layer reduces some frame-to-frame churn,
but it can still show distracting ROIs that only survive for a few frames. The
operator-visible overlay should use the available render backlog as a stability
window: show ROIs only when a target has remained strong for most of that
window, and avoid lighting up noisy transient candidates.

## Design

Add a native annotation-stability layer at the existing annotation publication
seam in `anomaly_detector_annotation.*`. The detector may continue producing
raw boxes every analyzed frame, but the overlay path will publish only stable
boxes selected from recent raw observations.

The stability window should be derived from the existing realtime cadence
frames, which is currently the default 0.5 second render window at 30 FPS. A
target becomes visible when it has matched a high-scoring ROI in at least 60%
of that window, with a floor of three analyzed observations so very short
windows do not publish one-frame hits. This keeps the first implementation
aligned with the render backlog contract without requiring a new Android or
FFmpeg telemetry pipe.

Published ROIs are selected by target continuity, not by raw frame order alone.
Each raw box is associated with an existing stability slot when its algorithm
and normalized center are close enough to the slot's last center. Otherwise it
may claim a free slot. Slots accumulate hit counts, score, and last geometry
inside the rolling window; stale slots age out when they are absent for the
window. The publication layer returns the strongest stable slots, capped at
four visible ROIs.

When no stable slot qualifies, the overlay returns no boxes. Previously
published slots remain visible only while they continue to satisfy the rolling
stability rule; the layer should not hold a stale box just to avoid flicker.
The result should feel calmer because true stable targets stay lit through the
render backlog, while momentary detections never become operator-visible.

## Boundaries

This change stays in native C and does not change detector scoring, candidate
extraction, target-track update logic, render queue ownership, FFmpeg frame
ownership, or Kotlin configuration. Debug overlays such as hot-region and
candidate-blob modes continue to bypass the stable overlay path and show raw
diagnostic output.

The first implementation uses frame-count windows. A later runtime-contract
packet can replace the window input with explicit adapter backlog telemetry if
the render path starts publishing precise backlog seconds to R2CAD.

## Testing

Add focused native tests for the publication helper:

- transient boxes below the three-hit floor remain hidden
- boxes present for less than 60% of the window remain hidden
- boxes present for at least 60% of the window are published
- no more than four ROIs are returned
- the strongest stable ROIs win when more than four are stable
- slots age out after the stability window when observations disappear

Then run the usual focused anomaly gate:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test`
- `./gradlew :app:compileDebugKotlin`

Because this changes operator-visible ROI publication, a follow-up replay or
tablet smoke check should be treated as useful before calling the behavior fully
qualified, even if the focused native gate passes.
