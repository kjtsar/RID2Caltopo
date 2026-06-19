package org.ncssar.rid2caltopo.notam

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.ncssar.rid2caltopo.airspace.AirspaceChipSeverity
import org.ncssar.rid2caltopo.airspace.AirspaceUiState

@Composable
fun NotamStatusChip(
    state: NotamUiState,
    airspaceState: AirspaceUiState? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
) {
    if (!state.visible && airspaceState == null) return
    val useAirspaceLabel = airspaceState != null && (
        !state.visible ||
            airspaceState.chipSeverity == AirspaceChipSeverity.Caution ||
            (airspaceState.chipSeverity == AirspaceChipSeverity.Neutral && airspaceState.errorMessage != null)
        )
    val displaySeverity = when {
        !useAirspaceLabel -> state.chipSeverity
        airspaceState?.chipSeverity == AirspaceChipSeverity.Caution -> NotamChipSeverity.Caution
        airspaceState?.chipSeverity == AirspaceChipSeverity.Normal -> NotamChipSeverity.Normal
        else -> NotamChipSeverity.Neutral
    }
    val displayLabel = if (useAirspaceLabel) {
        airspaceState?.chipLabel.orEmpty()
    } else {
        state.chipLabel
    }
    val colors = when (displaySeverity) {
        NotamChipSeverity.Danger -> AssistChipDefaults.assistChipColors(
            containerColor = Color(0xFFD32F2F),
            labelColor = Color.White
        )
        NotamChipSeverity.Caution -> AssistChipDefaults.assistChipColors(
            containerColor = Color(0xFFF57C00),
            labelColor = Color.White
        )
        NotamChipSeverity.Normal -> AssistChipDefaults.assistChipColors(
            containerColor = Color(0xFF2E7D32),
            labelColor = Color.White
        )
        NotamChipSeverity.Neutral -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Row(
        modifier = modifier.padding(outerPadding),
        horizontalArrangement = Arrangement.Start
    ) {
        AssistChip(
            onClick = onClick,
            label = { Text(displayLabel) },
            colors = colors
        )
    }
}
