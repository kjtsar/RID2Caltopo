# R2CAD Person Relevance Implementation Plan

## Objective

Add person recognition as positive semantic evidence for candidate targets from
Target Colors, Color Outlier, and Infrared. Person Relevance is not a fourth
appearance detector and must not replace, reject, or weaken existing detector
evidence.

The implementation must preserve realtime decode and render behavior. Person
inference runs asynchronously, receives bounded best-effort work, and may be
skipped whenever the app lacks runtime headroom.

## Acceptance Principles

1. Disabled Person Relevance is byte-for-byte behaviorally identical at the
   candidate, track, raw ROI, and app-visible ROI boundaries.
2. Missing, stale, failed, or low-confidence person results are neutral.
3. Person confidence can only add a bounded relevance bonus during the first
   active phase. It cannot reject a target or shorten its lifetime.
4. Decode, AD, render, and UI threads never wait for Person Relevance.
5. Work is capacity-one/latest-wins, top-K bounded, and declined whenever AD
   runtime pressure is not normal.
6. Model runtime, weights, training data, identity, and license provenance are
   recorded independently.
7. Correctness and performance are qualified independently: candidate recall,
   candidate-conditioned model recall, and end-to-end/app-visible recall must
   not be conflated.

## Runtime Flow

```text
decode -> AD input queue -> AD worker -> render queue -> render thread
                              |
                              +-> copied top-K candidate/track descriptors
                                  + referenced frame
                                  -> capacity-one Person worker
                                  -> versioned relevance snapshot
                                  -> future AD frames consume fresh evidence
```

The AD worker snapshots up to two emitted Color/IR target ROIs after target
tracking and result assembly. Track identity and detector provenance are
resolved only when Person Relevance is active, then copied as plain values;
the Person worker never receives `anomaly_state_t` or holds the anomaly lock.

The Person worker validates session generation, work sequence, source age,
track ID, and geometry revision before inference and publication. Results are
consumed only by a later AD frame. Decode, AD admission, render, and UI never
make or wait on a JNI inference call; the dedicated Person worker may call the
supported Android LiteRT adapter synchronously on its own thread.

## Packet Ledger

### PR-1: Backend-Neutral Relevance Contract

Owner: Person module child.

Add allocation-free candidate, model identity, decision, temporal evidence,
and positive-only fusion contracts. Provide a deterministic injected backend
for host tests. Wire both Android and host CMake targets.

Acceptance:

- Disabled and shadow modes preserve all existing observation output.
- Invalid/low results are neutral.
- Positive evidence is bounded and associated once with the correct target.
- Target Color and Color Outlier overlap cannot double-count one result.

### PR-2: Bounded Person Worker And Queue Coordination

Owner: runtime child after PR-1 acceptance.

Add one Person worker per rendered FFmpeg session, a capacity-one pending
mailbox, one in-flight batch, top-K changed-track selection, pressure admission,
staleness checks, teardown, and native runtime counters.

Acceptance:

- Offers use try-lock and return immediately.
- Latest work replaces pending work; no queue growth is possible.
- Person work is never offered from inline decode fallback.
- Non-normal AD pressure declines Person work.
- Shutdown joins only during session teardown and releases all frame refs.

### PR-3: Model Backend And Provenance

Owner: model child after PR-1 and PR-2 contracts stabilize.

Integrate an Apache-compatible on-device runtime behind the backend interface.
Start with a small COCO person-capable model for pipeline qualification; aerial
SAR suitability must be proven separately. Package model/runtime notices and
record model name, version, SHA-256, tensor contract, quantization, threshold,
runtime version, and accelerator.

Acceptance:

- Model initialization never occurs on decode, render, or UI threads.
- CPU fallback and initialization/inference failure are non-fatal.
- Candidate crops include bounded context and preserve source geometry.
- Model results are reproducible from an exported evidence bundle.

### PR-4: Review And Feedback Evidence

Owner: feedback child with disjoint Kotlin/review ownership.

Extend the existing local playback review schema rather than adding another
feedback system. Preserve point annotations while adding optional normalized
boxes and Person Relevance/model metadata. Feedback remains local unless the
operator explicitly exports it.

Acceptance:

- Existing schema-v1 review files still parse identically.
- Person, Not Person/False Positive, Missed Target, and Unsure evidence can be
  represented.
- Export omits incident location and full video unless explicitly added later.

### PR-5: Qualification And Release Gates

Owner: qualification child after PR-1 telemetry is stable.

Add paired OFF/SHADOW app-parity runs, deterministic fake-backend fixtures,
review scoring, and a same-run performance report under `tools/anomaly_test`.
Wire `personRelevanceQualification` into `releaseVerification`.

Required metrics:

- upstream candidate recall for labeled people
- candidate-conditioned person recall
- end-to-end and app-visible person recall
- reviewed false positives and unmatched accepted boxes per minute
- first-person-relevance latency
- offered, dropped, replaced, stale, evaluated, and published work
- inference, queue-age, total-frame average/p95/max, and realtime factor

Initial plumbing gates:

- OFF and SHADOW detector/app-visible signatures are identical.
- Fake-backend decisions exactly match fixtures.
- Required model identity fields are present.
- Median total-frame average is at most 1.10x paired OFF.
- Median total-frame p95 is at most 1.20x paired OFF.
- Every qualified case remains at least 1.0x realtime.

Production-model quality thresholds are set only after a reviewed aerial corpus
has nonzero denominators and the first model shootout is complete.

## Validation Ladder

```bash
git diff --check
cmake --build tools/anomaly_test/build_timing
ctest --test-dir tools/anomaly_test/build_timing --output-on-failure
tools/anomaly_test/build_timing/anomaly_test
python3 tools/anomaly_test/test_person_relevance_qualification.py
./gradlew :app:personRelevanceQualification
./gradlew :app:testDebugUnitTest
./gradlew :app:releaseCheck
```

Broader app-parity Color and IR replay plus the existing visible-color host
performance matrix are mandatory before Person Relevance becomes active by
default or affects operator-visible ROI weights.

## Promotion Stages

1. `OFF`: exact baseline identity.
2. `SHADOW`: asynchronous inference and evidence export, no scoring effect.
3. `POSITIVE_ONLY`: bounded relevance bonus with no negative evidence.
4. `QUALIFIED`: eligible for default enablement only after reviewed aerial and
   on-device S25U evidence passes adopted quality, latency, heat, and playback
   gates.

Every model promotion is explicit, versioned, and reversible. On-device
automatic retraining is out of scope.

## Implementation Status - 2026-07-13

Packets PR-1 through PR-5 are implemented and host-validated. The operator
control is `Off`, `Evaluate`, or experimental `Assist`, with `Off` as the
default. `Evaluate` records evidence without changing ROI relevance; `Assist`
can add only the bounded positive bonus.

The unchanged-HEAD and modified-tree native Color qualification executables
are byte-for-byte identical when built with the same native arm64 CMake 3.22
toolchain. An older workspace `build_perf` cache was x86_64 and produced
misleading translated timing results; it is not valid comparison evidence.
Detection output remained identical and both native arm64 qualification runs
passed. Handset/aerial-corpus validation is still required before promotion
beyond experimental `Assist`.
