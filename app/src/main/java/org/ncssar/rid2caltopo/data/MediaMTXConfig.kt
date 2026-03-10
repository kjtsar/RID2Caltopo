package org.ncssar.rid2caltopo.data

import java.io.File

object MediaMTXConfig {
    private const val RECORD_FORMAT_FMP4 = "fmp4"

    @JvmStatic
    fun buildRuntimeConfig(
        baseConfig: String,
        captureEnabled: Boolean,
        recordingRoot: File,
    ): String {
        val normalizedBase = baseConfig.trimEnd()
        val logLevel = if (CaltopoClient.DebugLevel >= CaltopoClient.DebugLevelDebug) "debug" else "info"
        if (!captureEnabled) {
            return "$normalizedBase\nrecord: no\nlogLevel: $logLevel\n"
        }

        val recordPath = File(recordingRoot, "%path/%Y-%m-%d_%H-%M-%S-%f").absolutePath
        return buildString {
            append(normalizedBase)
            append('\n')
            append('\n')
            append("# RID2Caltopo-managed recording output.\n")
            append("record: yes\n")
            append("recordPath: '")
            append(yamlSingleQuoted(recordPath))
            append("'\n")
            append("recordFormat: ")
            append(RECORD_FORMAT_FMP4)
            append("\nlogLevel: ")
            append(logLevel)
            append('\n')
        }
    }

    private fun yamlSingleQuoted(value: String): String = value.replace("'", "''")
}
