package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun IncidentView(
    incident: String,
    opPeriod: String
) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(3.dp)
            .height(25.dp)
    ) {
        Text(
            text = "Incident:",
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
            textAlign = TextAlign.Center,
            fontSize = 18.sp
        )
        Text(
            text = "${incident}, ",
            modifier = Modifier
                .width(100.dp)
                .background(MaterialTheme.colorScheme.surface),
            textAlign = TextAlign.Center,
            fontSize = 18.sp
        )
        Text(
            text = "Op Period:",
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
            textAlign = TextAlign.Center,
            fontSize = 18.sp
        )
        Text(
            text = opPeriod,
            modifier = Modifier
                .width(100.dp)
                .background(MaterialTheme.colorScheme.surface),
            textAlign = TextAlign.Center,
            fontSize = 18.sp
        )
    }
}
