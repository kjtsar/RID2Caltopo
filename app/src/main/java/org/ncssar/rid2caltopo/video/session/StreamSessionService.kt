package org.ncssar.rid2caltopo.video.session

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn
import org.ncssar.rid2caltopo.data.DelayedExec

data class RestartDecision(
    val allow: Boolean,
    val reason: String,
)

object StreamRestartBackoff {
    fun evaluate(
        isRestarting: Boolean,
        lastRestartAtMs: Long?,
        nowMs: Long,
        cooldownMs: Long,
    ): RestartDecision {
        if (isRestarting) return RestartDecision(allow = false, reason = "already-restarting")
        val lastRestart = lastRestartAtMs ?: return RestartDecision(allow = true, reason = "no-prior-restart")
        if (nowMs - lastRestart < cooldownMs) {
            return RestartDecision(allow = false, reason = "cooldown")
        }
        return RestartDecision(allow = true, reason = "cooldown-expired")
    }
}

class StreamSessionService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val policy: StreamRecoveryPolicy = StreamRecoveryPolicy(),
    private val sourcePathProvider: (String) -> String = { it },
    private val localPlaybackUriProvider: (String) -> Uri? = { null },
    private val burstyHlsSourceProvider: (String) -> Boolean = { false },
    private val restartCooldownProvider: (String) -> Long = { policy.restartCooldownMs },
    private val preferredModeProvider: (String) -> ProtocolMode = { ProtocolMode.HLS },
    private val isLocalPlaybackProvider: (String) -> Boolean = { false },
    private val listener: Listener? = null,
) {
    interface Listener {
        fun onBuffering(designator: String)
        fun onLive(designator: String)
        fun onEnded(designator: String)
        fun onError(designator: String, error: PlaybackException)
        fun onPlaybackDelayChanged(designator: String, delayMs: Long?)
    }

    private val tag = "StreamSessionService"

    private val playersByDesignator = mutableStateMapOf<String, ExoPlayer>()
    val players: Map<String, ExoPlayer> get() = playersByDesignator

    enum class ProtocolMode { RTSP, HLS }
    private val protocolByDesignator = mutableMapOf<String, ProtocolMode>()
    private val modeScoresByDesignator = mutableMapOf<String, MutableMap<ProtocolMode, Int>>()

    private val restarting = mutableSetOf<String>()
    private val playerCreatedAt = mutableMapOf<String, Long>()
    private val firstFrameRendered = mutableSetOf<String>()
    private val lastBufferingAt = mutableMapOf<String, Long>()
    private val lastRestartAt = mutableMapOf<String, Long>()

    private val stallPoll = DelayedExec()

    init {
        startStallPoll()
    }

    fun onStreamBecameLive(designator: String) {
        val currentMode = protocolByDesignator[designator]
        val preferredMode = preferredModeFor(designator)
        if (playersByDesignator.containsKey(designator)) {
            if (currentMode != null && currentMode != preferredMode) {
                CTDebug(tag, "Preferred mode change for $designator: $currentMode -> $preferredMode")
                recreatePlayer(designator, ignoreCooldown = true)
            }
            return
        }
        ensurePlayer(designator)
    }

    fun onStreamRepublished(designator: String) {
        if (!playersByDesignator.containsKey(designator)) {
            ensurePlayer(designator)
            return
        }
        CTDebug(tag, "Republish detected for $designator -> recreating player")
        recreatePlayer(designator, ignoreCooldown = true)
    }

    fun onStreamStopped(designator: String) {
        protocolByDesignator.remove(designator)
        releasePlayer(designator)
    }

    fun playerFor(designator: String): ExoPlayer? = playersByDesignator[designator]

    fun ensurePlayer(designator: String) {
        if (playersByDesignator.containsKey(designator)) return
        if (restarting.contains(designator)) return

        choosePreferredMode(designator)
        restarting += designator
        scope.launch {
            try {
                if (!restarting.contains(designator)) return@launch
                playersByDesignator[designator] = createPlayer(designator)
            } catch (t: Throwable) {
                CTWarn(tag, "ensurePlayer failed for '$designator': ${t.message}")
                playersByDesignator.remove(designator)
            } finally {
                restarting -= designator
            }
        }
    }

    fun releasePlayer(designator: String) {
        clearTracking(designator)
        listener?.onPlaybackDelayChanged(designator, null)
        playersByDesignator.remove(designator)?.let { player ->
            player.clearVideoSurface()
            player.release()
        }
    }

    fun recreatePlayer(designator: String, ignoreCooldown: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!ignoreCooldown && !canRestart(designator, now)) return

        val previousPlayer = playersByDesignator[designator]
        clearTracking(designator)
        restarting += designator
        lastRestartAt[designator] = now

        scope.launch {
            delay(policy.restartSettleDelayMs)
            if (!restarting.contains(designator)) return@launch
            try {
                val replacementPlayer = createPlayer(designator)
                val playerToRelease = playersByDesignator.put(designator, replacementPlayer)
                if (playerToRelease != null && playerToRelease !== replacementPlayer) {
                    playerToRelease.clearVideoSurface()
                    playerToRelease.release()
                }
            } catch (t: Throwable) {
                CTWarn(tag, "recreatePlayer failed for '$designator': ${t.message}")
                if (previousPlayer == null) {
                    playersByDesignator.remove(designator)
                }
            } finally {
                restarting -= designator
            }
        }
    }

    fun releaseAll() {
        stallPoll.stop()
        playersByDesignator.values.forEach { it.release() }
        playersByDesignator.clear()
        protocolByDesignator.clear()
        modeScoresByDesignator.clear()
        restarting.clear()
        playerCreatedAt.clear()
        firstFrameRendered.clear()
        lastBufferingAt.clear()
        lastRestartAt.clear()
    }

    private fun startStallPoll() {
        val sampleMs = minOf(
            policy.rtspMaxBufferingMsBeforeRestart,
            minOf(policy.hlsMaxBufferingMsBeforeRestart, BURSTY_HLS_MAX_BUFFERING_BEFORE_RESTART_MS)
        ) / 2
        stallPoll.start(this::pollForStalledPlayers, sampleMs, sampleMs)
    }

    private fun pollForStalledPlayers() {
        val now = System.currentTimeMillis()

        playersByDesignator.forEach { (designator, player) ->
            val liveOffsetMs = player.currentLiveOffset
            listener?.onPlaybackDelayChanged(
                designator,
                if (liveOffsetMs != C.TIME_UNSET) liveOffsetMs else null
            )
        }

        lastBufferingAt.toList().forEach { (designator, bufferingSince) ->
            val player = playersByDesignator[designator] ?: return@forEach
            if (player.playbackState != Player.STATE_BUFFERING) return@forEach

            val mode = protocolByDesignator[designator] ?: ProtocolMode.RTSP
            val burstyHls = mode == ProtocolMode.HLS && burstyHlsSourceProvider(designator)
            val maxBufferingMs = if (mode == ProtocolMode.RTSP) {
                policy.rtspMaxBufferingMsBeforeRestart
            } else if (burstyHls) {
                BURSTY_HLS_MAX_BUFFERING_BEFORE_RESTART_MS
            } else {
                policy.hlsMaxBufferingMsBeforeRestart
            }
            val startupGraceMs = if (mode == ProtocolMode.RTSP) {
                policy.rtspStartupGraceMs
            } else if (burstyHls) {
                BURSTY_HLS_STARTUP_GRACE_MS
            } else {
                policy.hlsStartupGraceMs
            }
            val bufferingMs = now - bufferingSince
            val startupMs = now - (playerCreatedAt[designator] ?: now)
            val stillStarting = !firstFrameRendered.contains(designator) && startupMs < startupGraceMs

            if (stillStarting) return@forEach
            if (bufferingMs <= maxBufferingMs) return@forEach
            if (!canRestart(designator, now)) return@forEach

            CTWarn(tag, "Stalled stream '$designator' mode=$mode for ${bufferingMs}ms. Recreating player.")
            recreatePlayer(designator)
        }
    }

    private fun canRestart(designator: String, now: Long): Boolean {
        val cooldownMs = restartCooldownProvider(designator)
        val decision = StreamRestartBackoff.evaluate(
            isRestarting = restarting.contains(designator),
            lastRestartAtMs = lastRestartAt[designator],
            nowMs = now,
            cooldownMs = cooldownMs,
        )
        if (!decision.allow && decision.reason == "cooldown") {
            CTDebug(tag, "Skipping restart for $designator due to cooldown.")
        }
        return decision.allow
    }

    private fun createPlayer(designator: String): ExoPlayer {
        val mode = modeFor(designator)
        val burstyHls = mode == ProtocolMode.HLS && burstyHlsSourceProvider(designator)

        val minBufferMs = when {
            mode == ProtocolMode.RTSP -> policy.rtspMinBufferMs
            burstyHls -> BURSTY_HLS_MIN_BUFFER_MS
            else -> policy.hlsMinBufferMs
        }
        val maxBufferMs = when {
            mode == ProtocolMode.RTSP -> policy.rtspMaxBufferMs
            burstyHls -> BURSTY_HLS_MAX_BUFFER_MS
            else -> policy.hlsMaxBufferMs
        }
        val bufferForPlaybackMs = when {
            mode == ProtocolMode.RTSP -> policy.rtspBufferForPlaybackMs
            burstyHls -> BURSTY_HLS_BUFFER_FOR_PLAYBACK_MS
            else -> policy.hlsBufferForPlaybackMs
        }
        val bufferForPlaybackAfterRebufferMs = when {
            mode == ProtocolMode.RTSP -> policy.rtspBufferForPlaybackAfterRebufferMs
            burstyHls -> BURSTY_HLS_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            else -> policy.hlsBufferForPlaybackAfterRebufferMs
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs,
            )
            .build()

        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()

        val mediaSource = createMediaSource(designator, mode, burstyHls)

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()

        player.setMediaSource(mediaSource)
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        attachPlayerListeners(player, designator)
        player.prepare()
        player.playWhenReady = true

        playerCreatedAt[designator] = System.currentTimeMillis()
        return player
    }

    private fun attachPlayerListeners(player: ExoPlayer, designator: String) {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameRendered += designator
                lastBufferingAt.remove(designator)
                adjustModeScore(designator, modeFor(designator), +3)
                val liveOffsetMs = player.currentLiveOffset
                listener?.onPlaybackDelayChanged(
                    designator,
                    if (liveOffsetMs != C.TIME_UNSET) liveOffsetMs else null
                )
                listener?.onLive(designator)
            }

            override fun onPlayerError(error: PlaybackException) {
                CTWarn(
                    tag,
                    "Player error for $designator mode=${modeFor(designator)} " +
                        "code=${error.errorCodeName} details=${describePlaybackError(error)}"
                )
                adjustModeScore(designator, modeFor(designator), -4)
                if (maybeSwitchProtocol(designator, error)) {
                    recreatePlayer(designator)
                    return
                }
                listener?.onError(designator, error)
                if (!playersByDesignator.containsKey(designator)) {
                    CTDebug(tag, "Skipping Exo restart for $designator because playback was rerouted.")
                    return
                }
                recreatePlayer(designator)
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        CTDebug(
                            tag,
                            "Buffering $designator mode=${modeFor(designator)}"
                        )
                        lastBufferingAt.putIfAbsent(designator, System.currentTimeMillis())
                        listener?.onBuffering(designator)
                    }
                    Player.STATE_READY -> {
                        restarting -= designator
                        val liveOffsetMs = player.currentLiveOffset
                        listener?.onPlaybackDelayChanged(
                            designator,
                            if (liveOffsetMs != C.TIME_UNSET) liveOffsetMs else null
                        )
                        if (firstFrameRendered.contains(designator)) {
                            lastBufferingAt.remove(designator)
                            listener?.onLive(designator)
                        } else {
                            listener?.onBuffering(designator)
                        }
                    }
                    Player.STATE_ENDED -> {
                        listener?.onEnded(designator)
                    }
                }
            }
        })
    }

    private fun describePlaybackError(error: PlaybackException): String {
        val parts = mutableListOf<String>()
        parts += "msg=${error.message ?: "n/a"}"

        var current: Throwable? = error.cause
        var depth = 0
        while (current != null && depth < 4) {
            val type = current::class.java.simpleName.ifBlank { current::class.java.name }
            val message = current.message ?: "n/a"
            parts += "cause$depth=$type:$message"
            current = current.cause
            depth += 1
        }

        return parts.joinToString(" | ")
    }

    private fun clearTracking(designator: String) {
        playerCreatedAt.remove(designator)
        firstFrameRendered.remove(designator)
        lastBufferingAt.remove(designator)
        restarting.remove(designator)
    }

    private fun createMediaSource(
        designator: String,
        mode: ProtocolMode,
        burstyHls: Boolean,
    ): androidx.media3.exoplayer.source.MediaSource {
        localPlaybackUriProvider(designator)?.let { localUri ->
            CTDebug(tag, "Starting local playback for $designator uri='$localUri'")
            return ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
                .createMediaSource(MediaItem.fromUri(localUri))
        }
        val sourcePath = sourcePathProvider(designator)
        return when (mode) {
            ProtocolMode.RTSP -> {
                val url = "rtsp://127.0.0.1:8554/$sourcePath"
                CTDebug(tag, "Starting RTSP player for $designator url='$url'")
                RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .setTimeoutMs(policy.rtspTimeoutMs.toLong())
                    .createMediaSource(MediaItem.fromUri(url))
            }
            ProtocolMode.HLS -> {
                val restartId = System.currentTimeMillis()
                val url = "http://127.0.0.1:8888/$sourcePath/index.m3u8?rid=$restartId"
                CTDebug(tag, "Starting HLS player for $designator url='$url'")
                val targetOffsetMs = if (burstyHls) BURSTY_HLS_TARGET_OFFSET_MS else 3_500L
                val minOffsetMs = if (burstyHls) BURSTY_HLS_MIN_OFFSET_MS else 2_500L
                val maxOffsetMs = if (burstyHls) BURSTY_HLS_MAX_OFFSET_MS else 7_000L
                val maxPlaybackSpeed = if (burstyHls) BURSTY_HLS_MAX_PLAYBACK_SPEED else 1.03f
                val connectTimeoutMs = if (burstyHls) BURSTY_HLS_CONNECT_TIMEOUT_MS else 3_000
                val readTimeoutMs = if (burstyHls) BURSTY_HLS_READ_TIMEOUT_MS else 8_000
                val mediaItem = MediaItem.Builder()
                    .setUri(url)
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(targetOffsetMs)
                            .setMinOffsetMs(minOffsetMs)
                            .setMaxOffsetMs(maxOffsetMs)
                            .setMinPlaybackSpeed(0.98f)
                            .setMaxPlaybackSpeed(maxPlaybackSpeed)
                            .build()
                    )
                    .build()
                HlsMediaSource.Factory(
                    DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(connectTimeoutMs)
                        .setReadTimeoutMs(readTimeoutMs)
                ).createMediaSource(mediaItem)
            }
        }
    }

    private fun maybeSwitchProtocol(designator: String, error: PlaybackException): Boolean {
        if (isLocalPlaybackProvider(designator)) return false
        val current = modeFor(designator)
        val scores = modeScoresByDesignator[designator] ?: return false
        // If the current mode's score has fallen below every alternative, switch now.
        val bestAlternative = ProtocolMode.entries
            .filter { it != current }
            .maxByOrNull { scores[it] ?: 0 }
            ?: return false
        val currentScore = scores[current] ?: 0
        val alternativeScore = scores[bestAlternative] ?: 0
        if (alternativeScore <= currentScore) return false
        CTDebug(
            tag,
            "Switching $designator from $current (score=$currentScore) " +
                "to $bestAlternative (score=$alternativeScore) after error: ${error.errorCodeName}"
        )
        protocolByDesignator[designator] = bestAlternative
        return true
    }

    private fun modeFor(designator: String): ProtocolMode {
        return protocolByDesignator[designator] ?: choosePreferredMode(designator)
    }

    private fun choosePreferredMode(designator: String): ProtocolMode {
        val mode = preferredModeFor(designator)
        protocolByDesignator[designator] = mode
        return mode
    }

    private fun preferredModeFor(designator: String): ProtocolMode {
        return preferredModeProvider(designator)
    }

    private companion object {
        const val BURSTY_HLS_MIN_BUFFER_MS = 4_000
        const val BURSTY_HLS_MAX_BUFFER_MS = 12_000
        const val BURSTY_HLS_BUFFER_FOR_PLAYBACK_MS = 1_500
        const val BURSTY_HLS_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
        const val BURSTY_HLS_MAX_BUFFERING_BEFORE_RESTART_MS = 1_500L
        const val BURSTY_HLS_STARTUP_GRACE_MS = 30_000L
        const val BURSTY_HLS_TARGET_OFFSET_MS = 5_000L
        const val BURSTY_HLS_MIN_OFFSET_MS = 3_500L
        const val BURSTY_HLS_MAX_OFFSET_MS = 10_000L
        const val BURSTY_HLS_MAX_PLAYBACK_SPEED = 1.02f
        const val BURSTY_HLS_CONNECT_TIMEOUT_MS = 3_000
        const val BURSTY_HLS_READ_TIMEOUT_MS = 12_000
    }

    private fun scoreFor(designator: String, mode: ProtocolMode): Int {
        val scores = modeScoresByDesignator.getOrPut(designator) {
            mutableMapOf(
                ProtocolMode.RTSP to 30,
                ProtocolMode.HLS to 20,
            )
        }
        return scores[mode] ?: 0
    }

    private fun adjustModeScore(designator: String, mode: ProtocolMode, delta: Int) {
        val scores = modeScoresByDesignator.getOrPut(designator) {
            mutableMapOf(
                ProtocolMode.RTSP to 30,
                ProtocolMode.HLS to 20,
            )
        }
        val before = scores[mode] ?: 0
        val after = (before + delta).coerceIn(-50, 100)
        scores[mode] = after
        CTDebug(tag, "Mode score $designator $mode: $before -> $after")
    }
}
