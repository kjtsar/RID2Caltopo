package org.ncssar.rid2caltopo.video.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamCameraTelemetryRegistryTest {
    @Test
    fun registryKeepsOnlyFreshCompleteDjiSamples() {
        StreamCameraTelemetryRegistry.update(
            designator = "MTRC4TD",
            telemetry = FfmpegTelemetry(
                sourceTag = "dji-sei-245",
                sourceTimestampUs = 1_250_000,
                gimbalPitchDeg = -37.0,
                cameraYawDeg = 111.46,
                horizontalFovDeg = 37.703125,
                verticalFovDeg = 21.207031,
                latitude = 39.153083,
                longitude = -121.132845,
                altitudeMeters = 574.595,
                djiAttitudeAnglesDeg = (0..8).map(Int::toDouble),
                djiNorthMm = 1_000,
                djiEastMm = 2_000,
                djiDownMm = -574_595,
            ),
            nowMs = 10_000,
        )

        val fresh = StreamCameraTelemetryRegistry.fresh(
            "mtrc4td",
            nowMs = 12_999,
        )
        // Host Android stubs return zero declination; production GeomagneticField
        // supplies the location/date-specific magnetic-to-true correction.
        assertEquals(338.54, fresh?.azimuthDeg ?: 0.0, 1e-9)
        assertNull(fresh?.courseDeg)
        assertEquals(-26.768848384424192, fresh?.tiltDeg ?: 0.0, 1e-9)
        assertEquals(111.46, fresh?.rawCameraAzimuthDeg ?: 0.0, 1e-9)
        assertEquals(-37.0, fresh?.rawTiltDeg ?: 0.0, 0.0)
        assertEquals(39.153091983, fresh?.latitudeDeg ?: 0.0, 1e-9)
        assertEquals(-121.132821868, fresh?.longitudeDeg ?: 0.0, 1e-7)
        assertNull(fresh?.altitudeMeters)
        assertEquals(0.0, fresh?.relativeUpMeters ?: -1.0, 0.0)
        assertEquals(39.153083, fresh?.referenceLatitudeDeg ?: 0.0, 0.0)
        assertEquals(-121.132845, fresh?.referenceLongitudeDeg ?: 0.0, 0.0)
        assertEquals(574.595, fresh?.referenceAltitudeMeters ?: 0.0, 0.0)
        assertEquals((0..8).map(Int::toDouble), fresh?.attitudeAnglesDeg)
        assertNull(StreamCameraTelemetryRegistry.fresh("MTRC4TD", nowMs = 13_001))
        StreamCameraTelemetryRegistry.clear("MTRC4TD")
    }

    @Test
    fun registryUsesFullWidthDisplacementAndDerivesCourseAndRelativeUp() {
        val base = FfmpegTelemetry(
            sourceTag = "dji-sei-245",
            sourceTimestampUs = 1_000_000,
            gimbalPitchDeg = -14.5625,
            cameraYawDeg = 75.3,
            horizontalFovDeg = 40.0,
            verticalFovDeg = 25.0,
            latitude = 39.0,
            longitude = -121.0,
            altitudeMeters = 595.8,
            djiNorthMm = 32_760,
            djiEastMm = 0,
            djiDownMm = -595_800,
        )
        StreamCameraTelemetryRegistry.update("WRAP", base, nowMs = 1_000)
        StreamCameraTelemetryRegistry.update(
            "WRAP",
            base.copy(
                sourceTimestampUs = 1_033_333,
                djiNorthMm = 36_760,
                djiEastMm = 4_000,
                djiDownMm = -598_300,
            ),
            nowMs = 1_010,
        )
        val sample = StreamCameraTelemetryRegistry.fresh("WRAP", nowMs = 1_010)
        assertEquals(36.760, sample?.northMeters ?: 0.0, 0.0)
        assertEquals(4.0, sample?.eastMeters ?: 0.0, 0.0)
        assertEquals(2.5, sample?.relativeUpMeters ?: 0.0, 0.0)
        assertEquals(45.0, sample?.courseDeg ?: 0.0, 1e-9)
        assertEquals(14.7, sample?.azimuthDeg ?: 0.0, 1e-9)
        StreamCameraTelemetryRegistry.clear("WRAP")
    }

    @Test
    fun freshAnchoredValidatesFullWidthPositionAfterStreamRestart() {
        val referenceLatitude = 39.319435
        val referenceLongitude = -120.658820
        StreamCameraTelemetryRegistry.update(
            "RESTART",
            FfmpegTelemetry(
                sourceTag = "dji-sei-245",
                sourceTimestampUs = 1_000_000,
                gimbalPitchDeg = -90.0,
                cameraYawDeg = 75.0,
                horizontalFovDeg = 37.7,
                verticalFovDeg = 21.2,
                latitude = referenceLatitude,
                longitude = referenceLongitude,
                altitudeMeters = 1_394.0,
                djiNorthMm = 375_216,
                djiEastMm = -371_216,
                djiDownMm = -1_462_000,
            ),
            nowMs = 2_000,
        )
        val targetNorthMeters = 375.216
        val targetEastMeters = -371.216
        val targetLatitude = referenceLatitude + Math.toDegrees(targetNorthMeters / 6_378_137.0)
        val targetLongitude = referenceLongitude + Math.toDegrees(
            targetEastMeters / (6_378_137.0 * kotlin.math.cos(Math.toRadians(referenceLatitude)))
        )

        val anchored = StreamCameraTelemetryRegistry.freshAnchored(
            designator = "RESTART",
            anchorLatitudeDeg = targetLatitude + Math.toDegrees(1.5 / 6_378_137.0),
            anchorLongitudeDeg = targetLongitude,
            anchorAltitudeMeters = 1_462.0,
            takeoffMslMeters = 1_394.0,
            nowMs = 2_000,
        )

        assertEquals(targetLatitude, anchored?.latitudeDeg ?: 0.0, 1e-10)
        assertEquals(targetLongitude, anchored?.longitudeDeg ?: 0.0, 1e-10)
        assertEquals(targetNorthMeters, anchored?.northMeters ?: 0.0, 1e-9)
        assertEquals(targetEastMeters, anchored?.eastMeters ?: 0.0, 1e-9)
        assertEquals(68.0, anchored?.relativeUpMeters ?: 0.0, 1e-9)
        StreamCameraTelemetryRegistry.clear("RESTART")
    }

    @Test
    fun freshAnchoredWithholdsPositionWhenRidDisagrees() {
        StreamCameraTelemetryRegistry.update(
            "AMBIGUOUS",
            FfmpegTelemetry(
                sourceTag = "dji-sei-245",
                sourceTimestampUs = 1_000_000,
                gimbalPitchDeg = -90.0,
                cameraYawDeg = 75.0,
                horizontalFovDeg = 37.7,
                verticalFovDeg = 21.2,
                latitude = 39.0,
                longitude = -121.0,
                altitudeMeters = 500.0,
                djiNorthMm = 0,
                djiEastMm = 0,
                djiDownMm = -500_000,
            ),
            nowMs = 3_000,
        )
        // A 46.3 m diagonal difference is outside the RID plausibility gate.
        val anchorLatitude = 39.0 + Math.toDegrees(32.768 / 6_378_137.0)
        val anchorLongitude = -121.0 + Math.toDegrees(
            32.768 / (6_378_137.0 * kotlin.math.cos(Math.toRadians(39.0)))
        )
        val anchored = StreamCameraTelemetryRegistry.freshAnchored(
            "AMBIGUOUS",
            anchorLatitude,
            anchorLongitude,
            nowMs = 3_000,
        )
        assertNull(anchored?.latitudeDeg)
        assertNull(anchored?.longitudeDeg)
        assertEquals(15.0, anchored?.azimuthDeg ?: 0.0, 1e-9)
        StreamCameraTelemetryRegistry.clear("AMBIGUOUS")
    }

    @Test
    fun orientationNormalizesSeiAzimuthAndUsesTwoPointTiltCalibration() {
        assertEquals(99.13, DjiCameraOrientation.trueAzimuthDeg(5.0, 14.13) ?: 0.0, 1e-9)
        assertEquals(23.33, DjiCameraOrientation.trueAzimuthDeg(80.8, 14.13) ?: 0.0, 1e-9)
        assertEquals(105.0, DjiCameraOrientation.trueAzimuthDeg(-1.0, 14.0) ?: 0.0, 0.0)
        assertNull(DjiCameraOrientation.trueAzimuthDeg(null, 14.0))
        assertNull(DjiCameraOrientation.trueAzimuthDeg(80.8, null))
        assertEquals(-90.0, DjiCameraOrientation.calibratedTiltDeg(-90.0) ?: 0.0, 0.0)
        assertEquals(0.0, DjiCameraOrientation.calibratedTiltDeg(-14.5625) ?: 1.0, 0.0)
        assertEquals(-11.86, DjiCameraOrientation.calibratedTiltDeg(-24.5) ?: 0.0, 0.02)
        assertEquals(90.0, DjiCameraOrientation.calibratedTiltDeg(120.0) ?: 0.0, 0.0)
    }
}
