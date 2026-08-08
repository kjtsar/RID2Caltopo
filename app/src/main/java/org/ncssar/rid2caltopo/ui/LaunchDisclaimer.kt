/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal const val LAUNCH_DISCLAIMER_TEXT =
    "RID2Caltopo is provided on a best-effort, \"as is,\" and \"as available\" basis, " +
        "with no express or implied warranties or guarantees, including merchantability, " +
        "fitness for a particular purpose, non-infringement, suitability, reliability, " +
        "availability, accuracy, or completeness. Features and information may be unavailable, " +
        "inaccurate, incomplete, or delayed. This app provides supplemental situational awareness " +
        "only and must not be used as the sole source for navigation, flight safety, communications, " +
        "or incident-command decisions. By selecting I agree, I accept responsibility for safe use " +
        "and for independently verifying safety-critical information."

@Composable
fun LaunchDisclaimerScreen(
    onAgree: () -> Unit,
    onDisagree: () -> Unit,
) {
    BackHandler(onBack = onDisagree)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Safety and Best-Effort Disclaimer",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = LAUNCH_DISCLAIMER_TEXT,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onAgree,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("I agree")
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onDisagree,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("I disagree")
                }
            }
        }
    }
}
