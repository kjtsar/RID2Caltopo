/** Ground elevation sample from the DEM service, with staleness flag. */
internal data class DroneAglState(
    val groundM: Double,
    val stale: Boolean
)

/**
 * Tracks whether the ATO calibration was seeded automatically from RID data,
 * has converged (sealed), or was explicitly set by the user.
 */
internal enum class AtoSeedSource {
    AUTO,        // RID-height EMA is still converging; calibration will keep updating
    AUTO_SEALED, // EMA has converged; calibration is locked until user manually overrides
    MANUAL,      // User pressed "Calibrate ATO at 50'"; never auto-update
}

/** Per-drone ATO takeoff-altitude calibration. Keyed by remoteId in the coordinator. */
internal data class DroneAltitudeCalibration(
    val takeoffTrackAltitudeM: Double,
    val seedSource: AtoSeedSource = AtoSeedSource.AUTO
)
