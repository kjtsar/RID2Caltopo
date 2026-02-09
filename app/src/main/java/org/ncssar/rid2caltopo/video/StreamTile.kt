package org.ncssar.rid2caltopo.video

import StreamsViewModel
import android.app.Activity
import android.graphics.Bitmap
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import org.ncssar.rid2caltopo.data.DesignatorState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ncssar.rid2caltopo.data.CaltopoClient


@Composable
fun StreamTile(
    streamDesignator: String,
    viewModel: StreamsViewModel,
    streamState: StreamState,
    onToggleFocus: () -> Unit,
) {
    val tag="StreamTile"
    val textureViewRef = remember {mutableStateOf<TextureView?>(null)}
    var showPicker by remember { mutableStateOf(false) }
    val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()
    val isFocused = (focusedPath == streamDesignator)
    CTDebug(tag, "StreamTile(): isFocused:${isFocused}, designator:${streamDesignator}, focusedPath:$focusedPath")
    val designatorState = viewModel.designatorStateFor(streamDesignator)
    val currentIsFocused by rememberUpdatedState(isFocused)
    val currentDesignatorState by rememberUpdatedState(designatorState)

    Box(
        modifier = Modifier
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = if (isFocused) Color.Yellow else Color.Transparent
            )
            .aspectRatio(16f / 9f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        CTDebug(tag, "StreamTile{${streamDesignator}) onTap")
                        onToggleFocus()
                    },
                    onLongPress = {
                        if (designatorState is DesignatorState.Yellow) {
                            CTDebug(tag, "StreamTile{${streamDesignator}) onLongPress")
                            showPicker = true
                        }
                    },
                    onDoubleTap = {
                        if (!currentIsFocused) {
                            CaltopoClient.ShowToast("Single-tap view to focus before submitting clue.")
                            return@detectTapGestures
                        }
                        if (currentDesignatorState !is DesignatorState.Green) {
                            CaltopoClient.ShowToast("Long-press to pair with a drone before submitting clue.")
                            return@detectTapGestures
                        }

                        val tv = textureViewRef.value
                        if (tv == null) {
                            CTDebug(tag, "TextureView not ready yet")
                            return@detectTapGestures
                        }

                        val bitmap = tv.bitmap
                        if (bitmap == null) {
                            CTDebug(tag, "Failed to capture bitmap from TextureView")
                            return@detectTapGestures
                        }

                        viewModel.onSnapshotCaptured(streamDesignator, bitmap)
                    }
                )
            }
        ) {
        StreamPlayer(
            state = streamState,
            designator = streamDesignator,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            viewModel = viewModel,
            onTextureViewReady = { tv -> textureViewRef.value = tv }
        )
        DesignatorIndicator(
            streamDesignator = streamDesignator,
            designatorState = designatorState,
            streamState = streamState,
            viewModel = viewModel,
            onLongPress = {
                if (designatorState is DesignatorState.Yellow) { showPicker = true }
            }
        )
        if (showPicker && designatorState is DesignatorState.Yellow) {
            DroneSpecPickerDialog(
                droneSpecStates = (designatorState as DesignatorState.Yellow).candidates,
                onSelect = { (selectedStreamDesignator, droneSpecState) ->
                    CTDebug(tag, "DroneSpecPickerDialog() User mapped '${streamDesignator}' to ${selectedStreamDesignator}:${droneSpecState.remoteId}")
                    droneSpecState.changeMappedId(streamDesignator)
                    showPicker = false
                },
                onDismiss = {showPicker = false}
            )
        }
    }
}

@Composable
fun StreamPlayer(
    viewModel: StreamsViewModel,
    state: StreamState,
    designator: String,
    modifier: Modifier = Modifier,
    onTextureViewReady: (TextureView) -> Unit
) {
    val tag = "StreamPlayer"
    CTDebug(tag, "StreamPlayer(${designator}) streamState:${state.name}")
    if (state != StreamState.LIVE) return

    val player = viewModel.playerFor(designator) ?: return
    CTDebug(tag, "StreamPlayer(${designator}): player:$player")
    key(player) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                TextureView(context).also { textureView ->
                    player.setVideoTextureView(textureView)
                    onTextureViewReady(textureView)
                }
            },
            update = { textureView ->
                player.setVideoTextureView(textureView)
            }
        )
    }

    DisposableEffect(player) {
        CTDebug(tag, "StreamPlayer(${designator}): Preparing player - surface ready.")
        player.prepare()
        player.playWhenReady = true
        onDispose {
            player.clearVideoTextureView(null)
        }
    }
}
