package org.ncssar.rid2caltopo.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.data.AppConfigStore
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.data.FaaConfigManager
import org.ncssar.rid2caltopo.data.R2CMqttManager
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.video.mapcache.MapCacheStartupMaintenance

class R2CApplication : Application() {
    val TAG = "R2CApplication"

    override fun onCreate() {
        super.onCreate()
        instance = this;

        // Force IPv4 preference for older stack compatibility
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("java.net.preferIPv6Addresses", "false");

        initializeCrashlyticsProbe()
        AppConfigStore.initialize(this)
        FaaConfigManager.refreshIfNeededOnStartup(this)
        NotamCenter.initialize(this)
        R2CMqttManager.InitializeNetworkAddressMonitor(this)
        MapCacheStartupMaintenance.ensureStarted(this)
        CTDebug(TAG, "onCreate().")
    }

    private fun initializeCrashlyticsProbe() {
        runCatching {
            val firebaseApp = FirebaseApp.initializeApp(this) ?: FirebaseApp.getInstance()
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCrashlyticsCollectionEnabled(true)
            crashlytics.setCustomKey("r2c_build_version", BuildConfig.BUILD_VERSION)
            crashlytics.log("R2C Crashlytics startup probe version=${BuildConfig.BUILD_VERSION}")
            CTDebug(
                TAG,
                "Crashlytics startup probe initialized app=${firebaseApp.name} version=${BuildConfig.BUILD_VERSION}"
            )
        }.onFailure { error ->
            CTError(TAG, "Crashlytics startup probe failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    companion object {
        private var instance: R2CApplication? = null;

        @JvmStatic
        fun getAppCtxt(): R2CApplication? {
            return instance;
        }
    }
}
