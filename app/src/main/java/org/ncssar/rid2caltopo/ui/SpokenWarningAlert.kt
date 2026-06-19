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
    val phrases: List<String>,
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
        requestWarningSequence(
            kinds = listOf(kind),
            sourceKey = sourceKey,
            nowMs = nowMs,
            cooldownMs = cooldownMs,
            volumeFraction = volumeFraction
        )
    }

    fun requestWarningSequence(
        kinds: List<SpokenWarningKind>,
        sourceKey: String,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = 0L,
        volumeFraction: Float = 1.0f,
    ) {
        val firstKind = kinds.firstOrNull() ?: return
        val key = WarningKey(firstKind, sourceKey)
        val lastRequestedAtMs = lastRequestedAtMsByKey[key]
        if (lastRequestedAtMs != null && nowMs - lastRequestedAtMs < cooldownMs) return

        lastRequestedAtMsByKey[key] = nowMs
        val phrases = kinds.map { it.phrase }
        _requests.value = SpokenWarningRequest(
            requestId = nextRequestId++,
            kind = firstKind,
            phrase = firstKind.phrase,
            phrases = phrases,
            volumeFraction = volumeFraction.coerceIn(0f, 1f),
        )
    }

    fun requestAudioAlarmTest(nowMs: Long = System.currentTimeMillis()) {
        requestWarningSequence(
            kinds = listOf(
                SpokenWarningKind.DroneTelemetry,
                SpokenWarningKind.Altitude,
                SpokenWarningKind.Proximity,
                SpokenWarningKind.ControllerSignalStrength
            ),
            sourceKey = "audio-alarm-test",
            nowMs = nowMs,
            cooldownMs = 0L
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
        currentRequest.phrases.forEachIndexed { index, phrase ->
            tts.speak(
                phrase,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                params,
                "r2c-spoken-warning-${currentRequest.requestId}-$index"
            )
        }
    }
}
