# Search Debug Follow-up Handoff - 2026-05-14

## Context

Jennifer's `log.txt` was from `RID2Caltopo 1.2.10(65)` on `13May2026`, so it predates the current LOS/OOR and alert UI changes. The key field symptoms were:

- Repeated confirmation prompts during active search use, each returning `STREAMS -> MAIN`.
- Repeated altitude warning toasts for drones above `200 ft AGL`.
- A later Max App Idle Time shutdown that left foreground-service start failures in the log.

## Completed In This Thread

### Altitude Alert Mute Lifetime

Changed `StreamsViewModel` so silenced compliance/altitude alerts persist for the lifetime of the running app, across track termination and later flights.

- File: `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt`
- Change: removed pruning of `mutedComplianceAlertDesignators` to active `_droneStates.keys`.
- Result: if a user mutes a drone's above-200-ft alert, that mappedId remains muted until app process restart or explicit unmute.

### Unknown Drone Confirmation Lifetime

Added an app-session Unknown RID set in `CaltopoClient`.

- File: `app/src/main/java/org/ncssar/rid2caltopo/data/CaltopoClient.java`
- `SaveDroneSpecUnknownConfirmation(remoteId)` now records the RID in `SessionUnknownDroneRemoteIds` and marks the active spec local-archive-only.
- `IsSessionUnknownDrone(remoteId)` exposes the session marker.
- `CaltopoClient` constructor and `newWaypoint()` reapply `localArchiveOnly` for session-unknown RIDs after track reset/revival.
- `promoteLocalArchiveOnlyDrone()` clears the session Unknown marker, matching the requested behavior when the user updates the mapped ID.

Confirmation prompt gating was updated:

- File: `app/src/main/java/org/ncssar/rid2caltopo/ui/R2CViewModel.kt`
- `onDroneSpecsChanged()` no longer queues a confirmation panel when `CaltopoClient.IsSessionUnknownDrone(drone.remoteId)` is true.

Coverage:

- File: `app/src/test/java/org/ncssar/rid2caltopo/data/CaltopoClientUnknownPromotionTest.kt`
- Added `unknownConfirmationPersistsForAppSessionUntilPromoted()`.

Validation run:

- `./gradlew testDebugUnitTest --tests org.ncssar.rid2caltopo.data.CaltopoClientUnknownPromotionTest`
- `./gradlew assembleDebug`

Both passed.

## Remaining Follow-up

### Max App Idle Time Shutdown

User clarified intent: Max App Idle Time should actually shut down the app, not leave a task instance where the user can reopen a dead UI whose foreground services cannot be launched.

Evidence from old `log.txt`:

- `13May191825.342`: `CheckIdle(): app idle timeout expired after 1019.708/120.000 minutes ... Shutting down app and map to save battery.`
- Then Android blocks foreground starts:
  - `MediaMTXService Android blocked MediaMTX foreground start`
  - `ScanningService Android blocked ScanningService foreground start`

Current code already appears improved versus v65:

- `R2CActivity.Shutdown()` routes through `CaltopoClient.QuitApplication()`.
- `QuitApplication()` sets `AppExitRequested = true` and calls `finishAndRemoveTask()`.
- `R2CActivity.onDestroy()` checks `isFinishing && exitRequested` before stopping services and calling `CaltopoClient.ShutdownAsync()`.

Next thread should verify this behavior on-device/current build:

1. Set `Max App Idle Time` low enough for a fast repro.
2. Let idle expiration fire from a normal foreground app state.
3. Confirm app task is removed or no longer resumable as a stale instance.
4. Confirm no `ForegroundServiceStartNotAllowedException` appears after idle expiration.
5. If stale task remains, make idle shutdown finish the root task more forcefully, likely by ensuring `finishAndRemoveTask()` is called on the active `R2CActivity` and no service restart path runs after `AppExitRequested`.

## Caution

`CaltopoClient.java` already had unrelated local edits in this worktree before this follow-up. Keep future diffs scoped and do not revert unrelated changes.
