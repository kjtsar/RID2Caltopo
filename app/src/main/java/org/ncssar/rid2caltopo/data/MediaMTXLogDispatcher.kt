package org.ncssar.rid2caltopo.data

import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug

object MediaMTXLogDispatcher {
    private val TAG = "MediaMTXLogDispatcher"
    private val listeners = mutableSetOf<(MediaMTXEvent) -> Unit>()

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
        val startMatch = START_REGEX.find(line) ?: return null
        val version = startMatch.groupValues[1]
        return MediaMTXEvent.ServerStarted(version)
    }
}
