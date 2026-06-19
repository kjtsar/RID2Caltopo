/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

import StreamsViewModel
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.location.Location
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.app.ArchiveCleanupDeleteResult
import org.ncssar.rid2caltopo.app.ArchiveCleanupDirectoryOption
import org.ncssar.rid2caltopo.app.MediaMTXService
import org.ncssar.rid2caltopo.app.LogArchiveDayOption
import org.ncssar.rid2caltopo.app.canDeleteArchiveCleanupSelection
import org.ncssar.rid2caltopo.app.defaultSelectedArchiveCleanupDirectories
import org.ncssar.rid2caltopo.app.formatArchiveSize
import org.ncssar.rid2caltopo.airspace.AirspaceCenter
import org.ncssar.rid2caltopo.data.AppUpdateAdvisory
import org.ncssar.rid2caltopo.data.AppConfigStore
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.DriveSyncAction
import org.ncssar.rid2caltopo.data.ExternalDisplayContentMode
import org.ncssar.rid2caltopo.data.FaaConfigManager
import org.ncssar.rid2caltopo.data.GoogleDriveConfigSync
import org.ncssar.rid2caltopo.data.MutualAidExportCoordinator
import org.ncssar.rid2caltopo.data.MutualAidProfileManager
import org.ncssar.rid2caltopo.data.MutualAidPackageManager
import org.ncssar.rid2caltopo.data.OrgConfigManager
import org.ncssar.rid2caltopo.data.WaypointTrack
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.notam.NotamPanel
import org.ncssar.rid2caltopo.notam.NotamStatusChip
import org.ncssar.rid2caltopo.video.ComplianceAlertBell
import org.ncssar.rid2caltopo.video.ComplianceAlertDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun parseCsvTags(csv: String): List<String> {
    val tags = linkedSetOf<String>()
    csv.split(",").forEach { raw ->
        val tag = raw.trim()
        if (tag.isNotEmpty()) tags.add(tag)
    }
    return tags.toList()
}

private fun buildTagCsv(selectedKnownTags: Set<String>, customTagsText: String): String {
    val tags = linkedSetOf<String>()
    tags.addAll(selectedKnownTags)
    tags.addAll(parseCsvTags(customTagsText))
    return tags.joinToString(",")
}

private fun formatLocationOverride(location: Location?): String {
    if (location == null) return "Device GPS"
    return "%.6f, %.6f".format(location.latitude, location.longitude)
}

private fun parseLocationOverride(input: String): Location? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(",")
    if (parts.size != 2) throw IllegalArgumentException("Use 'lat,lng'")
    val lat = parts[0].trim().toDoubleOrNull() ?: throw IllegalArgumentException("Latitude is invalid")
    val lng = parts[1].trim().toDoubleOrNull() ?: throw IllegalArgumentException("Longitude is invalid")
    if (lat !in -90.0..90.0) throw IllegalArgumentException("Latitude must be between -90 and 90")
    if (lng !in -180.0..180.0) throw IllegalArgumentException("Longitude must be between -180 and 180")
    return Location("manual-override").apply {
        latitude = lat
        longitude = lng
        accuracy = 1.0f
    }
}

private val REQUIRED_MAP_CACHE_TAGS = listOf(
    "MapCacheDebug",
    "MapCacheTile",
    "MapCacheDEM",
    "MapCacheIcon",
    "MapCacheStore"
)

// 1. Define a sealed interface to represent the different types of items in our list.
sealed interface MainScreenItem {
    object IncidentView : MainScreenItem
    data class LocalView(val viewModel: R2CViewModel) : MainScreenItem
    data class SpacerView(val height: Dp) : MainScreenItem
}

private fun shouldOfferDriveRestore(context: Context): Boolean {
    return !AppConfigStore.hasMeaningfulConfig(context) && CaltopoClient.GetArchiveUri() == null
}

internal suspend fun <T> readMutualAidPackagePreviewOffMain(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    readPreview: () -> T
): T = withContext(dispatcher) {
    readPreview()
}

// Try to bust thru Google Drive's cache to get the latest version of requested
// document.  N.B. If battery saver is on, Google Drive will always use the
// cached version regardless.
class FreshOpenDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return super.createIntent(context, input).apply {
            // Tells providers (like Drive) to check the server
            putExtra("android.content.extra.SHOW_ADVANCED", true)
            putExtra("android.content.extra.NO_CACHE", true)
            // Ensures we aren't restricted to files already on the device
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
        }
    }
}

class OpenArchiveDir() : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        return super.createIntent(context, input).apply {
            val downloadsUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Downloads"
            )
            val initialUri = input ?: downloadsUri
            putExtra(Intent.EXTRA_TITLE, "Select directory to archive drone tracks")
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            putExtra("android.content.extra.NO_CACHE", true)
        }
    }
}

private fun restartMediaMtxServer(context: android.content.Context) {
    val appContext = context.applicationContext
    MediaMTXService.requestRestart(appContext)
    CaltopoClient.ShowToast("Streams server restarted. Connected publishers will reconnect if supported.")
    CTDebug("MainMenu", "User requested MediaMTXService restart from menu.")
}

private fun isInstalledFromGooglePlay(context: Context): Boolean {
    return try {
        val installerPackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager
                .getInstallSourceInfo(context.packageName)
                .installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        installerPackage == "com.android.vending"
    } catch (_: Exception) {
        false
    }
}

private fun openAppUpgradeLocation(context: Context, updateUrl: String) {
    val targetUrl = updateUrl.trim()
    if (targetUrl.isNotEmpty()) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
        } catch (_: ActivityNotFoundException) {
            CaltopoClient.ShowToast("Update location unavailable.")
        }
        return
    }

    if (!isInstalledFromGooglePlay(context)) {
        CaltopoClient.ShowToast("Update location unavailable.")
        return
    }

    val packageId = BuildConfig.APPLICATION_ID
    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageId"))
    try {
        context.startActivity(marketIntent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageId"))
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    localViewModel: R2CViewModel,
    streamsViewModel: StreamsViewModel,
    availableLogArchiveDaysProvider: suspend () -> List<LogArchiveDayOption>,
    onEmailLog: suspend (List<String>) -> Unit,
    availableArchiveCleanupDirectoriesProvider: suspend () -> List<ArchiveCleanupDirectoryOption>,
    onDeleteArchiveDirectories: suspend (List<String>) -> ArchiveCleanupDeleteResult,
    onShowHelp: () -> Unit,
    externalDisplayConnected: Boolean = false,
    externalDisplayContentMode: ExternalDisplayContentMode? = null,
    onSetExternalDisplayContent: ((ExternalDisplayContentMode) -> Unit)? = null
) {
    val tag = "MainScreen"
    var menuExpanded by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showConfirmExitDialog by remember { mutableStateOf(false) }
    var showDebugTagDialog by remember { mutableStateOf(false) }
    var knownDebugTags by remember { mutableStateOf(listOf<String>()) }
    var selectedKnownTags by remember { mutableStateOf(setOf<String>()) }
    var customDebugTagsText by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(CaltopoClient.LoggingLevelName(CaltopoClient.DebugLevel)) }
    val context =  LocalContext.current
    var pendingDriveAction by remember { mutableStateOf<DriveSyncAction?>(null) }
    var pendingOrgExport by remember { mutableStateOf(false) }
    var pendingFaaExport by remember { mutableStateOf(false) }
    var driveSyncInProgress by remember { mutableStateOf(false) }
    var driveRestoreEligibilityLoaded by remember { mutableStateOf(false) }
    var showDriveRestoreDialog by remember { mutableStateOf(false) }
    var linkedDriveEmail by remember { mutableStateOf("") }
    var showOrgExportDialog by remember { mutableStateOf(false) }
    var showFaaExportDialog by remember { mutableStateOf(false) }
    var showImportConfigDialog by remember { mutableStateOf(false) }
    var showReleaseNotesDialog by remember { mutableStateOf(false) }
    var releaseNoteEntries by remember { mutableStateOf(parseReleaseNotes(null)) }
    var pendingMutualAidImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMutualAidImportPreview by remember { mutableStateOf<MutualAidPackageManager.PackagePreview?>(null) }
    var mutualAidPreviewRequestId by remember { mutableLongStateOf(0L) }
    var showMutualAidImportPreviewDialog by remember { mutableStateOf(false) }
    var importingMutualAidConfig by remember { mutableStateOf(false) }
    var showNotamPanel by remember { mutableStateOf(false) }
    var showProximityDebugDialog by remember { mutableStateOf(false) }
    var showLogArchiveDialog by remember { mutableStateOf(false) }
    var showArchiveCleanupDialog by remember { mutableStateOf(false) }
    var showArchiveDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showTestingToolsDialog by remember { mutableStateOf(false) }
    var showResetPersistentStateDialog by remember { mutableStateOf(false) }
    var showResubmitRecentTracksDialog by remember { mutableStateOf(false) }
    var resubmitRecentTracksDaysText by remember { mutableStateOf("2") }
    var resubmitRecentTracksInProgress by remember { mutableStateOf(false) }
    var forceArchiveDirPrompt by remember { mutableStateOf(false) }
    var mqttDisabled by remember { mutableStateOf(!CaltopoClient.GetUsePeersFlag()) }
    var loadingLogArchiveDays by remember { mutableStateOf(false) }
    var sendingLogArchive by remember { mutableStateOf(false) }
    var logArchiveDays by remember { mutableStateOf(emptyList<LogArchiveDayOption>()) }
    var selectedLogArchiveDays by remember { mutableStateOf(emptySet<String>()) }
    var loadingArchiveCleanupDirs by remember { mutableStateOf(false) }
    var deletingArchiveCleanupDirs by remember { mutableStateOf(false) }
    var archiveCleanupDirs by remember { mutableStateOf(emptyList<ArchiveCleanupDirectoryOption>()) }
    var selectedArchiveCleanupDirs by remember { mutableStateOf(defaultSelectedArchiveCleanupDirectories()) }
    var archiveCleanupDeleteMessage by remember { mutableStateOf<String?>(null) }
    var showLocationOverrideDialog by remember { mutableStateOf(false) }
    var locationOverrideText by remember { mutableStateOf("") }
    var showCompliancePanel by remember { mutableStateOf(false) }
    var showSignalLossPanel by remember { mutableStateOf(false) }
    var locationOverrideError by remember { mutableStateOf<String?>(null) }
    var locationOverrideLabel by remember { mutableStateOf(formatLocationOverride(CaltopoMap.GetMyLocationOverride())) }
    val notamUiState by NotamCenter.uiState.collectAsStateWithLifecycle()
    val airspaceUiState by AirspaceCenter.uiState.collectAsStateWithLifecycle()
    val overLimitDrones by streamsViewModel.overLimitDrones.collectAsStateWithLifecycle()
    val signalLossFlights by DroneSignalLossAlertCenter.flights.collectAsStateWithLifecycle()
    val proximityDebugPairs by ProximityAlertCenter.debugPairs.collectAsState()
    val appUpdateAdvisory by AppUpdateAdvisory.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    fun refreshDriveState() {
        coroutineScope.launch {
            val email = withContext(Dispatchers.IO) {
                GoogleDriveConfigSync.getLinkedAccountEmail(context)
            }
            val offerRestore = withContext(Dispatchers.IO) {
                shouldOfferDriveRestore(context)
            }
            linkedDriveEmail = email
            showDriveRestoreDialog = offerRestore
            driveRestoreEligibilityLoaded = true
        }
    }

    LaunchedEffect(context) {
        refreshDriveState()
    }

    fun runDriveAction(accountResult: ActivityResult? = null, requestedAction: DriveSyncAction) {
        val account = if (accountResult != null) {
            try {
                GoogleSignIn.getSignedInAccountFromIntent(accountResult.data)
                    .getResult(ApiException::class.java)
            } catch (e: ApiException) {
                CaltopoClient.ShowToast("Google sign-in failed: ${e.statusCode}")
                CTError(tag, "Google sign-in failed.", e)
                null
            }
        } else {
            GoogleDriveConfigSync.getAuthorizedAccount(context)
        }

        if (account == null) {
            pendingDriveAction = requestedAction
            return
        }

        driveSyncInProgress = true
        GoogleDriveConfigSync.performAction(context, account, requestedAction) { success, message ->
            driveSyncInProgress = false
            CaltopoClient.ShowToast(message)
            refreshDriveState()
        }
    }

    val driveSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            val requestedAction = pendingDriveAction
            val orgExport = pendingOrgExport
            val faaExport = pendingFaaExport
            pendingDriveAction = null
            pendingOrgExport = false
            pendingFaaExport = false

            val account = if (result.data != null) {
                try {
                    GoogleSignIn.getSignedInAccountFromIntent(result.data)
                        .getResult(ApiException::class.java)
                } catch (e: ApiException) {
                    null
                }
            } else null

            when {
                requestedAction != null && account != null ->
                    runDriveAction(result, requestedAction)
                requestedAction != null ->
                    CaltopoClient.ShowToast("Google Drive authorization was cancelled.")
                orgExport && account != null -> {
                    showOrgExportDialog = true
                    CaltopoClient.ShowToast("Signed in to Google Drive.")
                    refreshDriveState()
                }
                orgExport ->
                    CaltopoClient.ShowToast("Google Drive authorization was cancelled.")
                faaExport && account != null -> {
                    showFaaExportDialog = true
                    CaltopoClient.ShowToast("Signed in to Google Drive.")
                    refreshDriveState()
                }
                faaExport ->
                    CaltopoClient.ShowToast("Google Drive authorization was cancelled.")
            }
        }
    )

    val importMutualAidPackageLauncher = rememberLauncherForActivityResult(
        contract = FreshOpenDocument(),
        onResult = { uri ->
            mutualAidPreviewRequestId += 1L
            val requestId = mutualAidPreviewRequestId
            if (uri == null) return@rememberLauncherForActivityResult
            pendingMutualAidImportUri = null
            pendingMutualAidImportPreview = null
            coroutineScope.launch {
                val preview = readMutualAidPackagePreviewOffMain {
                    MutualAidPackageManager.readPackagePreview(context, uri)
                }
                if (requestId != mutualAidPreviewRequestId) return@launch
                if (!preview.first || preview.second == null) {
                    CaltopoClient.ShowToast("Could not read MA package preview.")
                    return@launch
                }
                pendingMutualAidImportUri = uri
                pendingMutualAidImportPreview = preview.second
                showMutualAidImportPreviewDialog = true
            }
        }
    )

    fun startDriveAction(requestedAction: DriveSyncAction) {
        val account = GoogleDriveConfigSync.getAuthorizedAccount(context)
        if (account != null) {
            runDriveAction(requestedAction = requestedAction)
        } else {
            pendingDriveAction = requestedAction
            driveSignInLauncher.launch(GoogleDriveConfigSync.createSignInIntent(context))
        }
    }

    fun disconnectDrive() {
        driveSyncInProgress = true
        GoogleDriveConfigSync.disconnect(context) { _, message ->
            driveSyncInProgress = false
            CaltopoClient.ShowToast(message)
            refreshDriveState()
        }
    }

    if (showOrgExportDialog) {
        OrgConfigExportDialog(
            sourceOrgName = CaltopoClient.GetHomeOrgName(),
            onDismiss = { showOrgExportDialog = false },
            onUploadRequested = { callback ->
                val account = GoogleDriveConfigSync.getAuthorizedAccount(context)
                if (account != null) {
                    OrgConfigManager.uploadOrgConfig(context, account) { success, message, token ->
                        refreshDriveState()
                        callback(success, message, token)
                    }
                } else {
                    showOrgExportDialog = false
                    pendingOrgExport = true
                    driveSignInLauncher.launch(GoogleDriveConfigSync.createSignInIntent(context))
                    callback(false, "Signing in to Google Drive…", null)
                }
            }
        )
    }

    if (showFaaExportDialog) {
        FaaConfigExportDialog(
            onDismiss = { showFaaExportDialog = false },
            onUploadRequested = { callback ->
                val account = GoogleDriveConfigSync.getAuthorizedAccount(context)
                if (account != null) {
                    FaaConfigManager.uploadFaaConfig(context, account) { success, message, token ->
                        refreshDriveState()
                        callback(success, message, token)
                    }
                } else {
                    showFaaExportDialog = false
                    pendingFaaExport = true
                    driveSignInLauncher.launch(GoogleDriveConfigSync.createSignInIntent(context))
                    callback(false, "Signing in to Google Drive…", null)
                }
            }
        )
    }

    if (appUpdateAdvisory.updateRequired) {
        AlertDialog(
            onDismissRequest = { },
            text = {
                Text("Update required. Continue with limited functionality.")
            },
            confirmButton = {
                TextButton(onClick = {
                    openAppUpgradeLocation(context, appUpdateAdvisory.updateUrl)
                }) {
                    Text("Upgrade")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppUpdateAdvisory.dismissForSession()
                }) {
                    Text("Continue")
                }
            }
        )
    }

    if (showLocationOverrideDialog) {
        AlertDialog(
            onDismissRequest = {
                showLocationOverrideDialog = false
                locationOverrideError = null
            },
            title = { Text("Simulate MyLocation") },
            text = {
                Column {
                    Text("Enter `lat,lng`. Leave empty to return to device GPS. This override lasts only until the app closes.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = locationOverrideText,
                        onValueChange = {
                            locationOverrideText = it
                            locationOverrideError = null
                        },
                        label = { Text("Latitude,Longitude") },
                        singleLine = true,
                        isError = locationOverrideError != null,
                        supportingText = {
                            Text(locationOverrideError ?: "Current: $locationOverrideLabel")
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val overrideLocation = parseLocationOverride(locationOverrideText)
                        CaltopoMap.SetMyLocationOverride(overrideLocation)
                        locationOverrideLabel = formatLocationOverride(CaltopoMap.GetMyLocationOverride())
                        NotamCenter.requestImmediateRefresh()
                        CaltopoClient.ShowToast(
                            if (overrideLocation == null) "Returned to device GPS location."
                            else "Using temporary location ${formatLocationOverride(overrideLocation)}"
                        )
                        showLocationOverrideDialog = false
                        locationOverrideError = null
                    } catch (e: IllegalArgumentException) {
                        locationOverrideError = e.message
                    }
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLocationOverrideDialog = false
                    locationOverrideError = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showImportConfigDialog) {
        ImportConfigDialog(
            onDismiss = { showImportConfigDialog = false },
            onJoin = { token ->
                showImportConfigDialog = false
                OrgConfigManager.joinFromToken(context, token) { _, message ->
                    CaltopoClient.ShowToast(message)
                }
            },
            onFaaJoin = { token ->
                showImportConfigDialog = false
                FaaConfigManager.importToken(context, token) { _, message ->
                    CaltopoClient.ShowToast(message)
                    NotamCenter.requestImmediateRefresh()
                }
            },
            onMutualAidJoin = { token ->
                showImportConfigDialog = false
                MutualAidProfileManager.joinFromToken(context, token) { _, message ->
                    CaltopoClient.ShowToast(message)
                }
            },
            onPickFile = {
                showImportConfigDialog = false
                importMutualAidPackageLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        )
    }

    if (showMutualAidImportPreviewDialog) {
        val preview = pendingMutualAidImportPreview
        val expiryText = preview?.expiresAtEpochMs?.takeIf { it > 0L }?.let {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(it))
        } ?: "No expiry"
        AlertDialog(
            onDismissRequest = {
                showMutualAidImportPreviewDialog = false
                pendingMutualAidImportUri = null
                pendingMutualAidImportPreview = null
            },
            title = { Text("Import Config") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (preview != null) {
                        Text("Package: ${preview.packageName.ifBlank { "MA Package" }}")
                        Text("Source org: ${preview.sourceOrg.ifBlank { "Unknown" }}")
                        Text("Display name: ${preview.displayName.ifBlank { "Mutual Aid" }}")
                        Text("Incident: ${preview.incident.ifBlank { "Unknown" }}")
                        Text("Op period: ${preview.opPeriod.ifBlank { "Unknown" }}")
                        Text("Map: ${preview.targetMapTitle.ifBlank { preview.targetMapId.ifBlank { "Unknown" } }}")
                        Text("Expires: $expiryText")
                        Text("Offline cache: ${preview.tileCount} tile(s), ${preview.demCount} DEM tile(s)")
                    } else {
                        Text("Could not read MA package preview.")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pendingMutualAidImportUri != null && preview != null,
                    onClick = {
                        val uri = pendingMutualAidImportUri
                        showMutualAidImportPreviewDialog = false
                        pendingMutualAidImportUri = null
                        pendingMutualAidImportPreview = null
                        if (uri != null) {
                            importingMutualAidConfig = true
                            MutualAidPackageManager.importPackageAsync(context, uri) { _, message ->
                                importingMutualAidConfig = false
                                CaltopoClient.ShowToast(message)
                            }
                        }
                    }
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showMutualAidImportPreviewDialog = false
                        pendingMutualAidImportUri = null
                        pendingMutualAidImportPreview = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (importingMutualAidConfig) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Importing MA Package") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Importing offline tiles and mutual-aid settings. This may take a while for larger packages.")
                }
            },
            confirmButton = { }
        )
    }

    if (showDriveRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showDriveRestoreDialog = false },
            title = { Text("Restore From Google Drive") },
            text = {
                Text("This install looks unconfigured. Restore your saved RID2Caltopo settings from Google Drive before setting the app up again?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDriveRestoreDialog = false
                        startDriveAction(DriveSyncAction.RESTORE)
                    }
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDriveRestoreDialog = false }
                ) {
                    Text("Skip")
                }
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
    ComplianceAlertDialog(
        visible = showCompliancePanel,
        overLimitDrones = overLimitDrones,
        onDismiss = { showCompliancePanel = false },
        onToggleMuted = { mappedId, muted ->
            streamsViewModel.setComplianceAlertMuted(mappedId, muted)
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
    if (showProximityDebugDialog) {
        AlertDialog(
            onDismissRequest = { showProximityDebugDialog = false },
            confirmButton = {
                TextButton(onClick = { showProximityDebugDialog = false }) {
                    Text("Close")
                }
            },
            title = { Text("Proximity Pairs") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (proximityDebugPairs.isEmpty()) {
                        Text("No active drone pairs.")
                    } else {
                        proximityDebugPairs.forEach { pair ->
                            Text(
                                "${pair.firstMappedId} <-> ${pair.secondMappedId}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Horizontal ${"%.1f".format(pair.horizontalSeparationFt)} ft  •  " +
                                    "Vertical ${"%.1f".format(pair.verticalSeparationFt)} ft  •  " +
                                    "3D ${"%.1f".format(pair.threeDSeparationFt)} ft" +
                                    if (pair.alerting) "  •  alerting" else ""
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            },
            dismissButton = {}
        )
    }

    if (showConfirmExitDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmExitDialog = false },
            title = { Text("Confirm Exit") },
            text = { Text("Do you really want to close this application?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmExitDialog = false
                        CaltopoClient.CTEvent(tag,"QuitConfirmed", null)
                        CaltopoClient.QuitApplication()
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showConfirmExitDialog = false
                        CaltopoClient.CTEvent(tag,"QuitCancelled", null)
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Ludicrous Debugging") },
            text = { Text("This will generate a large amount of debugging information. Are you sure?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        // Reset to base level
                        CaltopoClient.SetLoggingLevel(CaltopoClient.DebugLevelError)
                        level = CaltopoClient.LoggingLevelName(CaltopoClient.DebugLevel)
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDebugTagDialog) {
        AlertDialog(
            onDismissRequest = { showDebugTagDialog = false },
            title = { Text("Debug Tag Filter") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (knownDebugTags.isNotEmpty()) {
                        Text("Known tags")
                        Spacer(Modifier.height(8.dp))
                        knownDebugTags.forEach { knownTag ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Checkbox(
                                    checked = selectedKnownTags.contains(knownTag),
                                    onCheckedChange = { checked ->
                                        selectedKnownTags = if (checked) {
                                            selectedKnownTags + knownTag
                                        } else {
                                            selectedKnownTags - knownTag
                                        }
                                    }
                                )
                                Text(knownTag, modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = customDebugTagsText,
                        onValueChange = { customDebugTagsText = it },
                        label = { Text("Custom tags (CSV)") },
                        placeholder = { Text("StreamTile,StreamPlayer,StreamsViewModel") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val csv = buildTagCsv(selectedKnownTags, customDebugTagsText)
                        parseCsvTags(customDebugTagsText).forEach { tag ->
                            CaltopoClient.RegisterDebugTag(tag)
                        }
                        CaltopoClient.SetDebugTagFilter(csv)
                        val msg = if (csv.isBlank()) {
                            "Debug tag filter disabled."
                        } else {
                            "Debug tag filter: $csv"
                        }
                        CaltopoClient.ShowToast(msg)
                        CTDebug(tag, msg)
                        showDebugTagDialog = false
                    }
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        CaltopoClient.ClearDebugTagFilter()
                        selectedKnownTags = emptySet()
                        customDebugTagsText = ""
                        CaltopoClient.ShowToast("Debug tag filter cleared.")
                        showDebugTagDialog = false
                    }
                ) { Text("Clear") }
            }
        )
    }
    var isPickerOpen by remember { mutableStateOf(false) }
    // Launcher for loading a config file
    val loadConfigFileLauncher = rememberLauncherForActivityResult(
        contract = FreshOpenDocument(),
        onResult = { uri ->
            isPickerOpen = false
            if (uri != null) {
                CTDebug(tag, "loadConfigFileLauncher() returned '${uri}'")
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(
                        uri, takeFlags
                    )
                    CTDebug(tag, "loadConfigFileLauncher() secured URI read permission.")
                    if (CaltopoClient.LoadConfigFile(uri)) {
                        localViewModel.onUIEvent((UIEvent.ConfigFileLoaded))
                    } else {
                        localViewModel.onUIEvent(UIEvent.NotAbleToReadConfigFile)
                    }
                } catch (e: Exception) {
                    CTError(tag, "loadConfigFileLauncher(): read failed: ", e)
                }
            } else {
                CTDebug(tag, "loadConfigFileLauncher() picker closed w/o selection.")
                localViewModel.onUIEvent(UIEvent.NotAbleToReadConfigFile)
            }
        }
    )

    LaunchedEffect(localViewModel.overlay) {
        if (localViewModel.overlay == OverlayState.RequestConfigFile && !isPickerOpen) {
            CTDebug(tag, "LaunchedEffect(): requesting config file...")
            isPickerOpen = true
            loadConfigFileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }


    // Launcher for selecting an archive directory
    val queryArchiveDirLauncher = rememberLauncherForActivityResult(
        contract = OpenArchiveDir(),
        onResult = { uri ->
            if (null != uri) {
                CTDebug(tag, "queryArchiveDirLauncher() returned: '${uri}'")
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                CaltopoClient.SetArchiveUri(uri)
            }
        }
    )
    LaunchedEffect(showDriveRestoreDialog, driveSyncInProgress, forceArchiveDirPrompt, driveRestoreEligibilityLoaded) {
        val archiveUriMissing = withContext(Dispatchers.IO) {
            null == CaltopoClient.GetArchiveUri()
        }
        val shouldPromptArchiveDir =
            archiveUriMissing &&
                (forceArchiveDirPrompt || (driveRestoreEligibilityLoaded && !showDriveRestoreDialog && !driveSyncInProgress))
        if (shouldPromptArchiveDir) {
            val initialUri = withContext(Dispatchers.IO) {
                CaltopoClient.GetArchiveUriSelectionHint()
            }
            val permissionMissing = CaltopoClient.WasArchiveUriPermissionMissing()
            val prompt = if (permissionMissing) {
                "Archive folder access expired. Please re-select the archive directory for tracks and map cache."
            } else {
                "Select an archive directory for drone tracks and map cache."
            }
            CaltopoClient.ShowToast(prompt)
            CTDebug(tag, "LaunchedEffect() requesting archiveDir initialUri='${initialUri ?: "<none>"}'")
            forceArchiveDirPrompt = false
            queryArchiveDirLauncher.launch(initialUri)
        }
    }
    
    // 2. Build the unified list of display items.
    val screenItems = buildList {
        add(MainScreenItem.IncidentView)
        add(MainScreenItem.SpacerView(12.dp))
        add(MainScreenItem.LocalView(localViewModel))
    }

    val openDebugTagDialog = {
        val currentFilterTags = parseCsvTags(CaltopoClient.GetDebugTagFilterCsv())
        val knownTags = (CaltopoClient.GetRegisteredDebugTags() + REQUIRED_MAP_CACHE_TAGS)
            .distinct()
            .sorted()
        val knownSet = knownTags.toSet()
        knownDebugTags = knownTags
        selectedKnownTags = currentFilterTags.filter { knownSet.contains(it) }.toSet()
        customDebugTagsText = currentFilterTags.filter { !knownSet.contains(it) }
            .joinToString(",")
        showDebugTagDialog = true
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                modifier = Modifier.pointerInput(localViewModel) {
                    detectTapGestures(
                        onDoubleTap = {
                            localViewModel.showStreams()
                        }
                    )
                },
                title = {
                    Text(
                        "RID-2-Caltopo"
                    )
                },
                actions = {
                    val allOverLimitMuted = overLimitDrones.isNotEmpty() && overLimitDrones.all { it.muted }
                    val allSignalLossMuted = signalLossFlights.isNotEmpty() && signalLossFlights.all { it.muted }
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
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(text = { Text("Live View")}, onClick = {
                            localViewModel.showStreams()
                            CaltopoClient.CTEvent(tag,"Stream Service Activated", null)
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Send app log to Ken...") }, onClick = {
                            showLogArchiveDialog = true
                            loadingLogArchiveDays = true
                            logArchiveDays = emptyList()
                            selectedLogArchiveDays = emptySet()
                            menuExpanded = false
                            coroutineScope.launch {
                                val loadedDays = availableLogArchiveDaysProvider()
                                logArchiveDays = loadedDays
                                selectedLogArchiveDays = loadedDays
                                    .filter { it.isToday }
                                    .mapTo(linkedSetOf()) { it.directoryName }
                                    .ifEmpty { loadedDays.firstOrNull()?.let { linkedSetOf(it.directoryName) } ?: linkedSetOf() }
                                loadingLogArchiveDays = false
                            }
                        })
                        if (externalDisplayConnected && onSetExternalDisplayContent != null && externalDisplayContentMode != null) {
                            DropdownMenuItem(
                                text = { Text("External: Streams View") },
                                onClick = {
                                    onSetExternalDisplayContent(ExternalDisplayContentMode.StreamsGrid)
                                    menuExpanded = false
                                }
                            )
                        }
                        DropdownMenuItem(text = { Text("Status")}, onClick = {
                            localViewModel.showScanner()
                            CaltopoClient.CTEvent(tag,"ScannersDisplayed", null)
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Release Notes") }, onClick = {
                            releaseNoteEntries = loadReleaseNotes(context)
                            showReleaseNotesDialog = true
                            CaltopoClient.CTEvent(tag,"ReleaseNotesDisplayed", null)
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = {
                            Text("LogLevel:${level}") }, onClick = {
                            CaltopoClient.BumpLoggingLevel()
                            level = CaltopoClient.LoggingLevelName(CaltopoClient.DebugLevel)
                            if (CaltopoClient.DebugLevel == CaltopoClient.DebugLevelInfo) {
                                showConfirmDialog = true;
                            }
                            menuExpanded = true
                        })
                        DropdownMenuItem(text = { Text("Proximity Pairs") }, onClick = {
                            showProximityDebugDialog = true
                            menuExpanded = false
                        })

                        DropdownMenuItem(text = { Text("Developer Tools") }, onClick = {
                            showTestingToolsDialog = true
                            menuExpanded = false
                        })
                        DropdownMenuItem(
                            text = { Text("Import Config") },
                            onClick = {
                                menuExpanded = false
                                showImportConfigDialog = true
                            }
                        )
                        DropdownMenuItem(text = { Text("Delete Archive Folders...") }, onClick = {
                            showArchiveCleanupDialog = true
                            loadingArchiveCleanupDirs = true
                            deletingArchiveCleanupDirs = false
                            archiveCleanupDirs = emptyList()
                            selectedArchiveCleanupDirs = defaultSelectedArchiveCleanupDirectories()
                            archiveCleanupDeleteMessage = null
                            menuExpanded = false
                            coroutineScope.launch {
                                archiveCleanupDirs = availableArchiveCleanupDirectoriesProvider()
                                loadingArchiveCleanupDirs = false
                            }
                        })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = {
                            localViewModel.showSettings()
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Help") }, onClick = {
                            onShowHelp()
                            CaltopoClient.CTEvent(tag,"HelpDisplayed", null)
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Quit") }, onClick = {
                            showConfirmExitDialog = true
                            CaltopoClient.CTEvent(tag,"QuitMenuSelected", null)
                            menuExpanded = false
                        })
                    }
                }
            )
        }
    ) { paddingValues ->
        // 3. Use a single LazyColumn with the robust `items` DSL and a stable key.
        Box(
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            LazyColumn(modifier = Modifier.padding(paddingValues)) {
                item(key = "notam_chip") {
                    NotamStatusChip(
                        state = notamUiState,
                        airspaceState = airspaceUiState,
                        onClick = { showNotamPanel = true }
                    )
                }
                itemsIndexed(
                    items = screenItems,
                    key = { index, item ->
                        // This key is now guaranteed to be unique and stable
                        when (item) {
                            is MainScreenItem.LocalView -> "local_view" // A constant key for the single local view
                            is MainScreenItem.SpacerView -> "spacer_view_$index"
                            is MainScreenItem.IncidentView -> "incident_view"
                        }
                    }
                ) { _, item ->
                    // 4. Use a `when` statement to render the correct composable.
                    when (item) {
                        is MainScreenItem.IncidentView -> {
                             IncidentView()
                        }
                        is MainScreenItem.LocalView -> {
                            val localDrones by item.viewModel.drones.collectAsState()
                            val appUptime by item.viewModel.appUpTime.collectAsState()
                            val hostname by item.viewModel.hostname.collectAsState()

                            R2CView(
                                hostName = hostname,
                                drones = localDrones,
                                appUptime = appUptime,
                                viewModel = item.viewModel,
                                onConfirmDrone = { drone ->
                                    item.viewModel.requestDroneConfirmation(drone)
                                }
                            )
                        }
                        is MainScreenItem.SpacerView -> {
                            HorizontalDivider(thickness = item.height)
                        }
                    }
                }
            }
        }
    }

    if (showLogArchiveDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!loadingLogArchiveDays && !sendingLogArchive) {
                    showLogArchiveDialog = false
                }
            },
            title = { Text("Select Log Days") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    if (loadingLogArchiveDays) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                            Text("Scanning archived log folders…")
                        }
                    } else if (sendingLogArchive) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                            Text("Preparing log archive…")
                        }
                    } else if (logArchiveDays.isEmpty()) {
                        Text("No archived log folders with text logs were found.")
                    } else {
                        logArchiveDays.forEach { option ->
                            val checked = selectedLogArchiveDays.contains(option.directoryName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        selectedLogArchiveDays = selectedLogArchiveDays.toMutableSet().apply {
                                            if (isChecked) add(option.directoryName) else remove(option.directoryName)
                                        }
                                    }
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(option.directoryName)
                                    Text(
                                        text = "${option.logFileCount} log file${if (option.logFileCount == 1) "" else "s"}${if (option.isToday) " • today" else ""}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sendingLogArchive = true
                        coroutineScope.launch {
                            onEmailLog(logArchiveDays.filter { selectedLogArchiveDays.contains(it.directoryName) }.map { it.directoryName })
                            CaltopoClient.CTEvent(tag,"LogEmailed", null)
                            sendingLogArchive = false
                            showLogArchiveDialog = false
                        }
                    },
                    enabled = !loadingLogArchiveDays && !sendingLogArchive && selectedLogArchiveDays.isNotEmpty() && logArchiveDays.isNotEmpty()
                ) {
                    Text("Package Logs")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLogArchiveDialog = false },
                    enabled = !loadingLogArchiveDays && !sendingLogArchive
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showReleaseNotesDialog) {
        AlertDialog(
            onDismissRequest = { showReleaseNotesDialog = false },
            title = { Text("Release Notes") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    releaseNoteEntries.forEachIndexed { index, entry ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 10.dp)
                            )
                        }
                        val title = listOf(entry.title, entry.date)
                            .filter { it.isNotBlank() }
                            .joinToString(" \u00b7 ")
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium
                        )
                        entry.changeLines.forEach { change ->
                            Row(
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(
                                    text = "\u2022",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.width(18.dp)
                                )
                                Text(
                                    text = change,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        val metadata = entry.hash.takeIf { it.isNotBlank() }?.let { "commit $it" }.orEmpty()
                        if (metadata.isNotBlank()) {
                            Text(
                                text = metadata,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReleaseNotesDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showArchiveCleanupDialog) {
        val deleteEnabled = canDeleteArchiveCleanupSelection(archiveCleanupDirs, selectedArchiveCleanupDirs)
        AlertDialog(
            onDismissRequest = {
                if (!loadingArchiveCleanupDirs && !deletingArchiveCleanupDirs) {
                    showArchiveCleanupDialog = false
                    showArchiveDeleteConfirmDialog = false
                }
            },
            title = { Text("Delete Archive Folders") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    archiveCleanupDeleteMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    if (loadingArchiveCleanupDirs) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                            Text("Scanning archive folders...")
                        }
                    } else if (deletingArchiveCleanupDirs) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                            Text("Deleting selected archive folders...")
                        }
                    } else if (archiveCleanupDirs.isEmpty()) {
                        Text("No dated archive folders were found.")
                    } else {
                        archiveCleanupDirs.forEach { option ->
                            val checked = selectedArchiveCleanupDirs.contains(option.directoryName)
                            val enabled = !option.isToday
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    enabled = enabled,
                                    onCheckedChange = { isChecked ->
                                        if (enabled) {
                                            selectedArchiveCleanupDirs = selectedArchiveCleanupDirs.toMutableSet().apply {
                                                if (isChecked) add(option.directoryName) else remove(option.directoryName)
                                            }
                                        }
                                    }
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(option.directoryName)
                                    Text(
                                        text = "Age ${option.ageLabel} • ${option.sizeLabel}${if (option.isToday) " • today" else ""}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "${option.logFileCount} log${if (option.logFileCount == 1) "" else "s"} • ${option.kmzCount} KMZ • ${option.videoCount} video${if (option.videoCount == 1) "" else "s"}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showArchiveDeleteConfirmDialog = true },
                    enabled = !loadingArchiveCleanupDirs && !deletingArchiveCleanupDirs && deleteEnabled
                ) {
                    Text("Delete...")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showArchiveCleanupDialog = false
                        showArchiveDeleteConfirmDialog = false
                    },
                    enabled = !loadingArchiveCleanupDirs && !deletingArchiveCleanupDirs
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showArchiveDeleteConfirmDialog) {
        val selectedOptions = archiveCleanupDirs
            .filter { !it.isToday && selectedArchiveCleanupDirs.contains(it.directoryName) }
        val selectedTotalBytes = selectedOptions.sumOf { it.totalBytes }
        val selectedSizeLabel = selectedOptions.firstOrNull()?.let {
            formatArchiveSize(selectedTotalBytes)
        } ?: "0 B"
        AlertDialog(
            onDismissRequest = {
                if (!deletingArchiveCleanupDirs) {
                    showArchiveDeleteConfirmDialog = false
                }
            },
            title = { Text("Confirm Archive Deletion") },
            text = {
                Column {
                    Text(
                        "Permanently delete ${selectedOptions.size} archive folder${if (selectedOptions.size == 1) "" else "s"} totaling $selectedSizeLabel?"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    selectedOptions.forEach { option ->
                        Text(
                            text = option.directoryName,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val namesToDelete = selectedOptions.map { it.directoryName }
                        showArchiveDeleteConfirmDialog = false
                        deletingArchiveCleanupDirs = true
                        archiveCleanupDeleteMessage = null
                        coroutineScope.launch {
                            val result = onDeleteArchiveDirectories(namesToDelete)
                            archiveCleanupDirs = availableArchiveCleanupDirectoriesProvider()
                            selectedArchiveCleanupDirs = defaultSelectedArchiveCleanupDirectories()
                            archiveCleanupDeleteMessage = buildString {
                                append("Deleted ${result.deletedCount} archive folder")
                                append(if (result.deletedCount == 1) "." else "s.")
                                if (result.failedDirectoryNames.isNotEmpty()) {
                                    append(" Failed: ")
                                    append(result.failedDirectoryNames.joinToString(", "))
                                }
                            }
                            CaltopoClient.ShowToast(archiveCleanupDeleteMessage ?: "Archive cleanup complete.")
                            CaltopoClient.CTEvent(tag, "ArchiveFoldersDeleted", null)
                            deletingArchiveCleanupDirs = false
                        }
                    },
                    enabled = !deletingArchiveCleanupDirs && selectedOptions.isNotEmpty()
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showArchiveDeleteConfirmDialog = false },
                    enabled = !deletingArchiveCleanupDirs
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showResubmitRecentTracksDialog) {
        val days = resubmitRecentTracksDaysText.trim().toIntOrNull()
        val daysInvalid = days == null || days < 1
        AlertDialog(
            onDismissRequest = {
                if (!resubmitRecentTracksInProgress) showResubmitRecentTracksDialog = false
            },
            title = { Text("Resubmit Recent Tracks") },
            text = {
                Column {
                    OutlinedTextField(
                        value = resubmitRecentTracksDaysText,
                        onValueChange = { resubmitRecentTracksDaysText = it },
                        label = { Text("Days") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = daysInvalid,
                        enabled = !resubmitRecentTracksInProgress,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (daysInvalid) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Enter 1 or more days.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Only matching org/team-drone tracks are uploaded.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val requestedDays = days ?: return@TextButton
                        resubmitRecentTracksInProgress = true
                        coroutineScope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    WaypointTrack.ResubmitRecentTrackStatsToTracker(requestedDays)
                                }
                                CaltopoClient.ShowToast(result.summary())
                                showResubmitRecentTracksDialog = false
                            } catch (e: Exception) {
                                CTError(tag, "Resubmit recent tracks failed.", e)
                                CaltopoClient.ShowToast("Resubmit recent tracks failed.")
                            } finally {
                                resubmitRecentTracksInProgress = false
                            }
                        }
                    },
                    enabled = !resubmitRecentTracksInProgress && !daysInvalid
                ) {
                    Text(if (resubmitRecentTracksInProgress) "Resubmitting..." else "Resubmit")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResubmitRecentTracksDialog = false },
                    enabled = !resubmitRecentTracksInProgress
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showTestingToolsDialog) {
        AlertDialog(
            onDismissRequest = { showTestingToolsDialog = false },
            title = { Text("Developer Tools") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Button(
                        onClick = {
                            showTestingToolsDialog = false
                            loadConfigFileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Load Config File")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showTestingToolsDialog = false
                            openDebugTagDialog()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val active = if (CaltopoClient.IsDebugTagFilterEnabled()) "on" else "off"
                        Text("Debug Tags ($active)")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            resubmitRecentTracksDaysText = "2"
                            showResubmitRecentTracksDialog = true
                            showTestingToolsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Resubmit Recent Tracks To Tracker")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showTestingToolsDialog = false
                            if (CaltopoClient.GetHomeOrgName().isBlank()) {
                                CaltopoClient.ShowToast("Load ct_credentials with org_name before exporting org config.")
                            } else if (GoogleDriveConfigSync.getAuthorizedAccount(context) != null) {
                                showOrgExportDialog = true
                            } else {
                                pendingOrgExport = true
                                driveSignInLauncher.launch(GoogleDriveConfigSync.createSignInIntent(context))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Export Org Config")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showTestingToolsDialog = false
                            if (!CaltopoClient.HasMutualAidTemplate()) {
                                CaltopoClient.ShowToast("Load ct_mutual_aid_credentials before exporting an MA package.")
                            } else if (CaltopoMap.GetMapId().isBlank()) {
                                CaltopoClient.ShowToast("Connect to a CalTopo map before exporting an MA package.")
                            } else {
                                MutualAidExportCoordinator.requestExportDialog()
                                localViewModel.showStreams()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Export MA Package")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val account = GoogleDriveConfigSync.getAuthorizedAccount(context)
                            showTestingToolsDialog = false
                            if (account != null) {
                                showFaaExportDialog = true
                            } else {
                                pendingFaaExport = true
                                driveSignInLauncher.launch(GoogleDriveConfigSync.createSignInIntent(context))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Publish FAA Config")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            locationOverrideText = CaltopoMap.GetMyLocationOverride()?.let {
                                "%.6f, %.6f".format(it.latitude, it.longitude)
                            }.orEmpty()
                            locationOverrideLabel = formatLocationOverride(CaltopoMap.GetMyLocationOverride())
                            locationOverrideError = null
                            showLocationOverrideDialog = true
                            showTestingToolsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Simulate MyLocation...")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showResetPersistentStateDialog = true
                            showTestingToolsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset Persisted App State")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showTestingToolsDialog = false
                            restartMediaMtxServer(context)
                            CaltopoClient.CTEvent(tag,"RestartMediaMtxServer", null)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Restart MediaMtx Server")
                    }
                    if (linkedDriveEmail.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                showTestingToolsDialog = false
                                disconnectDrive()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Disconnect Google Drive")
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Peer coordination runs automatically whenever a map is " +
                            "connected. Disable only for isolated testing — dual-write to CalTopo " +
                            "will occur if multiple instances are running.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Disable Peer Coordination", modifier = Modifier.weight(1f))
                        Switch(
                            checked = mqttDisabled,
                            onCheckedChange = { disabled ->
                                mqttDisabled = disabled
                                CaltopoClient.SetUsePeers(!disabled)
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTestingToolsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showResetPersistentStateDialog) {
        AlertDialog(
            onDismissRequest = { showResetPersistentStateDialog = false },
            title = { Text("Reset Persisted App State?") },
            text = {
                Text(
                    "This rewrites app_config.pb from a fresh default ClientClassState and updates " +
                        "future backup/restore from that clean baseline. It clears persistent rid " +
                        "mappings, credentials, profiles, archive path, loaded-config history, and " +
                        "other saved settings. Transient runtime activity may repopulate state after reset."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        CaltopoClient.ResetPersistedClientState()
                        CaltopoClient.ShowToast("Persisted app state reset and backup state updated.")
                        showResetPersistentStateDialog = false
                    }
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetPersistentStateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
