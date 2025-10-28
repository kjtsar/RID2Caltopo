package org.ncssar.rid2caltopo.app

import androidx.activity.viewModels
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import org.ncssar.rid2caltopo.ui.R2CView
import org.ncssar.rid2caltopo.ui.getRootView
import org.ncssar.rid2caltopo.ui.theme.RID2CaltopoTheme
import org.opendroneid.android.Constants

import org.opendroneid.android.bluetooth.BluetoothScanner
import org.opendroneid.android.bluetooth.OpenDroneIdDataManager
import java.util.Locale
import androidx.core.net.toUri
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.ui.R2CViewModel

class R2CActivity : AppCompatActivity() {
    var locationRequest: LocationRequest? = null
    var codedPhySupported: Boolean = false
    var extendedAdvertisingSupported: Boolean = false
    var nanSupported: Boolean = false
    var wifiSupported: Boolean = false
    var locationCallback: LocationCallback? = null
    private val viewModel : R2CViewModel by viewModels()


    private val outstandingPermissionsList = ArrayList<String?>()

    private fun checkPermission(permission: String) {
        if (ActivityCompat.checkSelfPermission(
                this,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            CaltopoClient.CTDebug(
                TAG,
                String.format(Locale.US, "checkPermission(): Requesting '%s'.", permission)
            )
            outstandingPermissionsList.add(permission)
        } else {
            CaltopoClient.CTDebug(
                TAG, String.format(
                    Locale.US, "checkPermission(): '%s' granted.",
                    permission
                )
            )
        }
    }


    fun archiveTracks() {
        try {
            WaypointTrack.ArchiveTracks(this)
        } catch (e: Exception) {
            CaltopoClient.CTError(TAG, "archiveTracks() raised:", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val aircrafts by viewModel.aircrafts.collectAsState()
            val appUptime by viewModel.appUpTime.collectAsState()
            RID2CaltopoTheme {
                R2CView(
                    aircrafts = aircrafts,
                    appUptime = appUptime,
                    onShowHelp = { showHelpMenu() },
                    onShowLog = { openUri(CaltopoClient.GetDebugLogPath().toString(), "text/plain") },
                    onLoadConfigFile = { CaltopoClient.RequestLoadConfigFile() },
                    onShowVersion = { showToast(BuildConfig.BUILD_TIME) },
                    onShowSettings = { showCaltopoConfigPanel() },
                    onMappedIdChange = { aircraft, newId -> viewModel.updateMappedId(aircraft, newId) }
                )
            }
        }
        if (AppActivity != null) {
            CaltopoClient.CTDebug(TAG, "onCreate() with an existing activity.")
            if (AppActivity !== this) {
                RestartingFlag = true
                /* prevent ScanningService's PendingIntent tap from starting a new instance. */
                CaltopoClient.CTDebug(TAG, "onCreate() restarting with new activity.")
            }
        }
        AppActivity = this
        InitializedCalled = false
        CaltopoClient.InitializeForActivityAndContext(this, applicationContext)
        val archivePathVal: String? = CaltopoClient.GetArchivePath()
        if (null == archivePathVal) {
            Log.d(TAG, "Querying user for archiveDir()")
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

        val bluetoothAdapter: BluetoothAdapter? = BluetoothScanner.getBluetoothAdapter(this)
        if (null == bluetoothAdapter) {
            CTError(TAG, "Not able to access bluetooth adapter.")
            return
        }
        MyDeviceName = bluetoothAdapter.name
        CTDebug(TAG, "Setting MyDeviceName to: " + MyDeviceName)
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

    private fun showCaltopoConfigPanel() {
        val configPanel: CaltopoSettings = CaltopoSettings.newInstance()
        val transaction = supportFragmentManager.beginTransaction()
        configPanel.show(transaction, "CaltopoSettings")
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
        if (!RestartingFlag) {
            CaltopoClient.CTDebug(
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
            val view : View = if (getRootView() == null) View(null)
            else getRootView()!!

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
                AppActivity = null
                forceStopApp()
                super.onDestroy()
                return
            }
            CTDebug(TAG, "onDestroy() archiving tracks...")
            archiveTracks()
        }
        super.onDestroy()
    }


    fun forceStopApp() {
        Thread(Runnable {
            try {
                Thread.sleep(3000)
            } catch (ignored: java.lang.Exception) {
            }
            finish()
        }).start()
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
        var MyDeviceName: String? = "<unknown>"

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
        fun GetMyAppVersion(): String {
            return String.format(Locale.US,
                "%s(%s)",BuildConfig.BUILD_VERSION, BuildConfig.BUILD_TIME);
        }
    }
}

annotation class HelpMenu
