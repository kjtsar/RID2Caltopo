package org.ncssar.rid2caltopo.data

import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug

data class MediaMtxParserState(
    val pendingRunOnReadyPaths: LinkedHashSet<String> = linkedSetOf(),
    val pendingRunOnReadyVerbPaths: LinkedHashSet<String> = linkedSetOf(),
    val rtmpConnPathMap: Map<String, String> = emptyMap(),
    val pathPublisherConnMap: Map<String, String> = emptyMap(),
    val suppressStopForPath: LinkedHashSet<String> = linkedSetOf(),
    val publisherHandoffClosingConns: LinkedHashSet<String> = linkedSetOf(),
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
        Regex("""\[RTMP]\s+\[conn ([^\]]+)]\s+is publishing to path '([^']+)'""")
    private val rtmpClosedRegex =
        Regex("""\[RTMP]\s+\[conn ([^\]]+)]\s+closed:\s*(.+)""")

    fun parseLine(state: MediaMtxParserState, line: String): MediaMtxParserResult {
        val pendingRunOnReadyPaths = LinkedHashSet(state.pendingRunOnReadyPaths)
        val pendingRunOnReadyVerbPaths = LinkedHashSet(state.pendingRunOnReadyVerbPaths)
        val rtmpConnPathMap = state.rtmpConnPathMap.toMutableMap()
        val pathPublisherConnMap = state.pathPublisherConnMap.toMutableMap()
        val suppressStopForPath = LinkedHashSet(state.suppressStopForPath)
        val publisherHandoffClosingConns = LinkedHashSet(state.publisherHandoffClosingConns)
        val trimmed = line.trim()

        fun updatedState() = MediaMtxParserState(
            pendingRunOnReadyPaths = pendingRunOnReadyPaths,
            pendingRunOnReadyVerbPaths = pendingRunOnReadyVerbPaths,
            rtmpConnPathMap = rtmpConnPathMap,
            pathPublisherConnMap = pathPublisherConnMap,
            suppressStopForPath = suppressStopForPath,
            publisherHandoffClosingConns = publisherHandoffClosingConns,
        )

        fun emitStop(path: String): MediaMTXEvent? {
            if (suppressStopForPath.contains(path)) return null
            return MediaMTXEvent.StreamStopped(path)
        }

        fun normalizeRtmpCloseReason(reason: String): String {
            if (reason.contains("extended chunk stream IDs are not supported", ignoreCase = true)) {
                return "RTMP closed: publisher uses extended chunk stream IDs unsupported by current MediaMTX"
            }
            if (reason.contains("unexpected EOF", ignoreCase = true)) {
                return "RTMP closed: publisher disconnected unexpectedly"
            }
            return "RTMP closed: $reason"
        }

        val match = pathRegex.find(line)
        if (match != null) {
            val path = match.groupValues[1]
            val rem = match.groupValues[2]
            val event = when {
                rem.contains("runOnReady command") -> {
                    pendingRunOnReadyPaths.remove(path)
                    pendingRunOnReadyVerbPaths.remove(path)
                    null
                }

                rem.contains("runOnReady") -> {
                    pendingRunOnReadyPaths.remove(path)
                    pendingRunOnReadyVerbPaths.remove(path)
                    null
                }

                rem.contains("closing existing publisher") -> {
                    suppressStopForPath.add(path)
                    pathPublisherConnMap[path]?.let { publisherHandoffClosingConns.add(it) }
                    MediaMTXEvent.StreamPublisherHandoff(path)
                }

                rem.contains("created") -> {
                    suppressStopForPath.remove(path)
                    MediaMTXEvent.StreamConnecting(path)
                }
                rem.contains("destroyed") -> {
                    pathPublisherConnMap.remove(path)?.let { publisherHandoffClosingConns.remove(it) }
                    emitStop(path)
                }
                else -> null
            }
            return MediaMtxParserResult(
                event,
                updatedState(),
            )
        }

        val rtmpPublishingMatch = rtmpPublishingRegex.find(line)
        if (rtmpPublishingMatch != null) {
            val conn = rtmpPublishingMatch.groupValues[1]
            val path = rtmpPublishingMatch.groupValues[2]
            val previousPathForConn = rtmpConnPathMap[conn]
            val previousConnForPath = pathPublisherConnMap[path]
            val isDuplicatePublisherLine =
                previousPathForConn == path && previousConnForPath == conn

            if (previousPathForConn != null &&
                previousPathForConn != path &&
                pathPublisherConnMap[previousPathForConn] == conn
            ) {
                pathPublisherConnMap.remove(previousPathForConn)
            }
            rtmpConnPathMap[conn] = path
            pathPublisherConnMap[path] = conn
            pendingRunOnReadyPaths.remove(path)
            pendingRunOnReadyVerbPaths.remove(path)
            suppressStopForPath.remove(path)
            if (isDuplicatePublisherLine) {
                return MediaMtxParserResult(
                    null,
                    updatedState(),
                )
            }
            return MediaMtxParserResult(
                MediaMTXEvent.StreamStarted(path),
                updatedState(),
            )
        }

        val rtmpClosedMatch = rtmpClosedRegex.find(line)
        if (rtmpClosedMatch != null) {
            val conn = rtmpClosedMatch.groupValues[1]
            val reason = rtmpClosedMatch.groupValues[2].trim()
            val path = rtmpConnPathMap.remove(conn)
            if (path != null) {
                if (pathPublisherConnMap[path] == conn) {
                    pathPublisherConnMap.remove(path)
                }
                suppressStopForPath.add(path)
                if (publisherHandoffClosingConns.remove(conn) &&
                    reason.equals("terminated", ignoreCase = true)
                ) {
                    return MediaMtxParserResult(
                        null,
                        updatedState(),
                    )
                }
                return MediaMtxParserResult(
                    MediaMTXEvent.StreamError(path = path, reason = normalizeRtmpCloseReason(reason)),
                    updatedState(),
                )
            }
        }

        val hlsEventMatch = hlsEventRegex.find(line)
        if (hlsEventMatch != null) {
            val path = hlsEventMatch.groupValues[1]
            val rem = hlsEventMatch.groupValues[2]
            val event = when {
                rem.contains("created") -> MediaMTXEvent.HlsStreamStarted(path)
                rem.contains("destroyed") -> emitStop(path)
                else -> null
            }
            return MediaMtxParserResult(
                event,
                updatedState(),
            )
        }

        val rtspNoPublishingMatch = rtspNoPublishingRegex.find(line)
        if (rtspNoPublishingMatch != null) {
            pathPublisherConnMap.remove(rtspNoPublishingMatch.groupValues[1])?.let {
                publisherHandoffClosingConns.remove(it)
            }
            return MediaMtxParserResult(
                emitStop(rtspNoPublishingMatch.groupValues[1]),
                updatedState(),
            )
        }

        val startMatch = startRegex.find(line)
        if (startMatch != null) {
            return MediaMtxParserResult(
                MediaMTXEvent.ServerStarted(startMatch.groupValues[1]),
                updatedState(),
            )
        }

        return MediaMtxParserResult(
            null,
            updatedState(),
        )
    }
}

object MediaMTXLogDispatcher {
    private val listeners = mutableSetOf<(MediaMTXEvent) -> Unit>()
    private var parserState = MediaMtxParserState()
    private var pendingChunkFragment = ""

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

    internal fun dispatchChunk(
        chunk: String,
        shouldLog: Boolean,
        flushTrailingFragment: Boolean = true,
    ) {
        val combined = pendingChunkFragment + chunk
        val endsWithNewline = combined.endsWith('\n') || combined.endsWith('\r')
        val rawLines = combined.split(Regex("""\r?\n"""))
        val completeLines = if (endsWithNewline || flushTrailingFragment) rawLines else rawLines.dropLast(1)
        pendingChunkFragment = if (endsWithNewline || flushTrailingFragment) "" else rawLines.lastOrNull().orEmpty()

        completeLines
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { chunkLine ->
                if (shouldLog) {
                    CTDebug("MediaMTX", chunkLine)
                }
                onLogLine(chunkLine)
            }
    }

    @JvmStatic
    fun dispatch(line: String) {
        dispatchChunk(line, shouldLog = true, flushTrailingFragment = true)
    }

    internal fun resetForTests() {
        listeners.clear()
        parserState = MediaMtxParserState()
        pendingChunkFragment = ""
    }
}
