package org.ncssar.rid2caltopo.video

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridge
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

object ManagedVideoThumbnailStore {
    private const val WIDTH = 320
    private const val HEIGHT = 180
    private const val QUALITY = 72
    private val thumbnails = ConcurrentHashMap<String, ManagedVideoThumbnail>()

    fun get(sessionId: String): ManagedVideoThumbnail? = thumbnails[sessionId]

    suspend fun capture(sessionId: String, decoderSessionId: Long): ManagedVideoThumbnail? =
        withContext(Dispatchers.IO) {
            val frame = CompletableDeferred<ByteArray>()
            val lease = FfmpegBridge.startRemoteVideoFrames(
                decoderSessionId,
                WIDTH,
                HEIGHT,
                1.0,
                FfmpegBridge.RemoteVideoFramePurpose.THUMBNAIL,
            ) { callbackSessionId, width, height, _, i420 ->
                if (callbackSessionId == decoderSessionId && !frame.isCompleted) {
                    frame.complete(i420ToJpeg(width, height, i420))
                }
            }
            if (lease == null) return@withContext null
            try {
                val jpeg = withTimeoutOrNull(2_500L) { frame.await() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@withContext null
                // CalTopo and intervening caches need a different URL for every
                // capture, even when a stationary frame compresses identically.
                val revision = System.currentTimeMillis().toString()
                ManagedVideoThumbnail(revision, jpeg).also {
                    thumbnails[sessionId] = it
                }
            } finally {
                FfmpegBridge.stopRemoteVideoFrames(lease)
            }
        }

    private fun i420ToJpeg(width: Int, height: Int, i420: ByteArray): ByteArray {
        if (width <= 0 || height <= 0) return byteArrayOf()
        val yBytes = width * height
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val chromaBytes = chromaWidth * chromaHeight
        if (i420.size < yBytes + chromaBytes * 2) return byteArrayOf()
        val nv21 = ByteArray(yBytes + chromaBytes * 2)
        i420.copyInto(nv21, 0, 0, yBytes)
        val uOffset = yBytes
        val vOffset = yBytes + chromaBytes
        for (index in 0 until chromaBytes) {
            nv21[yBytes + index * 2] = i420[vOffset + index]
            nv21[yBytes + index * 2 + 1] = i420[uOffset + index]
        }
        return ByteArrayOutputStream().use { output ->
            val compressed = YuvImage(
                nv21,
                ImageFormat.NV21,
                width,
                height,
                null,
            ).compressToJpeg(Rect(0, 0, width, height), QUALITY, output)
            if (compressed) output.toByteArray() else byteArrayOf()
        }
    }
}
