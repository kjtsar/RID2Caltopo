package org.ncssar.rid2caltopo.video

/**
 * Pure routing logic that decides whether a given stream should be decoded by FFmpeg
 * (software, supports anomaly detection) or ExoPlayer (hardware MediaCodec).
 *
 * Rules:
 *  - FFmpeg is used for the focused stream, so the anomaly detector has access to raw pixels.
 *  - When there is exactly one live stream and nothing is explicitly focused, FFmpeg is used
 *    (single-stream case behaves as if that stream is focused).
 *  - All other concurrent streams use ExoPlayer to offload decode to the hardware VPU.
 *  - If FFmpeg is unavailable, ExoPlayer is used for everything.
 */
object StreamRenderRouter {
    fun useFfmpeg(
        designator: String,
        liveStreams: Map<String, StreamInfo>,
        focusedDesignator: String?,
        ffmpegAvailable: Boolean,
    ): Boolean {
        if (!ffmpegAvailable) return false
        val info = liveStreams[designator] ?: return false
        if (info.state != StreamState.LIVE) return false
        if (focusedDesignator == designator) return true
        val liveCount = liveStreams.values.count { it.state == StreamState.LIVE }
        return focusedDesignator == null && liveCount == 1
    }
}
