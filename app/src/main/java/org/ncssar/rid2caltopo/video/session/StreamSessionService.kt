package org.ncssar.rid2caltopo.video.session

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
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
    private val listener: Listener? = null,
) {
    interface Listener {
        fun onBuffering(designator: String)
        fun onLive(designator: String)
        fun onEnded(designator: String)
        fun onError(designator: String, error: PlaybackException)
    }

    private val tag = "StreamSessionService"

    private val playersByDesignator = mutableStateMapOf<String, ExoPlayer>()
    val players: Map<String, ExoPlayer> get() = playersByDesignator

    private enum class ProtocolMode { RTSP, HLS }
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
        ensurePlayer(designator)
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
        playersByDesignator.remove(designator)?.let { player ->
            player.clearVideoSurface()
            player.release()
        }
    }

    fun recreatePlayer(designator: String) {
        val now = System.currentTimeMillis()
        if (!canRestart(designator, now)) return

        playersByDesignator.remove(designator)?.let { player ->
            player.clearVideoSurface()
            player.release()
        }
        clearTracking(designator)
        restarting += designator
        lastRestartAt[designator] = now

        scope.launch {
            delay(policy.restartSettleDelayMs)
            try {
                playersByDesignator[designator] = createPlayer(designator)
            } catch (t: Throwable) {
                CTWarn(tag, "recreatePlayer failed for '$designator': ${t.message}")
                playersByDesignator.remove(designator)
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
        val sampleMs = policy.rtspMaxBufferingMsBeforeRestart / 2
        stallPoll.start(this::pollForStalledPlayers, sampleMs, sampleMs)
    }

    private fun pollForStalledPlayers() {
        val now = System.currentTimeMillis()

        lastBufferingAt.forEach { (designator, bufferingSince) ->
            val player = playersByDesignator[designator] ?: return@forEach
            if (player.playbackState != Player.STATE_BUFFERING) return@forEach

            val mode = protocolByDesignator[designator] ?: ProtocolMode.RTSP
            val maxBufferingMs = if (mode == ProtocolMode.RTSP) {
                policy.rtspMaxBufferingMsBeforeRestart
            } else {
                policy.hlsMaxBufferingMsBeforeRestart
            }
            val bufferingMs = now - bufferingSince
            val startupMs = now - (playerCreatedAt[designator] ?: now)
            val stillStarting = !firstFrameRendered.contains(designator) && startupMs < policy.startupGraceMs

            if (stillStarting) return@forEach
            if (bufferingMs <= maxBufferingMs) return@forEach
            if (!canRestart(designator, now)) return@forEach

            CTWarn(tag, "Stalled stream '$designator' mode=$mode for ${bufferingMs}ms. Recreating player.")
            recreatePlayer(designator)
        }
    }

    private fun canRestart(designator: String, now: Long): Boolean {
        val decision = StreamRestartBackoff.evaluate(
            isRestarting = restarting.contains(designator),
            lastRestartAtMs = lastRestartAt[designator],
            nowMs = now,
            cooldownMs = policy.restartCooldownMs,
        )
        if (!decision.allow && decision.reason == "cooldown") {
            CTDebug(tag, "Skipping restart for $designator due to cooldown.")
        }
        return decision.allow
    }

    private fun createPlayer(designator: String): ExoPlayer {
        val mode = modeFor(designator)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (mode == ProtocolMode.RTSP) policy.rtspMinBufferMs else policy.hlsMinBufferMs,
                if (mode == ProtocolMode.RTSP) policy.rtspMaxBufferMs else policy.hlsMaxBufferMs,
                if (mode == ProtocolMode.RTSP) policy.rtspBufferForPlaybackMs else policy.hlsBufferForPlaybackMs,
                if (mode == ProtocolMode.RTSP) policy.rtspBufferForPlaybackAfterRebufferMs else policy.hlsBufferForPlaybackAfterRebufferMs,
            )
            .build()

        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()

        val mediaSource = createMediaSource(designator, mode)

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
                listener?.onLive(designator)
            }

            override fun onPlayerError(error: PlaybackException) {
                CTDebug(
                    tag,
                    "Player error for $designator: code=${error.errorCodeName} msg=${error.message}"
                )
                adjustModeScore(designator, modeFor(designator), -4)
                if (maybeSwitchProtocol(designator, error)) {
                    recreatePlayer(designator)
                    return
                }
                listener?.onError(designator, error)
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
                        lastBufferingAt.remove(designator)
                        firstFrameRendered += designator
                        restarting -= designator
                        listener?.onLive(designator)
                    }
                    Player.STATE_ENDED -> {
                        listener?.onEnded(designator)
                    }
                }
            }
        })
    }

    private fun clearTracking(designator: String) {
        playerCreatedAt.remove(designator)
        firstFrameRendered.remove(designator)
        lastBufferingAt.remove(designator)
        restarting.remove(designator)
    }

    private fun createMediaSource(designator: String, mode: ProtocolMode): androidx.media3.exoplayer.source.MediaSource {
        return when (mode) {
            ProtocolMode.RTSP -> {
                val url = "rtsp://127.0.0.1:8554/$designator"
                CTDebug(tag, "Starting RTSP player for $designator url='$url'")
                RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(url))
            }
            ProtocolMode.HLS -> {
                val restartId = System.currentTimeMillis()
                val url = "http://127.0.0.1:8888/$designator/index.m3u8?rid=$restartId"
                CTDebug(tag, "Starting HLS fallback player for $designator url='$url'")
                val mediaItem = MediaItem.Builder()
                    .setUri(url)
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(2_000)
                            .setMinOffsetMs(1_000)
                            .setMaxOffsetMs(5_000)
                            .setMinPlaybackSpeed(0.97f)
                            .setMaxPlaybackSpeed(1.05f)
                            .build()
                    )
                    .build()
                HlsMediaSource.Factory(
                    DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                ).createMediaSource(mediaItem)
            }
        }
    }

    private fun maybeSwitchProtocol(designator: String, error: PlaybackException): Boolean {
        val current = modeFor(designator)
        val other = if (current == ProtocolMode.RTSP) ProtocolMode.HLS else ProtocolMode.RTSP

        if (current == ProtocolMode.RTSP &&
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
            adjustModeScore(designator, ProtocolMode.RTSP, -12)
            adjustModeScore(designator, ProtocolMode.HLS, +4)
        } else if (current == ProtocolMode.HLS &&
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
            adjustModeScore(designator, ProtocolMode.HLS, -8)
        }

        val currentScore = scoreFor(designator, current)
        val otherScore = scoreFor(designator, other)
        if (otherScore <= currentScore) return false

        protocolByDesignator[designator] = other
        lastRestartAt.remove(designator)
        CTWarn(
            tag,
            "Switching $designator from $current(score=$currentScore) to $other(score=$otherScore) after error."
        )
        return true
    }

    private fun modeFor(designator: String): ProtocolMode {
        return protocolByDesignator[designator] ?: choosePreferredMode(designator)
    }

    private fun choosePreferredMode(designator: String): ProtocolMode {
        val rtsp = scoreFor(designator, ProtocolMode.RTSP)
        val hls = scoreFor(designator, ProtocolMode.HLS)
        val mode = if (rtsp >= hls) ProtocolMode.RTSP else ProtocolMode.HLS
        protocolByDesignator[designator] = mode
        return mode
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
