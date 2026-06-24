package org.ncssar.rid2caltopo.video

data class StreamTelemetryState(
    val remoteId: String,
    val mappedId: String
)

enum class StreamTelemetryBindingStatus {
    NO_TELEMETRY,
    UNPAIRED_WITH_CANDIDATES,
    PAIRED
}

data class StreamTelemetryBindingResolution(
    val status: StreamTelemetryBindingStatus,
    val primaryLabel: String,
    val telemetry: StreamTelemetryState?
)

data class StreamTelemetryPairingWarning(
    val streamDesignator: String,
    val remoteId: String,
    val configuredStreamDesignator: String,
    val droneLabel: String
)

data class StreamTelemetryPairingControlDecision(
    val kind: StreamTelemetryPairingControlAction,
    val warning: StreamTelemetryPairingWarning? = null
)

data class ConfiguredStreamTelemetryBindingMaps(
    val streamDesignatorToRemoteId: Map<String, String>,
    val remoteIdToStreamDesignator: Map<String, String>
)

enum class StreamTelemetryPairingControlAction {
    ShowPicker,
    ShowWarning
}

fun bindStreamToRemoteId(
    streamBindings: MutableMap<String, String>,
    streamDesignator: String,
    remoteId: String
) {
    if (streamDesignator.isBlank() || remoteId.isBlank()) return
    streamBindings[streamDesignator] = remoteId
}

fun clearStreamTelemetryBinding(
    streamBindings: MutableMap<String, String>,
    streamDesignator: String
) {
    streamBindings.remove(streamDesignator)
}

fun resolveStreamTelemetryBinding(
    streamDesignator: String,
    telemetryStates: Collection<StreamTelemetryState>,
    runtimeStreamBindings: Map<String, String>,
    configuredStreamBindings: Map<String, String> = emptyMap()
): StreamTelemetryBindingResolution {
    val boundRemoteId = runtimeStreamBindings[streamDesignator]
        ?: configuredStreamBindings.valueForDesignator(streamDesignator)
    val boundTelemetry = boundRemoteId?.let { remoteId ->
        telemetryStates.firstOrNull { it.remoteId == remoteId }
    }
    if (boundTelemetry != null) {
        return StreamTelemetryBindingResolution(
            status = StreamTelemetryBindingStatus.PAIRED,
            primaryLabel = boundTelemetry.mappedId.ifBlank { streamDesignator },
            telemetry = boundTelemetry
        )
    }

    return StreamTelemetryBindingResolution(
        status = if (telemetryStates.isEmpty()) {
            StreamTelemetryBindingStatus.NO_TELEMETRY
        } else {
            StreamTelemetryBindingStatus.UNPAIRED_WITH_CANDIDATES
        },
        primaryLabel = streamDesignator,
        telemetry = null
    )
}

private fun Map<String, String>.valueForDesignator(streamDesignator: String): String? {
    this[streamDesignator]?.let { return it }
    val trimmedDesignator = streamDesignator.trim()
    if (trimmedDesignator.isBlank()) return null
    return entries.firstOrNull { (configuredDesignator, _) ->
        configuredDesignator.trim().equals(trimmedDesignator, ignoreCase = true)
    }?.value
}

fun streamTelemetryPairingWarning(
    streamDesignator: String,
    selectedTelemetry: StreamTelemetryState,
    configuredStreamDesignatorByRemoteId: Map<String, String>
): StreamTelemetryPairingWarning? {
    val configuredDesignator = configuredStreamDesignatorByRemoteId[selectedTelemetry.remoteId]
        ?.trim()
        .orEmpty()
    if (configuredDesignator.isBlank()) return null
    if (configuredDesignator.equals(streamDesignator.trim(), ignoreCase = true)) return null
    return StreamTelemetryPairingWarning(
        streamDesignator = streamDesignator,
        remoteId = selectedTelemetry.remoteId,
        configuredStreamDesignator = configuredDesignator,
        droneLabel = configuredDesignator
    )
}

fun streamTelemetryPairingControlAction(
    streamDesignator: String,
    candidateTelemetry: Collection<StreamTelemetryState>,
    configuredStreamDesignatorByRemoteId: Map<String, String>
): StreamTelemetryPairingControlDecision {
    val singleCandidate = candidateTelemetry.singleOrNull()
    if (singleCandidate != null) {
        val warning = streamTelemetryPairingWarning(
            streamDesignator = streamDesignator,
            selectedTelemetry = singleCandidate,
            configuredStreamDesignatorByRemoteId = configuredStreamDesignatorByRemoteId
        )
        if (warning != null) {
            return StreamTelemetryPairingControlDecision(
                kind = StreamTelemetryPairingControlAction.ShowWarning,
                warning = warning
            )
        }
    }
    return StreamTelemetryPairingControlDecision(kind = StreamTelemetryPairingControlAction.ShowPicker)
}

fun configuredStreamTelemetryBindingMaps(
    configuredTelemetry: Collection<StreamTelemetryState>
): ConfiguredStreamTelemetryBindingMaps {
    val streamDesignatorToRemoteId = mutableMapOf<String, String>()
    val remoteIdToStreamDesignator = mutableMapOf<String, String>()
    configuredTelemetry.forEach { telemetry ->
        val configuredStreamDesignator = telemetry.mappedId.trim()
        val remoteId = telemetry.remoteId.trim()
        if (configuredStreamDesignator.isBlank() || remoteId.isBlank()) return@forEach
        streamDesignatorToRemoteId[configuredStreamDesignator] = remoteId
        remoteIdToStreamDesignator[remoteId] = configuredStreamDesignator
    }
    return ConfiguredStreamTelemetryBindingMaps(
        streamDesignatorToRemoteId = streamDesignatorToRemoteId,
        remoteIdToStreamDesignator = remoteIdToStreamDesignator
    )
}
