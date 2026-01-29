package org.ncssar.rid2caltopo.video

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.ui.StreamPlayerView
import java.time.Instant

@Composable
fun StreamTile(
    designator: String,
    state: StreamState,
    activity: Activity,
    onToggleFocus: () -> Unit,
    onSnapshot: (ClueSnapshot) -> Unit
) {
    val playerViewRef = remember {mutableStateOf<PlayerView?>(null)}
    val player : ExoPlayer? = null
    val stateText = when (state) {
        StreamState.CONNECTING -> "Connecting..."
        StreamState.LIVE -> "Live"
        StreamState.STOPPED -> "Stopped"
        StreamState.ERROR -> "Error"
    }
    Box(
        modifier = Modifier
            .aspectRatio(16f / 9f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleFocus() },
                    onDoubleTap = {
                        playerViewRef.value?.let { pv ->
                            captureSnapshot(activity, pv) { bmp ->
                                bmp?.let {
                                    onSnapshot(
                                        ClueSnapshot(
                                            bitmap = it,
                                            designator = designator,
                                            timestamp = Instant.now()
                                        )
                                    )
                                }
                            }
                        }
                    }
                )
            }
    ) {
        /***

        StreamPlayerView(
            player = player,
            modifier = Modifier.fillMaxSize(),
            onPlayerViewReady = {playerViewRef.value = it}
        )
        ***/
        Text(
            text = "${designator} ${stateText}",
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(4.dp),
            color = Color.White,
        )
        StreamPlayer(
            state = state,
            designator = designator,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )
    }
}

@Composable
fun StreamPlayer(
    state: StreamState,
    designator: String,
    modifier: Modifier = Modifier
) {
    val TAG = "StreamPlayer"
    if (state != StreamState.LIVE) return
    val context = LocalContext.current
    val player = remember {ExoPlayer.Builder(context).build()}
    val url = "http://127.0.0.1:8888/${designator}/index.m3u8"
    CTDebug(TAG, "Starting HLS player with url: '${url}'")

    val mediaItem = MediaItem.Builder()
        .setUri(url)
        .build()
    val mediaSource = HlsMediaSource.Factory(
        DefaultHttpDataSource.Factory()
    ).createMediaSource(mediaItem)

    player.setMediaSource(mediaSource)
    player.prepare()
    player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    player.addListener(object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            CTError(TAG, "Playback error: ${error.errorCodeName}", error)
        }
        override fun onPlaybackStateChanged(state: Int) {
            CTDebug(TAG, "Playback state changed: $state")
        }
    })
    player.playWhenReady = true

    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            PlayerView(it).apply {
                useController = false
                this.player = player
            }
        }
    )
}