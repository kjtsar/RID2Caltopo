package org.ncssar.rid2caltopo.data

import java.time.Instant

sealed class MediaMTXEvent {
    data class StreamConnecting(
        val path: String,
        val timestamp: Instant = Instant.now()
    ) : MediaMTXEvent()
    data class StreamStarted(
        val path: String,
        val publisherConnId: String? = null,
        val timestamp: Instant = Instant.now()
    ) : MediaMTXEvent()
    data class StreamPublisherHandoff(
        val path: String,
        val publisherConnId: String? = null,
        val timestamp: Instant = Instant.now()
    ) : MediaMTXEvent()
    data class StreamStopped(
        val path: String,
        val publisherConnId: String? = null,
        val timestamp: Instant = Instant.now()
    ) : MediaMTXEvent()
    data class StreamError(
        val path: String,
        val publisherConnId: String? = null,
        val reason: String? = null,
        val timestamp: Instant = Instant.now()
    ) : MediaMTXEvent()
    data class RtmpSessionClosed(
        val path: String,
        val publisherConnId: String? = null,
        val reason: String? = null,
        val timestamp: Instant = Instant.now()
    ) : MediaMTXEvent()
    data class RtmpPublishDiagnostic(
        val path: String,
        val publisherConnId: String? = null,
        val phase: String,
        val elapsedMs: Long? = null,
        val detail: String? = null,
        val timestamp: Instant = Instant.now()
    ) : MediaMTXEvent()
    data class ServerStarted(
        val version: String,
        val timestamp: Instant = Instant.now()
    ) : MediaMTXEvent()
    data class HlsStreamStarted(
        val path: String,
        val timestamp: Instant = Instant.now()
    ) : MediaMTXEvent()
    data class RecordFileCompleted(
        val path: String,
        val filePath: String,
        val durationMs: Long,
        val timestamp: Instant = Instant.now()
    ) : MediaMTXEvent()
}
