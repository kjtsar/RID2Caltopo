package org.ncssar.rid2caltopo.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

object MediaMTXStatus {

    var serverStatus by mutableStateOf<String>("\uD83D\uDFE1 Starting")
        private set

    fun onServerStarted(version: String) {
        val myIpAddress: String = R2CPeer.GetMyIpAddress(false)
        Handler(Looper.getMainLooper()).post {
            serverStatus =
                "\uD83D\uDFE2 In => rtmp://${myIpAddress}/<droneDesig>, Out => rtsp://${myIpAddress}/<droneDesig>"
        }
    }

    @JvmStatic
    fun onServerExited(exitDescription: String) {
        Handler(Looper.getMainLooper()).post {
            serverStatus = "\uD83D\uDD34 Server exited: ${exitDescription}"
        }
    }
}