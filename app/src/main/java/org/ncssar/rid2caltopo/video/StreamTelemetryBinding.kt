package org.ncssar.rid2caltopo.video

import org.ncssar.rid2caltopo.video.ffmpeg.StreamCameraTelemetryRegistry
import java.util.Locale

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

data class PairedVideoFlightActivity(
    val publisherActive: Boolean,
    val lastActivityAtMs: Long,
)

data class PairedStreamTelemetryActivity(
    val paired: Boolean,
    val lastSeiActivityAtMs: Long,
)

/**
 * Session-scoped stream-to-aircraft bindings plus authoritative MediaMTX publisher presence.
 * A publisher connection may rotate during an RTMP/RTP restart; the stable stream designator
 * keeps the pairing attached to the same Remote ID across those connection changes.
 */
object StreamFlightActivityRegistry {
    private val lock = Any()
    private val runtimeBindings = mutableMapOf<String, String>()
    private var configuredBindings: Map<String, String> = emptyMap()
    private var livePublishers: Set<String> = emptySet()
    private val lastPublisherActivityAtMs = mutableMapOf<String, Long>()

    @JvmStatic
    fun bindRuntime(streamDesignator: String, remoteId: String) {
        val designator = normalizeDesignator(streamDesignator)
        val remote = remoteId.trim()
        if (designator.isEmpty() || remote.isEmpty()) return
        synchronized(lock) { runtimeBindings[designator] = remote }
    }

    @JvmStatic
    fun clearRuntime(streamDesignator: String) {
        val designator = normalizeDesignator(streamDesignator)
        if (designator.isEmpty()) return
        synchronized(lock) { runtimeBindings.remove(designator) }
    }

    @JvmStatic
    fun replaceConfigured(bindings: Map<String, String>) {
        val normalized = bindings.mapNotNull { (designator, remoteId) ->
            val key = normalizeDesignator(designator)
            val remote = remoteId.trim()
            if (key.isEmpty() || remote.isEmpty()) null else key to remote
        }.toMap()
        synchronized(lock) { configuredBindings = normalized }
    }

    @JvmStatic
    fun replaceLivePublishers(designators: Collection<String>, observedAtMs: Long) {
        val normalized = designators.map(::normalizeDesignator).filter(String::isNotEmpty).toSet()
        synchronized(lock) {
            (livePublishers - normalized).forEach { lastPublisherActivityAtMs[it] = observedAtMs }
            normalized.forEach { lastPublisherActivityAtMs[it] = observedAtMs }
            livePublishers = normalized
        }
    }

    @JvmStatic
    fun activityForRemoteId(remoteId: String, nowMs: Long): PairedVideoFlightActivity {
        val remote = remoteId.trim()
        if (remote.isEmpty()) return PairedVideoFlightActivity(false, 0L)
        synchronized(lock) {
            val designators = boundDesignatorsForRemoteIdLocked(remote)
            val active = designators.any { it in livePublishers }
            val lastActivity = if (active) nowMs else designators.maxOfOrNull {
                lastPublisherActivityAtMs[it] ?: 0L
            } ?: 0L
            return PairedVideoFlightActivity(active, lastActivity)
        }
    }

    @JvmStatic
    fun seiActivityForRemoteId(remoteId: String): PairedStreamTelemetryActivity {
        val remote = remoteId.trim()
        if (remote.isEmpty()) return PairedStreamTelemetryActivity(false, 0L)
        synchronized(lock) {
            val designators = boundDesignatorsForRemoteIdLocked(remote)
            return PairedStreamTelemetryActivity(
                paired = designators.isNotEmpty(),
                lastSeiActivityAtMs = designators.maxOfOrNull {
                    StreamCameraTelemetryRegistry.lastReceivedAtMs(it)
                } ?: 0L,
            )
        }
    }

    internal fun resetForTests() {
        synchronized(lock) {
            runtimeBindings.clear()
            configuredBindings = emptyMap()
            livePublishers = emptySet()
            lastPublisherActivityAtMs.clear()
        }
    }

    private fun normalizeDesignator(value: String): String =
        value.trim().uppercase(Locale.US)

    private fun boundDesignatorsForRemoteIdLocked(remoteId: String): List<String> =
        (configuredBindings.keys + runtimeBindings.keys).filter { designator ->
            (runtimeBindings[designator] ?: configuredBindings[designator]) == remoteId
        }
}

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
