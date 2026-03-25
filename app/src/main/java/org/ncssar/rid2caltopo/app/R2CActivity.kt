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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
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
import org.ncssar.rid2caltopo.data.OrgConfigManager
import org.ncssar.rid2caltopo.data.OrgConfigToken
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.R2CPeer
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.ui.ActiveScreen
import org.ncssar.rid2caltopo.ui.CaltopoSettingsScreen
import org.ncssar.rid2caltopo.ui.MainScreen
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
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class R2CActivity : AppCompatActivity(), R2CPeer.PeerListChangedListener  {
    var locationRequest: LocationRequest? = null
    var locationCallback: LocationCallback? = null
    var mFusedLocationClient: FusedLocationProviderClient? = null
    private val remoteViewModels = mutableStateListOf<R2CPeerViewModel>()
    private val outstandingPermissionsList = ArrayList<String?>()

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

    override fun onPeerListChanged(peers: MutableList<R2CPeer>) {
        remoteViewModels.clear()
        remoteViewModels.addAll(peers.map { peer ->
            ViewModelProvider(this, R2CPeerViewModelFactory(peer)).get(peer.peerName, R2CPeerViewModel::class.java)
        })
    }

    /**
     * Zip all log files (text/plain entries) from today's track directory into a
     * single archive and fire a share intent so the user can email the bundle.
     * Collects every session's log from the current calendar day, not just the
     * active one, so a multi-battery day produces one complete submission.
     */
    fun zipAndEmailAllLogs(context: Context) {
        val todaysDir: DocumentFile = CaltopoClient.GetTodaysTrackDir() ?: run {
            CTError(TAG, "zipAndEmailAllLogs(): today's track dir unavailable")
            return
        }

        // Log files are created as text/plain; other files in the dir are
        // GeoJSON tracks, JPEG snapshots, KMZ exports, etc.
        val logFiles = todaysDir.listFiles().filter { it.type == "text/plain" }
        if (logFiles.isEmpty()) {
            CTError(TAG, "zipAndEmailAllLogs(): no log files found in today's dir")
            return
        }

        val dateTag = SimpleDateFormat("ddMMMyyyy", Locale.US).format(Date())
        val zipFile = File(context.cacheDir, "R2C_Logs_$dateTag.zip")

        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                val resolver = context.contentResolver
                for (logDoc in logFiles) {
                    val entryName = (logDoc.name ?: "log_unknown") + ".txt"
                    resolver.openInputStream(logDoc.uri)?.use { inputStream ->
                        zos.putNextEntry(ZipEntry(entryName))
                        inputStream.copyTo(zos)
                        zos.closeEntry()
                    }
                }
            }

            val sharedUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", zipFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_EMAIL, arrayOf("kjtsar@kjt.us"))
                putExtra(Intent.EXTRA_SUBJECT, "RID2Caltopo Logs $dateTag (${logFiles.size} session${if (logFiles.size == 1) "" else "s"})")
                putExtra(Intent.EXTRA_STREAM, sharedUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(intent, "Send Logs via..."))
        } catch (e: Exception) {
            CTError(TAG, "zipAndEmailAllLogs(): failed to create/share zip", e)
        }
    }

    /**
     * Handle an r2c1:// URI that was fired by the OS camera after scanning an
     * org-config QR code.  Reconstructs the R2C1: token, downloads the bundle
     * from Drive (no sign-in required), and shows a toast with the result.
     */
    private fun handleR2cIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme != "r2c1") return
        val token = OrgConfigToken.MAGIC_PREFIX + uri.toString().removePrefix("r2c1://")
        CTDebug(TAG, "handleR2cIntent(): joining org from scanned QR")
        OrgConfigManager.joinFromToken(this, token) { _, message ->
            showToast(message)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleR2cIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CaltopoClient.MarkAppActive()
        CTDebug(TAG, "onCreate().")
        R2CPeer.SetPeerListChangedListener(this)
        val localViewModel = ViewModelProvider(
            this,
            R2CViewModelFactory(
                ScanningService.ScannerUptime
            ))[R2CViewModel::class.java]
        CaltopoClient.AddDroneSpecsChangedListener(localViewModel)
        CaltopoClient.CheckIdle()

        remoteViewModels.clear()
        setContent {
            RID2CaltopoTheme() {
                val localContext =  LocalContext.current
                val streamsViewModel : StreamsViewModel = viewModel()
                val activeScreen by localViewModel
                    .activeScreen
                    .collectAsState()
                when (activeScreen) {
                    ActiveScreen.MAIN -> {
                        MainScreen(
                            localViewModel = localViewModel,
                            remoteViewModels = remoteViewModels,
                            onEmailLog = {
                                zipAndEmailAllLogs(localContext)
                            },
                            onShowHelp = {showHelpMenu()}
                        )
                    }
                    ActiveScreen.SETTINGS -> {
                        CaltopoSettingsScreen(onDismiss = { localViewModel.showMain() })
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
                        localViewModel.showStreams()
                        ProximityAlertCenter.suspendCurrentAlert()
                    }
                )
            }
        }
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
        val exitRequested = CaltopoClient.IsExitRequested()
        val shouldShutdown = (this === AppActivity) && isFinishing && exitRequested

        if (shouldShutdown) {
            try {
                stopLocationUpdates()
                R2CPeer.SetPeerListChangedListener(null)
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
