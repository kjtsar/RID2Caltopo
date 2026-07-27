package org.ncssar.rid2caltopo.video

import OverLimitDroneUiState
import StreamsLayoutMode
import StreamsViewModel
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.ncssar.rid2caltopo.app.MediaMTXService
import org.ncssar.rid2caltopo.airspace.AirspaceCenter
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoNode
import org.ncssar.rid2caltopo.data.ExternalDisplayContentMode
import org.ncssar.rid2caltopo.data.MediaMTXStatus
import org.ncssar.rid2caltopo.data.R2CMqttManager
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.notam.NotamPanel
import org.ncssar.rid2caltopo.notam.NotamStatusChip
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionCenter
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionPanel
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionStatusChip
import org.ncssar.rid2caltopo.ui.CaltopoActionInterface
import org.ncssar.rid2caltopo.ui.CaltopoConnectionState
import org.ncssar.rid2caltopo.ui.ClueSubmissionSheet
import org.ncssar.rid2caltopo.ui.DroneSignalLossAlertCenter
import org.ncssar.rid2caltopo.ui.ResumeProximityAlertButton
import org.ncssar.rid2caltopo.ui.SignalLossAlertButton
import org.ncssar.rid2caltopo.ui.SignalLossAlertDialog
import org.opendroneid.android.bluetooth.WiFiScanner
import androidx.documentfile.provider.DocumentFile

private const val EMPTY_STREAMS_SETTINGS_DESIGNATOR = "__empty_streams_defaults__"
private const val STREAM_PIP_FRAME_PADDING_DP = 24f

internal data class StreamPipInsetSize(
    val width: Float,
    val height: Float
)

internal fun streamPipInsetSize(
    maxWidth: Float,
    maxHeight: Float,
    insetFraction: Float,
    aspectRatio: Float = streamPipFullFrameAspectRatio(maxWidth, maxHeight),
    padding: Float = STREAM_PIP_FRAME_PADDING_DP
): StreamPipInsetSize {
    val safeAspectRatio = if (aspectRatio.isFinite() && aspectRatio > 0f) aspectRatio else 1f
    val maxInsetWidth = (maxWidth - padding).coerceAtLeast(1f)
    val maxInsetHeight = (maxHeight - padding).coerceAtLeast(1f)
    val desiredWidth = maxInsetWidth * insetFraction
    val insetWidth = minOf(desiredWidth, maxInsetHeight * safeAspectRatio)
    return StreamPipInsetSize(width = insetWidth, height = insetWidth / safeAspectRatio)
}

internal fun streamPipFullFrameAspectRatio(width: Float, height: Float): Float =
    if (width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
        width / height
    } else {
        1f
    }

internal fun streamPipHasStreamContent(visibleStreamCount: Int, focusedPath: String?): Boolean =
    visibleStreamCount > 0 || focusedPath != null

internal fun streamPipInsetTapLayoutMode(currentLayoutMode: StreamsLayoutMode): StreamsLayoutMode =
    when (currentLayoutMode) {
        StreamsLayoutMode.Streams -> StreamsLayoutMode.Map
        StreamsLayoutMode.Map -> StreamsLayoutMode.Streams
        StreamsLayoutMode.Both -> StreamsLayoutMode.Both
    }

internal fun shouldShowMapPipInset(
    pipEnabled: Boolean,
    layoutMode: StreamsLayoutMode
): Boolean = pipEnabled && layoutMode == StreamsLayoutMode.Streams

internal fun shouldShowStreamsPipInset(
    pipEnabled: Boolean,
    layoutMode: StreamsLayoutMode
): Boolean = pipEnabled && layoutMode == StreamsLayoutMode.Map

internal data class StreamsFullScreenChrome(
    val showTopBar: Boolean,
    val showExitChip: Boolean
)

internal fun streamsFullScreenChrome(
    fullScreen: Boolean,
    externalContentActive: Boolean
): StreamsFullScreenChrome {
    val active = fullScreen && !externalContentActive
    return StreamsFullScreenChrome(
        showTopBar = !active,
        showExitChip = active
    )
}

internal fun shouldShowEnterFullScreenChip(
    fullScreen: Boolean,
    externalContentActive: Boolean
): Boolean = !fullScreen && !externalContentActive

internal data class FullScreenExitChipLayout(
    val minWidthDp: Float,
    val minHeightDp: Float,
    val endPaddingDp: Float
)

internal fun fullScreenExitChipLayout(): FullScreenExitChipLayout =
    FullScreenExitChipLayout(
        minWidthDp = 96f,
        minHeightDp = 48f,
        endPaddingDp = 84f
    )

internal data class StreamTileFocusPresentation(
    val effectiveFocused: Boolean,
    val showFocusBorder: Boolean
)

internal data class StreamTileChromePresentation(
    val fillContainer: Boolean,
    val showStandaloneTelemetryOverlay: Boolean
)

internal fun streamTileChromePresentation(
    fullScreenContent: Boolean,
    focused: Boolean
): StreamTileChromePresentation =
    StreamTileChromePresentation(
        fillContainer = fullScreenContent && focused,
        showStandaloneTelemetryOverlay = !focused
    )

internal fun streamTileFocusPresentation(
    displayedTileCount: Int,
    explicitlyFocused: Boolean
): StreamTileFocusPresentation {
    val singleDisplayedTile = displayedTileCount == 1
    return StreamTileFocusPresentation(
        effectiveFocused = singleDisplayedTile || explicitlyFocused,
        showFocusBorder = !singleDisplayedTile && explicitlyFocused
    )
}

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
        isServerRunning  -> "\uD83D\uDFE2 In => rtmp://$myIpAddress/<droneDesig>"
        serverExitReason.isNotEmpty() -> "\uD83D\uDD34 Server exited: $serverExitReason"
        else             -> "\uD83D\uDFE1 Starting"
    }
    val streams by viewModel.streams.collectAsStateWithLifecycle()
    val focusedPath by viewModel.focusedPath.collectAsStateWithLifecycle()
    val mapName = viewModel.mapName
    val notamUiState by NotamCenter.uiState.collectAsStateWithLifecycle()
    val airspaceUiState by AirspaceCenter.uiState.collectAsStateWithLifecycle()
    val landRestrictionUiState by LandRestrictionCenter.uiState.collectAsStateWithLifecycle()
    val overLimitDrones by viewModel.overLimitDrones.collectAsStateWithLifecycle()
    val signalLossFlights by DroneSignalLossAlertCenter.flights.collectAsStateWithLifecycle()
    var splitFraction by remember { mutableFloatStateOf(0.5f) }
    val persistedLayoutMode by viewModel.layoutMode.collectAsStateWithLifecycle()
    val streamPipUiState = viewModel.streamPipUiState
    val layoutMode = when (externalContentMode) {
        ExternalDisplayContentMode.StreamsGrid,
        ExternalDisplayContentMode.ObserverMode -> StreamsLayoutMode.Streams
        ExternalDisplayContentMode.MapOnly -> StreamsLayoutMode.Map
        ExternalDisplayContentMode.Split -> StreamsLayoutMode.Both
        null -> persistedLayoutMode
    }
    var showNotamPanel by remember { mutableStateOf(false) }
    var showLandRestrictionPanel by remember { mutableStateOf(false) }
    var showPerformancePanel by remember { mutableStateOf(false) }
    var showCompliancePanel by remember { mutableStateOf(false) }
    var showSignalLossPanel by remember { mutableStateOf(false) }
    var streamsFullScreen by remember { mutableStateOf(false) }
    val fullScreenChrome = streamsFullScreenChrome(
        fullScreen = streamsFullScreen,
        externalContentActive = externalContentMode != null
    )
    ApplyStreamsFullScreenSystemBars(fullScreenChrome.showExitChip)
    val allOverLimitMuted = overLimitDrones.isNotEmpty() && overLimitDrones.all { it.muted }
    val allSignalLossMuted = signalLossFlights.isNotEmpty() && signalLossFlights.all { it.muted }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column {
            if (fullScreenChrome.showTopBar) {
                TopAppBar(
                    modifier = Modifier.pointerInput(handleBack) {
                        detectTapGestures(
                            onDoubleTap = { handleBack() }
                        )
                    },
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NotamStatusChip(
                                state = notamUiState,
                                airspaceState = airspaceUiState,
                                onClick = { showNotamPanel = true },
                                outerPadding = PaddingValues(0.dp)
                            )
                            LandRestrictionStatusChip(
                                state = landRestrictionUiState,
                                onClick = { showLandRestrictionPanel = true },
                                outerPadding = PaddingValues(start = 8.dp)
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
                            StreamsMapStatusButton(
                                mapName = mapName,
                                onClick = onMapStatusTap,
                                modifier = Modifier
                                    .widthIn(max = 220.dp)
                                    .height(36.dp)
                            )
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            )
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
                            if (
                                shouldShowEnterFullScreenChip(
                                    fullScreen = streamsFullScreen,
                                    externalContentActive = externalContentMode != null
                                )
                            ) {
                                LayoutToggleChip(
                                    label = "Enter FS",
                                    selected = false,
                                    onClick = { streamsFullScreen = true }
                                )
                                Spacer(Modifier.width(6.dp))
                            }
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
                            if (externalContentMode == null) {
                                LayoutToggleChip(
                                    label = if (streamPipUiState.enabled) "PiP:On" else "PiP:Off",
                                    selected = streamPipUiState.enabled,
                                    onClick = { viewModel.setStreamPipEnabled(!streamPipUiState.enabled) }
                                )
                                Spacer(Modifier.width(6.dp))
                            }
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
            }

            Box(Modifier.fillMaxSize()) {
                when (layoutMode) {
                    StreamsLayoutMode.Both -> {
                        SplitStreamsAndMap(
                            viewModel = viewModel,
                            allowCapturedVideoPicker = allowModalDialogs,
                            splitFraction = splitFraction,
                            onSplitFractionChange = { splitFraction = it },
                            onMapStatusTap = onMapStatusTap,
                            onStreamsPaneTap = { viewModel.setLayoutMode(StreamsLayoutMode.Streams) },
                            onMapPaneTap = { viewModel.setLayoutMode(StreamsLayoutMode.Map) }
                        )
                    }

                    StreamsLayoutMode.Streams -> {
                        StreamsGrid(
                            viewModel = viewModel,
                            allowCapturedVideoPicker = allowModalDialogs,
                            onMapStatusTap = onMapStatusTap,
                            fullScreenContent = fullScreenChrome.showExitChip,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    StreamsLayoutMode.Map -> {
                        SplitMapPane(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                    }
                }

                val pipEnabled = streamPipUiState.enabled && externalContentMode == null
                if (shouldShowMapPipInset(pipEnabled = pipEnabled, layoutMode = layoutMode)) {
                    val swapToMap = {
                        val nextLayout = streamPipInsetTapLayoutMode(layoutMode)
                        viewModel.setLayoutMode(nextLayout)
                    }
                    StreamPipInsetFrame(
                        editorMode = streamPipUiState.editorMode,
                        insetFraction = streamPipUiState.insetFraction,
                        onTap = swapToMap,
                        onLongPress = { viewModel.toggleStreamPipEditorModeFromLongPress() },
                        onResizeFractionChange = { next -> viewModel.setStreamPipInsetFraction(next) }
                    ) {
                        SplitMapPane(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                            onSingleTapFocus = swapToMap,
                            presentationMode = MapPanePresentationMode.Inset
                        )
                    }
                }

                if (
                    shouldShowStreamsPipInset(
                        pipEnabled = pipEnabled,
                        layoutMode = layoutMode
                    )
                ) {
                    val swapToStreams = {
                        val nextLayout = streamPipInsetTapLayoutMode(layoutMode)
                        viewModel.setLayoutMode(nextLayout)
                    }
                    StreamPipInsetFrame(
                        editorMode = streamPipUiState.editorMode,
                        insetFraction = streamPipUiState.insetFraction,
                        onTap = swapToStreams,
                        onLongPress = { viewModel.toggleStreamPipEditorModeFromLongPress() },
                        onResizeFractionChange = { next -> viewModel.setStreamPipInsetFraction(next) }
                    ) {
                        StreamsGrid(
                            viewModel = viewModel,
                            allowCapturedVideoPicker = false,
                            onMapStatusTap = onMapStatusTap,
                            showTileControls = false,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (fullScreenChrome.showExitChip) {
                    FullScreenExitChip(
                        onClick = { streamsFullScreen = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .zIndex(10f)
                    )
                }

                if (allowModalDialogs) viewModel.pendingClue?.let {
                    ClueSubmissionSheet(
                        pendingClue = it,
                        onTitleChanged = viewModel::updateClueTitle,
                        onDescriptionChanged = viewModel::updateClueDescription,
                        onGimbalAngleChanged = viewModel::updateClueGimbalAngle,
                        onSubmit = viewModel::submitClue,
                        onSubmitLocalMarkerOnly = viewModel::submitLocalMarkerOnly,
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
            airspaceState = airspaceUiState,
            onDismiss = { showNotamPanel = false }
        )
    }
    if (showLandRestrictionPanel) {
        LandRestrictionPanel(
            state = landRestrictionUiState,
            onRefresh = LandRestrictionCenter::requestImmediateRefresh,
            onDismiss = { showLandRestrictionPanel = false }
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
private fun ApplyStreamsFullScreenSystemBars(active: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(active, view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (active && controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun FullScreenExitChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val layout = fullScreenExitChipLayout()
    Box(
        modifier = modifier
            .padding(top = 8.dp, end = layout.endPaddingDp.dp)
            .widthIn(min = layout.minWidthDp.dp)
            .heightIn(min = layout.minHeightDp.dp)
            .border(1.dp, Color.White, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        OutlinedOverlayText(
            text = "Exit FS",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun OutlinedOverlayText(
    text: String,
    style: TextStyle,
) {
    val outlinedStyle = style.copy(fontWeight = FontWeight.Black)
    Box {
        Text(
            text = text,
            color = Color.Black,
            style = outlinedStyle.copy(drawStyle = Stroke(width = 4f, miter = 2f)),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Text(
            text = text,
            color = Color.White,
            style = outlinedStyle,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.CenterStart)
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
    onMapStatusTap: () -> Unit,
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
                        onMapStatusTap = onMapStatusTap,
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
                        onMapStatusTap = onMapStatusTap,
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
private fun StreamPipInsetFrame(
    editorMode: Boolean,
    insetFraction: Float,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onResizeFractionChange: (Float) -> Unit,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val insetSize = streamPipInsetSize(
            maxWidth = maxWidth.value,
            maxHeight = maxHeight.value,
            insetFraction = insetFraction
        )
        val maxInsetWidth = (maxWidth - STREAM_PIP_FRAME_PADDING_DP.dp).coerceAtLeast(1.dp)
        val density = LocalDensity.current
        val latestInsetFraction by rememberUpdatedState(insetFraction)
        val latestOnResizeFractionChange by rememberUpdatedState(onResizeFractionChange)

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .width(insetSize.width.dp)
                .height(insetSize.height.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            content()
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(editorMode) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onLongPress = { onLongPress() }
                        )
                    }
            )
            if (editorMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .width(44.dp)
                        .height(44.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                        .pointerInput(maxInsetWidth, density) {
                            var dragStartFraction = 0f
                            var accumulatedDragFraction = 0f
                            detectDragGestures(
                                onDragStart = {
                                    dragStartFraction = latestInsetFraction
                                    accumulatedDragFraction = 0f
                                },
                                onDrag = { _, dragAmount ->
                                    val deltaPx = -dragAmount.x - dragAmount.y
                                    val denominatorPx = with(density) { maxInsetWidth.toPx() }
                                    if (denominatorPx > 0f) {
                                        accumulatedDragFraction += deltaPx / denominatorPx
                                        latestOnResizeFractionChange(dragStartFraction + accumulatedDragFraction)
                                    }
                                }
                            )
                        }
                )
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

@Composable
private fun StreamsMapStatusButton(
    mapName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        CaltopoActionInterface(
            state = streamsMapConnectionState(mapName),
            onActionClicked = onClick,
            modifier = Modifier.height(36.dp)
        )
    }
}

private fun streamsMapConnectionState(mapName: String?): CaltopoConnectionState {
    val connectedMapName = mapName?.takeIf { it.isNotBlank() }
    return if (connectedMapName == null) {
        CaltopoConnectionState.StandAlone
    } else {
        CaltopoConnectionState.MapSelected(
            CaltopoNode.MapNode(
                id = "",
                title = connectedMapName,
                updated = 0L
            )
        )
    }
}

fun <T> List<T>.padTo(size: Int): List<T?> =
    this + List(size - this.size) { null }

@Composable
private fun StreamsGrid(
    viewModel: StreamsViewModel,
    allowCapturedVideoPicker: Boolean,
    onMapStatusTap: () -> Unit,
    showTileControls: Boolean = true,
    fullScreenContent: Boolean = false,
    modifier: Modifier = Modifier
) {
    val tag = "StreamsGrid"
    val context = LocalContext.current
    val streams by viewModel.streams.collectAsStateWithLifecycle()
    val streamEntries = streams.entries
        .filter { (_, info) -> viewModel.isStreamVisible(info) }
        .toList()
    val mapName = viewModel.mapName
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
                mapName = mapName,
                onMapStatusTap = onMapStatusTap,
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

            if (fullScreenContent && visibleEntries.size == 1) {
                val (path, info) = visibleEntries.first()
                val focusPresentation = streamTileFocusPresentation(
                    displayedTileCount = visibleEntries.size,
                    explicitlyFocused = focusedPath == path
                )
                val chromePresentation = streamTileChromePresentation(
                    fullScreenContent = true,
                    focused = focusPresentation.effectiveFocused
                )
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
                    showTileControls = showTileControls,
                    effectiveFocused = focusPresentation.effectiveFocused,
                    showFocusBorder = focusPresentation.showFocusBorder,
                    fillContainer = chromePresentation.fillContainer,
                    showStandaloneTelemetryOverlay = chromePresentation.showStandaloneTelemetryOverlay,
                )
                return@Box
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
                        val focusPresentation = streamTileFocusPresentation(
                            displayedTileCount = visibleEntries.size,
                            explicitlyFocused = focusedPath == path
                        )
                        val chromePresentation = streamTileChromePresentation(
                            fullScreenContent = false,
                            focused = focusPresentation.effectiveFocused
                        )
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
                                showTileControls = showTileControls,
                                effectiveFocused = focusPresentation.effectiveFocused,
                                showFocusBorder = focusPresentation.showFocusBorder,
                                fillContainer = chromePresentation.fillContainer,
                                showStandaloneTelemetryOverlay = chromePresentation.showStandaloneTelemetryOverlay,
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
    mapName: String?,
    myIpAddress: String = R2CMqttManager.GetMyIpAddress(),
    onMapStatusTap: () -> Unit,
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
                onPlayCapturedVideo?.let { playCapturedVideo ->
                    DropdownMenuItem(
                        text = { Text("Play Captured Video") },
                        onClick = {
                            anomalyMenuExpanded = false
                            playCapturedVideo()
                        }
                    )
                }
                AnomalySettingsMenuContent(
                    viewModel = viewModel,
                    streamDesignator = settingsDesignator,
                    anomalyMode = anomalyConfig.detectorMode(),
                    isLocalPlayback = false,
                    pauseLocalPlaybackOnOpen = false,
                    onShowSettings = { showAnomalySettingsDialog = true },
                    onShowHelp = { showAdHelpDialog = true },
                    onCloseStream = null,
                    onRestartServer = onRestartServer,
                    onDismissMenu = { anomalyMenuExpanded = false },
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
            Spacer(modifier = Modifier.height(12.dp))
            StreamsMapStatusButton(
                mapName = mapName,
                onClick = onMapStatusTap,
                modifier = Modifier
                    .width(220.dp)
                    .height(48.dp)
            )
        }

        AnomalySettingsDialogs(
            viewModel = viewModel,
            streamDesignator = settingsDesignator,
            anomalyConfig = anomalyConfig,
            isLocalPlayback = false,
            showAnomalySettingsDialog = showAnomalySettingsDialog,
            onDismissAnomalySettingsDialog = { showAnomalySettingsDialog = false },
            showAdHelpDialog = showAdHelpDialog,
            onDismissAdHelpDialog = { showAdHelpDialog = false },
        )
    }
}
