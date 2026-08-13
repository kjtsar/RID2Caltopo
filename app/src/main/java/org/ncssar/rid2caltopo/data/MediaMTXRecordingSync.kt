package org.ncssar.rid2caltopo.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.ncssar.rid2caltopo.video.ManagedVideoSessionRecordingCatalog

object MediaMTXRecordingSync {
    private const val TAG = "MediaMTXRecordingSync"
    private const val DEFAULT_SAMPLE_BUFFER_SIZE = 1024 * 1024

    @JvmStatic
    fun getRecordingStagingDir(context: Context): File =
        File(context.filesDir, "mediamtx-recordings")

    @JvmStatic
    fun syncAll(context: Context, streamPath: String? = null) {
        val todaysTrackDir = CaltopoClient.GetTodaysTrackDir() ?: return
        val stagingRoot = getRecordingStagingDir(context)
        if (!stagingRoot.exists()) return

        try {
            if (streamPath.isNullOrBlank()) {
                syncDirectoryTree(context, todaysTrackDir, stagingRoot, "")
            } else {
                syncStreamPath(context, todaysTrackDir, File(stagingRoot, streamPath), streamPath)
            }
            deleteEmptyDirectories(stagingRoot)
        } catch (e: Exception) {
            CaltopoClient.CTError(TAG, "syncAll() raised for path=$streamPath", e)
        }
    }

    private fun syncDirectoryTree(
        context: Context,
        todaysTrackDir: DocumentFile,
        root: File,
        relativePath: String,
    ) {
        val children = root.listFiles()?.sortedBy { it.name } ?: return
        val mediaFiles = children.filter { it.isFile && isMediaFragment(it) }
        if (mediaFiles.isNotEmpty()) {
            syncStreamPath(context, todaysTrackDir, root, relativePath)
        }
        children
            .filter { it.isDirectory }
            .forEach { child ->
                val childRelative = if (relativePath.isBlank()) child.name else "$relativePath/${child.name}"
                syncDirectoryTree(context, todaysTrackDir, child, childRelative)
            }
    }

    private fun syncStreamPath(
        context: Context,
        todaysTrackDir: DocumentFile,
        sourceRoot: File,
        streamPath: String,
    ) {
        if (!sourceRoot.exists()) return
        val fragments = sourceRoot.listFiles()
            ?.filter { it.isFile && isMediaFragment(it) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (fragments.isEmpty()) return

        val targetRoot = ensureTargetDir(todaysTrackDir, streamPath)
        val mergedFile = tempMergedRecordingFile(context, streamPath, fragments.first())

        try {
            remuxMp4Sequence(fragments, mergedFile)
            copyFileInto(
                resolver = context.contentResolver,
                sourceFile = mergedFile,
                targetDir = targetRoot,
                targetName = archiveRecordingFileName(streamPath, fragments.first()),
            )
            ManagedVideoSessionRecordingCatalog.retain(
                context = context,
                mergedFile = mergedFile,
                streamPath = streamPath,
            )
            fragments.forEach { fragment ->
                if (!fragment.delete()) {
                    CaltopoClient.CTWarn(TAG, "Unable to delete staged fragment ${fragment.absolutePath}")
                }
            }
        } finally {
            if (mergedFile.exists() && !mergedFile.delete()) {
                CaltopoClient.CTWarn(TAG, "Unable to delete temp merged recording ${mergedFile.absolutePath}")
            }
        }
        deleteEmptyDirectories(sourceRoot)
    }

    private fun remuxMp4Sequence(inputFiles: List<File>, outputFile: File) {
        val firstInput = inputFiles.firstOrNull()
            ?: throw IllegalArgumentException("No input files to remux.")
        val firstLayout = readTrackLayout(firstInput)
        if (firstLayout.trackIndices.isEmpty()) {
            throw IllegalStateException("No audio/video tracks found in ${firstInput.absolutePath}")
        }

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTrackMap = LinkedHashMap<Int, Int>()
        firstLayout.trackIndices.forEach { trackIndex ->
            muxerTrackMap[trackIndex] = muxer.addTrack(firstLayout.formats.getValue(trackIndex))
        }
        muxer.start()

        try {
            val buffer = ByteBuffer.allocateDirect(maxSampleBufferSize(inputFiles, firstLayout.trackIndices))
            var nextSegmentOffsetUs = 0L
            inputFiles.forEach { inputFile ->
                nextSegmentOffsetUs = appendMp4IntoMuxer(
                    inputFile = inputFile,
                    muxer = muxer,
                    muxerTrackMap = muxerTrackMap,
                    buffer = buffer,
                    segmentOffsetUs = nextSegmentOffsetUs,
                )
            }
        } finally {
            muxer.stop()
            muxer.release()
        }
    }

    private fun appendMp4IntoMuxer(
        inputFile: File,
        muxer: MediaMuxer,
        muxerTrackMap: Map<Int, Int>,
        buffer: ByteBuffer,
        segmentOffsetUs: Long,
    ): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)
        muxerTrackMap.keys.forEach(extractor::selectTrack)

        return try {
            var segmentFirstPtsUs = Long.MIN_VALUE
            var lastWrittenPtsUs = segmentOffsetUs
            val bufferInfo = android.media.MediaCodec.BufferInfo()

            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break
                val muxerTrackIndex = muxerTrackMap[trackIndex]
                if (muxerTrackIndex == null) {
                    extractor.advance()
                    continue
                }

                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (segmentFirstPtsUs == Long.MIN_VALUE) {
                    segmentFirstPtsUs = if (sampleTimeUs >= 0L) sampleTimeUs else 0L
                }
                var presentationTimeUs = segmentOffsetUs + maxOf(0L, sampleTimeUs - segmentFirstPtsUs)
                if (presentationTimeUs <= lastWrittenPtsUs) {
                    presentationTimeUs = lastWrittenPtsUs + 1L
                }
                bufferInfo.set(
                    0,
                    sampleSize,
                    presentationTimeUs,
                    extractor.sampleFlags,
                )
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                lastWrittenPtsUs = presentationTimeUs
                extractor.advance()
            }

            lastWrittenPtsUs + 1L
        } finally {
            extractor.release()
        }
    }

    private fun readTrackLayout(inputFile: File): TrackLayout {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)
        return try {
            val trackIndices = mutableListOf<Int>()
            val formats = LinkedHashMap<Int, MediaFormat>()
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mimeType = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mimeType.startsWith("video/") && !mimeType.startsWith("audio/")) continue
                trackIndices += trackIndex
                formats[trackIndex] = format
            }
            TrackLayout(trackIndices = trackIndices, formats = formats)
        } finally {
            extractor.release()
        }
    }

    private fun maxSampleBufferSize(inputFiles: List<File>, trackIndices: List<Int>): Int {
        var maxSize = DEFAULT_SAMPLE_BUFFER_SIZE
        inputFiles.forEach { inputFile ->
            val extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)
            try {
                trackIndices.forEach { trackIndex ->
                    if (trackIndex >= extractor.trackCount) return@forEach
                    val format = extractor.getTrackFormat(trackIndex)
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxSize = maxOf(maxSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }
            } finally {
                extractor.release()
            }
        }
        return maxSize
    }

    private fun tempMergedRecordingFile(context: Context, streamPath: String, firstFragment: File): File {
        val timestamp = recordingTimestamp(firstFragment)
        val designator = archiveDesignator(streamPath)
        val safeTimestamp = timestamp.ifBlank { fallbackTimestamp() }
        val fileName = "${designator}_${safeTimestamp}.tmp.mp4"
        return File(getMergedRecordingRoot(context), "$streamPath/$fileName").apply {
            parentFile?.mkdirs()
        }
    }

    private fun getMergedRecordingRoot(context: Context): File =
        File(context.filesDir, "mediamtx-recordings-merged")

    private fun archiveRecordingFileName(streamPath: String, firstFragment: File): String {
        val designator = archiveDesignator(streamPath)
        val timestamp = recordingTimestamp(firstFragment).ifBlank { fallbackTimestamp() }
        return "${designator}_${timestamp}.mp4"
    }

    private fun archiveDesignator(streamPath: String): String =
        streamPath.substringAfterLast('/').ifBlank { "recording" }

    private fun recordingTimestamp(firstFragment: File): String {
        return archiveTimestampFromFragmentName(firstFragment.name).orEmpty()
    }

    private fun fallbackTimestamp(): String =
        SimpleDateFormat("ddMMMyyyy_HHmmss", Locale.US).format(Date())

    internal fun archiveTimestampFromFragmentName(name: String): String? {
        Regex("""(?:^|_)(\d{2}[A-Z][a-z]{2}\d{4})_(\d{6})(?:-|\.)""")
            .find(name)
            ?.let { return "${it.groupValues[1]}_${it.groupValues[2]}" }
        val match = Regex(
            """(?:^|_)(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})"""
        ).find(name) ?: return null
        val month = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        ).getOrNull(match.groupValues[2].toIntOrNull()?.minus(1) ?: -1) ?: return null
        return buildString {
            append(match.groupValues[3])
            append(month)
            append(match.groupValues[1])
            append('_')
            append(match.groupValues.drop(4).joinToString(separator = ""))
        }
    }

    private fun ensureTargetDir(root: DocumentFile, streamPath: String): DocumentFile {
        var current = root
        streamPath
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { segment ->
                current = current.findFile(segment)
                    ?: current.createDirectory(segment)
                    ?: throw IllegalStateException("Unable to create recording directory '$segment'")
            }
        return current
    }

    private fun copyFileInto(
        resolver: ContentResolver,
        sourceFile: File,
        targetDir: DocumentFile,
        targetName: String = sourceFile.name,
    ) {
        val existing = targetDir.findFile(targetName)
        val target = existing ?: targetDir.createFile(mimeTypeFor(targetName), targetName)
        if (target == null) {
            throw IllegalStateException("Unable to create recording file '$targetName'")
        }

        FileInputStream(sourceFile).use { input ->
            resolver.openOutputStream(target.uri, "w")?.use { output ->
                input.copyTo(output)
            } ?: throw IllegalStateException("Unable to open output stream for '$targetName'")
        }
    }

    private fun deleteEmptyDirectories(root: File): Boolean {
        if (!root.exists()) return true
        if (root.isFile) return false

        var isEmpty = true
        root.listFiles()?.forEach { child ->
            val childEmpty = if (child.isDirectory) deleteEmptyDirectories(child) else false
            if (child.isDirectory && childEmpty) {
                if (!child.delete()) {
                    isEmpty = false
                }
            } else {
                isEmpty = false
            }
        }
        return isEmpty
    }

    private fun isMediaFragment(file: File): Boolean =
        mimeTypeFor(file.name).startsWith("video/")

    private fun mimeTypeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp4", "fmp4" -> "video/mp4"
            "ts" -> "video/mp2t"
            else -> "application/octet-stream"
        }

    private data class TrackLayout(
        val trackIndices: List<Int>,
        val formats: Map<Int, MediaFormat>,
    )
}
