package org.ncssar.rid2caltopo.video.anomaly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnomalyConfigTest {
    @Test
    fun toNativeConfig_respectsOverrideAndBounds() {
        val config = AnomalyConfig(
            enabled = true,
            algorithms = setOf(AnomalyAlgorithm.ColorOutlier, AnomalyAlgorithm.Motion),
            frameStride = 99,
            sensitivity = 1.2f,
            minAreaFraction = 0.0f,
        )

        val native = config.toNativeConfig(enabledOverride = false)

        assertFalse(native.enabled)
        assertEquals(AnomalyAlgorithm.ColorOutlier.nativeMask or AnomalyAlgorithm.Motion.nativeMask, native.algorithmMask)
        assertEquals(8, native.frameStride)
        assertTrue(native.scoreThreshold in 1.0f..15.0f)
        assertTrue(native.minAreaFraction in 0.00005f..0.03f)
        assertEquals(ThermalPolarity.WhiteHot.nativeValue, native.thermalPolarity)
    }

    @Test
    fun toNativeConfig_carriesThermalPolarity() {
        val config = AnomalyConfig(
            thermalPolarity = ThermalPolarity.BlackHot,
        )

        val native = config.toNativeConfig()

        assertEquals(ThermalPolarity.BlackHot.nativeValue, native.thermalPolarity)
    }

    @Test
    fun fromNativeName_mapsKnownAlgorithms() {
        assertEquals(AnomalyAlgorithm.ColorOutlier, AnomalyAlgorithm.fromNativeName("color_outlier"))
        assertEquals(AnomalyAlgorithm.ThermalHotspot, AnomalyAlgorithm.fromNativeName("thermal"))
        assertEquals(AnomalyAlgorithm.Motion, AnomalyAlgorithm.fromNativeName("movement"))
        assertEquals(null, AnomalyAlgorithm.fromNativeName("unknown"))
    }

    @Test
    fun sanitize_rejectsInvalidBoxes() {
        val valid = AnomalyDetection.sanitize(
            algorithm = AnomalyAlgorithm.Motion,
            score = 1.0f,
            leftNorm = 0.1f,
            topNorm = 0.1f,
            rightNorm = 0.2f,
            bottomNorm = 0.2f,
            sourceTimestampUs = 123L,
        )
        val invalid = AnomalyDetection.sanitize(
            algorithm = AnomalyAlgorithm.Motion,
            score = 1.0f,
            leftNorm = 0.3f,
            topNorm = 0.3f,
            rightNorm = 0.1f,
            bottomNorm = 0.2f,
            sourceTimestampUs = 123L,
        )

        assertNotNull(valid)
        assertEquals(null, invalid)
    }
}
