import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DroneAltitudeCoordinatorTest {
    @Test
    fun shouldPreserveCalibrationOnMapReconnect_keepsLockedReferences() {
        assertTrue(
            DroneAltitudeCoordinator.shouldPreserveCalibrationOnMapReconnect(
                DroneAltitudeCalibration(512.4, AtoSeedSource.AUTO_SEALED)
            )
        )
        assertTrue(
            DroneAltitudeCoordinator.shouldPreserveCalibrationOnMapReconnect(
                DroneAltitudeCalibration(512.4, AtoSeedSource.MANUAL)
            )
        )
    }

    @Test
    fun shouldPreserveCalibrationOnMapReconnect_dropsUnsealedAutoReference() {
        assertFalse(
            DroneAltitudeCoordinator.shouldPreserveCalibrationOnMapReconnect(
                DroneAltitudeCalibration(512.4, AtoSeedSource.AUTO)
            )
        )
        assertFalse(DroneAltitudeCoordinator.shouldPreserveCalibrationOnMapReconnect(null))
    }

    @Test
    fun shouldResetAutoCalibrationForFlightChange_resetsAutoReferences() {
        assertTrue(
            DroneAltitudeCoordinator.shouldResetAutoCalibrationForFlightChange(
                DroneAltitudeCalibration(512.4, AtoSeedSource.AUTO)
            )
        )
        assertTrue(
            DroneAltitudeCoordinator.shouldResetAutoCalibrationForFlightChange(
                DroneAltitudeCalibration(512.4, AtoSeedSource.AUTO_SEALED)
            )
        )
        assertTrue(DroneAltitudeCoordinator.shouldResetAutoCalibrationForFlightChange(null))
    }

    @Test
    fun shouldResetAutoCalibrationForFlightChange_preservesManualReferences() {
        assertFalse(
            DroneAltitudeCoordinator.shouldResetAutoCalibrationForFlightChange(
                DroneAltitudeCalibration(512.4, AtoSeedSource.MANUAL)
            )
        )
    }

    @Test
    fun calculateDemBackedAglMeters_prefersAtoOverDriftingAbsoluteAltitude() {
        val calibration = DroneAltitudeCalibration(522.6, AtoSeedSource.AUTO_SEALED)
        val demScaleToMeters = 0.3048
        val takeoffGroundRaw = 1567.5
        val correctionM = calibration.takeoffTrackAltitudeM - (takeoffGroundRaw * demScaleToMeters)

        val aglAtReturn = DroneAltitudeCoordinator.calculateDemBackedAglMeters(
            altM = 520.0,
            ridHeightAtoM = 0.0,
            calibration = calibration,
            correctionM = correctionM,
            demGroundRaw = takeoffGroundRaw,
            demScaleToMeters = demScaleToMeters,
        )

        assertEquals(0.0, aglAtReturn, 0.000001)
    }

    @Test
    fun calculateDemBackedAglMeters_appliesTerrainDeltaToAto() {
        val calibration = DroneAltitudeCalibration(522.6, AtoSeedSource.AUTO_SEALED)
        val demScaleToMeters = 0.3048
        val takeoffGroundRaw = 1567.5
        val correctionM = calibration.takeoffTrackAltitudeM - (takeoffGroundRaw * demScaleToMeters)

        val aglOverLowerGround = DroneAltitudeCoordinator.calculateDemBackedAglMeters(
            altM = 520.0,
            ridHeightAtoM = 10.0,
            calibration = calibration,
            correctionM = correctionM,
            demGroundRaw = takeoffGroundRaw - (5.0 / demScaleToMeters),
            demScaleToMeters = demScaleToMeters,
        )

        assertEquals(15.0, aglOverLowerGround, 0.000001)
    }

    @Test
    fun calculateDemBackedAglMeters_fallsBackToAbsoluteAltitudeWithoutAto() {
        val calibration = DroneAltitudeCalibration(522.6, AtoSeedSource.AUTO_SEALED)
        val demScaleToMeters = 0.3048
        val takeoffGroundRaw = 1567.5
        val correctionM = calibration.takeoffTrackAltitudeM - (takeoffGroundRaw * demScaleToMeters)

        val aglWithoutAto = DroneAltitudeCoordinator.calculateDemBackedAglMeters(
            altM = 520.0,
            ridHeightAtoM = null,
            calibration = calibration,
            correctionM = correctionM,
            demGroundRaw = takeoffGroundRaw,
            demScaleToMeters = demScaleToMeters,
        )

        assertEquals(-2.6, aglWithoutAto, 0.000001)
    }

    @Test
    fun calculateDemBackedAglMeters_allowsNegativeDemResult() {
        val calibration = DroneAltitudeCalibration(100.0, AtoSeedSource.AUTO_SEALED)

        val aglMeters = DroneAltitudeCoordinator.calculateDemBackedAglMeters(
            altM = 100.0,
            ridHeightAtoM = 0.0,
            calibration = calibration,
            correctionM = 0.0,
            demGroundRaw = 100.9144,
            demScaleToMeters = 1.0,
        )

        assertEquals(-0.9144, aglMeters, 0.000001)
    }
}
