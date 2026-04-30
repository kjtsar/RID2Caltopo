package org.ncssar.rid2caltopo.video

import StreamsViewModel
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.video.anomaly.AnomalyAlgorithm
import org.ncssar.rid2caltopo.video.anomaly.AppearanceAnomalyMode
import org.ncssar.rid2caltopo.video.anomaly.AppearanceAnomalySelection
import org.ncssar.rid2caltopo.ui.StreamPlayerView


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
    var showUnmatchDialog by remember { mutableStateOf(false) }
    var anomalyMenuExpanded by remember { mutableStateOf(false) }
    var showAnomalySettingsDialog by remember { mutableStateOf(false) }
    var showSensitivityDialog by remember { mutableStateOf(false) }
    var showScanZoneDialog by remember { mutableStateOf(false) }
    val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()

    // Keep the altitude coordinator active while this tile is on screen.
    DisposableEffect(viewModel) {
        val removeConsumer = viewModel.addAltitudeConsumer()
        onDispose { removeConsumer() }
    }
    val isFocused = (focusedPath == streamDesignator)
    CTDebug(tag, "StreamTile(): isFocused:${isFocused}, designator:${streamDesignator}, focusedPath:$focusedPath")
    val isLocalPlayback = viewModel.isLocalPlayback(streamDesignator)
    val designatorState = viewModel.designatorStateFor(streamDesignator)
    val anomalyConfig = viewModel.anomalyConfigFor(streamDesignator)
    val resolvedAppearanceMode = viewModel.resolvedAppearanceModeFor(streamDesignator)
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

        // ATO / AGL / HDG overlay — shown whenever the stream is live and we have a linked drone.
        if (streamState == StreamState.LIVE && !isLocalPlayback) {
            val displayState = viewModel.droneDisplayStateFor(streamDesignator)
            val atoStr = displayState?.atoFt
                ?.let { "${"%.0f".format(it)}ATO" } ?: "--ATO"
            val aglStr = displayState?.aglFt
                ?.let { "${"%.0f".format(it)}${if (displayState.aglStale) "?" else ""}AGL" } ?: "--AGL"
            val hdgStr = displayState?.headingDeg
                ?.let { "${"%.0f".format(it)}°" } ?: "--°"
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 6.dp, top = 6.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (label in listOf(atoStr, aglStr, hdgStr)) {
                    Text(
                        text = label,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(streamDesignator, designatorState, currentIsFocused, currentDesignatorState) {
                    detectTapGestures(
                        onTap = {
                            CTDebug(tag, "StreamTile{${streamDesignator}) onTap")
                            if (isLocalPlayback) {
                                viewModel.ensureFocus(streamDesignator)
                            } else {
                                onToggleFocus()
                            }
                        },
                        onLongPress = {
                            if (isLocalPlayback) return@detectTapGestures
                            CTDebug(tag, "StreamTile(${streamDesignator}) onLongPress designatorState=${designatorState::class.simpleName}")
                            when (designatorState) {
                                is DesignatorState.Yellow -> showPicker = true
                                is DesignatorState.Green  -> showUnmatchDialog = true
                                else -> {}
                            }
                        },
                        onDoubleTap = {
                            if (isLocalPlayback) return@detectTapGestures
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
        if ((isFocused || isLocalPlayback) && streamState == StreamState.LIVE) {
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
                    if (!isLocalPlayback) {
                        DropdownMenuItem(
                            text = { Text("Restart Streams Server") },
                            onClick = {
                                anomalyMenuExpanded = false
                                onRestartServer()
                            }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .pointerInput(streamDesignator, anomalyConfig, resolvedAppearanceMode) {
                        detectTapGestures(onTap = { showAnomalySettingsDialog = true })
                    },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (anomalyConfig.enabled) "AD On" else "AD Off",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.pointerInput(streamDesignator, anomalyConfig.enabled) {
                        detectTapGestures(onTap = { viewModel.toggleAnomalyEnabled(streamDesignator) })
                    }
                )
                Text(
                    text = "Sens ${anomalyConfig.sensitivityLabel}",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.pointerInput(streamDesignator, anomalyConfig.sensitivity) {
                        detectTapGestures(onTap = { showSensitivityDialog = true })
                    }
                )
                Text(
                    text = "Zone ${anomalyConfig.scanZoneLabel}",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.pointerInput(streamDesignator, anomalyConfig.scanZone) {
                        detectTapGestures(onTap = { showScanZoneDialog = true })
                    }
                )
                Text(
                    text = "Hits ${anomalyConfig.minHits}",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.pointerInput(streamDesignator, anomalyConfig.minHits) {
                        detectTapGestures(onTap = { viewModel.cycleMinHits(streamDesignator) })
                    }
                )
                Text(
                    text = "Stride ${anomalyConfig.frameStride}x",
                    color = Color.White,
                    fontSize = 11.sp
                )
                Text(
                    text = "Detail ${anomalyConfig.pixelStepLabel}",
                    color = Color.White,
                    fontSize = 11.sp
                )
                when (resolvedAppearanceMode) {
                    AppearanceAnomalyMode.Thermal -> {
                        val thermalShortLabel = when (anomalyConfig.thermalPolarity) {
                            org.ncssar.rid2caltopo.video.anomaly.ThermalPolarity.WhiteHot -> "WH"
                            org.ncssar.rid2caltopo.video.anomaly.ThermalPolarity.BlackHot -> "BH"
                        }
                        OutlinedLegendText(
                            text = "Thermal ($thermalShortLabel)",
                            fillColor = Color.Red,
                            fontSize = 11.sp,
                            modifier = Modifier.pointerInput(streamDesignator, anomalyConfig.thermalPolarity) {
                                detectTapGestures(onTap = { viewModel.cycleAnomalyThermalPolarity(streamDesignator) })
                            }
                        )
                    }
                    AppearanceAnomalyMode.Color -> {
                        OutlinedLegendText(
                            text = "Color Outlier",
                            fillColor = Color.Blue,
                            fontSize = 11.sp
                        )
                    }
                }
                if (anomalyConfig.resolvedAlgorithms(viewModel.resolvedAppearanceModeFor(streamDesignator)).contains(AnomalyAlgorithm.Motion)) {
                    if (anomalyConfig.enabled) {
                        OutlinedLegendText(
                            text = "Motion",
                            fillColor = Color.Green,
                            fontSize = 11.sp
                        )
                    } else {
                        Text(
                            text = "Motion Off",
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    }
                } else {
                    Text(
                        text = "Motion Off",
                        color = Color.White,
                        fontSize = 11.sp
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
            if (showScanZoneDialog) {
                var sliderValue by remember(streamDesignator, anomalyConfig.scanZone) {
                    mutableStateOf(anomalyConfig.scanZone.coerceIn(0.5f, 1f))
                }
                AlertDialog(
                    onDismissRequest = { showScanZoneDialog = false },
                    title = { Text("Scan Zone") },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Sets the centered portion of the frame scanned for anomalies. Reduce to exclude wide-angle lens distortion at the frame edges."
                            )
                            Text(text = "Current: ${(sliderValue * 100f).toInt()}%")
                            Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                valueRange = 0.5f..1f
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("50% (center only)")
                                Text("100% (full frame)")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.setScanZone(streamDesignator, sliderValue)
                                showScanZoneDialog = false
                            }
                        ) {
                            Text("Apply")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showScanZoneDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
            if (showAnomalySettingsDialog) {
                var sensitivityValue by remember(streamDesignator, anomalyConfig.sensitivity) {
                    mutableStateOf(anomalyConfig.sensitivity.coerceIn(0f, 1f))
                }
                var scanZoneValue by remember(streamDesignator, anomalyConfig.scanZone) {
                    mutableStateOf(anomalyConfig.scanZone.coerceIn(0.5f, 1f))
                }
                var minHitsValue by remember(streamDesignator, anomalyConfig.minHits) {
                    mutableStateOf(anomalyConfig.minHits.coerceIn(1, 5))
                }
                var frameStrideValue by remember(streamDesignator, anomalyConfig.frameStride) {
                    mutableStateOf(anomalyConfig.frameStride.coerceIn(1, 4))
                }
                var pixelStepValue by remember(streamDesignator, anomalyConfig.pixelStep) {
                    mutableStateOf(anomalyConfig.pixelStep.coerceIn(0, 4))
                }
                AlertDialog(
                    onDismissRequest = { showAnomalySettingsDialog = false },
                    title = { Text("Anomaly Detector") },
                    text = {
                        val settingsScroll = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(settingsScroll),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Detection")
                                TextButton(onClick = { viewModel.toggleAnomalyEnabled(streamDesignator) }) {
                                    Text(if (anomalyConfig.enabled) "On" else "Off")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Appearance")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            viewModel.setAppearanceAnomalySelection(
                                                streamDesignator,
                                                AppearanceAnomalySelection.Thermal
                                            )
                                        }
                                    ) {
                                        Text("Thermal")
                                    }
                                    Button(
                                        onClick = {
                                            viewModel.setAppearanceAnomalySelection(
                                                streamDesignator,
                                                AppearanceAnomalySelection.Color
                                            )
                                        }
                                    ) {
                                        Text("Color")
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Motion")
                                TextButton(onClick = {
                                    viewModel.toggleAnomalyAlgorithm(streamDesignator, AnomalyAlgorithm.Motion)
                                }) {
                                    Text(
                                        if (anomalyConfig.nonAppearanceAlgorithms.contains(AnomalyAlgorithm.Motion)) {
                                            "On"
                                        } else {
                                            "Off"
                                        }
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Thermal Palette")
                                TextButton(onClick = { viewModel.cycleAnomalyThermalPolarity(streamDesignator) }) {
                                    Text(anomalyConfig.thermalPolarity.label)
                                }
                            }
                            Text("Sensitivity ${((sensitivityValue * 100f).toInt())}%")
                            Slider(
                                value = sensitivityValue,
                                onValueChange = { sensitivityValue = it },
                                valueRange = 0f..1f
                            )
                            Text("Scan Zone ${((scanZoneValue * 100f).toInt())}%")
                            Slider(
                                value = scanZoneValue,
                                onValueChange = { scanZoneValue = it },
                                valueRange = 0.5f..1f
                            )
                            Text("Min Hits $minHitsValue")
                            Slider(
                                value = minHitsValue.toFloat(),
                                onValueChange = { minHitsValue = it.toInt().coerceIn(1, 5) },
                                valueRange = 1f..5f,
                                steps = 3
                            )
                            Text("Frame Stride ${frameStrideValue}x")
                            Slider(
                                value = frameStrideValue.toFloat(),
                                onValueChange = { frameStrideValue = it.toInt().coerceIn(1, 4) },
                                valueRange = 1f..4f,
                                steps = 2
                            )
                            Text(
                                if (pixelStepValue <= 0) {
                                    "Detail Auto"
                                } else {
                                    "Detail ${pixelStepValue}px step"
                                }
                            )
                            Slider(
                                value = pixelStepValue.toFloat(),
                                onValueChange = { pixelStepValue = it.toInt().coerceIn(0, 4) },
                                valueRange = 0f..4f,
                                steps = 3
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.setAnomalySensitivity(streamDesignator, sensitivityValue)
                                viewModel.setScanZone(streamDesignator, scanZoneValue)
                                while (viewModel.anomalyConfigFor(streamDesignator).minHits != minHitsValue) {
                                    viewModel.cycleMinHits(streamDesignator)
                                }
                                while (viewModel.anomalyConfigFor(streamDesignator).frameStride != frameStrideValue) {
                                    viewModel.cycleAnomalyFrameStride(streamDesignator)
                                }
                                viewModel.setAnomalyPixelStep(streamDesignator, pixelStepValue)
                                showAnomalySettingsDialog = false
                            }
                        ) {
                            Text("Apply")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAnomalySettingsDialog = false }) {
                            Text("Close")
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
                if (isLocalPlayback) return@DesignatorIndicator
                when (designatorState) {
                    is DesignatorState.Yellow -> showPicker = true
                    is DesignatorState.Green  -> showUnmatchDialog = true
                    else -> {}
                }
            }
        )
        if (showUnmatchDialog && designatorState is DesignatorState.Green) {
            val greenState = designatorState as DesignatorState.Green
            AlertDialog(
                onDismissRequest = { showUnmatchDialog = false },
                title = { Text("Change Telemetry Pairing") },
                text = {
                    Text(
                        "Stream \"$streamDesignator\" is paired with Remote ID:\n" +
                            "${greenState.droneSpecState.remoteId}\n\n" +
                            "Unmatch to remove the pairing, or Remap to choose a different drone."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        CTDebug(tag, "StreamTile($streamDesignator) Unmatch confirmed")
                        greenState.droneSpecState.changeMappedId("")
                        showUnmatchDialog = false
                    }) { Text("Unmatch") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showUnmatchDialog = false }) { Text("Cancel") }
                        TextButton(onClick = {
                            CTDebug(tag, "StreamTile($streamDesignator) Remap: clearing pairing and opening picker")
                            greenState.droneSpecState.changeMappedId("")
                            showUnmatchDialog = false
                            showPicker = true
                        }) { Text("Remap") }
                    }
                }
            )
        }
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
private fun OutlinedLegendText(
    text: String,
    fillColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    val style = TextStyle(
        fontSize = fontSize,
        fontWeight = FontWeight.Black,
    )
    Box(modifier = modifier) {
        Text(
            text = text,
            color = Color.White,
            style = style.copy(drawStyle = Stroke(width = 3f, miter = 2f)),
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Text(
            text = text,
            color = fillColor,
            style = style,
            modifier = Modifier.align(Alignment.CenterStart)
        )
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
        val player = viewModel.getExoPlayerFor(designator)
        if (player != null) {
            StreamPlayerView(player = player, modifier = modifier, onPlayerViewReady = {})
        } else {
            Box(modifier = modifier.background(Color.Black))
        }
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
                        viewModel.unbindFfmpegRenderSurface(designator, attachedSurface)
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
            attachedSurface?.release()
            attachedSurface = null
            attachedTextureView = null
        }
    }
}
