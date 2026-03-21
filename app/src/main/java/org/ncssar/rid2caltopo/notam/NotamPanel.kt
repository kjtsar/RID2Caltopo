package org.ncssar.rid2caltopo.notam

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun NotamPanel(
    state: NotamUiState,
    onDismiss: () -> Unit
) {
    val expandedIds = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Text("Nearby NOTAMs")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Radius: ${state.radiusNm} NM")
                state.lastUpdatedText?.let {
                    Text(it, color = if (state.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.statusLine.isNotBlank()) {
                    Text(state.statusLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.errorMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(10.dp))
                if (state.suppressedNoticeCount > 0) {
                    Text(
                        "Showing only notices within ${state.radiusNm} NM. ${state.suppressedNoticeCount} farther notices hidden.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (state.notices.isEmpty()) {
                    Text(
                        if (state.configured) "No nearby NOTAM restrictions were found in the current state."
                        else "NOTAM monitoring is enabled, but credentials have not been loaded yet."
                    )
                } else {
                    state.notices.forEach { notice ->
                        val expanded = expandedIds.contains(notice.id)
                        if (notice.proximityText.isNotBlank()) {
                            Text(
                                notice.proximityText,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(notice.title, fontWeight = FontWeight.SemiBold)
                        val metaText = buildString {
                            if (notice.intersectsPilotBubble) append("intersects 1 NM operating area")
                            if (notice.effectiveText.isNotBlank()) {
                                if (isNotBlank()) append(" • ")
                                append(notice.effectiveText)
                            }
                        }
                        if (metaText.isNotBlank()) {
                            Text(metaText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (notice.summary.isNotBlank()) {
                            Text(notice.summary, modifier = Modifier.padding(top = 2.dp))
                        }
                        TextButton(
                            onClick = {
                                if (expanded) {
                                    expandedIds.remove(notice.id)
                                } else {
                                    expandedIds.add(notice.id)
                                }
                            },
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(if (expanded) "Hide details" else "Show details")
                        }
                        if (expanded) {
                            if (notice.details.isNotBlank()) {
                                Text(notice.details, modifier = Modifier.padding(top = 2.dp))
                            }
                            if (notice.rawText.isNotBlank()) {
                                Text(
                                    "Raw: ${notice.rawText}",
                                    modifier = Modifier.padding(top = 2.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    )
}
