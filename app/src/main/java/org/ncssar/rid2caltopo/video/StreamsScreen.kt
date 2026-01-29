package org.ncssar.rid2caltopo.video

import StreamsViewModel
import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import android.content.res.Configuration
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import org.checkerframework.checker.units.qual.UnknownUnits
import androidx.lifecycle.viewmodel.compose.viewModel
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.R2CPeer
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager


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
                title = { Text("Streams Service") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
            StreamsGrid(viewModel)
        }
    }
}

@Composable
private fun StreamsGrid(viewModel: StreamsViewModel) {
    val TAG: String = "StreamsGrid"
    val streams by viewModel.streams.collectAsState()
    val context = LocalContext.current
    val activity = context as Activity
// focusedPath = viewModel.focusedPath
    if (streams.isEmpty()) {
        CTDebug(TAG, "No streams to show.")
        EmptyStreamsView()
        return
    }

    val columns = if (streams.size <= 2) 1 else 2

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp)
    ) {
        items(streams.entries.toList()) { (path, info) ->
            StreamTile(
                designator = path,
                state = info.state,
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
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        val myIpAddress: String = R2CPeer.GetMyIpAddress(false)
        Text("Waiting for... rtmp://${myIpAddress}/<droneDesig> or rtsp://${myIpAddress}/<droneDesig>")
    }
}
