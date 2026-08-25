/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */


package org.ncssar.rid2caltopo.app

import StreamsViewModel
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.savedstate.SavedStateRegistryOwner
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.snackbar.Snackbar
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.data.MutualAidProfileManager
import org.ncssar.rid2caltopo.data.MutualAidPackageTransferManager
import org.ncssar.rid2caltopo.data.MutualAidPackageTransferToken
import org.ncssar.rid2caltopo.data.MutualAidToken
import org.ncssar.rid2caltopo.data.OrgConfigManager
import org.ncssar.rid2caltopo.data.OrgConfigToken
import org.ncssar.rid2caltopo.data.R2cRuntimeRegistry
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.ExternalDisplayAlertRouting
import org.ncssar.rid2caltopo.data.ExternalDisplayConfig
import org.ncssar.rid2caltopo.data.ExternalDisplayContentMode
import org.ncssar.rid2caltopo.data.ExternalDisplayMode
import org.ncssar.rid2caltopo.data.ExternalDisplayPrefs
import org.ncssar.rid2caltopo.data.FaaConfigManager
import org.ncssar.rid2caltopo.data.FaaConfigToken
import org.ncssar.rid2caltopo.data.R2CMqttManager
import org.ncssar.rid2caltopo.data.TrackerEnrollmentClient
import org.ncssar.rid2caltopo.data.PeerCoordinator
import org.ncssar.rid2caltopo.data.VideoStreamViewRequest
import org.ncssar.rid2caltopo.data.VideoMediaOffer
import org.ncssar.rid2caltopo.data.RecordingDownloadRequest
import org.ncssar.rid2caltopo.data.ManagedVideoMediaPeer
import org.ncssar.rid2caltopo.data.ManagedVideoStreamAdvertisement
import org.ncssar.rid2caltopo.airspace.AirspaceCenter
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionCenter
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.ui.ActiveScreen
import org.ncssar.rid2caltopo.ui.CaltopoSettingsScreen
import org.ncssar.rid2caltopo.ui.ComplianceAlertHost
import org.ncssar.rid2caltopo.ui.ControllerSignalStrengthAlertHost
import org.ncssar.rid2caltopo.ui.DroneScoutBridgeAlertHost
import org.ncssar.rid2caltopo.ui.DroneSignalLossAlertHost
import org.ncssar.rid2caltopo.ui.DroneSpecConfirmationDialog
import org.ncssar.rid2caltopo.ui.LaunchDisclaimerScreen
import org.ncssar.rid2caltopo.ui.MainScreen
import org.ncssar.rid2caltopo.ui.MutualAidPackageImportDialog
import org.ncssar.rid2caltopo.ui.ProximityAlertCenter
import org.ncssar.rid2caltopo.ui.ProximityAlertHost
import org.ncssar.rid2caltopo.ui.R2CViewModel
import org.ncssar.rid2caltopo.ui.R2CViewModelFactory
import org.ncssar.rid2caltopo.ui.ScannerScreen
import org.ncssar.rid2caltopo.ui.SpokenWarningAlertHost
import org.ncssar.rid2caltopo.ui.SpokenWarningCenter
import org.ncssar.rid2caltopo.ui.SpokenWarningKind
import org.ncssar.rid2caltopo.ui.theme.RID2CaltopoTheme
import org.ncssar.rid2caltopo.video.StreamsScreen
import org.ncssar.rid2caltopo.video.ManagedVideoStreamPresence
import org.ncssar.rid2caltopo.video.ManagedVideoSessionRecordingCatalog
import org.ncssar.rid2caltopo.video.ManagedVideoThumbnailStore
import org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridge
import org.ncssar.rid2caltopo.video.nominalManagedVideoSourceFps
import org.ncssar.rid2caltopo.video.StreamRegistry
import org.opendroneid.android.Constants
import org.opendroneid.android.bluetooth.BluetoothScanner
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val EXTRA_OPEN_STREAMS_QUALIFICATION =
    "org.ncssar.rid2caltopo.extra.OPEN_STREAMS_QUALIFICATION"

internal fun buildLogArchiveEntryName(rawName: String?): String {
    var baseName = rawName?.trim().orEmpty().ifBlank { "log_unknown" }
    while (baseName.lowercase(Locale.US).endsWith(".txt.txt")) {
        baseName = baseName.dropLast(4)
    }
    return if (baseName.lowercase(Locale.US).endsWith(".txt")) baseName else "$baseName.txt"
}

internal fun isDiagnosticBundleFile(rawName: String?, mimeType: String?): Boolean {
    val extension = rawName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase(Locale.US)
    return extension == "txt" || extension == "json" || mimeType == "text/plain"
}

internal fun buildDiagnosticArchiveEntryName(relativePath: String, mimeType: String?): String {
    val normalized = relativePath.trim('/').ifBlank { "log_unknown" }
    val parent = normalized.substringBeforeLast('/', missingDelimiterValue = "")
    val leaf = normalized.substringAfterLast('/')
    val extension = leaf.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)
    val archiveLeaf = if (extension == "json" || mimeType == "application/json") {
        leaf
    } else {
        buildLogArchiveEntryName(leaf)
    }
    return if (parent.isBlank()) archiveLeaf else "$parent/$archiveLeaf"
}

internal fun shouldShowBluetoothDisabledPanel(
    adapterPresent: Boolean,
    bluetoothEnabled: Boolean,
): Boolean = adapterPresent && !bluetoothEnabled

internal fun shouldClearTemporaryLocationOverrideOnCreate(
    hasSavedInstanceState: Boolean,
): Boolean = !hasSavedInstanceState

internal fun shouldShutdownOnActivityDestroy(
    isPrimaryActivity: Boolean,
    isFinishing: Boolean,
    isChangingConfigurations: Boolean,
): Boolean = isPrimaryActivity && isFinishing && !isChangingConfigurations

internal enum class AppBackAction {
    REQUEST_EXIT_CONFIRMATION,
    RETURN_TO_MAIN,
}

internal fun appBackAction(activeScreen: ActiveScreen): AppBackAction =
    if (activeScreen == ActiveScreen.MAIN) {
        AppBackAction.REQUEST_EXIT_CONFIRMATION
    } else {
        AppBackAction.RETURN_TO_MAIN
    }

private enum class AppExitRequestSource(val logValue: String) {
    SYSTEM_BACK("system_back"),
    QUIT_MENU("quit_menu"),
    BLUETOOTH_DISABLED("bluetooth_disabled"),
    DISCLAIMER_DECLINED("disclaimer_declined"),
}

@Composable
private fun ConfirmAppExitDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
        title = { Text("Close RID2Caltopo?") },
        text = {
            Text(
                "Closing the app stops active tracking, Remote ID reception, and video. " +
                    "Keep it open during a flight."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Close app")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Keep app open")
            }
        },
    )
}

@Composable
private fun BluetoothDisabledDialog(
    onOpenBluetoothSettings: () -> Unit,
    onQuit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text("bluetooth disabled") },
        text = {
            Text(
                "Bluetooth is disabled. RID2Caltopo needs Bluetooth enabled to receive " +
                    "Bluetooth Remote ID broadcasts."
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenBluetoothSettings) {
                Text("Open Bluetooth Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onQuit) {
                Text("Quit")
            }
        },
    )
}

@Composable
private fun TrackerReauthenticationDialog(
    onSignIn: () -> Unit,
    onContinueOffline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onContinueOffline,
        title = { Text("Tracker sign-in required") },
        text = {
            Text(
                "Sign in with an authorized organization account to restore Tracker " +
                    "sharing and online organization services. RID, video, maps, and " +
                    "your existing CalTopo configuration remain available."
            )
        },
        confirmButton = {
            TextButton(onClick = onSignIn) {
                Text("Sign in")
            }
        },
        dismissButton = {
            TextButton(onClick = onContinueOffline) {
                Text("Continue offline")
            }
        },
    )
}

internal fun shouldOpenTrackerReauthentication(browserAlreadyOpen: Boolean): Boolean =
    !browserAlreadyOpen

internal fun shouldRetryTrackerReauthenticationAfterBrowserReturn(
    browserWasOpen: Boolean,
    pendingUrl: String?,
): Boolean = browserWasOpen && !pendingUrl.isNullOrBlank()

@Composable
private fun VideoStreamRequestDialog(
    request: VideoStreamViewRequest,
    activeRequesterEmail: String?,
    preflightRouteKind: String?,
    estimatedUplinkBps: Long?,
    preflightFailure: String?,
    onApprove: (VideoQualityChoice) -> Unit,
    onDecline: () -> Unit,
) {
    val sourceDescription = if (request.sourceWidth > 0 && request.sourceHeight > 0) {
        val displayedSourceFps = nominalManagedVideoSourceFps(request.sourceFps)
        buildString {
            append("${request.sourceWidth}×${request.sourceHeight}")
            if (displayedSourceFps > 0.0) {
                append(" at ${String.format(Locale.US, "%.1f", displayedSourceFps)} fps")
            }
            if (request.sourceBitrateBps > 0L) {
                append(
                    ", ${String.format(
                        Locale.US,
                        "%.1f",
                        request.sourceBitrateBps / 1_000_000.0,
                    )} Mbps"
                )
            }
        }
    } else {
        "Source details pending"
    }
    val activeViewerWarning = if (!activeRequesterEmail.isNullOrBlank()) {
        "The app is already streaming to $activeRequesterEmail. " +
            "Starting this request will redirect that viewer.\n\n"
    } else {
        ""
    }
    val qualityChoices = videoQualityChoices(request, estimatedUplinkBps)
    val emergencyFallback = qualityChoices.firstOrNull {
        it.capacity == LinkCapacity.FALLBACK
    }
    var selectedChoice by remember(request.requestId, estimatedUplinkBps) {
        mutableStateOf(qualityChoices.firstOrNull { it.capacity != LinkCapacity.INSUFFICIENT })
    }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text("Video Stream Request") },
        text = {
            Column(modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "From ${request.requesterEmail}\n\n" +
                    "Incident: ${request.incidentName.ifBlank { "Not specified" }}\n" +
                "Drone: ${request.droneDesignator.ifBlank { "Not specified" }}\n" +
                    "Source: $sourceDescription\n\n" +
                    activeViewerWarning +
                    when {
                        !preflightFailure.isNullOrBlank() ->
                            "Link test unavailable: $preflightFailure\n\n" +
                                "Remote video remains off."
                        !preflightRouteKind.isNullOrBlank() &&
                            estimatedUplinkBps != null &&
                            estimatedUplinkBps > 0L ->
                            "${preflightRouteKind.replaceFirstChar { it.uppercase() }} " +
                                "link: ${String.format(
                                    Locale.US,
                                    "%.1f",
                                    estimatedUplinkBps / 1_000_000.0,
                                )} Mbps usable.\n\n" +
                                "Remote video remains off. Choose a complete quality preset, " +
                                "then explicitly select Start."
                        else ->
                            "Measuring the routed browser-to-tablet link. " +
                                "Remote video remains off."
                    }
                )
                if (!preflightRouteKind.isNullOrBlank()) {
                    if (emergencyFallback != null) {
                        Text(
                            "The measurement is below every normal profile. " +
                                "The smallest stream is available as a cautious fallback.",
                            color = LinkCapacity.FALLBACK.color,
                        )
                    }
                    qualityChoices.forEach { choice ->
                        TextButton(onClick = { selectedChoice = choice }) {
                            Text(
                                (if (selectedChoice == choice) "✓ " else "") + choice.label,
                                color = choice.capacity.color,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !preflightRouteKind.isNullOrBlank() &&
                    selectedChoice != null &&
                    selectedChoice?.capacity != LinkCapacity.INSUFFICIENT,
                onClick = { selectedChoice?.let(onApprove) },
            ) {
                Text("Start")
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("Decline") }
        },
    )
}

internal enum class LinkCapacity(val color: Color) {
    ENOUGH(Color(0xFF2E7D32)),
    MARGINAL(Color(0xFFB26A00)),
    FALLBACK(Color(0xFFB26A00)),
    INSUFFICIENT(Color(0xFFC62828)),
}

internal data class VideoQualityChoice(
    val preset: String,
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val bitrateBps: Long,
    val capacity: LinkCapacity,
)

internal fun shouldPresentVideoApproval(
    routeKind: String?,
    failure: String?,
): Boolean = !routeKind.isNullOrBlank() || !failure.isNullOrBlank()

internal fun videoQualityChoices(
    request: VideoStreamViewRequest,
    usableUplinkBps: Long?,
): List<VideoQualityChoice> {
    val sourceWidth = request.sourceWidth.takeIf { it > 0 } ?: 1280
    val sourceHeight = request.sourceHeight.takeIf { it > 0 } ?: 720
    val sourceFps = nominalManagedVideoSourceFps(request.sourceFps).takeIf { it > 0.0 } ?: 30.0
    val sourceLongEdge = maxOf(sourceWidth, sourceHeight)
    data class Preset(val name: String, val longEdge: Int, val fps: Double, val bitrate: Long)
    val presets = listOf(
        Preset("High", 1280, 30.0, 2_500_000L),
        Preset("Balanced", 960, 15.0, 1_200_000L),
        Preset("Low", 640, 10.0, 500_000L),
        Preset("Emergency", 640, 5.0, 200_000L),
    )
    val usable = usableUplinkBps ?: 0L
    val choices = presets.map { preset ->
        val targetLongEdge = minOf(sourceLongEdge, preset.longEdge)
        val scale = targetLongEdge.toDouble() / sourceLongEdge
        val width = ((sourceWidth * scale).toInt().coerceAtLeast(2)) and -2
        val height = ((sourceHeight * scale).toInt().coerceAtLeast(2)) and -2
        val fps = minOf(sourceFps, preset.fps)
        val referencePixels = preset.longEdge.toDouble() * preset.longEdge *
            minOf(sourceWidth, sourceHeight) / sourceLongEdge.toDouble()
        val pixelScale = minOf(1.0, width.toDouble() * height / maxOf(1.0, referencePixels))
        val rateScale = minOf(1.0, fps / preset.fps)
        val minimumBitrate = if (preset.name == "Emergency") 100_000L else 150_000L
        val bitrate = (preset.bitrate * pixelScale * rateScale).toLong()
            .coerceIn(minimumBitrate, preset.bitrate)
        val capacity = when {
            usable * 100 >= bitrate * 135 -> LinkCapacity.ENOUGH
            usable >= bitrate -> LinkCapacity.MARGINAL
            else -> LinkCapacity.INSUFFICIENT
        }
        val assessment = when (capacity) {
            LinkCapacity.ENOUGH -> "enough bandwidth"
            LinkCapacity.MARGINAL -> "marginal"
            LinkCapacity.FALLBACK -> "fallback"
            LinkCapacity.INSUFFICIENT -> "insufficient"
        }
        VideoQualityChoice(
            preset = preset.name,
            label = "${preset.name} • ${width}×${height} • ${String.format(Locale.US, "%.1f", fps)} fps • " +
                "est. ${String.format(Locale.US, "%.1f", bitrate / 1_000_000.0)} Mbps • $assessment",
            width = width,
            height = height,
            fps = fps,
            bitrateBps = bitrate,
            capacity = capacity,
        )
    }
    if (choices.any { it.capacity != LinkCapacity.INSUFFICIENT }) return choices
    val fallback = choices.minWithOrNull(
        compareBy<VideoQualityChoice>(
            { it.bitrateBps },
            { maxOf(it.width, it.height) },
            { it.fps },
        )
    ) ?: return choices
    return choices.map { choice ->
        if (choice != fallback) choice else choice.copy(
            label = choice.label.replace(
                "insufficient",
                "fallback — try lowest quality",
            ),
            capacity = LinkCapacity.FALLBACK,
        )
    }
}

private data class ApprovedVideoSelection(
    val request: VideoStreamViewRequest,
    val quality: VideoQualityChoice,
)

private fun formatManagedVideoBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

data class LogArchiveDayOption(
    val directoryName: String,
    val logFileCount: Int,
    val lastModifiedMs: Long,
    val isToday: Boolean,
)

class R2CActivity :
    AppCompatActivity(),
    R2CMqttManager.PeerListChangedListener,
    PeerCoordinator.VideoStreamRequestListener {
    var locationRequest: LocationRequest? = null
    var locationCallback: LocationCallback? = null
    var mFusedLocationClient: FusedLocationProviderClient? = null
    private val outstandingPermissionsList = ArrayList<String?>()
    private lateinit var localViewModel: R2CViewModel
    private lateinit var streamsViewModel: StreamsViewModel
    private var externalDisplayConfig by mutableStateOf(ExternalDisplayConfig())
    private var externalDisplayConnected by mutableStateOf(false)
    private var externalDisplayPresentation: ExternalDisplayPresentation? = null
    private var displayManager: DisplayManager? = null
    private var bluetoothDisabled by mutableStateOf(false)
    private var launchDisclaimerAccepted by mutableStateOf(false)
    private var trackerReauthenticationBrowserOpen = false
    private var pendingTrackerReauthenticationUrl by mutableStateOf<String?>(null)
    private var pendingVideoStreamRequest by mutableStateOf<VideoStreamViewRequest?>(null)
    private var pendingRecordingDownloadRequest by mutableStateOf<RecordingDownloadRequest?>(null)
    private var pendingVideoPreflightRouteKind by mutableStateOf<String?>(null)
    private var pendingVideoPreflightBps by mutableStateOf<Long?>(null)
    private var pendingVideoPreflightFailure by mutableStateOf<String?>(null)
    private var pendingVideoRequestExpiryJob: Job? = null
    private var pendingRecordingRequestExpiryJob: Job? = null
    private val approvedVideoSelections = linkedMapOf<String, ApprovedVideoSelection>()
    private val remoteControlledVideoRequests = linkedMapOf<String, VideoStreamViewRequest>()
    private var managedVideoMediaPeer: ManagedVideoMediaPeer? = null
    private var managedVideoRecordingDecoderSessionId: Long? = null
    private val managedVideoLiveSourcesByRequestId = linkedMapOf<String, String>()
    private var activeRemoteVideoRequest by mutableStateOf<VideoStreamViewRequest?>(null)
    private var activeRemoteVideoSelection: ApprovedVideoSelection? = null
    private var activeRemoteVideoOfferSdp: String? = null
    private var activeRemoteVideoMetrics by mutableStateOf<ManagedVideoMediaPeer.Metrics?>(null)
    private var activeRemoteVideoFailure by mutableStateOf<String?>(null)
    private var activeRemoteVideoMicrophoneEnabled by mutableStateOf(false)
    private var activeRemoteVideoMicrophoneError by mutableStateOf<String?>(null)
    private var managedVideoSourceRecoveryJob: Job? = null
    private var pendingManagedVideoMicrophoneEnable = false
    private var bluetoothStateReceiverRegistered = false
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                setBluetoothDisabledPanelVisible(true, "state changed: $state")
            } else if (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_TURNING_ON) {
                setBluetoothDisabledPanelVisible(false, "state changed: $state")
            } else {
                refreshBluetoothDisabledState("state changed: $state")
            }
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            runOnUiThread { refreshExternalDisplay() }
        }

        override fun onDisplayRemoved(displayId: Int) {
            runOnUiThread { refreshExternalDisplay() }
        }

        override fun onDisplayChanged(displayId: Int) {
            runOnUiThread { refreshExternalDisplay() }
        }
    }

    private fun checkPermission(permission: String) {
        if (ActivityCompat.checkSelfPermission(
                this,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            CTDebug(
                TAG,
                String.format(Locale.US, "checkPermission(): Requesting '%s'.", permission)
            )
            outstandingPermissionsList.add(permission)
        } else {
            CTDebug(
                TAG, String.format(
                    Locale.US, "checkPermission(): '%s' granted.",
                    permission
                )
            )
        }
    }

    override fun onPeerListChanged(peers: List<R2CMqttManager.PeerState>) {
        // Peer list changes still matter to the coordination layer, but the peer-specific
        // main-screen UI has been removed. Keep the listener registered so future diagnostics
        // can still hook in without changing runtime wiring.
    }

    suspend fun listAvailableLogArchiveDays(): List<LogArchiveDayOption> = withContext(Dispatchers.IO) {
        val archiveDir = CaltopoClient.GetArchiveDir() ?: return@withContext emptyList()
        val todayDirName = todayArchiveDirectoryName()
        archiveDir.listFiles()
            .asSequence()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val dirName = dir.name ?: return@mapNotNull null
                val logFileCount = dir.listFiles().count { it.type == "text/plain" }
                if (logFileCount <= 0) return@mapNotNull null
                LogArchiveDayOption(
                    directoryName = dirName,
                    logFileCount = logFileCount,
                    lastModifiedMs = dir.lastModified(),
                    isToday = dirName == todayDirName
                )
            }
            .sortedWith(
                compareByDescending<LogArchiveDayOption> { it.isToday }
                    .thenByDescending { it.lastModifiedMs }
                    .thenByDescending { it.directoryName }
            )
            .toList()
    }

    suspend fun listArchiveCleanupDirectories(): List<ArchiveCleanupDirectoryOption> = withContext(Dispatchers.IO) {
        val archiveDir = CaltopoClient.GetArchiveDir() ?: return@withContext emptyList()
        val nowMs = System.currentTimeMillis()
        val todayDirName = todayArchiveDirectoryName()
        archiveDir.listFiles()
            .asSequence()
            .filter { it.isDirectory && isDatedArchiveDirectoryName(it.name) }
            .mapNotNull { dir ->
                val dirName = dir.name ?: return@mapNotNull null
                buildArchiveCleanupOption(
                    directoryName = dirName,
                    lastModifiedMs = dir.lastModified(),
                    entries = dir.listFiles().map(::documentFileToArchiveEntry),
                    nowMs = nowMs,
                    todayName = todayDirName,
                )
            }
            .sortedWith(
                compareByDescending<ArchiveCleanupDirectoryOption> { it.ageMs }
                    .thenBy { it.directoryName }
            )
            .toList()
    }

    suspend fun deleteArchiveCleanupDirectories(directoryNames: List<String>): ArchiveCleanupDeleteResult = withContext(Dispatchers.IO) {
        val archiveDir = CaltopoClient.GetArchiveDir() ?: run {
            CTError(TAG, "deleteArchiveCleanupDirectories(): archive dir unavailable")
            return@withContext ArchiveCleanupDeleteResult(0, directoryNames.distinct())
        }
        val todayDirName = todayArchiveDirectoryName()
        var deletedCount = 0
        val failedNames = mutableListOf<String>()
        directoryNames
            .distinct()
            .filter { it != todayDirName && isDatedArchiveDirectoryName(it) }
            .forEach { dirName ->
                val dir = archiveDir.findFile(dirName)
                if (dir?.isDirectory == true && dir.delete()) {
                    deletedCount++
                } else {
                    failedNames.add(dirName)
                }
            }
        ArchiveCleanupDeleteResult(deletedCount, failedNames)
    }

    /**
     * Zip text log files from one or more archive subdirectories into a single archive and fire
     * a share intent so the user can email the bundle.
     */
    suspend fun zipAndEmailSelectedLogs(context: Context, selectedDirectoryNames: List<String>) {
        val zipResult = withContext(Dispatchers.IO) {
            val archiveDir = CaltopoClient.GetArchiveDir() ?: run {
                CTError(TAG, "zipAndEmailSelectedLogs(): archive dir unavailable")
                return@withContext null
            }
            if (selectedDirectoryNames.isEmpty()) {
                CTError(TAG, "zipAndEmailSelectedLogs(): no archive days selected")
                return@withContext null
            }

            val selectedDirs = selectedDirectoryNames.distinct().mapNotNull { dirName ->
                archiveDir.findFile(dirName)?.takeIf { it.isDirectory }
            }
            data class DiagnosticDocument(
                val directoryName: String,
                val relativePath: String,
                val document: DocumentFile,
            )
            fun collectDiagnosticDocuments(
                directory: DocumentFile,
                directoryName: String,
                relativePrefix: String = "",
            ): List<DiagnosticDocument> = directory.listFiles().flatMap { child ->
                val name = child.name ?: return@flatMap emptyList()
                val relativePath = if (relativePrefix.isBlank()) name else "$relativePrefix/$name"
                when {
                    child.isDirectory -> collectDiagnosticDocuments(child, directoryName, relativePath)
                    isDiagnosticBundleFile(name, child.type) -> listOf(
                        DiagnosticDocument(directoryName, relativePath, child)
                    )
                    else -> emptyList()
                }
            }
            val diagnosticFiles = selectedDirs.flatMap { dir ->
                collectDiagnosticDocuments(dir, dir.name ?: "tracks")
            }
            val logFileCount = diagnosticFiles.count {
                !it.relativePath.lowercase(Locale.US).endsWith(".json")
            }
            val trackFileCount = diagnosticFiles.count {
                it.relativePath.lowercase(Locale.US).endsWith(".json")
            }
            if (logFileCount == 0) {
                CTError(TAG, "zipAndEmailSelectedLogs(): no log files found in selected dirs")
                return@withContext null
            }

            val dateTag = SimpleDateFormat("ddMMMyyyy", Locale.US).format(Date())
            val zipFile = File(context.cacheDir, "R2C_Logs_$dateTag.zip")

            try {
                ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                    val resolver = context.contentResolver
                    for (diagnostic in diagnosticFiles) {
                        val entryName = "${diagnostic.directoryName}/${buildDiagnosticArchiveEntryName(diagnostic.relativePath, diagnostic.document.type)}"
                        resolver.openInputStream(diagnostic.document.uri)?.use { inputStream ->
                            val entry = ZipEntry(entryName)
                            val lastModified = diagnostic.document.lastModified()
                            if (lastModified > 0L) {
                                entry.time = lastModified
                            }
                            zos.putNextEntry(entry)
                            inputStream.copyTo(zos)
                            zos.closeEntry()
                        }
                    }
                    File(context.filesDir, ANR_TRACE_DIRECTORY)
                        .listFiles()
                        .orEmpty()
                        .filter { it.isFile }
                        .forEach { traceFile ->
                            val entry = ZipEntry("$ANR_TRACE_DIRECTORY/${traceFile.name}")
                            entry.time = traceFile.lastModified()
                            zos.putNextEntry(entry)
                            traceFile.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                }
                Triple(zipFile, selectedDirs.size, Pair(logFileCount, trackFileCount))
            } catch (e: Exception) {
                CTError(TAG, "zipAndEmailSelectedLogs(): failed to create/share zip", e)
                null
            }
        } ?: return

        val (zipFile, selectedDirCount, fileCounts) = zipResult
        val (logFileCount, trackFileCount) = fileCounts
        val dateTag = SimpleDateFormat("ddMMMyyyy", Locale.US).format(Date())
        val sharedUri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", zipFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("kjtsar@kjt.us"))
            putExtra(
                Intent.EXTRA_SUBJECT,
                "RID2Caltopo Diagnostics $dateTag (${selectedDirCount} day${if (selectedDirCount == 1) "" else "s"}, ${logFileCount} log${if (logFileCount == 1) "" else "s"}, ${trackFileCount} track${if (trackFileCount == 1) "" else "s"})"
            )
            putExtra(Intent.EXTRA_STREAM, sharedUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, "Send Logs via..."))
    }

    /** Handle organization/configuration and tracker-enrollment links from the OS. */
    private fun handleR2cIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme == "r2creauth") {
            trackerReauthenticationBrowserOpen = false
            pendingTrackerReauthenticationUrl = null
            // Consume the callback before acting on it. Keeping the VIEW intent attached to
            // the activity can replay it after a configuration change or activity restart.
            setIntent(Intent(this, R2CActivity::class.java).setAction(Intent.ACTION_MAIN))
            when (uri.host) {
                "complete" -> {
                    showToast("Tracker reauthentication succeeded. Configuration was preserved.")
                    TrackerEnrollmentClient.retryManagedConfigurationBootstrap(this)
                    R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
                        .resumeAfterReauthentication()
                }
                "erase" -> {
                    CaltopoClient.RequireManagedReauthentication()
                    showToast("Managed RID map, Tracker, and CalTopo credentials were erased.")
                }
            }
            return
        }
        TrackerEnrollmentClient.normalizedEnrollmentUrl(uri.toString())?.let { enrollmentUrl ->
            CTDebug(TAG, "handleR2cIntent(): redeeming tracker enrollment app link")
            lifecycleScope.launch {
                runCatching {
                    TrackerEnrollmentClient.redeem(this@R2CActivity, enrollmentUrl).also {
                        TrackerEnrollmentClient.apply(it)
                    }
                }.onSuccess { result ->
                    showToast("Organization '${result.organization}' imported; tracker enrollment installed.")
                    NotamCenter.requestImmediateRefresh()
                    AirspaceCenter.requestImmediateRefresh()
                    CaltopoClient.CheckUnreportedFiles()
                }.onFailure { error ->
                    showToast(error.message ?: "Tracker enrollment failed.")
                    CTError(
                        TAG,
                        "handleR2cIntent(): tracker enrollment failed",
                        error as? Exception ?: RuntimeException(error)
                    )
                }
            }
            return
        }
        when (uri.scheme) {
            OrgConfigToken.QR_SCHEME -> {
                val token = OrgConfigToken.MAGIC_PREFIX +
                    uri.toString().removePrefix("${OrgConfigToken.QR_SCHEME}://")
                CTDebug(TAG, "handleR2cIntent(): joining R2C2 org from scanned QR")
                OrgConfigManager.joinFromToken(this, token) { success, message ->
                    Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                    if (success) {
                        NotamCenter.requestImmediateRefresh()
                        AirspaceCenter.requestImmediateRefresh()
                    }
                }
            }
            "r2cma1" -> {
                val token = MutualAidToken.MAGIC_PREFIX + uri.toString().removePrefix("r2cma1://")
                CTDebug(TAG, "handleR2cIntent(): joining mutual aid from scanned QR")
                MutualAidProfileManager.joinFromToken(this, token) { _, message ->
                    showToast(message)
                }
            }
            "r2cmapkg1" -> {
                val token = MutualAidPackageTransferToken.MAGIC_PREFIX + uri.toString().removePrefix("r2cmapkg1://")
                CTDebug(TAG, "handleR2cIntent(): importing mutual aid package from scanned QR")
                MutualAidPackageTransferManager.importFromToken(this, token)
            }
            FaaConfigToken.QR_SCHEME -> {
                val token = FaaConfigToken.fromQrUri(uri.toString()) ?: return
                CTDebug(TAG, "handleR2cIntent(): importing FAA config from scanned QR")
                FaaConfigManager.importToken(this, token) { _, message ->
                    showToast(message)
                    NotamCenter.requestImmediateRefresh()
                }
            }
        }
    }

    private fun handleStreamsQualificationIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG ||
            intent?.getBooleanExtra(EXTRA_OPEN_STREAMS_QUALIFICATION, false) != true
        ) {
            return
        }
        CTDebug(TAG, "Opening Streams screen for connected-stream qualification")
        localViewModel.showStreams()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (launchDisclaimerAccepted) {
            handleR2cIntent(intent)
            handleStreamsQualificationIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        val retryTrackerReauthentication = shouldRetryTrackerReauthenticationAfterBrowserReturn(
            browserWasOpen = trackerReauthenticationBrowserOpen,
            pendingUrl = pendingTrackerReauthenticationUrl,
        )
        trackerReauthenticationBrowserOpen = false
        if (retryTrackerReauthentication) {
            pendingTrackerReauthenticationUrl = null
            CTDebug(
                TAG,
                "Returned from Tracker sign-in browser without an app callback; retrying Tracker access",
            )
            TrackerEnrollmentClient.retryManagedConfigurationBootstrap(this)
            R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
                .resumeAfterReauthentication()
        }
        reloadExternalDisplayConfig()
        refreshBluetoothDisabledState("resume")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Developer location simulation is deliberately process/task scoped.
        // Android can retain this process after the task is dismissed, so a
        // static-only override otherwise leaks into the next apparent launch.
        if (shouldClearTemporaryLocationOverrideOnCreate(savedInstanceState != null)) {
            CaltopoMap.SetMyLocationOverride(null)
        }
        setVolumeControlStream(AudioManager.STREAM_ALARM)
        CaltopoClient.MarkAppActive()
        CTDebug(TAG, "onCreate().")
        if (AppActivity != null) {
            CTDebug(TAG, "onCreate() with an existing activity.")
            if (AppActivity !== this) {
                RestartingFlag = true
                /* prevent ScanningService's PendingIntent tap from starting a new instance. */
                CTDebug(TAG, "onCreate() restarting with new activity.")
            }
        }
        AppActivity = this
        R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator.setPeerListChangedListener(this)
        R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
            .setVideoStreamRequestListener(this)
        localViewModel = ViewModelProvider(
            this,
            R2CViewModelFactory(
                ScanningService.ScannerUptime
            ))[R2CViewModel::class.java]
        streamsViewModel = ViewModelProvider(this)[StreamsViewModel::class.java]
        CaltopoClient.AddDroneSpecsChangedListener(localViewModel)
        CaltopoClient.AddDroneConfirmationCandidateListener(localViewModel)
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, null)
        reloadExternalDisplayConfig()
        registerBluetoothStateReceiver()
        refreshBluetoothDisabledState("startup")

        setContent {
            RID2CaltopoTheme content@ {
                var pendingAppExitSource by remember {
                    mutableStateOf<AppExitRequestSource?>(null)
                }
                val requestAppExit: (AppExitRequestSource) -> Unit = { source ->
                    if (pendingAppExitSource == null) {
                        pendingAppExitSource = source
                        CaltopoClient.CTEvent(
                            TAG,
                            "QuitConfirmationRequested source=${source.logValue}",
                            null,
                        )
                    }
                }
                val cancelAppExit = {
                    val source = pendingAppExitSource
                    pendingAppExitSource = null
                    CaltopoClient.CTEvent(
                        TAG,
                        "QuitCancelled source=${source?.logValue ?: "unknown"}",
                        null,
                    )
                }
                val confirmAppExit = {
                    val source = pendingAppExitSource
                    pendingAppExitSource = null
                    CaltopoClient.CTEvent(
                        TAG,
                        "QuitConfirmed source=${source?.logValue ?: "unknown"}",
                        null,
                    )
                    CaltopoClient.QuitApplication()
                }
                if (!launchDisclaimerAccepted) {
                    LaunchDisclaimerScreen(
                        onAgree = ::acceptLaunchDisclaimer,
                        onDisagree = {
                            requestAppExit(AppExitRequestSource.DISCLAIMER_DECLINED)
                        },
                    )
                    if (pendingAppExitSource != null) {
                        ConfirmAppExitDialog(
                            onCancel = cancelAppExit,
                            onConfirm = confirmAppExit,
                        )
                    }
                    return@content
                }
                val localContext = LocalContext.current
                val activeScreen by localViewModel
                    .activeScreen
                    .collectAsState()
                BackHandler(enabled = pendingAppExitSource == null) {
                    when (appBackAction(activeScreen)) {
                        AppBackAction.REQUEST_EXIT_CONFIRMATION ->
                            requestAppExit(AppExitRequestSource.SYSTEM_BACK)
                        AppBackAction.RETURN_TO_MAIN -> localViewModel.showMain()
                    }
                }
                var openDeveloperToolsWhenMainOpens by remember { mutableStateOf(false) }
                val pendingDroneConfirmation by localViewModel
                    .pendingDroneConfirmation
                    .collectAsState()
                val maPackageImportState by MutualAidPackageTransferManager.importState.collectAsState()
                val confirmationToneGenerator = remember(pendingDroneConfirmation?.remoteId) {
                    try {
                        ToneGenerator(AudioManager.STREAM_ALARM, CaltopoClient.GetToneGeneratorAlarmVolumePercent())
                    } catch (_: Exception) {
                        null
                    }
                }
                DisposableEffect(confirmationToneGenerator) {
                    onDispose { confirmationToneGenerator?.release() }
                }
                LaunchedEffect(pendingDroneConfirmation?.remoteId) {
                    if (pendingDroneConfirmation != null) {
                        confirmationToneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 300)
                    }
                }
                LaunchedEffect(Unit) {
                    while (isActive) {
                        val streams = StreamRegistry.streams.value
                        maintainManagedVideoSource(streams)
                        val peerCoordinator = R2cRuntimeRegistry
                            .getDefaultRuntime()
                            .peerCoordinator
                        val forcePreviewRefresh = activeRemoteVideoRequest == null &&
                            peerCoordinator.shouldRefreshManagedVideoThumbnails()
                        val previewDesignators = if (forcePreviewRefresh) {
                            streams.values
                                .asSequence()
                                .filter {
                                    it.state == org.ncssar.rid2caltopo.video.StreamState.LIVE &&
                                        !it.isLocalPlayback
                                }
                                .sortedBy { it.designator.lowercase() }
                                .map { it.designator }
                                .take(4)
                                .toSet()
                        } else {
                            emptySet()
                        }
                        streamsViewModel.setManagedVideoPreviewSources(previewDesignators)
                        val recordings = ManagedVideoSessionRecordingCatalog.snapshot(
                            applicationContext
                        )
                        var advertisements = ManagedVideoStreamPresence.snapshot(
                            streams,
                            streamsViewModel::managedVideoSourceInfo,
                            streamsViewModel::hasRecentManagedVideoFrame,
                            recordings,
                            ManagedVideoThumbnailStore::get,
                        )
                        if (activeRemoteVideoRequest == null) {
                            refreshManagedVideoThumbnails(
                                advertisements,
                                force = forcePreviewRefresh,
                            )
                            advertisements = ManagedVideoStreamPresence.snapshot(
                                streams,
                                streamsViewModel::managedVideoSourceInfo,
                                streamsViewModel::hasRecentManagedVideoFrame,
                                recordings,
                                ManagedVideoThumbnailStore::get,
                            )
                        }
                        peerCoordinator.updateManagedVideoStreams(
                            CaltopoClient.GetIncident(),
                            advertisements,
                        )
                        delay(5_000L)
                    }
                }
                when (activeScreen) {
                    ActiveScreen.MAIN -> {
                        MainScreen(
                            localViewModel = localViewModel,
                            streamsViewModel = streamsViewModel,
                            availableLogArchiveDaysProvider = {
                                listAvailableLogArchiveDays()
                            },
                            onEmailLog = { selectedDirectoryNames ->
                                zipAndEmailSelectedLogs(localContext, selectedDirectoryNames)
                            },
                            availableArchiveCleanupDirectoriesProvider = {
                                listArchiveCleanupDirectories()
                            },
                            onDeleteArchiveDirectories = { selectedDirectoryNames ->
                                deleteArchiveCleanupDirectories(selectedDirectoryNames)
                            },
                            externalDisplayConnected = externalDisplayConnected,
                            externalDisplayContentMode = externalDisplayConfig.contentMode,
                            onSetExternalDisplayContent = ::setExternalDisplayContentMode,
                            openDeveloperToolsOnStart = openDeveloperToolsWhenMainOpens,
                            onDeveloperToolsOpened = {
                                openDeveloperToolsWhenMainOpens = false
                            },
                            onRequestExit = {
                                requestAppExit(AppExitRequestSource.QUIT_MENU)
                            },
                        )
                    }
                    ActiveScreen.SETTINGS -> {
                        CaltopoSettingsScreen(
                            onDismiss = {
                                reloadExternalDisplayConfig(forceRecreate = true)
                                localViewModel.showMain()
                            },
                            onShowDeveloperTools = {
                                reloadExternalDisplayConfig(forceRecreate = true)
                                openDeveloperToolsWhenMainOpens = true
                                localViewModel.showMain()
                            },
                        )
                    }
                    ActiveScreen.SCANNER -> {
                        ScannerScreen(onDismiss = { localViewModel.showMain() })
                    }
                    ActiveScreen.STREAMS -> {
                        StreamsScreen(
                            onBack = { localViewModel.showMain() },
                            onMapStatusTap = { localViewModel.openConnectionOverlayFromCurrentScreen() },
                            viewModel = streamsViewModel,
                            remoteVideoStatus = activeRemoteVideoRequest?.let { request ->
                                val metrics = activeRemoteVideoMetrics
                                buildString {
                                    append("Remote viewing: ${request.requesterEmail}")
                                    if (metrics != null) {
                                        append(" • ${metrics.routeKind.replaceFirstChar { it.uppercase() }}")
                                        append(" • ${formatManagedVideoBytes(metrics.bytesSent)} sent")
                                        if (metrics.width > 0 && metrics.height > 0) {
                                            append(" • ${metrics.width}×${metrics.height}")
                                        }
                                        if (metrics.framesPerSecond > 0.0) {
                                            append(" • ${String.format(Locale.US, "%.1f", metrics.framesPerSecond)} fps")
                                        }
                                        if (metrics.bitrateBps > 0L) {
                                            append(" • ${String.format(Locale.US, "%.1f", metrics.bitrateBps / 1_000_000.0)} Mbps actual")
                                        }
                                    } else {
                                        append(" • Connecting")
                                    }
                                }
                            } ?: activeRemoteVideoFailure,
                            remoteVideoActive = activeRemoteVideoRequest != null,
                            remoteVideoDesignator = activeRemoteVideoRequest?.droneDesignator,
                            remoteRequesterEmail = activeRemoteVideoRequest?.requesterEmail,
                            remoteVideoMicrophoneEnabled = activeRemoteVideoMicrophoneEnabled,
                            remoteVideoMicrophoneError = activeRemoteVideoMicrophoneError,
                            onToggleRemoteVideoMicrophone = { toggleManagedVideoMicrophone() },
                            onTerminateRemoteVideo = { terminateManagedVideo() },
                        )
                    }
                }
                pendingDroneConfirmation?.let { confirmationState ->
                    DroneSpecConfirmationDialog(
                        state = confirmationState,
                        onFieldChange = { organization, pilotCallsign, droneDescription ->
                            localViewModel.updatePendingDroneConfirmation(
                                organization = organization,
                                pilotCallsign = pilotCallsign,
                                droneDescription = droneDescription
                            )
                        },
                        onSave = {
                            CTDebug("R2CActivity", "Drone confirmation Save clicked: remoteId=${confirmationState.remoteId}")
                            localViewModel.savePendingDroneConfirmation()
                        },
                        onUnknown = {
                            CTDebug("R2CActivity", "Drone confirmation Ignore clicked: remoteId=${confirmationState.remoteId}")
                            localViewModel.markPendingDroneConfirmationUnknown()
                        },
                    )
                }
                ProximityAlertHost(
                    onSuspend = { ProximityAlertCenter.suspendCurrentAlert() },
                    onMap = { alert ->
                        streamsViewModel.showMapOnly()
                        streamsViewModel.requestProximityMapFocus(
                            firstLat = alert.firstLat,
                            firstLng = alert.firstLng,
                            secondLat = alert.secondLat,
                            secondLng = alert.secondLng
                        )
                        if (shouldRouteAlertToPhone()) {
                            localViewModel.showStreams()
                        }
                        ProximityAlertCenter.suspendCurrentAlert()
                    }
                )
                ComplianceAlertHost()
                DroneSignalLossAlertHost()
                ControllerSignalStrengthAlertHost()
                DroneScoutBridgeAlertHost()
                SpokenWarningAlertHost()
                pendingVideoStreamRequest?.takeIf {
                    shouldPresentVideoApproval(
                        pendingVideoPreflightRouteKind,
                        pendingVideoPreflightFailure,
                    )
                }?.let { request ->
                    VideoStreamRequestDialog(
                        request = request,
                        activeRequesterEmail = currentRemoteVideoRequesterEmail(),
                        preflightRouteKind = pendingVideoPreflightRouteKind,
                        estimatedUplinkBps = pendingVideoPreflightBps,
                        preflightFailure = pendingVideoPreflightFailure,
                        onApprove = { choice ->
                            CaltopoClient.CTDebug(
                                "ManagedVideoApproval",
                                "Start selected request=${request.requestId} " +
                                    "quality=${choice.width}x${choice.height}@${choice.fps} " +
                                    "bitrate=${choice.bitrateBps}",
                            )
                            redirectActiveManagedVideo(request.requesterEmail)
                            approvedVideoSelections[request.requestId] =
                                ApprovedVideoSelection(request, choice)
                            R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
                                .respondToVideoStreamRequest(
                                    request.requestId,
                                    true,
                                    choice.width,
                                    choice.height,
                                    choice.fps,
                                    choice.bitrateBps,
                                )
                            pendingVideoRequestExpiryJob?.cancel()
                            pendingVideoRequestExpiryJob = null
                            pendingVideoStreamRequest = null
                            pendingVideoPreflightRouteKind = null
                            pendingVideoPreflightBps = null
                            pendingVideoPreflightFailure = null
                        },
                        onDecline = {
                            R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
                                .respondToVideoStreamRequest(
                                    request.requestId, false, 0, 0, 0.0, 0L,
                                )
                            releaseManagedVideoLiveSource(request.requestId)
                            pendingVideoRequestExpiryJob?.cancel()
                            pendingVideoRequestExpiryJob = null
                            pendingVideoStreamRequest = null
                            pendingVideoPreflightRouteKind = null
                            pendingVideoPreflightBps = null
                            pendingVideoPreflightFailure = null
                        },
                    )
                }
                pendingRecordingDownloadRequest?.let { request ->
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Recording Download Request") },
                        text = {
                            Text(
                                "${request.requesterEmail} requested the recorded video for " +
                                    "${request.droneDesignator}. Approve transfer to the authorized tracker account?"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
                                    .uploadRecordingDownload(request)
                                R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
                                    .respondToRecordingDownloadRequest(request.requestId, true)
                                pendingRecordingRequestExpiryJob?.cancel()
                                pendingRecordingRequestExpiryJob = null
                                pendingRecordingDownloadRequest = null
                            }) { Text("Approve transfer") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
                                    .respondToRecordingDownloadRequest(request.requestId, false)
                                pendingRecordingRequestExpiryJob?.cancel()
                                pendingRecordingRequestExpiryJob = null
                                pendingRecordingDownloadRequest = null
                            }) { Text("Decline") }
                        },
                        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                    )
                }
                if (maPackageImportState !is org.ncssar.rid2caltopo.data.MutualAidPackageImportState.Idle) {
                    MutualAidPackageImportDialog(
                        state = maPackageImportState,
                        onDismiss = { MutualAidPackageTransferManager.dismissImportState() },
                        onCancel = { MutualAidPackageTransferManager.cancelImport() }
                    )
                }
                if (bluetoothDisabled) {
                    BluetoothDisabledDialog(
                        onOpenBluetoothSettings = { openBluetoothSettings() },
                        onQuit = {
                            requestAppExit(AppExitRequestSource.BLUETOOTH_DISABLED)
                        },
                    )
                }
                pendingTrackerReauthenticationUrl?.let {
                    TrackerReauthenticationDialog(
                        onSignIn = ::openPendingTrackerReauthentication,
                        onContinueOffline = {
                            pendingTrackerReauthenticationUrl = null
                        },
                    )
                }
                if (pendingAppExitSource != null) {
                    ConfirmAppExitDialog(
                        onCancel = cancelAppExit,
                        onConfirm = confirmAppExit,
                    )
                }
            }
        }
    }

    private fun acceptLaunchDisclaimer() {
        if (launchDisclaimerAccepted) return
        CTDebug(TAG, "Launch disclaimer accepted")
        CaltopoClient.CheckIdle()
        if (CaltopoClient.IsExitRequested()) {
            CTDebug(TAG, "Launch acceptance idle check requested app exit")
            if (!isFinishing) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask()
                } else {
                    finish()
                }
            }
            return
        }
        launchDisclaimerAccepted = true
        refreshExternalDisplay()
        // Handle org-config QR scan that launched or re-launched this activity.
        handleR2cIntent(intent)
        handleStreamsQualificationIntent(intent)
        if (!InitializedCalled) {


            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                checkPermission(Manifest.permission.POST_NOTIFICATIONS)
            }

            checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                checkPermission(Manifest.permission.BLUETOOTH_SCAN)
                checkPermission(Manifest.permission.BLUETOOTH_CONNECT)
            }

            if (!outstandingPermissionsList.isEmpty()) {
                val permArray = outstandingPermissionsList.toTypedArray<String?>()
                ActivityCompat.requestPermissions(
                    this,
                    permArray,
                    Constants.REQUEST_BULK_PERMISSIONS
                )
            } else {
                initialize()
            }
        }
    }

    fun openUri(uriString : String?, mimeType: String? = null) {
        val uri : Uri = uriString?.toUri() ?: "https://www.caltopo.com".toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri)
        mimeType?.let {
            intent.setDataAndType(uri, it)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            showToast("No app found to open $uri")
        }
    }

    private fun checkBluetoothSupport() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH) !=
            PackageManager.PERMISSION_GRANTED) {
            CTError(TAG, "checkBluetoothSupport(): Did not get access to bluetooth!")
            refreshBluetoothDisabledState("bluetooth permission missing")
            return
        }

        val bluetoothAdapter: BluetoothAdapter? = BluetoothScanner.getBluetoothAdapter()
        if (null == bluetoothAdapter) {
            CTError(TAG, "Not able to access bluetooth adapter.")
            setBluetoothDisabledPanelVisible(false, "adapter unavailable")
            return
        }
        legacyBluetoothSupported = bluetoothAdapter.isEnabled
        setBluetoothDisabledPanelVisible(
            shouldShowBluetoothDisabledPanel(
                adapterPresent = true,
                bluetoothEnabled = bluetoothAdapter.isEnabled,
            ),
            "support check",
        )
        MyDeviceName = bluetoothAdapter.name
        CTDebug(TAG, "Setting MyDeviceName to:${MyDeviceName}")
        if (bluetoothAdapter.isLeCodedPhySupported) {
            codedPhySupported = true
        }
        if (bluetoothAdapter.isLeExtendedAdvertisingSupported) {
            extendedAdvertisingSupported = true
        }
    }

    private fun registerBluetoothStateReceiver() {
        if (bluetoothStateReceiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(bluetoothStateReceiver, filter)
        }
        bluetoothStateReceiverRegistered = true
    }

    private fun unregisterBluetoothStateReceiver() {
        if (!bluetoothStateReceiverRegistered) return
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) {
            CTError(TAG, "unregisterBluetoothStateReceiver() raised:", e)
        } finally {
            bluetoothStateReceiverRegistered = false
        }
    }

    private fun refreshBluetoothDisabledState(reason: String) {
        val bluetoothAdapter = try {
            BluetoothScanner.getBluetoothAdapter()
        } catch (e: SecurityException) {
            CTError(TAG, "refreshBluetoothDisabledState(): bluetooth permission unavailable.", e)
            null
        }
        setBluetoothDisabledPanelVisible(
            shouldShowBluetoothDisabledPanel(
                adapterPresent = bluetoothAdapter != null,
                bluetoothEnabled = bluetoothAdapter?.isEnabled == true,
            ),
            reason,
        )
    }

    private fun setBluetoothDisabledPanelVisible(visible: Boolean, reason: String) {
        if (bluetoothDisabled != visible) {
            CTDebug(TAG, "bluetooth disabled panel visible=$visible ($reason)")
        }
        bluetoothDisabled = visible
    }

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: Exception) {
            CTError(TAG, "openBluetoothSettings() raised:", e)
            showToast("Unable to open Bluetooth settings")
        }
    }

    private fun checkNaNSupport() {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            nanSupported = true
        }
    }

    private fun checkWiFiSupport() {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            wifiSupported = true
        }
    }

    private fun reloadExternalDisplayConfig(forceRecreate: Boolean = false) {
        externalDisplayConfig = ExternalDisplayPrefs.load(this)
        refreshExternalDisplay(forceRecreate = forceRecreate)
    }

    private fun setExternalDisplayContentMode(mode: ExternalDisplayContentMode) {
        val supportedMode = when (mode) {
            ExternalDisplayContentMode.StreamsGrid,
            ExternalDisplayContentMode.ObserverMode -> mode
            ExternalDisplayContentMode.MapOnly,
            ExternalDisplayContentMode.Split -> {
                showToast("External display map modes are not supported yet. Using streams view instead.")
                ExternalDisplayContentMode.StreamsGrid
            }
        }
        externalDisplayConfig = externalDisplayConfig.copy(contentMode = supportedMode)
        ExternalDisplayPrefs.save(this, externalDisplayConfig)
        refreshExternalDisplay(forceRecreate = true)
    }

    private fun shouldRouteAlertToPhone(): Boolean {
        return when (externalDisplayConfig.alertRouting) {
            ExternalDisplayAlertRouting.PhoneOnly -> true
            ExternalDisplayAlertRouting.ExternalOnly ->
                !externalDisplayConnected || externalDisplayConfig.mode != ExternalDisplayMode.AppManaged
            ExternalDisplayAlertRouting.Both -> true
        }
    }

    private fun findPresentationDisplay(): Display? {
        val displays = displayManager?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?: return null
        val primaryDisplayId = display?.displayId
        return displays.firstOrNull { it.displayId != primaryDisplayId } ?: displays.firstOrNull()
    }

    private fun refreshExternalDisplay(forceRecreate: Boolean = false) {
        val targetDisplay = findPresentationDisplay()
        externalDisplayConnected = targetDisplay != null
        if (externalDisplayConfig.mode != ExternalDisplayMode.AppManaged ||
            !externalDisplayConfig.autoOpenOnConnect ||
            targetDisplay == null) {
            dismissExternalDisplay(returnPhoneToMain = targetDisplay == null)
            return
        }
        val existing = externalDisplayPresentation
        val sameDisplay = existing?.display?.displayId == targetDisplay.displayId
        if (!forceRecreate && sameDisplay && existing?.isShowing == true) return
        dismissExternalDisplay(returnPhoneToMain = false)
        externalDisplayPresentation = ExternalDisplayPresentation(
            outerContext = this,
            display = targetDisplay,
            streamsViewModel = streamsViewModel,
            config = externalDisplayConfig,
            lifecycleOwner = this,
            savedStateRegistryOwner = this as SavedStateRegistryOwner,
            viewModelStoreOwner = this
        ).also {
            try {
                it.show()
            } catch (e: Exception) {
                CTError(TAG, "Unable to show external display presentation.", e)
                externalDisplayPresentation = null
            }
        }
    }

    private fun dismissExternalDisplay(returnPhoneToMain: Boolean) {
        val hadPresentation = externalDisplayPresentation != null
        externalDisplayPresentation?.dismiss()
        externalDisplayPresentation = null
        if (hadPresentation) {
            CTDebug(
                TAG,
                "dismissExternalDisplay(returnPhoneToMain=$returnPhoneToMain, hadPresentation=$hadPresentation, " +
                    "returnToPhoneOnly=${externalDisplayConfig.returnToPhoneOnlyLayoutOnDisconnect}, activeScreen=${localViewModel.activeScreen.value})"
            )
        }
        if (returnPhoneToMain &&
            hadPresentation &&
            externalDisplayConfig.returnToPhoneOnlyLayoutOnDisconnect &&
            localViewModel.activeScreen.value == ActiveScreen.STREAMS) {
            CTDebug(TAG, "dismissExternalDisplay(): returning phone from Streams to Main after presentation dismissal.")
            localViewModel.showMain()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray
    ) {
        CTDebug(TAG,String.format(Locale.US,
            "onRequestPermissionsResult(%d)", requestCode))
        super.onRequestPermissionsResult(requestCode, permissions as Array<out String>, grantResults)
        if (requestCode == REQUEST_MANAGED_VIDEO_MICROPHONE) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            pendingManagedVideoMicrophoneEnable = false
            if (granted) {
                managedVideoMediaPeer?.setMicrophoneEnabled(true)
            } else {
                activeRemoteVideoMicrophoneEnabled = false
                activeRemoteVideoMicrophoneError = "Microphone permission denied"
            }
            return
        }
        if (requestCode == Constants.REQUEST_BULK_PERMISSIONS) {
            for (i in permissions.indices) {
                outstandingPermissionsList.remove(permissions[i])
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    CTError(
                        TAG,
                        "onRequestPermissionsResult: Did not get " + permissions[i]
                    )
                } else {
                    CTDebug(
                        TAG,
                        "onRequestPermissionsResult(): Received " + permissions[i]
                    )
                }
            }
        }
        if (outstandingPermissionsList.isEmpty()) initialize()
    }


    private fun initialize() {
        if (InitializedCalled) return
        checkBluetoothSupport()
        checkNaNSupport()
        checkWiFiSupport()
        CTDebug(TAG, "initialize()")
        InitializedCalled = true
        CaltopoClient.InitArchiveDir()

        locationRequest = LocationRequest.Builder((10 * 1000).toLong()) // 10 seconds
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis((5 * 1000).toLong()) // 5 seconds
            .build()

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    if (location != null) {
                        val hadDeviceLocationBefore = CaltopoMap.GetDeviceLocation() != null
                        CaltopoMap.UpdateMyLocation(location)
                        if (!hadDeviceLocationBefore && CaltopoMap.GetDeviceLocation() != null) {
                            AirspaceCenter.requestImmediateRefresh()
                            NotamCenter.requestImmediateRefresh()
                            LandRestrictionCenter.requestImmediateRefresh()
                        }
                    }
                }
            }
        }
        if ((ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED) &&
                (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED)) {
            if (mFusedLocationClient != null && locationRequest != null && locationCallback != null) {
                CTDebug(TAG, "initialize(): requesting fused location updates.")
                mFusedLocationClient?.requestLocationUpdates(
                    locationRequest!!,
                    locationCallback!!,
                    Looper.getMainLooper()
                )?.addOnSuccessListener {
                    CTDebug(TAG, "initialize(): fused location updates registered.")
                }?.addOnFailureListener { e ->
                    CTError(TAG, "initialize(): requestLocationUpdates failed.", e)
                }
                mFusedLocationClient?.lastLocation
                    ?.addOnSuccessListener { location ->
                        if (location == null) {
                            CTDebug(TAG, "initialize(): fused lastLocation unavailable.")
                            return@addOnSuccessListener
                        }
                        CTDebug(
                            TAG,
                            String.format(
                                Locale.US,
                                "initialize(): applying fused lastLocation lat=%.7f lng=%.7f accuracy=%.3fm ageMs=%d",
                                location.latitude,
                                location.longitude,
                                location.accuracy,
                                (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
                            )
                        )
                        val hadDeviceLocationBefore = CaltopoMap.GetDeviceLocation() != null
                        CaltopoMap.UpdateMyLocation(location)
                        if (!hadDeviceLocationBefore && CaltopoMap.GetDeviceLocation() != null) {
                            AirspaceCenter.requestImmediateRefresh()
                            NotamCenter.requestImmediateRefresh()
                            LandRestrictionCenter.requestImmediateRefresh()
                        }
                    }
                    ?.addOnFailureListener { e ->
                        CTError(TAG, "initialize(): fused lastLocation failed.", e)
                    }
            }
        } else {
            CTError(TAG, "initialize(): location permissions unavailable; fused location updates not requested.")
        }
        CTDebug(
            TAG,
            String.format(
                Locale.US,
                "onCreate(): Requesting ScanningService start from activity 0x%x restarting=%s running=%s",
                this.hashCode(),
                RestartingFlag,
                ScanningService.IsRunning()
            )
        )
        ScanningService.requestStart(applicationContext)

        CTDebug(
            TAG,
            String.format(
                Locale.US,
                "onCreate(): Requesting MediaMTXService start restarting=%s running=%s",
                RestartingFlag,
                MediaMTXService.IsRunning()
            )
        )
        MediaMTXService.requestStart(applicationContext)
        RestartingFlag = false
    }

    private fun stopLocationUpdates() {
        try {
            val callback = locationCallback
            if (callback != null) {
                mFusedLocationClient?.removeLocationUpdates(callback)
            }
        } catch (e: Exception) {
            CTError(TAG, "stopLocationUpdates() raised:", e)
        } finally {
            locationCallback = null
            locationRequest = null
            mFusedLocationClient = null
        }
    }

    fun showToast(message: String) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) Toast.makeText(
            baseContext,
            message,
            Toast.LENGTH_LONG
        ).show()
        else {
            val view : View = findViewById<View>(android.R.id.content).rootView

            val snackbar: Snackbar = Snackbar.make(
                view,
                message,
                Snackbar.LENGTH_LONG
            )
            val snackView: View = snackbar.getView()
            val snackTextView: TextView =
                snackView.findViewById(com.google.android.material.R.id.snackbar_text)
            snackTextView.setTextIsSelectable(true)
            snackTextView.maxLines = 5
            snackbar.show()
        }
    }

    fun beginTrackerReauthentication(url: String) {
        runOnUiThread {
            pendingTrackerReauthenticationUrl = url
        }
    }

    private fun openPendingTrackerReauthentication() {
        val url = pendingTrackerReauthenticationUrl ?: return
        if (!shouldOpenTrackerReauthentication(trackerReauthenticationBrowserOpen)) {
            CTDebug(TAG, "Tracker reauthentication browser is already open")
            return
        }
        trackerReauthenticationBrowserOpen = true
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            trackerReauthenticationBrowserOpen = false
            showToast("Unable to open Tracker sign-in. Tap Sign in to try again.")
        }
    }

    public override fun onDestroy() {
        managedVideoMediaPeer?.close()
        managedVideoMediaPeer = null
        setVolumeControlStream(AudioManager.STREAM_ALARM)
        unregisterBluetoothStateReceiver()
        displayManager?.unregisterDisplayListener(displayListener)
        dismissExternalDisplay(returnPhoneToMain = false)
        val exitRequested = CaltopoClient.IsExitRequested()
        // Removing the task from Recents finishes the primary activity without
        // setting AppExitRequested. Every real primary-activity finish must run
        // shutdown so remote markers and active tracks are cleaned up.
        val shouldShutdown = shouldShutdownOnActivityDestroy(
            isPrimaryActivity = this === AppActivity,
            isFinishing = isFinishing,
            isChangingConfigurations = isChangingConfigurations,
        )

        if (shouldShutdown) {
            try {
                stopLocationUpdates()
                R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator.setPeerListChangedListener(null)
                R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
                    .setVideoStreamRequestListener(null)
                CTDebug(TAG,"onDestroy() shutting down streaming service..." )
                MediaMTXService.requestStop(this)
                CTDebug(TAG,"onDestroy() shutting down scanning service..." )
                ScanningService.requestStop(this)
                CaltopoClient.ShutdownAsync()
                CTDebug(TAG, "onDestroy() archiving tracks...")
                AppActivity = null
            } catch (e: Exception) {
                CTError(TAG, "onDestroy() raised:", e)
            }
        } else {
            stopLocationUpdates()
            CTDebug(
                TAG,
                String.format(
                    Locale.US,
                    "onDestroy() skipping app shutdown (thisIsApp=%s, isFinishing=%s, isChangingConfig=%s, exitRequested=%s)",
                    (this === AppActivity),
                    isFinishing,
                    isChangingConfigurations,
                    exitRequested
                )
            )
            if (isFinishing && !isChangingConfigurations) {
                if (this === AppActivity) AppActivity = null
            }
        }
        // Reset the one-shot initialization guard whenever the activity is truly
        // finishing (any path: Quit button, CheckIdle, or unexpected Shutdown call).
        // Without this reset, a cached process relaunch skips initialize() entirely
        // and the services never start.  Config-change rotations are excluded so that
        // a normal orientation change does not re-trigger service startup.
        if (isFinishing && !isChangingConfigurations) {
            CaltopoMap.SetMyLocationOverride(null)
            InitializedCalled = false
        }
        super.onDestroy()
    }

    override fun onVideoStreamRequest(request: VideoStreamViewRequest) {
        runOnUiThread {
            if (
                ManagedVideoSessionRecordingCatalog.find(
                    applicationContext,
                    request.streamSessionId,
                ) == null
            ) {
                requireManagedVideoLiveSource(request.requestId, request.droneDesignator)
            }
            if (!request.consentRequired) {
                remoteControlledVideoRequests[request.requestId] = request
                CaltopoClient.CTDebug(
                    "ManagedVideoApproval",
                    "Remote-controlled request=${request.requestId}; requester selects quality",
                )
                return@runOnUiThread
            }
            pendingVideoStreamRequest = request
            schedulePendingVideoRequestExpiry(request)
            pendingVideoPreflightRouteKind = null
            pendingVideoPreflightBps = null
            pendingVideoPreflightFailure = null
            SpokenWarningCenter.requestSpokenPhrase(
                kind = SpokenWarningKind.VideoStreamRequest,
                sourceKey = request.requestId,
                phrase = "Video Stream Request from ${request.requesterEmail}",
            )
            Toast.makeText(
                this,
                "Preparing routed video request from ${request.requesterEmail}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onRecordingDownloadRequest(request: RecordingDownloadRequest) {
        runOnUiThread {
            if (!request.consentRequired) {
                R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
                    .uploadRecordingDownload(request)
                Toast.makeText(
                    this,
                    "Sending recording to ${request.requesterEmail}",
                    Toast.LENGTH_LONG,
                ).show()
                return@runOnUiThread
            }
            pendingRecordingDownloadRequest = request
            schedulePendingRecordingRequestExpiry(request)
            SpokenWarningCenter.requestSpokenPhrase(
                kind = SpokenWarningKind.VideoStreamRequest,
                sourceKey = "recording-${request.requestId}",
                phrase = "Recording Download Request from ${request.requesterEmail}",
            )
        }
    }

    private fun requestExpiryDelayMillis(expiresAt: String): Long {
        val deadlineMs = runCatching {
            Instant.parse(expiresAt).toEpochMilli()
        }.getOrElse {
            CaltopoClient.CTWarn(
                "ManagedVideoApproval",
                "Invalid tracker request expiry '$expiresAt'; applying 60-second bound",
            )
            System.currentTimeMillis() + 60_000L
        }
        return (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun schedulePendingVideoRequestExpiry(request: VideoStreamViewRequest) {
        pendingVideoRequestExpiryJob?.cancel()
        pendingVideoRequestExpiryJob = lifecycleScope.launch {
            delay(requestExpiryDelayMillis(request.expiresAt))
            if (pendingVideoStreamRequest?.requestId != request.requestId) return@launch
            pendingVideoStreamRequest = null
            pendingVideoPreflightRouteKind = null
            pendingVideoPreflightBps = null
            pendingVideoPreflightFailure = null
            releaseManagedVideoLiveSource(request.requestId)
            CaltopoClient.CTDebug(
                "ManagedVideoApproval",
                "Video request timed out request=${request.requestId}",
            )
            Toast.makeText(
                this@R2CActivity,
                "Remote video request timed out",
                Toast.LENGTH_LONG,
            ).show()
            pendingVideoRequestExpiryJob = null
        }
    }

    private fun schedulePendingRecordingRequestExpiry(
        request: RecordingDownloadRequest,
    ) {
        pendingRecordingRequestExpiryJob?.cancel()
        pendingRecordingRequestExpiryJob = lifecycleScope.launch {
            delay(requestExpiryDelayMillis(request.expiresAt))
            if (pendingRecordingDownloadRequest?.requestId != request.requestId) {
                return@launch
            }
            pendingRecordingDownloadRequest = null
            CaltopoClient.CTDebug(
                "ManagedVideoApproval",
                "Recording request timed out request=${request.requestId}",
            )
            Toast.makeText(
                this@R2CActivity,
                "Recording transfer request timed out",
                Toast.LENGTH_LONG,
            ).show()
            pendingRecordingRequestExpiryJob = null
        }
    }

    override fun onVideoPreflightResult(
        requestId: String,
        routeKind: String,
        estimatedUplinkBps: Long,
    ) {
        runOnUiThread {
            if (pendingVideoStreamRequest?.requestId == requestId) {
                pendingVideoPreflightRouteKind = routeKind
                pendingVideoPreflightBps = estimatedUplinkBps
                pendingVideoPreflightFailure = null
                val choices = videoQualityChoices(
                    pendingVideoStreamRequest!!,
                    estimatedUplinkBps,
                )
                CaltopoClient.CTDebug(
                    "ManagedVideoApproval",
                    "Approval ready request=$requestId route=$routeKind " +
                        "usableBps=$estimatedUplinkBps choices=${choices.size} " +
                        "selected=${choices.firstOrNull {
                            it.capacity != LinkCapacity.INSUFFICIENT
                        }?.preset.orEmpty()}",
                )
            }
        }
    }

    override fun onVideoPreflightFailure(requestId: String, reason: String) {
        runOnUiThread {
            if (pendingVideoStreamRequest?.requestId == requestId) {
                pendingVideoPreflightFailure = reason
            }
        }
    }

    override fun onVideoStreamRequestCancelled(requestId: String) {
        runOnUiThread {
            if (pendingVideoStreamRequest?.requestId == requestId) {
                pendingVideoRequestExpiryJob?.cancel()
                pendingVideoRequestExpiryJob = null
                pendingVideoStreamRequest = null
                pendingVideoPreflightRouteKind = null
                pendingVideoPreflightBps = null
                pendingVideoPreflightFailure = null
            }
            approvedVideoSelections.remove(requestId)
            remoteControlledVideoRequests.remove(requestId)
            releaseManagedVideoLiveSource(requestId)
            if (activeRemoteVideoRequest?.requestId == requestId) {
                managedVideoMediaPeer?.close()
                managedVideoMediaPeer = null
                stopManagedVideoRecordingDecoder()
                setVolumeControlStream(AudioManager.STREAM_ALARM)
                activeRemoteVideoRequest = null
                activeRemoteVideoSelection = null
                activeRemoteVideoOfferSdp = null
                activeRemoteVideoMetrics = null
            }
        }
    }

    override fun onVideoMediaOffer(offer: VideoMediaOffer) {
        runOnUiThread {
            val remoteRequest = remoteControlledVideoRequests[offer.requestId]
            val remoteSelection = remoteRequest?.takeIf {
                it.streamSessionId == offer.streamSessionId &&
                    offer.selectedWidth > 0 && offer.selectedHeight > 0 &&
                    offer.selectedFps > 0.0 && offer.selectedBitrateBps > 0L
            }?.let { request ->
                ApprovedVideoSelection(
                    request,
                    VideoQualityChoice(
                        preset = "Requester selected",
                        label = "Requester selected",
                        width = offer.selectedWidth,
                        height = offer.selectedHeight,
                        fps = offer.selectedFps,
                        bitrateBps = offer.selectedBitrateBps,
                        capacity = LinkCapacity.ENOUGH,
                    ),
                )
            }
            val activeSelection = activeRemoteVideoSelection
                ?.takeIf { it.request.requestId == offer.requestId }
            if (activeSelection != null && activeRemoteVideoOfferSdp == offer.sdp) {
                CaltopoClient.CTDebug(
                    "ManagedVideoMedia",
                    "Ignoring replayed media offer for active request=${offer.requestId}",
                )
                return@runOnUiThread
            }
            // Keep the pilot's approval until an answer is actually ready.
            // The tracker can replay the same pending offer through its direct,
            // notification, and reconnect paths; consuming approval on receipt
            // lets a later replay incorrectly terminate a legitimate attempt.
            val approved = approvedVideoSelections[offer.requestId]
                ?: activeSelection
                ?: remoteSelection
            if (approved == null) {
                CaltopoClient.CTWarn(
                    "ManagedVideoMedia",
                    "Ignoring media offer without pilot approval request=${offer.requestId}",
                )
                return@runOnUiThread
            }
            if (activeSelection != null) {
                CaltopoClient.CTDebug(
                    "ManagedVideoMedia",
                    "Replacing media peer after browser reconnect request=${offer.requestId}",
                )
            }
            val recording = ManagedVideoSessionRecordingCatalog.find(
                applicationContext,
                approved.request.streamSessionId,
            )
            stopManagedVideoRecordingDecoder()
            val sessionId = recording?.let {
                streamsViewModel.startManagedVideoRecordingSession(
                    it.droneDesignator,
                    Uri.fromFile(it.file).toString(),
                )?.also { decoderSessionId ->
                        managedVideoRecordingDecoderSessionId = decoderSessionId
                    }
            } ?: streamsViewModel.managedVideoRenderSessionId(
                approved.request.droneDesignator,
            )
            if (sessionId == null) {
                approvedVideoSelections.remove(offer.requestId)
                releaseManagedVideoLiveSource(offer.requestId)
                activeRemoteVideoFailure = "Remote video could not start: drone source unavailable."
                R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
                    .sendVideoStreamTerminated(offer.requestId, "Drone source unavailable")
                return@runOnUiThread
            }
            managedVideoMediaPeer?.close()
            activeRemoteVideoMetrics = null
            activeRemoteVideoFailure = null
            activeRemoteVideoMicrophoneEnabled = false
            activeRemoteVideoMicrophoneError = null
            activeRemoteVideoRequest = approved.request
            if (recording == null) {
                requireManagedVideoLiveSource(
                    approved.request.requestId,
                    approved.request.droneDesignator,
                )
            } else {
                releaseManagedVideoLiveSource(approved.request.requestId)
            }
            remoteControlledVideoRequests.remove(offer.requestId)
            activeRemoteVideoSelection = approved
            activeRemoteVideoOfferSdp = offer.sdp
            setVolumeControlStream(AudioManager.STREAM_VOICE_CALL)
            SpokenWarningCenter.requestSpokenPhrase(
                kind = SpokenWarningKind.VideoStreamRequest,
                sourceKey = "sharing-${offer.requestId}",
                phrase = "Now sharing video stream with ${approved.request.requesterEmail}",
            )
            lateinit var peer: ManagedVideoMediaPeer
            peer = ManagedVideoMediaPeer(object : ManagedVideoMediaPeer.Sink {
                override fun sendAnswer(requestId: String, sdp: String) {
                    if (managedVideoMediaPeer !== peer) return
                    approvedVideoSelections.remove(requestId)
                    R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
                        .sendVideoMediaAnswer(requestId, sdp)
                }

                override fun onMetrics(metrics: ManagedVideoMediaPeer.Metrics) {
                    runOnUiThread {
                        if (
                            managedVideoMediaPeer === peer &&
                            activeRemoteVideoRequest?.requestId == metrics.requestId
                        ) {
                            activeRemoteVideoMetrics = metrics
                        }
                    }
                }

                override fun onFailure(requestId: String, reason: String) {
                    runOnUiThread {
                        if (
                            managedVideoMediaPeer === peer &&
                            activeRemoteVideoRequest?.requestId == requestId
                        ) {
                            val sourceEndedNormally =
                                managedVideoRecordingDecoderSessionId == null &&
                                StreamRegistry.streams.value[
                                    approved.request.droneDesignator
                                ]?.state != org.ncssar.rid2caltopo.video.StreamState.LIVE
                            val terminationReason = if (sourceEndedNormally) {
                                "source_ended"
                            } else {
                                reason
                            }
                            approvedVideoSelections.remove(requestId)
                            managedVideoMediaPeer = null
                            stopManagedVideoRecordingDecoder()
                            releaseManagedVideoLiveSource(requestId)
                            activeRemoteVideoRequest = null
                            activeRemoteVideoSelection = null
                            activeRemoteVideoOfferSdp = null
                            activeRemoteVideoMetrics = null
                            activeRemoteVideoFailure = if (sourceEndedNormally) {
                                null
                            } else {
                                "Remote video stopped: $reason"
                            }
                            setVolumeControlStream(AudioManager.STREAM_ALARM)
                            R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
                                .sendVideoStreamTerminated(requestId, terminationReason)
                        }
                    }
                }

                override fun onMicrophoneState(requestId: String, enabled: Boolean, error: String?) {
                    runOnUiThread {
                        if (
                            managedVideoMediaPeer === peer &&
                            activeRemoteVideoRequest?.requestId == requestId
                        ) {
                            activeRemoteVideoMicrophoneEnabled = enabled
                            activeRemoteVideoMicrophoneError = error
                        }
                    }
                }
            })
            managedVideoMediaPeer = peer
            // WebRTC factory/network-monitor creation can wait for Android
            // connectivity callbacks. Starting it on the UI thread can stall
            // before SDP callbacks are dispatched, leaving the browser without
            // an answer while the rest of the app appears responsive.
            lifecycleScope.launch(Dispatchers.IO) {
                val started = peer.start(
                        applicationContext,
                        offer,
                        sessionId,
                        approved.quality.width,
                        approved.quality.height,
                        approved.quality.fps,
                        approved.quality.bitrateBps,
                    )
                if (!started) {
                    withContext(Dispatchers.Main) {
                        if (managedVideoMediaPeer === peer) {
                            managedVideoMediaPeer = null
                            stopManagedVideoRecordingDecoder()
                            releaseManagedVideoLiveSource(offer.requestId)
                            activeRemoteVideoRequest = null
                            activeRemoteVideoSelection = null
                            activeRemoteVideoOfferSdp = null
                        }
                    }
                }
            }
        }
    }

    private fun stopManagedVideoRecordingDecoder() {
        managedVideoRecordingDecoderSessionId?.let(FfmpegBridge::stop)
        managedVideoRecordingDecoderSessionId = null
    }

    private fun requireManagedVideoLiveSource(requestId: String, designator: String) {
        val previous = managedVideoLiveSourcesByRequestId.put(requestId, designator)
        if (previous == designator) return
        if (previous != null && previous !in managedVideoLiveSourcesByRequestId.values) {
            streamsViewModel.setManagedVideoSourceRequired(previous, false)
        }
        streamsViewModel.setManagedVideoSourceRequired(designator, true)
    }

    private fun releaseManagedVideoLiveSource(requestId: String) {
        managedVideoLiveSourcesByRequestId.remove(requestId)?.let { designator ->
            if (designator in managedVideoLiveSourcesByRequestId.values) return@let
            streamsViewModel.setManagedVideoSourceRequired(designator, false)
        }
    }

    private fun terminateManagedVideo(reason: String = "Pilot terminated stream") {
        val requestId = activeRemoteVideoRequest?.requestId ?: return
        managedVideoSourceRecoveryJob?.cancel()
        managedVideoSourceRecoveryJob = null
        managedVideoMediaPeer?.close()
        managedVideoMediaPeer = null
        stopManagedVideoRecordingDecoder()
        releaseManagedVideoLiveSource(requestId)
        activeRemoteVideoRequest = null
        activeRemoteVideoSelection = null
        activeRemoteVideoOfferSdp = null
        activeRemoteVideoMetrics = null
        activeRemoteVideoFailure = null
        activeRemoteVideoMicrophoneEnabled = false
        activeRemoteVideoMicrophoneError = null
        setVolumeControlStream(AudioManager.STREAM_ALARM)
        R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
            .sendVideoStreamTerminated(requestId, reason)
    }

    private fun currentRemoteVideoRequesterEmail(): String? =
        activeRemoteVideoRequest?.requesterEmail
            ?: approvedVideoSelections.values.firstOrNull()?.request?.requesterEmail

    private fun redirectActiveManagedVideo(replacementRequesterEmail: String) {
        val reason = "Stream redirected to $replacementRequesterEmail"
        val activeRequestId = activeRemoteVideoRequest?.requestId
        if (activeRequestId != null) {
            terminateManagedVideo(reason)
        }
        val coordinator = R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
        val awaitingMedia = approvedVideoSelections.keys
            .filter { it != activeRequestId }
            .toList()
        awaitingMedia.forEach { requestId ->
            approvedVideoSelections.remove(requestId)
            coordinator.sendVideoStreamTerminated(requestId, reason)
        }
    }

    private suspend fun refreshManagedVideoThumbnails(
        advertisements: List<ManagedVideoStreamAdvertisement>,
        force: Boolean = false,
    ) {
        for (advertisement in advertisements.take(8)) {
            if (
                ManagedVideoThumbnailStore.get(advertisement.sessionId) != null &&
                (!force || advertisement.mediaKind != "live")
            ) continue
            val recording = if (advertisement.mediaKind == "recording") {
                ManagedVideoSessionRecordingCatalog.find(
                    applicationContext,
                    advertisement.sessionId,
                )
            } else {
                null
            }
            val ownedDecoder = recording?.let {
                streamsViewModel.startManagedVideoRecordingSession(
                    it.droneDesignator,
                    Uri.fromFile(it.file).toString(),
                )
            }
            val decoderSessionId = ownedDecoder
                ?: streamsViewModel.managedVideoRenderSessionId(
                    advertisement.droneDesignator
                )
                ?: continue
            try {
                ManagedVideoThumbnailStore.capture(
                    advertisement.sessionId,
                    decoderSessionId,
                )
            } finally {
                if (ownedDecoder != null) {
                    FfmpegBridge.stop(ownedDecoder)
                }
            }
        }
    }

    private fun maintainManagedVideoSource(
        streams: Map<String, org.ncssar.rid2caltopo.video.StreamInfo>,
    ) {
        val request = activeRemoteVideoRequest
        val peer = managedVideoMediaPeer
        if (request == null || peer == null) {
            managedVideoSourceRecoveryJob?.cancel()
            managedVideoSourceRecoveryJob = null
            return
        }
        if (managedVideoRecordingDecoderSessionId != null) {
            managedVideoSourceRecoveryJob?.cancel()
            managedVideoSourceRecoveryJob = null
            return
        }
        val stream = streams[request.droneDesignator]
        if (stream?.state == org.ncssar.rid2caltopo.video.StreamState.LIVE) {
            managedVideoSourceRecoveryJob?.cancel()
            managedVideoSourceRecoveryJob = null
            streamsViewModel.managedVideoRenderSessionId(request.droneDesignator)?.let { sessionId ->
                peer.rebindVideoSource(sessionId)
            }
            return
        }
        if (managedVideoSourceRecoveryJob != null) return
        val requestId = request.requestId
        CaltopoClient.CTWarn(
            "ManagedVideoMedia",
            "Drone source interrupted; preserving WebRTC during decoder recovery request=$requestId",
        )
        managedVideoSourceRecoveryJob = lifecycleScope.launch {
            delay(MANAGED_VIDEO_SOURCE_RECOVERY_GRACE_MS)
            managedVideoSourceRecoveryJob = null
            if (
                activeRemoteVideoRequest?.requestId == requestId &&
                StreamRegistry.streams.value[request.droneDesignator]?.state !=
                org.ncssar.rid2caltopo.video.StreamState.LIVE
            ) {
                terminateManagedVideo("Drone video source did not recover")
            }
        }
    }

    private fun toggleManagedVideoMicrophone() {
        val peer = managedVideoMediaPeer ?: return
        activeRemoteVideoMicrophoneError = null
        if (activeRemoteVideoMicrophoneEnabled) {
            peer.setMicrophoneEnabled(false)
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            peer.setMicrophoneEnabled(true)
        } else if (!pendingManagedVideoMicrophoneEnable) {
            pendingManagedVideoMicrophoneEnable = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_MANAGED_VIDEO_MICROPHONE,
            )
        }
    }

    companion object {
        private const val MANAGED_VIDEO_SOURCE_RECOVERY_GRACE_MS = 30_000L
        const val TAG: String = "R2CActivity"
        private const val REQUEST_MANAGED_VIDEO_MICROPHONE = 7310
        private var AppActivity: R2CActivity? = null
        @JvmStatic
        fun getR2CActivity(): R2CActivity? {
            return AppActivity
        }

        private var RestartingFlag = false
        private var InitializedCalled = false
        @JvmField
        var MyDeviceName:String = "<unknown>"
        var legacyBluetoothSupported: Boolean = false
        var codedPhySupported: Boolean = false
        var extendedAdvertisingSupported: Boolean = false
        var nanSupported: Boolean = false
        var wifiSupported: Boolean = false

        @JvmStatic
        fun Shutdown() {
            // Route through QuitApplication() so that AppExitRequested is set before
            // finishAffinity() fires, services are stopped explicitly, and the log file
            // is flushed.  The old direct finishAffinity() call left AppExitRequested=false,
            // which caused onDestroy() to skip cleanup and left services running with no
            // way to restart them on the next launch.
            CaltopoClient.QuitApplication()
        }

        @JvmStatic
        fun getMyAppVersion(): String {
            return String.format(Locale.US,"%s",BuildConfig.BUILD_VERSION)
        }
    }
}
