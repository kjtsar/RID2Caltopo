# IR Anomaly Handoff 2026-05-07

This note is the current handoff checkpoint for the next anomaly-tuning thread.

## Current Branch State

Recent work focused on the coarse thermal singleton path in:

- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c)
- [app/src/main/cpp/anomaly_analysis.h](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h)
- [tools/anomaly_test/anomaly_video_test.c](/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/anomaly_video_test.c)

The current code does three relevant things:

1. Adds direct coarse-singleton telemetry:
   `patch_support`, `motion_support`, `singleton_score_scale`,
   `retention_rank`, and `singleton_blob`.
2. Adds a coarse-mode singleton clutter penalty:
   compact singleton candidates get downweighted when many near-equal
   singleton rivals are present in the same frame.
3. Uses `retention_rank` in thermal candidate ordering so coarse winners are
   not chosen purely by "smallest hottest pixel wins".

## Key Result From Focused Replay

The reviewed nuisance at `11.567s` in `PowerHouseTeam.mp4` no longer wins at
`(0.3302, 0.2583)` in the focused late-FP replay.

Useful replay artifacts from the latest tuning pass:

- `/tmp/powerhouse_coarse_singleton_tune_v2.jsonl`
- `/tmp/powerhouse_coarse_singleton_tune_v2.csv`

At `11.567s`, the original tree singleton was cut from roughly `9.9` score to
roughly `4.65`, and a different coarse singleton candidate won instead.

## Reviewed Regression Status

The reviewed suite manifest now includes `PowerHouse1.review.json` as a scored
excerpt in:

- [tools/anomaly_test/regression_suite_manifest.json](/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/regression_suite_manifest.json)

Current scored suite output for this branch:

- `/private/tmp/regression_with_powerhouse1/suite_report.json`
- `/private/tmp/regression_with_powerhouse1/suite_report.md`

### Aggregate metrics with `PowerHouse1` included

- `current-detector-baseline`: `37 TP / 0 FP / 242 miss`
  recall `0.1326`, precision `1.0000`
- `dense-full-scan-gold`: `67 TP / 0 FP / 212 miss`
  recall `0.2401`, precision `1.0000`
- `redesigned-incremental`: `89 TP / 0 FP / 190 miss`
  recall `0.3190`, precision `1.0000`

### Important interpretation

`PowerHouse1` changes the picture in an important way:

- `current-detector-baseline` on `PowerHouse1`: `0 TP / 0 FP / 108 miss`
- `dense-full-scan-gold` on `PowerHouse1`: `0 TP / 0 FP / 108 miss`
- `redesigned-incremental` on `PowerHouse1`: `13 TP / 0 FP / 95 miss`

So the redesigned incremental path is currently the only scored profile that is
recovering that reviewed opening target at all.

## Main New Diagnosis

The next bottleneck is not just nuisance suppression.

`PowerHouse1` shows an early-acquisition / latency problem:

- first scored hit in the reviewed opening window arrives around `3.667s`
- suite-reported latency to first box is about `2.434s`
- the target is annotated much earlier, starting around `1.233s`

This means the next high-value pass should focus on why early true targets in
high-resolution clips are not becoming thermal winners soon enough.

## Recommended Next Thread

Prioritize `PowerHouse1` early acquisition over more broad singleton penalties.

Suggested work order:

1. Inspect the `PowerHouse1` reviewed opening window from about `1.2s` to
   `3.6s`.
2. Emit thermal telemetry for `redesigned-incremental` on that clip/window.
3. Compare pre-hit frames against the first-hit frame to determine what flips:
   candidate extraction, threshold crossing, target ranking, history support,
   or publish gating.
4. Tune for earlier pickup without giving back the `PowerHouseTeam` singleton
   clutter improvement.

## Helpful Questions For Next Pass

- Is the target present in thermal candidates before `3.667s` but losing rank?
- If present, is it being beaten by coarse singleton clutter, or by a larger
  non-singleton candidate?
- If absent, is extraction too conservative on `PowerHouse1` at 1080p?
- Is publish timing still delaying a valid target that is already detectable?

## Recommended Commands

Build:

```sh
cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test
cmake --build build
```

Rerun reviewed suite:

```sh
cd /Users/kjt/Projects/RID2Caltopo
python3 tools/anomaly_test/run_regression_suite.py \
  --binary /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/build/anomaly_video_test \
  --out-dir /tmp/regression_with_powerhouse1
```

Targeted `PowerHouse1` replay template:

```sh
cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test
./build/anomaly_video_test \
  /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/PowerHouse1.mp4 \
  --no-video \
  -c /tmp/powerhouse1_opening.csv \
  --summary-json /tmp/powerhouse1_opening_summary.json \
  --thermal-debug-jsonl /tmp/powerhouse1_opening.jsonl \
  --time-start 1.0 \
  --time-end 4.0 \
  -p bh -a 6 -t 2.8 -m 2 -s 0.6 --registration affine --stride 1
```

## Keep / Avoid

Keep:

- the current coarse singleton telemetry
- the current `PowerHouse1` reviewed suite entry
- `PowerHouseTeam` as the nuisance benchmark
- `PowerHouse1` as the early-acquisition benchmark

Avoid:

- broad new singleton penalties without checking `PowerHouse1`
- mixing unrelated planner or UI changes into the next detector pass
- overwriting the historical checkpoint docs; treat this file as the current
  thread handoff
