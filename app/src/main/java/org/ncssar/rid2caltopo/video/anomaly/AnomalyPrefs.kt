package org.ncssar.rid2caltopo.video.anomaly

import android.content.Context

object AnomalyPrefs {
    private const val PREFS_NAME = "anomaly_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SHOW_GUIDE_BOXES = "show_guide_boxes"
    private const val KEY_SHOW_HOT_OVERLAY = "show_hot_overlay"
    private const val KEY_SHOW_CANDIDATE_BLOBS = "show_candidate_blobs"
    private const val KEY_TROUBLESHOOTING_DEBUG = "troubleshooting_debug"
    private const val KEY_ALGORITHMS = "algorithms"
    private const val KEY_SALIENCY_ENABLED = "saliency_enabled"
    private const val KEY_APPEARANCE_SELECTION = "appearance_selection"
    private const val KEY_STRIDE_MODE = "stride_mode"
    private const val KEY_FRAME_STRIDE = "frame_stride"
    private const val KEY_ADAPTIVE_MIN_STRIDE_FRAMES = "adaptive_min_stride_frames"
    private const val KEY_ADAPTIVE_MAX_STRIDE_SECONDS = "adaptive_max_stride_seconds"
    private const val KEY_PIXEL_STEP = "pixel_step"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_MOTION_EVIDENCE_SENSITIVITY = "motion_evidence_sensitivity"
    private const val KEY_MIN_AREA_FRACTION = "min_area_fraction"
    private const val KEY_THERMAL_POLARITY = "thermal_polarity"
    private const val KEY_REGISTRATION_MODE = "registration_mode"
    private const val KEY_MOVEMENT_ESTIMATOR_MODE = "movement_estimator_mode"
    private const val KEY_SCAN_ZONE = "scan_zone"
    private const val KEY_MIN_HITS = "min_hits"
    private const val KEY_THERMAL_MIN_DELTA = "thermal_min_delta"
    private const val KEY_SMALL_TARGET_SCREEN_FRACTION = "small_target_screen_fraction"

    private fun migrateLegacyRealtimeDefaultsIfNeeded(config: AnomalyConfig): AnomalyConfig {
        val legacyAlgorithms = setOf(AnomalyAlgorithm.ThermalHotspot)
        val temporaryRealtimeAlgorithms = setOf(
            AnomalyAlgorithm.ThermalHotspot,
            AnomalyAlgorithm.Motion,
        )
        val persistedNonAppearanceAlgorithms = config.nonAppearanceAlgorithms
        val thermalOnlyAppearance =
            config.resolvedAppearanceMode() == AppearanceAnomalyMode.Thermal &&
                persistedNonAppearanceAlgorithms.isEmpty()
        val colorOnlyAppearance =
            config.resolvedAppearanceMode() == AppearanceAnomalyMode.Color &&
                persistedNonAppearanceAlgorithms.isEmpty()
        val looksRealtimeStride =
            config.frameStride == 1 ||
                (config.frameStride == 10 &&
                    kotlin.math.abs(config.scanZone - 0.80f) < 0.001f &&
                    kotlin.math.abs(config.sensitivity - 0.60f) < 0.001f)
        val looksRealtimeDefault =
            (config.algorithms == legacyAlgorithms ||
                config.algorithms == temporaryRealtimeAlgorithms ||
                thermalOnlyAppearance ||
                colorOnlyAppearance) &&
                !config.saliencyEnabled &&
                looksRealtimeStride &&
                config.pixelStep == 0 &&
                config.registrationMode == MotionRegistrationMode.Affine &&
                config.movementEstimatorMode == MovementEstimatorMode.LegacyAffine &&
                config.scanZone >= 0.60f &&
                config.minHits == 2 &&
                kotlin.math.abs(config.thermalMinDelta - 10.0f) < 0.001f
        return if (looksRealtimeDefault) {
            config.copy(
                algorithms = setOf(AnomalyAlgorithm.Motion),
                scanZone = 0.50f,
                frameStride = 1,
                sensitivity = 0.42f,
                movementEstimatorMode = MovementEstimatorMode.LayeredActive,
            )
        } else {
            config
        }
    }

    @JvmStatic
    fun load(context: Context): AnomalyConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = AnomalyConfig()
        val algorithms = prefs.getStringSet(KEY_ALGORITHMS, null)
            ?.mapNotNull { name -> runCatching { AnomalyAlgorithm.valueOf(name) }.getOrNull() }
            ?.toSet()
            ?: defaults.algorithms
        val appearanceSelection = prefs.getString(KEY_APPEARANCE_SELECTION, defaults.appearanceSelection.name)
            ?.let { name -> runCatching { AppearanceAnomalySelection.valueOf(name) }.getOrNull() }
            ?: defaults.appearanceSelection
        val polarity = prefs.getString(KEY_THERMAL_POLARITY, defaults.thermalPolarity.name)
            ?.let { name -> runCatching { ThermalPolarity.valueOf(name) }.getOrNull() }
            ?: defaults.thermalPolarity
        val registrationMode = prefs.getString(KEY_REGISTRATION_MODE, defaults.registrationMode.name)
            ?.let { name -> runCatching { MotionRegistrationMode.valueOf(name) }.getOrNull() }
            ?: defaults.registrationMode
        val movementEstimatorMode = prefs.getString(KEY_MOVEMENT_ESTIMATOR_MODE, defaults.movementEstimatorMode.name)
            ?.let { name -> runCatching { MovementEstimatorMode.valueOf(name) }.getOrNull() }
            ?: defaults.movementEstimatorMode
        val strideMode = prefs.getString(KEY_STRIDE_MODE, defaults.strideMode.name)
            ?.let { name -> runCatching { AnomalyStrideMode.valueOf(name) }.getOrNull() }
            ?: defaults.strideMode

        val loaded = AnomalyConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, defaults.enabled),
            showGuideBoxes = prefs.getBoolean(KEY_SHOW_GUIDE_BOXES, defaults.showGuideBoxes),
            showHotOverlay = prefs.getBoolean(KEY_SHOW_HOT_OVERLAY, defaults.showHotOverlay),
            showCandidateBlobs = prefs.getBoolean(KEY_SHOW_CANDIDATE_BLOBS, defaults.showCandidateBlobs),
            troubleshootingDebug = prefs.getBoolean(KEY_TROUBLESHOOTING_DEBUG, defaults.troubleshootingDebug),
            algorithms = algorithms,
            saliencyEnabled = prefs.getBoolean(KEY_SALIENCY_ENABLED, defaults.saliencyEnabled),
            appearanceSelection = appearanceSelection,
            strideMode = strideMode,
            frameStride = prefs.getInt(KEY_FRAME_STRIDE, defaults.frameStride).coerceIn(1, 10),
            adaptiveMinStrideFrames = prefs
                .getInt(KEY_ADAPTIVE_MIN_STRIDE_FRAMES, defaults.adaptiveMinStrideFrames)
                .coerceIn(2, 33),
            adaptiveMaxStrideSeconds = prefs
                .getFloat(KEY_ADAPTIVE_MAX_STRIDE_SECONDS, defaults.adaptiveMaxStrideSeconds)
                .coerceIn(0.1f, 10.0f),
            pixelStep = prefs.getInt(KEY_PIXEL_STEP, defaults.pixelStep).coerceIn(0, 8),
            sensitivity = prefs.getFloat(KEY_SENSITIVITY, defaults.sensitivity).coerceIn(0f, 1f),
            motionEvidenceSensitivity = prefs
                .getFloat(KEY_MOTION_EVIDENCE_SENSITIVITY, defaults.motionEvidenceSensitivity)
                .coerceIn(0f, 1f),
            minAreaFraction = prefs.getFloat(KEY_MIN_AREA_FRACTION, defaults.minAreaFraction)
                .coerceIn(0.00005f, 0.03f),
            thermalPolarity = polarity,
            registrationMode = registrationMode,
            movementEstimatorMode = movementEstimatorMode,
            scanZone = prefs.getFloat(KEY_SCAN_ZONE, defaults.scanZone).coerceIn(0.5f, 1.0f),
            minHits = prefs.getInt(KEY_MIN_HITS, defaults.minHits).coerceIn(1, 10),
            thermalMinDelta = prefs.getFloat(KEY_THERMAL_MIN_DELTA, defaults.thermalMinDelta).coerceIn(1.0f, 64.0f),
            smallTargetScreenFraction = prefs
                .getFloat(KEY_SMALL_TARGET_SCREEN_FRACTION, defaults.smallTargetScreenFraction)
                .coerceIn(0.0015f, 0.03f),
        )
        val migrated = migrateLegacyRealtimeDefaultsIfNeeded(loaded)
        if (migrated != loaded) {
            save(context, migrated)
        }
        return migrated
    }

    @JvmStatic
    fun save(context: Context, config: AnomalyConfig) {
        val normalized = config.copy(algorithms = config.nonAppearanceAlgorithms)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, normalized.enabled)
            .putBoolean(KEY_SHOW_GUIDE_BOXES, normalized.showGuideBoxes)
            .putBoolean(KEY_SHOW_HOT_OVERLAY, normalized.showHotOverlay)
            .putBoolean(KEY_SHOW_CANDIDATE_BLOBS, normalized.showCandidateBlobs)
            .putBoolean(KEY_TROUBLESHOOTING_DEBUG, normalized.troubleshootingDebug)
            .putStringSet(KEY_ALGORITHMS, normalized.algorithms.map { it.name }.toSet())
            .putBoolean(KEY_SALIENCY_ENABLED, normalized.saliencyEnabled)
            .putString(KEY_APPEARANCE_SELECTION, normalized.appearanceSelection.name)
            .putString(KEY_STRIDE_MODE, normalized.strideMode.name)
            .putInt(KEY_FRAME_STRIDE, normalized.frameStride.coerceIn(1, 10))
            .putInt(KEY_ADAPTIVE_MIN_STRIDE_FRAMES, normalized.adaptiveMinStrideFrames.coerceIn(2, 33))
            .putFloat(
                KEY_ADAPTIVE_MAX_STRIDE_SECONDS,
                normalized.adaptiveMaxStrideSeconds.coerceIn(0.1f, 10.0f)
            )
            .putInt(KEY_PIXEL_STEP, normalized.pixelStep.coerceIn(0, 8))
            .putFloat(KEY_SENSITIVITY, normalized.sensitivity.coerceIn(0f, 1f))
            .putFloat(
                KEY_MOTION_EVIDENCE_SENSITIVITY,
                normalized.motionEvidenceSensitivity.coerceIn(0f, 1f)
            )
            .putFloat(KEY_MIN_AREA_FRACTION, normalized.minAreaFraction.coerceIn(0.00005f, 0.03f))
            .putString(KEY_THERMAL_POLARITY, normalized.thermalPolarity.name)
            .putString(KEY_REGISTRATION_MODE, normalized.registrationMode.name)
            .putString(KEY_MOVEMENT_ESTIMATOR_MODE, normalized.movementEstimatorMode.name)
            .putFloat(KEY_SCAN_ZONE, normalized.scanZone.coerceIn(0.5f, 1.0f))
            .putInt(KEY_MIN_HITS, normalized.minHits.coerceIn(1, 10))
            .putFloat(KEY_THERMAL_MIN_DELTA, normalized.thermalMinDelta.coerceIn(1.0f, 64.0f))
            .putFloat(
                KEY_SMALL_TARGET_SCREEN_FRACTION,
                normalized.smallTargetScreenFraction.coerceIn(0.0015f, 0.03f)
            )
            .apply()
    }
}
