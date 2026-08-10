# Visible Color AD Next-Thread Handoff

## 2026-05-12 Stride-Gated Reacquisition Override

For the next thread, start with
[Visible_Color_AD_Stride_Gated_Reacquisition_Handoff_20260512.md](/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Stride_Gated_Reacquisition_Handoff_20260512.md).

Current live state:

- stride-gated app control loop is now active with `stride=10`
- AR is healthy in the latest app log; scale is near `1.0`
- top-6 interim blob scanning is active
- no ROI/overlay is produced in the latest current-code run
- next work should focus on Red1 reacquisition/candidate geometry under
  target-only/partial/cadence-full modes, especially zero-area / over-span
  `area` rejects

This note is the handoff for the next thread continuing the low-risk visible
color architecture work after one additional reviewed-Red1 candidate-ranking
pass.

## 2026-05-12 Parent Coordination Override

This section supersedes the older `2026-05-11` live-thread override below for
the next visible-color work cycle.

Current checkpoint truth:

- start from the current tree state after
  [Visible_Color_AD_Clean_Handoff_20260512.md](/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Clean_Handoff_20260512.md)
- accepted baseline repair remains active:
  - post-dense `color_small_target_priority_scale()` penalty is scoped to fresh
    frontend modes only
- no later experiment child from the clean-handoff cycle remains adopted
- do not continue the older winner-gate / small-dominates packet as the primary
  next step unless this BlobOfInterest line is explicitly paused

Current parent thesis:

- current `ring`, `support_mass`, and `area` failures may be symptoms of a bad
  blob definition rather than independently useful reject classes
- the next step is not another late-gate threshold relaxation
- define and measure a dense `BlobOfInterest` contract before mutating the
  detector again:
  - frame and recent-frame color uniqueness
  - dense intra-blob color homogeneity
  - strong surrounding boundary contrast
  - smaller than `Small Target` scale
  - persistence for `Hits` frames in the same affine-registry-locked location

Launch sequence status:

1. `child_dense_arch_map_v2` — read-only architecture child completed; it
   recommended implementation-first work at the fresh candidate-construction
   seam.
2. `child_blob_interest_cohort_v1` — read-only cohort child did not produce the
   requested cohort metrics; it only packetized/ledgered the queue.
3. `child_dense_blob_interest_v1` — active mutating experiment child assigned
   on `2026-05-12` to implement the dense BlobOfInterest seam; completed and
   reverted its own diff after proving recall movement with unacceptable
   fanout/perf.
4. `child_blob_interest_telemetry_v1` — hold as fallback only if the active
   mutating child proves current telemetry is insufficient.
5. `child_dense_blob_seedfilter_v1` — active mutating experiment child assigned
   on `2026-05-12` to preserve the dense-continuity extraction win while
   reducing seed/candidate fanout before dense growth; completed with candidate
   code left active for parent review.

Latest child result:

- `child_dense_blob_interest_v1` proved the dense-continuity seam is live:
  - baseline/loose path: `0` reviewed target extractions
  - tight dense continuity: `8` reviewed target extractions
- the child correctly rejected/reverted the diff because it also increased
  nuisance/fanout:
  - boxes rose from `3` to `11`
  - candidate entries rose from `115` baseline / `454` loose to `606` tight
  - Red1 replay wall time rose to `15.98s`
- next follow-up should keep the dense continuity insight but add seed/fanout
  control before another full mutating architecture attempt.

Latest seed-filter result:

- `child_dense_blob_seedfilter_v1` retained most of the dense-continuity recall
  while reducing fanout:
  - target extractions: `7` versus `8` in tight proof and `0` baseline
  - candidate entries: `512` versus `606` tight proof
  - dense checks: `7337` versus `14044` tight proof
  - boxes: `6` versus `11` tight proof
  - Red1 wall time: `12.66s` versus `15.98s` tight proof
- validation passed native build/tests, timing build/tests, Kotlin compile,
  trace/no-trace parity, and main regression hard gates.
- not ready for adoption because published Red1 boxes are still off-target and
  advisory color suite Red1 recall remains `0`.
- new live seam: target candidates now exist but usually lose scoring/promotion
  after extraction.

### Active Child Packet: `child_dense_target_promotion_v1`

Child id: `child_dense_target_promotion_v1`
Role: `experiment`
Checkpoint: current tree state with `child_dense_blob_seedfilter_v1` candidate
code active for parent review.
Hypothesis: after the seed-filter result, reviewed Red1 target candidates are
being extracted but usually lose scoring/promotion to off-target winners; a
bounded fresh-only post-extraction ranking/persistence adjustment can promote
recurrent dense BlobOfInterest candidates without increasing dense seed fanout
or re-opening late `ring`/`support_mass`/`area` relaxations.
Write scope:

- `app/src/main/cpp/anomaly_analysis.c`
- `app/src/main/cpp/anomaly_analysis.h`
- directly related native tests only, if needed

Implementation requirements:

- keep seed-filter dense cap/fanout behavior unchanged
- do not increase candidate count or dense checks as the mechanism
- add only fresh-only post-extraction promotion/ranking or persistence behavior
  for dense BlobOfInterest candidates recurring in approximately the same
  affine-registered location across `Hits` / `min_hits`
- use existing candidate and accumulator/persistence state where possible
- do not lower the global detection threshold broadly
- preserve legacy extraction/scoring behavior and JSONL compatibility

Adoption gates:

- fresh Red1 published boxes move to the reviewed target or reviewed recall
  becomes nonzero in the applicable reviewed suite
- seed-filter extraction/fanout improvements are retained
- boxes do not exceed seed-filter's `6` unless the added boxes are true target
  hits
- legacy/native fixtures and main regression hard gates stay green

Latest promotion result:

- `child_dense_target_promotion_v1` added fresh-only recurrent color-candidate
  promotion state on top of the seed-filter tree and left code active for
  parent review.
- correctness moved from extracted-but-never-winning to published target hits:
  - target extractions stayed `7`
  - target winners improved `0` to `2`
  - Red1 published boxes stayed at `6`
  - frames `26-28` now publish on the reviewed target; frames `74-76` remain
    off-target
- fanout stayed controlled:
  - candidate entries stayed `512`
  - dense checks stayed `7337`
- validation passed native build/tests, `ctest`, timing build/tests, Kotlin
  compile, trace/no-trace parity, and main regression hard gates.
- color reviewed suite improved to `9 TP / 2 FP` in the visible-color baseline;
  dense-gold stayed `0 TP`.
- perf caveat:
  - Red1 wall time rose from `12.66s` to `14.00s`
  - app-like realtime factor fell from about `0.41x` to `0.36x`
  - dense-gold realtime factor fell from about `0.041x` to `0.038x`
- parent interpretation: this is the first candidate worth independent
  validation because reviewed Red1 published-target recall is nonzero without
  additional fanout or boxes, but perf remains a review item.

### Active Validation Packet: `child_dense_validation_v2`

Child id: `child_dense_validation_v2`
Role: `validation`
Checkpoint: current tree state with `child_dense_blob_seedfilter_v1` and
`child_dense_target_promotion_v1` code active for parent review.
Hypothesis: the active dense seed-filter + target-promotion candidate is a real
correctness win rather than a telemetry artifact, with acceptable regression
behavior and a clearly stated perf tradeoff.
Write scope: read-only; no repo-tracked edits.

Validation focus:

- independently rerun native/timing tests, Kotlin compile, Red1 legacy/fresh
  app-parity replay, main reviewed regression suite, color reviewed suite, and
  visible-color perf benchmark
- confirm Red1 fresh target extraction count, target winner count, total boxes,
  candidate entries, dense checks, and wall time
- confirm frames `26-28` publish on reviewed Red1 target and frames `74-76`
  remain off-target
- confirm main hard gates and quantify color-suite/perf tradeoff

Validation result:

- `child_dense_validation_v2` independently confirmed the active seed-filter +
  target-promotion candidate.
- all requested commands passed and the validation child made no repo-tracked
  edits.
- Red1 fresh replay:
  - `7` target extractions
  - `2` target winners
  - `6` boxes
  - `512` candidate entries
  - `7337` dense checks
  - `11.02s` wall time / `0.463x` realtime
- published boxes confirm frames `26-28` on the reviewed Red1 target and
  frames `74-76` off-target.
- main hard gates preserved: `22/0`, `37/0`, `23/2` TP/FP.
- color suite matched promotion: visible-color baseline `9 TP / 2 FP`;
  dense-gold `0 TP / 0 FP`.
- perf aggregate:
  - app-like: `0.394x`, `81.63 ms`, color scoring `41.21 ms`
  - dense-gold: `0.0397x`, `843.53 ms`, color scoring `566.32 ms`
- parent decision: promote the active seed-filter + target-promotion candidate
  to the current candidate checkpoint, with a required follow-up to eliminate
  the remaining off-target frames `74-76` and continue perf recovery.

App/harness parity blocker found after user reran Android app:

- the confirmed target hits were from the host replay harness with
  `--color-frontend fresh-rgba`, not from a verified Android app run
- Android app color playback could still send native `colorFrontend=0`
  (`Legacy`) because `AnomalyConfig.colorFrontendMode` defaulted to `Legacy`
  even when resolved appearance mode was `Color`
- parity fix applied in `AnomalyConfig.toNativeConfig()`:
  - resolved Color appearance now maps legacy/default frontend to
    `FreshRgba` for native config
  - Thermal appearance still sends `Legacy`
- focused validation passed:
  - `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.anomaly.AnomalyConfigTest :app:compileDebugKotlin`
- next app rerun must verify logs show
  `anomaly config applied ... colorFrontend=1` before judging detector behavior
  from the Android UI.
- user reran the app after this parity fix and confirmed the Red1 detections
  are now visible in Android.
- new live blocker: app detector throughput is only about `10 fps`, so the next
  work should focus on performance without losing the confirmed Red1 target
  hits.

First perf follow-up after Android confirmation:

- app log `log.txt` confirms Android is now using `colorFrontend=1`, publishes
  the Red1 target overlay on frames `26-28`, and keeps the AD queue saturated at
  `q=2/3` while the worker lags playback.
- current tree baseline Red1 app-like replay measured about `0.37x` realtime
  with avg-total `85.92 ms`, `sampled_grid_prep=26.61 ms`, and
  `color_scoring=42.13 ms`.
- dense seed cap was tightened from `48` to `32` in
  `ANOMALY_COLOR_DENSE_SEED_TOP_K`.
  - focused Red1 run improved to `0.41x` / `78.19 ms` in the cleanest measured
    run while preserving `6` boxes and the frame-26 target winner.
  - app-like three-clip profile improved from `0.37x / 86.44 ms` to
    `0.390x / 82.04 ms`; `color_scoring` moved from `43.62 ms` to `41.29 ms`
    and `sampled_grid_prep` from `23.29 ms` to `21.96 ms`.
  - `K=24` was tried and rejected: frame `26` fell from target `winner` to
    merely `extracted`, and timing did not improve reliably.
- validation passed:
  - `cmake --build build && cmake --build build_timing`
  - `./build_timing/anomaly_test` (`83 passed, 0 failed`)
  - `./gradlew :app:compileDebugKotlin`
  - main regression suite retained `22/0`, `37/0`, `23/2` TP/FP profiles
  - visible-color suite retained baseline `9 TP / 2 FP` and dense-gold
    `0 TP / 0 FP`
- next perf seam is still the color path itself, not thread plumbing:
  `color_scoring` remains the largest bucket, followed by `sampled_grid_prep`.

### Active Child Packet: `child_dense_blob_seedfilter_v1`

Child id: `child_dense_blob_seedfilter_v1`
Role: `experiment`
Checkpoint: current tree state after the reverted
`child_dense_blob_interest_v1` proof; the tree includes staged detector/test
edits, so the child must not reset, stash, or revert unrelated work.
Hypothesis: the tight dense-continuity BlobOfInterest path can keep the Red1
target extraction gain while reducing candidate fanout, nuisance boxes, and
replay cost if eligible dense-growth seeds are filtered/prioritized before
dense growth.
Write scope:

- `app/src/main/cpp/anomaly_analysis.c`
- `app/src/main/cpp/anomaly_analysis.h`
- directly related native tests only, if needed

Implementation requirements:

- preserve the useful tight dense-continuity insight from
  `/tmp/child_dense_blob_interest_v1_candidate.diff`
- add fresh-only pre-dense-growth seed/fanout control before invoking dense
  growth
- base seed filtering/prioritization on existing local-peak, support, rarity,
  current/recent commonness, contrast/boundary, and small-target signals
- prefer a bounded top-K seed queue or suppression strategy over dense growth
  for every support peak
- keep legacy extraction, AR-lock/revisit, debug contracts, and global
  `ring`/`support_mass`/`area` thresholds unchanged

Adoption gates:

- retain material target-extraction improvement versus baseline, ideally near
  the `8` extracted frames from the tight proof
- reduce candidate entries and Red1 wall time versus the tight proof branch
- do not increase boxes beyond the tight proof branch
- keep legacy/native fixtures green

### Child Packet: `child_blob_interest_cohort_v1`

Child id: `child_blob_interest_cohort_v1`
Role: `analysis`
Checkpoint: current tree state after
`docs/Visible_Color_AD_Clean_Handoff_20260512.md`
Hypothesis: current false winners and reviewed Red1 target failures can be
separated by a dense BlobOfInterest contract: frame/recent-frame color
uniqueness, dense color homogeneity, boundary contrast, small-target scale, and
AR-locked persistence.
Write scope: read-only. Do not edit repo-tracked files. The child may inspect
existing artifacts and run read-only analysis commands that write only to
`/tmp` if necessary.

Context and required reads:

- `/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Clean_Handoff_20260512.md`
- `/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Thread_Operating_Model.md`
- latest artifact set named in the clean handoff, especially:
  - `/tmp/red1_app_fresh_color_debug_child_peak_plateau_seed_v1.jsonl`
  - `/tmp/red1_app_fresh_color_debug_child_compact_reject_gate_v1.jsonl`
- current code around dense verification and blob candidate gates in:
  - `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c`

Non-goals:

- no code edits
- no threshold tuning
- no full validation sweep
- do not propose broad gate relaxations as the primary answer

Questions to answer:

1. For surfaced target-local blobs, what concrete metrics are missing from
   telemetry to prove or disprove the BlobOfInterest contract?
2. Which current metrics are misleading because they are measured on coarse
   support blobs instead of dense components?
3. What minimal read-only report or telemetry addition should precede a
   mutating experiment?
4. How should the top-right homogeneous false-positive class from the app
   screenshot be represented as a negative-control test or metric?

Expected report format:

- attempted analysis
- artifacts inspected
- strongest evidence
- recommended missing telemetry/report fields
- recommended adoption gates for the next experiment
- conclusion: launch dense-growth experiment now | add telemetry/report child
  first | refine packet first

### Active Child Packet: `child_dense_blob_interest_v1`

Child id: `child_dense_blob_interest_v1`
Role: `experiment`
Checkpoint: current tree state after
`docs/Visible_Color_AD_Clean_Handoff_20260512.md`; the tree includes staged
detector/test edits, so the child must not reset, stash, or revert unrelated
work.
Hypothesis: fresh-mode candidate construction should form a dense pixel-first
BlobOfInterest before coarse support-blob reject gates; this should let compact
unique Red1 target-local blobs survive extraction while common/boundaryless
nuisance regions fail dense uniqueness or boundary diagnostics.
Write scope:

- `app/src/main/cpp/anomaly_analysis.c`
- `app/src/main/cpp/anomaly_analysis.h`
- directly related native tests only, if needed

Implementation requirements:

- add a fresh-only dense BlobOfInterest builder near the existing dense verifier
- enter it from the fresh path in `extract_color_blob_candidates()`, replacing
  the current fresh peak-seed shortcut into `build_color_blob_candidate()`
- use 8-neighbor dense pixel growth from eligible unique/local-peak seeds
- enforce `Small Target` scale on the dense component, not the coarse support
  component
- carry forward centroid, sample seed, dense bbox, area/span/fill, quality,
  isolation, ring/support diagnostics, `retention_rank`, and target-trace fields
- keep legacy fallback, `build_color_support_map()`, and AR-lock/revisit
  re-entry unchanged

Non-goals:

- do not change legacy extraction behavior
- do not tune global `ring`, `support_mass`, or `area` thresholds
- do not revive large-blob rescue
- do not change winner-gate or AR-lock/revisit policy first

Adoption gates:

- reviewed Red1 target reaches extracted or matched candidate in fresh mode, or
  materially moves toward extraction without increasing nuisance winners
- legacy/native fixtures stay green
- visible-color perf stays within the current envelope unless the correctness
  win is strong enough for explicit parent review

### Conditional Child Packet: `child_blob_interest_telemetry_v1`

Child id: `child_blob_interest_telemetry_v1`
Role: `experiment`
Checkpoint: current tree state after
`docs/Visible_Color_AD_Clean_Handoff_20260512.md`, plus the parent-reviewed
report from `child_blob_interest_cohort_v1`
Hypothesis: the next mutating detector experiment should be preceded by minimal
BlobOfInterest telemetry/reporting so parent review can distinguish true
dense-component failures from coarse-support measurement artifacts.
Write scope:

- `app/src/main/cpp/anomaly_analysis.c`
- `app/src/main/cpp/anomaly_analysis.h`
- `tools/anomaly_test/anomaly_video_test.c`
- directly related native telemetry tests only

Required implementation shape:

- add telemetry/report fields only for fresh visible-color candidate analysis
  and target-trace reporting
- report dense-component metrics separately from coarse support-component
  metrics
- include a negative-control lane for homogeneous/common-color false positives
  like the top-right Red1 app screenshot region
- do not relax `ring`, `support_mass`, or `area` gates
- do not change candidate ranking or promotion behavior

Minimum fields unless `child_blob_interest_cohort_v1` recommends a narrower
set:

- seed uniqueness in current frame and recent-frame history
- dense component area/span/fill and small-target ratio
- dense component intra-color variance or max color distance from seed
- boundary contrast between dense component pixels and immediate outside ring
- current coarse support component id/area/ring/support-mass for comparison
- AR-locked persistence count and stabilized-location drift for the traced
  target and for the negative-control winner

Validation ownership:

- this child owns build and focused telemetry validation for its own changes
- no full regression/perf sweep is required unless telemetry changes alter
  detector behavior
- prove detector outputs are unchanged when telemetry is disabled or passive

Expected report format:

- attempted change
- whether code remains active or was reverted
- commands run
- sample telemetry rows for reviewed target and negative control
- proof that behavior did not change
- conclusion: adopt telemetry | reject telemetry | refine packet first
- artifact paths

### Future Child Packet: `child_blob_interest_growth_v1`

Child id: `child_blob_interest_growth_v1`
Role: `experiment`
Checkpoint: parent-approved checkpoint after `child_blob_interest_cohort_v1`
and, if needed, `child_blob_interest_telemetry_v1`
Hypothesis: replacing the fresh-mode candidate contract with dense
BlobOfInterest growth will reject common homogeneous regions and preserve the
reviewed Red1 target better than the current coarse-support late-gate flow.
Write scope:

- `app/src/main/cpp/anomaly_analysis.c`
- `app/src/main/cpp/anomaly_analysis.h`
- directly related native tests only

Non-goals:

- do not use broad compact-blob rescue as the main mechanism
- do not tune `ring`, `support_mass`, or `area` thresholds first
- do not redesign AR-lock / revisit; use it as persistence evidence after
  dense blob construction
- do not change FFmpeg / bridge / UI wiring

Required implementation shape:

- fresh-mode seed selection starts from scene/recent-frame unique pixels
- dense growth uses 8-neighbor color continuity around the seed
- dense component must remain below the small-target envelope
- component acceptance requires boundary contrast against surrounding pixels
- candidate telemetry must show dense metrics and coarse-support metrics as
  separate fields

Validation ownership:

- this mutating child owns the full required visible-color validation sweep
  before asking for adoption
- if validation shows a null result or regression, the child reverts its own
  change before reporting unless the parent explicitly preserves it

Adoption gates:

- reviewed Red1 target moves toward extraction or matched candidate success
- top-right homogeneous/common-color false-positive class fails uniqueness or
  boundary-contrast checks
- fresh tracing on/off parity still holds
- black-hot reviewed regression does not materially regress
- visible-color perf stays within the current envelope unless the parent
  explicitly accepts a correctness/perf tradeoff

Expected report format:

- attempted change
- whether code remains active or was reverted
- commands run
- pass/fail summary
- target-vs-negative-control metrics before and after
- conclusion: adopt | reject | needs narrower follow-up
- artifact paths

## Immediate Override For The Next Live Thread

Before reading the older parent-ledger sections below:

- the next live thread should **not** treat itself as a parent-only launch
  thread
- the next live thread should **mutate code directly in the current worktree**
  and run its own validation
- the current starting point is the **current tree state**, including the
  newer fresh winner telemetry, winner-gate work, and small-dominates scoring
  changes
- do **not** restart from the older `child2_supportshape_v2` launch packet as
  if the later `2026-05-11 Winner-Gate Probe` and
  `2026-05-11 Small-Dominates Follow-On Packet` sections do not exist

Current next-thread deliverable:

- continue the bounded fresh visible-color winner work from the current tree
- keep the architecture distinction sharp:
  - current code is still coarse-first with dense local verification
  - it is not yet the intended dense pixel-first fresh-region detector
- preserve the new telemetry
- preserve the new small-dominates scoring change
- test whether the hard fresh winner gate can be softened or partially relaxed
  now that smallness is weighted much earlier
- the concrete nuisance line to suppress is the duplicate false-positive blob
  around `x≈51%`, `y≈58–59%` late in `Red1`

## Launch-Ready Child Packet Queue

These packets supersede any vague "continue from here" instruction. They are
intended to let the next thread start from a single bounded hypothesis rather
than rediscovering the same architectural mismatch.

### Child Packet: `child_dense_arch_analysis_v1`

Child id: `child_dense_arch_analysis_v1`
Role: `analysis`
Checkpoint: current tree state after the `2026-05-11` clean summary
Hypothesis: the fresh visible-color path can be decomposed into one clean seam
where fresh / unlocked regions bypass support-peak-first construction and form
dense blobs directly, while AR-lock / revisit remains an optimization layer
afterward
Write scope: read-only analysis of `app/src/main/cpp/anomaly_analysis.c/h`
Non-goals:
- do not edit repo-tracked files
- do not tune thresholds
- do not run the full regression / perf sweep
Required commands:
- `cd /Users/kjt/Projects/RID2Caltopo`
- `sed -n '1,220p' docs/Visible_Color_AD_Clean_Summary_20260511.md`
- `sed -n '1,220p' docs/Visible_Color_AD_Thread_Operating_Model.md`
- `sed -n '2000,2275p' app/src/main/cpp/anomaly_analysis.c`
- `sed -n '3590,4065p' app/src/main/cpp/anomaly_analysis.c`
- `sed -n '11745,11830p' app/src/main/cpp/anomaly_analysis.c`
Validation ownership:
- read-only child; no mutation validation required
Adoption gate:
- produce a concrete implementation map with:
  - exact entry point for fresh dense blob construction
  - exact bypass point for the support-peak-first path
  - exact state/output that must be carried forward so ranking/debugging still
    works
  - exact point where AR-lock / revisit re-enters
Artifact suffix:
- `child_dense_arch_analysis_v1`
Expected report format:
- attempted analysis
- exact code seam to change
- exact code seam to leave alone for this step
- risks
- conclusion: launch `child_dense_growth_v1` as-is | refine packet first

### Child Packet: `child_dense_growth_v1`

Child id: `child_dense_growth_v1`
Role: `experiment`
Checkpoint: current tree state after the `2026-05-11` clean summary
Hypothesis: replacing fresh-mode coarse/support-peak-first blob construction
with dense fresh-region blob growth using 8-neighbor continuity will move the
implementation toward the intended architecture without immediately changing
uniqueness scoring
Write scope: `app/src/main/cpp/anomaly_analysis.c`,
`app/src/main/cpp/anomaly_analysis.h`, and directly related native tests only
Non-goals:
- do not redesign AR-lock / revisit
- do not change winner-gate policy first
- do not change blob uniqueness scoring in this child
- do not broaden large-blob rescue behavior
Required commands:
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test`
- `cmake -B build`
- `cmake --build build`
- `./build/anomaly_test`
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/build`
- `ctest --output-on-failure`
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test`
- `cmake -B build_timing -DANOMALY_DEBUG_TIMING=ON`
- `cmake --build build_timing`
- `./build_timing/anomaly_test`
- `cd /Users/kjt/Projects/RID2Caltopo`
- `./gradlew :app:compileDebugKotlin`
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend legacy --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_legacy_summary_child_dense_growth_v1.json --color-debug-jsonl /tmp/red1_app_legacy_color_debug_child_dense_growth_v1.jsonl --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv -c /tmp/red1_app_legacy_detections_child_dense_growth_v1.csv`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_fresh_summary_child_dense_growth_v1.json --color-debug-jsonl /tmp/red1_app_fresh_color_debug_child_dense_growth_v1.jsonl --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv -c /tmp/red1_app_fresh_detections_child_dense_growth_v1.csv`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_fresh_notrace_summary_child_dense_growth_v1.json -c /tmp/red1_app_fresh_notrace_detections_child_dense_growth_v1.csv`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_fresh_trace_summary_child_dense_growth_v1.json --color-debug-jsonl /tmp/red1_app_fresh_trace_color_debug_child_dense_growth_v1.jsonl --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv -c /tmp/red1_app_fresh_trace_detections_child_dense_growth_v1.csv`
- `shasum -a 256 /tmp/red1_app_fresh_notrace_detections_child_dense_growth_v1.csv`
- `shasum -a 256 /tmp/red1_app_fresh_trace_detections_child_dense_growth_v1.csv`
- `cd /Users/kjt/Projects/RID2Caltopo`
- `python3 tools/anomaly_test/run_regression_suite.py --manifest tools/anomaly_test/regression_suite_manifest.json --binary tools/anomaly_test/build_timing/anomaly_video_test --out-dir /private/tmp/regression_main_child_dense_growth_v1 --report-json /private/tmp/regression_main_child_dense_growth_v1/suite_report.json --report-md /private/tmp/regression_main_child_dense_growth_v1/suite_report.md`
- `python3 tools/anomaly_test/run_regression_suite.py --manifest tools/anomaly_test/regression_suite_color_manifest.json --binary tools/anomaly_test/build_timing/anomaly_video_test --out-dir /private/tmp/regression_color_child_dense_growth_v1 --report-json /private/tmp/regression_color_child_dense_growth_v1/suite_report.json --report-md /private/tmp/regression_color_child_dense_growth_v1/suite_report.md`
- `python3 tools/anomaly_test/run_visible_color_perf_benchmarks.py --binary tools/anomaly_test/build_timing/anomaly_video_test --output /private/tmp/visible_color_perf_child_dense_growth_v1`
Validation ownership:
- this child owns the full validation sweep for its own mutation
- if validation shows a null result or regression, revert its own change before
  reporting unless explicitly instructed otherwise
Adoption gate:
- implementation truly switches the fresh path away from coarse-peak-first blob
  construction
- reviewed Red1 shows a correctness win or at minimum a meaningful move toward
  extraction without broadening the large nuisance blob lane
- black-hot reviewed regression and visible-color perf stay within the current
  envelope unless a clear correctness win justifies review
Artifact suffix:
- `child_dense_growth_v1`
Expected report format:
- attempted change
- whether code remains active or was reverted
- commands run
- pass/fail summary
- headline metrics before vs after
- conclusion: adopt | reject | needs narrower follow-up
- artifact paths

### Child Packet: `child_peak_uniqueness_v1`

Child id: `child_peak_uniqueness_v1`
Role: `experiment`
Checkpoint: current tree state after the `2026-05-11` clean summary
Hypothesis: once a compact dense blob is accepted, its uniqueness / rarity
signal should come from the most unique pixel inside that blob rather than the
blob's averaged isolation metrics, and that narrower scoring change can be
tested independently from dense growth
Write scope: `app/src/main/cpp/anomaly_analysis.c`,
`app/src/main/cpp/anomaly_analysis.h`, and directly related native tests only
Non-goals:
- do not replace the fresh blob-construction seam in this child
- do not relax large-blob gates
- do not tune global rarity thresholds first
- do not change AR-lock / revisit policy
Required commands:
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test`
- `cmake -B build`
- `cmake --build build`
- `./build/anomaly_test`
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/build`
- `ctest --output-on-failure`
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test`
- `cmake -B build_timing -DANOMALY_DEBUG_TIMING=ON`
- `cmake --build build_timing`
- `./build_timing/anomaly_test`
- `cd /Users/kjt/Projects/RID2Caltopo`
- `./gradlew :app:compileDebugKotlin`
- `cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend legacy --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_legacy_summary_child_peak_uniqueness_v1.json --color-debug-jsonl /tmp/red1_app_legacy_color_debug_child_peak_uniqueness_v1.jsonl --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv -c /tmp/red1_app_legacy_detections_child_peak_uniqueness_v1.csv`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_fresh_summary_child_peak_uniqueness_v1.json --color-debug-jsonl /tmp/red1_app_fresh_color_debug_child_peak_uniqueness_v1.jsonl --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv -c /tmp/red1_app_fresh_detections_child_peak_uniqueness_v1.csv`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_fresh_notrace_summary_child_peak_uniqueness_v1.json -c /tmp/red1_app_fresh_notrace_detections_child_peak_uniqueness_v1.csv`
- `./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_fresh_trace_summary_child_peak_uniqueness_v1.json --color-debug-jsonl /tmp/red1_app_fresh_trace_color_debug_child_peak_uniqueness_v1.jsonl --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv -c /tmp/red1_app_fresh_trace_detections_child_peak_uniqueness_v1.csv`
- `shasum -a 256 /tmp/red1_app_fresh_notrace_detections_child_peak_uniqueness_v1.csv`
- `shasum -a 256 /tmp/red1_app_fresh_trace_detections_child_peak_uniqueness_v1.csv`
- `cd /Users/kjt/Projects/RID2Caltopo`
- `python3 tools/anomaly_test/run_regression_suite.py --manifest tools/anomaly_test/regression_suite_manifest.json --binary tools/anomaly_test/build_timing/anomaly_video_test --out-dir /private/tmp/regression_main_child_peak_uniqueness_v1 --report-json /private/tmp/regression_main_child_peak_uniqueness_v1/suite_report.json --report-md /private/tmp/regression_main_child_peak_uniqueness_v1/suite_report.md`
- `python3 tools/anomaly_test/run_regression_suite.py --manifest tools/anomaly_test/regression_suite_color_manifest.json --binary tools/anomaly_test/build_timing/anomaly_video_test --out-dir /private/tmp/regression_color_child_peak_uniqueness_v1 --report-json /private/tmp/regression_color_child_peak_uniqueness_v1/suite_report.json --report-md /private/tmp/regression_color_child_peak_uniqueness_v1/suite_report.md`
- `python3 tools/anomaly_test/run_visible_color_perf_benchmarks.py --binary tools/anomaly_test/build_timing/anomaly_video_test --output /private/tmp/visible_color_perf_child_peak_uniqueness_v1`
Validation ownership:
- this child owns the full validation sweep for its own mutation
- if validation shows a null result or regression, revert its own change before
  reporting unless explicitly instructed otherwise
Adoption gate:
- accepted dense blobs now expose and use peak-pixel rarity / uniqueness rather
  than blob-averaged isolation as the primary uniqueness signal
- reviewed Red1 correctness improves without re-admitting the oversized
  nuisance blob lane
- no material regression in reviewed black-hot behavior or visible-color perf
Artifact suffix:
- `child_peak_uniqueness_v1`
Expected report format:
- attempted change
- whether code remains active or was reverted
- commands run
- pass/fail summary
- headline metrics before vs after
- conclusion: adopt | reject | needs narrower follow-up
- artifact paths

### Child Packet: `child_dense_validation_v1`

Child id: `child_dense_validation_v1`
Role: `validation`
Checkpoint: whichever candidate checkpoint the parent thread explicitly names
after reviewing the experiment reports
Hypothesis: the nominated dense-fresh candidate is a real win rather than a
telemetry artifact
Write scope: read-only unless the validation packet explicitly instructs the
child to revert a failed candidate tree
Non-goals:
- no redesign
- no new threshold tuning
- no opportunistic cleanup
Required commands:
- run the same full validation command set listed above using the parent-named
  checkpoint suffix for artifacts
Validation ownership:
- full rerun only; no redesign
Adoption gate:
- confirm the candidate's reported replay, regression, parity, and perf claims
Artifact suffix:
- `child_dense_validation_v1`
Expected report format:
- commands run
- pass/fail summary
- metric deltas versus parent-approved baseline
- conclusion: confirm | reject | inconclusive
- artifact paths

If there is any conflict between this section and older parent-ledger launch
instructions below, this section wins.

## Parent Steering Ledger

This document is the canonical parent-thread ledger for visible-color AD.
Future parent threads should update this section first, then append their
thread-specific narrative below.

Operating model:

- parent-thread rules and child-thread packet/report templates live in
  [Visible_Color_AD_Thread_Operating_Model.md](/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Thread_Operating_Model.md)
- treat this file as the durable decision ledger, not just a chronological
  narrative
- do not let child threads redefine the approved checkpoint on their own
- do not let the parent thread perform post-change regression/perf validation
  on behalf of a mutating child; that validation burden belongs to the child
- do not let the parent thread become the child in the same live thread;
  child execution must happen in a separate new thread or explicit delegated
  child-agent run

Current approved checkpoint:

- checkpoint id: `child2_supportshape_v2`
- status: approved working checkpoint for the next bounded child experiments
- reason:
  - it is the newest checkpoint that changed the dominant reviewed Red1
    blocker in an architecturally useful direction
  - it kept the fresh-only support-map shaping pass active
  - it preserved `legacy` behavior and the reviewed black-hot regression suite
  - parent review rejected adoption of `child3_ringthin_v1`, so checkpoint
    truth remains pinned here

Current top-priority next-thread directive:

- this overrides the older `child4_ringshift_analysis_v1` read-only analysis
  suggestion below
- do not start the next thread by repeating child3/child4 parent-ledger
  analysis
- start from the current dense-persist code state and focus on fresh visible-
  color coarse blob fan-out
- the next thread should implement, in order:
  1. coarse-component telemetry as a first-class output for fresh visible-
     color replay
  2. an early hard resource gate for coarse components already larger than the
     small-scale target envelope
  3. a bounded adaptive fresh-mode contrast / distinctness ratio that tightens
     when too many coarse blobs are found and relaxes when too few are found
- target outcome:
  - only a small handful of coarse blobs per full scan, ideally about `4-8`
- preserve:
  - `legacy`
  - persistence / revisit logic
  - FFmpeg / bridge / UI wiring

Why this is now the top priority:

- the latest perf investigation showed that `fresh-rgba` is not just slightly
  noisier than `legacy`; it explodes into far more coarse connected support
  regions before dense verification
- on the same reviewed `Red1` replay:
  - `legacy` averaged `19.08` coarse blobs examined per frame
  - `fresh-rgba` averaged `1157.99` coarse blobs examined per frame
- the user explicitly asked that we:
  - stop wasting work on blobs larger than the small-scale form factor
  - adapt the fresh visible-color contrast ratio dynamically based on how many
    blobs are being found

Exact baseline artifact set for comparison:

- reviewed Red1 app-parity:
  - `/tmp/red1_app_legacy_summary_child2_supportshape_v2.json`
  - `/tmp/red1_app_legacy_color_debug_child2_supportshape_v2.jsonl`
  - `/tmp/red1_app_fresh_summary_child2_supportshape_v2.json`
  - `/tmp/red1_app_fresh_color_debug_child2_supportshape_v2.jsonl`
  - `/tmp/red1_app_fresh_detections_child2_supportshape_v2.csv`
  - `/tmp/red1_app_fresh_notrace_detections_child2_supportshape_v2.csv`
- reviewed regression suites:
  - `/private/tmp/regression_main_child2_supportshape_v2/suite_report.json`
  - `/private/tmp/regression_main_child2_supportshape_v2/suite_report.md`
  - `/private/tmp/regression_color_child2_supportshape_v2/suite_report.json`
  - `/private/tmp/regression_color_child2_supportshape_v2/suite_report.md`
- visible-color perf:
  - `/private/tmp/visible_color_perf_child2_supportshape_v2/visible_color_perf_report.json`

Current success gates for adoption:

- reviewed Red1 must improve meaningfully versus `child2_supportshape_v2`
- at minimum, a candidate checkpoint must do one of these:
  - increase target extraction above `0`
  - reduce the reviewed fresh target miss burden while preserving the new
    compact-blob regime
  - move target failures from `support_map_rejected` / `ring` /
    `support_mass` toward matched-candidate or winning-box success
- black-hot reviewed regression must not regress materially:
  - `current-detector-baseline`: stay at `TP 22 / 279`, `FP 0 / 92` or better
  - `dense-full-scan-gold`: stay at `TP 37 / 279`, `FP 0 / 92` or better
  - `redesigned-incremental`: stay at `TP 23 / 279`, `FP 2 / 92` or better
- fresh tracing on/off parity must still hold
- performance must stay within the current envelope unless a correctness win
  clearly justifies the cost and the parent thread explicitly adopts it

Interpretation discipline for future threads:

- reviewed Red1 correctness is the primary adoption gate for visible-color work
- the current visible-color manifest is not yet a trusted correctness gate in
  the same sense as the black-hot reviewed suite
- use current visible-color manifest results as advisory signal only:
  - broadening warnings
  - workload / perf warnings
  - change-detection signal between branches
- do not reject a visible-color branch solely because the current color
  manifest broadened unless the parent thread explicitly promotes that lane to
  a hard gate
- when a branch has no reviewed Red1 correctness win, advisory visible-color
  broadening/perf signals may still be enough to say “not ready to adopt yet,”
  but label that reasoning explicitly as advisory rather than hard-gate
  failure

Active child assignments:

- active parent-assigned packet:
  - none; `child_dense_validation_v2` confirmed the active candidate
- queued conditional follow-ups:
  - app rerun/parity check: rebuild/install app and confirm Android log shows
    `colorFrontend=1` for Red1 color playback before judging UI detections
  - `child_dense_perf_recovery_v1`: next recommended lane after app parity was
    confirmed; preserve Red1 app detections while reducing app-like visible-color
    cost from about `10 fps` toward the previous realtime envelope
  - `child_dense_offtarget_suppress_v1`: next recommended mutating child;
    preserve the confirmed target hits at frames `26-28`, suppress the remaining
    off-target run at frames `74-76`, and avoid increasing fanout or dense checks
  - `child_blob_interest_telemetry_v1`: telemetry/reporting only if the
    active experiment proves current artifacts insufficient
  - `child_blob_interest_growth_v1`: mutating dense BlobOfInterest experiment
    packet retained as an older fallback name; prefer
    `child_dense_blob_interest_v1` for the active line
- most recent mutating child:
  - `candidate checkpoint`: active seed-filter + target-promotion tree confirmed
    by `child_dense_validation_v2`; use this as the current candidate checkpoint
    for the next child, not as a final adoption until the parent reviews the
    remaining off-target/perf tradeoff
  - `child_dense_target_promotion_v1`: completed on `2026-05-12`; code left
    active for parent review; target winners improved `0 -> 2`, Red1 boxes
    stayed `6`, candidate entries stayed `512`, main hard gates passed, and
    visible-color baseline improved to `9 TP / 2 FP`; perf slowed enough that
    independent validation is recommended before adoption
  - `child_dense_blob_seedfilter_v1`: completed on `2026-05-12`; candidate code
    left active for parent review; retained `7` target extractions and reduced
    tight-proof fanout, but published Red1 boxes stayed off-target, so result
    is `needs narrower follow-up`, not adoption
  - `child_dense_blob_interest_v1`: completed on `2026-05-12`; reverted by
    child; tight dense continuity extracted `8` reviewed target frames but
    increased boxes to `11`, candidate entries to `606`, and Red1 wall time to
    `15.98s`, so reject current diff and pursue narrower seed/fanout control
- most recent read-only children:
  - `child_dense_arch_map_v2`: completed on `2026-05-12`; recommended launching
    the implementation-first dense BlobOfInterest experiment
  - `child_blob_interest_cohort_v1`: completed on `2026-05-12` but did not
    produce the requested cohort metrics; no adoption decision depends on it
- most recent completed child:
  - `child3_ringthin_v1`: reviewed by parent and rejected for adoption on
    `2026-05-11`; checkpoint remains `child2_supportshape_v2`
- parent thread should launch at most:
  - `2` experiment children
  - `1` validation child
  - `3` active children total
- if the current live thread is acting as parent, requests such as `continue`
  or `2. please` must be answered by emitting or refining the child launch
  wrapper only, not by starting the experiment in-place

Superseded queue note:

- the older `child4_ringshift_analysis_v1` suggestion remains below as
  historical context only
- it is no longer the recommended starting point for the next thread
- the next thread should follow the top-priority directive above instead

Ranked next experiment queue:

1. implement fresh visible-color coarse-component telemetry and confirm the
   per-frame fan-out directly in the active replay path
2. add an early oversized-component hard gate before expensive downstream work
3. add a bounded adaptive fresh contrast / distinctness ratio driven by coarse
   blob count on full scans
4. only after that, revisit whether any older child3/child4 artifact analysis
   is still needed

Latest parent analysis:

- status: completed parent review of `child3_ringthin_v1` against the
  approved checkpoint using:
  - `/tmp/red1_app_fresh_color_debug_child2_supportshape_v2.jsonl`
  - `/tmp/red1_app_fresh_color_debug_child3_ringthin_v1.jsonl`
  - `/private/tmp/regression_main_child3_ringthin_v1/suite_report.json`
  - `/private/tmp/regression_color_child3_ringthin_v1/suite_report.json`
  - `/private/tmp/visible_color_perf_child3_ringthin_v1/visible_color_perf_report.json`
- reviewed Red1 moved partly in the intended direction, but not enough for
  adoption:
  - `fresh-rgba` headline boxes changed from `125` frames / `161` total boxes
    to `131` frames / `165` total boxes
  - reviewed fresh target-stage counts changed from:
    - `none = 92`, `no_candidate = 38`, `rarity_rejected = 13`,
      `support_map_rejected = 10`
    to:
    - `none = 92`, `no_candidate = 35`, `rarity_rejected = 13`,
      `support_map_rejected = 13`
  - reviewed fresh target extraction remained `0`
- within the reviewed fresh `no_candidate` frames, the failure mix shifted:
  - `ring`: `26 -> 15`
  - `support_mass`: `8 -> 13`
  - `area`: `4 -> 3`
  - other / no explicit component rejection reason: `0 -> 4`
- tracing parity still held for the child artifact set:
  - `/tmp/red1_app_fresh_notrace_detections_child3_ringthin_v1.csv`
  - `/tmp/red1_app_fresh_trace_detections_child3_ringthin_v1.csv`
  - parent comparison found them byte-identical
- reviewed black-hot and visible-color regression gates stayed unchanged:
  - `current-detector-baseline`: `TP 22 / 279`, `FP 0 / 92`
  - `dense-full-scan-gold`: `TP 37 / 279`, `FP 0 / 92`
  - `redesigned-incremental`: `TP 23 / 279`, `FP 2 / 92`
  - `visible-color-baseline`: `TP 0 / 61`, `FP 0 / 0`
  - `visible-color-dense-gold`: `TP 0 / 61`, `FP 0 / 0`
- visible-color perf regressed materially:
  - `visible-color-app-like-auto`: `52.26 ms -> 79.15 ms` average total and
    `27.05 ms -> 40.27 ms` average color scoring
  - `visible-color-dense-gold`: `975.91 ms -> 1071.24 ms` average total and
    `660.97 ms -> 724.80 ms` average color scoring
- parent interpretation:
  - the ring-thinning hypothesis did relieve part of the local ring burden
  - but it mostly redistributed failure into `support_map_rejected`,
    `support_mass`, and a small new no-component bucket instead of producing
    matched-candidate or winning-box success
  - the perf regression is too large for adoption without a clear correctness
    win
  - the next step should narrow why the ring relief translated into more seed
    and support-mass failures before launching another mutating child

Known null results that should not be repeated without a materially new
rationale:

- late oversized-component subdivision fallback in
  `extract_color_blob_candidates(...)`
- fresh-only weak-bridge continuity join gate
- ranking-only tweaks as the first next step
- broad late join tightening without earlier support-field shaping
- chasing rarity or local-support gating first on the current checkpoint
  without first addressing the local-ring pressure around the compact target
  blob
- the broad `child3_ringthin_v1` fresh-only ring-thinning variant, which
  reduced ring rejects but still produced `0` reviewed target extraction,
  increased `support_map_rejected` and `support_mass` failures, and regressed
  visible-color perf materially

Child artifact and naming policy:

- every child must use a unique suffix such as `child3_ringthin_v1`
- never overwrite the approved-checkpoint artifacts listed above
- any child that changes code must run the full required validation command
  set after its own change before reporting
- parent threads must never do worktree prep, code mutation, or validation on
  behalf of that child inside the same live thread
- child results are provisional until the parent thread records:
  - adopt
  - reject
  - needs narrower follow-up

## Parent Review From 2026-05-11 On child3_ringthin_v1

Parent decision:

- reject for adoption
- keep `child2_supportshape_v2` as the approved checkpoint

Why this was rejected:

- the child moved one intended metric:
  - reviewed fresh `ring` rejects within `no_candidate` dropped from `26`
    to `15`
- but it did not meet the adoption gate:
  - reviewed fresh target extraction stayed `0`
  - `support_map_rejected` increased from `10` to `13`
  - `support_mass` rejects increased from `8` to `13`
  - visible-color perf regressed materially
- it did preserve the non-correctness guardrails:
  - `legacy` reviewed Red1 headline behavior stayed at `152` frames with
    boxes and `188` total box events
  - reviewed black-hot and visible-color regression gates stayed unchanged
  - fresh trace / no-trace detections were byte-identical

Parent follow-up:

- do not adopt `child3_ringthin_v1`
- older parent guidance here suggested a read-only analysis child next
- that suggestion is now superseded by the later perf investigation update
  appended at the end of this file and summarized near the top:
  - do not start the next thread with child3/child4 analysis
  - start with coarse blob fan-out telemetry, an oversized-component resource
    gate, and adaptive fresh contrast / distinctness control instead

Suggested next child id:

- historical only: `child4_ringshift_analysis_v1`
- current recommended next mutating child should use a new id focused on:
  - coarse fan-out telemetry
  - oversized-component gating
  - adaptive fresh contrast control

Prepared next child assignment packet:

```md
Child id: child4_ringshift_analysis_v1
Role: analysis
Checkpoint: child2_supportshape_v2
Hypothesis: The rejected `child3_ringthin_v1` variant redistributed reviewed fresh failures from `ring` into `support_map_rejected`, `support_mass`, and a small new no-component bucket, and a read-only cohort diff across the existing artifacts can isolate the exact target-frame subsets and perf signals needed to constrain the next narrower mutating child.
Write scope: read-only artifact and code inspection only; no repo edits
Non-goals:
- do not change repo-tracked files
- do not rerun builds, regression, replay, or perf benchmarks
- do not propose support-mass, area, or ranking loosening without first tying it to specific shifted target-frame cohorts
- do not broaden into non-Red1 detector redesign
Required commands:
- cd /Users/kjt/Projects/RID2Caltopo
- python3 - <<'PY'
import json, pathlib, collections, statistics
base = pathlib.Path('/tmp/red1_app_fresh_color_debug_child2_supportshape_v2.jsonl')
trial = pathlib.Path('/tmp/red1_app_fresh_color_debug_child3_ringthin_v1.jsonl')
out = pathlib.Path('/tmp/child4_ringshift_analysis_v1_target_cohorts.md')
def load(path):
    rows = []
    for line in path.read_text().splitlines():
        obj = json.loads(line)
        t = obj['target']
        rows.append({
            'frame': obj['frame'],
            'time_s': obj['time_s'],
            'stage': t['stage'],
            'reason': t['component_rejection_reason'],
            'support_score': t['support_score'],
            'pre_support_score': t['pre_support_score'],
            'coherent_patch_cell_count': t['coherent_patch_cell_count'],
            'component_area': t['component_area'],
            'component_ring_fraction': t['component_ring_fraction'],
            'component_support_mass': t['component_support_mass'],
            'support_seed_eligible': t['support_seed_eligible'],
        })
    return {row['frame']: row for row in rows}
b = load(base)
t = load(trial)
transitions = collections.Counter()
reason_shifts = collections.Counter()
support_map_up = []
reason0 = []
for frame in sorted(b):
    rb = b[frame]
    rt = t[frame]
    transitions[(rb['stage'], rt['stage'])] += 1
    if rb['stage'] == 'no_candidate' and rt['stage'] == 'no_candidate':
        reason_shifts[(rb['reason'], rt['reason'])] += 1
    if rt['stage'] == 'support_map_rejected' and rb['stage'] != 'support_map_rejected':
        support_map_up.append((frame, rb, rt))
    if rt['stage'] == 'no_candidate' and rt['reason'] == 0:
        reason0.append((frame, rb, rt))
lines = []
lines.append('# child4_ringshift_analysis_v1 target cohort diff')
lines.append('')
lines.append('## Stage transitions')
for k, v in sorted(transitions.items()):
    lines.append(f'- {k[0]} -> {k[1]}: {v}')
lines.append('')
lines.append('## No-candidate reason shifts')
for k, v in sorted(reason_shifts.items()):
    lines.append(f'- reason {k[0]} -> {k[1]}: {v}')
lines.append('')
lines.append('## Newly support_map_rejected frames')
for frame, rb, rt in support_map_up:
    lines.append(f"- frame {frame}: {rb['stage']} -> {rt['stage']}, pre_support {rb['pre_support_score']:.3f} -> {rt['pre_support_score']:.3f}, support {rb['support_score']:.3f} -> {rt['support_score']:.3f}, coherent_cells {rb['coherent_patch_cell_count']} -> {rt['coherent_patch_cell_count']}")
lines.append('')
lines.append('## Reason-0 no-candidate frames in child3')
for frame, rb, rt in reason0:
    lines.append(f"- frame {frame}: child2 stage={rb['stage']} reason={rb['reason']}, child3 stage={rt['stage']} reason={rt['reason']}, area={rt['component_area']}, ring_fraction={rt['component_ring_fraction']:.3f}, support_mass={rt['component_support_mass']:.3f}")
out.write_text('\\n'.join(lines) + '\\n')
print(out)
PY
- python3 - <<'PY'
import json, pathlib
perf2 = json.loads(pathlib.Path('/private/tmp/visible_color_perf_child2_supportshape_v2/visible_color_perf_report.json').read_text())
perf3 = json.loads(pathlib.Path('/private/tmp/visible_color_perf_child3_ringthin_v1/visible_color_perf_report.json').read_text())
out = pathlib.Path('/tmp/child4_ringshift_analysis_v1_perf_diff.md')
def table(obj):
    rows = {}
    for prof in obj['profiles']:
        agg = prof['aggregates']
        rows[prof['id']] = {
            'avg_realtime_factor': agg['avg_realtime_factor'],
            'avg_total_ms': agg['avg_total_ms'],
            'avg_color_scoring_ms': agg['avg_color_scoring_ms'],
            'avg_sampled_grid_prep_ms': agg['avg_sampled_grid_prep_ms'],
            'avg_refresh_mask_build_ms': agg['avg_refresh_mask_build_ms'],
        }
    return rows
a = table(perf2)
b = table(perf3)
lines = ['# child4_ringshift_analysis_v1 perf diff', '', '## Aggregate deltas']
for key in sorted(a):
    lines.append(f"- {key}: realtime {a[key]['avg_realtime_factor']:.6f} -> {b[key]['avg_realtime_factor']:.6f}, total_ms {a[key]['avg_total_ms']:.2f} -> {b[key]['avg_total_ms']:.2f}, color_ms {a[key]['avg_color_scoring_ms']:.2f} -> {b[key]['avg_color_scoring_ms']:.2f}, sampled_grid_prep_ms {a[key]['avg_sampled_grid_prep_ms']:.2f} -> {b[key]['avg_sampled_grid_prep_ms']:.2f}, refresh_mask_build_ms {a[key]['avg_refresh_mask_build_ms']:.2f} -> {b[key]['avg_refresh_mask_build_ms']:.2f}")
out.write_text('\\n'.join(lines) + '\\n')
print(out)
PY
- rg -n "build_color_support_map|support_seed_count|support_peak_score|ring_fraction|support_mass|component_rejection_reason" /Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c /Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h
Validation ownership:
- this child is read-only, so it does not own a post-change validation sweep
- it must not mutate code or substitute a speculative experiment for analysis
Adoption gate:
- answer, with artifact-backed detail, all of these before another mutating child is launched:
  - which reviewed target frames shifted from ring rejection into support-mass or support-map rejection
  - whether the `support_map_rejected` increase came from frames that gained support then fell below seed threshold, or from a different cohort
  - what explains the `4` reason-`0` no-candidate frames
  - which perf buckets grew enough to explain the visible-color slowdown
Artifact suffix:
- child4_ringshift_analysis_v1
Expected report format:
- attempted analysis
- commands run
- frame-cohort summary
- answer to the narrowing question
- recommended next child shape: narrower ring relief | support-mass follow-up | stop
- artifact paths
```

Launch-ready analysis child wrapper:

```md
Read these first:
- /Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Next_Thread_Handoff.md
- /Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Thread_Operating_Model.md

You are child4_ringshift_analysis_v1. Role: analysis child.
Start from approved checkpoint child2_supportshape_v2.
You are read-only. Do not edit repo-tracked files, do not rerun validation, and
do not turn this into a mutating experiment.
Answer one narrowing question only: why did the rejected child3 ring-thinning
variant reduce ring rejects but increase support-map and support-mass failures,
introduce 4 reason-0 no-candidate frames, and regress visible-color perf?
Use the existing child2 and child3 Red1 debug artifacts, regression reports,
and perf reports named in the handoff.
Return the analysis report in the requested format, with artifact-backed
recommendation for the next mutating child shape.
```

## Update From 2026-05-11 Codex Child 2

This child thread tried one bounded fresh-mode-only support-map shaping
experiment earlier than blob subdivision or late join fallback. The goal was
to stop the reviewed Red1 target cells from entering one giant merged
area-rejected support field before extraction.

What was tried:

- changed `build_color_support_map(...)` in
  `app/src/main/cpp/anomaly_analysis.c`
- kept `legacy` support-map behavior intact
- for `fresh-rgba` only, replaced the prior broad patch aggregation with a
  more target-local score that:
  - boosts cells when a compact local peak stands above the immediate ring
  - penalizes broad uniform plateaus whose ring mean stays close to the local
    peak
- the intent was to reshape the support field before blob extraction, not to
  add another oversized-component salvage path

What changed in reviewed Red1:

- the dominant fresh failure mode moved away from giant area-rejected blobs
- the traced target-containing component collapsed from the prior
  `~1500-2200` sampled-cell plateau down to compact blobs:
  - mean traced target component area: about `7.50` sampled cells
  - max traced target component area: `27`
  - min traced target component area: `1`
- fresh reviewed target-stage counts became:
  - `none = 92`
  - `no_candidate = 38`
  - `rarity_rejected = 13`
  - `support_map_rejected = 10`
- fresh reviewed target extraction still stayed `0`
- fresh reviewed target rejection mix on the compact traced blobs became:
  - `ring = 28`
  - `support_mass = 8`
  - `area = 4`

Interpretation:

- the support-map shaping did the intended early architectural thing:
  - it stopped the target from living inside the old oversized area-rejected
    plateau
- but it did not produce a reviewed Red1 correctness win yet
- instead, the new blocker is smaller compact blobs that still fail later
  ring / support-mass admission checks or, in ten frames, fall below the
  support-map seed gate entirely

What was kept in the final tree:

- the fresh-only support-map shaping experiment remains active
- `legacy` reviewed Red1 behavior returned to the known checkpoint after a
  follow-up fix restored its original ring normalization

What was verified:

- `cmake --build build` in `tools/anomaly_test`: passed
- `cmake --build build_timing` in `tools/anomaly_test`: passed
- `./build/anomaly_test`: passed with `67 passed, 0 failed`
- `ctest --output-on-failure` in `tools/anomaly_test/build`: passed
- `./build_timing/anomaly_test`: passed with `67 passed, 0 failed`
- `./gradlew :app:compileDebugKotlin`: `BUILD SUCCESSFUL`
- reviewed Red1 app-parity re-runs completed with:
  - `/tmp/red1_app_legacy_summary_child2_supportshape_v2.json`
  - `/tmp/red1_app_legacy_color_debug_child2_supportshape_v2.jsonl`
  - `/tmp/red1_app_fresh_summary_child2_supportshape_v2.json`
  - `/tmp/red1_app_fresh_color_debug_child2_supportshape_v2.jsonl`
- reviewed Red1 fresh tracing parity check passed again:
  - `/tmp/red1_app_fresh_detections_child2_supportshape_v2.csv`
  - `/tmp/red1_app_fresh_notrace_detections_child2_supportshape_v2.csv`
  - both SHA-256 values matched
    `0f62b0da720834a9133793edcd8c6b193b74b13b1ad643460c9b6ff0e6da20ce`
- reviewed black-hot regression suite re-run completed with:
  - `/private/tmp/regression_main_child2_supportshape_v2/suite_report.json`
  - `/private/tmp/regression_main_child2_supportshape_v2/suite_report.md`
- visible-color regression suite re-run completed with:
  - `/private/tmp/regression_color_child2_supportshape_v2/suite_report.json`
  - `/private/tmp/regression_color_child2_supportshape_v2/suite_report.md`
- visible-color performance benchmark re-run completed with:
  - `/private/tmp/visible_color_perf_child2_supportshape_v2/visible_color_perf_report.json`

Current checkpoint metrics:

- reviewed Red1 app-parity headline behavior:
  - `legacy`: `152` frames with boxes, `188` total box events
  - `fresh-rgba`: `125` frames with boxes, `161` total box events
- reviewed visible-color regression remained:
  - `visible-color-baseline`: `TP 0 / 61`, `FP 0 / 0`
  - `visible-color-dense-gold`: `TP 0 / 61`, `FP 0 / 0`
- reviewed black-hot regression remained unchanged:
  - `current-detector-baseline`: `TP 22 / 279`, `FP 0 / 92`
  - `dense-full-scan-gold`: `TP 37 / 279`, `FP 0 / 92`
  - `redesigned-incremental`: `TP 23 / 279`, `FP 2 / 92`
- visible-color perf now sits at:
  - `visible-color-app-like-auto`: about `0.60x` realtime,
    `52.26 ms` average total, `27.05 ms` average color scoring
  - `visible-color-dense-gold`: about `0.03x` realtime,
    `975.91 ms` average total, `660.97 ms` average color scoring

Recommendation:

- do not go back to late oversized-component subdivision first
- the support-map shaping proved that the earlier seam is the right one
- start from this checkpoint and inspect the new compact target-blob rejects
  instead:
  - immediate ring thinning around the compact target blob
  - support-mass consequences of leaving too much surrounding support alive
  - whether a narrow compact-blob exception is justified once the blob is
    already target-sized and legacy remains unchanged
- keep comparing against this child’s artifacts, because the dominant blocker
  is no longer the old giant area-rejected component

## Update From 2026-05-11 Codex Child 1

This child thread tried one bounded fresh-mode-only component-formation
experiment on the recommended `extract_color_blob_candidates(...)` seam, then
reverted it after the full validation sweep showed no reviewed Red1
correctness gain and a meaningful dense visible-color cost increase.

What was tried:

- added a fresh-only weak-bridge continuity gate during neighbor joins in
  `app/src/main/cpp/anomaly_analysis.c`
- the idea was to require late, low-confidence joins to show extra local
  coherent support before they could keep extending a blob beyond the compact
  target envelope
- the experiment intentionally did not change `legacy` behavior or the public
  analyzer interface

What the experiment showed before revert:

- reviewed Red1 app-parity fresh mode stayed unchanged at:
  - `135` frames with boxes
  - `171` total box events
  - fresh target stages still `none = 92`, `no_candidate = 48`,
    `rarity_rejected = 13`
  - target extraction stayed `0`
- reviewed visible-color regression stayed unchanged on correctness:
  - `visible-color-baseline`: `TP 0 / 61`, `FP 0 / 0`
  - `visible-color-dense-gold`: `TP 0 / 61`, `FP 0 / 0`
- dense visible-color cost regressed materially while the experiment was
  enabled:
  - reviewed dense Red1 moved to about `1394.54 ms` average total and
    `856.13 ms` average color scoring
  - perf-bench dense aggregate moved to about `1077.39 ms` average total and
    `737.77 ms` average color scoring

What was kept in the final tree:

- the weak-bridge continuity gate was removed
- no detector behavior changes from this child remain active in the final tree
- the workspace is back on the prior safer checkpoint after a full re-run

What was re-verified after revert:

- `cmake --build build` in `tools/anomaly_test`: passed
- `cmake --build build_timing` in `tools/anomaly_test`: passed
- `./build/anomaly_test`: passed with `67 passed, 0 failed`
- `ctest --output-on-failure` in `tools/anomaly_test/build`: passed
- `./build_timing/anomaly_test`: passed with `67 passed, 0 failed`
- `./gradlew :app:compileDebugKotlin`: `BUILD SUCCESSFUL`
- reviewed Red1 app-parity re-runs completed with:
  - `/tmp/red1_app_legacy_summary_child1_revert.json`
  - `/tmp/red1_app_legacy_color_debug_child1_revert.jsonl`
  - `/tmp/red1_app_fresh_summary_child1_revert.json`
  - `/tmp/red1_app_fresh_color_debug_child1_revert.jsonl`
- reviewed Red1 fresh tracing parity check passed again:
  - `/tmp/red1_app_fresh_detections_child1_revert.csv`
  - `/tmp/red1_app_fresh_notrace_detections_child1_revert.csv`
  - both SHA-256 values matched
    `b6beaf5cb1192a54f2a284de765ba92a66481f414705290f6011cc92396bd3b9`
- reviewed black-hot regression suite re-run completed with:
  - `/private/tmp/regression_main_child1_revert/suite_report.json`
  - `/private/tmp/regression_main_child1_revert/suite_report.md`
- visible-color regression suite re-run completed with:
  - `/private/tmp/regression_color_child1_revert/suite_report.json`
  - `/private/tmp/regression_color_child1_revert/suite_report.md`
- visible-color performance benchmark re-run completed with:
  - `/private/tmp/visible_color_perf_child1_revert/visible_color_perf_report.json`

Restored checkpoint after revert:

- reviewed Red1 app-parity headline behavior returned to:
  - `fresh-rgba`: `135` frames with boxes, `171` total box events
  - `legacy`: `152` frames with boxes, `188` total box events
- reviewed fresh target stages returned to:
  - `none = 92`, `no_candidate = 48`, `rarity_rejected = 13`
- reviewed dense fresh target stages returned to:
  - `none = 92`, `no_candidate = 61`
- reviewed visible-color regression remained:
  - `visible-color-baseline`: `TP 0 / 61`, `FP 0 / 0`
  - `visible-color-dense-gold`: `TP 0 / 61`, `FP 0 / 0`
- reviewed black-hot regression also remained:
  - `current-detector-baseline`: `TP 22 / 279`, `FP 0 / 92`
  - `dense-full-scan-gold`: `TP 37 / 279`, `FP 0 / 92`
  - `redesigned-incremental`: `TP 23 / 279`, `FP 2 / 92`
- restored visible-color perf aggregate:
  - `visible-color-app-like-auto`: about `0.37x` realtime,
    `83.07 ms` average total, `40.61 ms` average color scoring
  - `visible-color-dense-gold`: about `0.04x` realtime,
    `919.12 ms` average total, `620.39 ms` average color scoring

Recommendation:

- do not keep or revive this weak-bridge continuity join gate as implemented
- it did not move reviewed Red1 extraction at all and only added dense fresh
  color-path cost
- keep focusing earlier than late subdivision, but prefer support-map shaping
  or seed formation changes that alter the target-containing support field
  before it is already one oversized area-rejected component
- the next thread should compare against the restored artifacts above, not the
  reverted experiment outputs

## Update From 2026-05-11 Codex Main Thread After Codex8/Codex9 Children

This thread sequentially invoked two child threads on the recommended
`extract_color_blob_candidates(...)` seam, then finished the cleanup and
validation in the parent thread.

What happened:

- child 1 tried a fresh-mode-only oversized-component subdivision fallback in
  `app/src/main/cpp/anomaly_analysis.c`
- child 2 completed the main reviewed regression sweep and confirmed that the
  subdivision pass did not improve reviewed Red1 at all
- the parent thread then removed that subdivision fallback and its temporary
  synthetic unit tests because the experiment was a correctness null result and
  also showed a large visible-color perf regression while it was enabled

What changed in the final tree:

- kept the prior codex7 frontend plumbing, candidate-ranking, and target-trace
  improvements intact
- removed the codex8 experimental subdivision helpers and fallback path from
  `app/src/main/cpp/anomaly_analysis.c`
- removed the codex8 temporary synthetic tests from
  `tools/anomaly_test/test_anomaly.c`
- the resulting tree is intended to be the restored codex7-style checkpoint,
  not a new detector retune

What was verified on the final reverted tree:

- `cmake -B build` in `tools/anomaly_test`: passed
- `cmake --build build` in `tools/anomaly_test`: passed
- `./build/anomaly_test`: passed with `67 passed, 0 failed`
- `ctest --output-on-failure` in `tools/anomaly_test/build`: passed
- `cmake -B build_timing -DANOMALY_DEBUG_TIMING=ON` in `tools/anomaly_test`:
  passed
- `cmake --build build_timing` in `tools/anomaly_test`: passed
- `./build_timing/anomaly_test`: passed with `67 passed, 0 failed`
- `./gradlew :app:compileDebugKotlin`: `BUILD SUCCESSFUL`
- reviewed Red1 app-parity re-runs completed with:
  - `/tmp/red1_app_legacy_summary_codex10_revert.json`
  - `/tmp/red1_app_legacy_color_debug_codex10_revert.jsonl`
  - `/tmp/red1_app_fresh_summary_codex10_revert.json`
  - `/tmp/red1_app_fresh_color_debug_codex10_revert.jsonl`
- reviewed Red1 fresh-mode tracing on/off parity check passed again:
  - `/tmp/red1_app_fresh_detections_codex10_revert.csv`
  - `/tmp/red1_app_fresh_notrace_detections_codex10_revert.csv`
  - both SHA-256 values matched
    `b6beaf5cb1192a54f2a284de765ba92a66481f414705290f6011cc92396bd3b9`
- reviewed black-hot regression suite re-run completed with:
  - `/private/tmp/regression_main_codex10_revert/suite_report.json`
  - `/private/tmp/regression_main_codex10_revert/suite_report.md`
- visible-color regression suite re-run completed with:
  - `/private/tmp/regression_color_codex10_revert/suite_report.json`
  - `/private/tmp/regression_color_codex10_revert/suite_report.md`
- post-revert visible-color perf sweep was re-run far enough to finish:
  - all `visible-color-app-like-auto` cases
  - `visible-color-dense-gold_Color1-0.0s-10.0s`
- the same fixed perf driver was interrupted by the user while it was still
  working on dense `Color2`, so there is not yet a complete new
  `/private/tmp/visible_color_perf_codex10_revert/visible_color_perf_report.json`

Important outcome:

- the codex8 subdivision experiment was a validated null result on reviewed
  Red1 and is not kept in the final tree
- the final reverted tree is back to the prior reviewed Red1 headline behavior:
  - `fresh-rgba`: `rarity_rejected = 13`, `no_candidate = 48`
  - `legacy`: `rarity_rejected = 41`, `no_candidate = 20`
  - `fresh-rgba` runtime summary: `135` frames with boxes, `171` total box
    events
  - `legacy` runtime summary: `152` frames with boxes, `188` total box events
- reviewed fresh target extraction remained `0 / 61`
- reviewed fresh target-stage counts also returned to the known checkpoint:
  - `stage_counts`: `none = 92`, `no_candidate = 48`, `rarity_rejected = 13`
- reviewed black-hot regression metrics remained unchanged:
  - `current-detector-baseline`: `TP 22 / 279`, `FP 0 / 92`
  - `dense-full-scan-gold`: `TP 37 / 279`, `FP 0 / 92`
  - `redesigned-incremental`: `TP 23 / 279`, `FP 2 / 92`
- reviewed visible-color regression metrics also remained unchanged:
  - `visible-color-baseline`: `TP 0 / 61`, `FP 0 / 0`
  - `visible-color-dense-gold`: `TP 0 / 61`, `FP 0 / 0`

Performance checkpoint:

- while the subdivision fallback was still enabled, the fixed visible-color
  perf benchmark regressed badly enough that the experiment was not worth
  keeping:
  - app-like aggregate moved to about `106.79 ms` total / `53.86 ms`
    color-scoring on the restored tree after rollback, versus the regressed
    experiment’s much slower run
- post-revert app-like perf is substantially closer to the prior codex7
  checkpoint again:
  - app-like aggregate across Color1/2/3:
    - average realtime factor: about `0.29x`
    - average total detector time: about `106.79 ms`
    - average color scoring time: about `53.86 ms`
- post-revert dense perf is only partially refreshed in this thread:
  - `visible-color-dense-gold_Color1-0.0s-10.0s`:
    - realtime factor: about `0.03x`
    - average total detector time: about `1030.00 ms`
    - average color scoring time: about `648.45 ms`
- because the user interrupted the remaining dense runs, codex7 still provides
  the last complete full-matrix perf report for final comparison:
  - `/private/tmp/visible_color_perf_thisthread/visible_color_perf_report.json`

Interpretation:

- the subdivision idea has now been tested directly on the intended seam and
  did not move reviewed Red1
- it also carried enough color-path cost that it should not be revived without
  a much tighter gating idea
- the working tree is now back on the safer codex7 checkpoint, with the child
  experiment removed

Recommended next step:

- do not spend another thread on component subdivision first
- start from the restored codex7-style tree and inspect target-local support
  shaping before blob extraction, especially ways to prevent the target-colored
  cells from ever entering the oversized component as one merged support field
- if a future thread needs a fresh full perf baseline after more detector
  changes, re-run `tools/anomaly_test/run_visible_color_perf_benchmarks.py`
  to completion and write a new aggregate report before comparing against
  codex7

## Update From 2026-05-11 Codex7 Thread

This thread validated the current in-worktree visible-color candidate-ranking /
frontend-plumbing pass and re-ran the full native, app-parity, reviewed
regression, and visible-color performance sweep.

What changed in the current tree:

- kept `legacy` as the app default visible-color frontend while plumbing
  explicit `colorFrontendMode` through:
  - `AnomalyConfig` / `NativeAnomalyConfig`
  - `FfmpegBridge.kt`
  - `ffmpeg_bridge.c`
- kept the earlier harness/frontend telemetry improvements:
  - app-parity `--color-frontend` override support
  - summary JSON recording `config.color_frontend`
  - richer target-component JSONL fields
- added a fresh-mode candidate-ranking / retention pass in
  `app/src/main/cpp/anomaly_analysis.c` that now:
  - prefers compact blobs more explicitly during color candidate ranking
  - considers center-share and peak-support before area/span tiebreaks
  - adds a bounded temporal boost for fresh-mode candidates near the active
    prior track center
- added synthetic fresh-color unit coverage for:
  - a compact center patch competing against singleton red speckles
  - a compact center patch bridged toward a larger same-color field

What was verified:

- `cmake -B build` in `tools/anomaly_test`: passed
- `cmake --build build` in `tools/anomaly_test`: passed
- `./build/anomaly_test`: passed with `76 passed, 0 failed`
- `ctest --output-on-failure` in `tools/anomaly_test/build`: passed
- `cmake -B build_timing -DANOMALY_DEBUG_TIMING=ON` in `tools/anomaly_test`:
  passed
- `cmake --build build_timing` in `tools/anomaly_test`: passed
- `./build_timing/anomaly_test`: passed with `76 passed, 0 failed`
- `./gradlew :app:compileDebugKotlin`: `BUILD SUCCESSFUL`
- reviewed Red1 app-parity re-runs completed with:
  - `/tmp/red1_app_legacy_summary_thisthread.json`
  - `/tmp/red1_app_legacy_color_debug_thisthread.jsonl`
  - `/tmp/red1_app_fresh_summary_thisthread.json`
  - `/tmp/red1_app_fresh_color_debug_thisthread.jsonl`
- reviewed black-hot regression suite re-run completed with:
  - `/private/tmp/regression_main_thisthread/suite_report.json`
  - `/private/tmp/regression_main_thisthread/suite_report.md`
- visible-color regression suite re-run completed with:
  - `/private/tmp/regression_color_thisthread/suite_report.json`
  - `/private/tmp/regression_color_thisthread/suite_report.md`
- visible-color timing benchmark re-run completed with:
  - `/private/tmp/visible_color_perf_thisthread/visible_color_perf_report.json`
- reviewed Red1 fresh-mode target tracing on/off parity check passed:
  - app-parity `fresh-rgba` detection CSV outputs were byte-identical
  - SHA-256 for tracing-off and tracing-on CSVs both matched:
    `b6beaf5cb1192a54f2a284de765ba92a66481f414705290f6011cc92396bd3b9`

Important outcome:

- this thread did not produce a reviewed Red1 correctness win
- reviewed app-parity headline behavior stayed unchanged:
  - `fresh-rgba`: `rarity_rejected = 13`, `no_candidate = 48`
  - `legacy`: `rarity_rejected = 41`, `no_candidate = 20`
  - `fresh-rgba` runtime summary stayed `135` frames with boxes and `171`
    total box events
  - `legacy` runtime summary stayed `152` frames with boxes and `188` total
    box events
- reviewed target extraction still remained `0 / 61` for both reviewed
  visible-color regression profiles:
  - `visible-color-baseline`: `TP 0 / 61`, `misses 61`
  - `visible-color-dense-gold`: `TP 0 / 61`, `misses 61`
- reviewed fresh target-stage / rejection telemetry also stayed unchanged from
  the prior codex5/codex6 checkpoint:
  - `stage_counts`: `none = 92`, `no_candidate = 48`, `rarity_rejected = 13`
  - `no_candidate` target-component rejection split stayed:
    - area reject: `39`
    - ring reject: `9`
  - mean target-containing rejected component size stayed:
    - area: about `1490.15` sampled cells
    - span: about `55.02` sampled cells
- the reviewed black-hot regression suite stayed unchanged versus the prior
  checkpoint:
  - `current-detector-baseline`: `TP 22 / 279`, `FP 0 / 92`
  - `dense-full-scan-gold`: `TP 37 / 279`, `FP 0 / 92`
  - `redesigned-incremental`: `TP 23 / 279`, `FP 2 / 92`

Performance checkpoint from this thread:

- visible-color app-like auto timing report:
  - average realtime factor: about `0.61x`
  - average total detector time: about `51.37 ms`
  - average color scoring time: about `25.92 ms`
- visible-color dense gold timing report:
  - average realtime factor: about `0.04x`
  - average total detector time: about `892.50 ms`
  - average color scoring time: about `606.27 ms`
- compared with the prior `codex6` benchmark, this run was materially faster:
  - app-like average total detector time moved from about `87.56 ms` to
    `51.37 ms`
  - app-like average color scoring time moved from about `44.19 ms` to
    `25.92 ms`
  - dense-gold average total detector time moved from about `1117.22 ms` to
    `892.50 ms`
  - dense-gold average color scoring time moved from about `749.13 ms` to
    `606.27 ms`

Interpretation:

- the current tree now has the app/frontend plumbing needed to experiment with
  visible-color frontend selection from Kotlin without changing the production
  default away from `legacy`
- the fresh-mode ranking / temporal-bias pass appears performance-positive on
  the fixed visible-color benchmark matrix
- however, it still did not move the reviewed Red1 correctness checkpoint at
  all
- because the reviewed fresh target-stage counts, rejection reasons, and
  extraction count all stayed unchanged, the next thread should still treat
  oversized target-containing component formation as the primary unresolved
  blocker
- the tracing-on/off parity check is now explicitly verified for the reviewed
  `fresh-rgba` app-parity replay, so future detector tuning can use target
  tracing with more confidence

Useful new artifacts:

- `/tmp/red1_app_legacy_summary_thisthread.json`
- `/tmp/red1_app_fresh_summary_thisthread.json`
- `/tmp/red1_app_fresh_color_debug_thisthread.jsonl`
- `/private/tmp/regression_main_thisthread/suite_report.md`
- `/private/tmp/regression_color_thisthread/suite_report.md`
- `/private/tmp/visible_color_perf_thisthread/visible_color_perf_report.json`

Recommended next step:

- do not spend another thread on ranking-only tweaks first
- go back to the codex5/codex6 target-component evidence and work directly in
  target-local component subdivision / support-map post-processing around
  `extract_color_blob_candidates(...)`
- when testing future detector changes, keep this current tree as the new
  “performance-improved but correctness-unchanged” checkpoint

## Update From 2026-05-11 Codex6 Thread

This thread stayed on the low-risk path and finished with a clean validation
sweep. It briefly tested a fresh-mode oversized-component subdivision idea, but
that hypothesis did not improve reviewed Red1 and was not kept in the final
tree.

What changed in the final tree:

- kept the current visible-color detector behavior unchanged relative to the
  prior reviewed Red1 checkpoint
- made app/replay summary artifacts self-describing by recording
  `config.color_frontend` in `anomaly_video_test` summary JSON output
- improved color target debug accounting so
  `target.extracted_candidate_index` falls back to the matched candidate index
  when the matched bbox contains the reviewed center even if the traced
  component peak does not line up exactly

What was verified:

- `cmake --build build` in `tools/anomaly_test`: passed
- `./build/anomaly_test`: passed with `76 passed, 0 failed`
- `ctest --output-on-failure` in `tools/anomaly_test/build`: passed
- `cmake --build build_timing` in `tools/anomaly_test`: passed
- `./build_timing/anomaly_test`: passed with `76 passed, 0 failed`
- `./gradlew :app:compileDebugKotlin`: `BUILD SUCCESSFUL`
- reviewed Red1 app-parity re-runs completed with:
  - `/tmp/red1_app_legacy_summary_codex6.json`
  - `/tmp/red1_app_legacy_color_debug_codex6.jsonl`
  - `/tmp/red1_app_fresh_summary_codex6.json`
  - `/tmp/red1_app_fresh_color_debug_codex6.jsonl`
- reviewed regression suite re-run completed with:
  - `/private/tmp/regression_main_codex6/suite_report.json`
  - `/private/tmp/regression_main_codex6/suite_report.md`
- visible-color regression suite re-run completed with:
  - `/private/tmp/regression_color_codex6/suite_report.json`
  - `/private/tmp/regression_color_codex6/suite_report.md`
- visible-color timing benchmark re-run completed with:
  - `/private/tmp/visible_color_perf_codex6/visible_color_perf_report.json`

Important outcome:

- this thread did not produce a reviewed Red1 correctness win
- reviewed app-parity headline behavior stayed unchanged:
  - `fresh-rgba`: `rarity_rejected = 13`, `no_candidate = 48`
  - `legacy`: `rarity_rejected = 41`, `no_candidate = 20`
  - `fresh-rgba` runtime summary stayed `135` frames with boxes and `171`
    total box events
  - `legacy` runtime summary stayed `152` frames with boxes and `188` total
    box events
- reviewed target extraction still remained `0 / 61` for both reviewed
  visible-color regression profiles:
  - `visible-color-baseline`: `TP 0 / 61`, `misses 61`
  - `visible-color-dense-gold`: `TP 0 / 61`, `misses 61`
- the attempted fresh-mode component subdivision hypothesis was a validated
  null result and was not kept
- the reviewed black-hot regression suite did not show reviewed metric
  regressions versus the prior `codex5` artifacts:
  - `current-detector-baseline`: unchanged at `TP 22 / 279`, `FP 0 / 92`
  - `dense-full-scan-gold`: unchanged at `TP 37 / 279`, `FP 0 / 92`
  - `redesigned-incremental`: unchanged at `TP 23 / 279`, `FP 2 / 92`

Performance checkpoint from this thread:

- visible-color app-like auto timing report:
  - average realtime factor: about `0.35x`
  - average total detector time: about `87.56 ms`
  - average color scoring time: about `44.19 ms`
- visible-color dense gold timing report:
  - average realtime factor: about `0.03x`
  - average total detector time: about `1117.22 ms`
  - average color scoring time: about `749.13 ms`
- compared with the prior `codex5` benchmark, this run was slightly slower:
  - app-like average total detector time moved from about `86.61 ms` to
    `87.56 ms`
  - dense-gold average total detector time moved from about `1028.59 ms` to
    `1117.22 ms`

Interpretation:

- the final landed changes in this thread are debugging / artifact-quality
  improvements, not a detector retune
- the next thread should still treat oversized target-containing fresh-mode
  components as the primary unresolved correctness blocker
- the reviewed fresh trace is still the first thing to inspect, but the new
  summary JSON now records the active frontend directly, which makes future
  artifact comparison less error-prone
- the extraction-index fallback did not change reviewed Red1 because there were
  still no extracted target candidates, but it should make future target-debug
  traces more trustworthy once a partial correctness win exists

Useful new artifacts:

- `/tmp/red1_app_legacy_summary_codex6.json`
- `/tmp/red1_app_fresh_summary_codex6.json`
- `/tmp/red1_app_fresh_color_debug_codex6.jsonl`

If continuing detector work next, start from the `codex5` and `codex6` fresh
JSONL traces together:

- `codex5` remains the key target-component telemetry checkpoint
- `codex6` confirms the final tree stayed behaviorally aligned while improving
  artifact self-description

## Update From 2026-05-11 Codex5 Thread

This thread stayed deliberately low risk. It did not attempt another detector
behavior retune first. Instead, it closed a visibility gap in the reviewed
Red1 debugging path and re-ran the full native / regression / performance
validation sweep.

What changed:

- added target-component telemetry to visible-color blob extraction so the
  reviewed target JSONL now records the exact component containing the target
  sample at extraction time:
  - component seed / peak sample
  - component area / span / fill
  - component peak support / mean support / quality
  - component ring fraction / support mass
  - whether the component was rejected and by which blob gate
  - the component bbox footprint and whether it ever became an extracted
    candidate
- aligned visible-color debug bbox accounting with the actual published /
  overlay footprint by using the same expanded sample-cell box math for:
  - candidate JSONL bbox fields
  - target matched-candidate bbox fields
  - target contains-candidate checks used by reviewed tracing
- added native regression coverage that the bridged fresh-color target case
  must populate blob-telemetry fields once it survives into support seeding

What was verified:

- `cmake --build build` in `tools/anomaly_test`: passed
- `./build/anomaly_test`: passed with `76 passed, 0 failed`
- `ctest --output-on-failure` in `tools/anomaly_test/build`: passed
- `cmake --build build_timing` in `tools/anomaly_test`: passed
- `./gradlew :app:compileDebugKotlin`: `BUILD SUCCESSFUL`
- reviewed Red1 app-parity re-runs completed with:
  - `/tmp/red1_app_legacy_summary_codex5.json`
  - `/tmp/red1_app_legacy_color_debug_codex5.jsonl`
  - `/tmp/red1_app_fresh_summary_codex5.json`
  - `/tmp/red1_app_fresh_color_debug_codex5.jsonl`
- reviewed regression suite re-run completed with:
  - `/private/tmp/regression_main_codex5/suite_report.json`
  - `/private/tmp/regression_main_codex5/suite_report.md`
- visible-color regression suite re-run completed with:
  - `/private/tmp/regression_color_codex5/suite_report.json`
  - `/private/tmp/regression_color_codex5/suite_report.md`
- visible-color timing benchmark re-run completed with:
  - `/private/tmp/visible_color_perf_codex5/visible_color_perf_report.json`

Important outcome:

- this thread did not produce a reviewed Red1 correctness win
- reviewed app-parity headline behavior stayed unchanged:
  - `fresh-rgba`: `rarity_rejected = 13`, `no_candidate = 48`
  - `legacy`: `rarity_rejected = 41`, `no_candidate = 20`
  - `fresh-rgba` runtime summary stayed `135` frames with boxes and `171`
    total box events
  - `legacy` runtime summary stayed `152` frames with boxes and `188` total
    box events
- the new telemetry finally answered the main unresolved extraction question:
  - on all `48 / 48` reviewed `fresh-rgba` `no_candidate` frames, the target
    sample belonged to a traced extraction component
  - on all `48 / 48`, that target-containing component was rejected before any
    candidate was emitted
  - extracted target candidate count remained `0 / 61`
  - target-component rejection reason breakdown on those `48` frames was:
    - area reject: `39`
    - ring reject: `9`
- the target-containing rejected components were not borderline:
  - mean traced component area: about `1490.15` sampled cells
  - mean traced component span: about `55.02` sampled cells
  - closest failing frames still showed very large target-containing rejected
    blobs, for example:
    - frame `18` at `0.567s`: area `1953`, span `69`
    - frame `19` at `0.600s`: area `1803`, span `69`
    - frame `23` at `0.733s`: area `1964`, span `69`
    - frame `135` at `4.467s`: area `1668`, span `62`
    - frame `140` at `4.634s`: area `1557`, span `63`

Interpretation:

- the current blocker is now much clearer than in previous threads
- the reviewed target is not mainly failing because the right compact blob is
  extracted but ranked slightly wrong
- instead, the reviewed target is usually being merged into a much larger
  target-containing component that dies at blob gating before extraction
- the previous “nearest candidate is close” signal was misleading by itself;
  those nearby emitted candidates are usually unrelated survivors next to a
  separately rejected target-containing super-component
- the most valuable next work is therefore target-local subdivision /
  component-splitting inside `extract_color_blob_candidates(...)`, especially
  before or during the conditions that let a fresh-mode supported region grow
  into a `~55`-cell-span connected field

Performance checkpoint from this thread:

- visible-color app-like auto timing report:
  - average realtime factor: about `0.36x`
  - average total detector time: about `86.61 ms`
  - average color scoring time: about `41.28 ms`
- visible-color dense gold timing report:
  - average realtime factor: about `0.03x`
  - average total detector time: about `1028.59 ms`
  - average color scoring time: about `696.09 ms`

Useful new artifact:

- `/tmp/red1_app_fresh_color_debug_codex5.jsonl`

Use that trace first in the next thread. It is the first reviewed fresh Red1
JSONL that directly tells you whether the target-containing component was
rejected, how large it was, and which blob gate killed it.

## Update From 2026-05-11 Codex4 Thread

This thread tried one more low-risk detector-side hypothesis in the exact area
the handoff recommended: fresh-mode blob growth inside
`extract_color_blob_candidates(...)`.

What changed:

- tightened fresh-mode blob growth earlier once a color blob expands beyond a
  compact-target envelope
- when that fresh-mode large-blob growth path is active:
  - diagonal joins are no longer allowed
  - neighbor join thresholds are raised
  - the allowed support-band slack is reduced
- added a native fresh-RGBA synthetic guardrail where a compact center patch
  connected toward a larger same-color field must still survive support seeding
  and rarity gating

What was verified:

- `cmake --build build` in `tools/anomaly_test`: passed
- `./build/anomaly_test`: passed with `74 passed, 0 failed`
- `ctest --output-on-failure` in `tools/anomaly_test/build`: passed
- `cmake -B build_timing -DANOMALY_DEBUG_TIMING=ON` in `tools/anomaly_test`:
  passed
- `cmake --build build_timing` in `tools/anomaly_test`: passed
- `./gradlew :app:compileDebugKotlin`: `BUILD SUCCESSFUL`
- reviewed Red1 `fresh-rgba` app-parity re-run completed with:
  - `/tmp/red1_app_fresh_summary_codex4.json`
  - `/tmp/red1_app_fresh_color_debug_codex4.jsonl`
- reviewed regression suite re-run completed with:
  - `/private/tmp/regression_main_codex4/suite_report.json`
  - `/private/tmp/regression_main_codex4/suite_report.md`
- visible-color regression suite re-run completed with:
  - `/private/tmp/regression_color_codex4/suite_report.json`
  - `/private/tmp/regression_color_codex4/suite_report.md`
- visible-color timing benchmark re-run completed with:
  - `/private/tmp/visible_color_perf_codex4/visible_color_perf_report.json`

Important outcome:

- the fresh-mode large-blob join-tightening hypothesis did not improve the
  reviewed Red1 correctness checkpoint
- reviewed `fresh-rgba` remained exactly unchanged at the headline level:
  - `rarity_rejected`: 13
  - `no_candidate`: 48
  - matched candidate frames: 0
  - winner frames containing reviewed center: 0
- app-parity runtime summary for reviewed `fresh-rgba` also remained unchanged:
  - frames with boxes: 135
  - total box events: 171
- the mean nearest-candidate distance on `no_candidate` reviewed frames also
  remained unchanged at about `0.130224`
- the dominant reject mode still stayed overwhelmingly at oversized area
  rejection; only the raw rejected blob areas shifted somewhat smaller on some
  frames, without changing extraction success

Useful detail from this run:

- the closest unchanged failing frames were still:
  - frame `140` at `4.634s`
  - frame `19` at `0.600s`
  - frame `23` at `0.733s`
  - frame `18` at `0.567s`
  - frame `135` at `4.467s`
- their nearest-candidate distances and stage labels stayed the same as the
  previous `codex3` trace even after the join-tightening change

Performance checkpoint from this thread:

- visible-color app-like auto timing report:
  - average realtime factor: about `0.34x`
  - average total detector time: about `92.08 ms`
  - average color scoring time: about `46.16 ms`
- visible-color dense gold timing report:
  - average realtime factor: about `0.03x`
  - average total detector time: about `1166.57 ms`
  - average color scoring time: about `781.02 ms`

Interpretation:

- this thread did not produce a reviewed-Red1 correctness win
- it did produce one more validated null result on the detector side:
  tightening fresh-mode blob joins alone is not enough to turn the reviewed
  target into a matched candidate
- the remaining blocker still looks more like target-local component identity /
  box placement than a simple “let fewer neighbors join” problem
- the next thread should use `/tmp/red1_app_fresh_color_debug_codex4.jsonl` as
  the newest reviewed fresh trace if continuing from the current tree

## Update From 2026-05-11 Codex3 Thread

This thread implemented one practical detector-side follow-up and verified it
against both native tests and the reviewed `fresh-rgba` Red1 harness case.

What changed:

- refactored fresh color blob candidate scoring/rejection into a shared helper
  inside `app/src/main/cpp/anomaly_analysis.c`
- tried a bounded fresh-mode salvage path for oversized color blobs so strong
  local peak cores could still be emitted as compact candidates even when the
  parent connected component would otherwise be rejected on area
- added a temporary native regression for a compact center patch bridged into a
  larger same-color field, then removed it after the hypothesis failed and the
  thread restored the previous passing baseline

What was verified:

- `cmake --build build` in `tools/anomaly_test`: passed
- `./build/anomaly_test`: passed with `72 passed, 0 failed`
- `ctest --output-on-failure` in `tools/anomaly_test/build`: passed
- `./gradlew :app:compileDebugKotlin`: `BUILD SUCCESSFUL`
- reviewed Red1 `fresh-rgba` app-parity re-run completed with:
  - `/tmp/red1_app_fresh_summary_codex3.json`
  - `/tmp/red1_app_fresh_color_debug_codex3.jsonl`
- reviewed regression suite re-run completed with:
  - `/private/tmp/regression_main_codex/suite_report.json`
  - `/private/tmp/regression_main_codex/suite_report.md`
- visible-color regression suite re-run completed with:
  - `/private/tmp/regression_color_codex/suite_report.json`
  - `/private/tmp/regression_color_codex/suite_report.md`

Important outcome:

- the oversized-blob salvage hypothesis did not improve reviewed Red1
  correctness and was not kept
- reviewed `fresh-rgba` remained:
  - `rarity_rejected`: 13
  - `no_candidate`: 48
  - matched candidate frames: 0
  - winner frames containing reviewed center: 0
- runtime summary shifted slightly but not meaningfully toward correctness:
  - previous `codex2`: `134` frames with boxes, `173` total box events
  - current `codex3`: `135` frames with boxes, `171` total box events
- for `no_candidate` reviewed frames, the dominant reject signal still stayed
  almost entirely at oversized blob rejection:
  - `strongest_reject_reason = area` on `47 / 48` target `no_candidate` frames
  - summed reject counters across those frames stayed:
    - `blob_reject_area_count`: `356`
    - `blob_reject_support_mass_count`: `175`

Interpretation:

- the attempted “emit compact cores from oversized blobs” hypothesis did not
  survive validation and the tree was returned to the prior passing baseline
- the next thread should assume the remaining blocker is still target-local
  component formation / subdivision near the reviewed center, not test
  infrastructure

## Update From 2026-05-11 Thread

This follow-up thread did not land a new detector-behavior win for reviewed
Red1. It did two practical things:

- re-ran the reviewed `fresh-rgba` app-parity Red1 comparison after a focused
  local NMS hypothesis check
- updated the harness JSONL writer so color debug output now includes the
  already-existing blob post-pass counters:
  - `support_seed_count`
  - `support_peak_score`
  - `blob_reject_*`
  - `blob_examined_count`
  - `strongest_reject_*`

Important outcome:

- the NMS-side hypothesis did not improve reviewed Red1 and was not kept as a
  detector behavior change
- reviewed `fresh-rgba` remains:
  - `rarity_rejected`: 13
  - `no_candidate`: 48
  - matched candidate frames: 0
  - winner frames containing reviewed center: 0
- runtime summary also remained unchanged from the prior post-ranking-change
  checkpoint:
  - frames with boxes: 134
  - total box events: 173

So the next thread should treat the previous post-ranking-change detector
behavior as still current, but should use the newly richer JSONL output to
inspect exactly why target-local blobs are failing to survive candidate
extraction.

New artifact from this thread:

- `/tmp/red1_app_fresh_summary_codex2.json`
- `/tmp/red1_app_fresh_color_debug_codex2.jsonl`

Those files are especially useful because the JSONL now exposes blob-extraction
reject counters directly, which older Red1 color JSONL outputs did not.

## Where This Thread Landed

The previous thread completed the native Phase 1 stabilization checkpoint from
`docs/Low_Risk_Color_AD_Architecture_Plan.md`, but it did not complete the
reviewed Red1 correctness checkpoint.

This thread did one focused detector-side follow-up:

- changed color blob top-candidate ranking so the extractor no longer prefers
  the smallest blobs ahead of stronger compact blobs
- added a native regression test for a compact center patch competing with
  many isolated same-color speckles
- re-ran reviewed Red1 app-parity comparisons after that ranking change

In short:

- native/frontend plumbing for `legacy`, `fresh_rgba`, and scaffolded
  `fresh_yuv` is in place
- native color unit tests are now green again
- Kotlin/native bridge still compiles
- app-parity harness support for color frontend selection is now trustworthy
- fresh color candidate lists are no longer dominated by singleton speckles in
  reviewed Red1
- Red1 still fails in both `legacy` and `fresh-rgba`
- `fresh-rgba` improves target seed/support survival versus `legacy`
- but `fresh-rgba` still does not extract or rank a winning box containing the
  reviewed center

So the next thread should not start with more architecture work. It should
start with focused reviewed-Red1 candidate extraction work near the reviewed
center, using the post-ranking-change traces as the new baseline.

## Progress Against The Low-Risk Plan

### Completed or effectively completed

From `docs/Low_Risk_Color_AD_Architecture_Plan.md`:

- Phase 1 kept the public RGBA `anomaly_process_frame(...)` boundary unchanged
- FFmpeg decode / playback / overlay pipelines were not changed
- hidden native experiment modes exist:
  - `legacy`
  - `fresh_rgba`
  - `fresh_yuv`
- production default remains `legacy`
- `fresh_rgba` forces current-frame color sampling rather than carried color
  state
- fresh mode disables pre-support temporal rescue
- fresh mode adds post-candidate temporal boost instead
- `color_contrast_weight` was repurposed into a current-frame cohesion signal
  for fresh mode only
- cohesion is used in:
  - support-map weighting
  - blob neighbor-join decisions
- `fresh_yuv` is still scaffolded only and falls back to `fresh_rgba`
- native color tests pass
- Kotlin compile passes
- a new native regression now checks that a compact fresh-RGBA center patch
  survives against many isolated speckles

### Not yet complete

Also from the plan:

- reviewed Red1 is still not passing
- `fresh-rgba` is not yet a trustworthy experiment path for reviewed Red1
- the singleton-heavy top-candidate failure mode is improved, but the target is
  still not being extracted as a matched candidate in reviewed Red1
- the next thread still needs to validate debug target tracing enabled vs
  disabled after any new color scoring changes
- no decision should be made yet about implementing true `fresh_yuv`

## Files Touched In This Thread

Primary implementation files:

- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c`
- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h`
- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/ffmpeg_bridge.c`
- `/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/video/anomaly/AnomalyModels.kt`
- `/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/video/ffmpeg/FfmpegBridge.kt`

Harness / validation files:

- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/anomaly_video_test.c`
- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/test_anomaly.c`

Docs:

- `/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_Anomaly_Detector_Architecture.md`
- `/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Next_Thread_Handoff.md`

## What This Thread Changed

Primary detector change:

- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c`
  - `compare_color_blob_rank(...)` no longer sorts by smallest area/span ahead
    of stronger compact blobs
  - ranking now keeps retention-rank first, then favors stronger
    score/quality, then a compact-target preference instead of a blanket
    singleton preference

New regression coverage:

- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/test_anomaly.c`
  - added a fresh-RGBA test where a compact center patch must survive against
    many isolated same-color speckles

## What Was Fixed In Native Color Behavior

This thread resolved the native regressions that were blocking Phase 1
validation.

The important detector-side fixes were:

- explicit legacy/fresh frontend gating was kept intact in the color path
- legacy test configs were pinned explicitly to `ANOMALY_COLOR_FRONTEND_LEGACY`
- near-neutral grayscale texture is no longer promoted into color seeds via
  color rarity / contrast rescue
- compact isolated color blobs are allowed to survive coarse area rejection
  when their support-map spread is still target-like

That was enough to restore:

- basic compact red-patch detection
- stride-hold behavior
- large-motion initial hotspot establishment
- target-only planning / track persistence tests

## What Passed

- `cmake --build build` in `tools/anomaly_test`
- `./build/anomaly_test`
  - previous checkpoint result: `67 passed, 0 failed`
  - current follow-up thread result: `72 passed, 0 failed`
- `./gradlew :app:compileDebugKotlin`
  - result: `BUILD SUCCESSFUL`

## Important Harness Fix From This Thread

The harness had a misleading app-parity behavior at the start of this thread:

- `--app-defaults` always forced `fresh-rgba`
- that was inconsistent with Kotlin, where the app default is `Legacy`
- it also made `--app-defaults --color-frontend legacy` ineffective

This thread fixed that in `tools/anomaly_test/anomaly_video_test.c` so that:

- app-parity defaults now resolve to `legacy`
- explicit `--color-frontend ...` overrides are preserved after app-parity
  config derivation

Do not assume older app-parity Red1 comparisons are frontend-correct unless
you verify how they were run.

## Reviewed Red1 Status

The reviewed target case used here was:

- clip:
  `/Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4`
- reviewed target CSV reused from:
  `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/regression/color-red1-telemetry-pass/visible-color-baseline/red1-opening-target-track/color_target.csv`

This thread produced true app-parity comparison outputs in:

- legacy:
  `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/app_legacy/`
- fresh-rgba:
  `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/app_fresh/`

Most important artifacts:

- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/app_legacy/summary.json`
- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/app_legacy/color_debug.jsonl`
- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/app_fresh/summary.json`
- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/app_fresh/color_debug.jsonl`

This thread also produced updated post-ranking-change comparison outputs in
`/tmp`:

- `/tmp/red1_app_legacy_summary_after.json`
- `/tmp/red1_app_legacy_color_debug_after.jsonl`
- `/tmp/red1_app_fresh_summary_after.json`
- `/tmp/red1_app_fresh_color_debug_after.jsonl`

### Result summary

Neither frontend currently wins the reviewed Red1 target.

For the 61 reviewed target frames:

- `legacy`
  - stage counts:
    - `rarity_rejected`: 46
    - `no_candidate`: 15
  - support-seed eligible target frames: 15
  - target frames with any winner candidate present: 56
  - target frames where winning candidate contains reviewed center: 0

- `fresh-rgba`
  - stage counts:
    - `rarity_rejected`: 13
    - `no_candidate`: 48
  - support-seed eligible target frames: 48
  - target frames with any winner candidate present: 61
  - target frames where winning candidate contains reviewed center: 0

Interpretation:

- `fresh-rgba` meaningfully improves front-end target survival
- the target is much less likely to die at `rarity_rejected`
- but the downstream blob/candidate stage still fails to produce the right box
- the remaining problem is not primarily front-end seed starvation anymore
- it is now candidate extraction / candidate ranking / box placement

### Secondary runtime comparison

App-parity run summaries on the same Red1 excerpt:

- `legacy`
  - frames with boxes: 146
  - total box events: 170
- `fresh-rgba`
  - frames with boxes: 132
  - total box events: 162

So `fresh-rgba` is not simply “more permissive everywhere.” It is producing
better target support but still not translating that into a correct reviewed
winner.

### Post-ranking-change follow-up

After the ranking change from this thread:

- `legacy`
  - stage counts:
    - `rarity_rejected`: 41
    - `no_candidate`: 20
  - target matched-candidate frames: 0
  - frames whose top candidate list was all singleton blobs: 6
  - app-parity runtime summary:
    - frames with boxes: 152
    - total box events: 188

- `fresh-rgba`
  - stage counts:
    - `rarity_rejected`: 13
    - `no_candidate`: 48
  - target matched-candidate frames: 0
  - frames whose top candidate list was all singleton blobs: 0
  - mean nearest-candidate distance to reviewed target improved from about
    `0.167` to about `0.134`
  - app-parity runtime summary:
    - frames with boxes: 134
    - total box events: 173

Interpretation:

- the ranking fix improved the candidate pool shape in `fresh-rgba`
- the old failure mode where the top list was nearly all `area=1` speckles is
  materially reduced
- but the reviewed target still does not become a matched candidate
- the dominant remaining problem is now more specifically target-local blob
  formation / join behavior near the reviewed center, not generic top-list
  singleton crowding

### 2026-05-11 follow-up

This thread re-ran the reviewed `fresh-rgba` Red1 comparison with the current
detector and richer JSONL instrumentation:

- fresh-rgba:
  - stage counts:
    - `rarity_rejected`: 13
    - `no_candidate`: 48
  - target matched-candidate frames: 0
  - app-parity runtime summary:
    - frames with boxes: 134
    - total box events: 173

Interpretation:

- no additional correctness progress was observed in reviewed Red1
- the practical value from this thread is better diagnostics, not a new tuned
  detector behavior
- if a next thread changes blob extraction again, compare against
  `/tmp/red1_app_fresh_color_debug_codex2.jsonl` instead of older JSONL
  outputs because it includes blob reject counters directly

## Best Current Interpretation

At this checkpoint, the architecture work has done its job:

- legacy compatibility is restored
- fresh current-frame sampling is implemented and testable
- reviewed Red1 no longer points first at stale color-state carry

The dominant remaining failure mode now appears to be:

- the reviewed Red1 target can survive front-end gating in `fresh-rgba`
- and the candidate pool is healthier than before
- but the target-local support still fragments, shifts, or joins incorrectly so
  the reviewed center never becomes a matched candidate

That means the next thread should work in:

- `extract_color_blob_candidates(...)`
- support-map post-processing immediately around it
- target-local join behavior and box placement around the reviewed center

not in:

- FFmpeg decode
- RGBA/YUV frame plumbing
- Kotlin bridge plumbing
- new public settings

## Recommended Next Work Order

### 1. Reproduce the exact reviewed Red1 comparison first

Start by regenerating the app-parity outputs using the fixed harness:

```bash
cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test

./build/anomaly_video_test \
  /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 \
  --no-video \
  --app-defaults \
  --app-appearance color \
  --color-frontend legacy \
  --time-start 0.0 \
  --time-end 5.1 \
  --summary-json /tmp/red1_app_legacy_summary.json \
  --color-debug-jsonl /tmp/red1_app_legacy_color_debug.jsonl \
  --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv \
  -c /tmp/red1_app_legacy_detections.csv

./build/anomaly_video_test \
  /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 \
  --no-video \
  --app-defaults \
  --app-appearance color \
  --color-frontend fresh-rgba \
  --time-start 0.0 \
  --time-end 5.1 \
  --summary-json /tmp/red1_app_fresh_summary.json \
  --color-debug-jsonl /tmp/red1_app_fresh_color_debug.jsonl \
  --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv \
  -c /tmp/red1_app_fresh_detections.csv
```

Confirm before doing new tuning:

- `legacy` really prints `color = legacy`
- `fresh-rgba` really prints `color = fresh-rgba`

### 2. Inspect reviewed target frames that still reach `no_candidate`

This is the highest-value next step.

Fresh mode already improved many Red1 target frames from:

- `rarity_rejected`

to:

- `no_candidate`

Start with the updated post-ranking-change trace:

- `/tmp/red1_app_fresh_color_debug_after.jsonl`

Highest-value frames to inspect first are those where the nearest candidate is
already close to the reviewed target but still does not match it:

- frame `140` at `4.634s`
- frame `19` at `0.600s`
- frame `23` at `0.733s`
- frame `18` at `0.567s`
- frame `135` at `4.467s`

For the next thread, prefer using the newest reviewed fresh trace first:

- `/tmp/red1_app_fresh_color_debug_codex3.jsonl`

If you need the prior unchanged baseline immediately before the oversized-blob
salvage attempt, keep this older comparison close by as well:

- `/tmp/red1_app_fresh_color_debug_codex2.jsonl`

because it now includes:

- `support_seed_count`
- `support_peak_score`
- `blob_reject_area_count`
- `blob_reject_ring_count`
- `blob_reject_support_mass_count`
- `blob_reject_quality_count`
- `blob_examined_count`
- `strongest_reject_reason`
- `strongest_reject_peak_support`
- `strongest_reject_area`
- `strongest_reject_span`
- `strongest_reject_ring_fraction`
- `strongest_reject_support_mass`
- `strongest_reject_quality`

These are useful because the nearest candidate distance is already around
`0.05` to `0.06`, so the remaining miss is likely box placement / component
formation rather than gross target starvation.

Questions to answer:

- Is the target support map split into two- or three-cell components just off
  the reviewed center?
- Is the target merged into a nearby component whose peak is displaced away
  from the reviewed center?
- Is candidate NMS collapsing the right local blob into the wrong nearby blob?
- Is box center selection too tied to a support peak offset from the reviewed
  center even when the blob is otherwise target-like?

### 3. Tune blob extraction before touching new frontend logic

The most likely next tuning area is:

- `extract_color_blob_candidates(...)`

Likely sub-areas:

- join thresholds / neighbor banding
- support-map thresholding around `0.55f` seeds for target-local cells
- compact blob area/span exceptions
- candidate center selection within a blob
- target-local blob matching consequences of peak-vs-bbox placement

The current evidence says this is more important than more rarity tuning and
more important than further generic ranking changes.

### 4. Re-check debug target tracing enabled vs disabled after each scoring change

The architecture plan explicitly requires this.

Do not trust a tuning pass until detector decisions are unchanged with target
tracing on vs off.

### 5. Do not start real `fresh_yuv` yet

The Phase 2 decision remains premature.

Reason:

- `fresh-rgba` has not yet been made correct enough on reviewed Red1
- the current blocker is downstream candidate behavior, not obviously the RGBA
  boundary itself

Only revisit `fresh_yuv` after reviewed Red1 has a credible `fresh-rgba`
baseline.

## Important Cautions

- do not change the FFmpeg display/decode path yet
- do not change playback or overlay frame formats
- do not change the public RGBA `anomaly_process_frame(...)` signature yet
- do not assume old app-parity color frontend comparisons were valid before the
  harness fix in this thread
- do not begin YUV-sidecar work before reviewed Red1 blob/candidate behavior is
  understood

## Practical Starting Points

If picking one place to start, inspect these sections first:

- `extract_color_blob_candidates(...)`
- `build_color_support_map(...)`
- reviewed Red1 target rows in:
  - `/tmp/red1_app_fresh_color_debug_after.jsonl`
  - `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/app_fresh/color_debug.jsonl`
- the app-parity frontend override handling in:
  - `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/anomaly_video_test.c`

## Desired End State For This Phase

The phase is complete when all of the following are true:

- native color unit tests pass
- Kotlin/native bridge still compiles
- `legacy` remains the stable production default
- app-parity `legacy` and `fresh-rgba` comparisons are trustworthy
- reviewed Red1 target frames are no longer dying mainly at front-end gating
- reviewed Red1 winner boxes contain the reviewed center often enough to treat
  `fresh-rgba` as a credible experiment path
- debug target tracing on/off does not change detector decisions

Only after that should the next thread decide whether true `fresh_yuv` is worth
implementing.

## Update From 2026-05-11 Parent Thread

This parent-thread cycle stayed read-only and narrowed the next bounded
experiment choice before assigning more mutating work.

What was reviewed:

- `/tmp/red1_app_fresh_color_debug_child2_supportshape_v2.jsonl`
- `app/src/main/cpp/anomaly_analysis.c`
- `app/src/main/cpp/anomaly_analysis.h`

What the parent analysis established:

- all `10` `support_map_rejected` reviewed fresh target frames failed at the
  support-map seed gate after still showing:
  - coherent local target neighborhoods
  - nonzero pre-support
  - no earlier rarity or local-support rejection
- the drop from pre-support to final support score is therefore happening
  inside the fresh support-map shaping path, not because the target vanished
  before reaching it
- the larger reviewed fresh blocker remains post-extraction ring rejection:
  `28` ring rejects versus only `8` support-mass rejects among the `38`
  `no_candidate` target frames

Parent decision for the next child packet:

- keep `child2_supportshape_v2` as the approved checkpoint
- keep the next mutating experiment on one seam only:
  `build_color_support_map(...)`
- specifically test a fresh-only local ring-thinning or ring-penalty relief
  change around already-compact target blobs
- do not start with support-mass loosening, area exceptions, or ranking-only
  tweaks

Suggested next child id:

- `child3_ringthin_v1`

Prepared next child assignment packet:

```md
Child id: child3_ringthin_v1
Role: experiment
Checkpoint: child2_supportshape_v2
Hypothesis: A fresh-only local ring-thinning or ring-penalty relief change in build_color_support_map(...) will lift both the 10 support-map seed-gate misses and part of the 28 ring-rejected compact blobs without regressing legacy behavior.
Write scope: app/src/main/cpp/anomaly_analysis.c in build_color_support_map(...) and directly related native unit coverage only
Non-goals:
- do not change extract_color_blob_candidates(...)
- do not loosen support-mass, area, or ranking gates first
- do not change legacy behavior
- do not change FFmpeg, Kotlin bridge, public analyzer interfaces, or frontend mode wiring
Required commands:
- cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test
- cmake -B build
- cmake --build build
- ./build/anomaly_test
- cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/build
- ctest --output-on-failure
- cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test
- cmake -B build_timing -DANOMALY_DEBUG_TIMING=ON
- cmake --build build_timing
- ./build_timing/anomaly_test
- cd /Users/kjt/Projects/RID2Caltopo
- ./gradlew :app:compileDebugKotlin
- cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test
- ./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend legacy --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_legacy_summary_child3_ringthin_v1.json --color-debug-jsonl /tmp/red1_app_legacy_color_debug_child3_ringthin_v1.jsonl --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv -c /tmp/red1_app_legacy_detections_child3_ringthin_v1.csv
- ./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_fresh_summary_child3_ringthin_v1.json --color-debug-jsonl /tmp/red1_app_fresh_color_debug_child3_ringthin_v1.jsonl --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv -c /tmp/red1_app_fresh_detections_child3_ringthin_v1.csv
- ./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_fresh_notrace_summary_child3_ringthin_v1.json -c /tmp/red1_app_fresh_notrace_detections_child3_ringthin_v1.csv
- ./build/anomaly_video_test /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_app_fresh_trace_summary_child3_ringthin_v1.json --color-debug-jsonl /tmp/red1_app_fresh_trace_color_debug_child3_ringthin_v1.jsonl --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv -c /tmp/red1_app_fresh_trace_detections_child3_ringthin_v1.csv
- shasum -a 256 /tmp/red1_app_fresh_notrace_detections_child3_ringthin_v1.csv
- shasum -a 256 /tmp/red1_app_fresh_trace_detections_child3_ringthin_v1.csv
- cd /Users/kjt/Projects/RID2Caltopo
- python3 tools/anomaly_test/run_regression_suite.py --manifest tools/anomaly_test/regression_suite_manifest.json --binary tools/anomaly_test/build_timing/anomaly_video_test --out-dir /private/tmp/regression_main_child3_ringthin_v1 --report-json /private/tmp/regression_main_child3_ringthin_v1/suite_report.json --report-md /private/tmp/regression_main_child3_ringthin_v1/suite_report.md
- python3 tools/anomaly_test/run_regression_suite.py --manifest tools/anomaly_test/regression_suite_color_manifest.json --binary tools/anomaly_test/build_timing/anomaly_video_test --out-dir /private/tmp/regression_color_child3_ringthin_v1 --report-json /private/tmp/regression_color_child3_ringthin_v1/suite_report.json --report-md /private/tmp/regression_color_child3_ringthin_v1/suite_report.md
- python3 tools/anomaly_test/run_visible_color_perf_benchmarks.py --binary tools/anomaly_test/build_timing/anomaly_video_test --output-dir /private/tmp/visible_color_perf_child3_ringthin_v1
Validation ownership:
- because this child mutates code, it must run the entire required command set itself after its change
- the parent thread must not substitute a later regression pass for this child’s missing validation
Adoption gate:
- reviewed Red1 must improve meaningfully versus child2_supportshape_v2
- at minimum, the candidate should either reduce the 10 support_map_rejected target frames, reduce ring-rejected target blobs from the current 28-frame burden, or convert some reviewed fresh target failures into matched-candidate or winning-box success
- legacy reviewed Red1 behavior must stay aligned with the approved checkpoint
- fresh tracing on/off parity must still hold
- black-hot and visible-color regression metrics must not regress materially
- any perf cost must stay inside the current envelope unless the correctness gain is clear enough for parent adoption
Artifact suffix:
- child3_ringthin_v1
Expected report format:
- attempted change
- whether code remains active or was reverted
- commands run
- pass/fail summary
- headline metrics before vs after
- conclusion: adopt | reject | needs narrower follow-up
- artifact paths
```

Launch-ready experiment child wrapper:

```md
Read these first:
- /Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Next_Thread_Handoff.md
- /Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Thread_Operating_Model.md

You are child3_ringthin_v1. Role: experiment child.
Start from approved checkpoint child2_supportshape_v2.
Own only this seam: app/src/main/cpp/anomaly_analysis.c in build_color_support_map(...) and directly related native unit coverage only.
Test only this hypothesis: A fresh-only local ring-thinning or ring-penalty relief change in build_color_support_map(...) will lift both the 10 support-map seed-gate misses and part of the 28 ring-rejected compact blobs without regressing legacy behavior.
Do not change extract_color_blob_candidates(...), support-mass gates, area gates, ranking logic, legacy behavior, FFmpeg or Kotlin bridge paths, public analyzer interfaces, or frontend mode wiring.
Run the required validation commands with artifact suffix child3_ringthin_v1.
You own the full post-change validation sweep for your mutation. Do not hand
that validation burden back to the parent thread.
If the result is a correctness null or regression, revert your own change before reporting.
Return the child report template exactly.
```

## Update From 2026-05-11 Perf Investigation Thread

This thread did not adopt any new detector change. It was a replay/perf
investigation thread only, and both candidate perf fixes were reverted after
they failed to improve the benchmark path.

### What Was Verified

Native rebuild and unit verification still pass on the current code state:

```sh
cmake -S tools/anomaly_test -B tools/anomaly_test/build
cmake --build tools/anomaly_test/build
./tools/anomaly_test/build/anomaly_test
```

Result:

- `81 passed, 0 failed`

### Main Finding

The fresh visible-color perf regression is still real, and the strongest
evidence now points at coarse sampled-grid color component fan-out, not at:

- dense persistence / revisit wiring
- target tracking
- a missing selective-refresh reuse in the new persistence code

The key split is:

- `legacy` Red1 replay remains much cheaper
- `fresh-rgba` creates vastly more coarse connected support components to
  examine before dense verification / final retention

Interpretation:

- the fresh visible-color path is too permissive earlier in the color
  distinctness / support / coarse blob formation stages
- the expensive downstream work is being multiplied because too many coarse
  regions survive long enough to be examined

### Best Evidence Collected

#### 1. Reviewed Red1 replay on `legacy`

Command:

```sh
tools/anomaly_test/build_timing/anomaly_video_test \
  app/src/test/resources/vidcap/Red1.mp4 \
  --no-video \
  --app-defaults \
  --app-appearance color \
  --app-motion off \
  --app-saliency off \
  --registration affine \
  --stride 1 \
  --time-start 0.0 \
  --time-end 5.1 \
  --summary-json /tmp/red1_dense_followup_legacy_compare_summary_20260511.json \
  --color-debug-jsonl /tmp/red1_dense_followup_legacy_compare_color_debug_20260511.jsonl \
  -c /tmp/red1_dense_followup_legacy_compare_detections_20260511.csv
```

Headline result:

- frames processed: `153`
- frames with boxes: `21`
- average total frame time: `40.26 ms`
- average sampled-grid prep time: `7.52 ms`
- average color scoring time: `15.05 ms`

#### 2. Reviewed Red1 replay on `fresh-rgba`

Baseline artifact from the earlier validated dense-persist follow-up:

- `/tmp/red1_dense_followup_freshrgba_summary_20260511.json`
- `/tmp/red1_dense_followup_freshrgba_color_debug_20260511.jsonl`
- `/tmp/red1_dense_followup_freshrgba_detections_20260511.csv`

Headline result from that baseline:

- frames processed: `153`
- frames with boxes: `66`
- total box events: `71`
- average total frame time: `79.87 ms`
- average sampled-grid prep time: `22.90 ms`
- average color scoring time: `41.45 ms`

#### 3. Fresh-vs-legacy coarse component fan-out on the same Red1 clip

Derived from:

- `/tmp/red1_dense_followup_legacy_compare_color_debug_20260511.jsonl`
- `/tmp/red1_dense_followup_freshrgba_color_debug_20260511.jsonl`

Average per frame:

- `legacy`
  - `support_seed_count`: `8286.39`
  - `blob_examined_count`: `19.08`
  - `candidate_count`: `0.39`
- `fresh-rgba`
  - `support_seed_count`: `9927.58`
  - `blob_examined_count`: `1157.99`
  - `candidate_count`: `1.46`

This is the strongest current signal.

Important interpretation:

- fresh mode is not just producing slightly more support seeds
- it is exploding those seeds into far more coarse connected support regions
- that explosion is the best current explanation for the large `color_scoring`
  cost increase and part of the `sampled_grid_prep` increase

### Null Experiments Run In This Thread

These were tried, measured, and reverted. Do not repeat them in the next
thread unless there is a materially new rationale.

#### 1. Re-enable fresh-mode selective reuse instead of full fresh sampling

Attempted idea:

- let `fresh-rgba` use the selective refresh mask instead of forcing fresh
  full-grid color sampling

Outcome:

- worse runtime on the reviewed Red1 replay
- worse app-like replay behavior on the first visible-color benchmark cases
- reverted

Measured reviewed Red1 artifact:

- `/tmp/red1_dense_followup_freshrgba_after_fix_summary_20260511.json`
- `/tmp/red1_dense_followup_freshrgba_after_fix_color_debug_20260511.jsonl`
- `/tmp/red1_dense_followup_freshrgba_after_fix_detections_20260511.csv`

Headline result:

- average total frame time: `115.54 ms`
- average sampled-grid prep time: `34.47 ms`
- average color scoring time: `59.84 ms`

Conclusion:

- the fresh-mode perf regression is not simply “we forgot to use selective
  refresh”

#### 2. Fresh-only coarse pre-dense reject for ringy low-quality blobs

Attempted idea:

- reject some obviously diffuse fresh-mode coarse blobs before dense
  verification

Outcome:

- kept reviewed Red1 headline detections stable
- helped one focused Red1 replay somewhat
- did not improve the benchmark-path `Color1` app-like replay enough to justify
  adoption
- reverted

Measured artifacts:

- `/tmp/red1_dense_followup_freshrgba_gatefix_summary_20260511.json`
- `/tmp/red1_dense_followup_freshrgba_gatefix_color_debug_20260511.jsonl`
- `/tmp/red1_dense_followup_freshrgba_gatefix_detections_20260511.csv`
- `/tmp/color1_gatefix_summary_20260511.json`
- `/tmp/color1_gatefix_detections_20260511.csv`

Focused Red1 result:

- average total frame time: `88.36 ms`
- average sampled-grid prep time: `25.60 ms`
- average color scoring time: `45.78 ms`

But app-like `Color1` still remained far too slow:

- average total frame time: `115.15 ms`
- average sampled-grid prep time: `32.03 ms`
- average color scoring time: `58.35 ms`

Conclusion:

- a late coarse reject is not enough by itself

### What The User Requested Next

The user’s proposed next direction makes sense and should guide the next
thread:

1. do not waste downstream work on blobs already larger than the small-scale
   target envelope
2. make the fresh visible-color contrast ratio or distinctness threshold more
   discriminative when too many blobs are being formed
3. adapt that ratio dynamically:
   - if too many coarse blobs are found, increase the ratio / tighten
     distinctness
   - if too few or no blobs are found, decrease it
4. ideally stabilize around only a handful of coarse blobs per full scan
5. it is acceptable to spend extra work on the first frame to establish a good
   initial ratio

### Recommended Next Thread Plan

The next thread should stay narrow and do this in order:

1. Add explicit coarse-component count telemetry as a first-class visible-color
   debug/output value for fresh mode.
   At minimum capture:
   - total coarse components examined
   - coarse components above the small-target envelope
   - retained coarse components that actually reach dense verification
2. Add an early hard resource gate for clearly oversized coarse components.
   This should happen before expensive dense verification work.
3. Add a bounded adaptive fresh-mode contrast / distinctness control loop
   driven by coarse blob count on full scans only.
   Suggested shape:
   - first full scan sets the initial ratio
   - later full scans nudge it gradually
   - clamp the ratio to a safe bounded range
   - aim for roughly `4-8` coarse components, not hundreds
4. Keep this adaptive logic fresh-visible-color only.
   Do not change:
   - `legacy`
   - persistence / revisit logic
   - FFmpeg / bridge / UI wiring

### Important Cautions For The Next Thread

- do not broaden into persistence tuning first; this thread strengthened the
  case that persistence is not the main runtime culprit
- do not repeat the reverted selective-refresh experiment as the next first
  step
- do not treat dense verification itself as the root cause without first
  reducing the coarse component fan-out feeding it
- if a new adaptive-contrast experiment fails focused replay or benchmark
  validation, revert it before ending the thread

### Suggested New-Thread Prompt

Use something close to this:

> Continue from
> [Visible_Color_AD_Next_Thread_Handoff.md](/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Next_Thread_Handoff.md).
> Start from the current dense-persist code state with no additional perf fix
> adopted. Focus next on fresh visible-color coarse blob fan-out. Add coarse
> component telemetry, add an early oversized-component resource gate, and try
> a bounded adaptive fresh contrast/distinctness ratio that tightens when blob
> count is too high and relaxes when blob count is too low. Preserve `legacy`
> behavior and keep persistence / revisit logic unchanged.

## Update From 2026-05-11 Codex Coarse Fan-Out Pass

This thread implemented the currently requested coarse-fan-out work directly in
the live tree:

- added first-class fresh visible-color coarse telemetry to
  `anomaly_debug_color_t` and the replay / bridge outputs:
  - `coarse_component_count`
  - `coarse_oversized_count`
  - `dense_verify_component_count`
  - `adaptive_source_coarse_count`
  - `fresh_distinctness_ratio`
- added a fresh-only early hard gate that rejects clearly oversized coarse
  support components before dense verification work
- added a bounded fresh-only adaptive distinctness ratio on full scans that
  tightens when coarse fan-out is high and relaxes when it is lower
- preserved:
  - `legacy`
  - persistence / revisit logic
  - FFmpeg / bridge / UI wiring

Validation and outcome:

- native validation passed:
  - `tools/anomaly_test/build/anomaly_test`
  - `ctest --output-on-failure`
  - `tools/anomaly_test/build_timing/anomaly_test`
- Kotlin compile guardrail passed:
  - `./gradlew :app:compileDebugKotlin`
- reviewed Red1 fresh trace parity still held:
  - `/tmp/red1_app_fresh_notrace_detections_codex_coarsefanout_v1.csv`
  - `/tmp/red1_app_fresh_trace_detections_codex_coarsefanout_v1.csv`
  - both files hashed to
    `34dde00f63e07060d705bd53c74989cce2cd02454d3f1557cad075a83f46f83e`
- black-hot reviewed regression stayed at the approved checkpoint:
  - `current-detector-baseline`: `TP 22 / 279`, `FP 0 / 92`
  - `dense-full-scan-gold`: `TP 37 / 279`, `FP 0 / 92`
  - `redesigned-incremental`: `TP 23 / 279`, `FP 2 / 92`
  - artifacts:
    - `/private/tmp/regression_main_codex_coarsefanout_v1/suite_report.json`
    - `/private/tmp/regression_main_codex_coarsefanout_v1/suite_report.md`

Useful new telemetry from the reviewed fresh Red1 replay:

- replay artifacts:
  - `/tmp/red1_app_fresh_summary_codex_coarsefanout_v1.json`
  - `/tmp/red1_app_fresh_color_debug_codex_coarsefanout_v1.jsonl`
  - `/tmp/red1_app_legacy_summary_codex_coarsefanout_v1.json`
  - `/tmp/red1_app_legacy_color_debug_codex_coarsefanout_v1.jsonl`
- fresh reviewed Red1 coarse telemetry now showed:
  - average coarse components examined per frame: `23.16`
  - maximum coarse components examined in a frame: `46`
  - average oversized coarse components per frame: `1.74`
  - average coarse components that reached dense verification: `21.42`
  - adaptive ratio range during the replay: `1.40 -> 1.76`
- reviewed fresh target-stage counts in this variant were:
  - `support_map_rejected = 48`
  - `rarity_rejected = 6`
  - `no_candidate = 6`
  - `local_support_rejected = 1`
- within the `no_candidate` target frames, rejection reasons were:
  - `area = 4`
  - `support_mass = 2`

Why this pass must not be adopted as-is:

- this pass did not yet show a reviewed Red1 correctness win that would justify
  adoption:
  - the fresh reviewed target-stage mix still remained mostly on the reject
    side:
    - `support_map_rejected = 48`
    - `rarity_rejected = 6`
    - `no_candidate = 6`
    - `local_support_rejected = 1`
  - this thread did not establish new matched-candidate or winning-box success
- separately, the current visible-color manifest produced a very large
  broadening signal:
  - `/private/tmp/regression_color_codex_coarsefanout_v1/visible-color-baseline/red1-opening-target-track/summary.json`
  - `/private/tmp/regression_color_codex_coarsefanout_v1/visible-color-dense-gold/red1-opening-target-track/summary.json`
  - `/private/tmp/regression_color_codex_coarsefanout_v1/suite_report.json`
  - `/private/tmp/regression_color_codex_coarsefanout_v1/suite_report.md`
  - that baseline run produced `149 / 153` frames with boxes and `210` total
    box events
  - dense gold also failed badly at `132 / 153` frames with boxes and `160`
    total box events
  - timing also regressed to about:
    - average total frame time: `79.44 ms`
    - average color scoring time: `36.72 ms`
    - dense-gold average total frame time: `899.41 ms`
    - dense-gold average color scoring time: `539.44 ms`
- treat that visible-color result as a strong broadening / performance warning,
  not as a trusted “working visible-color correctness test” failure
- because there was no demonstrated reviewed Red1 correctness win and the
  branch also broadened badly in the current color-manifest lane, this pass
  was not ready to adopt as the new checkpoint
- the visible-color perf benchmark was not run after this because the color
  regression had already failed hard enough to make perf follow-on work low
  value

Conclusion:

- do not adopt from this evidence set
- keep the existing approved checkpoint unchanged
- the telemetry itself is valuable, but the fresh adaptive distinctness /
  support shaping in this form appears over-permissive in the current
  visible-color baseline lane

Next-thread recommendation after this failed pass:

1. preserve or reapply the new coarse telemetry fields first; they are useful
2. revert or disable the fresh adaptive distinctness tightening / relaxation
   logic before the next experiment starts
3. if the next thread keeps the oversized coarse gate, retest it without the
   adaptive support-threshold reshaping first
4. do not run a broad suite first next time; start with:
   - reviewed Red1 fresh replay
   - color manifest baseline `red1-opening-target-track`
5. if visible-color baseline broadens again, stop and revert before spending
   time on dense gold or perf

Explicit reminder for the next thread:

- the objective is not “make the current visible-color manifest pass”
- the objective is to improve reviewed Red1 correctness while preserving the
  established black-hot guardrails
- the current visible-color manifest should be used to notice broadening or
  workload explosions, not to substitute for reviewed Red1 correctness

## Update From 2026-05-11 Codex Adaptive Rollback Pass

This follow-up implemented the latest recommendation from the rejected
coarse-fan-out pass:

- kept the new coarse visible-color telemetry fields active
- kept the fresh-only early oversized coarse-component gate active
- removed the fresh adaptive distinctness retuning loop so fresh mode now uses
  a fixed bounded ratio again during the replay

Code state and scope:

- file changed:
  - `app/src/main/cpp/anomaly_analysis.c`
- intentionally preserved:
  - `legacy`
  - persistence / revisit logic
  - FFmpeg / bridge / UI wiring

Validation run:

- native/unit:
  - `cmake --build tools/anomaly_test/build`
  - `tools/anomaly_test/build/anomaly_test`
  - `ctest --output-on-failure` in `tools/anomaly_test/build`
  - `cmake -B tools/anomaly_test/build_timing -DANOMALY_DEBUG_TIMING=ON`
  - `cmake --build tools/anomaly_test/build_timing`
  - `tools/anomaly_test/build_timing/anomaly_test`
- Kotlin compile guardrail:
  - `./gradlew :app:compileDebugKotlin`
- focused reviewed Red1 replay:
  - `tools/anomaly_test/build_timing/anomaly_video_test ... --color-frontend legacy`
  - `tools/anomaly_test/build_timing/anomaly_video_test ... --color-frontend fresh-rgba`
  - trace / no-trace parity rerun for fresh reviewed Red1
- reviewed regression suites:
  - `python3 tools/anomaly_test/run_regression_suite.py --manifest tools/anomaly_test/regression_suite_manifest.json --binary tools/anomaly_test/build_timing/anomaly_video_test --out-dir /private/tmp/regression_main_codex_adaptive_rollback_v1 ...`
  - `python3 tools/anomaly_test/run_regression_suite.py --manifest tools/anomaly_test/regression_suite_color_manifest.json --binary tools/anomaly_test/build_timing/anomaly_video_test --out-dir /private/tmp/regression_color_codex_adaptive_rollback_v1 ...`

Observed result:

- reviewed fresh Red1 trace parity still held:
  - `/tmp/red1_app_fresh_notrace_detections_codex_adaptive_rollback_v1.csv`
  - `/tmp/red1_app_fresh_trace_detections_codex_adaptive_rollback_v1.csv`
  - both hashed to
    `34dde00f63e07060d705bd53c74989cce2cd02454d3f1557cad075a83f46f83e`
- black-hot reviewed guardrails stayed at the approved checkpoint:
  - `current-detector-baseline`: `TP 22 / 279`, `FP 0 / 92`
  - `dense-full-scan-gold`: `TP 37 / 279`, `FP 0 / 92`
  - `redesigned-incremental`: `TP 23 / 279`, `FP 2 / 92`
  - report artifacts:
    - `/private/tmp/regression_main_codex_adaptive_rollback_v1/suite_report.json`
    - `/private/tmp/regression_main_codex_adaptive_rollback_v1/suite_report.md`
- focused reviewed Red1 fresh replay returned to the pre-adaptive headline:
  - `66 / 153` frames with boxes
  - `71` total box events
  - average total frame time about `151.41 ms`
  - average color scoring about `73.85 ms`
  - artifacts:
    - `/tmp/red1_app_fresh_summary_codex_adaptive_rollback_v1.json`
    - `/tmp/red1_app_fresh_color_debug_codex_adaptive_rollback_v1.jsonl`
    - `/tmp/red1_app_legacy_summary_codex_adaptive_rollback_v1.json`
    - `/tmp/red1_app_legacy_color_debug_codex_adaptive_rollback_v1.jsonl`
- coarse telemetry remained useful and confirmed the fresh path is still far
  too permissive even without adaptive retuning:
  - average coarse components examined per frame: `93.45`
  - maximum coarse components examined in a frame: `150`
  - average oversized coarse components per frame: `49.69`
  - average coarse components reaching dense verification per frame: `43.76`
  - fresh distinctness ratio stayed fixed at `1.28`
- the visible-color manifest still broadened badly:
  - baseline `red1-opening-target-track`: `149 / 153` frames with boxes and
    `210` total box events
  - dense gold `red1-opening-target-track`: `136 / 153` frames with boxes and
    `180` total box events
  - timing also remained very poor:
    - baseline average total frame time: `135.93 ms`
    - baseline average color scoring: `64.06 ms`
    - dense-gold average total frame time: `1020.52 ms`
    - dense-gold average color scoring: `594.78 ms`
  - report artifacts:
    - `/private/tmp/regression_color_codex_adaptive_rollback_v1/suite_report.json`
    - `/private/tmp/regression_color_codex_adaptive_rollback_v1/suite_report.md`

Conclusion:

- do not adopt this rollback as a new checkpoint
- this result is still valuable because it isolates the problem further:
  - the rejected adaptive retuning loop was not the sole cause of the
    broadening
  - even with a fixed fresh distinctness ratio, the current fresh support /
    coarse-component formation path still produces far too many blobs
- for the next bounded experiment:
  1. keep the coarse telemetry
  2. keep working before dense verification, not in persistence
  3. focus on earlier fresh support-map / coarse-component shaping so the
     coarse count drops materially before dense verification
  4. continue using reviewed Red1 plus color-baseline `red1-opening-target-track`
     as the first stop/go check before broader validation

## Precision Note For The Next Thread

The latest user clarification is more precise than several earlier summaries in
this file. Future threads should treat this section as the authoritative
statement of the intended detection model.

### What The User Actually Wants

The target design is not merely:

- a coarse sampled-grid scan with bounded spacing
- followed by dense verification only around surviving coarse candidates

The user wants a denser pixel-first blob construction pass for any region that
is newly scanned or can no longer be trusted from affine-registration carry-
forward.

In plain terms:

1. when a region is new, unlocked, or due for periodic refresh, inspect that
   region densely enough to determine real blob extents rather than sampled-
   grid approximations
2. form blobs directly from high-contrast pixel transitions / small connected
   pixel runs
3. merge row-to-row blob fragments when their extents / centroid indicate they
   are the same small target
4. reject any blob immediately if:
   - it is not sufficiently unique, or
   - its merged extent grows beyond the small-target envelope
5. keep only a very small set of the smallest, most unique blobs per frame
   (roughly `4-6`, not hundreds)
6. once a blob is accepted as a candidate, use affine registration to revisit
   that candidate neighborhood in subsequent locked frames instead of
   rescanning the rest of the frame

### Important Current Mismatch

The current implementation does not yet match that intended model.

What the code does today:

- full refresh means a full sampled-grid refresh of the ROI
- sampled spacing is capped so it is no coarser than about half the configured
  small-target span
- dense pixel work happens only after a coarse support component survives long
  enough to reach dense verification

What the user means by the desired design:

- newly scanned / unlocked regions should get blob formation from dense local
  pixel evidence first
- candidate extents should be real extents from the start, not coarse-grid
  bounding boxes refined only later
- affine-registration revisit should be the optimization, not coarse sampled
  blob formation

### Consequence For Future Work

Do not let future thread summaries blur these two architectures together.

If a future change still starts from coarse sampled-grid component formation,
describe it accurately as:

- a coarse-first detector with dense local verification

Do not describe that as if it already implements the user's intended dense
pixel-first blob construction model.

### Recommended Next Implementation Direction

If the next thread wants to align with the clarified user intent while keeping
the existing FFmpeg / bridge / revisit framework intact, it should explore a
bounded detector-core rewrite along these lines:

1. dense scanline or dense local connected-component construction for freshly
   scanned regions only
2. immediate uniqueness and oversize rejection during blob growth / merge, not
   after broad coarse fan-out
3. retention of only the smallest handful of unique blobs
4. affine-registration-driven candidate revisit on later locked frames using
   stored centroid / extent / color characteristics

This is a detector-architecture clarification, not yet an adopted code change.

## 2026-05-11 Winner-Gate Probe

This thread followed the newer reviewer guidance to keep the architecture
distinction sharp and to treat accepted-winner gating as the next bounded seam,
not as proof that the detector already implements the intended dense pixel-
first fresh-region model.

What changed in code:

- preserved the current bounded dense-verifier / peak-seed work in
  `app/src/main/cpp/anomaly_analysis.c`
- preserved the fresh-RGBA sparse-impostor regression in
  `tools/anomaly_test/test_anomaly.c`
- added fresh-mode winner-gate telemetry for:
  - raw accepted-candidate index before gating
  - post-gate accepted winner index
  - size-envelope ratios
  - scene-commonness / rarity metrics
  - explicit winner-gate rejection reason and thresholds
- added a fresh-mode accepted-winner rejection pass on two axes:
  - dense candidate size / span versus the configured small-target envelope
  - dense candidate rarity / commonness versus the surrounding scene

Validation run in this thread:

- `cmake --build tools/anomaly_test/build`
- `./tools/anomaly_test/build/anomaly_test`
- `./tools/anomaly_test/build/anomaly_video_test app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --app-motion off --app-saliency off --registration affine --stride 1 --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_winner_gate_summary_20260511.json --color-debug-jsonl /tmp/red1_winner_gate_color_debug_20260511.jsonl -c /tmp/red1_winner_gate_detections_20260511.csv`

Measured outcome:

- unit suite stayed green: `83 passed, 0 failed`
- fresh reviewed Red1 replay collapsed from the earlier peak-seed baseline
  `71` box events to `0`
- replay mode mix also changed materially:
  - earlier peak-seed baseline: `full 39 / partial 28 / target-only 86`
  - this winner-gate probe: `full 39 / partial 96 / target-only 18`

How to interpret this:

- the pass did what it was supposed to do directionally:
  - accepted fresh winners now expose the exact size/commonness telemetry
    needed for the next stop/go check
  - the harness no longer emits fresh reviewed replay boxes at the reviewer’s
    cited failure windows
- but this is not yet adoption-ready:
  - the harness already did not reproduce those exact app-review timestamps
    one-for-one before the change, so that specific stop/go check is still
    app-side evidence first and harness evidence second
  - collapsing the whole reviewed fresh replay to `0` boxes is too aggressive
    to treat as a clean correctness win

What the next thread should do from here:

1. start from `/tmp/red1_winner_gate_color_debug_20260511.jsonl` and compare it
   directly against `/private/tmp/red1_dense_peak_seed_color_debug_20260511.jsonl`
2. keep the new winner-gate telemetry, but retune only the fresh accepted-
   winner commonness gate so it stops the `3.439s` and `4.598s` reviewer
   failures without blanketing the whole replay to zero boxes
3. do not describe the current code as if it already performs dense pixel-
   first blob formation in fresh/unlocked regions; it is still a coarse-first
   detector with dense local verification

## 2026-05-11 Small-Dominates Follow-On Packet

This packet supersedes the narrower “retune only the commonness gate” wording
above.

The user clarified the operator intent more sharply:

- smallness must dominate winner selection
- if a blob is large enough that a human pilot can easily see it on the
  controller screen, that blob is not the detector’s target use case
- uniqueness matters most when comparing small stand-outs, not as an excuse
  for large obvious blobs to win

What changed after that clarification:

- added an earlier small-target priority scale inside
  `app/src/main/cpp/anomaly_analysis.c`
- that scale now affects:
  - dense color candidate `quality`
  - dense color candidate `final_score`
  - dense color candidate `retention_rank`
- the intention was to make compact unique blobs win earlier, before the
  later accepted-winner gate has to rescue the result

Latest validation from this pass:

- `cmake --build tools/anomaly_test/build`
- `./tools/anomaly_test/build/anomaly_test`
- `./tools/anomaly_test/build/anomaly_video_test app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --app-motion off --app-saliency off --registration affine --stride 1 --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_small_dominates_summary_20260511.json --color-debug-jsonl /tmp/red1_small_dominates_color_debug_20260511.jsonl -c /tmp/red1_small_dominates_detections_20260511.csv`

Observed result:

- unit suite still green: `83 passed, 0 failed`
- reviewed fresh Red1 replay still stayed at `0` boxes
- replay mode mix stayed at:
  - `full 39 / partial 96 / target-only 18`

Interpretation:

- the new small-dominates scoring likely helps in the intended direction
- but the currently layered hard fresh winner gate is still masking whether
  that scoring change alone is enough
- the current tree therefore does not yet answer the important question:
  “If smallness dominates earlier, can we relax the hard winner gate and still
  suppress the big homogeneous false-positive blob?”

Critical evidence for the next thread:

- the new duplicate review false positives near `x≈51%`, `y≈58–59%` line up
  with the same large accepted raw winner in the pre-gate replay around:
  - `4.734s`
  - `4.934s`
  - `5.067s`
- that nuisance blob was not winning because it was more unique than the true
  red target
- direct comparison from this thread:
  - nuisance blob around `4.734s` and `4.934s`:
    - `hist_rarity_score ≈ 0.000613 - 0.000629`
    - area about `7.22 - 7.67`
    - span about `3.67 - 4.0`
  - true target nearby in the fresh target-trace replay:
    - `4.667s rarity = 0.002674`
    - `4.867s rarity = 0.002915`
- conclusion:
  - the nuisance blob is not a “more unique pixel should win” case
  - it is a “large obvious blob must lose decisively” case

Follow-on thread instructions:

1. start from the current worktree state, not from the older dense-peak-seed
   baseline
2. treat `small dominates` as the primary design rule for accepted fresh
   winners
3. preserve the new telemetry fields:
   - raw candidate index
   - winner-gate reason
   - small-target span/area ratios
   - scene commonness
4. keep the earlier small-dominates scoring change in place initially
5. then run the main experiment:
   - soften or temporarily relax the hard fresh winner gate
   - keep the new size-dominant scoring active
   - see whether the large `51%, 58-59%` homogeneous blob still loses
6. only if that nuisance blob starts winning again, reintroduce the lightest
   possible size-conditioned accepted-winner penalty needed to stop it
7. do not start by globally raising the uniqueness threshold
8. do not frame the next change as “pick the most unique pixel in the blob”
   because the current candidate rarity is already tied to the peak sample’s
   histogram family

Specific stop/go checks for the next thread:

- stop/go check 1:
  - the duplicate false positives near `4.632s`, `4.668s`, `4.766s`,
    `4.807s`, `4.832s`, `4.855s`, `4.900s`, `4.933s`, `4.972s`
    around `x≈51%`, `y≈58–59%` should not reappear as accepted winners
- stop/go check 2:
  - the replay must not stay trivially at `0` boxes unless there is strong
    evidence that every former winner was truly oversized/common and that the
    target path is correspondingly cleaner
- stop/go check 3:
  - if boxes reappear, prefer smaller winners with materially better rarity
    than the large nuisance blob

Artifacts to read first in the next thread:

- `/tmp/red1_small_dominates_color_debug_20260511.jsonl`
- `/tmp/red1_small_dominates_summary_20260511.json`
- `/tmp/red1_winner_gate_color_debug_20260511.jsonl`
- `/tmp/red1_target_freshrgba_color_debug.jsonl`
- `/private/tmp/red1_dense_peak_seed_color_debug_20260511.jsonl`
- `app/src/test/resources/vidcap/Red1.review.json`

Required validation commands for the next thread:

- `cmake --build tools/anomaly_test/build`
- `./tools/anomaly_test/build/anomaly_test`
- reviewed fresh Red1 replay with:
  - `./tools/anomaly_test/build/anomaly_video_test app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --app-motion off --app-saliency off --registration affine --stride 1 --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/<new_summary>.json --color-debug-jsonl /tmp/<new_color_debug>.jsonl -c /tmp/<new_detections>.csv`

How to describe the checkpoint honestly:

- current code is still a coarse-first detector with dense local verification
- this thread did not finish the broader dense pixel-first architecture change
- this thread only sharpened two bounded mechanisms:
  - accepted-winner telemetry
  - size-dominant fresh candidate scoring
