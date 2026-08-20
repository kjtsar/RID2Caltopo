package org.ncssar.rid2caltopo.video.ffmpeg

import android.hardware.GeomagneticField
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

data class StreamCameraTelemetrySample(
    /** True-north camera azimuth converted from DJI's magnetic tag-4 angle. */
    val azimuthDeg: Double?,
    /** Course from recent SEI position motion; not camera azimuth. */
    val courseDeg: Double?,
    val tiltDeg: Double,
    val horizontalFovDeg: Double,
    val verticalFovDeg: Double,
    /** Reconstructed aircraft coordinate from the reference plus unwrapped N/E displacement. */
    val latitudeDeg: Double?,
    val longitudeDeg: Double?,
    /** Barometric altitude is supplied by the paired RID state, not DJI's unknown datum. */
    val altitudeMeters: Double?,
    val relativeUpMeters: Double?,
    val referenceLatitudeDeg: Double?,
    val referenceLongitudeDeg: Double?,
    val referenceAltitudeMeters: Double?,
    val northMeters: Double?,
    val eastMeters: Double?,
    val sourceTimestampUs: Long?,
    val receivedAtMs: Long,
    /** Raw tag-4 azimuth encoder retained for diagnostics and calibration evidence. */
    val rawCameraAzimuthDeg: Double,
    val rawTiltDeg: Double,
    val attitudeAnglesDeg: List<Double>,
)

object StreamCameraTelemetryRegistry {
    const val DEFAULT_MAX_AGE_MS = 3_000L
    private const val EARTH_RADIUS_METERS = 6_378_137.0
    private const val COURSE_BASELINE_METERS = 3.0
    private val lock = Any()
    private val samples = mutableMapOf<String, StreamCameraTelemetrySample>()
    private val unwrapStates = mutableMapOf<String, DisplacementState>()

    private data class DisplacementState(
        var lastSourceTimestampUs: Long?,
        var lastRawNorth: Int,
        var lastRawEast: Int,
        var lastRawDown: Int,
        var northMm: Long,
        var eastMm: Long,
        var downMm: Long,
        val initialDownMm: Long,
        var courseAnchorNorthMm: Long,
        var courseAnchorEastMm: Long,
        var courseDeg: Double? = null,
    )

    private fun signed16Delta(current: Int, previous: Int): Int {
        var delta = current - previous
        if (delta > 32_767) delta -= 65_536
        if (delta < -32_768) delta += 65_536
        return delta
    }

    private fun newState(telemetry: FfmpegTelemetry): DisplacementState? {
        val north = telemetry.djiRelativeNorthMmRaw ?: return null
        val east = telemetry.djiRelativeEastMmRaw ?: return null
        val down = telemetry.djiRelativeDownMmRaw ?: return null
        return DisplacementState(
            lastSourceTimestampUs = telemetry.sourceTimestampUs,
            lastRawNorth = north,
            lastRawEast = east,
            lastRawDown = down,
            northMm = north.toLong(),
            eastMm = east.toLong(),
            downMm = down.toLong(),
            initialDownMm = down.toLong(),
            courseAnchorNorthMm = north.toLong(),
            courseAnchorEastMm = east.toLong(),
        )
    }

    private fun updateDisplacement(state: DisplacementState, telemetry: FfmpegTelemetry) {
        val north = telemetry.djiRelativeNorthMmRaw ?: return
        val east = telemetry.djiRelativeEastMmRaw ?: return
        val down = telemetry.djiRelativeDownMmRaw ?: return
        state.northMm += signed16Delta(north, state.lastRawNorth)
        state.eastMm += signed16Delta(east, state.lastRawEast)
        state.downMm += signed16Delta(down, state.lastRawDown)
        state.lastRawNorth = north
        state.lastRawEast = east
        state.lastRawDown = down
        state.lastSourceTimestampUs = telemetry.sourceTimestampUs

        val deltaNorthMm = state.northMm - state.courseAnchorNorthMm
        val deltaEastMm = state.eastMm - state.courseAnchorEastMm
        if (hypot(deltaNorthMm.toDouble(), deltaEastMm.toDouble()) >= COURSE_BASELINE_METERS * 1_000.0) {
            state.courseDeg = ((Math.toDegrees(atan2(deltaEastMm.toDouble(), deltaNorthMm.toDouble())) % 360.0) + 360.0) % 360.0
            state.courseAnchorNorthMm = state.northMm
            state.courseAnchorEastMm = state.eastMm
        }
    }

    fun update(designator: String, telemetry: FfmpegTelemetry, nowMs: Long = System.currentTimeMillis()) {
        if (telemetry.sourceTag != "dji-sei-245") return
        val rawAzimuth = telemetry.cameraYawDeg?.takeIf { it.isFinite() } ?: return
        val rawTilt = telemetry.gimbalPitchDeg?.takeIf { it.isFinite() } ?: return
        val tilt = DjiCameraOrientation.calibratedTiltDeg(rawTilt) ?: return
        val width = telemetry.horizontalFovDeg?.takeIf { it.isFinite() && it > 0.0 } ?: return
        val height = telemetry.verticalFovDeg?.takeIf { it.isFinite() && it > 0.0 } ?: return
        val referenceLatitude = telemetry.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 }
        val referenceLongitude = telemetry.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 }
        val referenceAltitude = telemetry.altitudeMeters?.takeIf { it.isFinite() && it in -1000.0..30000.0 }
        val declination = if (referenceLatitude != null && referenceLongitude != null) {
            GeomagneticField(
                referenceLatitude.toFloat(),
                referenceLongitude.toFloat(),
                (referenceAltitude ?: 0.0).toFloat(),
                nowMs,
            ).declination.toDouble()
        } else null
        val absoluteAzimuth = DjiCameraOrientation.trueAzimuthDeg(rawAzimuth, declination) ?: return
        val key = designator.trim().uppercase()
        if (key.isEmpty()) return
        synchronized(lock) {
            val prior = unwrapStates[key]
            val sourceRestarted = prior?.lastSourceTimestampUs?.let { previous ->
                telemetry.sourceTimestampUs?.let { current -> current + 1_000_000L < previous } ?: false
            } ?: false
            val state = if (prior == null || sourceRestarted) {
                newState(telemetry)?.also { unwrapStates[key] = it }
            } else {
                updateDisplacement(prior, telemetry)
                prior
            }
            val northMeters = state?.northMm?.div(1_000.0)
            val eastMeters = state?.eastMm?.div(1_000.0)
            val aircraftLatitude = if (referenceLatitude != null && northMeters != null) {
                referenceLatitude + Math.toDegrees(northMeters / EARTH_RADIUS_METERS)
            } else null
            val aircraftLongitude = if (referenceLatitude != null && referenceLongitude != null && eastMeters != null) {
                referenceLongitude + Math.toDegrees(eastMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(referenceLatitude))))
            } else null
            samples[key] = StreamCameraTelemetrySample(
                azimuthDeg = absoluteAzimuth,
                courseDeg = state?.courseDeg,
                tiltDeg = tilt,
                horizontalFovDeg = width,
                verticalFovDeg = height,
                latitudeDeg = aircraftLatitude,
                longitudeDeg = aircraftLongitude,
                altitudeMeters = null,
                relativeUpMeters = state?.let { (it.initialDownMm - it.downMm) / 1_000.0 },
                referenceLatitudeDeg = referenceLatitude,
                referenceLongitudeDeg = referenceLongitude,
                referenceAltitudeMeters = referenceAltitude,
                northMeters = northMeters,
                eastMeters = eastMeters,
                sourceTimestampUs = telemetry.sourceTimestampUs,
                receivedAtMs = nowMs,
                rawCameraAzimuthDeg = rawAzimuth,
                rawTiltDeg = rawTilt,
                attitudeAnglesDeg = telemetry.djiAttitudeAnglesDeg.takeIf { it.size == 9 }
                    ?: List(9) { Double.NaN },
            )
        }
    }

    fun fresh(
        designator: String,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): StreamCameraTelemetrySample? = synchronized(lock) {
        samples[designator.trim().uppercase()]?.takeIf {
            nowMs >= it.receivedAtMs && nowMs - it.receivedAtMs <= maxAgeMs
        }
    }

    fun clear(designator: String) {
        synchronized(lock) {
            val key = designator.trim().uppercase()
            samples.remove(key)
            unwrapStates.remove(key)
        }
    }
}
