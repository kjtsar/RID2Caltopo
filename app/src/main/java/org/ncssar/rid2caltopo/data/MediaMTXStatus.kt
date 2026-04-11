package org.ncssar.rid2caltopo.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

object MediaMTXStatus {

    /** True while the MediaMTX server process is running. */
    var isServerRunning by mutableStateOf(false)
        private set

    /** Non-empty only after onServerExited(); cleared on next onServerStarted(). */
    var serverExitReason by mutableStateOf("")
        private set

    fun onServerStarted(version: String) {
        isServerRunning = true
        serverExitReason = ""
    }

    @JvmStatic
    fun onServerExited(exitDescription: String) {
        isServerRunning = false
        serverExitReason = exitDescription
    }

    @JvmStatic
    fun waitForExit(maxDelayInMsec: Integer) {
        // no-op for now
    }
}
