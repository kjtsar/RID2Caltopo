# MapPane Shell and Facades

## Problem

`MapPane.kt` has grown into a large user-facing runtime surface. It currently mixes map lifecycle, osmdroid setup, viewport policy, artifact hydration, overlay rendering, drone labels, local tracks, NOTAM dialogs, map settings, bad-tile handling, offline tile preparation, DEM preparation, pilot display preferences, and export/cache actions in one file and one dominant `SplitMapPane` composable.

That makes normal feature work risky. Small UI or runtime changes can disturb unrelated behavior, and very large Compose/runtime dispatch surfaces have already become fragile enough to trigger ART/runtime failures. The first hardening step should reduce this fragility without changing operator-visible behavior.

## Goals

- Preserve the public `SplitMapPane(viewModel, modifier, onSingleTapFocus, presentationMode)` entrypoint.
- Keep `StreamsScreen.kt` functionally unchanged in the first packet.
- Split the largest UI and helper areas into focused files with narrow interfaces.
- Keep the first packet behavior-preserving unless a clearly incorrect behavior is identified and explicitly called out.
- Make future changes easier to review by grouping related MapPane behavior behind named seams.
- Keep existing focused JVM tests green and add tests only where extraction exposes a useful pure seam.

## Non-Goals

- Do not redesign map behavior, pilot display behavior, artifact rendering, offline prep, or drone follow policy in the first packet.
- Do not change Color AD, anomaly detector, stream binding, tracker upload, or archive behavior.
- Do not move ownership of long-lived runtime state out of `SplitMapPane` yet unless required to preserve behavior.
- Do not attempt a full ViewModel/controller rewrite in the first packet.

## Approach

Use the shell-and-facades approach.

`SplitMapPane` remains the public shell and keeps the current top-level runtime wiring. The first packet extracts related UI and pure/helper logic into feature-named files. The extracted pieces receive the existing state and callbacks from the shell, so state lifetime and recomposition behavior stay as close to the current implementation as possible.

This avoids a large ownership rewrite while still reducing ART/runtime dispatch pressure and making future work reviewable. Later packets can move facade internals behind controller-style state holders once each boundary is proven.

## Target File Layout

`MapPane.kt` remains the public shell:

- `MapPanePresentationMode`
- `SplitMapPane(...)`
- top-level wiring between `StreamsViewModel`, map lifecycle, and extracted facades
- temporary local state that has not yet moved behind a controller

`MapPaneModels.kt` contains shared MapPane data structures and small policy helpers:

- presentation and base-layer enums
- offline-prep models
- overlay state models
- local track and drone map point models
- shared constants that are used by more than one extracted file

`MapPaneDialogs.kt` contains dialog-only composables:

- NOTAM detail and group dialogs
- bad-tile removal dialog
- map folders dialog wiring
- bad-tiles help dialog
- cache size and tile age dialogs
- pilot display/color picker dialogs

`MapPaneMenus.kt` contains menu-only composables:

- settings menu
- base-layer menu
- map-management menu
- bad-tile menu

`MapPaneArtifacts.kt` contains artifact and folder overlay logic:

- map folder UI state builders
- hidden folder/item filtering
- artifact hydration result helpers
- synthetic folder classification
- artifact display titles and folder defaults
- moved/hidden clue and media filtering

`MapPaneTiles.kt` contains tile, cache, offline-prep, and DEM tile math:

- tile source selection
- tile provider construction/restart policy
- live-priority tile request helpers
- offline tile iteration and estimates
- DEM sample and tile-name helpers
- bad-tile hash export helpers

`MapPaneDroneOverlays.kt` contains drone rendering and label behavior:

- marker drawable builders
- status/name label drawables
- label layout helpers
- local track seeding helpers
- predictive-head helpers
- marker info-window behavior

`MapPaneViewport.kt` contains viewport and follow policy:

- initial viewport zoom policy
- inset viewport zoom policy
- bounds-fit safety
- follow-focused-drone policy
- viewport usability helpers

The exact file split may be adjusted during implementation if imports show a cleaner boundary, but the extraction should remain behavior-preserving and should not create broad new abstractions merely to satisfy the file list.

## Interfaces

Extracted composables should prefer explicit action callbacks over direct ViewModel access. For the first packet, callbacks may still close over existing `SplitMapPane` state.

For example, menu facades should receive:

- current display values
- expanded/dismissed state
- action callbacks such as `onReloadMap`, `onOpenMapFolders`, `onToggleContours`

They should not receive the full `StreamsViewModel` unless there is no practical alternative.

Helper files should prefer pure functions for policy decisions and data transformations. Code that requires Android UI classes, osmdroid classes, or mutable map overlays should remain isolated from pure policy helpers when possible.

## Behavior Preservation

The first packet should intentionally preserve:

- full and inset `SplitMapPane` entrypoint behavior
- map startup viewport behavior
- follow-focused-drone behavior
- artifact hydration and refresh timing
- map folder visibility behavior
- bad tile management behavior
- offline prep and tile cache settings behavior
- pilot display preference behavior
- drone marker, label, and local track rendering behavior

If implementation exposes a clearly incorrect behavior, address it only after calling it out separately in the change summary and adding focused test coverage where practical.

## Testing

Run existing focused tests that already cover MapPane seams:

- `MapPanePresentationModeTest`
- `MapPaneLocalTrackSeedTest`
- `MapPanePilotDisplayTest`
- `MapPaneArtifactOverlayStateTest`
- `PilotDisplayPrefsTest`

Add focused JVM tests only when an extracted pure helper gains a clearer standalone contract. Avoid broad UI tests in the first packet unless the extraction changes behavior in a way that cannot be covered by existing pure seams.

Run at least:

- `git diff --check`
- `./gradlew :app:testDebugUnitTest --tests 'org.ncssar.rid2caltopo.video.MapPane*' --tests 'org.ncssar.rid2caltopo.data.PilotDisplayPrefsTest'`
- `./gradlew :app:compileDebugKotlin`

Use `./gradlew :app:releaseCheck` before merging or if extraction touches shared runtime behavior beyond MapPane.

## Risks

The largest risk is accidentally changing state lifetime while moving code. Many MapPane values are Compose `remember` state, mutable collections, osmdroid overlays, or callbacks that interact with background work. The first packet should avoid moving ownership of those values and should pass them through explicit facades instead.

The second risk is creating circular imports or a catch-all `MapPaneModels.kt` that becomes a new dumping ground. Shared models should move there only when multiple extracted files need them.

The third risk is confusing extraction with redesign. If a behavior change looks worthwhile, it should be named and justified separately from the mechanical decomposition.

## First Packet Acceptance

- `SplitMapPane(...)` signature is unchanged.
- `StreamsScreen.kt` has no functional changes.
- `MapPane.kt` is materially smaller and no longer contains the largest dialog/menu blocks.
- Extracted files have clear names and limited responsibilities.
- Existing MapPane-focused tests pass.
- Kotlin debug compile passes.
- Any intentional behavior fix is documented separately from extraction.
