package org.ncssar.rid2caltopo.video.ffmpeg

/** Converts DJI's private SEI camera attitude fields into CalTopo's conventions. */
object DjiCameraOrientation {
    private const val RAW_HORIZONTAL_REFERENCE_DEG = -14.5625
    private const val RAW_DOWN_REFERENCE_DEG = -90.0

    @JvmStatic
    fun controllerAzimuthDeg(cameraAzimuthDeg: Double?): Double? {
        val finite = cameraAzimuthDeg?.takeIf { it.isFinite() } ?: return null
        // The M4TD controller and August 24 clue flight confirm that tag-4 offset 3
        // increases clockwise and is already aligned to the controller's north
        // convention. Raw 16.733 degrees corresponds to about 287-288 degrees.
        return (((finite - 90.0) % 360.0) + 360.0) % 360.0
    }

    /**
     * Matrice 4TD field calibration from the controlled flight observed on 2026-08-19:
     * raw -90 = straight down (-90), and raw -14.5625 = the controller's horizontal 0.
     */
    @JvmStatic
    fun calibratedTiltDeg(rawTiltDeg: Double?): Double? {
        val raw = rawTiltDeg?.takeIf { it.isFinite() } ?: return null
        val scale = 90.0 / (RAW_HORIZONTAL_REFERENCE_DEG - RAW_DOWN_REFERENCE_DEG)
        return ((raw - RAW_HORIZONTAL_REFERENCE_DEG) * scale).coerceIn(-90.0, 90.0)
    }

}
