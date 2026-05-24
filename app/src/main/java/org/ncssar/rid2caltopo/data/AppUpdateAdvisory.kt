package org.ncssar.rid2caltopo.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.ncssar.rid2caltopo.BuildConfig

data class AppUpdateAdvisoryState(
    val recommendedVersionCode: Int = 0,
    val updateUrl: String = "",
    val dismissedForSession: Boolean = false
) {
    val updateRequired: Boolean
        get() = recommendedVersionCode > BuildConfig.VERSION_CODE && !dismissedForSession
}

object AppUpdateAdvisory {
    private val _state = MutableStateFlow(AppUpdateAdvisoryState())
    val state: StateFlow<AppUpdateAdvisoryState> = _state

    @JvmStatic
    fun onTrackerRecommendation(recommendedVersionCode: Int, updateUrl: String?) {
        val normalizedCode = recommendedVersionCode.coerceAtLeast(0)
        val normalizedUrl = updateUrl?.trim().orEmpty()
        val current = _state.value
        val preserveDismissal = current.dismissedForSession &&
            current.recommendedVersionCode == normalizedCode &&
            current.updateUrl == normalizedUrl
        _state.value = current.copy(
            recommendedVersionCode = normalizedCode,
            updateUrl = normalizedUrl,
            dismissedForSession = preserveDismissal
        )
    }

    fun dismissForSession() {
        _state.value = _state.value.copy(dismissedForSession = true)
    }

    @JvmStatic
    fun resetForTesting() {
        _state.value = AppUpdateAdvisoryState()
    }
}
