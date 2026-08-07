package org.ncssar.rid2caltopo.ui

import android.content.Context
import java.io.IOException

internal const val RELEASE_NOTES_ASSET = "release_notes.txt"
internal const val RELEASE_NOTES_FALLBACK = "Release notes unavailable for this build."
internal val RELEASE_NOTES_FALLBACK_ENTRY = ReleaseNoteEntry(
    hash = "",
    date = "",
    title = "Release notes unavailable",
    detail = "This build did not include versioned release notes."
)

internal data class ReleaseNoteEntry(
    val hash: String,
    val date: String,
    val title: String,
    val detail: String
) {
    val changeLines: List<String>
        get() = detail.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
}

internal fun parseReleaseNotes(raw: String?): List<ReleaseNoteEntry> {
    val unifiedEntries = parseUnifiedReleaseNotes(raw)
    if (unifiedEntries.isNotEmpty()) return unifiedEntries

    val entries = raw
        ?.split('\u001E')
        ?.flatMap { record ->
            if (record.contains('\t')) {
                listOf(record.trim())
            } else {
                record.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            }
        }
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.mapNotNull(::parseReleaseNoteLine)
        ?.toList()
        .orEmpty()
    return entries.ifEmpty { listOf(RELEASE_NOTES_FALLBACK_ENTRY) }
}

private val unifiedReleaseNoteSections = setOf(
    "Latest changes",
    "Platform-specific changes",
    "Known platform differences"
)

private fun parseUnifiedReleaseNotes(raw: String?): List<ReleaseNoteEntry> {
    val lines = raw?.lineSequence()?.map(String::trim)?.toList().orEmpty()
    if (lines.none { it.removeSuffix(":") in unifiedReleaseNoteSections }) return emptyList()

    val entries = mutableListOf<ReleaseNoteEntry>()
    var sectionTitle: String? = null
    val changes = mutableListOf<String>()

    fun finishSection() {
        val title = sectionTitle ?: return
        if (changes.isNotEmpty()) {
            entries += ReleaseNoteEntry(
                hash = "",
                date = "",
                title = title,
                detail = changes.joinToString("\n")
            )
        }
        changes.clear()
    }

    lines.forEach { line ->
        val possibleSection = line.removeSuffix(":")
        if (possibleSection in unifiedReleaseNoteSections) {
            finishSection()
            sectionTitle = possibleSection
        } else if (sectionTitle != null && line.isNotEmpty()) {
            changes += line.removePrefix("-").trim()
        }
    }
    finishSection()
    return entries
}

private fun parseReleaseNoteLine(line: String): ReleaseNoteEntry? {
    val parts = line.split('\t', limit = 3)
    val parsedParts = if (parts.size == 3) {
        parts
    } else {
        line.split(' ', limit = 3)
    }
    if (parsedParts.size != 3) {
        return null
    }

    val subject = parsedParts[2].trim()
    val (title, detail) = splitReleaseNoteSubject(subject)

    return ReleaseNoteEntry(
        hash = parsedParts[0].trim(),
        date = parsedParts[1].trim(),
        title = title.ifEmpty { subject },
        detail = detail
    )
}

private fun splitReleaseNoteSubject(subject: String): Pair<String, String> {
    val titleAndDetail = subject.split(": ", limit = 2)
    if (titleAndDetail.size == 2) {
        return titleAndDetail[0].trim().removeSuffix(":") to titleAndDetail[1].cleanReleaseNoteDetail()
    }

    val lines = subject.lineSequence().toList()
    val title = lines.firstOrNull()?.trim()?.removeSuffix(":").orEmpty()
    val detail = lines.drop(1).joinToString("\n").cleanReleaseNoteDetail()
    return title.ifEmpty { subject } to detail
}

private fun String.cleanReleaseNoteDetail(): String {
    return lineSequence()
        .map { line ->
            line.trim()
                .removePrefix("-")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

internal fun loadReleaseNotes(context: Context): List<ReleaseNoteEntry> {
    return try {
        context.assets.open(RELEASE_NOTES_ASSET).bufferedReader().use { reader ->
            parseReleaseNotes(reader.readText())
        }
    } catch (_: IOException) {
        listOf(RELEASE_NOTES_FALLBACK_ENTRY)
    }
}
