package org.ncssar.rid2caltopo.video

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.ncssar.rid2caltopo.R
import org.ncssar.rid2caltopo.app.MediaMTXService
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.MediaMTXStatus
import org.ncssar.rid2caltopo.data.R2CPeer
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.notam.NotamPanel
import org.ncssar.rid2caltopo.notam.NotamStatusChip
import org.ncssar.rid2caltopo.ui.ClueSubmissionSheet
import org.ncssar.rid2caltopo.ui.ResumeProximityAlertButton
import org.opendroneid.android.bluetooth.WiFiScanner

private fun restartMediaMtxServer(context: android.content.Context) {
    val appContext = context.applicationContext
    val restartIntent = Intent(appContext, MediaMTXService::class.java).apply {
        action = "RESTART_SERVICE"
    }
    appContext.startForegroundService(restartIntent)
    CaltopoClient.ShowToast("Streams server restarted. Connected publishers will reconnect if supported.")
    CTDebug("StreamsPane", "User requested MediaMTXService restart from streams settings.")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamsScreen(
    viewModel: StreamsViewModel = viewModel(),
    onBack: () -> Unit,
) {
    val serverStatus = MediaMTXStatus.serverStatus
    val mapName = viewModel.mapName
    val notamUiState by NotamCenter.uiState.collectAsStateWithLifecycle()
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
    val layoutMode by viewModel.layoutMode.collectAsStateWithLifecycle()
    var showNotamPanel by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NotamStatusChip(
                            state = notamUiState,
                            onClick = { showNotamPanel = true },
                            outerPadding = PaddingValues(0.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = "$serverStatus - $mapStatus",
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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

                viewModel.pendingClue?.let {
                    ClueSubmissionSheet(
                        pendingClue = it,
                        onTitleChanged = viewModel::updateClueTitle,
                        onDescriptionChanged = viewModel::updateClueDescription,
                        onSubmit = viewModel::submitClue,
                        onCancel = viewModel::clearPendingClue,
                    )
                }
            }
        }
    }

    if (showNotamPanel) {
        NotamPanel(
            state = notamUiState,
            onDismiss = { showNotamPanel = false }
        )
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

    Box(modifier = modifier) {
        if (visibleEntries.isEmpty()) {
            CTDebug(tag, "No streams to show.")
            EmptyStreamsView(
                mapStatus = mapStatus,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            if (focusedPath == null && visibleEntries.count() == 1) {
                viewModel.toggleFocus(visibleEntries[0].key)
            }

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
private fun EmptyStreamsView(mapStatus: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val myIpAddress: String = R2CPeer.GetMyIpAddress(false)
            val ssid = WiFiScanner.WiFiSSID(LocalContext.current)
            Text("Stream video to: 'rtmp://$myIpAddress/<droneDesig>' on $ssid network")
            Text(
                text = mapStatus,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}
