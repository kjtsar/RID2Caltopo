package org.ncssar.rid2caltopo.video

import org.ncssar.rid2caltopo.data.ManagedVideoStreamAdvertisement
import java.util.UUID
import android.util.Base64

object ManagedVideoStreamPresence {
    private val sessionIdBySourcePath = linkedMapOf<String, String>()
    private var currentLiveDesignators = emptySet<String>()

    @Synchronized
    fun snapshot(
        streams: Map<String, StreamInfo>,
        sourceInfoProvider: (String) -> org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridge.VideoSourceInfo? = { null },
        hasRecentFrame: (String) -> Boolean = { true },
        recordings: List<ManagedVideoSessionRecording> = emptyList(),
        thumbnailProvider: (String) -> ManagedVideoThumbnail? = { null },
    ): List<ManagedVideoStreamAdvertisement> {
        // Stream inventory is intentionally independent of telemetry binding. A camera
        // commonly starts publishing before its drone emits Remote ID; the tablet-level
        // R2C link must expose it immediately, while track metadata is added only after
        // a matching telemetry identity becomes available.
        val live = streams.values
            .filter {
                it.state == StreamState.LIVE &&
                    !it.isLocalPlayback
            }
            .sortedBy { it.designator.lowercase() }
            .take(4)
        val livePaths = live.mapTo(linkedSetOf()) { it.sourcePath }
        currentLiveDesignators = live
            .map { it.designator.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        sessionIdBySourcePath.keys.retainAll(livePaths)
        val liveAdvertisements = live.map { stream ->
            // Inventory follows publisher state, not decoder/UI readiness. Only
            // attach source metadata once a recent frame proves it is current.
            val source = sourceInfoProvider(stream.designator)
                .takeIf { hasRecentFrame(stream.designator) }
            val sessionId = sessionIdBySourcePath.getOrPut(stream.sourcePath) {
                UUID.randomUUID().toString()
            }
            val thumbnail = thumbnailProvider(sessionId)
            ManagedVideoStreamAdvertisement(
                sessionId,
                stream.designator,
                source?.width ?: 0,
                source?.height ?: 0,
                nominalManagedVideoSourceFps(source?.fps ?: 0.0),
                source?.bitrateBps ?: 0,
                source?.codec ?: "",
                "live",
                null,
                0L,
                thumbnail?.revision.orEmpty(),
                thumbnail?.jpegBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            )
        }
        return liveAdvertisements + recordings.map { recording ->
            ManagedVideoSessionRecordingCatalog.advertisement(
                recording,
                thumbnailProvider(recording.sessionId),
            )
        }
    }

    @Synchronized
    internal fun resetForTests() {
        sessionIdBySourcePath.clear()
        currentLiveDesignators = emptySet()
    }

    @JvmStatic
    @Synchronized
    fun hasLiveDesignator(vararg candidates: String): Boolean = candidates.any {
        it.trim().lowercase() in currentLiveDesignators
    }

    @JvmStatic
    @Synchronized
    fun matchingLiveDesignator(vararg candidates: String): String? = candidates
        .map { it.trim() }
        .firstOrNull {
            it.isNotEmpty() && it.lowercase() in currentLiveDesignators
        }
}

/**
 * Some RTMP controllers declare a 240 fps codec time base even though decoded
 * frames arrive at normal video cadence. Managed streaming supports at most
 * 30 fps, and a decoded cadence of 15 fps or better represents the nominal
 * 30 fps controller mode used by the quality ladder.
 */
internal fun nominalManagedVideoSourceFps(reportedFps: Double): Double = when {
    !reportedFps.isFinite() || reportedFps <= 0.0 -> 0.0
    reportedFps >= 15.0 -> 30.0
    else -> reportedFps
}
