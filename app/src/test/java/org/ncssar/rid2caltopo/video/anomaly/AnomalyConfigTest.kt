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
        assertEquals(33, native.frameStride)
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
    fun toNativeConfig_colorSelectionUsesFreshRgbaFrontend() {
        val config = AnomalyConfig(
            appearanceSelection = AppearanceAnomalySelection.Color,
            algorithms = emptySet(),
            colorFrontendMode = ColorFrontendMode.Legacy,
        )

        val native = config.toNativeConfig()

        assertEquals(AnomalyAlgorithm.ColorOutlier.nativeMask, native.algorithmMask)
        assertEquals(ColorFrontendMode.FreshRgba.nativeValue, native.colorFrontendMode)
        assertEquals(AnomalyStrideMode.Adaptive.nativeValue, native.strideMode)
        assertEquals(30, native.frameStride)
        assertEquals(30, native.adaptiveMinStrideFrames)
    }

    @Test
    fun toNativeConfig_colorRealtimeDefaultsUseAdaptiveStride() {
        val native = AnomalyConfig(
            appearanceSelection = AppearanceAnomalySelection.Color,
            algorithms = emptySet(),
        ).toNativeConfig(sourceFps = 29.97f)

        assertEquals(AnomalyAlgorithm.ColorOutlier.nativeMask, native.algorithmMask)
        assertEquals(AnomalyStrideMode.Adaptive.nativeValue, native.strideMode)
        assertEquals(30, native.frameStride)
        assertEquals(30, native.adaptiveMinStrideFrames)
        assertEquals(60, native.adaptiveMaxStrideFrames)
        assertEquals(2.0f, native.adaptiveMaxStrideSeconds)
        assertEquals(1, native.pixelStep)
        assertEquals(1, native.colorTargetCandidateLimit)
    }

    @Test
    fun toNativeConfig_colorCandidateLimitIsConfigurableAndClamped() {
        val low = AnomalyConfig(colorTargetCandidateLimit = 0).toNativeConfig()
        val high = AnomalyConfig(colorTargetCandidateLimit = 9).toNativeConfig()
        val manual = AnomalyConfig(colorTargetCandidateLimit = 3).toNativeConfig()

        assertEquals(1, low.colorTargetCandidateLimit)
        assertEquals(4, high.colorTargetCandidateLimit)
        assertEquals(3, manual.colorTargetCandidateLimit)
    }

    @Test
    fun toNativeConfig_targetColorFamiliesDefaultOff() {
        val native = AnomalyConfig().toNativeConfig()

        assertEquals(0, native.targetColorFamilyMask)
    }

    @Test
    fun toNativeConfig_targetColorFamiliesAreConfigurableAndClamped() {
        val selected =
            TargetColorFamily.White.nativeMask or
            TargetColorFamily.Pink.nativeMask or
            TargetColorFamily.Purple.nativeMask
        val withUnknownBits = selected or 0x8000
        val native = AnomalyConfig(targetColorFamilyMask = withUnknownBits).toNativeConfig()

        assertEquals(selected, native.targetColorFamilyMask)
    }

    @Test
    fun targetColorFamilies_arePlainOperatorColorTerms() {
        assertEquals(
            listOf(
                "White",
                "Black",
                "Grey",
                "Yellow",
                "Red",
                "Blue",
                "Green",
                "Brown",
                "Pink",
                "Orange",
                "Purple",
            ),
            TargetColorFamily.entries.map { it.label },
        )
        assertEquals(0x07FF, TargetColorFamily.allowedMask)
    }

    @Test
    fun targetColorFamilySummary_supportsMultiColorSelection() {
        assertEquals("None", targetColorFamilySummary(0))
        assertEquals("Red", targetColorFamilySummary(TargetColorFamily.Red.nativeMask))
        assertEquals(
            "Red, Blue",
            targetColorFamilySummary(
                TargetColorFamily.Red.nativeMask or TargetColorFamily.Blue.nativeMask
            )
        )
        assertEquals(
            "Red, Blue +2",
            targetColorFamilySummary(
                TargetColorFamily.Red.nativeMask or
                    TargetColorFamily.Blue.nativeMask or
                    TargetColorFamily.Orange.nativeMask or
                    TargetColorFamily.Purple.nativeMask
            )
        )
    }

    @Test
    fun targetColorSelectionEnabled_onlyAllowsColorMode() {
        assertTrue(targetColorSelectionEnabled(AppearanceAnomalySelection.Color))
        assertFalse(targetColorSelectionEnabled(AppearanceAnomalySelection.Thermal))
    }

    @Test
    fun appearanceSelectionFromPersisted_migratesAutoAndUnknownValuesToColor() {
        assertEquals(
            AppearanceAnomalySelection.Color,
            AnomalyPrefs.appearanceSelectionFromPersisted("Auto")
        )
        assertEquals(
            AppearanceAnomalySelection.Color,
            AnomalyPrefs.appearanceSelectionFromPersisted("unexpected")
        )
        assertEquals(
            AppearanceAnomalySelection.Thermal,
            AnomalyPrefs.appearanceSelectionFromPersisted("Thermal")
        )
    }

    @Test
    fun anomalyPrefs_stripTargetColorsAtPersistenceBoundary() {
        val selected =
            TargetColorFamily.Red.nativeMask or
            TargetColorFamily.White.nativeMask
        val config = AnomalyConfig(
            enabled = true,
            targetColorFamilyMask = selected,
            colorTargetCandidateLimit = 3,
        )

        val persistable = AnomalyPrefs.persistableConfig(config)
        val sessionDefault = AnomalyPrefs.sessionDefaultConfigFromPersisted(config)

        assertEquals(0, persistable.targetColorFamilyMask)
        assertEquals(0, sessionDefault.targetColorFamilyMask)
        assertEquals(3, persistable.colorTargetCandidateLimit)
        assertEquals(3, sessionDefault.colorTargetCandidateLimit)
        assertTrue(persistable.enabled)
        assertTrue(sessionDefault.enabled)
    }

    @Test
    fun toNativeConfig_colorPixelStepOverrideRemainsManual() {
        val native = AnomalyConfig(
            appearanceSelection = AppearanceAnomalySelection.Color,
            algorithms = emptySet(),
            pixelStep = 3,
        ).toNativeConfig(sourceFps = 29.97f)

        assertEquals(3, native.pixelStep)
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
        assertEquals(30, config.frameStride)
        assertEquals(30, config.adaptiveMinStrideFrames)
    }

    @Test
    fun resetToRealtimeDefaults_usesExplicitColorBaseCase() {
        val tuned = AnomalyConfig(
            enabled = true,
            appearanceSelection = AppearanceAnomalySelection.Color,
            algorithms = setOf(AnomalyAlgorithm.Motion, AnomalyAlgorithm.PersistentDarkPatch),
            saliencyEnabled = true,
            showHotOverlay = true,
            showCandidateBlobs = true,
            troubleshootingDebug = true,
            strideMode = AnomalyStrideMode.Fixed,
            frameStride = 2,
            adaptiveMinStrideFrames = 4,
            adaptiveMaxStrideSeconds = 3.0f,
            pixelStep = 3,
            colorTargetCandidateLimit = 4,
        )

        val reset = tuned.resetToRealtimeDefaults()

        assertTrue(reset.enabled)
        assertEquals(AppearanceAnomalySelection.Color, reset.appearanceSelection)
        assertEquals(setOf(AnomalyAlgorithm.Motion), reset.algorithms)
        assertFalse(reset.saliencyEnabled)
        assertFalse(reset.showHotOverlay)
        assertFalse(reset.showCandidateBlobs)
        assertFalse(reset.troubleshootingDebug)
        assertEquals(AnomalyStrideMode.Adaptive, reset.strideMode)
        assertEquals(30, reset.frameStride)
        assertEquals(30, reset.adaptiveMinStrideFrames)
        assertEquals(2.0f, reset.adaptiveMaxStrideSeconds)
        assertEquals(0, reset.pixelStep)
        assertEquals(1, reset.colorTargetCandidateLimit)
    }

    @Test
    fun resetToRealtimeDefaults_colorBaseCaseCarriesTwoSecondWindowToNative() {
        val reset = AnomalyConfig(
            enabled = true,
            appearanceSelection = AppearanceAnomalySelection.Color,
            frameStride = 4,
            adaptiveMinStrideFrames = 4,
            adaptiveMaxStrideSeconds = 0.25f,
            colorTargetCandidateLimit = 4,
        ).resetToRealtimeDefaults()

        val native = reset.toNativeConfig(sourceFps = 29.97f)

        assertEquals(AnomalyStrideMode.Adaptive.nativeValue, native.strideMode)
        assertEquals(30, native.frameStride)
        assertEquals(30, native.adaptiveMinStrideFrames)
        assertEquals(60, native.adaptiveMaxStrideFrames)
        assertEquals(2.0f, native.adaptiveMaxStrideSeconds)
        assertEquals(1, native.colorTargetCandidateLimit)
    }

    @Test
    fun resetToRealtimeDefaults_colorBaseCaseSurvivesSettingsApplyBounds() {
        val reset = AnomalyConfig(
            appearanceSelection = AppearanceAnomalySelection.Color,
            frameStride = 2,
            adaptiveMinStrideFrames = 2,
            adaptiveMaxStrideSeconds = 0.5f,
        ).resetToRealtimeDefaults()

        val appliedFromDialog = reset.copy(
            frameStride = reset.frameStride.coerceIn(1, 33),
            adaptiveMinStrideFrames = reset.adaptiveMinStrideFrames.coerceIn(2, 33),
            adaptiveMaxStrideSeconds = reset.adaptiveMaxStrideSeconds.coerceIn(0.1f, 10.0f),
        )
        val native = appliedFromDialog.toNativeConfig(sourceFps = 29.97f)

        assertEquals(30, appliedFromDialog.frameStride)
        assertEquals(30, appliedFromDialog.adaptiveMinStrideFrames)
        assertEquals(AnomalyStrideMode.Adaptive.nativeValue, native.strideMode)
        assertEquals(30, native.frameStride)
        assertEquals(30, native.adaptiveMinStrideFrames)
        assertEquals(60, native.adaptiveMaxStrideFrames)
    }

    @Test
    fun resetToRealtimeDefaults_keepsIrBaseCaseForThermal() {
        val tuned = AnomalyConfig(
            enabled = true,
            appearanceSelection = AppearanceAnomalySelection.Thermal,
            strideMode = AnomalyStrideMode.Adaptive,
            frameStride = 9,
            colorTargetCandidateLimit = 4,
        )

        val reset = tuned.resetToRealtimeDefaults()

        assertTrue(reset.enabled)
        assertEquals(AppearanceAnomalySelection.Thermal, reset.appearanceSelection)
        assertEquals(AnomalyStrideMode.Fixed, reset.strideMode)
        assertEquals(1, reset.frameStride)
        assertEquals(1, reset.colorTargetCandidateLimit)
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
        assertEquals(30, native.frameStride)
    }

    @Test
    fun forLocalPlaybackReview_keepsRealtimeSensitivityAndStrideWhenUntuned() {
        val reviewConfig = AnomalyConfig().forLocalPlaybackReview()

        assertEquals(0.59f, reviewConfig.sensitivity, 0.001f)
        assertTrue(reviewConfig.motionEnabled)
        assertEquals(AnomalyStrideMode.Fixed, reviewConfig.strideMode)
        assertEquals(1, reviewConfig.frameStride)

        val native = reviewConfig.toNativeConfig(sourceFps = 30.0f)
        assertEquals(3.04f, native.scoreThreshold, 0.01f)
        assertEquals(30, native.frameStride)
        assertEquals(
            AnomalyAlgorithm.ColorOutlier.nativeMask or AnomalyAlgorithm.Motion.nativeMask,
            native.algorithmMask
        )
    }

    @Test
    fun forLocalPlaybackReview_preservesPersistedCapturedPlaybackSettings() {
        val reviewConfig = AnomalyConfig(
            enabled = false,
            appearanceSelection = AppearanceAnomalySelection.Color,
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
            AnomalyAlgorithm.ColorOutlier.nativeMask or AnomalyAlgorithm.Motion.nativeMask,
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
    fun realtimeDefaults_matchDocumentedColorDefaults() {
        val config = AnomalyConfig()

        assertEquals(AppearanceAnomalySelection.Color, config.appearanceSelection)
        assertEquals(setOf(AnomalyAlgorithm.Motion), config.algorithms)
        assertEquals(ThermalPolarity.BlackHot, config.thermalPolarity)
        assertEquals(MotionRegistrationMode.Affine, config.registrationMode)
        assertEquals(MovementEstimatorMode.LayeredActive, config.movementEstimatorMode)
        assertEquals(AnomalyStrideMode.Fixed, config.strideMode)
        assertEquals(1, config.frameStride)
        assertEquals(2, config.adaptiveMinStrideFrames)
        assertEquals(1.0f, config.adaptiveMaxStrideSeconds)
        assertEquals(0.50f, config.scanZone)
        assertEquals(0.59f, config.sensitivity)
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
