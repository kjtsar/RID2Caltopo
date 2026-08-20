package org.ncssar.rid2caltopo.video.ffmpeg

data class FfmpegTelemetry(
    val sourceTag: String? = null,
    val confidence: Double? = null,
    val remoteId: String? = null,
    val sourceTimestampUs: Long? = null,
    val renderLatencyMs: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val gimbalPitchDeg: Double? = null,
    val cameraYawDeg: Double? = null,
    val headingDeg: Double? = null,
    val horizontalFovDeg: Double? = null,
    val verticalFovDeg: Double? = null,
    /** Diagnostic-only tag-4 binary angles, captured from the same DJI SEI frame. */
    val djiAttitudeAnglesDeg: List<Double> = emptyList(),
    val djiRelativeNorthMmRaw: Int? = null,
    val djiRelativeEastMmRaw: Int? = null,
    val djiRelativeDownMmRaw: Int? = null,
)
