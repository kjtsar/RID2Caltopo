package org.ncssar.rid2caltopo.app

import android.app.Application
import org.ncssar.rid2caltopo.data.AppConfigStore
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.R2CPeer
import org.ncssar.rid2caltopo.notam.NotamCenter

class R2CApplication : Application() {
    val TAG = "R2CApplication"

    override fun onCreate() {
        super.onCreate()
        instance = this;

        // Force IPv4 preference for older stack compatibility
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("java.net.preferIPv6Addresses", "false");

        AppConfigStore.initialize(this)
        NotamCenter.initialize(this)
        R2CPeer.InitializeNetworkAddressMonitor(this)
        CTDebug(TAG, "onCreate().")
    }

    companion object {
        private var instance: R2CApplication? = null;

        @JvmStatic
        fun getAppCtxt(): R2CApplication? {
            return instance;
        }
    }
}
