package org.ncssar.rid2caltopo.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class PeerTrafficMapPoint(
    val sourceZoneId: String,
    val remoteId: String,
    val mappedId: String,
    val source: String,
    val sequence: Long,
    val sampleTimestampMsec: Long,
    val receivedAtMsec: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMslMeters: Double?,
    val headingDeg: Double?,
    val speedKnots: Double?,
)

/** Map-only advisory traffic. It intentionally does not enter local flight or alert state. */
internal object PeerTrafficMapRegistry {
    private val _points = MutableStateFlow<Map<String, PeerTrafficMapPoint>>(emptyMap())
    val points = _points.asStateFlow()

    @JvmStatic
    fun update(
        sourceZoneId: String,
        remoteId: String,
        mappedId: String,
        source: String,
        sequence: Long,
        sampleTimestampMsec: Long,
        receivedAtMsec: Long,
        latitude: Double,
        longitude: Double,
        altitudeMslMeters: Double?,
        headingDeg: Double?,
        speedKnots: Double?,
    ) {
        if (sourceZoneId.isBlank() || remoteId.isBlank() || sampleTimestampMsec <= 0L) return
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
            (latitude == 0.0 && longitude == 0.0)
        ) return
        val key = "$sourceZoneId|$source|$remoteId"
        val incoming = PeerTrafficMapPoint(
            sourceZoneId = sourceZoneId,
            remoteId = remoteId,
            mappedId = mappedId.trim().ifBlank { remoteId },
            source = source,
            sequence = sequence,
            sampleTimestampMsec = sampleTimestampMsec,
            receivedAtMsec = receivedAtMsec,
            latitude = latitude,
            longitude = longitude,
            altitudeMslMeters = altitudeMslMeters?.takeIf { it.isFinite() },
            headingDeg = headingDeg?.takeIf { it.isFinite() },
            speedKnots = speedKnots?.takeIf { it.isFinite() && it >= 0.0 },
        )
        _points.update { currentPoints ->
            val current = currentPoints[key]
            if (current != null &&
                current.sampleTimestampMsec >= sampleTimestampMsec &&
                current.sequence >= sequence
            ) currentPoints else currentPoints + (key to incoming)
        }
    }

    @JvmStatic
    fun clear() { _points.value = emptyMap() }
}
