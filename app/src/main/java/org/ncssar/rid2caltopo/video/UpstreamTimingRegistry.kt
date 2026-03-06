package org.ncssar.rid2caltopo.video

import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug

data class UpstreamBoundaryMarker(
    val designator: String,
    val sourcePath: String,
    val boundary: String,
    val observedAtMs: Long,
    val publisherConnId: String? = null,
    val previousPublisherConnId: String? = null,
    val publisherRotated: Boolean = false,
    val rotationSequence: Long = 0L,
    val outcome: String,
    val reason: String? = null,
)

object UpstreamTimingRegistry {
    private const val TAG = "UpstreamTiming"
    private val lock = Any()
    private val latestByDesignator = mutableMapOf<String, UpstreamBoundaryMarker>()
    private val latestByPublisherConnId = mutableMapOf<String, UpstreamBoundaryMarker>()
    private val rotationSequenceByDesignator = mutableMapOf<String, Long>()

    fun recordBoundary(
        designator: String,
        sourcePath: String,
        boundary: String,
        outcome: String,
        publisherConnId: String? = null,
        previousPublisherConnId: String? = null,
        publisherRotated: Boolean = false,
        reason: String? = null,
        observedAtMs: Long = System.currentTimeMillis(),
    ): UpstreamBoundaryMarker {
        val normalizedConn = publisherConnId?.takeIf { it.isNotBlank() }
        val normalizedPreviousConn = previousPublisherConnId?.takeIf { it.isNotBlank() }
        val markerAndGap = synchronized(lock) {
            val previousMarker = latestByDesignator[designator]
            val shouldAdvanceRotation =
                publisherRotated ||
                    boundary == "stream_publisher_handoff" ||
                    (boundary == "stream_started" &&
                        normalizedConn != null &&
                        normalizedPreviousConn == null &&
                        (rotationSequenceByDesignator[designator] ?: 0L) == 0L)
            val nextRotationSequence = (rotationSequenceByDesignator[designator] ?: 0L) +
                if (shouldAdvanceRotation) 1L else 0L
            rotationSequenceByDesignator[designator] = nextRotationSequence

            val marker = UpstreamBoundaryMarker(
                designator = designator,
                sourcePath = sourcePath,
                boundary = boundary,
                observedAtMs = observedAtMs,
                publisherConnId = normalizedConn,
                previousPublisherConnId = normalizedPreviousConn,
                publisherRotated = publisherRotated,
                rotationSequence = nextRotationSequence,
                outcome = outcome,
                reason = reason?.takeIf { it.isNotBlank() },
            )
            latestByDesignator[designator] = marker
            marker.publisherConnId?.let { conn ->
                latestByPublisherConnId[conn] = marker
            }
            val sincePreviousMs = previousMarker?.let { marker.observedAtMs - it.observedAtMs }
            marker to sincePreviousMs
        }

        val marker = markerAndGap.first
        val sincePreviousMs = markerAndGap.second
        CTDebug(
            TAG,
            "Boundary marker designator=${marker.designator} sourcePath=${marker.sourcePath} " +
                "boundary=${marker.boundary} outcome=${marker.outcome} observedAtMs=${marker.observedAtMs} " +
                "sincePreviousMs=${sincePreviousMs ?: -1L} publisherConnId=${marker.publisherConnId} " +
                "previousPublisherConnId=${marker.previousPublisherConnId} " +
                "publisherRotated=${marker.publisherRotated} rotationSeq=${marker.rotationSequence} " +
                "reason=${marker.reason}"
        )
        return marker
    }

    fun latestForDesignator(designator: String): UpstreamBoundaryMarker? {
        return synchronized(lock) {
            latestByDesignator[designator]
        }
    }

    fun latestForPublisherConn(publisherConnId: String): UpstreamBoundaryMarker? {
        return synchronized(lock) {
            latestByPublisherConnId[publisherConnId]
        }
    }

    internal fun resetForTests() {
        synchronized(lock) {
            latestByDesignator.clear()
            latestByPublisherConnId.clear()
            rotationSequenceByDesignator.clear()
        }
    }
}
