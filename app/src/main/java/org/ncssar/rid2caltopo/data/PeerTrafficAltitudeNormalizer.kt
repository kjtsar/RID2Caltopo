package org.ncssar.rid2caltopo.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ncssar.rid2caltopo.app.R2CApplication
import org.ncssar.rid2caltopo.video.mapcache.DemElevationService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Confirmation-anchored, shadow-only per-flight altitude conversion to MSL. */
internal object PeerTrafficAltitudeNormalizer {
    private const val MAX_CONFIRMATION_SAMPLE_AGE_MS = 10_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calibrations = ConcurrentHashMap<String, Calibration>()

    data class Metadata(
        val flightEpoch: String?,
        val state: String,
        val mslAltitudeMeters: Double?,
        val mslAltitudeSampleTimestampMsec: Long?,
        val correctionMeters: Double?,
        val calibrationTimestampMsec: Long?,
        val demSource: String?,
        val demResolutionMeters: Double?,
    )

    private data class Calibration(
        val flightStartMsec: Long,
        val flightEpoch: String,
        val state: String,
        val reportedGroundAltitudeMeters: Double? = null,
        val correctionMeters: Double? = null,
        val calibrationTimestampMsec: Long? = null,
        val demSource: String? = null,
        val demResolutionMeters: Double? = null,
    )

    @JvmStatic
    fun lockAtConfirmation(spec: CtDroneSpec, nowMsec: Long = System.currentTimeMillis()) {
        val remoteId = spec.remoteId.trim()
        if (remoteId.isEmpty()) return
        val flightStartMsec = spec.startMsecTimestamp
        val existing = calibrations[remoteId]
        if (existing != null && existing.flightStartMsec == flightStartMsec &&
            (existing.state == "pending" || existing.state == "locked")
        ) return

        val flightEpoch = UUID.randomUUID().toString()
        val sampleTimestampMsec = spec.mostRecentMsecTimestamp
        val latitude = if (spec.hasTakeoffLocation()) spec.takeoffLat else spec.lastLat
        val longitude = if (spec.hasTakeoffLocation()) spec.takeoffLng else spec.lastLng
        val reportedGroundAltitudeMeters = spec.impliedTakeoffAltM
            ?.takeIf { it.isFinite() && it > -999.0 }
            ?: spec.lastAlt.takeIf { it.isFinite() && it > -999.0 }
        val usableSnapshot = flightStartMsec > 0L &&
            sampleTimestampMsec > 0L &&
            nowMsec - sampleTimestampMsec in 0L..MAX_CONFIRMATION_SAMPLE_AGE_MS &&
            latitude.isFinite() && longitude.isFinite() &&
            !(latitude == 0.0 && longitude == 0.0) &&
            reportedGroundAltitudeMeters != null

        if (!usableSnapshot) {
            calibrations[remoteId] = Calibration(flightStartMsec, flightEpoch, "unavailable")
            return
        }

        calibrations[remoteId] = Calibration(
            flightStartMsec,
            flightEpoch,
            "pending",
            reportedGroundAltitudeMeters = reportedGroundAltitudeMeters,
        )
        val context = R2CApplication.getAppCtxt()
        if (context == null) {
            calibrations.computeIfPresent(remoteId) { _, current ->
                if (current.flightEpoch == flightEpoch) current.copy(state = "unavailable") else current
            }
            return
        }
        scope.launch {
            val terrain = try {
                DemElevationService(context).sampleElevationMeters(latitude, longitude)
            } catch (_: Exception) {
                null
            }
            calibrations.computeIfPresent(remoteId) { _, current ->
                if (current.flightEpoch != flightEpoch) return@computeIfPresent current
                if (terrain == null || terrain.stale) {
                    current.copy(state = "unavailable")
                } else {
                    current.copy(
                        state = "locked",
                        correctionMeters = correctionMeters(
                            terrain.elevationMeters,
                            reportedGroundAltitudeMeters,
                        ),
                        calibrationTimestampMsec = nowMsec,
                        demSource = terrain.source,
                        demResolutionMeters = terrain.horizontalResolutionMeters,
                    )
                }
            }
        }
    }

    @JvmStatic
    fun metadata(remoteId: String, rawAltitudeMeters: Double, altitudeTimestampMsec: Long): Metadata {
        val calibration = calibrations[remoteId]
            ?: return Metadata(null, "unconfirmed", null, null, null, null, null, null)
        val correction = calibration.correctionMeters
        val normalized = if (
            calibration.state == "locked" && correction != null && rawAltitudeMeters.isFinite()
        ) normalizedMslMeters(rawAltitudeMeters, correction) else null
        return Metadata(
            calibration.flightEpoch,
            calibration.state,
            normalized,
            normalized?.let { altitudeTimestampMsec },
            correction,
            calibration.calibrationTimestampMsec,
            calibration.demSource,
            calibration.demResolutionMeters,
        )
    }

    @JvmStatic
    fun clear(remoteId: String) {
        calibrations.remove(remoteId)
    }

    /** Converts DJI SEI's validated takeoff-relative displacement into the raw flight datum. */
    @JvmStatic
    fun reportedAltitudeForRelativeUp(remoteId: String, relativeUpMeters: Double?): Double? {
        val calibration = calibrations[remoteId] ?: return null
        return reportedAltitudeForRelativeUp(
            calibration.reportedGroundAltitudeMeters,
            relativeUpMeters,
        )
    }

    internal fun resetForTesting() = calibrations.clear()

    internal fun correctionMeters(demMslMeters: Double, reportedGroundAltitudeMeters: Double): Double =
        demMslMeters - reportedGroundAltitudeMeters

    internal fun normalizedMslMeters(rawAltitudeMeters: Double, correctionMeters: Double): Double =
        rawAltitudeMeters + correctionMeters

    internal fun reportedAltitudeForRelativeUp(
        reportedGroundAltitudeMeters: Double?,
        relativeUpMeters: Double?,
    ): Double? = if (
        reportedGroundAltitudeMeters != null && reportedGroundAltitudeMeters.isFinite() &&
        relativeUpMeters != null && relativeUpMeters.isFinite()
    ) reportedGroundAltitudeMeters + relativeUpMeters else null
}
