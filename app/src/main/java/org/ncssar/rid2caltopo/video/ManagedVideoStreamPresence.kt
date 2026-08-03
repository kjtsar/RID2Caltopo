package org.ncssar.rid2caltopo.video

import org.ncssar.rid2caltopo.data.ManagedVideoStreamAdvertisement
import java.util.UUID

object ManagedVideoStreamPresence {
    private val sessionIdBySourcePath = linkedMapOf<String, String>()

    @Synchronized
    fun snapshot(
        streams: Map<String, StreamInfo>,
        sourceInfoProvider: (String) -> org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridge.VideoSourceInfo? = { null },
        hasRecentFrame: (String) -> Boolean = { true },
    ): List<ManagedVideoStreamAdvertisement> {
        val live = streams.values
            .filter {
                it.state == StreamState.LIVE &&
                    !it.isLocalPlayback &&
                    hasRecentFrame(it.designator)
            }
            .sortedBy { it.designator.lowercase() }
            .take(4)
        val livePaths = live.mapTo(linkedSetOf()) { it.sourcePath }
        sessionIdBySourcePath.keys.retainAll(livePaths)
        return live.map { stream ->
            val source = sourceInfoProvider(stream.designator)
            ManagedVideoStreamAdvertisement(
                sessionIdBySourcePath.getOrPut(stream.sourcePath) {
                    UUID.randomUUID().toString()
                },
                stream.designator,
                source?.width ?: 0,
                source?.height ?: 0,
                nominalManagedVideoSourceFps(source?.fps ?: 0.0),
                source?.bitrateBps ?: 0,
                source?.codec ?: "",
            )
        }
    }

    @Synchronized
    internal fun resetForTests() {
        sessionIdBySourcePath.clear()
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
