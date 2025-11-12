package org.ncssar.rid2caltopo.app

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModelProvider
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
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.material.snackbar.Snackbar
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClientMap
import org.ncssar.rid2caltopo.data.WaypointTrack
import org.ncssar.rid2caltopo.ui.theme.RID2CaltopoTheme
import org.opendroneid.android.Constants

import org.opendroneid.android.bluetooth.BluetoothScanner
import org.opendroneid.android.bluetooth.OpenDroneIdDataManager
import java.util.Locale
import androidx.core.net.toUri
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.data.R2CRest
import org.ncssar.rid2caltopo.ui.CaltopoSettingsScreen
import org.ncssar.rid2caltopo.ui.CtAlertDialog
import org.ncssar.rid2caltopo.ui.MainScreen
import org.ncssar.rid2caltopo.ui.R2CRestViewModel
import org.ncssar.rid2caltopo.ui.R2CRestViewModelFactory
import org.ncssar.rid2caltopo.ui.R2CViewModel
import org.ncssar.rid2caltopo.ui.R2CViewModelFactory

class R2CActivity : AppCompatActivity(), R2CRest.ClientListChangedListener  {
    var locationRequest: LocationRequest? = null
    var codedPhySupported: Boolean = false
    var extendedAdvertisingSupported: Boolean = false
    var nanSupported: Boolean = false
    var wifiSupported: Boolean = false
    var locationCallback: LocationCallback? = null
    var mFusedLocationClient: FusedLocationProviderClient? = null
    private val remoteViewModels = mutableStateListOf<R2CRestViewModel>()
    private val outstandingPermissionsList = ArrayList<String?>()
    private val showSettingsDialog = mutableStateOf(false)
    private val showMapIdDialog = mutableStateOf(false)
    private var newMapIdForDialog = ""


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


    fun archiveTracks() {
        try {
            WaypointTrack.ArchiveTracks()
        } catch (e: Exception) {
            CTError(TAG, "archiveTracks() raised:", e)
        }
    }

    override fun onClientListChanged(clients: MutableList<R2CRest>) {
        remoteViewModels.clear()
        remoteViewModels.addAll(clients.map { client ->
            ViewModelProvider(this, R2CRestViewModelFactory(client))[R2CRestViewModel::class.java]
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val remoteClients = R2CRest.GetClientList()
        R2CRest.SetClientListChangedListener(this)
        appContext = applicationContext
        val localViewModel = ViewModelProvider(
            this,
            R2CViewModelFactory(
                CaltopoClient.GetMapId(),
                CaltopoClient.GetGroupId(),
                ScanningService.ScannerUptime
            ))[R2CViewModel::class.java]
        CaltopoClient.SetDroneSpecsChangedListener(localViewModel)
        remoteViewModels.clear()
        remoteViewModels.addAll(remoteClients.map { client ->
            ViewModelProvider(this, R2CRestViewModelFactory(client))[R2CRestViewModel::class.java]
        })
        setContent {
            if (showSettingsDialog.value) {
                CaltopoSettingsScreen(onDismiss = {showSettingsDialog.value = false})
            }
            if (showMapIdDialog.value) {
                CtAlertDialog(
                    onDismissRequest = {showMapIdDialog.value = false},
                    onConfirmation = {
                        CaltopoClient.ConfirmMapIdChange(newMapIdForDialog)
                        showMapIdDialog.value = false
                    },
                    title = "Terminate map connection",
                    text = "Do you want to terminate the existing map connection?"
                )
            }
            RID2CaltopoTheme {
                MainScreen(
                    localViewModel = localViewModel,
                    remoteViewModels = remoteViewModels,
                    onShowHelp = { showHelpMenu() },
                    onShowLog = { openUri(CaltopoClient.GetDebugLogPath().toString(),"text/plain") },
                    loadConfigFile = {loadConfigFile()},
                    onShowVersion = { showToast(BuildConfig.BUILD_TIME) },
                    onShowSettings = { showSettingsDialog.value = true},
                )
            }
        }
        if (AppActivity != null) {
            CTDebug(TAG, "onCreate() with an existing activity.")
            if (AppActivity !== this) {
                RestartingFlag = true
                /* prevent ScanningService's PendingIntent tap from starting a new instance. */
                CTDebug(TAG, "onCreate() restarting with new activity.")
            }
        }
        AppActivity = this
        InitializedCalled = false
        CaltopoClient.InitializeForActivityAndContext(this, applicationContext)
        val archivePathVal: String? = CaltopoClient.GetArchivePath()
        if (null == archivePathVal) {
            CTDebug(TAG, "Querying user for archiveDir()")
            CaltopoClient.QueryUserForArchiveDir()
        }

        DataManager = OpenDroneIdDataManager(null)

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
            ActivityCompat.requestPermissions(this, permArray, Constants.REQUEST_BULK_PERMISSIONS)
        } else {
            initialize()
        }
    }
    fun showMapIdChangeDialog(newMapId: String) {
        newMapIdForDialog = newMapId
        showMapIdDialog.value = true
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

    fun loadConfigFile() {
        try {
            CaltopoClient.RequestLoadConfigFile()
        } catch (e: Exception) {
            showToast("Error loading config file: " + e.message)
        }
    }

    private fun checkBluetoothSupport() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH) !=
            PackageManager.PERMISSION_GRANTED) {
            CTError(TAG, "checkBluetoothSupport(): Did not get access to bluetooth!")
            return
        }

        val bluetoothAdapter: BluetoothAdapter? = BluetoothScanner.getBluetoothAdapter(this)
        if (null == bluetoothAdapter) {
            CTError(TAG, "Not able to access bluetooth adapter.")
            return
        }
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
        CaltopoClient.PermissionsGrantedWeShouldBeGoodToGo()
        InitializedCalled = true

        locationRequest = LocationRequest.Builder((10 * 1000).toLong()) // 10 seconds
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis((5 * 1000).toLong()) // 5 seconds
            .build()

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    if (location != null) {
                        DataManager?.receiverLocation = location
                        CaltopoClientMap.UpdateMyLocation(location)
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
        if (!RestartingFlag) {
            CTDebug(
                TAG,
                String.format(
                    Locale.US,
                    "onCreate(): Starting ScanningService from activity 0x%x",
                    this.hashCode()
                )
            )
            val serviceIntent = Intent(this, ScanningService::class.java)
            applicationContext.startForegroundService(serviceIntent)
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
        if (this === AppActivity) {
            if (isFinishing()) {
                CTDebug(TAG,"onDestroy() shutting down scanning service..." )
                val serviceIntent = Intent(this, ScanningService::class.java)
                stopService(serviceIntent)
                CaltopoClient.Shutdown()
                CTDebug(TAG, "onDestroy() archiving tracks...")
                archiveTracks()
                AppActivity = null
                forceStopApp()
                super.onDestroy()
                return
            }
        }
        super.onDestroy()
    }

    fun forceStopApp() {
        Thread {
            try {
                Thread.sleep(3000)
            } catch (ignored: java.lang.Exception) {
            }
            finish()
        }.start()
    }

    companion object {
        const val TAG: String = "R2CActivity"
        private var AppActivity: R2CActivity? = null
        private var DataManager: OpenDroneIdDataManager? = null
        @JvmStatic
        fun getR2CActivity(): R2CActivity? {
            return AppActivity
        }

        private var RestartingFlag = false
        private var InitializedCalled = false
        @JvmField
        var MyDeviceName:String = "<unknown>"

        private var appContext: Context? = null

        @JvmStatic
        fun getAppContext(): Context? {
            return appContext
        }

        @JvmStatic
        fun getDataManager(): OpenDroneIdDataManager? {
            return DataManager
        }

        @JvmStatic
        fun getMyAppVersion(): String {
            return String.format(Locale.US,
                "%s(%s)",BuildConfig.BUILD_VERSION, BuildConfig.BUILD_TIME)
        }
    }
}
