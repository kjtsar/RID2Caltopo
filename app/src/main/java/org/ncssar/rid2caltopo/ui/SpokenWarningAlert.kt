package org.ncssar.rid2caltopo.ui

import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.data.CaltopoClient

enum class SpokenWarningKind(val phrase: String) {
    DroneTelemetry("Drone Telemetry"),
    Altitude("Altitude"),
    Proximity("Proximity"),
    ControllerSignalStrength("Controller Signal Strength"),
}

data class SpokenWarningRequest(
    val requestId: Long,
    val kind: SpokenWarningKind,
    val phrase: String,
    val volumeFraction: Float,
)

object SpokenWarningCenter {
    private data class WarningKey(
        val kind: SpokenWarningKind,
        val sourceKey: String,
    )

    private val _requests = MutableStateFlow<SpokenWarningRequest?>(null)
    val requests: StateFlow<SpokenWarningRequest?> = _requests.asStateFlow()

    private val lastRequestedAtMsByKey = linkedMapOf<WarningKey, Long>()
    private var nextRequestId = 1L

    fun requestWarning(
        kind: SpokenWarningKind,
        sourceKey: String,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = 0L,
        volumeFraction: Float = 1.0f,
    ) {
        val key = WarningKey(kind, sourceKey)
        val lastRequestedAtMs = lastRequestedAtMsByKey[key]
        if (lastRequestedAtMs != null && nowMs - lastRequestedAtMs < cooldownMs) return

        lastRequestedAtMsByKey[key] = nowMs
        _requests.value = SpokenWarningRequest(
            requestId = nextRequestId++,
            kind = kind,
            phrase = kind.phrase,
            volumeFraction = volumeFraction.coerceIn(0f, 1f),
        )
    }

    fun resetForTests() {
        _requests.value = null
        lastRequestedAtMsByKey.clear()
        nextRequestId = 1L
    }
}

@Composable
fun SpokenWarningAlertHost() {
    val context = LocalContext.current
    val request by SpokenWarningCenter.requests.collectAsState()
    var ready by remember { mutableStateOf(false) }
    val tts = remember(context) {
        TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
        }
    }

    LaunchedEffect(ready) {
        if (ready) {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts.setSpeechRate(0.88f)
            tts.setPitch(0.82f)
        }
    }

    DisposableEffect(tts) {
        onDispose {
            try {
                tts.stop()
                tts.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(request?.requestId, ready) {
        val currentRequest = request ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val volume = (currentRequest.volumeFraction * CaltopoClient.GetAlarmVolumeMultiplier())
            .coerceIn(0f, 1f)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        tts.speak(
            currentRequest.phrase,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "r2c-spoken-warning-${currentRequest.requestId}"
        )
    }
}
