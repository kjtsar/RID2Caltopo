# New Stream Shows Grid Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When any new stream becomes live, clear one-up focus so the streams screen shows all admitted live streams, while preserving the existing four-stream cap and clean fifth-stream rejection.

**Architecture:** Add a tiny pure focus-decision helper near the existing stream resync helper, then call it from `StreamsViewModel.syncStreamSessions(...)` after the newly live stream set is known and before render routing is computed. Remove the off-focus "New stream attached" toast because the grid transition becomes the alert. Keep the existing `StreamAdmissionPolicy` capacity behavior and add a regression that the fifth concurrent stream is rejected without changing the four admitted streams.

**Tech Stack:** Kotlin, Android ViewModel, Media3/ExoPlayer stream routing, JUnit4, Gradle `app:testDebugUnitTest`.

---

## File Structure

- Modify `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt`
  - Add a pure helper for focus clearing decisions.
  - Replace the current off-focus toast block with focus clearing before render routing.
- Modify `app/src/test/java/org/ncssar/rid2caltopo/video/StreamsViewModelResyncTest.kt`
  - Add pure helper tests for clearing focus when new streams arrive and preserving manual focus after the grid is visible.
- Modify `app/src/test/java/org/ncssar/rid2caltopo/video/StreamRegistryLifecycleTest.kt`
  - Add regression coverage for four admitted streams plus a clean fifth-stream capacity rejection.
- No production changes to `StreamRegistry.kt` are expected.

---

### Task 1: Add Focus Decision Tests

**Files:**
- Modify: `app/src/test/java/org/ncssar/rid2caltopo/video/StreamsViewModelResyncTest.kt`
- Later modify: `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt`

- [ ] **Step 1: Write failing tests**

Append these tests to `StreamsViewModelResyncTest`:

```kotlin
    @Test
    fun focusAfterStreamSync_clearsFocusWhenSecondStreamBecomesLive() {
        val focus = focusAfterStreamSync(
            currentFocus = "1SAR33",
            liveDesignators = setOf("1SAR33", "1sar7mn4pr"),
            newlyVisibleLiveDesignators = setOf("1sar7mn4pr"),
        )

        assertEquals(null, focus)
    }

    @Test
    fun focusAfterStreamSync_clearsFocusWhenFourthStreamBecomesLive() {
        val focus = focusAfterStreamSync(
            currentFocus = "1SAR33",
            liveDesignators = setOf("1SAR33", "1sar7mn4pr", "1sar83", "1sar50"),
            newlyVisibleLiveDesignators = setOf("1sar50"),
        )

        assertEquals(null, focus)
    }

    @Test
    fun focusAfterStreamSync_keepsManualFocusWhenNoNewStreamArrived() {
        val focus = focusAfterStreamSync(
            currentFocus = "1sar7mn4pr",
            liveDesignators = setOf("1SAR33", "1sar7mn4pr"),
            newlyVisibleLiveDesignators = emptySet(),
        )

        assertEquals("1sar7mn4pr", focus)
    }

    @Test
    fun focusAfterStreamSync_clearsFocusWhenFocusedStreamDisappears() {
        val focus = focusAfterStreamSync(
            currentFocus = "1SAR33",
            liveDesignators = setOf("1sar7mn4pr"),
            newlyVisibleLiveDesignators = emptySet(),
        )

        assertEquals(null, focus)
    }
```

- [ ] **Step 2: Run tests to verify RED**

Run:

```bash
./gradlew app:testDebugUnitTest --tests StreamsViewModelResyncTest
```

Expected: FAIL because `focusAfterStreamSync(...)` is unresolved.

- [ ] **Step 3: Add minimal helper**

In `StreamsViewModel.kt`, near `chooseResyncSnapshot(...)`, add:

```kotlin
internal fun focusAfterStreamSync(
    currentFocus: String?,
    liveDesignators: Set<String>,
    newlyVisibleLiveDesignators: Set<String>,
): String? {
    if (currentFocus == null) return null
    if (currentFocus !in liveDesignators) return null
    if (newlyVisibleLiveDesignators.isNotEmpty()) return null
    return currentFocus
}
```

- [ ] **Step 4: Run tests to verify GREEN**

Run:

```bash
./gradlew app:testDebugUnitTest --tests StreamsViewModelResyncTest
```

Expected: PASS for all `StreamsViewModelResyncTest` tests.

---

### Task 2: Apply Focus Clearing and Remove New-Stream Toast

**Files:**
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt`
- Test: `app/src/test/java/org/ncssar/rid2caltopo/video/StreamsViewModelResyncTest.kt`

- [ ] **Step 1: Replace the off-focus toast block**

In `syncStreamSessions(...)`, leave the existing early missing-focus clearing in place:

```kotlin
        val focused = _focusedPath.value
        if (focused != null && !streamsMap.containsKey(focused)) {
            CTDebug(tag, "Focused stream $focused is no longer present -> clearing focus")
            _focusedPath.value = null
        }
```

Then replace the `focusedPath` / `newlyAttachedOffFocus` toast block with:

```kotlin
        val focusAfterSync = focusAfterStreamSync(
            currentFocus = _focusedPath.value,
            liveDesignators = liveDesignators,
            newlyVisibleLiveDesignators = added,
        )
        if (focusAfterSync != _focusedPath.value) {
            val previousFocus = _focusedPath.value
            _focusedPath.value = focusAfterSync
            if (previousFocus != null && added.isNotEmpty()) {
                CTInfo(
                    tag,
                    "New stream attached while $previousFocus had focus -> clearing focus to show ${activeLiveStreams.size} streams"
                )
            }
        }
```

Delete this old block entirely:

```kotlin
        val focusedPath = _focusedPath.value
        if (streamsUiActive && focusedPath != null) {
            val newlyAttachedOffFocus = added.filter { it != focusedPath }
            if (newlyAttachedOffFocus.isNotEmpty()) {
                val msg = if (newlyAttachedOffFocus.size == 1) {
                    "New stream attached: ${newlyAttachedOffFocus.first()}"
                } else {
                    "New streams attached: ${newlyAttachedOffFocus.joinToString(", ")}"
                }
                CaltopoClient.ShowToast(msg)
                CTInfo(tag, "$msg -> keeping current focus")
            }
        }
```

- [ ] **Step 2: Run focused tests**

Run:

```bash
./gradlew app:testDebugUnitTest --tests StreamsViewModelResyncTest --tests org.ncssar.rid2caltopo.video.StreamRenderRouterTest
```

Expected: PASS. Existing `StreamRenderRouterTest` should remain unchanged because the router still behaves the same once focus is cleared by the ViewModel.

- [ ] **Step 3: Search for removed toast text**

Run:

```bash
rg -n "New stream attached|New streams attached" app/src/main/java app/src/test/java
```

Expected: no matches.

---

### Task 3: Add Four-Stream and Fifth-Rejection Regression

**Files:**
- Modify: `app/src/test/java/org/ncssar/rid2caltopo/video/StreamRegistryLifecycleTest.kt`
- Modify: `app/src/test/java/org/ncssar/rid2caltopo/video/StreamsViewModelResyncTest.kt`

- [ ] **Step 1: Keep the four-stream focus clearing test**

Keep `focusAfterStreamSync_clearsFocusWhenFourthStreamBecomesLive()` in `StreamsViewModelResyncTest`. It proves that a new third or fourth stream clears focus just like the second stream.

- [ ] **Step 2: Add fifth-stream admission test**

Append this test to `StreamRegistryLifecycleTest`:

```kotlin
    @Test
    fun fifthConcurrentLiveStream_isRejectedAndFourAdmittedStreamsRemain() {
        val initial = StreamAdmissionState(
            active = mapOf(
                "d1" to StreamInfo(designator = "d1", state = StreamState.LIVE),
                "d2" to StreamInfo(designator = "d2", state = StreamState.LIVE),
                "d3" to StreamInfo(designator = "d3", state = StreamState.LIVE),
                "d4" to StreamInfo(designator = "d4", state = StreamState.LIVE),
            ),
            stateChangedAtMs = mapOf(
                "d1" to 1_000L,
                "d2" to 1_000L,
                "d3" to 1_000L,
                "d4" to 1_000L,
            ),
            rejectedPaths = emptySet(),
        )

        val result = StreamAdmissionPolicy.admit(
            state = initial,
            designator = "d5",
            sourcePath = "d5",
            controllerProfile = StreamControllerProfile.GENERIC,
            targetState = StreamState.LIVE,
            nowMs = 2_000L,
            maxSimultaneousStreams = 4,
            staleConnectingMs = 30_000L,
            staleErrorMs = 120_000L,
        )

        assertFalse(result.admitted)
        assertTrue(result.shouldNotifyRejection)
        assertEquals("capacity", result.rejectionReason)
        assertEquals(setOf("d1", "d2", "d3", "d4"), result.state.active.keys)
        assertFalse(result.state.active.containsKey("d5"))
        assertEquals(setOf("d5"), result.state.rejectedPaths)
    }
```

- [ ] **Step 3: Run tests to verify GREEN**

Run:

```bash
./gradlew app:testDebugUnitTest --tests StreamsViewModelResyncTest --tests org.ncssar.rid2caltopo.video.StreamRegistryLifecycleTest --tests org.ncssar.rid2caltopo.video.StreamRenderRouterTest
```

Expected: PASS.

---

### Task 4: Verification and Commit

**Files:**
- Verify: `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt`
- Verify: `app/src/test/java/org/ncssar/rid2caltopo/video/StreamsViewModelResyncTest.kt`
- Verify: `app/src/test/java/org/ncssar/rid2caltopo/video/StreamRegistryLifecycleTest.kt`

- [ ] **Step 1: Run focused unit tests**

Run:

```bash
./gradlew app:testDebugUnitTest --tests StreamsViewModelResyncTest --tests org.ncssar.rid2caltopo.video.StreamRegistryLifecycleTest --tests org.ncssar.rid2caltopo.video.StreamRenderRouterTest
```

Expected: PASS.

- [ ] **Step 2: Run release check if focused tests pass**

Run:

```bash
./gradlew app:releaseCheck
```

Expected: PASS.

- [ ] **Step 3: Inspect diff**

Run:

```bash
git diff -- app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt app/src/test/java/org/ncssar/rid2caltopo/video/StreamsViewModelResyncTest.kt app/src/test/java/org/ncssar/rid2caltopo/video/StreamRegistryLifecycleTest.kt
```

Expected: Only the focus-clearing helper, toast removal, focus sync call, and focused tests are changed.

- [ ] **Step 4: Commit implementation**

Run:

```bash
git add app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt app/src/test/java/org/ncssar/rid2caltopo/video/StreamsViewModelResyncTest.kt app/src/test/java/org/ncssar/rid2caltopo/video/StreamRegistryLifecycleTest.kt
git commit -m "Show stream grid when new stream connects"
```

Expected: Commit succeeds with only the intended implementation files.
