package org.ncssar.rid2caltopo.video

import OverLimitDroneUiState
import StreamsViewModel
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
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
import org.ncssar.rid2caltopo.ui.DroneSignalLossAlertCenter
import org.ncssar.rid2caltopo.ui.ResumeProximityAlertButton
import org.ncssar.rid2caltopo.ui.SignalLossAlertButton
import org.ncssar.rid2caltopo.ui.SignalLossAlertDialog
import org.opendroneid.android.bluetooth.WiFiScanner
import androidx.documentfile.provider.DocumentFile
import kotlin.math.roundToInt
import org.ncssar.rid2caltopo.video.anomaly.AnomalyAlgorithm
import org.ncssar.rid2caltopo.video.anomaly.AppearanceAnomalySelection

private const val EMPTY_STREAMS_SETTINGS_DESIGNATOR = "__empty_streams_defaults__"

private class OpenCapturedVideoDocument : ActivityResultContract<Uri?, Uri?>() {
    override fun createIntent(context: android.content.Context, input: Uri?): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            putExtra("android.content.extra.NO_CACHE", true)
            if (input != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
            }
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
    }
}

private fun resolveCapturedVideoDisplayName(
    context: android.content.Context,
    uri: Uri,
): String {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                if (name.isNotEmpty()) return name
            }
        }
    } catch (_: Exception) {
    }
    return DocumentFile.fromSingleUri(context, uri)?.name?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "Captured Video"
}

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
    val currentOnBack = rememberUpdatedState(onBack)
    val releaseStreamsUiConsumer = remember(viewModel) {
        val removeConsumer = viewModel.addStreamsUiConsumer()
        var removed = false
        {
            if (!removed) {
                removed = true
                removeConsumer()
            }
        }
    }
    val handleBack = remember(releaseStreamsUiConsumer) {
        {
            releaseStreamsUiConsumer()
            currentOnBack.value()
        }
    }

    DisposableEffect(releaseStreamsUiConsumer) {
        onDispose {
            releaseStreamsUiConsumer()
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
    val signalLossFlights by DroneSignalLossAlertCenter.flights.collectAsStateWithLifecycle()
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
    var showSignalLossPanel by remember { mutableStateOf(false) }
    val allOverLimitMuted = overLimitDrones.isNotEmpty() && overLimitDrones.all { it.muted }
    val allSignalLossMuted = signalLossFlights.isNotEmpty() && signalLossFlights.all { it.muted }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column {
            TopAppBar(
                modifier = Modifier.pointerInput(handleBack) {
                    detectTapGestures(
                        onDoubleTap = { handleBack() }
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
                                .pointerInput(onMapStatusTap, handleBack) {
                                    detectTapGestures(
                                        onTap = { onMapStatusTap() },
                                        onDoubleTap = { handleBack() }
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
                        IconButton(onClick = handleBack) {
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
                        SignalLossAlertButton(
                            flights = signalLossFlights,
                            allMuted = allSignalLossMuted,
                            onClick = { showSignalLossPanel = true }
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
                            allowCapturedVideoPicker = allowModalDialogs,
                            splitFraction = splitFraction,
                            onSplitFractionChange = { splitFraction = it },
                            onStreamsPaneTap = { viewModel.setLayoutMode(StreamsLayoutMode.Streams) },
                            onMapPaneTap = { viewModel.setLayoutMode(StreamsLayoutMode.Map) }
                        )
                    }

                    StreamsLayoutMode.Streams -> {
                        StreamsGrid(
                            viewModel = viewModel,
                            allowCapturedVideoPicker = allowModalDialogs,
                            modifier = Modifier.fillMaxSize()
                        )
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
        SignalLossAlertDialog(
            visible = showSignalLossPanel,
            flights = signalLossFlights,
            onDismiss = { showSignalLossPanel = false },
            onToggleMuted = { flightKey, muted ->
                DroneSignalLossAlertCenter.setMuted(flightKey, muted)
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onToggleMuted(drone.mappedId, !drone.muted)
                                },
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
    allowCapturedVideoPicker: Boolean,
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
                    StreamsGrid(
                        viewModel = viewModel,
                        allowCapturedVideoPicker = allowCapturedVideoPicker,
                        modifier = Modifier.fillMaxSize()
                    )
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
                    StreamsGrid(
                        viewModel = viewModel,
                        allowCapturedVideoPicker = allowCapturedVideoPicker,
                        modifier = Modifier.fillMaxSize()
                    )
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
    allowCapturedVideoPicker: Boolean,
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
    val onPlayCapturedVideo =
        if (allowCapturedVideoPicker) {
            val capturedVideoLauncher = rememberLauncherForActivityResult(
                contract = OpenCapturedVideoDocument(),
                onResult = { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: SecurityException) {
                    }
                    viewModel.openCapturedVideo(
                        uri = uri,
                        displayName = resolveCapturedVideoDisplayName(context, uri)
                    )
                }
            )
            remember(viewModel, capturedVideoLauncher) {
                { capturedVideoLauncher.launch(viewModel.capturedVideoPickerInitialUri()) }
            }
        } else {
            null
        }
    val pendingReviewExport = viewModel.pendingLocalPlaybackReviewExport()
    val reviewExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri ->
            viewModel.completeLocalPlaybackReviewExport(uri)
        }
    )

    LaunchedEffect(singleVisibleDesignator) {
        singleVisibleDesignator?.let { designator ->
            viewModel.ensureFocus(designator)
        }
    }
    Box(modifier = modifier) {
        if (visibleEntries.isEmpty()) {
            CTDebug(tag, "No streams to show.")
            EmptyStreamsView(
                viewModel = viewModel,
                mapStatus = mapStatus,
                onPlayCapturedVideo = onPlayCapturedVideo,
                onRestartServer = {
                    restartMediaMtxServer(context)
                },
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
                                    viewModel.closeStream(path)
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

    if (pendingReviewExport != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearPendingLocalPlaybackReviewExport() },
            title = { Text("Save Review Sidecar?") },
            text = {
                Text("Save the updated review annotations for ${pendingReviewExport.designator} as ${pendingReviewExport.suggestedFileName}?") 
            },
            confirmButton = {
                TextButton(
                    onClick = { reviewExportLauncher.launch(pendingReviewExport.suggestedFileName) }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearPendingLocalPlaybackReviewExport() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EmptyStreamsView(
    viewModel: StreamsViewModel,
    mapStatus: String,
    myIpAddress: String = R2CMqttManager.GetMyIpAddress(),
    onPlayCapturedVideo: (() -> Unit)?,
    onRestartServer: () -> Unit,
    modifier: Modifier = Modifier
) {
    var anomalyMenuExpanded by remember { mutableStateOf(false) }
    var showAnomalySettingsDialog by remember { mutableStateOf(false) }
    var showAdHelpDialog by remember { mutableStateOf(false) }
    val settingsDesignator = EMPTY_STREAMS_SETTINGS_DESIGNATOR
    val anomalyConfig = viewModel.anomalyConfigFor(settingsDesignator)
    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            IconButton(
                onClick = { anomalyMenuExpanded = true },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Streams settings"
                )
            }
            DropdownMenu(
                expanded = anomalyMenuExpanded,
                onDismissRequest = { anomalyMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (anomalyConfig.enabled) {
                                "Anomaly Detection: On (tap to turn Off)"
                            } else {
                                "Anomaly Detection: Off (tap to turn On)"
                            }
                        )
                    },
                    onClick = {
                        anomalyMenuExpanded = false
                        viewModel.toggleAnomalyEnabled(settingsDesignator)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Anomaly Detector Settings") },
                    onClick = {
                        anomalyMenuExpanded = false
                        showAnomalySettingsDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("AD Help") },
                    onClick = {
                        anomalyMenuExpanded = false
                        showAdHelpDialog = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Restart Streams Server") },
                    onClick = {
                        anomalyMenuExpanded = false
                        onRestartServer()
                    }
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val ssid = WiFiScanner.WiFiSSID(LocalContext.current)
            Text("Stream video to: 'rtmp://$myIpAddress/<droneDesig>' on $ssid network")
            Spacer(modifier = Modifier.height(16.dp))
            if (onPlayCapturedVideo != null) {
                Button(onClick = onPlayCapturedVideo) {
                    Text("Play Captured Video")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = mapStatus,
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (showAnomalySettingsDialog) {
            var sensitivityValue by remember(settingsDesignator, anomalyConfig.sensitivity) {
                mutableStateOf(anomalyConfig.sensitivity.coerceIn(0f, 1f))
            }
            var scanZoneValue by remember(settingsDesignator, anomalyConfig.scanZone) {
                mutableStateOf(anomalyConfig.scanZone.coerceIn(0.5f, 1f))
            }
            var motionEvidenceSensitivityValue by remember(settingsDesignator, anomalyConfig.motionEvidenceSensitivity) {
                mutableStateOf(anomalyConfig.motionEvidenceSensitivity.coerceIn(0f, 1f))
            }
            var minHitsValue by remember(settingsDesignator, anomalyConfig.minHits) {
                mutableStateOf(anomalyConfig.minHits.coerceIn(1, 5))
            }
            var frameStrideValue by remember(settingsDesignator, anomalyConfig.frameStride) {
                mutableStateOf(anomalyConfig.frameStride.coerceIn(1, 4))
            }
            var pixelStepValue by remember(settingsDesignator, anomalyConfig.pixelStep) {
                mutableStateOf(anomalyConfig.pixelStep.coerceIn(0, 4))
            }
            var thermalMinDeltaValue by remember(settingsDesignator, anomalyConfig.thermalMinDelta) {
                mutableStateOf(anomalyConfig.thermalMinDelta.coerceIn(1.0f, 64.0f))
            }
            var smallTargetFractionValue by remember(settingsDesignator, anomalyConfig.smallTargetScreenFraction) {
                mutableStateOf(anomalyConfig.smallTargetScreenFraction.coerceIn(0.0015f, 0.03f))
            }
            AlertDialog(
                onDismissRequest = { showAnomalySettingsDialog = false },
                title = { Text("Anomaly Detector") },
                text = {
                    val settingsScroll = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .verticalScroll(settingsScroll),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Detection")
                            TextButton(onClick = { viewModel.toggleAnomalyEnabled(settingsDesignator) }) {
                                Text(if (anomalyConfig.enabled) "On" else "Off")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Reset to Realtime Defaults")
                            TextButton(onClick = { viewModel.resetAnomalyRealtimeDefaults(settingsDesignator) }) {
                                Text("Reset")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Appearance (${anomalyConfig.appearanceSelection.label})")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        viewModel.setAppearanceAnomalySelection(
                                            settingsDesignator,
                                            AppearanceAnomalySelection.Auto
                                        )
                                    }
                                ) {
                                    Text("Auto")
                                }
                                Button(
                                    onClick = {
                                        viewModel.setAppearanceAnomalySelection(
                                            settingsDesignator,
                                            AppearanceAnomalySelection.Thermal
                                        )
                                    }
                                ) {
                                    Text("Infrared")
                                }
                                Button(
                                    onClick = {
                                        viewModel.setAppearanceAnomalySelection(
                                            settingsDesignator,
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
                                viewModel.toggleAnomalyAlgorithm(settingsDesignator, AnomalyAlgorithm.Motion)
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
                            Text("Saliency")
                            TextButton(onClick = { viewModel.toggleSaliencyEnabled(settingsDesignator) }) {
                                Text(if (anomalyConfig.saliencyEnabled) "On" else "Off")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Show Guide Boxes")
                            TextButton(onClick = { viewModel.toggleShowGuideBoxes(settingsDesignator) }) {
                                Text(if (anomalyConfig.showGuideBoxes) "On" else "Off")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Show Hottest Region")
                            TextButton(onClick = { viewModel.toggleShowHotOverlay(settingsDesignator) }) {
                                Text(if (anomalyConfig.showHotOverlay) "On" else "Off")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Show Candidate Blobs")
                            TextButton(onClick = { viewModel.toggleShowCandidateBlobs(settingsDesignator) }) {
                                Text(if (anomalyConfig.showCandidateBlobs) "On" else "Off")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Troubleshooting Debug")
                            TextButton(onClick = { viewModel.toggleAnomalyTroubleshootingDebug(settingsDesignator) }) {
                                Text(if (anomalyConfig.troubleshootingDebug) "On" else "Off")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Registration")
                            TextButton(onClick = { viewModel.cycleAnomalyRegistrationMode(settingsDesignator) }) {
                                Text(anomalyConfig.registrationMode.label)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Infrared Palette")
                            TextButton(onClick = { viewModel.cycleAnomalyThermalPolarity(settingsDesignator) }) {
                                Text(anomalyConfig.thermalPolarity.label)
                            }
                        }
                        Text("Sensitivity ${((sensitivityValue * 100f).toInt())}%")
                        Slider(
                            value = sensitivityValue,
                            onValueChange = { sensitivityValue = it },
                            valueRange = 0f..1f
                        )
                        Text("Motion Evidence ${((motionEvidenceSensitivityValue * 100f).toInt())}%")
                        Slider(
                            value = motionEvidenceSensitivityValue,
                            onValueChange = { motionEvidenceSensitivityValue = it },
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
                        Text("Thermal Min Delta ${"%.1f".format(thermalMinDeltaValue)}")
                        Slider(
                            value = thermalMinDeltaValue,
                            onValueChange = { thermalMinDeltaValue = it },
                            valueRange = 1f..64f
                        )
                        val smallTargetDenominator =
                            (1.0f / smallTargetFractionValue.coerceIn(0.0015f, 0.03f)).roundToInt()
                        Text("Small Target Scale 1/$smallTargetDenominator screen diagonal")
                        Slider(
                            value = smallTargetFractionValue,
                            onValueChange = { smallTargetFractionValue = it },
                            valueRange = 0.0015f..0.03f
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val guideColor = Color(0xFF80CBC4).copy(alpha = 0.80f)
                                val previewConfig = anomalyConfig.copy(
                                    scanZone = scanZoneValue,
                                    smallTargetScreenFraction = smallTargetFractionValue,
                                )
                                val (scanZoneWidth, scanZoneHeight) = previewConfig.scanZoneSize(
                                    frameWidth = size.width,
                                    frameHeight = size.height,
                                )
                                val scanZoneTopLeft = Offset(
                                    x = (size.width - scanZoneWidth) * 0.5f,
                                    y = (size.height - scanZoneHeight) * 0.5f,
                                )
                                drawRect(
                                    color = guideColor,
                                    topLeft = scanZoneTopLeft,
                                    size = Size(scanZoneWidth, scanZoneHeight),
                                    style = Stroke(width = 2.dp.toPx())
                                )
                                val targetSpanPx = anomalyConfig.copy(
                                    smallTargetScreenFraction = smallTargetFractionValue
                                ).effectiveSmallTargetSpanPx(
                                    frameWidth = size.width.roundToInt(),
                                    frameHeight = size.height.roundToInt(),
                                ).coerceAtMost(size.minDimension * 0.40f)
                                val topLeft = Offset(
                                    x = (size.width - targetSpanPx) * 0.5f,
                                    y = (size.height - targetSpanPx) * 0.5f,
                                )
                                drawRect(
                                    color = guideColor,
                                    topLeft = topLeft,
                                    size = Size(targetSpanPx, targetSpanPx),
                                    style = Stroke(width = 2.dp.toPx())
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.setAnomalySensitivity(settingsDesignator, sensitivityValue)
                            viewModel.setMotionEvidenceSensitivity(settingsDesignator, motionEvidenceSensitivityValue)
                            viewModel.setScanZone(settingsDesignator, scanZoneValue)
                            viewModel.setMinHits(settingsDesignator, minHitsValue)
                            viewModel.setAnomalyFrameStride(settingsDesignator, frameStrideValue)
                            viewModel.setAnomalyPixelStep(settingsDesignator, pixelStepValue)
                            viewModel.setAnomalyThermalMinDelta(settingsDesignator, thermalMinDeltaValue)
                            viewModel.setAnomalySmallTargetScreenFraction(settingsDesignator, smallTargetFractionValue)
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

        if (showAdHelpDialog) {
            val helpScroll = rememberScrollState()
            AlertDialog(
                onDismissRequest = { showAdHelpDialog = false },
                title = { Text("Anomaly Detection Help") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .verticalScroll(helpScroll),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Legend controls")
                        Text("Sens: Detection sensitivity. Lower values are stricter and require a stronger outlier before drawing a box.")
                        Text("Zone: Centered portion of the frame scanned for anomalies. Lower values ignore more of the outer frame.")
                        Text("Hits: Consecutive analyzed-frame hits required in roughly the same motion-stabilized region before a detection is promoted.")
                        Text("Stride: Analyze every Nth frame. Higher stride reduces CPU load but may miss brief motion.")
                        Text("Detail: Pixel sampling step for appearance analysis. Auto chooses a default from frame size; smaller steps inspect more detail at higher cost.")
                        Text("Appearance: Infrared is the recommended default for SAR thermal video. Color Outlier is mainly for visible-light footage or special cases.")
                        Text("ShowHot: Draws a red ring around the hottest region in the frame as a thermal debug aid.")
                        Text("Guide Boxes: Shows cyan outlines for the centered scan zone and the maximum small-target size.")
                        Text("Saliency: Enables the unified saliency detector. Turn it off to match harness runs that omit the saliency algorithm.")
                        Text("Motion: Motion evidence sensitivity. Higher values strengthen the motion detector and also increase the influence of motion support in combined anomaly scoring.")
                        Text("Registration: Chooses the motion-registration backend used to stabilize detections. Affine usually tracks camera motion more accurately; GMV is simpler and may be cheaper.")
                        Text("Infrared (WH/BH): Thermal polarity. WH means brighter pixels are hotter; BH means darker pixels are hotter.")
                        Text("Thermal Min Delta: Minimum infrared contrast before thermal/saliency evidence is considered. Raise it to ignore weaker temperature differences.")
                        Text("Small: Maximum on-screen small-target box size. The cyan rectangle shows the largest blob the anomaly detector should treat as a 'small target' for the squinter. As the camera zooms in, targets larger than this are down-ranked and can disappear.")
                        Text("Motion badge: Indicates whether the motion detector is currently part of the active anomaly stack.")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAdHelpDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}
