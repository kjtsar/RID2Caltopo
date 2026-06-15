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
        assertEquals(AnomalyStrideMode.Fixed.nativeValue, native.strideMode)
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
    fun motionEnabled_tracksOnlyOperatorMotionToggle() {
        val withPersistedAppearance = AnomalyConfig(
            algorithms = setOf(AnomalyAlgorithm.ThermalHotspot, AnomalyAlgorithm.Motion),
        )
        val appearanceOnly = AnomalyConfig(
            algorithms = setOf(AnomalyAlgorithm.ThermalHotspot),
        )

        assertTrue(withPersistedAppearance.motionEnabled)
        assertFalse(appearanceOnly.motionEnabled)
    }

    @Test
    fun toNativeConfig_defaultMotionSensitivityMapsToNeutralScale() {
        val native = AnomalyConfig(
            motionEvidenceSensitivity = 0.60f,
        ).toNativeConfig()

        assertEquals(1.0f, native.motionEvidenceScale, 0.0001f)
    }

    @Test
    fun toNativeConfig_motionSensitivityKeepsLowAndHighEndpoints() {
        val low = AnomalyConfig(
            motionEvidenceSensitivity = 0.0f,
        ).toNativeConfig()
        val high = AnomalyConfig(
            motionEvidenceSensitivity = 1.0f,
        ).toNativeConfig()

        assertEquals(0.25f, low.motionEvidenceScale, 0.0001f)
        assertEquals(2.0f, high.motionEvidenceScale, 0.0001f)
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
        assertEquals(AnomalyStrideMode.Adaptive.nativeValue, native.strideMode)
        assertEquals(4, native.frameStride)
        assertEquals(4, native.adaptiveMinStrideFrames)
    }

    @Test
    fun toNativeConfig_colorRealtimeDefaultsUseAdaptiveStride() {
        val native = AnomalyConfig(
            appearanceSelection = AppearanceAnomalySelection.Color,
            algorithms = emptySet(),
        ).toNativeConfig(sourceFps = 29.97f)

        assertEquals(AnomalyAlgorithm.ColorOutlier.nativeMask, native.algorithmMask)
        assertEquals(AnomalyStrideMode.Adaptive.nativeValue, native.strideMode)
        assertEquals(4, native.frameStride)
        assertEquals(4, native.adaptiveMinStrideFrames)
        assertEquals(30, native.adaptiveMaxStrideFrames)
    }

    @Test
    fun toNativeConfig_colorStrideOverrideRemainsManual() {
        val native = AnomalyConfig(
            appearanceSelection = AppearanceAnomalySelection.Color,
            algorithms = emptySet(),
            strideMode = AnomalyStrideMode.Fixed,
            frameStride = 3,
        ).toNativeConfig(sourceFps = 29.97f)

        assertEquals(AnomalyStrideMode.Fixed.nativeValue, native.strideMode)
        assertEquals(3, native.frameStride)
        assertEquals(2, native.adaptiveMinStrideFrames)
    }

    @Test
    fun withAppearanceSelection_colorAdoptsRealtimeStrideDefaults() {
        val config = AnomalyConfig().withAppearanceSelection(AppearanceAnomalySelection.Color)

        assertEquals(AppearanceAnomalySelection.Color, config.appearanceSelection)
        assertEquals(AnomalyStrideMode.Adaptive, config.strideMode)
        assertEquals(4, config.frameStride)
        assertEquals(4, config.adaptiveMinStrideFrames)
    }

    @Test
    fun forLocalPlaybackReview_enablesDetectionWithoutChangingRealtimeConfig() {
        val config = AnomalyConfig(
            enabled = false,
            appearanceSelection = AppearanceAnomalySelection.Color,
            algorithms = setOf(AnomalyAlgorithm.Motion),
            sensitivity = 0.73f,
            thermalPolarity = ThermalPolarity.WhiteHot,
        )

        val reviewConfig = config.forLocalPlaybackReview()

        assertTrue(reviewConfig.enabled)
        assertEquals(config.appearanceSelection, reviewConfig.appearanceSelection)
        assertEquals(config.algorithms, reviewConfig.algorithms)
        assertEquals(config.sensitivity, reviewConfig.sensitivity, 0.001f)
        assertEquals(config.thermalPolarity, reviewConfig.thermalPolarity)
        assertEquals(config.strideMode, reviewConfig.strideMode)
        assertEquals(config.frameStride, reviewConfig.frameStride)
        assertEquals(config.adaptiveMinStrideFrames, reviewConfig.adaptiveMinStrideFrames)
        assertEquals(config.adaptiveMaxStrideSeconds, reviewConfig.adaptiveMaxStrideSeconds)

        val native = reviewConfig.toNativeConfig(sourceFps = 30.0f)
        assertEquals(AnomalyStrideMode.Adaptive.nativeValue, native.strideMode)
        assertEquals(4, native.frameStride)
    }

    @Test
    fun forLocalPlaybackReview_keepsRealtimeSensitivityAndStrideWhenUntuned() {
        val reviewConfig = AnomalyConfig().forLocalPlaybackReview()

        assertEquals(0.42f, reviewConfig.sensitivity, 0.001f)
        assertTrue(reviewConfig.motionEnabled)
        assertEquals(AnomalyStrideMode.Fixed, reviewConfig.strideMode)
        assertEquals(1, reviewConfig.frameStride)

        val native = reviewConfig.toNativeConfig(sourceFps = 30.0f)
        assertEquals(4.81f, native.scoreThreshold, 0.01f)
        assertEquals(1, native.frameStride)
        assertEquals(
            AnomalyAlgorithm.ThermalHotspot.nativeMask or AnomalyAlgorithm.Motion.nativeMask,
            native.algorithmMask
        )
    }

    @Test
    fun forLocalPlaybackReview_preservesPersistedCapturedPlaybackSettings() {
        val reviewConfig = AnomalyConfig(
            enabled = false,
            appearanceSelection = AppearanceAnomalySelection.Auto,
            algorithms = setOf(AnomalyAlgorithm.Motion),
            strideMode = AnomalyStrideMode.Fixed,
            frameStride = 2,
            adaptiveMinStrideFrames = 2,
            adaptiveMaxStrideSeconds = 1.0f,
            sensitivity = 0.59f,
        ).forLocalPlaybackReview()

        assertTrue(reviewConfig.enabled)
        assertEquals(0.59f, reviewConfig.sensitivity, 0.001f)
        assertTrue(reviewConfig.motionEnabled)
        assertEquals(AnomalyStrideMode.Fixed, reviewConfig.strideMode)
        assertEquals(2, reviewConfig.frameStride)

        val native = reviewConfig.toNativeConfig(sourceFps = 30.0f)
        assertEquals(
            AnomalyAlgorithm.ThermalHotspot.nativeMask or AnomalyAlgorithm.Motion.nativeMask,
            native.algorithmMask
        )
    }

    @Test
    fun forLocalPlaybackReview_preservesExplicitThermalCapturedPlaybackSettings() {
        val reviewConfig = AnomalyConfig(
            enabled = false,
            appearanceSelection = AppearanceAnomalySelection.Thermal,
            algorithms = setOf(AnomalyAlgorithm.Motion),
            strideMode = AnomalyStrideMode.Fixed,
            frameStride = 2,
            adaptiveMinStrideFrames = 2,
            adaptiveMaxStrideSeconds = 1.0f,
            sensitivity = 0.59f,
        ).forLocalPlaybackReview()

        assertTrue(reviewConfig.enabled)
        assertEquals(AppearanceAnomalySelection.Thermal, reviewConfig.appearanceSelection)
        assertEquals(0.59f, reviewConfig.sensitivity, 0.001f)
        assertEquals(2, reviewConfig.frameStride)
        assertTrue(reviewConfig.motionEnabled)

        val native = reviewConfig.toNativeConfig(sourceFps = 30.0f)
        assertEquals(
            AnomalyAlgorithm.ThermalHotspot.nativeMask or AnomalyAlgorithm.Motion.nativeMask,
            native.algorithmMask
        )
    }

    @Test
    fun forLocalPlaybackReview_preservesManualStrideTuning() {
        val config = AnomalyConfig(
            enabled = false,
            strideMode = AnomalyStrideMode.Adaptive,
            frameStride = 5,
            adaptiveMinStrideFrames = 4,
            adaptiveMaxStrideSeconds = 0.5f,
        )

        val reviewConfig = config.forLocalPlaybackReview()

        assertTrue(reviewConfig.enabled)
        assertEquals(config.strideMode, reviewConfig.strideMode)
        assertEquals(config.frameStride, reviewConfig.frameStride)
        assertEquals(config.adaptiveMinStrideFrames, reviewConfig.adaptiveMinStrideFrames)
        assertEquals(config.adaptiveMaxStrideSeconds, reviewConfig.adaptiveMaxStrideSeconds)
    }

    @Test
    fun realtimeDefaults_matchDocumentedIrDefaults() {
        val config = AnomalyConfig()

        assertEquals(AppearanceAnomalySelection.Auto, config.appearanceSelection)
        assertEquals(setOf(AnomalyAlgorithm.Motion), config.algorithms)
        assertEquals(ThermalPolarity.BlackHot, config.thermalPolarity)
        assertEquals(MotionRegistrationMode.Affine, config.registrationMode)
        assertEquals(MovementEstimatorMode.LayeredActive, config.movementEstimatorMode)
        assertEquals(AnomalyStrideMode.Fixed, config.strideMode)
        assertEquals(1, config.frameStride)
        assertEquals(2, config.adaptiveMinStrideFrames)
        assertEquals(1.0f, config.adaptiveMaxStrideSeconds)
        assertEquals(0.50f, config.scanZone)
        assertEquals(0.42f, config.sensitivity)
        assertEquals(2, config.minHits)
        assertEquals(10.0f, config.thermalMinDelta)

        val native = config.toNativeConfig(sourceFps = 30.0f)
        assertEquals(MovementEstimatorMode.LayeredActive.nativeValue, native.movementEstimatorMode)
    }

    @Test
    fun toNativeConfig_carriesAdaptiveStrideFieldsWithoutChangingFixedStride() {
        val config = AnomalyConfig(
            strideMode = AnomalyStrideMode.Adaptive,
            frameStride = 7,
            adaptiveMinStrideFrames = 1,
            adaptiveMaxStrideSeconds = 1.0f,
        )

        val native = config.toNativeConfig(sourceFps = 29.97f)

        assertEquals(AnomalyStrideMode.Adaptive.nativeValue, native.strideMode)
        assertEquals(7, native.frameStride)
        assertEquals(2, native.adaptiveMinStrideFrames)
        assertEquals(30, native.adaptiveMaxStrideFrames)
        assertEquals(1.0f, native.adaptiveMaxStrideSeconds)
    }

    @Test
    fun toNativeConfig_clampsAdaptiveMaxFramesToSafetyCap() {
        val native = AnomalyConfig(
            strideMode = AnomalyStrideMode.Adaptive,
            adaptiveMinStrideFrames = 40,
            adaptiveMaxStrideSeconds = 2.0f,
        ).toNativeConfig(sourceFps = 60.0f)

        assertEquals(33, native.adaptiveMinStrideFrames)
        assertEquals(33, native.adaptiveMaxStrideFrames)
        assertEquals(2.0f, native.adaptiveMaxStrideSeconds)
    }

    @Test
    fun toNativeConfig_mapsAdaptiveMaxFramesConservativelyWhenFpsUnknown() {
        val native = AnomalyConfig(
            strideMode = AnomalyStrideMode.Adaptive,
            adaptiveMinStrideFrames = 4,
            adaptiveMaxStrideSeconds = 1.0f,
        ).toNativeConfig()

        assertEquals(4, native.adaptiveMinStrideFrames)
        assertEquals(33, native.adaptiveMaxStrideFrames)
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
