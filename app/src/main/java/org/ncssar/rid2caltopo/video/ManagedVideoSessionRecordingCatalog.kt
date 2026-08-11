package org.ncssar.rid2caltopo.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Base64
import org.ncssar.rid2caltopo.data.CaltopoClient
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
 * App-private catalog of captures made during this process lifetime.
 *
 * MediaMTX fragments still follow the normal archive path. A merged copy is
 * retained here only until the next app process starts so an authorized
 * browser can request direct WebRTC playback without cloud media storage.
 */
object ManagedVideoSessionRecordingCatalog {
    private const val TAG = "ManagedVideoRecordingCatalog"
    private const val ROOT_NAME = "managed-video-session-recordings"
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val root = root(context)
        if (root.exists()) {
            root.deleteRecursively()
        }
        root.mkdirs()
        initialized = true
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
        val destination = File(root(context), "${safePath}__${mergedFile.name}")
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
        return root(context)
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
    fun findLatestForDesignator(
        context: Context,
        vararg candidates: String,
    ): ManagedVideoSessionRecording? {
        val normalized = candidates
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (normalized.isEmpty()) return null
        return snapshot(context).firstOrNull {
            it.droneDesignator.trim().lowercase() in normalized
        }
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
                sessionId = UUID.nameUUIDFromBytes(file.absolutePath.toByteArray()).toString(),
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
}

data class ManagedVideoThumbnail(
    val revision: String,
    val jpegBytes: ByteArray,
)
