# LAANC Airspace Awareness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add read-only LAANC/controlled-airspace awareness and align the operating-area radius with the 1 statute mile BVLOS waiver.

**Architecture:** Introduce a small `airspace` package with shared operating-radius constants, FAA UAS Facility Map models/parser, policy, repository, and center. Existing NOTAM and map overlay code consume the shared radius while the new airspace state can be surfaced in the same operator-facing chip/panel area.

**Tech Stack:** Kotlin/JVM unit tests, Android `Location`, OkHttp, `org.json`, Kotlin coroutines/StateFlow, existing Compose/osmdroid UI patterns.

---

### Task 1: Shared Operating Radius

**Files:**
- Create: `app/src/main/java/org/ncssar/rid2caltopo/airspace/OperatingArea.kt`
- Create: `app/src/test/java/org/ncssar/rid2caltopo/airspace/OperatingAreaTest.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/notam/NotamMapOverlayAdapter.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/notam/NotamRepository.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/notam/NotamHumanizer.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/notam/NotamPanel.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt`

- [ ] Add failing tests for 1 statute mile conversion and label.
- [ ] Implement `OperatingArea` constants and helpers.
- [ ] Replace hard-coded `1 NM operating area` logic and labels with `OperatingArea.radiusNm` and `OperatingArea.displayLabel`.
- [ ] Run `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.airspace.OperatingAreaTest --tests org.ncssar.rid2caltopo.notam.NotamPolicyTest`.

### Task 2: FAA UAS Facility Map Parser And Policy

**Files:**
- Create: `app/src/main/java/org/ncssar/rid2caltopo/airspace/AirspaceModels.kt`
- Create: `app/src/main/java/org/ncssar/rid2caltopo/airspace/FaaUasFacilityMapParser.kt`
- Create: `app/src/main/java/org/ncssar/rid2caltopo/airspace/AirspacePolicy.kt`
- Create: `app/src/test/java/org/ncssar/rid2caltopo/airspace/FaaUasFacilityMapParserTest.kt`
- Create: `app/src/test/java/org/ncssar/rid2caltopo/airspace/AirspacePolicyTest.kt`

- [ ] Add failing parser test with the Fallon fixture: `CEILING=400`, `APT1_NAME=Fallon NAS (Van Voorhis Fld)`, `APT1_LAANC=1`, `AIRSPACE_1=D`.
- [ ] Add failing policy test requiring caution label `Airspace: LAANC required - Fallon NAS Class D up to 400 ft`.
- [ ] Implement models, parser, and policy.
- [ ] Run focused airspace unit tests.

### Task 3: Live Airspace Repository And Center

**Files:**
- Create: `app/src/main/java/org/ncssar/rid2caltopo/airspace/AirspaceRepository.kt`
- Create: `app/src/main/java/org/ncssar/rid2caltopo/airspace/AirspaceCenter.kt`
- Create: `app/src/test/java/org/ncssar/rid2caltopo/airspace/AirspaceRepositoryTest.kt`

- [ ] Add failing test that repository builds a URL against FAA `FeatureServer/0/query` with point geometry, 1 statute mile distance buffer, and requested fields.
- [ ] Implement OkHttp repository with timeout handling and stale cached state retention.
- [ ] Implement `AirspaceCenter` refresh loop matching `NotamCenter` style.
- [ ] Run focused repository tests.

### Task 4: Operator UI Integration

**Files:**
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/app/R2CApplication.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/ui/MainScreen.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsScreen.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/notam/NotamStatusChip.kt`
- Modify: `app/src/main/java/org/ncssar/rid2caltopo/notam/NotamPanel.kt`

- [ ] Add failing UI/policy test for airspace caution priority over NOTAM clear where feasible.
- [ ] Initialize `AirspaceCenter` with app startup/shutdown.
- [ ] Feed airspace state into existing chip/panel entry points and show LAANC-required caution before green NOTAM-clear.
- [ ] Keep NOTAM details available in the panel while adding controlled-airspace summary.

### Task 5: Verification

**Files:**
- Update docs/release notes only if the project has a current release-note source in scope.

- [ ] Run focused tests for `org.ncssar.rid2caltopo.airspace.*` and `org.ncssar.rid2caltopo.notam.*`.
- [ ] Run `./gradlew :app:releaseCheck`.
- [ ] Manually verify a live FAA query for `39.47816,-118.78456` returns Fallon Class D up to 400 ft, or record why live verification was unavailable.
