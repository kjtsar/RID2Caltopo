package org.ncssar.rid2caltopo.data

import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug

object MediaMTXLogDispatcher {
    private val TAG = "MediaMTXLogDispatcher"
    private val listeners = mutableSetOf<(MediaMTXEvent) -> Unit>()
    private val pendingRunOnReadyPaths = linkedSetOf<String>()
    private val pendingRunOnReadyVerbPaths = linkedSetOf<String>()

    fun addListener(listener: (MediaMTXEvent) -> Unit) {
        listeners += listener
    }

    private fun emit(event: MediaMTXEvent) {
        listeners.forEach { it(event) }
    }

    fun onLogLine(line: String) {
        parseStreamLifecycle(line)?.let { emit(it) }
    }

    @JvmStatic
    fun dispatch(line: String) {
        // 1️⃣ Always log
        CTDebug("MediaMTX", line)

        // 2️⃣ Try stream lifecycle parsing
        onLogLine(line)
    }

    private fun parseStreamLifecycle(line: String): MediaMTXEvent? {
        val PATH_REGEX =
            Regex("""\[path ([^\]]+)]\s*(.+)""")

        val START_REGEX =
            Regex("""MediaMTX (v[0-9]+\.[0-9]+\.[0-9]+)""")

        val HLS_EVENT_REGEX =
            Regex("""\[HLS]\s+\[muxer ([^\]]+)]\s+(.+)""")
        val RTSP_NO_PUBLISHING_REGEX =
            Regex("""no one is publishing to path '([^']+)'""")
        val RTMP_PUBLISHING_REGEX =
            Regex("""\[RTMP]\s+\[conn [^\]]+]\s+is publishing to path '([^']+)'""")

        val trimmed = line.trim()
        if (pendingRunOnReadyPaths.size > 1 && (trimmed == "started" || trimmed == "stopped")) {
            pendingRunOnReadyPaths.clear()
        }
        if (pendingRunOnReadyPaths.size == 1) {
            val pendingPath = pendingRunOnReadyPaths.first()
            if (trimmed == "started") {
                pendingRunOnReadyPaths.clear()
                return MediaMTXEvent.StreamStarted(pendingPath)
            }
            if (trimmed == "stopped") {
                pendingRunOnReadyPaths.clear()
                return MediaMTXEvent.StreamStopped(pendingPath)
            }
            pendingRunOnReadyPaths.clear()
        }
        if (pendingRunOnReadyVerbPaths.size > 1 &&
            (trimmed == "command started" || trimmed == "command stopped")) {
            pendingRunOnReadyVerbPaths.clear()
        }
        if (pendingRunOnReadyVerbPaths.size == 1) {
            val pendingVerbPath = pendingRunOnReadyVerbPaths.first()
            if (trimmed == "command started") {
                pendingRunOnReadyVerbPaths.clear()
                return MediaMTXEvent.StreamStarted(pendingVerbPath)
            }
            if (trimmed == "command stopped") {
                pendingRunOnReadyVerbPaths.clear()
                return MediaMTXEvent.StreamStopped(pendingVerbPath)
            }
            pendingRunOnReadyVerbPaths.clear()
        }

        val match = PATH_REGEX.find(line)
        if (match != null) {
            val path = match.groupValues[1]
            val rem  = match.groupValues[2]

            return when {
                rem.contains("runOnReady command started") ->
                    MediaMTXEvent.StreamStarted(path)

                rem.contains("runOnReady command stopped") ->
                    MediaMTXEvent.StreamStopped(path)

                rem.contains("runOnReady command") -> {
                    pendingRunOnReadyPaths += path
                    null
                }

                rem.contains("runOnReady") -> {
                    pendingRunOnReadyVerbPaths += path
                    null
                }

                rem.contains("created") ->
                    MediaMTXEvent.StreamConnecting(path)

                rem.contains("destroyed") ->
                    MediaMTXEvent.StreamStopped(path)

                else -> null
            }
        }

        val rtmpPublishingMatch = RTMP_PUBLISHING_REGEX.find(line)
        if (rtmpPublishingMatch != null) {
            val path = rtmpPublishingMatch.groupValues[1]
            pendingRunOnReadyPaths.remove(path)
            pendingRunOnReadyVerbPaths.remove(path)
            return MediaMTXEvent.StreamStarted(path)
        }

        val hlsEventMatch = HLS_EVENT_REGEX.find(line)
        if (hlsEventMatch != null) {
            val path = hlsEventMatch.groupValues[1]
            val rem = hlsEventMatch.groupValues[2]
            return when {
                rem.contains("created") -> MediaMTXEvent.HlsStreamStarted(path)
                rem.contains("destroyed") -> MediaMTXEvent.StreamStopped(path)
                else -> null
            }
        }

        val rtspNoPublishingMatch = RTSP_NO_PUBLISHING_REGEX.find(line)
        if (rtspNoPublishingMatch != null) {
            return MediaMTXEvent.StreamStopped(rtspNoPublishingMatch.groupValues[1])
        }

        val startMatch = START_REGEX.find(line)
        if (startMatch != null) {
            return MediaMTXEvent.ServerStarted(startMatch.groupValues[1])
        }

        return null
    }
/***

    private fun parseStreamLifecycle(line: String): MediaMTXEvent? {
        val PATH_REGEX =
            Regex("""\[path ([^\]]+)]\s*(.+)""")
        val START_REGEX =
            Regex("""MediaMTX (v[0-9]+\.[0-9]+\.[0-9]+)""")
        val HLS_START_REGEX =
            Regex("""\[HLS\]\s+\[muxer ([^\]]+])\s+ created""")
        val match = PATH_REGEX.find(line)
        if (null != match) {
            val path = match.groupValues[1]
            val rem = match.groupValues[2]
            return when {
                rem.contains("created") ->
                    MediaMTXEvent.StreamConnecting(path)

                rem.contains("runOnReady") ->
                    MediaMTXEvent.StreamStarted(path)

                rem.contains("destroyed") ->
                    MediaMTXEvent.StreamStopped(path)

                else -> null
            }
        }
        val hlsStartMatch = HLS_START_REGEX.find(line)
        if (null != hlsStartMatch) {
            return MediaMTXEvent.HlsStreamStarted(hlsStartMatch.groupValues[1]);
        }
        val startMatch = START_REGEX.find(line)
        if (null != startMatch) {
            val version = startMatch.groupValues[1]
            return MediaMTXEvent.ServerStarted(version)
        }
        return null;
    }
***/
}
