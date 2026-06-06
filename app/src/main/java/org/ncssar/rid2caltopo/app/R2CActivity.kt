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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
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
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.ui.ActiveScreen
import org.ncssar.rid2caltopo.ui.CaltopoSettingsScreen
import org.ncssar.rid2caltopo.ui.ComplianceAlertHost
import org.ncssar.rid2caltopo.ui.DroneSignalLossAlertHost
import org.ncssar.rid2caltopo.ui.DroneSpecConfirmationDialog
import org.ncssar.rid2caltopo.ui.MainScreen
import org.ncssar.rid2caltopo.ui.MutualAidPackageImportDialog
import org.ncssar.rid2caltopo.ui.ProximityAlertCenter
import org.ncssar.rid2caltopo.ui.ProximityAlertHost
import org.ncssar.rid2caltopo.ui.R2CViewModel
import org.ncssar.rid2caltopo.ui.R2CViewModelFactory
import org.ncssar.rid2caltopo.ui.ScannerScreen
import org.ncssar.rid2caltopo.ui.theme.RID2CaltopoTheme
import org.ncssar.rid2caltopo.video.StreamsScreen
import org.opendroneid.android.Constants
import org.opendroneid.android.bluetooth.BluetoothScanner
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun buildLogArchiveEntryName(rawName: String?): String {
    val baseName = rawName?.trim().orEmpty().ifBlank { "log_unknown" }
    return if (baseName.lowercase(Locale.US).endsWith(".txt")) {
        baseName
    } else {
        "$baseName.txt"
    }
}

internal fun shouldShowBluetoothDisabledPanel(
    adapterPresent: Boolean,
    bluetoothEnabled: Boolean,
): Boolean = adapterPresent && !bluetoothEnabled

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

data class LogArchiveDayOption(
    val directoryName: String,
    val logFileCount: Int,
    val lastModifiedMs: Long,
    val isToday: Boolean,
)

class R2CActivity : AppCompatActivity(), R2CMqttManager.PeerListChangedListener  {
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
            val logFiles = selectedDirs.flatMap { dir ->
                dir.listFiles()
                    .filter { it.type == "text/plain" }
                    .map { dir to it }
            }
            if (logFiles.isEmpty()) {
                CTError(TAG, "zipAndEmailSelectedLogs(): no log files found in selected dirs")
                return@withContext null
            }

            val dateTag = SimpleDateFormat("ddMMMyyyy", Locale.US).format(Date())
            val zipFile = File(context.cacheDir, "R2C_Logs_$dateTag.zip")

            try {
                ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                    val resolver = context.contentResolver
                    for ((dir, logDoc) in logFiles) {
                        val dirName = dir.name ?: "logs"
                        val entryName = "$dirName/${buildLogArchiveEntryName(logDoc.name)}"
                        resolver.openInputStream(logDoc.uri)?.use { inputStream ->
                            val entry = ZipEntry(entryName)
                            val lastModified = logDoc.lastModified()
                            if (lastModified > 0L) {
                                entry.time = lastModified
                            }
                            zos.putNextEntry(entry)
                            inputStream.copyTo(zos)
                            zos.closeEntry()
                        }
                    }
                }
                Triple(zipFile, selectedDirs.size, logFiles.size)
            } catch (e: Exception) {
                CTError(TAG, "zipAndEmailSelectedLogs(): failed to create/share zip", e)
                null
            }
        } ?: return

        val (zipFile, selectedDirCount, logFileCount) = zipResult
        val dateTag = SimpleDateFormat("ddMMMyyyy", Locale.US).format(Date())
        val sharedUri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", zipFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("kjtsar@kjt.us"))
            putExtra(
                Intent.EXTRA_SUBJECT,
                "RID2Caltopo Logs $dateTag (${selectedDirCount} day${if (selectedDirCount == 1) "" else "s"}, ${logFileCount} log${if (logFileCount == 1) "" else "s"})"
            )
            putExtra(Intent.EXTRA_STREAM, sharedUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, "Send Logs via..."))
    }

    /**
     * Handle an r2c1:// URI that was fired by the OS camera after scanning an
     * org-config QR code.  Reconstructs the R2C1: token, downloads the bundle
     * from Drive (no sign-in required), and shows a toast with the result.
     */
    private fun handleR2cIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        when (uri.scheme) {
            "r2c1" -> {
                val token = OrgConfigToken.MAGIC_PREFIX + uri.toString().removePrefix("r2c1://")
                CTDebug(TAG, "handleR2cIntent(): joining org from scanned QR")
                OrgConfigManager.joinFromToken(this, token) { _, message ->
                    showToast(message)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleR2cIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        reloadExternalDisplayConfig()
        refreshBluetoothDisabledState("resume")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
        localViewModel = ViewModelProvider(
            this,
            R2CViewModelFactory(
                ScanningService.ScannerUptime
            ))[R2CViewModel::class.java]
        streamsViewModel = ViewModelProvider(this)[StreamsViewModel::class.java]
        CaltopoClient.AddDroneSpecsChangedListener(localViewModel)
        CaltopoClient.AddDroneConfirmationCandidateListener(localViewModel)
        CaltopoClient.CheckIdle()
        if (CaltopoClient.IsExitRequested()) {
            CTDebug(TAG, "onCreate(): idle check requested app exit; skipping remaining initialization.")
            if (!isFinishing) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask()
                } else {
                    finish()
                }
            }
            return
        }
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, null)
        reloadExternalDisplayConfig()
        registerBluetoothStateReceiver()
        refreshBluetoothDisabledState("startup")

        setContent {
            RID2CaltopoTheme() {
                val localContext =  LocalContext.current
                val activeScreen by localViewModel
                    .activeScreen
                    .collectAsState()
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
                            onShowHelp = {showHelpMenu()},
                            externalDisplayConnected = externalDisplayConnected,
                            externalDisplayContentMode = externalDisplayConfig.contentMode,
                            onSetExternalDisplayContent = ::setExternalDisplayContentMode
                        )
                    }
                    ActiveScreen.SETTINGS -> {
                        CaltopoSettingsScreen(onDismiss = {
                            reloadExternalDisplayConfig(forceRecreate = true)
                            localViewModel.showMain()
                        })
                    }
                    ActiveScreen.SCANNER -> {
                        ScannerScreen(onDismiss = { localViewModel.showMain() })
                    }
                    ActiveScreen.STREAMS -> {
                        StreamsScreen(
                            onBack = { localViewModel.showMain() },
                            onMapStatusTap = { localViewModel.openConnectionOverlayFromCurrentScreen() },
                            viewModel = streamsViewModel
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
                        onQuit = { CaltopoClient.QuitApplication() },
                    )
                }
            }
        }
        refreshExternalDisplay()
        // Handle org-config QR scan that launched or re-launched this activity.
        handleR2cIntent(intent)
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

    private fun showHelpMenu() {
        val helpMenu: DeviceHelp = DeviceHelp.newInstance()
        val transaction = supportFragmentManager.beginTransaction()
        helpMenu.show(transaction, "Help")
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
                            NotamCenter.requestImmediateRefresh()
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
                            NotamCenter.requestImmediateRefresh()
                        }
                    }
                    ?.addOnFailureListener { e ->
                        CTError(TAG, "initialize(): fused lastLocation failed.", e)
                    }
            }
        } else {
            CTError(TAG, "initialize(): location permissions unavailable; fused location updates not requested.")
        }
        if (!RestartingFlag || !ScanningService.IsRunning()) {
            CTDebug(
                TAG,
                String.format(
                    Locale.US,
                    "onCreate(): Starting ScanningService from activity 0x%x",
                    this.hashCode()
                )
            )
            ScanningService.requestStart(applicationContext)
        } else {
            CTDebug(TAG, "onCreate(): ScanningService already running; skipping duplicate start.")
        }
        if (!RestartingFlag || !MediaMTXService.IsRunning()) {
            CTDebug(TAG, "Starting MediaMTXService...")
            MediaMTXService.requestStart(applicationContext)
        } else {
            CTDebug(TAG, "onCreate(): MediaMTXService already running; skipping duplicate start.")
        }
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

    public override fun onDestroy() {
        unregisterBluetoothStateReceiver()
        displayManager?.unregisterDisplayListener(displayListener)
        dismissExternalDisplay(returnPhoneToMain = false)
        val exitRequested = CaltopoClient.IsExitRequested()
        val shouldShutdown = (this === AppActivity) && isFinishing && exitRequested

        if (shouldShutdown) {
            try {
                stopLocationUpdates()
                R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator.setPeerListChangedListener(null)
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
            InitializedCalled = false
        }
        super.onDestroy()
    }

    companion object {
        const val TAG: String = "R2CActivity"
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
