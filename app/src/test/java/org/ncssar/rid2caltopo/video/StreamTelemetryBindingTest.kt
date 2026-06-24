package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamTelemetryBindingTest {
    @Test
    fun manualPairingBindsStreamToRemoteIdWithoutChangingMappedId() {
        val telemetry = testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")
        val bindings = mutableMapOf<String, String>()

        bindStreamToRemoteId(bindings, "NCSSAR_MTRC4TD", telemetry.remoteId)

        assertEquals("1581F8", bindings["NCSSAR_MTRC4TD"])
        assertEquals("1SAR138DjMtrc4td", telemetry.mappedId)
    }

    @Test
    fun pairedStreamUsesCurrentMappedIdAsPrimaryLabel() {
        val bindings = mutableMapOf("NCSSAR_MTRC4TD" to "1581F8")

        val resolved = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")),
            runtimeStreamBindings = bindings
        )

        assertEquals(StreamTelemetryBindingStatus.PAIRED, resolved.status)
        assertEquals("1SAR138DjMtrc4td", resolved.primaryLabel)
        assertEquals("1581F8", resolved.telemetry?.remoteId)
    }

    @Test
    fun redAndYellowStreamsUseControllerDesignatorAsPrimaryLabel() {
        val red = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = emptyList(),
            runtimeStreamBindings = mutableMapOf()
        )
        val yellow = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")),
            runtimeStreamBindings = mutableMapOf()
        )

        assertEquals(StreamTelemetryBindingStatus.NO_TELEMETRY, red.status)
        assertEquals("NCSSAR_MTRC4TD", red.primaryLabel)
        assertNull(red.telemetry)
        assertEquals(StreamTelemetryBindingStatus.UNPAIRED_WITH_CANDIDATES, yellow.status)
        assertEquals("NCSSAR_MTRC4TD", yellow.primaryLabel)
        assertNull(yellow.telemetry)
    }

    @Test
    fun existingRuntimeBindingSurvivesMappedIdChangesAndLaterFlights() {
        val bindings = mutableMapOf("NCSSAR_MTRC4TD" to "1581F8")

        val firstFlight = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "1SAR83Mtrc4TD")),
            runtimeStreamBindings = bindings
        )
        val afterConfirmation = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")),
            runtimeStreamBindings = bindings
        )

        assertEquals(StreamTelemetryBindingStatus.PAIRED, firstFlight.status)
        assertEquals("1SAR83Mtrc4TD", firstFlight.primaryLabel)
        assertEquals(StreamTelemetryBindingStatus.PAIRED, afterConfirmation.status)
        assertEquals("1SAR138DjMtrc4td", afterConfirmation.primaryLabel)
        assertEquals("1581F8", bindings["NCSSAR_MTRC4TD"])
    }

    @Test
    fun configuredStreamBindingPairsByRidMapDefaultAndUsesCurrentMappedIdLabel() {
        val runtimeBindings = mutableMapOf<String, String>()
        val configuredBindings = mapOf("NCSSAR_MTRC4TD" to "1581F8")

        val resolved = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "NCSSAR_MTRC4TD")),
            runtimeStreamBindings = runtimeBindings,
            configuredStreamBindings = configuredBindings
        )
        val afterPilotCallsignChange = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")),
            runtimeStreamBindings = runtimeBindings,
            configuredStreamBindings = configuredBindings
        )

        assertEquals(StreamTelemetryBindingStatus.PAIRED, resolved.status)
        assertEquals("NCSSAR_MTRC4TD", resolved.primaryLabel)
        assertNull(runtimeBindings["NCSSAR_MTRC4TD"])
        assertEquals(StreamTelemetryBindingStatus.PAIRED, afterPilotCallsignChange.status)
        assertEquals("1SAR138DjMtrc4td", afterPilotCallsignChange.primaryLabel)
    }

    @Test
    fun configuredStreamBindingMatchesControllerDesignatorCaseInsensitively() {
        val runtimeBindings = mutableMapOf<String, String>()
        val configuredBindings = mapOf("NCSSAR_MTRC4TD" to "1581F8")

        val resolved = resolveStreamTelemetryBinding(
            streamDesignator = "ncssar_mtrc4td",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "NCSSAR_MTRC4TD")),
            runtimeStreamBindings = runtimeBindings,
            configuredStreamBindings = configuredBindings
        )

        assertEquals(StreamTelemetryBindingStatus.PAIRED, resolved.status)
        assertEquals("NCSSAR_MTRC4TD", resolved.primaryLabel)
        assertEquals("1581F8", resolved.telemetry?.remoteId)
        assertNull(runtimeBindings["ncssar_mtrc4td"])
    }

    @Test
    fun pairAnywayRuntimeOverrideDoesNotPersistToConfiguredDefault() {
        val runtimeBindings = mutableMapOf<String, String>()
        val configuredBindings = mapOf("NCSSAR_MTRC4TD" to "1581F8")

        bindStreamToRemoteId(runtimeBindings, "1SAR83Mtrc4Td1", "1581F8")
        val resolved = resolveStreamTelemetryBinding(
            streamDesignator = "1SAR83Mtrc4Td1",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")),
            runtimeStreamBindings = runtimeBindings,
            configuredStreamBindings = configuredBindings
        )

        assertEquals(StreamTelemetryBindingStatus.PAIRED, resolved.status)
        assertEquals("1SAR138DjMtrc4td", resolved.primaryLabel)
        assertEquals(mapOf("NCSSAR_MTRC4TD" to "1581F8"), configuredBindings)
    }

    @Test
    fun warningIsShownWhenRuntimePairingDiffersFromConfiguredDesignator() {
        val warning = streamTelemetryPairingWarning(
            streamDesignator = "1SAR83Mtrc4Td1",
            selectedTelemetry = testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td"),
            configuredStreamDesignatorByRemoteId = mapOf("1581F8" to "NCSSAR_MTRC4TD")
        )

        assertEquals("1SAR83Mtrc4Td1", warning?.streamDesignator)
        assertEquals("1581F8", warning?.remoteId)
        assertEquals("NCSSAR_MTRC4TD", warning?.configuredStreamDesignator)
    }

    @Test
    fun noWarningWhenRuntimePairingMatchesConfiguredDesignator() {
        val warning = streamTelemetryPairingWarning(
            streamDesignator = "NCSSAR_MTRC4TD",
            selectedTelemetry = testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td"),
            configuredStreamDesignatorByRemoteId = mapOf("1581F8" to "NCSSAR_MTRC4TD")
        )

        assertNull(warning)
    }

    @Test
    fun singleCandidatePairingControlShowsMismatchWarningDirectly() {
        val action = streamTelemetryPairingControlAction(
            streamDesignator = "1SAR83Mtrc4Td1",
            candidateTelemetry = listOf(testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")),
            configuredStreamDesignatorByRemoteId = mapOf("1581F8" to "NCSSAR_MTRC4TD")
        )

        assertEquals(StreamTelemetryPairingControlAction.ShowWarning, action.kind)
        assertEquals("1581F8", action.warning?.remoteId)
        assertEquals("NCSSAR_MTRC4TD", action.warning?.configuredStreamDesignator)
    }

    @Test
    fun multipleCandidatePairingControlStillShowsPicker() {
        val action = streamTelemetryPairingControlAction(
            streamDesignator = "1SAR83Mtrc4Td1",
            candidateTelemetry = listOf(
                testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td"),
                testTelemetry(remoteId = "1581F9", mappedId = "1SAR139DjMtrc4td")
            ),
            configuredStreamDesignatorByRemoteId = mapOf("1581F8" to "NCSSAR_MTRC4TD")
        )

        assertEquals(StreamTelemetryPairingControlAction.ShowPicker, action.kind)
        assertNull(action.warning)
    }

    @Test
    fun configuredBindingMapsAreBuiltOnceFromPersistedRidMapDefaults() {
        val maps = configuredStreamTelemetryBindingMaps(
            listOf(
                testTelemetry(remoteId = "1581F8", mappedId = "NCSSAR_MTRC4TD"),
                testTelemetry(remoteId = "1581F9", mappedId = ""),
                testTelemetry(remoteId = "", mappedId = "NCSSAR_MTRC5TD")
            )
        )

        assertEquals(mapOf("NCSSAR_MTRC4TD" to "1581F8"), maps.streamDesignatorToRemoteId)
        assertEquals(mapOf("1581F8" to "NCSSAR_MTRC4TD"), maps.remoteIdToStreamDesignator)
    }

    @Test
    fun clearingRuntimeBindingDoesNotClearTelemetryMappedId() {
        val telemetry = testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")
        val bindings = mutableMapOf("NCSSAR_MTRC4TD" to telemetry.remoteId)

        clearStreamTelemetryBinding(bindings, "NCSSAR_MTRC4TD")
        val resolved = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = listOf(telemetry),
            runtimeStreamBindings = bindings
        )

        assertNull(bindings["NCSSAR_MTRC4TD"])
        assertEquals("1SAR138DjMtrc4td", telemetry.mappedId)
        assertEquals(StreamTelemetryBindingStatus.UNPAIRED_WITH_CANDIDATES, resolved.status)
    }

    private fun testTelemetry(remoteId: String, mappedId: String): StreamTelemetryState {
        return StreamTelemetryState(remoteId = remoteId, mappedId = mappedId)
    }
}
