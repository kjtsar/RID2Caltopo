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
    "I represent and warrant that I am authorized to request access to and use " +
        "RID2Caltopo and r2c-tracker on behalf of the organization I represent, and to " +
        "bind that organization to this acknowledgment. The organization and I accept " +
        "full responsibility for all access to, use of, reliance on, and inability to use " +
        "RID2Caltopo and r2c-tracker, including all operational, flight, navigation, safety, " +
        "communications, and incident-command decisions.\n\n" +
        "RID2Caltopo and r2c-tracker are provided on a best-effort, \"as is,\" and \"as available\" basis, " +
        "with no express or implied warranties or guarantees, including merchantability, " +
        "fitness for a particular purpose, non-infringement, suitability, reliability, " +
        "availability, accuracy, or completeness. Features and information may be unavailable, " +
        "inaccurate, incomplete, or delayed. These tools provide supplemental situational awareness " +
        "only and must not be used as the sole source for navigation, flight safety, communications, " +
        "or incident-command decisions. The organization remains responsible for independently " +
        "verifying safety-critical information.\n\n" +
        "To the fullest extent permitted by law, the organization and I release, waive, discharge, " +
        "indemnify, defend, and hold harmless UAS4SAR LLC, as the publisher and operator of the " +
        "RID2Caltopo app and r2c-tracker website, and the project's contributors, from and against " +
        "any and all claims, demands, actions, liabilities, losses, damages, judgments, costs, and " +
        "expenses, including reasonable attorneys' fees, arising out of or relating to access to, " +
        "use of, reliance on, or inability to use RID2Caltopo or r2c-tracker, including claims based " +
        "on negligence, whether known or unknown.\n\n" +
        "I understand that California Civil Code section 1542 provides: \"A general release does not " +
        "extend to claims that the creditor or releasing party does not know or suspect to exist in " +
        "his or her favor at the time of executing the release and that, if known by him or her, " +
        "would have materially affected his or her settlement with the debtor or released party.\" " +
        "The organization and I expressly waive all rights and benefits under section 1542 and accept " +
        "the risk that facts, injuries, damages, or claims may exist or later be discovered that are " +
        "unknown or unsuspected now or differ from what is currently believed.\n\n" +
        "RID2Caltopo is an independent project and is not affiliated with or endorsed by CalTopo; it " +
        "uses the CalTopo Teams API. I have read, understand, and voluntarily agree to this acknowledgment."

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
                    text = "Acknowledgment, Release, and Safety Terms",
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
