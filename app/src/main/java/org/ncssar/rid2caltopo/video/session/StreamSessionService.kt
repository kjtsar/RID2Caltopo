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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn
import org.ncssar.rid2caltopo.data.DelayedExec

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
        releasePlayer(designator)
    }

    fun playerFor(designator: String): ExoPlayer? = playersByDesignator[designator]

    fun ensurePlayer(designator: String) {
        if (playersByDesignator.containsKey(designator)) return
        if (restarting.contains(designator)) return

        restarting += designator
        scope.launch {
            playersByDesignator[designator] = createPlayer(designator)
            restarting -= designator
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

        clearTracking(designator)
        restarting += designator
        lastRestartAt[designator] = now

        scope.launch {
            delay(policy.restartSettleDelayMs)
            playersByDesignator[designator] = createPlayer(designator)
            restarting -= designator
        }
    }

    fun releaseAll() {
        stallPoll.stop()
        playersByDesignator.values.forEach { it.release() }
        playersByDesignator.clear()
        restarting.clear()
        playerCreatedAt.clear()
        firstFrameRendered.clear()
        lastBufferingAt.clear()
        lastRestartAt.clear()
    }

    private fun startStallPoll() {
        val sampleMs = policy.maxBufferingMsBeforeRestart / 2
        stallPoll.start(this::pollForStalledPlayers, sampleMs, sampleMs)
    }

    private fun pollForStalledPlayers() {
        val now = System.currentTimeMillis()

        lastBufferingAt.forEach { (designator, bufferingSince) ->
            val player = playersByDesignator[designator] ?: return@forEach
            if (player.playbackState != Player.STATE_BUFFERING) return@forEach

            val bufferingMs = now - bufferingSince
            val startupMs = now - (playerCreatedAt[designator] ?: now)
            val stillStarting = !firstFrameRendered.contains(designator) && startupMs < policy.startupGraceMs

            if (stillStarting) return@forEach
            if (bufferingMs <= policy.maxBufferingMsBeforeRestart) return@forEach
            if (!canRestart(designator, now)) return@forEach

            CTWarn(tag, "Stalled stream '$designator' for ${bufferingMs}ms. Recreating player.")
            recreatePlayer(designator)
        }
    }

    private fun canRestart(designator: String, now: Long): Boolean {
        if (restarting.contains(designator)) return false
        val lastRestart = lastRestartAt[designator] ?: return true
        if (now - lastRestart < policy.restartCooldownMs) {
            CTDebug(tag, "Skipping restart for $designator due to cooldown.")
            return false
        }
        return true
    }

    private fun createPlayer(designator: String): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                policy.minBufferMs,
                policy.maxBufferMs,
                policy.bufferForPlaybackMs,
                policy.bufferForPlaybackAfterRebufferMs,
            )
            .build()

        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()

        val restartId = System.currentTimeMillis()
        val url = "http://127.0.0.1:8888/$designator/index.m3u8?rid=$restartId"
        CTDebug(tag, "Starting HLS player for $designator url='$url'")

        val mediaSource = HlsMediaSource.Factory(
            DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        ).createMediaSource(MediaItem.fromUri(url))

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
                listener?.onLive(designator)
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(designator, error)
                recreatePlayer(designator)
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
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
}
