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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
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
import org.ncssar.rid2caltopo.data.R2CMqttManager
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.ui.ActiveScreen
import org.ncssar.rid2caltopo.ui.CaltopoSettingsScreen
import org.ncssar.rid2caltopo.ui.DroneSpecConfirmationDialog
import org.ncssar.rid2caltopo.ui.MainScreen
import org.ncssar.rid2caltopo.ui.MutualAidPackageImportDialog
import org.ncssar.rid2caltopo.ui.ProximityAlertCenter
import org.ncssar.rid2caltopo.ui.ProximityAlertHost
import org.ncssar.rid2caltopo.ui.R2CPeerViewModel
import org.ncssar.rid2caltopo.ui.R2CPeerViewModelFactory
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
    private val remoteViewModels = mutableStateListOf<R2CPeerViewModel>()
    private val outstandingPermissionsList = ArrayList<String?>()
    private lateinit var localViewModel: R2CViewModel
    private lateinit var streamsViewModel: StreamsViewModel
    private var externalDisplayConfig by mutableStateOf(ExternalDisplayConfig())
    private var externalDisplayConnected by mutableStateOf(false)
    private var externalDisplayPresentation: ExternalDisplayPresentation? = null
    private var displayManager: DisplayManager? = null
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
        remoteViewModels.clear()
        remoteViewModels.addAll(peers.map { peer ->
            ViewModelProvider(this, R2CPeerViewModelFactory(peer)).get(peer.guid, R2CPeerViewModel::class.java)
        })
    }

    suspend fun listAvailableLogArchiveDays(): List<LogArchiveDayOption> = withContext(Dispatchers.IO) {
        val archiveDir = CaltopoClient.GetArchiveDir() ?: return@withContext emptyList()
        val todayDirName = "tracks-" + SimpleDateFormat("ddMMMyyyy", Locale.US).format(Date())
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

        context.startActivity(Intent.createChooser(intent, "Send Logs via..."))
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
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleR2cIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        reloadExternalDisplayConfig()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CaltopoClient.MarkAppActive()
        CTDebug(TAG, "onCreate().")
        R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator.setPeerListChangedListener(this)
        localViewModel = ViewModelProvider(
            this,
            R2CViewModelFactory(
                ScanningService.ScannerUptime
            ))[R2CViewModel::class.java]
        streamsViewModel = ViewModelProvider(this)[StreamsViewModel::class.java]
        CaltopoClient.AddDroneSpecsChangedListener(localViewModel)
        CaltopoClient.CheckIdle()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, null)
        reloadExternalDisplayConfig()

        remoteViewModels.clear()
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
                when (activeScreen) {
                    ActiveScreen.MAIN -> {
                        MainScreen(
                            localViewModel = localViewModel,
                            remoteViewModels = remoteViewModels,
                            availableLogArchiveDaysProvider = {
                                listAvailableLogArchiveDays()
                            },
                            onEmailLog = { selectedDirectoryNames ->
                                zipAndEmailSelectedLogs(localContext, selectedDirectoryNames)
                            },
                            onShowHelp = {showHelpMenu()},
                            externalDisplayConnected = externalDisplayConnected,
                            externalDisplayContentMode = externalDisplayConfig.contentMode,
                            onSetExternalDisplayContent = ::setExternalDisplayContentMode
                        )
                    }
                    ActiveScreen.SETTINGS -> {
                        CaltopoSettingsScreen(onDismiss = {
                            reloadExternalDisplayConfig()
                            localViewModel.showMain()
                        })
                    }
                    ActiveScreen.SCANNER -> {
                        ScannerScreen(onDismiss = { localViewModel.showMain() })
                    }
                    ActiveScreen.STREAMS -> {
                        StreamsScreen(
                            onBack = { localViewModel.showMain() },
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
                            localViewModel.savePendingDroneConfirmation()
                        },
                        onDismiss = {
                            localViewModel.dismissPendingDroneConfirmation()
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
                if (maPackageImportState !is org.ncssar.rid2caltopo.data.MutualAidPackageImportState.Idle) {
                    MutualAidPackageImportDialog(
                        state = maPackageImportState,
                        onDismiss = { MutualAidPackageTransferManager.dismissImportState() },
                        onCancel = { MutualAidPackageTransferManager.cancelImport() }
                    )
                }
            }
        }
        refreshExternalDisplay()
        // Handle org-config QR scan that launched or re-launched this activity.
        handleR2cIntent(intent)

        if (AppActivity != null) {
            CTDebug(TAG, "onCreate() with an existing activity.")
            if (AppActivity !== this) {
                RestartingFlag = true
                /* prevent ScanningService's PendingIntent tap from starting a new instance. */
                CTDebug(TAG, "onCreate() restarting with new activity.")
            }
        }
        AppActivity = this
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
            return
        }

        val bluetoothAdapter: BluetoothAdapter? = BluetoothScanner.getBluetoothAdapter()
        if (null == bluetoothAdapter) {
            CTError(TAG, "Not able to access bluetooth adapter.")
            return
        }
        legacyBluetoothSupported = bluetoothAdapter.isEnabled
        MyDeviceName = bluetoothAdapter.name
        CTDebug(TAG, "Setting MyDeviceName to:${MyDeviceName}")
        if (bluetoothAdapter.isLeCodedPhySupported) {
            codedPhySupported = true
        }
        if (bluetoothAdapter.isLeExtendedAdvertisingSupported) {
            extendedAdvertisingSupported = true
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

    private fun reloadExternalDisplayConfig() {
        externalDisplayConfig = ExternalDisplayPrefs.load(this)
        refreshExternalDisplay()
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
        CTDebug(
            TAG,
            "dismissExternalDisplay(returnPhoneToMain=$returnPhoneToMain, hadPresentation=$hadPresentation, " +
                "returnToPhoneOnly=${externalDisplayConfig.returnToPhoneOnlyLayoutOnDisconnect}, activeScreen=${localViewModel.activeScreen.value})"
        )
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
                mFusedLocationClient?.requestLocationUpdates(
                    locationRequest!!,
                    locationCallback!!,
                    Looper.getMainLooper()
                )
            }
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
            val scanningServiceIntent = Intent(this, ScanningService::class.java)
            applicationContext.startForegroundService(scanningServiceIntent)
        } else {
            CTDebug(TAG, "onCreate(): ScanningService already running; skipping duplicate start.")
        }
        if (!RestartingFlag || !MediaMTXService.IsRunning()) {
            CTDebug(TAG, "Starting MediaMTXService...")
            val mediaMtxServiceIntent = Intent(this, MediaMTXService::class.java)
            applicationContext.startForegroundService(mediaMtxServiceIntent)
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
        displayManager?.unregisterDisplayListener(displayListener)
        dismissExternalDisplay(returnPhoneToMain = false)
        val exitRequested = CaltopoClient.IsExitRequested()
        val shouldShutdown = (this === AppActivity) && isFinishing && exitRequested

        if (shouldShutdown) {
            try {
                stopLocationUpdates()
                R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator.setPeerListChangedListener(null)
                CTDebug(TAG,"onDestroy() shutting down streaming service..." )
                val streamServiceIntent = Intent(this, MediaMTXService::class.java)
                stopService(streamServiceIntent)
                CTDebug(TAG,"onDestroy() shutting down scanning service..." )
                val serviceIntent = Intent(this, ScanningService::class.java)
                stopService(serviceIntent)
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
