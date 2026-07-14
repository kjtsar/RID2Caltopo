# Subject Color Evidence ROI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let selected subject color hints create one bounded ROI from compact multi-family color evidence, while preserving no-hint Color AD behavior.

**Architecture:** Add a lightweight subject-color evidence cluster path that reuses the existing sampled color grid and color-family classifier. It will collect selected-family sampled points with local contrast, form compact clusters, score by distinct family count, publish at most one color target observation, and expose debug telemetry. Existing candidate-level hint scoring remains in place as a lower-risk ranking helper.

**Tech Stack:** Native C Color AD (`anomaly_analysis.c`, `anomaly_color_detector.h`), native unit harness (`tools/anomaly_test/test_anomaly.c`), host video harness (`anomaly_video_test`), app-parity review scoring.

---

## Guardrails

- Keep no-hint mode behaviorally equivalent.
- Do not add a full-resolution pass; use existing sampled grid data and per-sample color state.
- Do not publish more than one subject-color-evidence ROI per frame/cadence window.
- Same-family repeats do not multiply score.
- Different selected families in a compact local cluster multiply subject evidence approximately by distinct family count.
- Require local contrast and compactness before ROI publication.
- Preserve existing candidate-level hint scoring and Red1/Red2 behavior unless the new cluster evidence intentionally qualifies.
- Record rejected cluster reasons so Floristan failures are explainable.

## File Structure

- Modify `app/src/main/cpp/anomaly_color_detector.h`
  - Add small scalar helpers and structs for subject color evidence cluster scoring.
- Modify `app/src/main/cpp/anomaly_analysis.h`
  - Add debug fields for the best subject color evidence cluster.
- Modify `app/src/main/cpp/anomaly_result_builder.h`
  - Add publication fields for cluster telemetry.
- Modify `app/src/main/cpp/anomaly_result_builder.c`
  - Copy cluster telemetry into `anomaly_result_t`.
- Modify `app/src/main/cpp/anomaly_analysis.c`
  - Collect selected-family evidence from sampled color points, form bounded clusters, score best cluster, and append one color observation when accepted.
- Modify `tools/anomaly_test/anomaly_video_test.c`
  - Emit cluster telemetry in color debug JSONL and summary if useful.
- Modify `tools/anomaly_test/test_anomaly.c`
  - Add scalar tests for cluster scoring and result publication.
- Modify `docs/superpowers/plans/2026-06-27-subject-color-evidence-roi.md`
  - Track implementation and qualification status.

## Task 0: Parent Preflight

**Owner:** parent/controller.

**Files:**
- Read only, except plan checklist updates.

- [x] Run `git status --short` and note unrelated dirty files.
- [x] Confirm `docs/superpowers/specs/2026-06-27-subject-color-evidence-roi-design.md` is present.
- [x] Run `cmake --build tools/anomaly_test/build_timing --target anomaly_test anomaly_video_test` to ensure the starting harness still builds.
- [x] Run `tools/anomaly_test/build_timing/anomaly_test` and record the starting pass count.
- [x] Preserve all existing untracked media/review files; do not clean the worktree.

Task 0 note: starting native build passed and `anomaly_test` reported `4384 passed, 0 failed`. The worktree contains many unrelated untracked local media/docs/generated files; this plan must ignore them.

## Task 1: Add Scalar Cluster Scoring Helpers And Tests

**Owner:** implementation subagent.

**Files:**
- Modify: `app/src/main/cpp/anomaly_color_detector.h`
- Modify: `tools/anomaly_test/test_anomaly.c`

- [x] Add a compact enum for subject color evidence reject reasons:
  - `ANOMALY_COLOR_EVIDENCE_REJECT_NONE`
  - `ANOMALY_COLOR_EVIDENCE_REJECT_NO_SELECTED_HINT`
  - `ANOMALY_COLOR_EVIDENCE_REJECT_NO_SELECTED_FAMILY`
  - `ANOMALY_COLOR_EVIDENCE_REJECT_WEAK_LOCAL_CONTRAST`
  - `ANOMALY_COLOR_EVIDENCE_REJECT_TOO_SPARSE`
  - `ANOMALY_COLOR_EVIDENCE_REJECT_TOO_BROAD`
  - `ANOMALY_COLOR_EVIDENCE_REJECT_LOW_SCORE`
- [x] Add `anomaly_color_subject_evidence_score_t` with:
  - `int selected_mask`
  - `int matched_mask`
  - `int distinct_family_count`
  - `float score`
  - `float confidence`
  - `bool accepted`
  - `int reject_reason`
- [x] Add `anomaly_color_count_hint_families(int mask)`.
- [x] Add a scalar scoring helper with this signature:

```c
static inline anomaly_color_subject_evidence_score_t anomaly_color_subject_evidence_score_cluster(
        int   selected_mask,
        int   matched_mask,
        int   support_count,
        float cluster_span_norm,
        float mean_luma_contrast,
        float mean_chroma_contrast,
        float density)
```

- [x] Implement the helper with these initial gates:
  - selected mask must be nonzero
  - matched selected families must be nonzero
  - support count must be at least `2`
  - either mean luma contrast >= `6.0f` or mean chroma contrast >= `8.0f`
  - normalized cluster span must be `> 0.0f` and `<= 0.090f`
  - density must be `>= 0.18f`
- [x] Implement approximate family scaling:
  - base family score: `1.05f * distinct_family_count`
  - contrast bonus capped at `0.45f`
  - density bonus capped at `0.35f`
  - compactness bonus capped at `0.25f`
  - final score capped at `4.25f`
- [x] Set acceptance thresholds:
  - one family accepted at score >= `2.35f`
  - two families accepted at score >= `2.65f`
  - three or more families accepted at score >= `3.00f`
- [x] Add native tests:
  - no selected mask rejects
  - unselected family rejects
  - one locally distinct compact family can be accepted only above high threshold
  - two selected families score higher than one under equal geometry
  - three selected families score higher than two under equal geometry
  - same-family repeat mask does not increase distinct family count
  - weak contrast rejects
  - broad cluster rejects
  - sparse cluster rejects
- [x] Run `cmake --build tools/anomaly_test/build_timing --target anomaly_test`.
- [x] Run `tools/anomaly_test/build_timing/anomaly_test`.
- [x] Expected result: new tests pass and no existing native tests regress.

Task 1 note: implemented by subagent and parent-refined after read-only review. The literal score recipe could not let a one-family cluster reach its stated high threshold, so the helper includes an explicit bounded support-quorum bonus capped at `0.70f`. Parent added constant-geometry tests proving same-family support only adds a capped bonus and does not increase distinct-family count. Updated native test run reported `4396 passed, 0 failed`.

## Task 2: Add Cluster Telemetry Publication

**Owner:** implementation subagent.

**Files:**
- Modify: `app/src/main/cpp/anomaly_analysis.h`
- Modify: `app/src/main/cpp/anomaly_result_builder.h`
- Modify: `app/src/main/cpp/anomaly_result_builder.c`
- Modify: `tools/anomaly_test/anomaly_video_test.c`
- Modify: `tools/anomaly_test/test_anomaly.c`

- [x] Add `anomaly_debug_color_subject_evidence_t` to `anomaly_analysis.h` with:
  - `bool enabled`
  - `bool accepted`
  - `int selected_mask`
  - `int matched_mask`
  - `int distinct_family_count`
  - `int support_count`
  - `int reject_reason`
  - `float score`
  - `float confidence`
  - `float center_x_norm`
  - `float center_y_norm`
  - `float span_norm`
  - `float density`
  - `float mean_luma_contrast`
  - `float mean_chroma_contrast`
- [x] Add that struct as `subject_evidence` inside `anomaly_debug_color_t`.
- [x] Add matching publication struct fields in `anomaly_result_builder.h`.
- [x] Add a publication function:

```c
void anomaly_result_publish_color_debug_subject_evidence(
        anomaly_result_t *result_out,
        const anomaly_result_color_debug_subject_evidence_publication_t *evidence);
```

- [x] Implement the copy in `anomaly_result_builder.c`.
- [x] Update `tools/anomaly_test/anomaly_video_test.c` color JSONL output to include:
  - `subject_evidence.enabled`
  - `subject_evidence.accepted`
  - `subject_evidence.selected_mask`
  - `subject_evidence.matched_mask`
  - `subject_evidence.distinct_family_count`
  - `subject_evidence.support_count`
  - `subject_evidence.reject_reason`
  - `subject_evidence.score`
  - `subject_evidence.center_x_norm`
  - `subject_evidence.center_y_norm`
  - `subject_evidence.span_norm`
  - `subject_evidence.density`
  - `subject_evidence.mean_luma_contrast`
  - `subject_evidence.mean_chroma_contrast`
- [x] Add a result-builder unit test that publishes a non-default subject evidence record and verifies every field is copied.
- [x] Run `cmake --build tools/anomaly_test/build_timing --target anomaly_test anomaly_video_test`.
- [x] Run `tools/anomaly_test/build_timing/anomaly_test`.
- [x] Expected result: debug fields are zero/default until Task 3 fills them.

Task 2 note: implemented by subagent and parent-reviewed. Parent reran `cmake --build tools/anomaly_test/build_timing --target anomaly_test anomaly_video_test`, `git diff --check -- ...`, and `tools/anomaly_test/build_timing/anomaly_test`; native tests reported `4410 passed, 0 failed`.

## Task 3: Integrate Sampled-Grid Evidence Clustering

**Owner:** implementation subagent.

**Files:**
- Modify: `app/src/main/cpp/anomaly_analysis.c`
- Modify: `tools/anomaly_test/test_anomaly.c` only if a small helper needs additional test exposure.

- [x] Add a local bounded candidate struct near the color scan logic:

```c
typedef struct {
    bool valid;
    int sx;
    int sy;
    int matched_mask;
    float score;
    float luma_contrast;
    float chroma_contrast;
} anomaly_color_subject_evidence_sample_t;
```

- [x] Collect at most `64` evidence samples per frame from the sampled grid:
  - only when `subject_color_hint_mask != 0`
  - only when `roi_state->color_valid_mask[idx] != 0`
  - classify sample luma/u/v with `anomaly_color_subject_hint_classify`
  - keep sample when `(matched_mask & selected_mask) != 0`
  - require local UV support at least `ANOMALY_COLOR_LOCAL_SUPPORT_MIN`
  - require local contrast using existing color contrast fields or local ring contrast if available
  - rank samples by saliency color map score plus contrast
  - keep top samples with simple replacement of the weakest score
- [x] Build clusters from the kept samples:
  - seed each cluster from one kept sample
  - include samples within a normalized radius of `0.075f`
  - compute distinct matched selected family mask
  - compute support count
  - compute center as average sample position
  - compute span as max normalized distance from center
  - compute mean luma/chroma contrast
  - compute density as support count divided by an estimated cluster area in sampled-grid cells
- [x] Score each cluster using `anomaly_color_subject_evidence_score_cluster`.
- [x] Pick the highest accepted cluster, or the highest rejected cluster for telemetry.
- [x] Convert one accepted cluster into an `anomaly_target_observation_t`:
  - `valid = true`
  - `publish_confirming = true`
  - `algorithm = ANOMALY_ALGO_COLOR`
  - center from cluster center
  - half side at least current `target_half_side`, but no less than `0.045f`
  - confidence from cluster score clamped to `0.35f..0.82f`
  - support radius around `half_side * 1.7f`
- [x] Append this observation before `anomaly_target_tracks_update_from_observations(...)` only if not near an existing observation.
- [x] Gate app-visible pressure:
  - only one subject-evidence observation per frame
  - reject near reviewed FP clusters using `anomaly_color_candidate_near_reviewed_fp_cluster`
  - do not bypass stale color lock logic in result publication
- [x] Publish subject evidence telemetry every frame when hints are enabled.
- [x] Run `cmake --build tools/anomaly_test/build_timing --target anomaly_test anomaly_video_test`.
- [x] Run `tools/anomaly_test/build_timing/anomaly_test`.
- [x] Run one short Floristan debug pass with `black,white,skin` and inspect JSONL for nonzero subject evidence telemetry.

Task 3 note: implemented by subagent and parent-tuned. Parent rejected a looser density experiment because it pulled clusters toward broad river/glare texture. Final shape keeps strict cluster density, gates multi-color sessions so subject-evidence ROI publication requires at least two matched selected families, and adds a subject-hint-only color display ROI floor (`0.10` normalized) so near-subject hinted color boxes cover verbal-description uncertainty without changing no-hint behavior. Parent rebuild passed and `anomaly_test` reported `4414 passed, 0 failed`.

## Task 4: Parent Floristan Tuning Gate

**Owner:** parent/controller.

**Files:**
- Modify native constants only if the first pass clearly over/under-shoots and the changes are small.
- Update this plan with results.

- [x] Run Floristan app-visible hinted replay:

```bash
tools/anomaly_test/build_timing/anomaly_video_test app/src/test/resources/vidcap/floristan.mp4 \
  --no-video \
  --app-defaults \
  --app-appearance color \
  --app-display-output \
  --subject-color-hints black,white,skin \
  --summary-json /private/tmp/floristan_bws_cluster_summary.json \
  --color-debug-jsonl /private/tmp/floristan_bws_cluster_color_debug.jsonl \
  -c /private/tmp/floristan_bws_cluster_detections.csv
```

- [x] Score against corrected positive-only sidecar:

```bash
python3 tools/anomaly_test/review_eval.py --json /private/tmp/floristan.positive.review.json /private/tmp/floristan_bws_cluster_detections.csv
```

- [x] Compare against current safe hinted baseline:
  - current safe baseline: `76` box events, `0/19` hits, `12` near misses, p50 miss `0.06118` norm
  - acceptable first target: at least `2` true-positive annotations without more than roughly doubling off-target box events
- [x] Inspect telemetry for:
  - accepted cluster count
  - distinct family count
  - reject reasons
  - whether accepted ROIs align with annotations or clutter
- [x] If accepted clusters are all clutter, back out or tighten Task 3 constants before proceeding.
- [x] If Floristan remains `0/19`, decide whether telemetry shows a fixable threshold problem or a need for shape detection.

Task 4 note: corrected positive-only Floristan sidecar written to `/private/tmp/floristan.positive.review.json` by converting the accidental person `false_positive` annotation to `missed_target` (`19` positives, `0` negatives). Final v3 replay with `black,white,skin` produced `83` box events and scored `7/19` true-positive annotations, `5` near misses, reviewed recall `0.368`, first hit at `2.202s`, off-target events `72`. This is close to the prior safe pressure baseline (`76` events) and materially improves from `0/19`.

## Task 5: Red1/Red2 Regression And Perf Gate

**Owner:** verification subagent or parent/controller.

**Files:**
- Read only, except plan status updates.

- [x] Run Red1 no-hint:

```bash
tools/anomaly_test/build_timing/anomaly_video_test app/src/test/resources/vidcap/Red1.mp4 \
  --no-video --app-defaults --app-appearance color --app-display-output \
  --summary-json /private/tmp/color_evidence_red1_nohint_summary.json \
  -c /private/tmp/color_evidence_red1_nohint.csv
```

- [x] Run Red1 red-hint:

```bash
tools/anomaly_test/build_timing/anomaly_video_test app/src/test/resources/vidcap/Red1.mp4 \
  --no-video --app-defaults --app-appearance color --app-display-output \
  --subject-color-hints red \
  --summary-json /private/tmp/color_evidence_red1_redhint_summary.json \
  --color-debug-jsonl /private/tmp/color_evidence_red1_redhint_debug.jsonl \
  -c /private/tmp/color_evidence_red1_redhint.csv
```

- [x] Score Red1:

```bash
python3 tools/anomaly_test/review_eval.py --json app/src/test/resources/vidcap/Red1.review.json \
  /private/tmp/color_evidence_red1_nohint.csv \
  /private/tmp/color_evidence_red1_redhint.csv
```

- [x] Run Red2 no-hint:

```bash
tools/anomaly_test/build_timing/anomaly_video_test app/src/test/resources/vidcap/Red2.mp4 \
  --no-video --app-defaults --app-appearance color --app-display-output \
  --summary-json /private/tmp/color_evidence_red2_nohint_summary.json \
  -c /private/tmp/color_evidence_red2_nohint.csv
```

- [x] Run Red2 red-hint:

```bash
tools/anomaly_test/build_timing/anomaly_video_test app/src/test/resources/vidcap/Red2.mp4 \
  --no-video --app-defaults --app-appearance color --app-display-output \
  --subject-color-hints red \
  --summary-json /private/tmp/color_evidence_red2_redhint_summary.json \
  --color-debug-jsonl /private/tmp/color_evidence_red2_redhint_debug.jsonl \
  -c /private/tmp/color_evidence_red2_redhint.csv
```

- [x] Score Red2:

```bash
python3 tools/anomaly_test/review_eval.py --json tools/anomaly_test/reviews/Red2.review.json \
  /private/tmp/color_evidence_red2_nohint.csv \
  /private/tmp/color_evidence_red2_redhint.csv
```

- [x] Compare to prior current values:
  - Red1: `65` boxes, recall `0.60`, precision `1.00`, off-target `55`, realtime around `1.8x`
  - Red2: `117` boxes, recall `1.00`, precision `1.00`, off-target `96`, realtime around `1.9x`
- [x] Expected result:
  - no-hint rows remain equivalent to prior current no-hint
  - red-hint does not materially inflate box events or off-target pressure
  - reviewed recall/precision do not regress
  - realtime factor remains faster than realtime

Task 5 note: first red-hint pass exposed material pressure inflation from one-family subject-evidence cluster publication (`Red1` +80 events, `Red2` +59 events). Parent tightened cluster-created ROI publication to require at least two matched selected families, while preserving single-color candidate hint scoring/telemetry. Final Red checks:

- `Red1` no-hint: `65` events, recall `0.60`, precision `1.00`, off-target `55`, realtime `0.69x`, max frame `583.03ms`.
- `Red1` red-hint: `65` events, recall `0.60`, precision `1.00`, off-target `55`, realtime `0.69x`, max frame `611.99ms`.
- `Red2` no-hint: `117` events, recall `1.00`, precision `1.00`, off-target `96`, realtime `1.52x`, max frame `394.01ms`.
- `Red2` red-hint: `117` events, recall `1.00`, precision `1.00`, off-target `96`, realtime `1.50x`, max frame `396.30ms`.

Red-hint box area is larger because subject-color hints now use a `0.10` normalized display ROI floor for color boxes, but box event count, off-target event count, recall, and precision were unchanged in both Red clips.

Final review note: read-only review found no blocking issues and called out the two-family publication gate as the critical safety latch. Parent added a helper-level regression test proving accepted one-family subject evidence does not publish a standalone ROI, while accepted two-family evidence can. Final native run reported `4418 passed, 0 failed`; `test_app_parity_defaults_and_timing.py` reported `14` tests OK.

## Task 6: Final Parent Verification And Review

**Owner:** parent/controller with optional final review subagent.

**Files:**
- Read only, except plan status updates.

- [ ] Run `git diff --check` scoped to touched files.
- [ ] Run `tools/anomaly_test/build_timing/anomaly_test`.
- [ ] Run `python3 tools/anomaly_test/test_app_parity_defaults_and_timing.py`.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.anomaly.AnomalyConfigTest`.
- [ ] Dispatch a final code review focused on:
  - no-hint equivalence risk
  - hinted ROI pressure risk
  - telemetry completeness
  - sampled-grid CPU/memory budget
  - Red1/Red2 regression
  - Floristan acceptance evidence
- [ ] Summarize whether the cluster path should ship, be tightened, or be backed out.

## Open Implementation Notes

- This plan intentionally does not add UI changes; it uses the existing multi-select subject color hints.
- The first implementation should bias toward explainability and bounded output, even if Floristan needs a second tuning pass.
- If the subject evidence path creates too many off-target boxes, the first tightening levers should be local contrast, compact span, density, and one-family threshold.
