# MapPane Shell and Facades Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce `MapPane.kt` fragility by preserving `SplitMapPane` as the public shell while extracting map-owned models, dialogs, menus, viewport helpers, artifacts, tiles, and drone overlay helpers into focused files.

**Architecture:** Use shell-and-facades extraction. `SplitMapPane` keeps top-level state ownership and passes current state/callbacks into extracted composables and helpers. MapPane-owned files stay map-centric; stream playback, stream clue reporting, stream identity/binding, and stream-focused controls remain in stream modules.

**Tech Stack:** Kotlin, Jetpack Compose, osmdroid, Android JVM unit tests, Gradle.

---

## File Structure

- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`
  - Keep `SplitMapPane` signature unchanged.
  - Keep shell wiring, top-level Compose state, and map lifecycle orchestration.
  - Remove extracted declarations after each new file compiles.

- Create: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneModels.kt`
  - Shared MapPane enums, data classes, constants, and pure policy helpers used by multiple extracted files.
  - No stream playback/clue/reporting ownership.

- Create: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneViewport.kt`
  - Viewport and follow policy helpers currently covered by `MapPanePresentationModeTest`.

- Create: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneDialogs.kt`
  - Dialog-only composables and pilot display dialog support.

- Create: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneMenus.kt`
  - Menu-only composables with explicit state/action parameters.

- Create: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneArtifacts.kt`
  - Artifact overlay state helpers, folder UI builders, and synthetic folder classification.

- Create: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneTiles.kt`
  - Tile source/provider helpers, offline tile math, live tile priority helpers, and DEM tile helpers.

- Create: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneDroneOverlays.kt`
  - Drone label layout, marker drawables, local track helpers, and predictive-head helpers.

- Existing tests:
  - `app/src/test/java/org/ncssar/rid2caltopo/video/MapPanePresentationModeTest.kt`
  - `app/src/test/java/org/ncssar/rid2caltopo/video/MapPaneLocalTrackSeedTest.kt`
  - `app/src/test/java/org/ncssar/rid2caltopo/video/MapPanePilotDisplayTest.kt`
  - `app/src/test/java/org/ncssar/rid2caltopo/video/MapPaneArtifactOverlayStateTest.kt`
  - `app/src/test/java/org/ncssar/rid2caltopo/data/PilotDisplayPrefsTest.kt`

---

### Task 1: Establish the Baseline

**Files:**
- Read: `docs/superpowers/specs/2026-06-23-mappane-shell-facades-design.md`
- Read: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`
- Test: existing MapPane-focused JVM tests

- [ ] **Step 1: Confirm the worktree context**

Run:

```bash
git status --short
```

Expected: Existing unrelated dirty/untracked files may be present. Do not revert them. Record tracked files that are already modified before editing.

- [ ] **Step 2: Run focused baseline tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'org.ncssar.rid2caltopo.video.MapPane*' --tests 'org.ncssar.rid2caltopo.data.PilotDisplayPrefsTest'
```

Expected: PASS. If this fails before edits, inspect the failure and stop; do not start extraction on a red baseline.

- [ ] **Step 3: Run baseline Kotlin compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS. If this fails before edits, inspect the failure and stop.

- [ ] **Step 4: Commit nothing**

No code changed in this task. Leave the worktree as-is.

---

### Task 2: Extract Shared Models and Viewport Policy

**Files:**
- Create: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneModels.kt`
- Create: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneViewport.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`
- Test: `app/src/test/java/org/ncssar/rid2caltopo/video/MapPanePresentationModeTest.kt`

- [ ] **Step 1: Create `MapPaneModels.kt` with shared declarations**

Move these declarations from `MapPane.kt` into `MapPaneModels.kt`, preserving package name, visibility, names, and values exactly:

```kotlin
package org.ncssar.rid2caltopo.video

internal const val MAP_PANE_TAG = "SplitMapPane"

internal enum class MapPanePresentationMode {
    Full,
    Inset
}

internal const val MAP_PANE_VERBOSE_LOGS = false
private const val INSET_FOLLOW_INTERVAL_MS = 500L
private const val INSET_FOLLOW_MIN_MOVE_METERS = 1.0
internal const val LOCAL_DEVICE_SYMBOL = "radiotower"
internal const val LOCAL_DEVICE_COLOR_HEALTHY = "0000FF"
internal const val LOCAL_DEVICE_COLOR_STARTING = "808080"
internal const val LOCAL_DEVICE_COLOR_DEGRADED = "FFA500"
internal const val LOCAL_DEVICE_COLOR_UNCONFIGURED = "FF0000"
internal const val ICON_LATENCY_TAG = "RidIconLatency"
internal const val AGL_LIMIT_FT = 200.0
internal const val RANGE_LIMIT_FT = 5280.0
internal const val AGL_ICON_NEAR_DELTA_FT = 20.0
internal const val FT_TO_METERS = 0.3048
internal const val NEAR_LIMIT_RATIO = 0.90
internal const val NEAR_ALERT_COOLDOWN_MS = 30_000L
private const val STARTUP_MY_LOCATION_FRESH_MS = 60_000L
private const val STARTUP_MY_LOCATION_WAIT_MS = 20_000L
private const val STARTUP_MY_LOCATION_MIN_ZOOM = 14.0
internal const val OVER_ALERT_COOLDOWN_MS = 12_000L
internal const val METERS_TO_FEET = 3.28084
internal const val DEM_RETRY_INTERVAL_MS = 2_000L
internal const val PREDICTIVE_HEAD_MIN_AGE_MS = 600L
internal const val PREDICTIVE_HEAD_MAX_AGE_MS = 5_000L
internal const val PREDICTIVE_HEAD_MAX_LOOKAHEAD_MS = 2_000L
internal const val PREDICTIVE_HEAD_MAX_SPEED_MPS = 45.0
internal const val PREDICTIVE_HEAD_MAX_VERTICAL_SPEED_MPS = 15.0
internal const val PREDICTIVE_HEAD_MAX_DISTANCE_M = 90.0
internal const val SIGNIFICANT_HEADING_MOVE_M = 16.0 * FT_TO_METERS
internal const val LABEL_MAX_ABS_FEET = 1000.0
internal const val DEFAULT_CAMERA_FOV_WIDTH_DEG = 80.0
internal const val OSM_MAX_ZOOM = 19.0
internal const val MAP_DISPLAY_MAX_ZOOM = 22.0
internal const val MAP_CACHE_PREFS_NAME = "map_cache"
internal const val MAP_CACHE_PREWARM_SIG_KEY = "prewarm_signature_v1"
internal const val OSM_TILE_DOWNLOAD_THREADS: Short = 1
internal const val OSM_TILE_DOWNLOAD_MAX_QUEUE: Short = 1000
internal const val OSM_OFFLINE_PREP_REQUEST_DELAY_MS = 1_250L
internal const val TILE_FS_THREADS: Short = 4
internal const val TILE_FS_MAX_QUEUE: Short = 2000
internal const val TILE_IO_ACTIVE_GRACE_MS = 2_000L
private const val WEB_MERCATOR_HALF_WORLD_METERS = 20_037_508.342789244
```

Also move model declarations in the current `MapPane.kt` range around lines 318-578:

```kotlin
internal enum class BaseLayerOption(val label: String)
internal enum class OfflinePrepAreaMode(val label: String)
internal enum class AlertSeverity
internal data class OfflinePrepPreset(preserve current signature and body)
internal val OFFLINE_PREP_PRESETS = listOf(preserve current signature and body)
internal data class OfflinePrepProgress(preserve current signature and body)
internal data class GeoBoundary(preserve current signature and body)
internal data class OfflineBoundaryOption(preserve current signature and body)
internal data class OfflinePrepEstimate(preserve current signature and body)
internal data class OfflinePrepCacheStatus(preserve current signature and body)
internal data class MapPaneBackgroundWorkStatus(preserve current signature and body)
internal data class LiveTileRequest(preserve current signature and body)
internal data class DroneMapPoint(preserve current signature and body)
internal data class LabelRect(preserve current signature and body)
internal data class LabelLeaderLine(preserve current signature and body)
internal data class DroneLabelLayoutInput(preserve current signature and body)
internal data class DroneLabelLayout(preserve current signature and body)
internal data class DroneComplianceState(preserve current signature and body)
internal data class ArtifactPointSpec(preserve current signature and body)
internal data class ArtifactLineSpec(preserve current signature and body)
internal data class ArtifactPolygonSpec(preserve current signature and body)
internal data class ArtifactOverlayState(preserve current signature and body)
internal data class ArtifactHydrationProgress(preserve current signature and body)
internal data class ArtifactFolderDefault(preserve current signature and body)
internal data class ArtifactHydrationResult(preserve current signature and body)
internal data class LocalTrackPoint(preserve current signature and body)
internal data class PredictedHead(preserve current signature and body)
internal data class BadTileDialogState(preserve current signature and body)
internal data class PilotDisplaySettingsState(preserve current signature and body)
```

Keep private UI-only declarations such as `PilotDisplayColorSlot` and `PilotColorPickerTarget` in the dialog file during Task 3.

- [ ] **Step 2: Create `MapPaneViewport.kt` with viewport helpers**

Move these functions from `MapPane.kt` into `MapPaneViewport.kt` with the same package and signatures:

```kotlin
internal fun mapPaneMarkerScale(mode: MapPanePresentationMode): Float =
    when (mode) {
        MapPanePresentationMode.Full -> 1.0f
        MapPanePresentationMode.Inset -> 0.55f
    }

internal fun mapPaneLineScale(mode: MapPanePresentationMode): Float =
    when (mode) {
        MapPanePresentationMode.Full -> 1.0f
        MapPanePresentationMode.Inset -> 0.65f
    }

internal fun mapPaneInsetViewportZoom(
    fullWidthPx: Int?,
    fullHeightPx: Int?,
    insetWidthPx: Int?,
    insetHeightPx: Int?,
    fullZoom: Double,
    maxZoom: Double = MAP_DISPLAY_MAX_ZOOM
): Double {
    if (fullWidthPx == null || fullHeightPx == null || insetWidthPx == null || insetHeightPx == null) {
        return fullZoom.coerceAtMost(maxZoom)
    }
    if (fullWidthPx <= 0 || fullHeightPx <= 0 || insetWidthPx <= 0 || insetHeightPx <= 0) {
        return fullZoom.coerceAtMost(maxZoom)
    }
    val widthScale = fullWidthPx.toDouble() / insetWidthPx.toDouble()
    val heightScale = fullHeightPx.toDouble() / insetHeightPx.toDouble()
    val scale = maxOf(widthScale, heightScale)
    val zoomDelta = kotlin.math.log(scale, 2.0)
    return (fullZoom - zoomDelta).coerceAtMost(maxZoom)
}

internal fun mapPaneInitialViewportZoom(
    presentationMode: MapPanePresentationMode,
    restoredZoom: Double,
    maxZoom: Double = MAP_DISPLAY_MAX_ZOOM
): Double {
    if (presentationMode != MapPanePresentationMode.Inset) return restoredZoom
    return restoredZoom.coerceAtMost(maxZoom)
}

internal fun shouldFollowFocusedDrone(
    presentationMode: MapPanePresentationMode,
    followFocusedDroneEnabled: Boolean,
    hasFocusedDroneTelemetry: Boolean,
    operatorAdjustedViewport: Boolean
): Boolean {
    if (!followFocusedDroneEnabled || !hasFocusedDroneTelemetry) return false
    return presentationMode == MapPanePresentationMode.Inset || !operatorAdjustedViewport
}

internal fun mapPaneShouldReplayCachedArtifacts(
    presentationMode: MapPanePresentationMode,
    cachedFeatureCount: Int
): Boolean = presentationMode == MapPanePresentationMode.Full && cachedFeatureCount <= 0

internal fun mapPaneShouldRequestArtifactRefreshOnMount(
    presentationMode: MapPanePresentationMode,
    cachedFeatureCount: Int
): Boolean = false

internal fun mapPaneCanZoomToBoundingBox(
    mapWidthPx: Int,
    mapHeightPx: Int,
    pointCount: Int
): Boolean = mapWidthPx > 0 && mapHeightPx > 0 && pointCount > 1

internal fun isUsableMapViewportState(latitude: Double, longitude: Double, zoom: Double): Boolean {
    return latitude.isFinite() &&
        longitude.isFinite() &&
        zoom.isFinite() &&
        latitude in -90.0..90.0 &&
        longitude in -180.0..180.0 &&
        zoom > 0.0
}
```

If the current helper bodies differ from this block, preserve the current helper bodies and do not change behavior.

- [ ] **Step 3: Remove moved declarations from `MapPane.kt`**

Delete only the declarations moved in Steps 1 and 2. Leave all call sites unchanged; they remain in package `org.ncssar.rid2caltopo.video` and should resolve without import changes.

- [ ] **Step 4: Run focused viewport tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'org.ncssar.rid2caltopo.video.MapPanePresentationModeTest'
```

Expected: PASS.

- [ ] **Step 5: Run Kotlin compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS. If imports are missing, add only the imports needed by the new files. Do not change behavior.

- [ ] **Step 6: Commit**

Run:

```bash
git add app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneModels.kt app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneViewport.kt
git commit -m "Split MapPane models and viewport policy"
```

Expected: Commit succeeds.

---

### Task 3: Extract Dialog Facades

**Files:**
- Create: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneDialogs.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`
- Test: Kotlin compile plus MapPane-focused JVM tests

- [ ] **Step 1: Move dialog-private pilot display types**

Move these declarations from `MapPane.kt` into `MapPaneDialogs.kt`:

```kotlin
private enum class PilotDisplayColorSlot {
    Active,
    Archive
}

private data class PilotColorPickerTarget(
    val settings: PilotDisplaySettingsState,
    val slot: PilotDisplayColorSlot
)
```

If `PilotColorPickerTarget` is still used directly by `SplitMapPane`, keep the type in `MapPane.kt` for this task and move only `PilotDisplayColorSlot`. Do not widen private types to `internal` unless the compiler requires cross-file access.

- [ ] **Step 2: Move pilot display dialog composables**

Move these composables from `MapPane.kt` into `MapPaneDialogs.kt`:

```kotlin
@Composable
private fun PilotDisplaySettingsContent(preserve current signature and body)

@Composable
private fun PilotDisplayColorRow(preserve current signature and body)

@Composable
private fun PilotTrackColorPickerDialog(preserve current signature and body)
```

If a composable must be called from `SplitMapPane`, make that composable `internal` and keep helper rows private:

```kotlin
@Composable
internal fun PilotTrackColorPickerDialog(preserve current signature and body)
```

Preserve the parameter list and body exactly.

- [ ] **Step 3: Move MapPane dialog facades**

Move these composables from `MapPane.kt` into `MapPaneDialogs.kt`:

```kotlin
@Composable
private fun MapPaneNotamDialogs(preserve current signature and body)

@Composable
private fun MapPaneManagementDialogs(preserve current signature and body)
```

Change them to `internal` because `SplitMapPane` calls them from `MapPane.kt`:

```kotlin
@Composable
internal fun MapPaneNotamDialogs(preserve current signature and body)

@Composable
internal fun MapPaneManagementDialogs(preserve current signature and body)
```

Preserve callback parameters and bodies exactly.

- [ ] **Step 4: Remove moved declarations from `MapPane.kt`**

Delete the moved composables and private helpers from `MapPane.kt`. Keep call sites in `SplitMapPane` unchanged unless the compiler needs a visibility adjustment.

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'org.ncssar.rid2caltopo.video.MapPanePilotDisplayTest' --tests 'org.ncssar.rid2caltopo.data.PilotDisplayPrefsTest'
```

Expected: PASS.

- [ ] **Step 6: Run Kotlin compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneDialogs.kt
git commit -m "Extract MapPane dialog facades"
```

Expected: Commit succeeds.

---

### Task 4: Extract Menu Facades

**Files:**
- Create: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneMenus.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`
- Test: Kotlin compile

- [ ] **Step 1: Move `MapPaneSettingsMenus`**

Move the full `BoxScope.MapPaneSettingsMenus` composable from `MapPane.kt` into `MapPaneMenus.kt`.

Use this signature shape, preserving the current parameter list exactly:

```kotlin
@Composable
internal fun BoxScope.MapPaneSettingsMenus(
    context: Context,
    settingsMenuExpanded: Boolean,
    onSettingsMenuExpandedChange: (Boolean) -> Unit,
    mapManagementMenuExpanded: Boolean,
    onMapManagementMenuExpandedChange: (Boolean) -> Unit,
    baseLayerMenuExpanded: Boolean,
    onBaseLayerMenuExpandedChange: (Boolean) -> Unit,
    badTilesMenuExpanded: Boolean,
    onBadTilesMenuExpandedChange: (Boolean) -> Unit,
    baseLayer: BaseLayerOption,
    predictiveHeadEnabled: Boolean,
    followFocusedDroneEnabled: Boolean,
    mapReloadInFlight: Boolean,
    mapName: String?,
    autoRemoveBadTiles: Boolean,
    contourOverlayEnabled: Boolean,
    hasMapFolders: Boolean,
    onTogglePredictiveHead: () -> Unit,
    onDownloadMap: () -> Unit,
    onOpenMapFolders: () -> Unit,
    onToggleFollowFocusedDrone: () -> Unit,
    onReloadMap: () -> Unit,
    onOpenBadTiles: () -> Unit,
    onOpenBadTilesHowTo: () -> Unit,
    onOpenCacheSize: () -> Unit,
    onOpenTileAge: () -> Unit,
    onToggleAutoRemoveBadTiles: () -> Unit,
    onClearBadTileFlags: () -> Unit,
    onExportBadTileHashes: () -> Unit,
    onBaseLayerSelected: (BaseLayerOption) -> Unit,
    onToggleContours: () -> Unit
)
```

Do not pass `StreamsViewModel` to this file. Keep all stream-related behavior outside this menu facade.

- [ ] **Step 2: Remove moved menu code from `MapPane.kt`**

Delete only the moved `MapPaneSettingsMenus` declaration from `MapPane.kt`. Keep the existing call site in the `BoxScope` content.

- [ ] **Step 3: Run Kotlin compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 4: Commit**

Run:

```bash
git add app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneMenus.kt
git commit -m "Extract MapPane menu facades"
```

Expected: Commit succeeds.

---

### Task 5: Extract Artifact Overlay Helpers

**Files:**
- Create: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneArtifacts.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`
- Test: `app/src/test/java/org/ncssar/rid2caltopo/video/MapPaneArtifactOverlayStateTest.kt`

- [ ] **Step 1: Move synthetic folder constants and models**

Move declarations around the current `MapPane.kt` artifact section into `MapPaneArtifacts.kt`:

```kotlin
private const val CALTOPO_ASSIGNMENTS_FOLDER_ID = "__caltopo_assignments__"
private const val CALTOPO_ASSIGNMENTS_FOLDER_TITLE = "Assignments"
private const val CALTOPO_RANGE_RINGS_FOLDER_ID = "__caltopo_range_rings__"
private const val CALTOPO_RANGE_RINGS_FOLDER_TITLE = "Range Rings"
private const val CALTOPO_MARKERS_FOLDER_ID = "__caltopo_markers__"
private const val CALTOPO_MARKERS_FOLDER_TITLE = "Markers"
private const val CALTOPO_LINES_POLYGONS_FOLDER_ID = "__caltopo_lines_polygons__"
private const val CALTOPO_LINES_POLYGONS_FOLDER_TITLE = "Lines & Polygons"
private const val CALTOPO_APP_TRACKS_FOLDER_ID = "__caltopo_app_tracks__"
private const val CALTOPO_APP_TRACKS_FOLDER_TITLE = "App Tracks"
private const val CALTOPO_OTHER_MAP_ITEMS_FOLDER_ID = "__caltopo_other_map_items__"
private const val CALTOPO_OTHER_MAP_ITEMS_FOLDER_TITLE = "Other Map Items"

private data class SyntheticArtifactFolder(preserve current signature and body)
private val syntheticArtifactFoldersById = listOf(preserve current signature and body).associateBy { it.id }
```

Preserve exact current values and constructors.

- [ ] **Step 2: Move artifact helper functions**

Move these functions from `MapPane.kt` into `MapPaneArtifacts.kt`, preserving signatures and bodies:

```kotlin
internal fun buildMapFolderUiStates(features: Map<String, JSONObject>): List<MapFolderUiState>
internal fun mapFolderUiDebugSummary(preserve current signature and body)
internal fun buildArtifactOverlayState(preserve current signature and body)
internal fun buildArtifactHydrationResult(preserve current signature and body)
internal fun movedDroneFolderMarkerIds(preserve current signature and body)
private fun isDroneTrackMarker(preserve current signature and body)
private fun isMediaObjectWithHiddenParent(preserve current signature and body)
private fun effectiveArtifactFolderId(preserve current signature and body)
private fun syntheticArtifactFolderId(preserve current signature and body)
private fun artifactDisplayTitle(preserve current signature and body)
private fun archivedDroneTrackPilotCallsign(preserve current signature and body)
private fun orphanFolderTitle(preserve current signature and body)
private fun applySyntheticArtifactFolderDefault(preserve current signature and body)
private fun syntheticArtifactFolderDefault(preserve current signature and body)
private fun appendGeometryArtifact(preserve current signature and body)
private fun isArtifactDelete(preserve current signature and body)
private fun geoPointsFromLine(preserve current signature and body)
private fun artifactLogSummary(preserve current signature and body)
private fun allArtifactGeoPoints(preserve current signature and body)
private fun boundingBoxFromPoints(preserve current signature and body)
```

If a private helper is used by a later tile/drone task, keep it in `MapPane.kt` until that task or make it `internal` only when needed.

- [ ] **Step 3: Remove moved artifact code from `MapPane.kt`**

Delete only moved declarations. Keep `SplitMapPane` artifact state ownership and call sites unchanged.

- [ ] **Step 4: Run artifact tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'org.ncssar.rid2caltopo.video.MapPaneArtifactOverlayStateTest'
```

Expected: PASS.

- [ ] **Step 5: Run Kotlin compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneArtifacts.kt
git commit -m "Extract MapPane artifact helpers"
```

Expected: Commit succeeds.

---

### Task 6: Extract Tile and Offline Prep Helpers

**Files:**
- Create: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneTiles.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`
- Test: `app/src/test/java/org/ncssar/rid2caltopo/video/MapPaneArtifactOverlayStateTest.kt`

- [ ] **Step 1: Move tile source objects and provider helpers**

Move these declarations from `MapPane.kt` into `MapPaneTiles.kt`, preserving signatures and bodies:

```kotlin
internal object ArcGisWorldImageryTileSource : OnlineTileSourceBase(preserve current signature and body)
internal object OsmStandardTileSource : OnlineTileSourceBase(preserve current signature and body)
internal object UsgsContoursTileSource : OnlineTileSourceBase(preserve current signature and body)
internal fun osmUserAgent(): String
internal fun buildOfflineTileRequest(preserve current signature and body)
internal fun tileSourceForBaseLayer(baseLayer: BaseLayerOption): OnlineTileSourceBase
internal fun offlinePrepTileSources(preserve current signature and body)
internal fun offlinePrepTileOperationCount(preserve current signature and body)
internal fun needsBaseTileProviderRestart(preserve current signature and body)
internal fun needsViewportTileProviderRestart(preserve current signature and body)
internal fun visibleTileNetworkActive(preserve current signature and body)
internal fun offlineFirstForVisibleTiles(preserve current signature and body)
private fun prefetchMapTileIfMissing(preserve current signature and body)
internal fun configureOsmdroid(context: Context)
private fun buildTileMapProvider(preserve current signature and body)
private fun restartTileProviderForViewportIntent(preserve current signature and body)
```

If `buildTileMapProvider` or `restartTileProviderForViewportIntent` must be called from `SplitMapPane`, make it `internal` in the new file. Preserve behavior.

- [ ] **Step 2: Move offline tile and DEM helpers**

Move these helpers from `MapPane.kt` into `MapPaneTiles.kt`, preserving behavior:

```kotlin
private fun estimateTileCountForBounds(preserve current signature and body)
internal fun estimateTileCountApproximate(preserve current signature and body)
private suspend fun forEachTileIndexForBounds(preserve current signature and body)
internal fun orderedTileIndexesForOfflinePrep(preserve current signature and body)
internal fun liveTilePriorityRequests(preserve current signature and body)
internal fun droneTilePriorityRequests(preserve current signature and body)
private fun collectTileIndexesForBounds(preserve current signature and body)
private fun tileIndexForPoint(preserve current signature and body)
private fun nextTileIndexForHeading(preserve current signature and body)
private fun dronePathTileIndexes(preserve current signature and body)
private fun tileLineBetween(preserve current signature and body)
private fun estimateDemSamplesForBounds(preserve current signature and body)
internal fun estimateDemSamplesApproximate(preserve current signature and body)
private suspend fun forEachDemSamplePointForBounds(preserve current signature and body)
private fun tileNameForLocation(preserve current signature and body)
private suspend fun autoDownloadDemTile(preserve current signature and body)
private fun demTileNamesForBounds(preserve current signature and body)
private fun lonToTileX(preserve current signature and body)
private fun latToTileY(preserve current signature and body)
private fun tileXToLon(preserve current signature and body)
private fun tileYToLat(preserve current signature and body)
private fun tileIndexInsideBoundary(preserve current signature and body)
private fun pointInPolygon(preserve current signature and body)
private fun boundaryCoverageRatio(preserve current signature and body)
private fun boundsAreaMeters2(preserve current signature and body)
private fun polygonAreaMeters2(preserve current signature and body)
private fun formatDurationShort(preserve current signature and body)
internal fun queryAvailableCacheBytes(preserve current signature and body)
internal fun mapCacheRootSignature(preserve current signature and body)
private fun exportBadTileHashes(preserve current signature and body)
internal fun buildOfflineBoundaryOptions(preserve current signature and body)
private fun geoBoundaryFromPoints(preserve current signature and body)
private fun isTrackLikeFeature(preserve current signature and body)
```

If `SplitMapPane` calls a moved private helper directly, change that helper to `internal`.

- [ ] **Step 3: Remove moved tile/offline code from `MapPane.kt`**

Delete moved declarations only. Do not change offline prep behavior, cache policy, or network suppression behavior.

- [ ] **Step 4: Run tile/artifact tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'org.ncssar.rid2caltopo.video.MapPaneArtifactOverlayStateTest'
```

Expected: PASS.

- [ ] **Step 5: Run Kotlin compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneTiles.kt
git commit -m "Extract MapPane tile and offline helpers"
```

Expected: Commit succeeds.

---

### Task 7: Extract Drone Overlay Helpers

**Files:**
- Create: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneDroneOverlays.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`
- Test: `app/src/test/java/org/ncssar/rid2caltopo/video/MapPaneLocalTrackSeedTest.kt`
- Test: `app/src/test/java/org/ncssar/rid2caltopo/video/MapPanePilotDisplayTest.kt`

- [ ] **Step 1: Move local track helpers**

Move these declarations from `MapPane.kt` into `MapPaneDroneOverlays.kt`, preserving signatures and bodies:

```kotlin
private fun localTrackDesignator(mappedId: String): String
private const val LOCAL_TRACK_RECENT_POINT_LIMIT = 500
private const val LOCAL_TRACK_FLIGHT_POINT_LIMIT = 10_000
private const val LOCAL_TRACK_DUPLICATE_COORD_EPSILON = 0.000001
private const val LOCAL_TRACK_DUPLICATE_ALT_EPSILON_METERS = 0.5
internal fun seedLocalTrackPointsFromSnapshot(preserve current signature and body)
internal fun shouldSeedLocalTrackSnapshotForDesignator(preserve current signature and body)
private fun LocalTrackPoint.isSameTrackPoint(preserve current signature and body)
internal fun fullFlightTrackMappedIds(preserve current signature and body)
internal fun confirmedCurrentFlightMappedIds(preserve current signature and body)
```

- [ ] **Step 2: Move pilot display and track color helpers**

Move these declarations from `MapPane.kt` into `MapPaneDroneOverlays.kt`, preserving signatures and bodies:

```kotlin
private val PILOT_DISPLAY_COLOR_PALETTE = listOf(preserve current signature and body)
internal fun pilotDisplayPreferencesByMappedId(preserve current signature and body)
private fun trackColorInt(preserve current signature and body)
private fun closedPolylinePoints(preserve current signature and body)
private fun applyPolygonStyle(preserve current signature and body)
private fun applyPolylineStyle(preserve current signature and body)
```

If `PILOT_DISPLAY_COLOR_PALETTE` is required by `MapPaneDialogs.kt`, make it `internal`.

- [ ] **Step 3: Move label layout and marker helpers**

Move these declarations from `MapPane.kt` into `MapPaneDroneOverlays.kt`, preserving signatures and bodies:

```kotlin
internal fun layoutDroneLabelGroups(preserve current signature and body)
private data class DroneLabelCandidate(preserve current signature and body)
private fun droneLabelCandidates(preserve current signature and body)
private data class DroneLabelGroupSize(preserve current signature and body)
private fun droneLabelGroupSize(preserve current signature and body)
private fun droneLabelCandidate(preserve current signature and body)
private fun LabelRect.fitsWithin(preserve current signature and body)
private fun LabelRect.overlapArea(preserve current signature and body)
private fun LabelRect.outsideArea(preserve current signature and body)
private class LocalMarkerInfoWindow(preserve current signature and body)
internal enum class MarkerInfoWindowTapAction
internal fun markerInfoWindowTapAction(preserve current signature and body)
private fun consumeInsetMarkerTaps(preserve current signature and body)
private data class DroneLabelDrawSpec(preserve current signature and body)
private class DroneLabelOverlay(preserve current signature and body)
private fun LabelRect.toAndroidRect(preserve current signature and body)
private fun openClueSnapshotInExternalViewer(preserve current signature and body)
private fun sanitizeClueSnapshotFileName(preserve current signature and body)
private fun localDeviceMarkerColor()
private fun localDeviceStatusLines()
private fun geoPointFromLngLat(preserve current signature and body)
private fun colorFromHex(preserve current signature and body)
private fun BoundingBox.containsLocation(preserve current signature and body)
private fun locationAgeMs(preserve current signature and body)
private fun nearestDistanceMeters(preserve current signature and body)
private fun distanceFeetFromTakeoff(preserve current signature and body)
private fun nearestLocalTrackTailDistanceMeters(preserve current signature and body)
private fun predictedHeadPoint(preserve current signature and body)
private fun destinationPoint(preserve current signature and body)
private fun normalizeDegrees(preserve current signature and body)
internal data class ScreenLine(preserve current signature and body)
internal fun droneStatusLabelText(preserve current signature and body)
internal fun droneDetailLines(preserve current signature and body)
internal fun bearingLineToViewportEdge(preserve current signature and body)
private fun polarPoint(preserve current signature and body)
private fun buildDroneStatusLabelDrawable(preserve current signature and body)
private fun buildDroneNameLabelDrawable(preserve current signature and body)
private fun buildDroneMarkerDrawable(preserve current signature and body)
private fun buildNotamMarkerIcon(preserve current signature and body)
private fun isKnownArtifactSymbol(preserve current signature and body)
private fun markerIconForArtifactSymbol(preserve current signature and body)
private fun drawableScaleOrDefault(preserve current signature and body)
private fun scaledDimension(preserve current signature and body)
private fun scaleDrawableBitmap(preserve current signature and body)
private fun cachedScaledRemoteMarkerDrawable(preserve current signature and body)
private fun symbolGlyphForMarkerSymbol(preserve current signature and body)
private fun fallbackGlyphForSymbol(preserve current signature and body)
private fun normalizeMarkerColor(preserve current signature and body)
private fun buildCaltopoLikeSymbolDrawable(preserve current signature and body)
```

Any helper called directly by `SplitMapPane` after the move should be `internal`. Keep all stream playback/clue-reporting ownership out of this file; only marker display helpers may remain here.

- [ ] **Step 4: Remove moved drone overlay code from `MapPane.kt`**

Delete only moved declarations. Keep `SplitMapPane` state ownership and overlay mutation sequencing unchanged.

- [ ] **Step 5: Run local track and pilot display tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'org.ncssar.rid2caltopo.video.MapPaneLocalTrackSeedTest' --tests 'org.ncssar.rid2caltopo.video.MapPanePilotDisplayTest'
```

Expected: PASS.

- [ ] **Step 6: Run Kotlin compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt app/src/main/java/org/ncssar/rid2caltopo/video/MapPaneDroneOverlays.kt
git commit -m "Extract MapPane drone overlay helpers"
```

Expected: Commit succeeds.

---

### Task 8: Final Boundary Audit and Verification

**Files:**
- Modify if needed: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`
- Modify if needed: extracted `MapPane*.kt` files
- Test: focused MapPane and compile gates

- [ ] **Step 1: Audit public entrypoint and stream boundary**

Run:

```bash
rg -n "internal fun SplitMapPane|StreamTile|StreamRegistry|clue|Pair Anyway|designatorStateFor" app/src/main/java/org/ncssar/rid2caltopo/video/MapPane*.kt
```

Expected:

- `SplitMapPane` appears only in `MapPane.kt`.
- `StreamTile`, `StreamRegistry`, stream pairing text, and stream binding logic do not appear in new MapPane-owned files.
- The word `clue` may appear only in map artifact/marker display helpers, not stream clue reporting workflows.

- [ ] **Step 2: Audit file sizes**

Run:

```bash
wc -l app/src/main/java/org/ncssar/rid2caltopo/video/MapPane*.kt
```

Expected: `MapPane.kt` is materially smaller than its pre-refactor baseline of about 7,596 lines, and extracted files have clear responsibilities. If one extracted file is unexpectedly huge because it absorbed unrelated behavior, split it before final verification.

- [ ] **Step 3: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: No output and exit code 0.

- [ ] **Step 4: Run focused MapPane tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'org.ncssar.rid2caltopo.video.MapPane*' --tests 'org.ncssar.rid2caltopo.data.PilotDisplayPrefsTest'
```

Expected: PASS.

- [ ] **Step 5: Run Kotlin compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 6: Run broader release gate if extraction touched shared runtime behavior**

Run this if any task changed behavior beyond mechanical extraction:

```bash
./gradlew :app:releaseCheck
```

Expected: PASS.

- [ ] **Step 7: Commit final cleanup if needed**

If Step 1 or Step 2 required cleanup edits, run:

```bash
git add app/src/main/java/org/ncssar/rid2caltopo/video/MapPane*.kt
git commit -m "Tighten MapPane facade boundaries"
```

Expected: Commit succeeds. If no cleanup edits were needed, do not create an empty commit.

---

## Self-Review Checklist

- Spec coverage: Tasks preserve `SplitMapPane`, keep `StreamsScreen.kt` functionally unchanged, split map-owned files by responsibility, and verify the stream boundary.
- Placeholder scan: The plan contains no unresolved placeholders.
- Type consistency: New files stay in package `org.ncssar.rid2caltopo.video`; moved `internal` declarations remain accessible to existing tests and call sites.
- Behavior preservation: Each task moves existing code first, compiles immediately, and avoids ownership changes unless required for cross-file visibility.
