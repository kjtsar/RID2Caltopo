package org.ncssar.rid2caltopo.ui

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun CoroutineScope.copyPlainTextToClipboard(
    clipboard: Clipboard,
    label: String,
    text: String
) {
    launch {
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
    }
}
