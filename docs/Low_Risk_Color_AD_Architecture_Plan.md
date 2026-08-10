# Low-Risk Color AD Architecture Plan

## Summary
Build this in two phases so we can improve visible-color AD without risking IR detection or playback.

Phase 1 is the default path and does not change the FFmpeg decode pipeline, playback pipeline, render pipeline, or IR detector inputs. It keeps the existing RGBA analyzer boundary intact, removes color-state carry from the front end, and evaluates a fresh-per-frame color path in the AD thread.

Phase 2 is optional and only starts if Phase 1 shows that the current RGBA-derived color front end is still the limiting factor. Phase 2 adds a color-only YUV sidecar path at the AD queue boundary, behind a disabled-by-default experiment flag. It does not replace the existing RGBA path.

## Implementation Changes
### 1. Stabilize the current architecture before changing the data source
- Update the color architecture docs to match the code that actually exists now.
- Add a short “production vs experimental” note that explicitly says:
  - IR remains RGBA-fed as today.
  - playback/overlay remains unchanged.
  - all color experiments must be validated with debug target tracing both on and off.
- Treat the current working tree as non-authoritative and rebase the next implementation thread onto a known-good source baseline before any new tuning.

### 2. Phase 1: fresh-per-frame color front end on the existing RGBA boundary
- Keep `anomaly_process_frame()` as the production analyzer entrypoint and keep its current RGBA signature unchanged.
- Keep all FFmpeg conversion behavior unchanged, including the anomaly-only RGBA conversion path in the decode/AD handoff.
- Add a color-path mode switch inside the analyzer with two implementations:
  - `legacy_carried_color_state`
  - `fresh_per_frame_color_state`
- Default the new mode to `fresh_per_frame_color_state` only for explicit experiment runs at first; production default stays legacy until validated.
- In `fresh_per_frame_color_state`:
  - fully resample color cells for the active ROI every analyzed frame
  - do not affine-carry `color_luma/u/v`, `color_raw_score`, or `color_contrast_weight` from prior frames
  - allow temporal logic only after candidate formation, not as a substitute for current-frame color evidence
- Keep the existing sampled-grid size and ROI bounds logic so timing and scan-zone behavior remain comparable to current runs.
- Keep the existing blob extraction stage, but feed it only current-frame color evidence in this mode.

### 3. Clarify and implement the intended color-cohesion signal
- Separate two concepts in code and docs:
  - `front_end_color_evidence`: rarity/residual score for a cell
  - `blob_cohesion`: whether neighboring cells belong in the same target-colored component
- Replace the current inert per-cell `color_contrast_weight` behavior with an explicit cohesion metric that is computed from current-frame local color similarity.
- Use that cohesion metric in two places only:
  - support-map weighting
  - blob neighbor-join decisions
- Do not use cohesion to rescue stale or unsampled cells.
- Keep the current local-support gate, but make the blob-building logic the main place where “adjacent pixels with similar color form a blob” is enforced.

### 4. Move temporal support later in the pipeline
- Keep temporal rescue and accumulator logic as post-front-end signals only.
- Temporal logic may:
  - boost ranking of a current-frame candidate near the persisted track
  - break ties between plausible blobs
  - suppress jitter in final winner selection
- Temporal logic may not:
  - create a color candidate when current-frame color evidence is absent
  - populate raw color state for unsampled cells
  - alter debug-target behavior when tracing is enabled

### 5. Phase 2: optional color-only YUV sidecar, disabled by default
- Do not modify the display decode path or the existing IR analyzer input path.
- Extend the AD-thread queue payload so it can optionally carry a color-analysis sidecar derived from the decoded `AVFrame`.
- Supported Phase 2 input policy:
  - if decoded frame format is a directly usable 4:2:0 YUV family format, capture native Y and chroma plane references or a compact copied sidecar for color analysis
  - otherwise fall back to the existing RGBA-derived color sampling path automatically
- Add a new internal analyzer helper for color sampling from YUV planes; do not replace the existing RGBA analyzer entrypoint.
- Keep the YUV color path experiment-only and off by default until it proves a measurable accuracy or latency improvement over Phase 1.
- Do not change overlay drawing, output frame formats, or render queue behavior.

## Interfaces and Defaults
- Keep the public analyzer API unchanged for Phase 1:
  - `anomaly_process_frame(...)` remains RGBA-based.
- Add internal experiment config only, not new user-facing UI controls initially:
  - `color_frontend_mode = legacy | fresh_rgba | fresh_yuv`
  - default for production codepath: `legacy`
  - default for experiment harness/profile runs in the next thread: `fresh_rgba`
- If Phase 2 is implemented, `fresh_yuv` is enabled only in test/harness configurations first and falls back to `fresh_rgba` automatically for unsupported pixel formats.

## Test Plan
### Functional correctness
- Verify IR-only runs produce byte-for-byte equivalent detector decisions before and after Phase 1 changes.
- Verify playback and overlay behavior are unchanged on representative local and live streams.
- Verify color debug target tracing enabled vs disabled produces identical detector decisions in each frontend mode.
- Verify `fresh_per_frame_color_state` does not emit candidates from carried stale color state.

### Color AD evaluation
- Run the unit harness from a known-good baseline first.
- Re-run reviewed Red1 cases with:
  - `legacy`
  - `fresh_rgba`
  - `fresh_yuv` if Phase 2 is built
- Use the app-like color-only replay profile as the gate for acceptance, not only the more permissive harness profile.
- For Red1 review, explicitly inspect target frames around the previously useful samples and compare:
  - rarity/front-end score
  - support-map seed count
  - blob area/span/fill
  - candidate rank and threshold outcome
  - final winner box alignment to reviewed center

### Performance and pressure behavior
- Measure AD-thread latency and queue depth under:
  - local playback
  - live stream replay
  - color-only mode
  - mixed algorithm mode
- Acceptance target for Phase 1:
  - no regression in render/playback stability
  - no increase in AD pressure-mode bypass frequency large enough to alter current runtime behavior materially
- Acceptance target for Phase 2:
  - must be at least neutral on stability and either improve Red1-like recall or reduce AD-thread cost enough to justify keeping it

## Assumptions and Chosen Defaults
- The next thread starts from a known-good baseline, not from the current experimental detector state.
- No FFmpeg decoder surgery is in scope for Phase 1.
- No changes to public app settings or operator-facing controls are in scope initially.
- The first implementation goal is trustworthy color evidence, not aggressive retuning of blob thresholds.
- YUV-native analysis is treated as an optional second step only after the fresh-RGBA experiment tells us whether the current front-end statefulness is the main blocker.
