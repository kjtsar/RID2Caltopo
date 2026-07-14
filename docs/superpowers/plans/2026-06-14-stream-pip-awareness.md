# Stream PiP Awareness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional Picture-in-Picture awareness mode where a focused stream can show a compact, view-only map inset, and map-dominant mode can show a passive focused-video inset.

**Architecture:** Keep PiP orchestration in `StreamsScreen`, state and persistence in `StreamsViewModel`, and map visual density/follow behavior in `SplitMapPane` via an explicit presentation mode. The inset wrapper owns tap/long-press/resize interactions so inset map/video content remains passive unless promoted to the dominant view.

**Tech Stack:** Kotlin, Jetpack Compose, osmdroid `MapView`, existing `StreamsViewModel`, existing `StreamTile`, existing `SplitMapPane`, JVM unit tests with Gradle.

---

## File Structure

- Modify `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt`
  - Add PiP state, size clamping helpers, persistence hooks, and a focused-drone follow target helper.
- Modify `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsScreen.kt`
  - Add the `PiP` top-bar toggle and PiP overlay shell for stream-dominant and map-dominant layouts.
- Modify `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`
  - Add `MapPanePresentationMode.Full` and `MapPanePresentationMode.Inset`.
  - Make inset mode compact, view-only, and throttled-follow aware.
- Create `app/src/test/java/org/ncssar/rid2caltopo/video/StreamPipStateTest.kt`
  - Unit-test size clamping and PiP state transitions.
- Create `app/src/test/java/org/ncssar/rid2caltopo/video/MapPanePresentationModeTest.kt`
  - Unit-test compact scaling values.

---

### Task 1: Add PiP State Helpers

**Files:**
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt`
- Create: `app/src/test/java/org/ncssar/rid2caltopo/video/StreamPipStateTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/ncssar/rid2caltopo/video/StreamPipStateTest.kt`:

```kotlin
package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamPipStateTest {
    @Test
    fun clampStreamPipInsetFraction_keepsPanelUsable() {
        assertEquals(0.22f, clampStreamPipInsetFraction(0.05f), 0.0001f)
        assertEquals(0.33f, clampStreamPipInsetFraction(0.33f), 0.0001f)
        assertEquals(0.55f, clampStreamPipInsetFraction(0.90f), 0.0001f)
    }

    @Test
    fun nextStreamPipEnabledToggle_exitsEditorWhenTurningOff() {
        val current = StreamPipUiState(
            enabled = true,
            insetFraction = 0.33f,
            editorMode = true
        )

        assertEquals(
            StreamPipUiState(enabled = false, insetFraction = 0.33f, editorMode = false),
            current.withEnabled(false)
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.StreamPipStateTest
```

Expected: compile failure for missing `StreamPipUiState` and `clampStreamPipInsetFraction`.

- [ ] **Step 3: Add minimal state helpers**

In `StreamsViewModel.kt`, near `StreamsLayoutMode`, add:

```kotlin
data class StreamPipUiState(
    val enabled: Boolean,
    val insetFraction: Float,
    val editorMode: Boolean = false
) {
    fun withEnabled(nextEnabled: Boolean): StreamPipUiState =
        copy(enabled = nextEnabled, editorMode = if (nextEnabled) editorMode else false)
}

internal const val STREAM_PIP_MIN_INSET_FRACTION = 0.22f
internal const val STREAM_PIP_DEFAULT_INSET_FRACTION = 0.33f
internal const val STREAM_PIP_MAX_INSET_FRACTION = 0.55f

internal fun clampStreamPipInsetFraction(value: Float): Float {
    if (!value.isFinite()) return STREAM_PIP_DEFAULT_INSET_FRACTION
    return value.coerceIn(STREAM_PIP_MIN_INSET_FRACTION, STREAM_PIP_MAX_INSET_FRACTION)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.StreamPipStateTest
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 2: Persist PiP Toggle and Size

**Files:**
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt`
- Modify: `app/src/test/java/org/ncssar/rid2caltopo/video/StreamPipStateTest.kt`

- [ ] **Step 1: Extend the test for persisted value normalization**

Add to `StreamPipStateTest`:

```kotlin
@Test
fun streamPipUiState_normalizesPersistedInsetFraction() {
    val state = StreamPipUiState.fromPersisted(
        enabled = true,
        insetFraction = Float.NaN
    )

    assertEquals(
        StreamPipUiState(
            enabled = true,
            insetFraction = STREAM_PIP_DEFAULT_INSET_FRACTION,
            editorMode = false
        ),
        state
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.StreamPipStateTest
```

Expected: compile failure for missing `StreamPipUiState.fromPersisted`.

- [ ] **Step 3: Add normalized constructor and ViewModel state**

Update `StreamPipUiState` in `StreamsViewModel.kt`:

```kotlin
data class StreamPipUiState(
    val enabled: Boolean,
    val insetFraction: Float,
    val editorMode: Boolean = false
) {
    fun withEnabled(nextEnabled: Boolean): StreamPipUiState =
        copy(enabled = nextEnabled, editorMode = if (nextEnabled) editorMode else false)

    companion object {
        fun fromPersisted(enabled: Boolean, insetFraction: Float): StreamPipUiState =
            StreamPipUiState(
                enabled = enabled,
                insetFraction = clampStreamPipInsetFraction(insetFraction),
                editorMode = false
            )
    }
}
```

Inside `StreamsViewModel`, near `_layoutMode`, add:

```kotlin
private val streamPipPrefs by lazy {
    getApplication<Application>()
        .applicationContext
        .getSharedPreferences("stream_pip_prefs", android.content.Context.MODE_PRIVATE)
}

private val _streamPipUiState = mutableStateOf(
    StreamPipUiState.fromPersisted(
        enabled = streamPipPrefs.getBoolean("enabled", false),
        insetFraction = streamPipPrefs.getFloat("inset_fraction", STREAM_PIP_DEFAULT_INSET_FRACTION)
    )
)
val streamPipUiState: StreamPipUiState
    get() = _streamPipUiState.value

fun setStreamPipEnabled(enabled: Boolean) {
    val next = _streamPipUiState.value.withEnabled(enabled)
    _streamPipUiState.value = next
    streamPipPrefs.edit().putBoolean("enabled", next.enabled).apply()
}

fun setStreamPipEditorMode(editorMode: Boolean) {
    _streamPipUiState.value = _streamPipUiState.value.copy(
        editorMode = editorMode && _streamPipUiState.value.enabled
    )
}

fun setStreamPipInsetFraction(insetFraction: Float) {
    val clamped = clampStreamPipInsetFraction(insetFraction)
    _streamPipUiState.value = _streamPipUiState.value.copy(insetFraction = clamped)
    streamPipPrefs.edit().putFloat("inset_fraction", clamped).apply()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.StreamPipStateTest
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 3: Add PiP Toggle to the Streams Top Bar

**Files:**
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsScreen.kt`

- [ ] **Step 1: Add UI state read and toggle action**

In `StreamsScreen`, after `val persistedLayoutMode by viewModel.layoutMode.collectAsStateWithLifecycle()`, add:

```kotlin
val streamPipUiState = viewModel.streamPipUiState
```

In the `TopAppBar` actions `Row`, before the existing `Split` chip block, add:

```kotlin
LayoutToggleChip(
    label = if (streamPipUiState.enabled) "PiP:On" else "PiP:Off",
    selected = streamPipUiState.enabled,
    onClick = { viewModel.setStreamPipEnabled(!streamPipUiState.enabled) }
)
Spacer(Modifier.width(6.dp))
```

- [ ] **Step 2: Compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 4: Add PiP Overlay Shell

**Files:**
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsScreen.kt`

- [ ] **Step 1: Add overlay interaction composable**

Add below `SplitStreamsAndMap` in `StreamsScreen.kt`:

```kotlin
@Composable
private fun StreamPipInsetFrame(
    editorMode: Boolean,
    insetFraction: Float,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onResizeFractionDelta: (Float) -> Unit,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val shortestSide = minOf(maxWidth, maxHeight)
        val insetSize = shortestSide * insetFraction
        val density = LocalDensity.current

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .width(insetSize)
                .height(insetSize)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            content()
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(editorMode) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onLongPress = { onLongPress() }
                        )
                    }
            )
            if (editorMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .width(44.dp)
                        .height(44.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                        .pointerInput(Unit) {
                            detectDragGestures { _, dragAmount ->
                                val deltaPx = -dragAmount.x - dragAmount.y
                                val denominatorPx = with(density) { shortestSide.toPx() }
                                if (denominatorPx > 0f) {
                                    onResizeFractionDelta(deltaPx / denominatorPx)
                                }
                            }
                        }
                )
            }
        }
    }
}
```

- [ ] **Step 2: Compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: compile failure for missing imports if `matchParentSize` or shapes imports are absent.

- [ ] **Step 3: Add missing imports**

If needed, add imports at the top of `StreamsScreen.kt`:

```kotlin
import androidx.compose.foundation.layout.matchParentSize
```

No import is needed for `MaterialTheme.shapes.small` because `MaterialTheme` is already imported.

- [ ] **Step 4: Compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 5: Render Map Inset in Stream-Dominant Mode

**Files:**
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsScreen.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`

- [ ] **Step 1: Add MapPane presentation enum**

In `MapPane.kt`, near `MAP_PANE_TAG`, add:

```kotlin
internal enum class MapPanePresentationMode {
    Full,
    Inset
}
```

Change `SplitMapPane` signature:

```kotlin
internal fun SplitMapPane(
    viewModel: StreamsViewModel,
    modifier: Modifier = Modifier,
    onSingleTapFocus: (() -> Unit)? = null,
    presentationMode: MapPanePresentationMode = MapPanePresentationMode.Full
)
```

- [ ] **Step 2: Render the map inset overlay for stream-dominant mode**

In `StreamsScreen`, inside the outer `Box(Modifier.fillMaxSize())`, after the `when (layoutMode)` block and before modal sheets, add:

```kotlin
val pipEnabled = streamPipUiState.enabled && externalContentMode == null
if (pipEnabled && layoutMode == StreamsLayoutMode.Streams && focusedPath != null) {
    StreamPipInsetFrame(
        editorMode = streamPipUiState.editorMode,
        insetFraction = streamPipUiState.insetFraction,
        onTap = { viewModel.setLayoutMode(StreamsLayoutMode.Map) },
        onLongPress = { viewModel.setStreamPipEditorMode(true) },
        onResizeFractionDelta = { delta ->
            viewModel.setStreamPipInsetFraction(streamPipUiState.insetFraction + delta)
        }
    ) {
        SplitMapPane(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
            presentationMode = MapPanePresentationMode.Inset
        )
    }
}
```

- [ ] **Step 3: Compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 6: Render Focused Video Inset in Map-Dominant Mode

**Files:**
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsScreen.kt`

- [ ] **Step 1: Add video inset overlay**

Below the stream-dominant PiP block from Task 5, add:

```kotlin
if (pipEnabled && layoutMode == StreamsLayoutMode.Map && focusedPath != null) {
    StreamPipInsetFrame(
        editorMode = streamPipUiState.editorMode,
        insetFraction = streamPipUiState.insetFraction,
        onTap = { viewModel.setLayoutMode(StreamsLayoutMode.Streams) },
        onLongPress = { viewModel.setStreamPipEditorMode(true) },
        onResizeFractionDelta = { delta ->
            viewModel.setStreamPipInsetFraction(streamPipUiState.insetFraction + delta)
        }
    ) {
        StreamsGrid(
            viewModel = viewModel,
            allowCapturedVideoPicker = false,
            onMapStatusTap = onMapStatusTap,
            modifier = Modifier.fillMaxSize()
        )
    }
}
```

The transparent interaction layer in `StreamPipInsetFrame` must remain above `StreamsGrid`, so the small video inset is passive.

- [ ] **Step 2: Compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 7: Make Inset Map Compact and View-Only

**Files:**
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`
- Create: `app/src/test/java/org/ncssar/rid2caltopo/video/MapPanePresentationModeTest.kt`

- [ ] **Step 1: Write compact-scale tests**

Create `MapPanePresentationModeTest.kt`:

```kotlin
package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Test

class MapPanePresentationModeTest {
    @Test
    fun mapPaneMarkerScale_isSmallerForInsetMode() {
        assertEquals(1.0f, mapPaneMarkerScale(MapPanePresentationMode.Full), 0.0001f)
        assertEquals(0.55f, mapPaneMarkerScale(MapPanePresentationMode.Inset), 0.0001f)
    }

    @Test
    fun mapPaneLineScale_isThinnerForInsetMode() {
        assertEquals(1.0f, mapPaneLineScale(MapPanePresentationMode.Full), 0.0001f)
        assertEquals(0.65f, mapPaneLineScale(MapPanePresentationMode.Inset), 0.0001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.MapPanePresentationModeTest
```

Expected: compile failure for missing `mapPaneMarkerScale` and `mapPaneLineScale`.

- [ ] **Step 3: Add scale helpers**

In `MapPane.kt`, near `MapPanePresentationMode`, add:

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
```

- [ ] **Step 4: Apply compact mode in MapPane**

Inside `SplitMapPane`, near other presentation values, add:

```kotlin
val isInsetMode = presentationMode == MapPanePresentationMode.Inset
val markerScale = mapPaneMarkerScale(presentationMode)
val lineScale = mapPaneLineScale(presentationMode)
```

Apply line scale to local/archive tracks and bearing overlays:

```kotlin
applyPolylineStyle(this, trackColor, 2.0f * lineScale)
applyPolylineStyle(this, trackColor, 4.0f * lineScale)
```

For `markerIconForArtifactSymbol(...)`, add a `scale: Float = 1.0f` parameter and scale the generated bitmap dimensions before returning the drawable. Pass `scale = markerScale` from MapPane marker creation sites.

For `buildDroneMarkerDrawable(...)`, add a `scale: Float = 1.0f` parameter and scale the final bitmap dimensions. Pass `scale = markerScale`.

Disable inset info windows and popup state by guarding marker listeners:

```kotlin
if (!isInsetMode) {
    setOnMarkerClickListener { tappedMarker, _ ->
        if (focusedPath != point.designator) {
            viewModel.toggleFocus(point.designator)
        }
        if (tappedMarker.isInfoWindowShown) {
            tappedMarker.closeInfoWindow()
            openBubbleDesignator = null
        } else {
            openBubbleDesignator = point.designator
        }
        true
    }
}
```

Suppress label overlays in inset mode:

```kotlin
if (!isInsetMode) {
    droneLabelSpecs.add(...)
}
```

- [ ] **Step 5: Run tests and compile**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.MapPanePresentationModeTest
./gradlew :app:compileDebugKotlin
```

Expected: both commands end with `BUILD SUCCESSFUL`.

---

### Task 8: Add Throttled Inset Follow Mode

**Files:**
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`

- [ ] **Step 1: Add follow constants**

In `MapPane.kt`, near other map constants, add:

```kotlin
private const val INSET_FOLLOW_INTERVAL_MS = 500L
private const val INSET_FOLLOW_MIN_MOVE_METERS = 1.0
```

- [ ] **Step 2: Add latest focused point state in SplitMapPane**

Inside `SplitMapPane`, after `val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()`, add:

```kotlin
var lastInsetFollowAtMs by remember { mutableStateOf(0L) }
var lastInsetFollowPoint by remember { mutableStateOf<GeoPoint?>(null) }
```

- [ ] **Step 3: Recenter in AndroidView update**

Inside the `AndroidView(update = { mapView -> ... })` block, after `dronePoints` has been computed and before `mapView.invalidate()`, add:

```kotlin
if (presentationMode == MapPanePresentationMode.Inset) {
    val nowMs = System.currentTimeMillis()
    val focusPoint = focusedPath?.let { focus ->
        dronePoints.firstOrNull { it.designator == focus }
    }
    if (focusPoint != null && nowMs - lastInsetFollowAtMs >= INSET_FOLLOW_INTERVAL_MS) {
        val target = GeoPoint(focusPoint.lat, focusPoint.lng)
        val previous = lastInsetFollowPoint
        val movedEnough = previous == null ||
            previous.distanceToAsDouble(target) >= INSET_FOLLOW_MIN_MOVE_METERS
        if (movedEnough) {
            mapView.controller.setCenter(target)
            if (mapView.zoomLevelDouble < 14.0) {
                mapView.controller.setZoom(14.0)
            }
            lastInsetFollowPoint = target
        }
        lastInsetFollowAtMs = nowMs
    }
}
```

Do not call `viewModel.persistMapViewportState(...)` from inset follow mode. The inset should not overwrite the full MapPane viewport.

- [ ] **Step 4: Compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 9: Prevent Inset Mode from Persisting Full Map Gestures

**Files:**
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`

- [ ] **Step 1: Disable full map gesture controls in inset mode**

In the `MapView(context).apply { ... }` factory, change:

```kotlin
setMultiTouchControls(true)
```

to:

```kotlin
setMultiTouchControls(!isInsetMode)
```

In `setOnTouchListener`, wrap operator-adjusted persistence logic:

```kotlin
if (!isInsetMode) {
    when (event?.actionMasked) {
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_POINTER_DOWN,
        MotionEvent.ACTION_MOVE -> {
            if (!operatorAdjustedViewport) {
                operatorAdjustedViewport = true
                CTDebug(MAP_PANE_TAG, "Map viewport operator-adjusted; suppressing startup recenter")
            }
        }
    }
}
false
```

- [ ] **Step 2: Guard viewport persistence in map listener**

Where the `MapListener` persists viewport state, change each direct call:

```kotlin
viewModel.persistMapViewportState(mapCenter, zoomLevelDouble)
```

to:

```kotlin
if (!isInsetMode) {
    viewModel.persistMapViewportState(mapCenter, zoomLevelDouble)
}
```

- [ ] **Step 3: Compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 10: Integration Verification

**Files:**
- Verify only.

- [ ] **Step 1: Run focused JVM tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.StreamPipStateTest
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.MapPanePresentationModeTest
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.MapPanePilotDisplayTest
```

Expected: each command ends with `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run compile verification**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 4: On-device manual smoke test**

Use an Android device or emulator with at least one live or captured stream:

1. Open Streams.
2. Focus one stream.
3. Toggle `PiP:On`.
4. Confirm stream-dominant mode shows bottom-right compact map inset.
5. Tap the inset and confirm MapPane becomes dominant.
6. Tap the video inset and confirm stream-dominant mode returns.
7. Long-press either inset and confirm the resize handle appears.
8. Drag the handle and confirm the size changes.
9. Navigate away/back and confirm the size persists while editor mode does not persist.
10. With drone telemetry available, confirm the inset map recenters no faster than a few times per second and does not affect video smoothness.

- [ ] **Step 5: Release gate if requested**

Run the full gate when the branch is otherwise ready:

```bash
./gradlew :app:releaseCheck
```

Expected: `BUILD SUCCESSFUL`.

---

## Self-Review Notes

- Spec coverage: The plan covers PiP toggle, stream-dominant map inset, map-dominant video inset, tap promotion, long-press editor mode, resize persistence, compact map presentation, throttled focused-drone follow, and preserving full MapPane interaction only when dominant.
- Placeholder scan: No `TBD`, `TODO`, or intentionally unspecified implementation steps are present.
- Scope check: This plan implements the real MapPane inset first. It does not implement the fallback lightweight map preview unless performance testing proves the full inset too heavy.
