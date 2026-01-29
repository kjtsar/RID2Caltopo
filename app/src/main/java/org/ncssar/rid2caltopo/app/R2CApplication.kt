package org.ncssar.rid2caltopo.app

import android.app.Application
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug

class R2CApplication : Application() {
    val TAG = "R2CApplication"

    override fun onCreate() {
        super.onCreate()
        // global initialization here
        CTDebug(TAG, "onCreate().")
    }
}
