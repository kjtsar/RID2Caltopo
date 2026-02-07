/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.ncssar.rid2caltopo.data.CaltopoClient
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

@Composable
fun IncidentView() {
    var incidentState by remember { mutableStateOf(CaltopoClient.GetIncident()) }
    var opPeriodState by remember { mutableStateOf(CaltopoClient.GetOpPeriod()) }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(2.dp)
            .pointerInput(Unit) {
                // Force keyboard hide and focus clear when tapping background
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                })
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        val ofMod = Modifier.width(IntrinsicSize.Min).background(MaterialTheme.colorScheme.surface)

        // Text Field for Incident
        OutlinedTextField(
            value = incidentState,
            onValueChange = { incidentState = it },
            modifier = ofMod.onFocusChanged { focusState ->
                // "Save on Blur": If focus is lost, commit the change
                if (!focusState.isFocused) {
                    CaltopoClient.SetIncident(incidentState)
                }
            },
            label = { Text("Incident") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    CaltopoClient.SetIncident(incidentState)
                    focusManager.clearFocus()
                    keyboardController?.hide() // Explicit hide
                }
            )
        )

        // Text Field for Op Period
        OutlinedTextField(
            value = opPeriodState,
            onValueChange = { opPeriodState = it },
            modifier = ofMod.onFocusChanged { focusState ->
                if (!focusState.isFocused) {
                    CaltopoClient.SetOpPeriod(opPeriodState)
                }
            },
            label = { Text("Op Period") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    CaltopoClient.SetOpPeriod(opPeriodState)
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
            )
        )
    }
}

/***
@Composable
fun IncidentView() {
    var incidentState by remember { mutableStateOf(CaltopoClient.GetIncident()) }
    var opPeriodState by remember { mutableStateOf(CaltopoClient.GetOpPeriod()) }

    val focusManager = LocalFocusManager.current

    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(2.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {focusManager.clearFocus()})
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        val ofMod = Modifier
            .width(IntrinsicSize.Min)
            .background(MaterialTheme.colorScheme.surface)

        OutlinedTextField(
            label = { Text("Incident") },
            value = incidentState,
            modifier = ofMod,
            onValueChange = {incidentState = it},
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    CaltopoClient.SetIncident(incidentState)
                    focusManager.clearFocus() // Closes keyboard
                }
            )
        )

        OutlinedTextField(
            label = { Text("Op Period") },
            value = opPeriodState,
            modifier = ofMod,
            onValueChange = { opPeriodState = it },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    CaltopoClient.SetOpPeriod(opPeriodState)
                    focusManager.clearFocus() // Closes keyboard
                }
            )
        )
    }
}
***/