# RID2Caltopo Anomaly Detector (R2CAD) Overview

R2CAD is architected as a standalone module implemented in standard C, with
stdlib and pthreads as its portability baseline.

As a standalone module, R2CAD must be verifiable and improvable on the build
host without depending on the Android application, FFmpeg, JNI, Kotlin, or the
rendering environment. That verification must cover both algorithm correctness
and the threaded realtime runtime, because high-performance realtime detection
on the Android device is central to the module's purpose.

## Architecture

R2CAD should expose one implementation that can be used in two related ways:

1. Deterministic frame-by-frame operation for algorithm and replay tests.
2. Realtime threaded operation for production-style performance, state
   ownership, synchronization, queue pressure, and shutdown/reset tests.

These should not become divergent implementations. Frame-by-frame operation
should be achieved by limiting the supplied input frames and by polling or
draining outputs without requiring detector push-back. The same internal
pipeline should process an ordered frame/config stream and produce deterministic
detection results, making it the simplest way to validate Motion Estimation
(ME), Color, IR, Shape, fusion, tracking, and false-positive rejection behavior.

The threaded runtime should own the ME and Anomaly Detection (AD) worker
threads, bounded internal queues, frame admission policy, queue-pressure
degradation, runtime state synchronization, and result publication. The
internal implementation should be designed to run full speed in either Thorough
or Cursory mode; the adapter controls how many frames are supplied and how
quickly results are consumed. Android and FFmpeg should act as
producer/consumer adapters around this runtime rather than being the only place
where detector concurrency exists.

Conceptually:

```text
Adapter -> R2CAD Runtime -> ME Thread -> Evidence Queue -> Detector Thread -> Result Queue -> Adapter
```

The deployment adapter owns decoded-frame production, Android surfaces, FFmpeg
lifecycles, render queues, JNI/Kotlin config persistence, and application-level
enable/disable policy. R2CAD owns the detector pipeline's realtime execution
model.

## Current Implementation Checkpoint

The current Android live path already has separate decode, AD worker, and render
thread surfaces:

```text
FFmpeg decode -> AD input queue -> AD worker -> render queue -> render thread
```

This means the next modularization work should not proceed as though detector
threading is absent. The immediate direction is to move the policy and runtime
contracts behind focused, host-testable C modules while preserving the existing
thread ownership and behavior.

Current runtime extraction status:

- `anomaly_runtime_budget.{h,c}` owns the first standalone Cursory/Thorough
  runtime budget contract and the pure render-queue trim keep-latest
  calculation, render queue hard-cap sizing arithmetic, and bounded target
  latency calculation. It also owns the source-interval estimate update
  arithmetic and confidence increment used by decode-cadence and PTS-cadence
  sampling, plus the asymmetric stall-estimate update arithmetic used by
  inter-burst gap observations and the proven-gap update arithmetic used to
  retain large confirmed gaps. It also owns the shared decay-toward-floor
  arithmetic used by stale stall/proven-gap estimates and the desired render
  interval proportional/smoothing calculation. It also owns the pure current
  render interval fallback/clamp helper, FPS-to-interval conversion, PTS
  interval-from-span arithmetic, buffered-span conversion, local-file playback
  target-interval selection, and local-file playback PTS normalization/repair
  arithmetic used by the adapter. It also owns the local-playback timing
  history ring-index selection and span-validity predicate used for debug
  timing snapshots, plus the local-playback frame-history replay slot
  selection arithmetic, local-playback step-control decisions, and the shared
  local-playback ring append next/count arithmetic. It also owns the
  local-playback pause/step advance-consumption decision, render startup
  observation/finalize decision, render lag/budget/log-throttle decision, and
  the pure render due-time advance/skip arithmetic used after a frame dequeue,
  plus the shared render/AD queue
  tail-index, offset-index, pop-state, and trim-state arithmetic. It also owns
  the pure render queue storage-capacity selection arithmetic. Render queue
  storage, allocation, mutation, PTS queue
  traversal/monotonicity validation, stall/gap measurement, cadence sample
  collection, local-playback timing sample storage/output, local-playback
  frame-history storage/cloning, JNI pause/step state application, render
  signaling, PTS relock guards, and mutable stall-active state remain
  adapter-owned, while the pure decode-delta
  gap/plausible-cadence classifiers and decode-stall predicate used by those
  guards now live in the runtime budget contract. FFmpeg stream probing,
  local-file fast paths, repair counters, logging, and `AVFrame` ownership
  remain adapter-owned.
- `anomaly_runtime_pressure.{h,c}` owns the live AD queue-pressure thresholds,
  explicit pressure-policy construction, pressure-mode selection, recovery
  hysteresis, per-frame bypass decisions, and pressure mode names. It also owns
  the pure backlog-to-frame-capacity helper used by the local-file AD sidecar
  queue budget and the pure oldest-frame drop count used by local sidecar frame
  admission. The AD input queue storage-capacity calculation is also a pure
  runtime pressure helper, while allocation and `AVFrame` ownership remain in
  the adapter.
- `anomaly_runtime_handoff.{h,c}` owns the pure metadata decision that says
  whether a dequeued AD worker frame should be analyzed or forwarded without
  analysis because processing is disabled, the generation is stale, pressure is
  bypassing work, or the frame metadata is invalid.
- `ffmpeg_bridge.c` still owns Android/FFmpeg adapter concerns: decoded-frame
  production, queue storage, pthread synchronization, AVFrame ownership,
  render forwarding, native session lifecycle, and live pressure fallback
  logging.
- Motion Estimation is implemented as a focused native module, but it still
  runs synchronously inside `anomaly_process_frame()` on the AD worker path.
  A future standalone R2CAD runtime may add a separate ME worker/evidence
  queue, but that split should be introduced only after the contract,
  ownership, and host-thread tests are explicit.

## Inputs And Outputs

Input to R2CAD is a stream or bounded queue of decoded video frames, potentially
delivered in realtime. If time permits, R2CAD returns Region Of Interest (ROI)
annotations and detector telemetry for those frames.

Optional runtime input includes render/backlog telemetry from the adapter. This
telemetry should be passed as an explicit budget or cadence signal, not read
implicitly from Android or FFmpeg state. Useful values include frame deadline,
render backlog, target latency, startup state, and adapter pressure mode.

R2CAD output should separate:

- stable detections and ROI annotations,
- ME evidence and detector health summaries,
- optional debug/telemetry payloads for host harnesses and diagnostics,
- adapter-owned overlay or display side effects.

Frame buffers retained for stepping forward and backward through ROI-marked
frames are an app/debugging concern. They help a human reviewer inspect results
until the detector can evaluate those ROI decisions automatically, but they
should not be required by the standalone R2CAD core.

## Realtime Runtime Policy

During startup and realtime playback, the render path may consume a frame before
ME or AD can process it. When this happens, the ME and AD workers should suspend
work on stale frames and skip to the next eligible frame.

During startup, the ME/AD pair should skip the first default 0.25 seconds of
frames to ensure smooth start. After startup, the runtime should monitor adapter
telemetry and adjust its processing mode to preserve realtime playback:

- If render backlog drops below 0.25 seconds of frames, ME and AD should switch
  to Cursory processing mode.
- If render backlog reaches 0.5 seconds or more, ME and AD may switch to
  Thorough processing mode.
- Up to N frames of backlog, defaulting to at least 0.5 seconds, is acceptable
  when it improves detection without threatening playback.

In both modes, the internal R2CAD pipeline should run as fast as it can. Cursory
and Thorough modes should choose different analysis depth, reuse strategies, or
search breadth; they should not depend on deliberately slowing the worker
threads.

These thresholds should be runtime configuration values and should be validated
on the host harness with synthetic timing, queue pressure, reset, and shutdown
tests before relying on Android-device behavior as proof.

## Motion Estimation

The Movement Estimator thread examines each incoming frame and produces motion
evidence relative to the preceding frame. Today this evidence is a global
movement vector comprised of rotation, translation, and scaling factors.

The target architecture should also support 3D/parallax-aware motion evidence.
The PowerHouse samples demonstrate that parallax can be substantial enough that
a single global movement vector is insufficient for all detector decisions.

In Thorough processing mode, ME should eventually support an option to identify
up to N movement regions, default 2, that differ from the global movement vector
or the dominant parallax model. This work is dependent on available processing
time and should degrade gracefully in Cursory mode. The resulting evidence
should be reusable by all detector threads so they can track target ROIs
efficiently over time without recomputing movement independently.

## Detector Threads

The next stage in the R2CAD runtime is a detector thread. Today the runtime has
one detector thread that runs either Color or IR detection. Future runtime
versions may run multiple detector threads in parallel, including Shape, which
would look for shapes that are unique within the current or recent frames.

All detector threads require the ME output to predict and maintain target ROI
locations over time. Motion and Shape should be additive evidence channels,
while Color and IR may remain modal or selectively enabled depending on the
video source and operator configuration.

## Host Verification

The vidcap sample videos include annotated IR and Color clips that can be used
to improve ME and AD behavior. The host harness should apply those videos to
both deterministic frame-limited runs and realtime threaded runtime runs,
extracting ROI-annotated results and structured telemetry so Codex can
iteratively improve detection behavior.

The goal is to improve ME, IR, and Color detections while reducing or
eliminating false positives as much as possible. Validation should include:

- deterministic frame-by-frame algorithm regression,
- threaded runtime queue-pressure and synchronization tests,
- startup, reset, config-change, and shutdown race tests,
- realtime-factor and processing-latency measurements,
- precision/recall and false-positive review on annotated clips,
- app-parity runs that match the Android deployment configuration.

## Behavior Change Qualification

Any future R2CAD functionality, behavior, scoring, configuration, pacing,
threading, queueing, or detector-ranking change must be qualified with both
functionality and performance regressions before it is considered complete.
Focused helper tests are useful for proving a contract, but they are not enough
to claim detector readiness or app parity.

The qualification packet for a behavior change should include:

- a focused red/green host test that captures the intended contract or
  regression before production behavior is changed,
- deterministic native C tests and CTest coverage for the touched runtime or
  detector contract,
- app-local or app-parity harness runs using the same effective configuration
  as the Android playback path, including `--app-defaults` and
  `--app-display-output` when the Android path is the target,
- reviewed precision, recall, track-match, false-positive, realtime-factor, and
  processing-latency comparisons against the relevant baseline clips,
- focused Gradle configuration/runtime tests when Kotlin, JNI config mapping,
  app defaults, pressure policy, or playback behavior is touched,
- a final broad gate such as `:app:releaseCheck` when the change is intended to
  land in the application.

For app-local captured playback, qualification must include the reviewed
PowerHouse IR clips and the PowerHouseTeam false-positive sweep or its current
successor. A change that improves recall but materially worsens false positives,
track stability, playback smoothness, or runtime should remain unqualified until
the tradeoff is explicit and accepted.

## Near-Term Detector Priorities

The next detector behavior changes should stay concentrated on the seams that
most directly affect operator value:

- improve Movement Estimation so parallax, local background movement, and camera
  motion do not become misleading Motion evidence,
- improve reacquisition and persistence of already-identified targets so Color
  and IR detectors keep useful boxes on known targets instead of repeatedly
  rediscovering or dropping them,
- reduce false positives in both Color and IR modes, especially movement-only or
  weak single-frame candidates that reach the app-visible overlay without stable
  supporting evidence.

Each of these changes should be evaluated in the same qualification packet that
will be used for release decisions, not only in a synthetic helper harness. The
host harness should remain the primary closed-loop workflow so regressions can
be diagnosed without depending on manual device playback or user-supplied logs.

Backing each ROI is a collection of scores accumulated for a target in a given
location over time.  Primary scores include:
 * Uniqueness - color uniqueness within current and recent frames for color detector
   or intensity for IR detector.
 * Small, cohesive blob with obvious boundary.
 * Blob exhibits movment non-coordinated with Global Movement Vector.
