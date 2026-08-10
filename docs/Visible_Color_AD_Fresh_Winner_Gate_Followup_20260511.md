# Visible Color AD Fresh Winner Gate Follow-Up 2026-05-11

This is a short bounded handoff for the fresh Red1 accepted-winner follow-up.

## Starting Point

- preserve the current fresh winner telemetry
- preserve the current small-dominates scoring
- keep the architecture description honest:
  - this is still a coarse-first detector with dense local verification
  - it is not yet the broader dense pixel-first redesign

## What Was Tried Here

- softened the fresh winner commonness gate in
  [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c)
- current rule:
  - `scene commonness` can no longer erase compact winners on its own
  - commonness only contributes when the accepted winner is already oversized
  - the hard size gate remains intact

## Validation

- rebuilt:
  - `cmake --build tools/anomaly_test/build`
- replayed:
  - `./tools/anomaly_test/build/anomaly_video_test app/src/test/resources/vidcap/Red1.mp4 --no-video --app-defaults --app-appearance color --app-motion off --app-saliency off --registration affine --stride 1 --color-frontend fresh-rgba --time-start 0.0 --time-end 5.1 --summary-json /tmp/red1_softgate_v2_summary_20260511.json --color-debug-jsonl /tmp/red1_softgate_v2_color_debug_20260511.jsonl -c /tmp/red1_softgate_v2_detections_20260511.csv`
- current local unit binary state:
  - `./tools/anomaly_test/build/anomaly_test` still reports `63 passed, 17 failed`
  - this was already true before the gate edits in this thread

## What Changed

- the fresh replay still ends at `0` detection frames and `0` total boxes
- the replay mix moved slightly:
  - baseline current tree: `full=39 partial=96 target-only=18`
  - softened gate: `full=39 partial=94 target-only=20`
- winner-gate telemetry changed slightly:
  - baseline current tree: `raw_valid=14`, `winning=8`, winner-gate rejects `{ commonness: 6 }`
  - softened gate: `raw_valid=14`, `winning=9`, winner-gate rejects `{ commonness: 5 }`

## Late Nuisance Blob Check

Reviewed late frames around the reported nuisance line:

- `4.734s`, `4.801s`, `4.834s`, `4.901s`, `4.934s`, `5.067s`
- the large `x≈51%`, `y≈58-59%` blob still does **not** become an accepted winner
- in those frames it remains either:
  - below threshold after the earlier small-dominates scoring, or
  - oversized enough that it still cannot win cleanly

## Current Interpretation

- the hard accepted-winner gate was part of the zero-box collapse, but not all of it
- removing `commonness-only` erasure recovered one accepted raw winner and slightly shifted the replay planner behavior
- the replay still stays at zero because the bigger remaining problem is earlier than the late oversized blob itself

## Next Bounded Step

- keep the current telemetry and the commonness relaxation from this thread
- inspect the remaining `winner_gate_reject_reason=2` frame around `5.034s`
- then focus one step earlier than accepted-winner erasure:
  - why the late `x≈51%`, `y≈58-59%` nuisance blob is already scoring below threshold in most late frames
  - why the replay still has no accepted late target-side winners even after compact winners are no longer commonness-killed
