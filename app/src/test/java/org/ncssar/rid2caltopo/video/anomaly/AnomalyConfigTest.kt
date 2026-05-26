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
            appearanceSelection = AppearanceAnomalySelection.Color,
            algorithms = setOf(AnomalyAlgorithm.Motion),
            saliencyEnabled = false,
            frameStride = 99,
            sensitivity = 1.2f,
            minAreaFraction = 0.0f,
        )

        val native = config.toNativeConfig(enabledOverride = false)
        val expectedMask =
            AnomalyAlgorithm.ColorOutlier.nativeMask or
            AnomalyAlgorithm.Motion.nativeMask

        assertFalse(native.enabled)
        assertEquals(expectedMask, native.algorithmMask)
        assertEquals(MotionRegistrationMode.Affine.nativeValue, native.registrationMode)
        assertEquals(10, native.frameStride)
        assertTrue(native.scoreThreshold in 1.0f..15.0f)
        assertTrue(native.minAreaFraction in 0.00005f..0.03f)
        assertEquals(ThermalPolarity.BlackHot.nativeValue, native.thermalPolarity)
        assertEquals(ColorFrontendMode.FreshRgba.nativeValue, native.colorFrontendMode)
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
    fun toNativeConfig_thermalOnlyExcludesColorInfluence() {
        val config = AnomalyConfig(
            appearanceSelection = AppearanceAnomalySelection.Thermal,
            algorithms = emptySet(),
            saliencyEnabled = false,
        )

        val native = config.toNativeConfig()

        assertEquals(AnomalyAlgorithm.ThermalHotspot.nativeMask, native.algorithmMask)
    }

    @Test
    fun toNativeConfig_thermalAppearanceWithMotionAndSaliencyStillExcludesColor() {
        val config = AnomalyConfig(
            appearanceSelection = AppearanceAnomalySelection.Thermal,
            algorithms = setOf(AnomalyAlgorithm.Motion),
            saliencyEnabled = true,
        )

        val native = config.toNativeConfig()
        val expectedMask =
            AnomalyAlgorithm.ThermalHotspot.nativeMask or
            AnomalyAlgorithm.Motion.nativeMask or
            AnomalyAlgorithm.PersistentDarkPatch.nativeMask

        assertEquals(expectedMask, native.algorithmMask)
        assertEquals(0, native.algorithmMask and AnomalyAlgorithm.ColorOutlier.nativeMask)
        assertEquals(ColorFrontendMode.Legacy.nativeValue, native.colorFrontendMode)
    }

    @Test
    fun toNativeConfig_autoColorDetectionUsesFreshRgbaFrontend() {
        val config = AnomalyConfig(
            appearanceSelection = AppearanceAnomalySelection.Auto,
            algorithms = emptySet(),
            colorFrontendMode = ColorFrontendMode.Legacy,
        )

        val native = config.toNativeConfig(
            detectedAppearanceMode = AppearanceAnomalyMode.Color
        )

        assertEquals(AnomalyAlgorithm.ColorOutlier.nativeMask, native.algorithmMask)
        assertEquals(ColorFrontendMode.FreshRgba.nativeValue, native.colorFrontendMode)
    }

    @Test
    fun realtimeDefaults_matchDocumentedIrDefaults() {
        val config = AnomalyConfig()

        assertEquals(AppearanceAnomalySelection.Auto, config.appearanceSelection)
        assertEquals(setOf(AnomalyAlgorithm.Motion), config.algorithms)
        assertEquals(ThermalPolarity.BlackHot, config.thermalPolarity)
        assertEquals(MotionRegistrationMode.Affine, config.registrationMode)
        assertEquals(1, config.frameStride)
        assertEquals(0.50f, config.scanZone)
        assertEquals(0.42f, config.sensitivity)
        assertEquals(2, config.minHits)
        assertEquals(10.0f, config.thermalMinDelta)
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
