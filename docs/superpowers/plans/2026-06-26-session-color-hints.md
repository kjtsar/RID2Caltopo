# Session Color Hints Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add session-scoped coarse subject-color hints to Color AD so witness-style descriptions such as "white swim trunks" or "red shirt and blue pants" can narrowly rescue target-like color candidates, while no-hint mode preserves the current uniqueness-first behavior and realtime budget.

**Architecture:** Thread a subject-color bitmask from the anomaly settings dialog through Kotlin config, JNI, native config, host harness, and debug telemetry. In native Color AD, classify only existing provisional/retained color candidates into coarse semantic color families, apply a bounded hint rescue after hard target-plausibility gates, and add a capped proximity boost when multiple selected color families appear near each other. Do not add a full-frame color pass, persistent user color preferences, arbitrary RGB picking, or a movement requirement.

**Tech Stack:** Kotlin/Compose settings UI, `StreamsViewModel` config state, JNI bridge in `ffmpeg_bridge.c`, native C anomaly detector, `anomaly_video_test`, pytest/native harness qualification.

---

## Guardrails

- Keep all edits review-first in the current worktree. Do not commit unless the user explicitly asks.
- Preserve no-hint behavior as an equivalence gate. With bitmask `0`, ranking and publication should match current output aside from added telemetry fields.
- Keep app-visible qualification authoritative: use `--app-defaults --app-display-output` for behavior claims.
- Keep Color AD uniqueness-first. Hints can rescue locally distinct target-like candidates; they must not make common broad regions win by color label alone.
- Keep processor and memory cost bounded: classify candidate records already produced by the color path, cap proximity work to the retained candidate set, and avoid new dense frame scans.
- Treat selected colors as inclusive first-level gates for operator descriptions, but still require size, compactness, local distinctness, and existing hard clutter gates.

## File Structure

- Modify `app/src/main/java/org/ncssar/rid2caltopo/video/anomaly/AnomalyModels.kt`.
- Modify `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt`.
- Modify `app/src/main/java/org/ncssar/rid2caltopo/video/StreamTile.kt`.
- Modify `app/src/main/java/org/ncssar/rid2caltopo/video/ffmpeg/FfmpegBridge.kt`.
- Modify `app/src/main/cpp/ffmpeg_bridge.c`.
- Modify `app/src/main/cpp/anomaly_analysis.h`.
- Modify `app/src/main/cpp/anomaly_detector.c`.
- Modify `app/src/main/cpp/anomaly_analysis.c`.
- Modify `app/src/main/cpp/anomaly_result_builder.c`.
- Modify `app/src/main/cpp/anomaly_appearance_candidates.c` only if final ranking integration requires candidate-sort metadata.
- Modify `tools/anomaly_test/anomaly_video_test.c`.
- Modify or add focused tests under `tools/anomaly_test/` and Android unit tests under `app/src/test/` as locally appropriate.

## Task 0: Preflight And Dirty Tree Map

**Owner:** parent/controller

- [x] Run `git status --short`.
- [x] Run `rg -n "colorTargetCandidateLimit|NativeAnomalyConfig|nativeUpdateAnomalyConfig|color_candidate|color_uniqueness_rank|Color Candidates|AnomalyConfig" app/src/main/java/org/ncssar/rid2caltopo/video app/src/main/cpp tools/anomaly_test`.
- [x] Confirm `docs/superpowers/specs/2026-06-26-session-color-hints-design.md` is present and use it as the behavior contract.
- [x] Record any unrelated dirty files so child packets do not revert or overwrite them.
- [x] If local generated files or prior harness outputs exist, leave them alone unless a validation command overwrites `/private/tmp` outputs.

Preflight note: the checkout already contains many unrelated untracked files and generated harness outputs. Child packets must keep to their declared write sets and must not clean or revert unrelated paths.

## Task 1: Add Kotlin Config And Settings UI Plumbing

**Owner:** child packet, disjoint write set limited to Kotlin/UI bridge files.

**Files:**
- Modify `app/src/main/java/org/ncssar/rid2caltopo/video/anomaly/AnomalyModels.kt`.
- Modify `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt`.
- Modify `app/src/main/java/org/ncssar/rid2caltopo/video/StreamTile.kt`.
- Modify `app/src/main/java/org/ncssar/rid2caltopo/video/ffmpeg/FfmpegBridge.kt`.

- [x] Add subject-color bitmask constants for:
  - `DARK_BLACK = 1 shl 0`
  - `WHITE_LIGHT = 1 shl 1`
  - `RED = 1 shl 2`
  - `ORANGE_YELLOW = 1 shl 3`
  - `GREEN = 1 shl 4`
  - `BLUE = 1 shl 5`
  - `SKIN_TONE = 1 shl 6`
- [x] Add `subjectColorHintMask: Int = 0` to `AnomalyConfig` and `NativeAnomalyConfig`.
- [x] Thread the mask through `toNativeConfig()` and `FfmpegBridge.updateAnomalyConfig(...)`.
- [x] Add `StreamsViewModel` state mutation for replacing the full bitmask or toggling one color flag.
- [x] Add a `Subject colors` multi-select chip row in the anomaly settings dialog, visible when Color appearance mode is active.
- [x] Labels must be compact: `Dark/Black`, `White/Light`, `Red`, `Orange/Yellow`, `Green`, `Blue`, `Skin tone`.
- [x] Keep session scope only. Do not add datastore persistence or global preferences.
- [x] Add focused Kotlin tests if this repo has an existing lightweight config/view-model test seam for anomaly config updates.
- [x] Run `./gradlew :app:compileDebugKotlin`.
- [x] Expected result: Kotlin/JNI declarations compile, native implementation may still need Task 2 before broader native-linked gates pass.

Task 1 accepted: implementation by child packet Avicenna, spec review approved by Kepler. Focused config test, compile, and touched-file diff check passed. Known handoff concern is expected: native `nativeUpdateAnomalyConfig` signature must be extended in Task 2.

## Task 2: Add Native Config, Harness CLI, And Telemetry Fields

**Owner:** child packet, disjoint write set limited to bridge/native config/harness telemetry files.

**Files:**
- Modify `app/src/main/cpp/ffmpeg_bridge.c`.
- Modify `app/src/main/cpp/anomaly_analysis.h`.
- Modify `app/src/main/cpp/anomaly_detector.c`.
- Modify `app/src/main/cpp/anomaly_result_builder.c`.
- Modify `tools/anomaly_test/anomaly_video_test.c`.
- Modify `tools/anomaly_test/test_app_parity_defaults_and_timing.py` if app-default metadata checks need the new field.

- [x] Add `subject_color_hint_mask` to `anomaly_config_t`, defaulting to `0`.
- [x] Extend `nativeUpdateAnomalyConfig(...)` and its log line to accept/report the mask.
- [x] Add `--subject-color-hints` or equivalent CLI support to `anomaly_video_test`, accepting comma-separated names such as `white,skin,blue`.
- [x] Ensure app-default harness mapping reports mask `0` by default.
- [x] Add debug JSON/CSV fields for selected hint mask even before scoring uses it.
- [x] Add candidate-level debug fields with stable zero defaults:
  - `color_hint_matched_mask`
  - `color_hint_score`
  - `color_hint_proximity_count`
  - `color_hint_proximity_score`
  - `color_hint_rescue`
  - `color_hint_reject_reason`
- [x] Run `cmake --build tools/anomaly_test/build_timing --target anomaly_video_test`.
- [x] Run `python3 tools/anomaly_test/test_app_parity_defaults_and_timing.py`.
- [x] Expected result: app-default metadata stays green and no-hint telemetry reports disabled/zero values.

Task 2 accepted: implementation by child packet Feynman, controller added runtime config transition coverage for live hint-mask changes, and Darwin review approved. Verified `cmake --build tools/anomaly_test/build_timing --target anomaly_test anomaly_video_test`, app parity metadata tests, native tests, help output, invalid hint parsing, diff check, and a short Floristan parse sanity check showing `white,skin,blue -> 0x62`.

## Task 3: TDD Native Color-Family Classification

**Owner:** child packet, focused native implementation.

**Files:**
- Modify `app/src/main/cpp/anomaly_analysis.c`.
- Modify or add native tests in `tools/anomaly_test/test_anomaly.c` or the closest existing native test file.

- [x] Add red tests for candidate-level family classification using representative luma/U/V or equivalent internal color values:
  - white/light matches high-luma low-saturation samples.
  - dark/black matches low-luma samples.
  - red, orange/yellow, green, and blue match their expected chroma ranges.
  - skin tone matches broad plausible skin samples but not saturated red/orange clothing samples.
  - no selected mask yields hint score `0`.
- [x] Run `cmake --build tools/anomaly_test/build_timing` and the focused native test executable; verify the new tests fail for missing functionality.
- [x] Implement a small helper that classifies one candidate using existing candidate color data. Prefer `static` helpers local to `anomaly_analysis.c` unless a test seam requires a tiny header exposure.
- [x] Keep the classifier broad and cheap; no image-wide scans or heap allocations.
- [x] Run the focused native tests again and verify the new tests pass.
- [x] Run `tools/anomaly_test/build_timing/anomaly_test`.

Task 3 accepted: implementation by child packet Bohr and review approved by Archimedes. Controller re-ran `cmake --build tools/anomaly_test/build_timing --target anomaly_test` and touched-file `git diff --check`; child reported native tests at `4370 passed, 0 failed`.

## Task 4: Integrate Hint Rescue And Multi-Color Proximity

**Owner:** child packet, focused native scoring/ranking implementation.

**Files:**
- Modify `app/src/main/cpp/anomaly_analysis.c`.
- Modify `app/src/main/cpp/anomaly_appearance_candidates.c` only if needed for final sort/rank fields.
- Modify native tests under `tools/anomaly_test/`.

- [x] Add red tests for scoring behavior:
  - no-hint ranking is equivalent for a small synthetic candidate set.
  - one white/light candidate that is compact and locally distinct can enter the hint rescue path despite weaker global rarity.
  - a broad/homogeneous white/light region remains rejected.
  - nearby red and blue target-like candidates receive a bounded proximity boost.
  - distant red and blue candidates do not receive proximity support.
- [x] Run focused native tests and verify the new tests fail for missing scoring/proximity behavior.
- [x] Integrate hint scoring after hard candidate formation and before final Color AD ranking/publication.
- [x] Calculate proximity over the capped retained/provisional candidate set only.
- [x] Bound the boost so global uniqueness and existing hard gates still dominate non-target clutter.
- [x] Prefer one published box for a close multi-color support cluster instead of multiple duplicate boxes.
- [x] Populate debug fields and rejection reasons for hint-rescued or hint-rejected candidates.
- [x] Run `cmake --build tools/anomaly_test/build_timing`.
- [x] Run `tools/anomaly_test/build_timing/anomaly_test`.

Task 4 implementation note: candidate-level hint scoring, bounded proximity, debug fields, skin-tone broadening, and seed-support bypass were implemented and verified (`4384 passed, 0 failed`). A later experiment that synthesized hinted seed reserve observations was rejected and backed out because Floristan stayed at `0/19` hits while app-visible box pressure rose to 151 off-target events. The accepted Task 4 state only rescues real retained candidates and does not worsen the app-visible Floristan count.

## Task 5: Host Harness Qualification On Floristan And Color Regressions

**Owner:** parent/controller, with optional child verification packet.

**Files:**
- Modify tests or docs only if qualification reveals missing harness coverage.

- [x] Rebuild the host harness:

```bash
cmake --build tools/anomaly_test/build_timing --target anomaly_video_test
```

- [x] Run no-hint Floristan app-parity baseline:

```bash
tools/anomaly_test/build_timing/anomaly_video_test app/src/test/resources/vidcap/floristan.mp4 --no-video --app-defaults --app-appearance color --app-display-output --summary-json /private/tmp/floristan_no_hint_summary.json --color-debug-jsonl /private/tmp/floristan_no_hint_color_debug.jsonl -c /private/tmp/floristan_no_hint_detections.csv
```

- [x] Score with the corrected positive-only review sidecar:

```bash
python3 tools/anomaly_test/review_eval.py /private/tmp/floristan_no_hint_detections.csv /private/tmp/floristan.positive.review.json --summary-json /private/tmp/floristan_no_hint_eval.json
```

- [x] Run Floristan with `White/Light` hint:

```bash
tools/anomaly_test/build_timing/anomaly_video_test app/src/test/resources/vidcap/floristan.mp4 --no-video --app-defaults --app-appearance color --app-display-output --subject-color-hints white --summary-json /private/tmp/floristan_white_hint_summary.json --color-debug-jsonl /private/tmp/floristan_white_hint_color_debug.jsonl -c /private/tmp/floristan_white_hint_detections.csv
```

- [x] Score the hinted replay:

```bash
python3 tools/anomaly_test/review_eval.py /private/tmp/floristan_white_hint_detections.csv /private/tmp/floristan.positive.review.json --summary-json /private/tmp/floristan_white_hint_eval.json
```

- [x] Compare no-hint vs hinted Floristan metrics: recall, false positives/off-targets, near misses, `realtime_factor`, max frame time, and hint-rescue debug reasons.
- [ ] Run Red1/Red2 visible-color reviewed regressions with no hints to prove no-hint behavior did not regress.
- [ ] Run at least one Red clip with an intentionally matching hint to confirm hints do not inflate off-target publication.
- [ ] Run the existing visible-color performance matrix or the repo's accepted Color AD realtime qualification command, with attention to max frame time.
- [ ] Expected result: no-hint output stays equivalent, hinted Floristan improves or produces explainable gated misses without unacceptable off-target/perf regression.

Task 5 current result: `black,white,skin` app-parity replay matched the safe no-worsening pressure profile (`76` box events) but did not improve Floristan recall (`0/19` true-positive annotations, `12` near misses, miss p50 `0.06118` norm). Target tracing showed annotated samples can be support-seed eligible, but no retained color candidate lands near the subject; pure candidate reranking is therefore insufficient for this clip.

## Task 6: App-Bound Verification And Final Review

**Owner:** parent/controller

- [ ] Run `git diff --check`.
- [ ] Run `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`.
- [ ] Run `tools/anomaly_test/build_timing/anomaly_test`.
- [ ] Run `./gradlew :app:compileDebugKotlin`.
- [ ] Run `./gradlew :app:releaseCheck` before claiming app-bound readiness.
- [ ] Inspect `git diff` for unrelated edits, accidental generated churn, and persistent settings storage.
- [ ] Dispatch a final code-review subagent over the integrated diff, focused on:
  - no-hint equivalence risk
  - JNI signature/order mismatch risk
  - broad color-family overmatching
  - performance/memory budget
  - app-visible qualification gaps
- [ ] Record final acceptance results in the parent thread.

## Deferred Work

- Shape detector or body-part geometry model.
- Precision color picker, eyedropper, arbitrary RGB values, or user-authored complex color rules.
- Permanent saved color preferences.
- Movement-required rescue logic for river searches.
