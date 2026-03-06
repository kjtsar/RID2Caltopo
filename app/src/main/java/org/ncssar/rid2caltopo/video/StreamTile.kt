package org.ncssar.rid2caltopo.video

import StreamsViewModel
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.ncssar.rid2caltopo.data.DesignatorState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.video.anomaly.AnomalyAlgorithm


@Composable
fun StreamTile(
    streamDesignator: String,
    streamRevision: Long,
    viewModel: StreamsViewModel,
    streamState: StreamState,
    streamErrorDetail: String?,
    onCloseStream: () -> Unit,
    onRestartServer: () -> Unit,
    onToggleFocus: () -> Unit,
) {
    val tag="StreamTile"
    val textureViewRef = remember {mutableStateOf<TextureView?>(null)}
    var showPicker by remember { mutableStateOf(false) }
    var anomalyMenuExpanded by remember { mutableStateOf(false) }
    var showSensitivityDialog by remember { mutableStateOf(false) }
    val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()
    val isFocused = (focusedPath == streamDesignator)
    CTDebug(tag, "StreamTile(): isFocused:${isFocused}, designator:${streamDesignator}, focusedPath:$focusedPath")
    val designatorState = viewModel.designatorStateFor(streamDesignator)
    val anomalyConfig = viewModel.anomalyConfigFor(streamDesignator)
    val currentIsFocused by rememberUpdatedState(isFocused)
    val currentDesignatorState by rememberUpdatedState(designatorState)

    Box(
        modifier = Modifier
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = if (isFocused) Color.Yellow else Color.Transparent
            )
            .aspectRatio(16f / 9f)
        ) {
        StreamPlayer(
            state = streamState,
            designator = streamDesignator,
            streamRevision = streamRevision,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            viewModel = viewModel,
            onTextureViewReady = { tv -> textureViewRef.value = tv }
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(streamDesignator, designatorState, currentIsFocused, currentDesignatorState) {
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
        )
        if (isFocused && streamState == StreamState.LIVE) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                IconButton(
                    onClick = { anomalyMenuExpanded = true },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Anomaly detection settings"
                    )
                }
                DropdownMenu(
                    expanded = anomalyMenuExpanded,
                    onDismissRequest = { anomalyMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Close Stream") },
                        onClick = {
                            anomalyMenuExpanded = false
                            onCloseStream()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Restart Streams Server") },
                        onClick = {
                            anomalyMenuExpanded = false
                            onRestartServer()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Anomaly Detection: ${if (anomalyConfig.enabled) "On" else "Off"}") },
                        onClick = { viewModel.toggleAnomalyEnabled(streamDesignator) }
                    )
                    DropdownMenuItem(
                        text = {
                            val on = anomalyConfig.algorithms.contains(AnomalyAlgorithm.ColorOutlier)
                            Text("${AnomalyAlgorithm.ColorOutlier.label}: ${if (on) "On" else "Off"}")
                        },
                        onClick = { viewModel.toggleAnomalyAlgorithm(streamDesignator, AnomalyAlgorithm.ColorOutlier) }
                    )
                    DropdownMenuItem(
                        text = {
                            val on = anomalyConfig.algorithms.contains(AnomalyAlgorithm.ThermalHotspot)
                            Text("${AnomalyAlgorithm.ThermalHotspot.label}: ${if (on) "On" else "Off"}")
                        },
                        onClick = { viewModel.toggleAnomalyAlgorithm(streamDesignator, AnomalyAlgorithm.ThermalHotspot) }
                    )
                    DropdownMenuItem(
                        text = {
                            val on = anomalyConfig.algorithms.contains(AnomalyAlgorithm.Motion)
                            Text("${AnomalyAlgorithm.Motion.label}: ${if (on) "On" else "Off"}")
                        },
                        onClick = { viewModel.toggleAnomalyAlgorithm(streamDesignator, AnomalyAlgorithm.Motion) }
                    )
                    DropdownMenuItem(
                        text = { Text("Frame Stride: ${anomalyConfig.frameStride}x") },
                        onClick = { viewModel.cycleAnomalyFrameStride(streamDesignator) }
                    )
                    DropdownMenuItem(
                        text = { Text("Thermal Palette: ${anomalyConfig.thermalPolarity.label}") },
                        onClick = { viewModel.cycleAnomalyThermalPolarity(streamDesignator) }
                    )
                    DropdownMenuItem(
                        text = { Text("Sensitivity: ${anomalyConfig.sensitivityLabel}") },
                        onClick = {
                            anomalyMenuExpanded = false
                            showSensitivityDialog = true
                        }
                    )
                }
            }
            if (showSensitivityDialog) {
                var sliderValue by remember(streamDesignator, anomalyConfig.sensitivity) {
                    mutableStateOf(anomalyConfig.sensitivity.coerceIn(0f, 1f))
                }
                AlertDialog(
                    onDismissRequest = { showSensitivityDialog = false },
                    title = { Text("Detection Sensitivity") },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Lower sensitivity requires a stronger outlier in a smaller region before drawing a box."
                            )
                            Text(text = "Current: ${(sliderValue * 100f).toInt()}%")
                            Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                valueRange = 0f..1f
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Strict")
                                Text("Aggressive")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.setAnomalySensitivity(streamDesignator, sliderValue)
                                showSensitivityDialog = false
                            }
                        ) {
                            Text("Apply")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSensitivityDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
        DesignatorIndicator(
            streamDesignator = streamDesignator,
            streamState = streamState,
            streamErrorDetail = streamErrorDetail,
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
    streamRevision: Long,
    modifier: Modifier = Modifier,
    onTextureViewReady: (TextureView) -> Unit
) {
    val tag = "StreamPlayer"
    val surfaceTag = "StreamTile"
    if (state != StreamState.LIVE) return

    if (!viewModel.useFfmpegRender(designator)) {
        Box(
            modifier = modifier
                .background(Color.Black)
        )
        return
    }

    var attachedTextureView by remember(designator) { mutableStateOf<TextureView?>(null) }
    var attachedSurface by remember(designator) { mutableStateOf<Surface?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).also { textureView ->
                CTDebug(
                    surfaceTag,
                    "FFmpeg TextureView factory for $designator viewId=${System.identityHashCode(textureView)}"
                )
                attachedTextureView = textureView
                onTextureViewReady(textureView)
                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var lastUpdatedLogAtMs = 0L
                    private var updateLogCount = 0
                    private var surfaceFrameCount = 0

                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        CTDebug(
                            surfaceTag,
                            "SurfaceTexture available for $designator viewId=${System.identityHashCode(textureView)} textureId=${System.identityHashCode(surfaceTexture)} size=${width}x${height}"
                        )
                        attachedSurface?.release()
                        attachedSurface = Surface(surfaceTexture)
                        val bound = viewModel.bindFfmpegRenderSurface(
                            designator,
                            attachedSurface!!
                        )
                        if (!bound) {
                            CTDebug(tag, "FFmpeg surface bind deferred for $designator")
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        CTDebug(
                            surfaceTag,
                            "SurfaceTexture size changed for $designator textureId=${System.identityHashCode(surfaceTexture)} size=${width}x${height}"
                        )
                    }

                    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                        CTDebug(
                            surfaceTag,
                            "SurfaceTexture destroyed for $designator textureId=${System.identityHashCode(surfaceTexture)}"
                        )
                        viewModel.unbindFfmpegRenderSurface(designator)
                        attachedSurface?.release()
                        attachedSurface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                        surfaceFrameCount += 1
                        val now = System.currentTimeMillis()
                        if (updateLogCount < 3 || now - lastUpdatedLogAtMs >= 5_000L) {
                            lastUpdatedLogAtMs = now
                            updateLogCount += 1
                            CTDebug(
                                surfaceTag,
                                "SurfaceTexture updated for $designator textureId=${System.identityHashCode(surfaceTexture)} count=$surfaceFrameCount"
                            )
                        }
                    }
                }
            }
        },
        update = { textureView ->
            if (attachedTextureView !== textureView) {
                CTDebug(
                    surfaceTag,
                    "FFmpeg TextureView update rebound for $designator oldViewId=${attachedTextureView?.let { System.identityHashCode(it) }} newViewId=${System.identityHashCode(textureView)}"
                )
                attachedTextureView = textureView
                onTextureViewReady(textureView)
            }
        }
    )

    DisposableEffect(designator) {
        onDispose {
            CTDebug(
                surfaceTag,
                "FFmpeg TextureView dispose for $designator viewId=${attachedTextureView?.let { System.identityHashCode(it) }}"
            )
            viewModel.unbindFfmpegRenderSurface(designator)
            attachedSurface?.release()
            attachedSurface = null
            attachedTextureView = null
        }
    }
}
