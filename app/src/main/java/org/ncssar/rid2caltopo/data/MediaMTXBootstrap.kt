package org.ncssar.rid2caltopo.data

import org.ncssar.rid2caltopo.video.StreamRegistry

object MediaMTXBootstrap {

    @JvmStatic
    fun init() {
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

                is MediaMTXEvent.ServerStarted ->
                    MediaMTXStatus.onServerStarted(event.version)

                is MediaMTXEvent.HlsStreamStarted -> {}
                //    StreamRegistry.onStreamStarted(event.path)
            }
        }
    }
}
