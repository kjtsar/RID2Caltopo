package org.ncssar.rid2caltopo.video

import StreamsViewModel
import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.ncssar.rid2caltopo.data.R2CPeer
import org.ncssar.rid2caltopo.data.MediaMTXStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamsScreen(
    viewModel: StreamsViewModel = viewModel(),
    onBack: () -> Unit,
) {
    val TAG: String = "StreamsScreen"

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = MediaMTXStatus.serverStatus,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
            StreamsGrid(viewModel)
        }
    }
}

fun <T> List<T>.padTo(size: Int): List<T?> =
    this + List(size - this.size) { null }

@Composable
private fun StreamsGrid(viewModel: StreamsViewModel) {
    val TAG: String = "StreamsGrid"
    val streams by viewModel.streams.collectAsState()
    val streamEntries = streams.entries.toList()
    val context = LocalContext.current
    val activity = context as Activity
    val focusedPath = viewModel.focusedPath
    val visibleEntries =
        if (focusedPath != null) {
            streamEntries.filter {it.key == focusedPath}
        } else {
            streamEntries
        }

    if (visibleEntries.isEmpty()) {
        CTDebug(TAG, "No streams to show.")
        EmptyStreamsView()
        return
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
                    CTDebug(TAG, "Rendering stream ${path}...")
                    StreamTile(
                        viewModel = viewModel,
                        streamDesignator = path,
                        streamState = info.state,
                        onToggleFocus = {
                            viewModel.toggleFocus(path)
                        },
                        activity = activity,
                        onSnapshot = { snapshot ->
                            viewModel.onSnapshotCaptured(snapshot)
                        }
                    )
                }
            }
        }
    }
}


/***
fun submitClue(snapshot: ClueSnapshot, description: String) {
    val context = LocalContext.current
    val path = SnapshotStore.save(context, snapshot.bitmap)

    val submission = ClueSubmission(
        designator = snapshot.designator,
        timestamp = snapshot.timestamp,
        latitude = snapshot.lat, // CtDroneSpec.lastLat,
        longitude = snapshot.lng, // CtDroneSpec.lastLng,
        altitudeMeters = snapshot.alt, // CtDroneSpec.lastAlt,
        description = description,
        snapshotPath = path
    )

    val request = OneTimeWorkRequestBuilder<SubmitClueWorker>()
        .setInputData(submission.toWorkData())
        .build()

    WorkManager.getInstance(context).enqueue(request)
}
 ***/

@Composable
private fun EmptyStreamsView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val myIpAddress: String = R2CPeer.GetMyIpAddress(false)
        Text("Waiting for drone to attach at rtmp://${myIpAddress}/<droneDesig>")
    }
}
