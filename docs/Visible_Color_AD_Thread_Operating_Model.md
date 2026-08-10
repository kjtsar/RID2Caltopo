# Visible Color AD Thread Operating Model

## Summary

Use one durable parent thread as the visible-color AD program manager and use
short-lived child threads only for bounded experiment, validation, or analysis
work. The parent thread owns checkpoint truth, ranked next work, adoption
decisions, and the handoff ledger.

Canonical parent ledger:

- [Visible_Color_AD_Next_Thread_Handoff.md](/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Next_Thread_Handoff.md)

## Parent Responsibilities

- restate the current approved checkpoint before assigning new work
- launch at most:
  - `2` experiment children
  - `1` validation child
  - `3` active children total
- avoid running two mutating children on the same detector seam at once
- do not run regression, replay, or perf validation on behalf of a mutating
  child experiment
- review child outcomes only in the parent thread
- adopt at most one new checkpoint per parent cycle
- update the parent ledger with:
  - approved checkpoint
  - active child status
  - ranked next queue
  - null results
  - success gates

## Hard Rule: Parent And Child Must Be Different Threads

- the parent role and a mutating child role must never be executed by the same
  live thread
- if a thread has declared itself the parent thread for this operating model,
  it must remain steering-only for the rest of that thread
- the parent thread may prepare, refine, and emit a launch-ready child packet
  or wrapper, but it must not mutate code, run child validation, or “continue”
  into the experiment itself
- if the user says things like:
  - `continue`
  - `2. please`
  - `start the child yourself`
  - `please proceed`
  the parent thread must interpret that as a request to produce the child
  launch payload, not as permission to become the child
- actual child execution must happen in a separate new thread or by explicit
  delegated child-agent execution outside the parent thread

## Forbidden Parent Actions

Once a thread is acting as the parent, it must not:

- edit repo-tracked files for the experiment it is assigning
- stash, restore, revert, or otherwise prepare the worktree for the child’s
  mutation
- run the child’s post-change replay, regression, or perf validation
- start “from the approved checkpoint and own it end to end”
- interpret a request to continue as a role switch into experiment child

The parent may only:

- inspect the current ledger or artifacts
- refine the next-child packet
- mark active child status
- review completed child reports
- update adoption decisions in the ledger

## Hard Rule: Who Runs Validation

- any child that changes repo-tracked code must run the full required
  validation command set for its own change before it reports results
- this includes:
  - native build/tests
  - timing build/tests
  - Kotlin compile
  - reviewed Red1 app-parity replay
  - fresh tracing on/off parity check
  - black-hot reviewed regression suite
  - visible-color reviewed regression suite
  - visible-color perf benchmark
- the parent thread must not perform that post-change validation sweep in place
  of the child
- a mutating child result is incomplete if any required validation step is
  skipped without an explicit parent-approved narrowing in the assignment
  packet
- if validation shows a correctness null result or regression, the mutating
  child must revert its own change before reporting unless the parent packet
  explicitly says to preserve the candidate tree for follow-up validation

## Child Roles

### Experiment child

- one detector seam only
- one hypothesis only
- may change only the assigned seam and directly related unit coverage
- must run the full required validation sweep after making its change
- must prove both of these before asking for adoption:
  - the change moved its intended goal
  - the change did not damage existing reviewed behavior or the performance
    envelope materially
- must revert its own change if validation shows a null result or regression

### Validation child

- no detector redesign
- reruns the full agreed validation set on a candidate checkpoint
- produces a comparison summary only

### Analysis child

- read-only
- inspects JSONL, summary, regression, and perf artifacts
- answers one narrowing question for the parent

## Child Assignment Packet

Every child thread should be launched with a packet that includes all of the
following.

```md
Child id: <child_id>
Role: experiment | validation | analysis
Checkpoint: <approved checkpoint id>
Hypothesis: <one-sentence testable statement>
Write scope: <allowed seam or read-only>
Non-goals:
- <item>
- <item>
Required commands:
- <exact command 1>
- <exact command 2>
Validation ownership:
- if this child mutates code, it owns the full required validation sweep for
  its own change
Adoption gate:
- <what counts as a win>
Artifact suffix:
- <child_id>
Expected report format:
- attempted change
- whether code remains active or was reverted
- commands run
- pass/fail summary
- headline metrics before vs after
- conclusion: adopt | reject | needs narrower follow-up
- artifact paths
```

## Known-Good Validation Command Set

Use these commands unless the parent thread explicitly narrows the scope.
For a mutating experiment child, this entire set is the default post-change
validation burden, not an optional menu.

### Native build and tests

```sh
cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test
cmake -B build
cmake --build build
./build/anomaly_test
cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/build
ctest --output-on-failure
cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test
cmake -B build_timing -DANOMALY_DEBUG_TIMING=ON
cmake --build build_timing
./build_timing/anomaly_test
cd /Users/kjt/Projects/RID2Caltopo
./gradlew :app:compileDebugKotlin
```

### Reviewed Red1 app-parity replay

Use `<child_id>` as the unique artifact suffix.

```sh
cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test
./build/anomaly_video_test \
  /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 \
  --no-video \
  --app-defaults \
  --app-appearance color \
  --color-frontend legacy \
  --time-start 0.0 \
  --time-end 5.1 \
  --summary-json /tmp/red1_app_legacy_summary_<child_id>.json \
  --color-debug-jsonl /tmp/red1_app_legacy_color_debug_<child_id>.jsonl \
  --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv \
  -c /tmp/red1_app_legacy_detections_<child_id>.csv

./build/anomaly_video_test \
  /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 \
  --no-video \
  --app-defaults \
  --app-appearance color \
  --color-frontend fresh-rgba \
  --time-start 0.0 \
  --time-end 5.1 \
  --summary-json /tmp/red1_app_fresh_summary_<child_id>.json \
  --color-debug-jsonl /tmp/red1_app_fresh_color_debug_<child_id>.jsonl \
  --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv \
  -c /tmp/red1_app_fresh_detections_<child_id>.csv
```

### Fresh tracing on/off parity check

```sh
cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test
./build/anomaly_video_test \
  /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 \
  --no-video \
  --app-defaults \
  --app-appearance color \
  --color-frontend fresh-rgba \
  --time-start 0.0 \
  --time-end 5.1 \
  --summary-json /tmp/red1_app_fresh_notrace_summary_<child_id>.json \
  -c /tmp/red1_app_fresh_notrace_detections_<child_id>.csv

./build/anomaly_video_test \
  /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/Red1.mp4 \
  --no-video \
  --app-defaults \
  --app-appearance color \
  --color-frontend fresh-rgba \
  --time-start 0.0 \
  --time-end 5.1 \
  --summary-json /tmp/red1_app_fresh_trace_summary_<child_id>.json \
  --color-debug-jsonl /tmp/red1_app_fresh_trace_color_debug_<child_id>.jsonl \
  --color-target-csv /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/out/red1-legacy-vs-fresh/color_target.csv \
  -c /tmp/red1_app_fresh_trace_detections_<child_id>.csv

shasum -a 256 /tmp/red1_app_fresh_notrace_detections_<child_id>.csv
shasum -a 256 /tmp/red1_app_fresh_trace_detections_<child_id>.csv
```

### Reviewed regression suites

Note: `run_regression_suite.py` uses `--out-dir`, not `--output-dir`.

```sh
cd /Users/kjt/Projects/RID2Caltopo
python3 tools/anomaly_test/run_regression_suite.py \
  --manifest tools/anomaly_test/regression_suite_manifest.json \
  --binary tools/anomaly_test/build_timing/anomaly_video_test \
  --out-dir /private/tmp/regression_main_<child_id> \
  --report-json /private/tmp/regression_main_<child_id>/suite_report.json \
  --report-md /private/tmp/regression_main_<child_id>/suite_report.md

python3 tools/anomaly_test/run_regression_suite.py \
  --manifest tools/anomaly_test/regression_suite_color_manifest.json \
  --binary tools/anomaly_test/build_timing/anomaly_video_test \
  --out-dir /private/tmp/regression_color_<child_id> \
  --report-json /private/tmp/regression_color_<child_id>/suite_report.json \
  --report-md /private/tmp/regression_color_<child_id>/suite_report.md
```

### Visible-color perf benchmark

```sh
cd /Users/kjt/Projects/RID2Caltopo
python3 tools/anomaly_test/run_visible_color_perf_benchmarks.py \
  --binary tools/anomaly_test/build_timing/anomaly_video_test \
  --output-dir /private/tmp/visible_color_perf_<child_id>
```

## Child Report Template

Every child thread should return results in this exact shape.

```md
## Child Result: <child_id>

Role:
- experiment | validation | analysis

Checkpoint:
- <approved checkpoint id used as the starting point>

Attempted change:
- <what was tried or analyzed>

Code state after validation:
- active
- reverted
- read-only

Commands run:
- <command>
- <command>

Pass/fail summary:
- native build/tests: pass | fail
- timing build/tests: pass | fail
- Kotlin compile: pass | fail
- Red1 app-parity replay: pass | fail
- black-hot reviewed regression: pass | fail
- visible-color reviewed regression: pass | fail
- visible-color perf benchmark: pass | fail

Headline metrics before vs after:
- <metric>
- <metric>

Conclusion:
- adopt
- reject
- needs narrower follow-up

Artifact paths:
- <path>
- <path>

Key interpretation:
- <one or two short bullets only>
```

## Parent Adoption Rule

A child result is not current truth until the parent thread:

1. reviews the child report
2. decides `adopt`, `reject`, or `needs narrower follow-up`
3. updates the ledger in
   [Visible_Color_AD_Next_Thread_Handoff.md](/Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Next_Thread_Handoff.md)
4. records the new approved checkpoint or explicitly restores the prior one

The parent may inspect and interpret child artifacts, but it must not
substitute its own regression run for the child’s missing validation on a
mutating experiment.

## Required Parent Response Pattern

When the parent thread is asked to continue or launch the next experiment, its
response should stay within one of these shapes:

- `I remain the parent thread. I will not mutate code or run child validation
  in this thread. Here is the launch-ready child packet/wrapper for <child_id>.`
- `I remain the parent thread. Active child assignments is now <status>.`
- `I remain the parent thread. Here is my review of the completed child
  report/artifacts, and here is the adoption decision.`

The parent thread should not answer with language such as:

- `I’ll implement the experiment now`
- `I’ll stash the tree and start child3`
- `I’m editing anomaly_analysis.c now`
- `I’m running the validation sweep now`

## Suggested Child Launch Wrappers

### Experiment child wrapper

```md
Read these first:
- /Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Next_Thread_Handoff.md
- /Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Thread_Operating_Model.md

You are <child_id>. Role: experiment child.
Start from approved checkpoint <checkpoint_id>.
Own only this seam: <write scope>.
Test only this hypothesis: <hypothesis>.
Do not change <non-goals>.
Run the required validation commands with artifact suffix <child_id>.
If the result is a correctness null or regression, revert your own change
before reporting.
Return the child report template exactly.
```

### Validation child wrapper

```md
Read these first:
- /Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Next_Thread_Handoff.md
- /Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Thread_Operating_Model.md

You are <child_id>. Role: validation child.
Do not redesign the detector.
Validate checkpoint <checkpoint_id> with the exact required commands and
artifact suffix <child_id>.
Return only the child report template.
```

### Analysis child wrapper

```md
Read these first:
- /Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Next_Thread_Handoff.md
- /Users/kjt/Projects/RID2Caltopo/docs/Visible_Color_AD_Thread_Operating_Model.md

You are <child_id>. Role: analysis child.
Stay read-only.
Inspect only the assigned artifacts and answer one narrowing question.
Return the child report template with `Code state after validation: read-only`.
```
