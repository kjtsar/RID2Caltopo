package org.ncssar.rid2caltopo.data

import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug

data class MediaMtxParserState(
    val pendingRunOnReadyPaths: LinkedHashSet<String> = linkedSetOf(),
    val pendingRunOnReadyVerbPaths: LinkedHashSet<String> = linkedSetOf(),
)

data class MediaMtxParserResult(
    val event: MediaMTXEvent?,
    val state: MediaMtxParserState,
)

object MediaMtxLogParser {
    private val pathRegex = Regex("""\[path ([^\]]+)]\s*(.+)""")
    private val startRegex = Regex("""MediaMTX (v[0-9]+\.[0-9]+\.[0-9]+)""")
    private val hlsEventRegex = Regex("""\[HLS]\s+\[muxer ([^\]]+)]\s+(.+)""")
    private val rtspNoPublishingRegex = Regex("""no one is publishing to path '([^']+)'""")
    private val rtmpPublishingRegex =
        Regex("""\[RTMP]\s+\[conn [^\]]+]\s+is publishing to path '([^']+)'""")

    fun parseLine(state: MediaMtxParserState, line: String): MediaMtxParserResult {
        val pendingRunOnReadyPaths = LinkedHashSet(state.pendingRunOnReadyPaths)
        val pendingRunOnReadyVerbPaths = LinkedHashSet(state.pendingRunOnReadyVerbPaths)
        val trimmed = line.trim()

        if (pendingRunOnReadyPaths.size > 1 && (trimmed == "started" || trimmed == "stopped")) {
            pendingRunOnReadyPaths.clear()
        }
        if (pendingRunOnReadyPaths.size == 1) {
            val pendingPath = pendingRunOnReadyPaths.first()
            if (trimmed == "started") {
                pendingRunOnReadyPaths.clear()
                return MediaMtxParserResult(
                    MediaMTXEvent.StreamStarted(pendingPath),
                    MediaMtxParserState(pendingRunOnReadyPaths, pendingRunOnReadyVerbPaths),
                )
            }
            if (trimmed == "stopped") {
                pendingRunOnReadyPaths.clear()
                return MediaMtxParserResult(
                    MediaMTXEvent.StreamStopped(pendingPath),
                    MediaMtxParserState(pendingRunOnReadyPaths, pendingRunOnReadyVerbPaths),
                )
            }
            pendingRunOnReadyPaths.clear()
        }

        if (
            pendingRunOnReadyVerbPaths.size > 1 &&
            (trimmed == "command started" || trimmed == "command stopped")
        ) {
            pendingRunOnReadyVerbPaths.clear()
        }
        if (pendingRunOnReadyVerbPaths.size == 1) {
            val pendingVerbPath = pendingRunOnReadyVerbPaths.first()
            if (trimmed == "command started") {
                pendingRunOnReadyVerbPaths.clear()
                return MediaMtxParserResult(
                    MediaMTXEvent.StreamStarted(pendingVerbPath),
                    MediaMtxParserState(pendingRunOnReadyPaths, pendingRunOnReadyVerbPaths),
                )
            }
            if (trimmed == "command stopped") {
                pendingRunOnReadyVerbPaths.clear()
                return MediaMtxParserResult(
                    MediaMTXEvent.StreamStopped(pendingVerbPath),
                    MediaMtxParserState(pendingRunOnReadyPaths, pendingRunOnReadyVerbPaths),
                )
            }
            pendingRunOnReadyVerbPaths.clear()
        }

        val match = pathRegex.find(line)
        if (match != null) {
            val path = match.groupValues[1]
            val rem = match.groupValues[2]
            val event = when {
                rem.contains("runOnReady command started") -> MediaMTXEvent.StreamStarted(path)
                rem.contains("runOnReady command stopped") -> MediaMTXEvent.StreamStopped(path)
                rem.contains("runOnReady command") -> {
                    pendingRunOnReadyPaths += path
                    null
                }

                rem.contains("runOnReady") -> {
                    pendingRunOnReadyVerbPaths += path
                    null
                }

                rem.contains("created") -> MediaMTXEvent.StreamConnecting(path)
                rem.contains("destroyed") -> MediaMTXEvent.StreamStopped(path)
                else -> null
            }
            return MediaMtxParserResult(
                event,
                MediaMtxParserState(pendingRunOnReadyPaths, pendingRunOnReadyVerbPaths),
            )
        }

        val rtmpPublishingMatch = rtmpPublishingRegex.find(line)
        if (rtmpPublishingMatch != null) {
            val path = rtmpPublishingMatch.groupValues[1]
            pendingRunOnReadyPaths.remove(path)
            pendingRunOnReadyVerbPaths.remove(path)
            return MediaMtxParserResult(
                MediaMTXEvent.StreamStarted(path),
                MediaMtxParserState(pendingRunOnReadyPaths, pendingRunOnReadyVerbPaths),
            )
        }

        val hlsEventMatch = hlsEventRegex.find(line)
        if (hlsEventMatch != null) {
            val path = hlsEventMatch.groupValues[1]
            val rem = hlsEventMatch.groupValues[2]
            val event = when {
                rem.contains("created") -> MediaMTXEvent.HlsStreamStarted(path)
                rem.contains("destroyed") -> MediaMTXEvent.StreamStopped(path)
                else -> null
            }
            return MediaMtxParserResult(
                event,
                MediaMtxParserState(pendingRunOnReadyPaths, pendingRunOnReadyVerbPaths),
            )
        }

        val rtspNoPublishingMatch = rtspNoPublishingRegex.find(line)
        if (rtspNoPublishingMatch != null) {
            return MediaMtxParserResult(
                MediaMTXEvent.StreamStopped(rtspNoPublishingMatch.groupValues[1]),
                MediaMtxParserState(pendingRunOnReadyPaths, pendingRunOnReadyVerbPaths),
            )
        }

        val startMatch = startRegex.find(line)
        if (startMatch != null) {
            return MediaMtxParserResult(
                MediaMTXEvent.ServerStarted(startMatch.groupValues[1]),
                MediaMtxParserState(pendingRunOnReadyPaths, pendingRunOnReadyVerbPaths),
            )
        }

        return MediaMtxParserResult(
            null,
            MediaMtxParserState(pendingRunOnReadyPaths, pendingRunOnReadyVerbPaths),
        )
    }
}

object MediaMTXLogDispatcher {
    private val listeners = mutableSetOf<(MediaMTXEvent) -> Unit>()
    private var parserState = MediaMtxParserState()

    fun addListener(listener: (MediaMTXEvent) -> Unit) {
        listeners += listener
    }

    private fun emit(event: MediaMTXEvent) {
        listeners.forEach { it(event) }
    }

    fun onLogLine(line: String) {
        val result = MediaMtxLogParser.parseLine(parserState, line)
        parserState = result.state
        result.event?.let { emit(it) }
    }

    @JvmStatic
    fun dispatch(line: String) {
        CTDebug("MediaMTX", line)
        onLogLine(line)
    }
}
