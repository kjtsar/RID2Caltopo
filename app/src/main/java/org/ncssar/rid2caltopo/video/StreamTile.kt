package org.ncssar.rid2caltopo.video

import StreamsViewModel
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import org.ncssar.rid2caltopo.data.DesignatorState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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

    val useFfmpeg = viewModel.useFfmpegRender(designator)
    if (useFfmpeg) {
        CTDebug(tag, "StreamPlayer(${designator}) using FFmpeg render path.")
        var attachedTextureView by remember(designator) { mutableStateOf<TextureView?>(null) }
        var attachedSurface by remember(designator) { mutableStateOf<Surface?>(null) }

        AndroidView(
            modifier = modifier,
            factory = { context ->
                TextureView(context).also { textureView ->
                    attachedTextureView = textureView
                    onTextureViewReady(textureView)
                    textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            attachedSurface?.release()
                            attachedSurface = Surface(surfaceTexture)
                            val bound = viewModel.bindFfmpegRenderSurface(
                                designator,
                                attachedSurface!!
                            )
                            CTDebug(tag, "FFmpeg surface bound for $designator: $bound")
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            viewModel.unbindFfmpegRenderSurface(designator)
                            attachedSurface?.release()
                            attachedSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                    }
                }
            },
            update = { textureView ->
                if (attachedTextureView !== textureView) {
                    attachedTextureView = textureView
                    onTextureViewReady(textureView)
                }
            }
        )

        DisposableEffect(designator) {
            onDispose {
                viewModel.unbindFfmpegRenderSurface(designator)
                attachedSurface?.release()
                attachedSurface = null
                attachedTextureView = null
            }
        }
        return
    }

    val player = viewModel.playerFor(designator) ?: return
    CTDebug(tag, "StreamPlayer(${designator}): player:$player")
    var attachedTextureView by remember(player) { mutableStateOf<TextureView?>(null) }

    key(player) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                TextureView(context).also { textureView ->
                    attachedTextureView = textureView
                    player.setVideoTextureView(textureView)
                    onTextureViewReady(textureView)
                }
            },
            update = { textureView ->
                if (attachedTextureView !== textureView) {
                    attachedTextureView = textureView
                    player.setVideoTextureView(textureView)
                }
            }
        )
    }

    DisposableEffect(player) {
        onDispose {
            attachedTextureView?.let { player.clearVideoTextureView(it) }
            attachedTextureView = null
        }
    }
}
