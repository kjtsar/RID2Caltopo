package org.ncssar.rid2caltopo.data

import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn

object MediaMTXStructuredDispatcher {
    private val listeners = mutableSetOf<(MediaMTXEvent) -> Unit>()
    private val fieldRegexTemplate = "\"%s\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\""

    fun addListener(listener: (MediaMTXEvent) -> Unit) {
        listeners += listener
    }

    private fun emit(event: MediaMTXEvent) {
        listeners.forEach { it(event) }
    }

    private fun extractStringField(json: String, field: String): String? {
        val regex = Regex(fieldRegexTemplate.format(Regex.escape(field)))
        val match = regex.find(json) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }

    internal fun parseEventJson(json: String): MediaMTXEvent? {
        val type = extractStringField(json, "type") ?: return null
        return when (type) {
            "stream_connecting" -> MediaMTXEvent.StreamConnecting(extractStringField(json, "path") ?: return null)
            "stream_started" -> MediaMTXEvent.StreamStarted(
                path = extractStringField(json, "path") ?: return null,
                publisherConnId = extractStringField(json, "publisherConnId")?.takeIf { it.isNotBlank() },
            )
            "stream_publisher_handoff" -> MediaMTXEvent.StreamPublisherHandoff(
                path = extractStringField(json, "path") ?: return null,
                publisherConnId = extractStringField(json, "publisherConnId")?.takeIf { it.isNotBlank() },
            )
            "stream_stopped" -> MediaMTXEvent.StreamStopped(
                path = extractStringField(json, "path") ?: return null,
                publisherConnId = extractStringField(json, "publisherConnId")?.takeIf { it.isNotBlank() },
            )
            "stream_error" -> {
                val reason = extractStringField(json, "reason")?.takeIf { it.isNotBlank() }
                MediaMTXEvent.StreamError(
                    path = extractStringField(json, "path") ?: return null,
                    publisherConnId = extractStringField(json, "publisherConnId")?.takeIf { it.isNotBlank() },
                    reason = reason,
                )
            }
            "server_started" -> MediaMTXEvent.ServerStarted(extractStringField(json, "version") ?: return null)
            "hls_started" -> MediaMTXEvent.HlsStreamStarted(extractStringField(json, "path") ?: return null)
            else -> null
        }
    }

    @JvmStatic
    fun dispatchEventJson(json: String) {
        try {
            parseEventJson(json)?.let(::emit)
        } catch (t: Throwable) {
            CTWarn("MediaMTXStructured", "Unable to parse structured MediaMTX event: ${t.message}")
        }
    }

    internal fun resetForTests() {
        listeners.clear()
    }
}
