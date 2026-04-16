package org.ncssar.rid2caltopo.video

/**
 * Pure routing logic that decides whether a given stream should be decoded by FFmpeg
 * (software, supports anomaly detection) or ExoPlayer (hardware MediaCodec).
 *
 * Rules:
 *  - When no stream tiles are displayed, FFmpeg is used for nothing.
 *  - When exactly one stream tile is displayed, FFmpeg is used for that single visible stream.
 *  - When multiple stream tiles are displayed, ExoPlayer is used for all of them to offload
 *    decode to the hardware VPU.
 *  - If FFmpeg is unavailable, ExoPlayer is used for everything.
 */
object StreamRenderRouter {
    fun useFfmpeg(
        designator: String,
        liveStreams: Map<String, StreamInfo>,
        focusedDesignator: String?,
        ffmpegAvailable: Boolean,
        displayedTileCount: Int,
    ): Boolean {
        if (!ffmpegAvailable) return false
        val info = liveStreams[designator] ?: return false
        if (info.state != StreamState.LIVE) return false
        if (displayedTileCount != 1) return false
        return if (focusedDesignator != null) {
            focusedDesignator == designator
        } else {
            liveStreams.values.count { it.state == StreamState.LIVE } == 1
        }
    }
}
