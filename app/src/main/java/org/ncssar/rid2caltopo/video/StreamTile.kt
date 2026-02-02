package org.ncssar.rid2caltopo.video

import StreamsViewModel
import android.app.Activity
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
import java.time.Instant
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import org.ncssar.rid2caltopo.data.DesignatorState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.ncssar.rid2caltopo.data.CtDroneSpec

@Composable
fun StreamTile(
    streamDesignator: String,
    viewModel: StreamsViewModel,
    streamState: StreamState,
    activity: Activity,
    onToggleFocus: () -> Unit,
    onSnapshot: (ClueSnapshot) -> Unit
) {
    val tag="StreamTile"
    val playerViewRef = remember {mutableStateOf<PlayerView?>(null)}
    var showPicker by remember { mutableStateOf(false) }
    val isFocused = viewModel.focusedPath == streamDesignator
    val designatorState = remember(
        streamDesignator,
        viewModel.activeDroneSpecs,
        showPicker
    ) {
        viewModel.designatorStateFor(streamDesignator)
    }
    val droneSpec: CtDroneSpec? = if (designatorState is DesignatorState.Green) designatorState.dronespec else null

    var dsTimestamp: String? = remember (droneSpec) {droneSpec?.durationInSecAsString}

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
                        if (isFocused && designatorState is DesignatorState.Green) {
                            CTDebug(tag, "StreamTile{${streamDesignator}) onDoubleTap")
                            playerViewRef.value?.let { pv ->
                                captureSnapshot(activity, pv) { bmp ->
                                    bmp?.let {
                                        onSnapshot(
                                            ClueSnapshot(
                                                bitmap = it,
                                                designator = streamDesignator,
                                                timestamp = Instant.now()
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            }
        ) {
        StreamPlayer(
            state = streamState,
            designator = streamDesignator,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            viewModel = viewModel,
        )
        DesignatorIndicator(
            streamDesignator = streamDesignator,
            designatorState = designatorState,
            streamState = streamState,
            dsTimestamp = dsTimestamp,
            onLongPress = {
                if (designatorState is DesignatorState.Yellow) { showPicker = true }
            }
        )
        if (showPicker && designatorState is DesignatorState.Yellow) {
            DroneSpecPickerDialog(
                droneSpecs = (designatorState as DesignatorState.Yellow).candidates,
                onSelect = { droneSpec ->
                    CTDebug(tag, "DroneSpecPickerDialog() User mapped '${streamDesignator}' to ${droneSpec.remoteId}")
                    droneSpec.setMappedId(streamDesignator)
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
    modifier: Modifier = Modifier
) {
    val tag = "StreamPlayer"
    CTDebug(tag, "StreamPlayer(${designator}) streamState:${state.name}")
    if (state != StreamState.LIVE) return

    val player = viewModel.playerFor(designator) ?: return
    CTDebug(tag, "StreamPlayer(${designator}) player:${player}")
    // val restartKey = viewModel.restartIdFor(designator)
    CTDebug(tag, "StreamPlayer(${designator}): player:$player")
    key(player) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                TextureView(context).also { textureView ->
                    player.setVideoTextureView(textureView)
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