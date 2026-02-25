package org.ncssar.rid2caltopo.video.ffmpeg

data class FfmpegTelemetry(
    val sourceTag: String? = null,
    val confidence: Double? = null,
    val remoteId: String? = null,
    val sourceTimestampUs: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val gimbalPitchDeg: Double? = null,
    val cameraYawDeg: Double? = null,
    val headingDeg: Double? = null,
)
