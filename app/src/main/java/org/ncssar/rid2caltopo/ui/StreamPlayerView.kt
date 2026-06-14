package org.ncssar.rid2caltopo.ui

import android.view.LayoutInflater
import android.view.TextureView
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.ncssar.rid2caltopo.R

@OptIn(UnstableApi::class)
@Composable
fun StreamPlayerView(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    fillFrame: Boolean = false,
    onPlayerTextureViewReady: (TextureView) -> Unit
) {
    fun reportTextureView(playerView: PlayerView) {
        (playerView.videoSurfaceView as? TextureView)?.let(onPlayerTextureViewReady)
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            (LayoutInflater.from(context).inflate(R.layout.stream_player_view, null) as PlayerView).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                this.player = player
                resizeMode = if (fillFrame) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                reportTextureView(this)
            }
        },
        update = { view ->
            view.player = player
            view.resizeMode = if (fillFrame) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            reportTextureView(view)
        }
    )
}
