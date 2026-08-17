package org.ncssar.rid2caltopo.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Base64
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.ManagedVideoStreamAdvertisement
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

data class ManagedVideoSessionRecording(
    val sessionId: String,
    val droneDesignator: String,
    val file: File,
    val recordedAt: Instant,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Double,
    val codec: String,
)

/**
 * App-private catalog of captures made during the current incident.
 *
 * MediaMTX fragments still follow the normal archive path. A merged copy is
 * retained here across app restarts during the same incident so an authorized
 * browser can request direct WebRTC playback without cloud media storage.
 */
object ManagedVideoSessionRecordingCatalog {
    private const val TAG = "ManagedVideoRecordingCatalog"
    private const val ROOT_NAME = "managed-video-session-recordings"
    internal const val TRACK_ASSOCIATION_GRACE_MS = 30_000L
    private var initializedIncidentKey = ""

    @Synchronized
    fun initialize(context: Context) {
        val incidentKey = currentIncidentKey()
        if (initializedIncidentKey == incidentKey) return
        activeRoot(context, incidentKey).mkdirs()
        initializedIncidentKey = incidentKey
    }

    @Synchronized
    fun retain(
        context: Context,
        mergedFile: File,
        streamPath: String,
    ): File? {
        initialize(context)
        if (!mergedFile.isFile || mergedFile.length() <= 0L) return null
        val safePath = streamPath
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "recording" }
        val destination = File(activeRoot(context), "${safePath}__${mergedFile.name}")
        val temporary = File(destination.parentFile, ".${destination.name}.tmp")
        return try {
            mergedFile.copyTo(temporary, overwrite = true)
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            destination.setLastModified(System.currentTimeMillis())
            destination
        } catch (error: Exception) {
            temporary.delete()
            CaltopoClient.CTWarn(TAG, "Unable to retain current-session recording: ${error.message}")
            null
        }
    }

    @Synchronized
    fun snapshot(context: Context): List<ManagedVideoSessionRecording> {
        initialize(context)
        return activeRoot(context)
            .walkTopDown()
            .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
            .mapNotNull(::readRecording)
            .sortedByDescending { it.recordedAt }
            .take(20)
            .toList()
    }

    @Synchronized
    fun find(context: Context, sessionId: String): ManagedVideoSessionRecording? =
        snapshot(context).firstOrNull { it.sessionId == sessionId }

    @JvmStatic
    fun findForTrack(
        context: Context,
        trackStartedAtMs: Long,
        trackEndedAtMs: Long,
        vararg candidates: String,
    ): ManagedVideoSessionRecording? = selectForTrack(
        recordings = snapshot(context),
        trackStartedAtMs = trackStartedAtMs,
        trackEndedAtMs = trackEndedAtMs,
        candidates = candidates.asList(),
    )

    /**
     * Select the recording that overlaps this exact track interval. Matching
     * only the designator is unsafe because a later track can otherwise reuse
     * the previous flight's most recent recording.
     */
    internal fun selectForTrack(
        recordings: List<ManagedVideoSessionRecording>,
        trackStartedAtMs: Long,
        trackEndedAtMs: Long,
        candidates: List<String>,
    ): ManagedVideoSessionRecording? {
        if (trackStartedAtMs <= 0L || trackEndedAtMs < trackStartedAtMs) return null
        val normalized = candidates
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (normalized.isEmpty()) return null
        val allowedStart = trackStartedAtMs - TRACK_ASSOCIATION_GRACE_MS
        val allowedEnd = trackEndedAtMs + TRACK_ASSOCIATION_GRACE_MS
        return recordings
            .asSequence()
            .filter { it.droneDesignator.trim().lowercase() in normalized }
            .map { recording ->
                val recordingEndMs = recording.recordedAt.toEpochMilli()
                val recordingStartMs = recordingEndMs - recording.durationMs.coerceAtLeast(0L)
                val overlapsAllowedWindow = recordingEndMs >= allowedStart && recordingStartMs <= allowedEnd
                if (!overlapsAllowedWindow) return@map null
                val overlapMs = (
                    minOf(trackEndedAtMs, recordingEndMs) -
                        maxOf(trackStartedAtMs, recordingStartMs)
                    ).coerceAtLeast(0L)
                val boundaryDistanceMs = minOf(
                    kotlin.math.abs(recordingStartMs - trackStartedAtMs),
                    kotlin.math.abs(recordingEndMs - trackEndedAtMs),
                )
                Triple(recording, overlapMs, boundaryDistanceMs)
            }
            .filterNotNull()
            .sortedWith(
                compareByDescending<Triple<ManagedVideoSessionRecording, Long, Long>> { it.second }
                    .thenBy { it.third }
                    .thenByDescending { it.first.recordedAt }
            )
            .map { it.first }
            .firstOrNull()
    }

    fun advertisement(
        recording: ManagedVideoSessionRecording,
        thumbnail: ManagedVideoThumbnail? = null,
    ): ManagedVideoStreamAdvertisement = ManagedVideoStreamAdvertisement(
        recording.sessionId,
        recording.droneDesignator,
        recording.width,
        recording.height,
        recording.fps,
        0L,
        recording.codec,
        "recording",
        recording.recordedAt.toString(),
        recording.durationMs,
        thumbnail?.revision.orEmpty(),
        thumbnail?.jpegBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
    )

    private fun readRecording(file: File): ManagedVideoSessionRecording? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0
            val fps = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: 0.0
            val designator = file.name.substringBefore("__").ifBlank { "Recording" }
            ManagedVideoSessionRecording(
                sessionId = sessionIdForPath(file.absolutePath),
                droneDesignator = designator,
                file = file,
                recordedAt = Instant.ofEpochMilli(file.lastModified()),
                durationMs = durationMs,
                width = width,
                height = height,
                fps = fps,
                codec = "h264",
            )
        } catch (error: Exception) {
            CaltopoClient.CTWarn(TAG, "Unable to inspect ${file.name}: ${error.message}")
            null
        } finally {
            retriever.release()
        }
    }

    private fun root(context: Context): File = File(context.filesDir, ROOT_NAME)

    internal fun sessionIdForPath(path: String): String =
        UUID.nameUUIDFromBytes(path.toByteArray()).toString()

    private fun activeRoot(
        context: Context,
        incidentKey: String = currentIncidentKey(),
    ): File {
        val scopeID = UUID.nameUUIDFromBytes(incidentKey.toByteArray()).toString()
        return File(root(context), scopeID)
    }

    private fun currentIncidentKey(): String {
        val mapId = CaltopoMap.GetMapId().trim()
        if (mapId.isNotEmpty()) return "map:$mapId"
        return "incident:${CaltopoClient.GetIncident().trim().lowercase()}"
    }
}

data class ManagedVideoThumbnail(
    val revision: String,
    val jpegBytes: ByteArray,
)
