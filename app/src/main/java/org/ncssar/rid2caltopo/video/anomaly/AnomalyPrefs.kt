package org.ncssar.rid2caltopo.video.anomaly

import android.content.Context

object AnomalyPrefs {
    private const val PREFS_NAME = "anomaly_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SHOW_HOT_OVERLAY = "show_hot_overlay"
    private const val KEY_SHOW_CANDIDATE_BLOBS = "show_candidate_blobs"
    private const val KEY_ALGORITHMS = "algorithms"
    private const val KEY_SALIENCY_ENABLED = "saliency_enabled"
    private const val KEY_APPEARANCE_SELECTION = "appearance_selection"
    private const val KEY_FRAME_STRIDE = "frame_stride"
    private const val KEY_PIXEL_STEP = "pixel_step"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_MOTION_EVIDENCE_SENSITIVITY = "motion_evidence_sensitivity"
    private const val KEY_MIN_AREA_FRACTION = "min_area_fraction"
    private const val KEY_THERMAL_POLARITY = "thermal_polarity"
    private const val KEY_REGISTRATION_MODE = "registration_mode"
    private const val KEY_SCAN_ZONE = "scan_zone"
    private const val KEY_MIN_HITS = "min_hits"
    private const val KEY_THERMAL_MIN_DELTA = "thermal_min_delta"

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

        return AnomalyConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, defaults.enabled),
            showHotOverlay = prefs.getBoolean(KEY_SHOW_HOT_OVERLAY, defaults.showHotOverlay),
            showCandidateBlobs = prefs.getBoolean(KEY_SHOW_CANDIDATE_BLOBS, defaults.showCandidateBlobs),
            algorithms = algorithms,
            saliencyEnabled = prefs.getBoolean(KEY_SALIENCY_ENABLED, defaults.saliencyEnabled),
            appearanceSelection = appearanceSelection,
            frameStride = prefs.getInt(KEY_FRAME_STRIDE, defaults.frameStride).coerceIn(1, 8),
            pixelStep = prefs.getInt(KEY_PIXEL_STEP, defaults.pixelStep).coerceIn(0, 8),
            sensitivity = prefs.getFloat(KEY_SENSITIVITY, defaults.sensitivity).coerceIn(0f, 1f),
            motionEvidenceSensitivity = prefs
                .getFloat(KEY_MOTION_EVIDENCE_SENSITIVITY, defaults.motionEvidenceSensitivity)
                .coerceIn(0f, 1f),
            minAreaFraction = prefs.getFloat(KEY_MIN_AREA_FRACTION, defaults.minAreaFraction)
                .coerceIn(0.00005f, 0.03f),
            thermalPolarity = polarity,
            registrationMode = registrationMode,
            scanZone = prefs.getFloat(KEY_SCAN_ZONE, defaults.scanZone).coerceIn(0.5f, 1.0f),
            minHits = prefs.getInt(KEY_MIN_HITS, defaults.minHits).coerceIn(1, 10),
            thermalMinDelta = prefs.getFloat(KEY_THERMAL_MIN_DELTA, defaults.thermalMinDelta).coerceIn(1.0f, 64.0f),
        )
    }

    @JvmStatic
    fun save(context: Context, config: AnomalyConfig) {
        val normalized = config.copy(algorithms = config.nonAppearanceAlgorithms)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, normalized.enabled)
            .putBoolean(KEY_SHOW_HOT_OVERLAY, normalized.showHotOverlay)
            .putBoolean(KEY_SHOW_CANDIDATE_BLOBS, normalized.showCandidateBlobs)
            .putStringSet(KEY_ALGORITHMS, normalized.algorithms.map { it.name }.toSet())
            .putBoolean(KEY_SALIENCY_ENABLED, normalized.saliencyEnabled)
            .putString(KEY_APPEARANCE_SELECTION, normalized.appearanceSelection.name)
            .putInt(KEY_FRAME_STRIDE, normalized.frameStride.coerceIn(1, 8))
            .putInt(KEY_PIXEL_STEP, normalized.pixelStep.coerceIn(0, 8))
            .putFloat(KEY_SENSITIVITY, normalized.sensitivity.coerceIn(0f, 1f))
            .putFloat(
                KEY_MOTION_EVIDENCE_SENSITIVITY,
                normalized.motionEvidenceSensitivity.coerceIn(0f, 1f)
            )
            .putFloat(KEY_MIN_AREA_FRACTION, normalized.minAreaFraction.coerceIn(0.00005f, 0.03f))
            .putString(KEY_THERMAL_POLARITY, normalized.thermalPolarity.name)
            .putString(KEY_REGISTRATION_MODE, normalized.registrationMode.name)
            .putFloat(KEY_SCAN_ZONE, normalized.scanZone.coerceIn(0.5f, 1.0f))
            .putInt(KEY_MIN_HITS, normalized.minHits.coerceIn(1, 10))
            .putFloat(KEY_THERMAL_MIN_DELTA, normalized.thermalMinDelta.coerceIn(1.0f, 64.0f))
            .apply()
    }
}
