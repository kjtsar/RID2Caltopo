# Local Drone Display Preferences Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add local pilot-keyed display preferences for drone track colors, bearing-to-edge overlays, and explicit `ATO/AGL/RNG/HDG` drone labels.

**Architecture:** Keep this feature local to MapPane display state. Store pilot preferences in a small SharedPreferences-backed data helper keyed by normalized `CtDroneSpec.owner`, then have MapPane resolve the current pilot for each rendered drone and redraw all local overlays from the latest preference snapshot. Keep CalTopo, KMZ, and tracker publication paths untouched.

**Tech Stack:** Kotlin, Jetpack Compose, osmdroid `Polyline`/`Marker` overlays, Android `SharedPreferences`, existing JUnit/Gradle unit tests.

---

## Packet Ledger

- **Packet A: Preference Model and Store**  
  Owner: child. Files: `app/src/main/java/org/ncssar/rid2caltopo/data/PilotDisplayPrefs.kt`, `app/src/test/java/org/ncssar/rid2caltopo/data/PilotDisplayPrefsTest.kt`.  
  Acceptance: callsign normalization, default colors, update/reset semantics, and invalid color fallback are covered by tests.

- **Packet B: MapPane Pure Rendering Helpers**  
  Owner: child. Files: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`, `app/src/test/java/org/ncssar/rid2caltopo/video/MapPaneArtifactOverlayStateTest.kt`.  
  Acceptance: helper tests cover pilot preference resolution, track color fanout, status label formatting, and bearing-to-edge endpoint calculation.

- **Packet C: MapPane UI Integration**  
  Owner: child after A and B are available. Files: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`.  
  Acceptance: tapping a drone marker exposes local display settings with active/archive color pickers, bearing toggle, reset, and immediate redraw across all visible tracks for that pilot.

- **Packet D: Integrated Verification**  
  Owner: parent. Commands: focused unit tests, compile, `git diff --check`.  
  Acceptance: all packet tests pass together, Kotlin compiles, and no CalTopo/tracker/KMZ publication paths are changed.

## File Structure

- Create `app/src/main/java/org/ncssar/rid2caltopo/data/PilotDisplayPrefs.kt` for the local preference model, normalization, validation, load/save/reset, and immutable snapshots.
- Create `app/src/test/java/org/ncssar/rid2caltopo/data/PilotDisplayPrefsTest.kt` for pure JVM tests of normalization and preference behavior.
- Modify `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt` for pure helper functions and UI/render integration. Keep the helper functions small and `internal` so JVM tests can exercise geometry and formatting without rendering an Android `MapView`.
- Modify `app/src/test/java/org/ncssar/rid2caltopo/video/MapPaneArtifactOverlayStateTest.kt` or add a nearby `MapPanePilotDisplayTest.kt` for MapPane helper tests.

## Task 1: Pilot Preference Store

**Files:**
- Create: `app/src/main/java/org/ncssar/rid2caltopo/data/PilotDisplayPrefs.kt`
- Create: `app/src/test/java/org/ncssar/rid2caltopo/data/PilotDisplayPrefsTest.kt`

- [ ] **Step 1: Write failing tests for normalization and defaults**

Add tests like:

```kotlin
package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PilotDisplayPrefsTest {
    @Test
    fun normalizePilotCallsign_trimsUppercasesAndRejectsBlank() {
        assertEquals("HARRY1", normalizePilotCallsign(" harry1 "))
        assertEquals("JENNIFER7", normalizePilotCallsign("Jennifer7"))
        assertNull(normalizePilotCallsign("   "))
    }

    @Test
    fun pilotDisplayPreference_defaultsMatchMapPaneColors() {
        val pref = PilotDisplayPreference()

        assertEquals("#1E88E5", pref.activeTrackColor)
        assertEquals("#FF00FF", pref.archiveTrackColor)
        assertEquals(false, pref.bearingEnabled)
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.data.PilotDisplayPrefsTest
```

Expected: fail because `PilotDisplayPreference` and `normalizePilotCallsign` do not exist.

- [ ] **Step 3: Implement the model and normalizer**

Create `PilotDisplayPrefs.kt`:

```kotlin
package org.ncssar.rid2caltopo.data

import android.content.Context
import java.util.Locale

const val DEFAULT_ACTIVE_TRACK_COLOR = "#1E88E5"
const val DEFAULT_ARCHIVE_TRACK_COLOR = "#FF00FF"

data class PilotDisplayPreference(
    val activeTrackColor: String = DEFAULT_ACTIVE_TRACK_COLOR,
    val archiveTrackColor: String = DEFAULT_ARCHIVE_TRACK_COLOR,
    val bearingEnabled: Boolean = false
)

fun normalizePilotCallsign(raw: String?): String? {
    val normalized = raw?.trim()?.uppercase(Locale.US).orEmpty()
    return normalized.ifBlank { null }
}

fun sanitizeTrackColor(raw: String?, fallback: String): String {
    val value = raw?.trim().orEmpty()
    val candidate = when {
        Regex("^#[0-9a-fA-F]{6}$").matches(value) -> value.uppercase(Locale.US)
        Regex("^[0-9a-fA-F]{6}$").matches(value) -> "#${value.uppercase(Locale.US)}"
        else -> return fallback
    }
    return candidate
}

object PilotDisplayPrefs {
    private const val PREFS_NAME = "pilot_display_prefs"
    private const val KEY_ACTIVE_SUFFIX = ".active"
    private const val KEY_ARCHIVE_SUFFIX = ".archive"
    private const val KEY_BEARING_SUFFIX = ".bearing"

    fun load(context: Context, pilotCallsign: String?): PilotDisplayPreference {
        val key = normalizePilotCallsign(pilotCallsign) ?: return PilotDisplayPreference()
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return PilotDisplayPreference(
            activeTrackColor = sanitizeTrackColor(prefs.getString(key + KEY_ACTIVE_SUFFIX, null), DEFAULT_ACTIVE_TRACK_COLOR),
            archiveTrackColor = sanitizeTrackColor(prefs.getString(key + KEY_ARCHIVE_SUFFIX, null), DEFAULT_ARCHIVE_TRACK_COLOR),
            bearingEnabled = prefs.getBoolean(key + KEY_BEARING_SUFFIX, false)
        )
    }

    fun save(context: Context, pilotCallsign: String?, preference: PilotDisplayPreference): Boolean {
        val key = normalizePilotCallsign(pilotCallsign) ?: return false
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key + KEY_ACTIVE_SUFFIX, sanitizeTrackColor(preference.activeTrackColor, DEFAULT_ACTIVE_TRACK_COLOR))
            .putString(key + KEY_ARCHIVE_SUFFIX, sanitizeTrackColor(preference.archiveTrackColor, DEFAULT_ARCHIVE_TRACK_COLOR))
            .putBoolean(key + KEY_BEARING_SUFFIX, preference.bearingEnabled)
            .apply()
        return true
    }

    fun reset(context: Context, pilotCallsign: String?): Boolean {
        val key = normalizePilotCallsign(pilotCallsign) ?: return false
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key + KEY_ACTIVE_SUFFIX)
            .remove(key + KEY_ARCHIVE_SUFFIX)
            .remove(key + KEY_BEARING_SUFFIX)
            .apply()
        return true
    }
}
```

- [ ] **Step 4: Add tests for sanitize/update/reset behavior**

Extend `PilotDisplayPrefsTest.kt` with:

```kotlin
@Test
fun sanitizeTrackColor_acceptsHashOrBareHexAndFallsBack() {
    assertEquals("#00AAFF", sanitizeTrackColor("#00aaff", "#111111"))
    assertEquals("#AA00CC", sanitizeTrackColor("aa00cc", "#111111"))
    assertEquals("#111111", sanitizeTrackColor("blue", "#111111"))
}
```

Use Robolectric only if this repo already has it configured; otherwise keep persistence smoke coverage for an instrumentation or integration child and leave JVM tests on pure helpers.

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.data.PilotDisplayPrefsTest
```

Expected: pass.

## Task 2: MapPane Helper Tests and Pure Functions

**Files:**
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`
- Modify or create: `app/src/test/java/org/ncssar/rid2caltopo/video/MapPanePilotDisplayTest.kt`

- [ ] **Step 1: Write failing tests for status label formatting**

Add:

```kotlin
package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Test

class MapPanePilotDisplayTest {
    @Test
    fun droneStatusLabel_includesExplicitUnitsAndHeading() {
        assertEquals(
            "ATO:125' AGL:90' RNG:420' HDG:273°",
            droneStatusLabelText(
                atoFeet = 125.2,
                aglFeet = 90.4,
                aglStale = false,
                rangeFeet = 420.0,
                headingDeg = 273.2
            )
        )
    }

    @Test
    fun droneStatusLabel_usesMissingTokensAndStaleAglMarker() {
        assertEquals(
            "ATO:--' AGL:75?' RNG:--' HDG:--°",
            droneStatusLabelText(
                atoFeet = null,
                aglFeet = 75.0,
                aglStale = true,
                rangeFeet = null,
                headingDeg = null
            )
        )
    }
}
```

- [ ] **Step 2: Add failing tests for bearing edge calculation**

Add:

```kotlin
@Test
fun bearingLineToViewportEdge_extendsEastToRightEdge() {
    val result = bearingLineToViewportEdge(
        startX = 50.0,
        startY = 50.0,
        headingDeg = 90.0,
        viewportWidth = 100,
        viewportHeight = 100
    )

    assertEquals(100.0, result!!.endX, 0.01)
    assertEquals(50.0, result.endY, 0.01)
}

@Test
fun bearingLineToViewportEdge_returnsNullForInvalidHeading() {
    assertEquals(
        null,
        bearingLineToViewportEdge(50.0, 50.0, Double.NaN, 100, 100)
    )
}
```

- [ ] **Step 3: Implement helper functions**

Add small helpers near other MapPane pure helpers:

```kotlin
internal data class ScreenLine(
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double
)

internal fun droneStatusLabelText(
    atoFeet: Double?,
    aglFeet: Double?,
    aglStale: Boolean,
    rangeFeet: Double?,
    headingDeg: Double?
): String {
    val ato = atoFeet?.takeIf { kotlin.math.abs(it) <= LABEL_MAX_ABS_FEET }?.let { "%.0f".format(Locale.US, it) } ?: "--"
    val agl = aglFeet?.takeIf { kotlin.math.abs(it) <= LABEL_MAX_ABS_FEET }?.let { "%.0f%s".format(Locale.US, it, if (aglStale) "?" else "") } ?: "--"
    val rng = rangeFeet?.let { "%.0f".format(Locale.US, it) } ?: "--"
    val hdg = headingDeg?.takeIf { it.isFinite() }?.let { "%.0f".format(Locale.US, normalizeDegrees(it)) } ?: "--"
    return "ATO:$ato' AGL:$agl' RNG:$rng' HDG:$hdg°"
}

internal fun bearingLineToViewportEdge(
    startX: Double,
    startY: Double,
    headingDeg: Double?,
    viewportWidth: Int,
    viewportHeight: Int
): ScreenLine? {
    val heading = headingDeg?.takeIf { it.isFinite() } ?: return null
    if (viewportWidth <= 0 || viewportHeight <= 0) return null
    val radians = Math.toRadians(normalizeDegrees(heading))
    val dx = kotlin.math.sin(radians)
    val dy = -kotlin.math.cos(radians)
    val candidates = mutableListOf<Double>()
    if (dx > 0.0) candidates += (viewportWidth.toDouble() - startX) / dx
    if (dx < 0.0) candidates += (0.0 - startX) / dx
    if (dy > 0.0) candidates += (viewportHeight.toDouble() - startY) / dy
    if (dy < 0.0) candidates += (0.0 - startY) / dy
    val distance = candidates.filter { it > 0.0 && it.isFinite() }.minOrNull() ?: return null
    return ScreenLine(startX, startY, startX + dx * distance, startY + dy * distance)
}
```

- [ ] **Step 4: Run focused MapPane helper tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.MapPanePilotDisplayTest
```

Expected: pass.

## Task 3: MapPane Rendering and Settings UI

**Files:**
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`

- [ ] **Step 1: Load and remember pilot preference state**

In `MapPane`, add a remembered state map keyed by normalized pilot callsign:

```kotlin
val pilotDisplayPrefsByKey = remember { mutableStateMapOf<String, PilotDisplayPreference>() }

fun preferenceForDrone(point: DroneMapPoint): PilotDisplayPreference {
    val key = normalizePilotCallsign(point.droneSpec?.getOwner())
    return if (key == null) {
        PilotDisplayPreference()
    } else {
        pilotDisplayPrefsByKey.getOrPut(key) { PilotDisplayPrefs.load(context, key) }
    }
}
```

Import `PilotDisplayPreference`, `PilotDisplayPrefs`, and `normalizePilotCallsign` from `org.ncssar.rid2caltopo.data`.

- [ ] **Step 2: Apply preferences to all tracks for a pilot**

Build a mapping from local track designator to preference before drawing track polylines:

```kotlin
val preferenceByMappedId = dronePoints.associate { point ->
    localTrackDesignator(point.designator) to preferenceForDrone(point)
}
```

Use the resolved colors when creating local track polylines:

```kotlin
val pref = preferenceByMappedId[mappedId] ?: PilotDisplayPreference()
applyPolylineStyle(this, AndroidColor.parseColor(pref.archiveTrackColor), 2.0f)
```

and:

```kotlin
val pref = preferenceByMappedId[mappedId] ?: PilotDisplayPreference()
applyPolylineStyle(this, AndroidColor.parseColor(pref.activeTrackColor), 4.0f)
```

If a color parse fails, fall back to `DEFAULT_ACTIVE_TRACK_COLOR` or `DEFAULT_ARCHIVE_TRACK_COLOR`.

- [ ] **Step 3: Replace label construction**

Replace the current compact label token construction with:

```kotlin
val labelText = droneStatusLabelText(
    atoFeet = labelAtoFeet,
    aglFeet = labelAglFeet,
    aglStale = labelAglStale,
    rangeFeet = labelRangeFeet,
    headingDeg = headingDeg
)
```

Keep the existing label placement/decluttering logic.

- [ ] **Step 4: Add bearing overlay draw**

After the drone marker position is known and before labels are drawn, project the rendered drone point into screen pixels, call `bearingLineToViewportEdge`, and add a `Polyline` using `mapView.projection.fromPixels(...)` for the endpoint. Use the pilot preference's active color or a high-contrast derivative; keep it local-only and do not create CalTopo objects.

- [ ] **Step 5: Add local settings sheet state**

Add a selected-pilot settings state:

```kotlin
data class PilotDisplaySettingsState(
    val pilotKey: String,
    val displayName: String,
    val preference: PilotDisplayPreference
)
```

On marker click, after existing focus/open bubble behavior, set this state when `normalizePilotCallsign(point.droneSpec?.getOwner())` is non-null.

- [ ] **Step 6: Add color picker UI**

Use Android's built-in color int flow or a simple Compose dialog with color swatches plus hex preview. The user requested a color picker, not raw text entry; a palette grid is acceptable if it includes enough distinct SAR-friendly colors and shows current active/archive values. Cancel must leave the previous color unchanged.

The save path must update `pilotDisplayPrefsByKey[pilotKey]`, call `PilotDisplayPrefs.save(context, pilotKey, updatedPreference)`, and invalidate MapPane so all visible tracks for that pilot redraw together.

- [ ] **Step 7: Run compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: success.

## Task 4: Integrated Verification

**Files:**
- Verify all touched files.

- [ ] **Step 1: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.data.PilotDisplayPrefsTest --tests org.ncssar.rid2caltopo.video.MapPanePilotDisplayTest
```

Expected: pass.

- [ ] **Step 2: Run existing MapPane tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.MapPaneArtifactOverlayStateTest --tests org.ncssar.rid2caltopo.video.MapPaneLocalTrackSeedTest
```

Expected: pass.

- [ ] **Step 3: Compile debug Kotlin**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: success.

- [ ] **Step 4: Check whitespace**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 5: Parent review**

Parent reviews:

- No custom color writes in `CaltopoSession.java`, `CaltopoLiveTrack.java`, `CaltopoMap.java`, or `WaypointTrack.java`.
- Bearing overlay exists only in MapPane local overlays.
- Pilot color update is keyed by normalized `owner` and redraws all current local tracks for that pilot.
- Drone label uses `ATO`, `AGL`, `RNG`, and `HDG`.

## Child Packet Prompts

### Child A Prompt

Implement Packet A from `docs/superpowers/plans/2026-06-12-local-drone-display-preferences.md`: create the local pilot display preference model/store and focused JVM tests. Stay in `PilotDisplayPrefs.kt` plus `PilotDisplayPrefsTest.kt`. Do not touch MapPane or CalTopo/tracker publication code. Run the focused `PilotDisplayPrefsTest` Gradle command and report results plus changed files.

### Child B Prompt

Implement Packet B from `docs/superpowers/plans/2026-06-12-local-drone-display-preferences.md`: add MapPane pure helper tests and helper functions for status label formatting, bearing-to-edge geometry, and preference color resolution where practical. Stay in MapPane helper/test seams; do not add UI dialogs and do not touch CalTopo/tracker publication code. Run the focused MapPane helper test command and report results plus changed files.

### Child C Prompt

After Packets A and B land, implement Packet C from `docs/superpowers/plans/2026-06-12-local-drone-display-preferences.md`: integrate preferences into MapPane rendering and marker-click settings UI with color pickers, bearing toggle, reset, and all-visible-tracks redraw for the selected pilot. Do not touch CalTopo/tracker/KMZ publication paths. Run focused MapPane tests and `:app:compileDebugKotlin`, then report results plus changed files.
