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
                djiRelativeNorthMmRaw = 1_000,
                djiRelativeEastMmRaw = 2_000,
                djiRelativeDownMmRaw = -500,
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
    fun registryUnwrapsLocalDisplacementAndDerivesCourseAndRelativeUp() {
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
            djiRelativeNorthMmRaw = 32_760,
            djiRelativeEastMmRaw = 0,
            djiRelativeDownMmRaw = -1_000,
        )
        StreamCameraTelemetryRegistry.update("WRAP", base, nowMs = 1_000)
        StreamCameraTelemetryRegistry.update(
            "WRAP",
            base.copy(
                sourceTimestampUs = 1_033_333,
                djiRelativeNorthMmRaw = -28_776, // +4,000 mm across the signed wrap
                djiRelativeEastMmRaw = 4_000,
                djiRelativeDownMmRaw = -3_500,
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
