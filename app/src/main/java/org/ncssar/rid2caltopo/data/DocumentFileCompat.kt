package org.ncssar.rid2caltopo.data

import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

/** Provider-neutral helpers for DocumentFile implementations that add MIME extensions. */
object DocumentFileCompat {
    @JvmStatic
    fun createFileWithExactName(
        parent: DocumentFile,
        mimeType: String,
        desiredName: String,
    ): DocumentFile? {
        val createName = displayNameForCreateFile(mimeType, desiredName)
        val created = parent.createFile(mimeType, createName) ?: return null
        if (created.name == desiredName) return created

        if (parent.findFile(desiredName) == null && created.renameTo(desiredName)) {
            return parent.findFile(desiredName) ?: created
        }
        return created
    }

    @JvmStatic
    fun findFileIncludingLegacyDuplicateExtension(
        parent: DocumentFile,
        mimeType: String,
        desiredName: String,
    ): DocumentFile? {
        parent.findFile(desiredName)?.let { return it }
        val extension = mimeExtension(mimeType) ?: return null
        if (!desiredName.lowercase(Locale.US).endsWith(".$extension")) return null
        val legacyName = "$desiredName.$extension"
        val legacy = parent.findFile(legacyName) ?: return null
        if (parent.findFile(desiredName) == null && legacy.renameTo(desiredName)) {
            return parent.findFile(desiredName) ?: legacy
        }
        return legacy
    }
}

internal fun displayNameForCreateFile(mimeType: String, desiredName: String): String {
    val extension = mimeExtension(mimeType) ?: return desiredName
    val suffix = ".$extension"
    return if (desiredName.lowercase(Locale.US).endsWith(suffix)) {
        desiredName.dropLast(suffix.length)
    } else {
        desiredName
    }
}

private fun mimeExtension(mimeType: String): String? {
    val normalizedMimeType = mimeType.trim().lowercase(Locale.US)
    val knownExtension = when (normalizedMimeType) {
        "text/plain" -> "txt"
        "application/json" -> "json"
        else -> null
    }
    if (knownExtension != null) return knownExtension
    return MimeTypeMap.getSingleton().getExtensionFromMimeType(normalizedMimeType)
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf { it.isNotEmpty() }
}
