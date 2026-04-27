package org.ncssar.rid2caltopo.video

import OverLimitDroneUiState
import StreamsViewModel
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.ncssar.rid2caltopo.R
import org.ncssar.rid2caltopo.app.MediaMTXService
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.ExternalDisplayContentMode
import org.ncssar.rid2caltopo.data.MediaMTXStatus
import org.ncssar.rid2caltopo.data.R2CMqttManager
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.notam.NotamPanel
import org.ncssar.rid2caltopo.notam.NotamStatusChip
import org.ncssar.rid2caltopo.ui.ClueSubmissionSheet
import org.ncssar.rid2caltopo.ui.ResumeProximityAlertButton
import org.opendroneid.android.bluetooth.WiFiScanner

private fun restartMediaMtxServer(context: android.content.Context) {
    val appContext = context.applicationContext
    MediaMTXService.requestRestart(appContext)
    CaltopoClient.ShowToast("Streams server restarted. Connected publishers will reconnect if supported.")
    CTDebug("StreamsPane", "User requested MediaMTXService restart from streams settings.")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamsScreen(
    viewModel: StreamsViewModel = viewModel(),
    onBack: () -> Unit,
    onMapStatusTap: () -> Unit = {},
    showNavigation: Boolean = true,
    externalContentMode: ExternalDisplayContentMode? = null,
    allowModalDialogs: Boolean = true,
) {
    DisposableEffect(viewModel) {
        val removeConsumer = viewModel.addStreamsUiConsumer()
        onDispose {
            removeConsumer()
        }
    }

    val isServerRunning = MediaMTXStatus.isServerRunning
    val serverExitReason = MediaMTXStatus.serverExitReason
    var myIpAddress by remember { mutableStateOf(R2CMqttManager.GetMyIpAddress()) }
    LaunchedEffect(Unit) {
        while (myIpAddress.isEmpty()) {
            delay(2000)
            myIpAddress = R2CMqttManager.GetMyIpAddress()
        }
    }
    val serverStatus = when {
        isServerRunning  -> "\uD83D\uDFE2 In => rtmp://$myIpAddress/<droneDesig>, Out => rtsp://$myIpAddress/<droneDesig>"
        serverExitReason.isNotEmpty() -> "\uD83D\uDD34 Server exited: $serverExitReason"
        else             -> "\uD83D\uDFE1 Starting"
    }
    val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()
    val mapName = viewModel.mapName
    val notamUiState by NotamCenter.uiState.collectAsStateWithLifecycle()
    val overLimitDrones by viewModel.overLimitDrones.collectAsStateWithLifecycle()
    val mapStatus by remember(mapName) {
        derivedStateOf {
            if (mapName != null) {
                "Connected to $mapName"
            } else {
                "No map connection"
            }
        }
    }

    var splitFraction by remember { mutableFloatStateOf(0.5f) }
    val persistedLayoutMode by viewModel.layoutMode.collectAsStateWithLifecycle()
    val layoutMode = when (externalContentMode) {
        ExternalDisplayContentMode.StreamsGrid,
        ExternalDisplayContentMode.ObserverMode -> StreamsLayoutMode.Streams
        ExternalDisplayContentMode.MapOnly -> StreamsLayoutMode.Map
        ExternalDisplayContentMode.Split -> StreamsLayoutMode.Both
        null -> persistedLayoutMode
    }
    var showNotamPanel by remember { mutableStateOf(false) }
    var showPerformancePanel by remember { mutableStateOf(false) }
    var showCompliancePanel by remember { mutableStateOf(false) }
    val allOverLimitMuted = overLimitDrones.isNotEmpty() && overLimitDrones.all { it.muted }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column {
            TopAppBar(
                modifier = Modifier.pointerInput(onBack) {
                    detectTapGestures(
                        onDoubleTap = { onBack() }
                    )
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NotamStatusChip(
                            state = notamUiState,
                            onClick = { showNotamPanel = true },
                            outerPadding = PaddingValues(0.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = serverStatus,
                            modifier = Modifier
                                .clickable { showPerformancePanel = true }
                                .padding(end = 8.dp),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .pointerInput(onMapStatusTap, onBack) {
                                    detectTapGestures(
                                        onTap = { onMapStatusTap() },
                                        onDoubleTap = { onBack() }
                                    )
                                }
                        ) {
                            Text(
                                text = mapStatus,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = if (showNavigation) {
                    {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                } else {
                    {}
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ComplianceAlertBell(
                            overLimitDrones = overLimitDrones,
                            allOverLimitMuted = allOverLimitMuted,
                            onClick = { showCompliancePanel = true }
                        )
                        ResumeProximityAlertButton()
                        if (layoutMode != StreamsLayoutMode.Both) {
                            LayoutToggleChip(
                                label = "Split",
                                selected = false,
                                onClick = {
                                    viewModel.setLayoutMode(StreamsLayoutMode.Both)
                                    if (splitFraction !in 0.1f..0.9f) {
                                        splitFraction = 0.5f
                                    }
                                }
                            )
                        }
                    }
                }
            )

            Box(Modifier.fillMaxSize()) {
                when (layoutMode) {
                    StreamsLayoutMode.Both -> {
                        SplitStreamsAndMap(
                            viewModel = viewModel,
                            splitFraction = splitFraction,
                            onSplitFractionChange = { splitFraction = it },
                            onStreamsPaneTap = { viewModel.setLayoutMode(StreamsLayoutMode.Streams) },
                            onMapPaneTap = { viewModel.setLayoutMode(StreamsLayoutMode.Map) }
                        )
                    }

                    StreamsLayoutMode.Streams -> {
                        StreamsGrid(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                    }

                    StreamsLayoutMode.Map -> {
                        SplitMapPane(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                    }
                }

                if (allowModalDialogs) viewModel.pendingClue?.let {
                    ClueSubmissionSheet(
                        pendingClue = it,
                        onTitleChanged = viewModel::updateClueTitle,
                        onDescriptionChanged = viewModel::updateClueDescription,
                        onGimbalAngleChanged = viewModel::updateClueGimbalAngle,
                        onSubmit = viewModel::submitClue,
                        onCancel = viewModel::clearPendingClue,
                    )
                }
            }
        }

        ComplianceAlertDialog(
            visible = showCompliancePanel,
            overLimitDrones = overLimitDrones,
            onDismiss = { showCompliancePanel = false },
            onToggleMuted = { mappedId, muted ->
                viewModel.setComplianceAlertMuted(mappedId, muted)
            }
        )
    }

    if (showNotamPanel) {
        NotamPanel(
            state = notamUiState,
            onDismiss = { showNotamPanel = false }
        )
    }
    if (showPerformancePanel) {
        AlertDialog(
            onDismissRequest = { showPerformancePanel = false },
            title = { Text("Performance") },
            text = {
                Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = viewModel.performancePanelText(focusedPath),
                        fontSize = 14.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPerformancePanel = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun ComplianceAlertBell(
    overLimitDrones: List<OverLimitDroneUiState>,
    allOverLimitMuted: Boolean,
    onClick: () -> Unit
) {
    if (overLimitDrones.isEmpty()) return
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (allOverLimitMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
            contentDescription = "Altitude alerts",
            tint = if (allOverLimitMuted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            }
        )
    }
}

@Composable
fun ComplianceAlertDialog(
    visible: Boolean,
    overLimitDrones: List<OverLimitDroneUiState>,
    onDismiss: () -> Unit,
    onToggleMuted: (mappedId: String, muted: Boolean) -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Altitude Alerts") },
        text = {
            if (overLimitDrones.isEmpty()) {
                Text("No drones are currently above 200 ft AGL.")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    overLimitDrones.forEach { drone ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(drone.mappedId)
                                Text(
                                    text = complianceAlertSummary(drone),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp
                                )
                            }
                            IconButton(
                                onClick = {
                                    onToggleMuted(drone.mappedId, !drone.muted)
                                }
                            ) {
                                Icon(
                                    imageVector = if (drone.muted) {
                                        Icons.Default.NotificationsOff
                                    } else {
                                        Icons.Default.Notifications
                                    },
                                    contentDescription = if (drone.muted) {
                                        "Enable alerts for ${drone.mappedId}"
                                    } else {
                                        "Mute alerts for ${drone.mappedId}"
                                    },
                                    tint = if (drone.muted) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

private fun complianceAlertSummary(drone: OverLimitDroneUiState): String {
    return if (drone.usingDemAgl) {
        String.format(
            java.util.Locale.US,
            "%.0f' AGL%s",
            drone.aglFt,
            if (drone.staleDem) " (DEM stale)" else ""
        )
    } else {
        val atoText = drone.atoFt?.let {
            String.format(java.util.Locale.US, "%.0f' ATO", it)
        } ?: String.format(java.util.Locale.US, "%.0f' ATO", drone.aglFt)
        "$atoText (AGL not available)"
    }
}

@Composable
private fun SplitStreamsAndMap(
    viewModel: StreamsViewModel,
    splitFraction: Float,
    onSplitFractionChange: (Float) -> Unit,
    onStreamsPaneTap: () -> Unit,
    onMapPaneTap: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isPortrait = maxHeight > maxWidth
        val dividerThickness = 24.dp
        val dividerLineThickness = 4.dp
        val clamped = splitFraction.coerceIn(0f, 1f)
        val maxHeightPx = maxHeight.value
        val maxWidthPx = maxWidth.value
        val density = LocalDensity.current
        val dividerThicknessDp = dividerThickness
        val dividerHalfDp = dividerThicknessDp / 2
        val dividerLineDp = dividerLineThickness

        if (isPortrait) {
            val dividerOffsetDp = with(density) { (clamped * maxHeightPx).dp - dividerHalfDp }
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(clamped.coerceIn(0f, 1f))
                        .align(Alignment.TopStart)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onStreamsPaneTap() })
                        }
                ) {
                    StreamsGrid(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight((1f - clamped).coerceIn(0f, 1f))
                        .align(Alignment.BottomStart)
                ) {
                    SplitMapPane(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize(),
                        onSingleTapFocus = onMapPaneTap
                    )
                }
                Box(
                    modifier = Modifier
                        .offset(y = dividerOffsetDp)
                        .fillMaxWidth()
                        .height(dividerThicknessDp)
                        .background(Color.Transparent)
                        .pointerInput(Unit) {
                            detectDragGestures { _, dragAmount ->
                                val delta = if (maxHeightPx > 0f) dragAmount.y / maxHeightPx else 0f
                                onSplitFractionChange((splitFraction + delta).coerceIn(0f, 1f))
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .height(dividerLineDp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                    )
                }
            }
        } else {
            val dividerOffsetDp = with(density) { (clamped * maxWidthPx).dp - dividerHalfDp }
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(clamped.coerceIn(0f, 1f))
                        .align(Alignment.CenterStart)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onStreamsPaneTap() })
                        }
                ) {
                    StreamsGrid(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((1f - clamped).coerceIn(0f, 1f))
                        .align(Alignment.CenterEnd)
                ) {
                    SplitMapPane(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize(),
                        onSingleTapFocus = onMapPaneTap
                    )
                }
                Box(
                    modifier = Modifier
                        .offset(x = dividerOffsetDp)
                        .fillMaxHeight()
                        .width(dividerThicknessDp)
                        .background(Color.Transparent)
                        .pointerInput(Unit) {
                            detectDragGestures { _, dragAmount ->
                                val delta = if (maxWidthPx > 0f) dragAmount.x / maxWidthPx else 0f
                                onSplitFractionChange((splitFraction + delta).coerceIn(0f, 1f))
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxHeight()
                            .width(dividerLineDp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                    )
                }
            }
        }
    }
}

@Composable
private fun LayoutToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun <T> List<T>.padTo(size: Int): List<T?> =
    this + List(size - this.size) { null }

@Composable
private fun StreamsGrid(
    viewModel: StreamsViewModel,
    modifier: Modifier = Modifier
) {
    val tag = "StreamsGrid"
    val context = LocalContext.current
    val streams by viewModel.streams.collectAsStateWithLifecycle()
    val streamEntries = streams.entries
        .filter { (_, info) -> viewModel.isStreamVisible(info) }
        .toList()
    val mapName = viewModel.mapName
    val mapStatus by remember(mapName) {
        derivedStateOf {
            if (mapName != null) {
                "Connected to $mapName"
            } else {
                "No map connection"
            }
        }
    }
    val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()
    val visibleEntries =
        if (focusedPath != null) {
            streamEntries.filter { it.key == focusedPath }
        } else {
            streamEntries
        }

    val singleVisibleDesignator = remember(visibleEntries, focusedPath) {
        if (focusedPath == null && visibleEntries.size == 1) visibleEntries[0].key else null
    }

    LaunchedEffect(singleVisibleDesignator) {
        singleVisibleDesignator?.let { designator ->
            viewModel.ensureFocus(designator)
        }
    }

    Box(modifier = modifier) {
        if (visibleEntries.isEmpty()) {
            CTDebug(tag, "No streams to show.")
            EmptyStreamsView(
                mapStatus = mapStatus,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val columns = if (visibleEntries.size <= 2) 1 else 2
            val rows = when (visibleEntries.size) {
                0, 1 -> 1
                2 -> 2
                else -> 2
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val cellHeight = maxHeight / rows

                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = false,
                    contentPadding = PaddingValues(4.dp)
                ) {
                    items(
                        items = visibleEntries,
                        key = { it.key }
                    ) { (path, info) ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(cellHeight)
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            StreamTile(
                                viewModel = viewModel,
                                streamDesignator = path,
                                streamRevision = info.revision,
                                streamState = info.state,
                                streamErrorDetail = info.errorDetail,
                                onCloseStream = {
                                    if (focusedPath != path) {
                                        viewModel.toggleFocus(path)
                                    }
                                    viewModel.dismissFocusedStream()
                                },
                                onRestartServer = {
                                    restartMediaMtxServer(context)
                                },
                                onToggleFocus = {
                                    viewModel.toggleFocus(path)
                                },
                            )
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun EmptyStreamsView(mapStatus: String, myIpAddress: String = R2CMqttManager.GetMyIpAddress(), modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val ssid = WiFiScanner.WiFiSSID(LocalContext.current)
            Text("Stream video to: 'rtmp://$myIpAddress/<droneDesig>' on $ssid network")
            Text(
                text = mapStatus,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}
