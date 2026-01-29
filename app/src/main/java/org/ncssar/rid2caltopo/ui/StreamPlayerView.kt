package org.ncssar.rid2caltopo.ui

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
fun StreamPlayerView(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    onPlayerViewReady: (PlayerView) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                this.player = player
                onPlayerViewReady(this)
            }
        },
        update = { view ->
            view.player = player
        }
    )
}
