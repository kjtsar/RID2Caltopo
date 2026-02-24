package org.ncssar.rid2caltopo.video.mapcache

import androidx.documentfile.provider.DocumentFile
import java.io.File

internal sealed interface MapCacheRoot {
    data class FileBacked(val dir: File) : MapCacheRoot
    data class SafBacked(val dir: DocumentFile) : MapCacheRoot
}
