# Stream Remote ID Binding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bind RTMP stream designators to telemetry by Remote ID without mutating drone mapped IDs. Use persisted RID-map `mappedId` values as the default controller designators, and keep "Pair Anyway" mismatches runtime-only.

**Architecture:** `StreamsViewModel` resolves pairings from configured defaults (`rid_map mappedId -> remoteId`) plus an in-memory runtime override table (`streamDesignator -> remoteId`). Stream/video state stays keyed by controller stream designator; paired telemetry is resolved through Remote ID. StreamTile display uses the controller designator while red/yellow and the paired drone mapped ID while green.

**Tech Stack:** Android/Kotlin, Compose, existing JVM unit tests via Gradle.

---

### Task 1: Add Runtime Binding Seams

**Files:**
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt`
- Test: `app/src/test/java/org/ncssar/rid2caltopo/video/StreamsViewModelStreamBindingTest.kt`

- [x] **Step 1: Write failing tests**

Create tests for manual pairing by Remote ID, non-mutating mapped ID, red/yellow/green labels, binding survival across mapped ID changes, configured RID-map defaults, runtime-only Pair Anyway overrides, and mismatch warnings.

- [x] **Step 2: Run focused test to verify red**

Run: `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.StreamsViewModelStreamBindingTest`

Expected: compile/test failure because binding helpers do not exist.

- [x] **Step 3: Implement minimal ViewModel binding API**

Add runtime override map, configured RID-map default resolution, methods to bind/clear streams, resolve paired state by Remote ID, and resolve StreamTile primary labels.

- [x] **Step 4: Run focused test to verify green**

Run: `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.StreamTelemetryBindingTest`

Expected: pass.

### Task 2: Wire StreamTile UI To Binding

**Files:**
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/StreamTile.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/DesignatorIndicator.kt`
- Test: `app/src/test/java/org/ncssar/rid2caltopo/video/StreamsViewModelStreamBindingTest.kt`

- [x] **Step 1: Update manual picker actions**

Replace `droneSpecState.changeMappedId(streamDesignator)` with a ViewModel runtime bind call, gated by the mismatch warning when the configured stream designator differs.

- [x] **Step 2: Update unmatch/remap actions**

Clear only the runtime stream binding. Do not clear `CtDroneSpec.mappedId`.

- [x] **Step 3: Update primary label display**

Use a ViewModel label helper so red/yellow show controller stream designator and green shows the paired drone mapped ID.

- [x] **Step 4: Run focused test**

Run: `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.StreamTelemetryBindingTest`

Expected: pass.

### Task 3: Release Verification

**Files:**
- Verify all modified files.

- [x] **Step 1: Run release gate**

Run: `./gradlew :app:releaseCheck`

Expected: build successful.
