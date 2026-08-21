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
    /** Aircraft coordinate reconstructed from the reference plus full-width N/E displacement. */
    val latitudeDeg: Double?,
    val longitudeDeg: Double?,
    /** MSL altitude is supplied by the paired RID state. */
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
    private const val MAX_RID_ANCHOR_RESIDUAL_METERS = 30.0
    private const val MAX_RID_VERTICAL_RESIDUAL_METERS = 20.0
    private const val COURSE_BASELINE_METERS = 3.0
    private val lock = Any()
    private val samples = mutableMapOf<String, StreamCameraTelemetrySample>()
    private val courseStates = mutableMapOf<String, CourseState>()

    private data class CourseState(
        var anchorNorthMm: Int,
        var anchorEastMm: Int,
        var lastSourceTimestampUs: Long?,
        var courseDeg: Double? = null,
    )

    private fun updateCourse(state: CourseState, northMm: Int, eastMm: Int) {
        val deltaNorthMm = northMm.toLong() - state.anchorNorthMm
        val deltaEastMm = eastMm.toLong() - state.anchorEastMm
        if (hypot(deltaNorthMm.toDouble(), deltaEastMm.toDouble()) >= COURSE_BASELINE_METERS * 1_000.0) {
            state.courseDeg = ((Math.toDegrees(atan2(deltaEastMm.toDouble(), deltaNorthMm.toDouble())) % 360.0) + 360.0) % 360.0
            state.anchorNorthMm = northMm
            state.anchorEastMm = eastMm
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
        val northMm = telemetry.djiNorthMm
        val eastMm = telemetry.djiEastMm
        val downMm = telemetry.djiDownMm
        val key = designator.trim().uppercase()
        if (key.isEmpty()) return
        synchronized(lock) {
            val courseState = if (northMm != null && eastMm != null) {
                val prior = courseStates[key]
                val sourceRestarted = prior?.lastSourceTimestampUs?.let { previous ->
                    telemetry.sourceTimestampUs?.let { current -> current + 1_000_000L < previous } ?: false
                } ?: false
                if (prior == null || sourceRestarted) {
                    CourseState(northMm, eastMm, telemetry.sourceTimestampUs).also {
                        courseStates[key] = it
                    }
                } else {
                    updateCourse(prior, northMm, eastMm)
                    prior.lastSourceTimestampUs = telemetry.sourceTimestampUs
                    prior
                }
            } else {
                null
            }
            val northMeters = northMm?.div(1_000.0)
            val eastMeters = eastMm?.div(1_000.0)
            val aircraftLatitude = if (referenceLatitude != null && northMeters != null) {
                referenceLatitude + Math.toDegrees(northMeters / EARTH_RADIUS_METERS)
            } else null
            val aircraftLongitude = if (referenceLatitude != null && referenceLongitude != null && eastMeters != null) {
                referenceLongitude + Math.toDegrees(eastMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(referenceLatitude))))
            } else null
            samples[key] = StreamCameraTelemetrySample(
                azimuthDeg = absoluteAzimuth,
                courseDeg = courseState?.courseDeg,
                tiltDeg = tilt,
                horizontalFovDeg = width,
                verticalFovDeg = height,
                latitudeDeg = aircraftLatitude,
                longitudeDeg = aircraftLongitude,
                altitudeMeters = null,
                relativeUpMeters = if (downMm != null && referenceAltitude != null) {
                    -downMm.toDouble() / 1_000.0 - referenceAltitude
                } else null,
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

    /**
     * Validates the independently decoded full-width DJI position against a current RID fix.
     * Position is withheld when the two sources disagree beyond the operational gate, while
     * camera orientation remains usable and RID remains the caller's position fallback.
     */
    fun freshAnchored(
        designator: String,
        anchorLatitudeDeg: Double,
        anchorLongitudeDeg: Double,
        anchorAltitudeMeters: Double? = null,
        takeoffMslMeters: Double? = null,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): StreamCameraTelemetrySample? {
        val sample = fresh(designator, nowMs, maxAgeMs) ?: return null
        val anchoredHorizontal = if (
            anchorLatitudeDeg.isFinite() && anchorLatitudeDeg in -90.0..90.0 &&
            anchorLongitudeDeg.isFinite() && anchorLongitudeDeg in -180.0..180.0 &&
            sample.latitudeDeg != null && sample.longitudeDeg != null
        ) {
            val latitudeRadians = Math.toRadians(sample.latitudeDeg)
            val residualNorth = Math.toRadians(anchorLatitudeDeg - sample.latitudeDeg) * EARTH_RADIUS_METERS
            val residualEast = Math.toRadians(anchorLongitudeDeg - sample.longitudeDeg) *
                EARTH_RADIUS_METERS * cos(latitudeRadians)
            hypot(residualNorth, residualEast).takeIf { it <= MAX_RID_ANCHOR_RESIDUAL_METERS }
        } else null
        val validatedRelativeUp = if (
            sample.relativeUpMeters != null && anchorAltitudeMeters?.isFinite() == true &&
            takeoffMslMeters?.isFinite() == true
        ) {
            val targetUp = anchorAltitudeMeters - takeoffMslMeters
            sample.relativeUpMeters.takeIf {
                kotlin.math.abs(it - targetUp) <= MAX_RID_VERTICAL_RESIDUAL_METERS
            }
        } else sample.relativeUpMeters
        return sample.copy(
            latitudeDeg = sample.latitudeDeg.takeIf { anchoredHorizontal != null },
            longitudeDeg = sample.longitudeDeg.takeIf { anchoredHorizontal != null },
            northMeters = sample.northMeters.takeIf { anchoredHorizontal != null },
            eastMeters = sample.eastMeters.takeIf { anchoredHorizontal != null },
            relativeUpMeters = validatedRelativeUp,
        )
    }

    fun clear(designator: String) {
        synchronized(lock) {
            val key = designator.trim().uppercase()
            samples.remove(key)
            courseStates.remove(key)
        }
    }
}
