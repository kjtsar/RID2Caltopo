package org.ncssar.rid2caltopo.data

import org.ncssar.rid2caltopo.video.StreamRegistry

object MediaMTXBootstrap {

    @JvmStatic
    fun init() {
        // Structured events are the primary lifecycle feed from MediaMTX.
        MediaMTXStructuredDispatcher.addListener { event ->
            when (event) {
                is MediaMTXEvent.StreamConnecting ->
                    StreamRegistry.onStreamConnecting(event.path)

                is MediaMTXEvent.StreamStarted ->
                    StreamRegistry.onStreamStarted(event.path, event.publisherConnId)

                is MediaMTXEvent.StreamPublisherHandoff ->
                    StreamRegistry.onStreamPublisherHandoff(event.path, event.publisherConnId)

                is MediaMTXEvent.StreamStopped ->
                    StreamRegistry.onStreamStopped(event.path, event.publisherConnId)

                is MediaMTXEvent.StreamError ->
                    StreamRegistry.onStreamError(event.path, event.reason, event.publisherConnId)

                is MediaMTXEvent.RtmpSessionClosed -> {}

                is MediaMTXEvent.ServerStarted ->
                    MediaMTXStatus.onServerStarted(event.version)

                is MediaMTXEvent.HlsStreamStarted -> {}
                //    StreamRegistry.onStreamStarted(event.path)
            }
        }
        // Raw log parsing is intentionally narrower: only use it for stop signals
        // that some controllers expose in logs before MediaMTX emits/destroys the
        // corresponding structured path state.
        MediaMTXLogDispatcher.addListener { event ->
            when (event) {
                is MediaMTXEvent.StreamStopped ->
                    StreamRegistry.onStreamStopped(event.path, event.publisherConnId)

                else -> {}
            }
        }
    }
}
