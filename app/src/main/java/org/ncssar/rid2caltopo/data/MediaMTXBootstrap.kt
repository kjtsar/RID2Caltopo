package org.ncssar.rid2caltopo.data

import org.ncssar.rid2caltopo.video.StreamRegistry

object MediaMTXBootstrap {

    @JvmStatic
    fun init() {
        MediaMTXLogDispatcher.addListener { event ->
            when (event) {
                is MediaMTXEvent.StreamConnecting ->
                    StreamRegistry.onStreamConnecting(event.path)

                is MediaMTXEvent.StreamStarted ->
                    StreamRegistry.onStreamStarted(event.path)

                is MediaMTXEvent.StreamStopped ->
                    StreamRegistry.onStreamStopped(event.path)

                is MediaMTXEvent.StreamError ->
                    StreamRegistry.onStreamError(event.path)

                is MediaMTXEvent.ServerStarted ->
                    MediaMTXStatus.onServerStarted(event.version)

                is MediaMTXEvent.HlsStreamStarted -> {}
                //    StreamRegistry.onStreamStarted(event.path)
            }
        }
    }
}
