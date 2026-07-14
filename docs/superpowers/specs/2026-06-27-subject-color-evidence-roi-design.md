# Subject Color Evidence ROI Design

## Goal

When an operator enters subject color hints such as `black + skin tone + white`, the Color AD should treat those selected colors as positive subject evidence. A single selected color family in a locally distinct region should be noteworthy. Two selected color families in close proximity should be much more compelling. Three selected color families in close proximity should be strong enough to create a bounded ROI designator unless hard safety gates reject it.

This extends the current color-hint behavior. The existing implementation only nudges retained target-like candidates; the new behavior must also find compact clusters of selected color evidence even when normal Color AD candidate formation did not retain a candidate there.

## Product Behavior

- With no subject color hints selected, behavior must remain equivalent to current uniqueness-first Color AD.
- With one or more subject color hints selected, selected colors become positive subject evidence.
- A one-family match can produce a low-confidence hinted ROI only when it is compact, locally distinct, and above a high single-family threshold.
- A two-family adjacent cluster should score approximately twice as strongly as a single-family cluster.
- A three-family adjacent cluster should score approximately three times as strongly as a single-family cluster.
- Only selected families count. Unselected colors near the cluster are ignored for hint scoring.
- Same-family repeats do not multiply the score. `white + white` is one family, not two.
- The detector should publish at most one hinted color-evidence ROI per frame/cadence window.

## Native Design

Add a bounded `subject color evidence` path inside the existing color scan, active only when `subject_color_hint_mask != 0`.

The path works over the existing sampled grid/color state, not a new full-resolution frame pass:

1. Classify sampled points with the existing `anomaly_color_subject_hint_classify(...)`.
2. Keep only points whose matched family intersects the selected hint mask.
3. Require local contrast using the same luma/chroma contrast concept as candidate hint scoring.
4. Build compact clusters over nearby selected-family evidence.
5. Score each cluster by distinct selected family count, local contrast, compactness, and support density.
6. Reject broad/homogeneous/common clusters.
7. Convert the best accepted cluster into one color target observation/ROI.

The score should be intentionally stronger than the current proximity nudge:

- one selected family: notable but conservative
- two selected families: strong ROI evidence
- three or more selected families: very strong ROI evidence

The cluster score should have an explicit cap and should not create more than one app-visible hinted ROI per frame/cadence interval.

## Guardrails

- No-hint mode must stay behaviorally equivalent.
- Use existing sampled-grid data; do not add a full-frame dense pass.
- Require local contrast so glare, broad foam, flat rocks, and uniform water do not qualify solely by color label.
- Require compactness so large regions of water/foam/rocks cannot become a hinted subject.
- Reject clusters near reviewed false-positive zones using existing reviewed-FP gates where available.
- Keep app-visible output bounded: one hinted ROI max.
- Add telemetry before relying on the behavior: selected mask, matched family mask, distinct family count, cluster score, cluster span, local contrast, accept/reject reason.

## Floristan Acceptance Target

Floristan should be qualified with `black,white,skin` using:

```bash
tools/anomaly_test/build_timing/anomaly_video_test \
  app/src/test/resources/vidcap/floristan.mp4 \
  --no-video \
  --app-defaults \
  --app-appearance color \
  --app-display-output \
  --subject-color-hints black,white,skin \
  --summary-json /private/tmp/floristan_bws_cluster_summary.json \
  --color-debug-jsonl /private/tmp/floristan_bws_cluster_color_debug.jsonl \
  -c /private/tmp/floristan_bws_cluster_detections.csv
```

Expected result: at least a few true-positive annotations on the corrected positive-only Floristan sidecar, without a large off-target pressure increase. If the detector still misses, debug telemetry must explain whether the failure was classification, local contrast, cluster compactness, or publication gating.

## Regression Targets

Run Red1 and Red2 with no hints and with `--subject-color-hints red`.

Expected result:

- no-hint emitted CSV rows remain equivalent to baseline
- red-hint preserves Red1/Red2 reviewed recall and precision
- red-hint does not inflate box event count or off-target pressure materially
- realtime factor and max frame time stay close to the current values

## Deferred

- Body-shape detector.
- Arbitrary RGB/color picker.
- Saved session color presets.
- More than one hinted ROI per frame.
