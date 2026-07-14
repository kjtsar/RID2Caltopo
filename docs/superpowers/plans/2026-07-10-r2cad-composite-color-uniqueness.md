# R2C Anomaly Detector Composite Color Uniqueness Plan

## Objective

Improve visible-color candidate ranking by scoring the predominant color of a
coherent blob against the scene outside that blob, its local background ring,
and its motion-aligned recent history.

The existing uniqueness-first detector remains authoritative until reviewed
video evidence supports promotion of the composite score.

## Behavioral Contract

- Preserve existing color sampling, support-map formation, blob extraction,
  candidate publication, and target-color behavior while the new score is in
  shadow mode.
- Compute new evidence only for already-extracted color candidates.
- Do not let a candidate inflate its own background commonness: subtract its
  color contribution from scene counts before computing background rarity.
- Prefer coherent predominant color over a rare peak cell by measuring blob
  purity and color entropy.
- Compare the blob color distribution with a surrounding ring to capture local
  distinctness.
- Down-weight near-neutral chroma whose hue is unstable.
- Apply temporal consistency only after motion registration is trustworthy;
  never teach the recent background model with the tracked candidate footprint.
- Preserve the separate, explicitly gated target-color detector path.

## Proposed Shadow Score

The initial composite is deliberately factored rather than trained:

```text
composite uniqueness =
    candidate-excluded background rarity
  * predominant-color purity
  * local-ring divergence
  * chroma reliability
  * temporal consistency
```

Each component and the composite must be exported independently so reviewed
failures can be attributed to one term rather than hidden in a single number.

## Packet Ledger

### Packet 0: Frozen Baseline

- Capture Red1, Red2, and available Floristan app-parity outputs.
- Preserve app-visible detection signatures, reviewed precision/recall, current
  candidate ordering, stage timing, and full-scan tails.

### Packet A: Blob Signature and Candidate-Excluded Rarity

- Add fixed-size per-candidate UV histogram/signature helpers.
- Measure predominant family, dominant share, normalized entropy, and
  candidate-excluded scene rarity.
- Cover histogram saturation, candidate subtraction, multi-bin coherent color,
  and mixed-color blobs with native tests.

### Packet B: Local and Reliability Evidence

- Build a local background-ring signature around each extracted candidate.
- Add bounded histogram divergence and chroma-reliability helpers.
- Ensure frame edges, sparse rings, neutral blobs, and compression-scale color
  shifts have deterministic behavior.

### Packet C: Shadow Integration

- Compute all components for existing color candidates only.
- Export component and composite values through native result/debug telemetry
  and `anomaly_video_test` JSONL.
- Do not change `color_uniqueness_rank`, candidate comparison, winner gates, or
  published detections.

### Packet D: Temporal Signature Consistency

- Associate shadow signatures with motion-aligned candidate tracks only when
  registration health is trustworthy.
- Reward stable color signatures without adding tracked footprints to the
  recent background reference.
- Reset or neutralize temporal evidence on scene discontinuity or registration
  degradation.

### Packet E: Evaluation

- Compare authoritative and shadow candidate rankings on reviewed clips.
- Report winner agreement, positive rank lift, negative rank lift, first-hit
  latency, false positives per minute, and component attribution.
- Report scoring overhead separately for full and non-full rescan modes.

## Integration Decision

Exact dense-pixel membership exists only inside `verify_dense_color_component()`
and is discarded after the component is summarized. Capture shadow evidence at
that seam rather than replaying flood fill after extraction:

- mark exact accepted dense pixels while the verifier's bounded membership
  buffers are live
- build a dense blob signature and exact nonmember ring signature there
- carry fixed-size shadow evidence through the extracted candidate
- build a separate sampled-grid candidate contribution for scene subtraction
- compute the composite only after extraction and NMS have finalized candidates

Dense-pixel counts must never be subtracted from sampled-grid scene counts. The
shadow path therefore uses separate wide `uint32_t` current/recent scene
histograms; the authoritative saturated `uint8_t` histogram remains unchanged.

Target-centered hard-rescue candidates that bypass dense verification initially
receive invalid/neutral shadow evidence instead of a fabricated footprint.

## Temporal Decision

Temporal shadow evidence reuses production color target slots only as a
motion-aligned positional reference; it cannot create, hold, score, or publish a
track. Candidate association occurs after registration prediction and requires
`ANOMALY_REG_HEALTH_HEALTHY`.

- newly allocated color tracks seed their shadow signature after production
  target-track update through a strict one-to-one positional rematch
- signatures remain valid across at most 60 healthy source frames so normal app
  stride does not erase recent color evidence
- scene discontinuity or any non-healthy registration invalidates immediately
- unmatched, stale, rescue-only, and invalid evidence contributes neutral
  temporal consistency `1.0` to the multiplicative shadow composite
- tracked signatures are candidate history only and are never added to the
  scene-background histogram

## Frozen Baseline

The pre-change evidence is stored outside the worktree under
`/private/tmp/r2cad-uniqueness-baseline`:

- app-parity Red1 and Red2 detection CSVs, summaries, and color-candidate JSONL
- the complete tracked color regression suite and profile reports
- the existing Gradle color-realtime qualification report

Observed app-parity debug runs were `5.11x` realtime for Red1 and `5.76x` for
Red2, with full-scan maxima near `43 ms`. The tracked app-dense-stride profile
remained above realtime. Dense-gold remains diagnostic-only and confirms that
candidate-only shadow scoring must stay small relative to seed scoring.

The baseline also revealed that authoritative `color_uniqueness_rank` is the
primary comparator key but is not currently exported in color JSONL. Packet C
must export both that existing rank and the independent shadow components.

## Promotion Gate

The composite score remains shadow-only unless all of the following hold:

- no unexplained loss of reviewed positive detections
- no regression in reviewed precision
- measurable positive rank lift or earlier first hit on more than one fixture
- no candidate-order instability caused by tiny floating-point differences
- Color AD remains above the realtime qualification threshold
- full-scan long-frame gates remain green
- native tests, harness regression, app-parity comparison, and
  `./gradlew :app:releaseCheck` pass

Promotion, if justified, should use score bands or an epsilon tie policy rather
than allowing insignificant composite-score differences to dominate all other
candidate evidence.

## Outcome

The implementation is accepted as shadow-only instrumentation. It is not
promoted into authoritative Color AD ranking.

Tracked Red1/Red2 regression evidence:

- valid shadow evidence for 2,030 of 2,321 extracted candidates (`87.5%`)
- production/shadow winner agreement on complete frames: 206/358 (`57.5%`)
- reviewed positive rank-lift mean: `-0.048`, median `0`
- one useful Red2 opportunity at `4.401s`: reviewed target moved from production
  rank 4 to shadow rank 1, mainly from chroma reliability and excluded rarity
- no reviewed negative candidate was close enough to support a valid negative
  rank comparison

App-dense-stride evidence remained limited because most reviewed timestamps are
stride-skipped and therefore have no extracted candidate at the `0.05s`
comparison window. On frames with complete evidence, winner agreement was 9/10.

Floristan diagnostic evidence:

- the normal app-stride profile still produced no reviewed-target candidates
- the all-frame diagnostic produced candidates elsewhere but none close enough
  to the reviewed person track for a valid rank comparison
- ranking changes therefore cannot rescue Floristan until candidate formation
  supplies a target candidate

Performance and regression acceptance:

- all six authoritative Red1/Red2 profile detection CSVs are byte-identical to
  the frozen baseline
- native suite: 5,195 passed, 0 failed
- CTest: 5/5 passed
- shadow parser/analyzer tests: 9 passed
- optimized qualification: Red1 `6.36x`, Red2 `6.98x` realtime; median full
  tails near `40 ms`; reviewed precision/recall unchanged
- `./gradlew :app:releaseCheck`: passed, including release assembly and
  Crashlytics mapping/native-symbol uploads

The next evidence-driven iteration should improve extraction coverage on hard
moving-drone footage and add reviewed negative candidate examples. Composite
weight tuning is not justified from the present dataset because it has one
strong positive opportunity, near-zero aggregate positive lift, and no valid
negative rank comparisons.
