package org.ncssar.rid2caltopo.ui

import PendingClue
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SheetValue
import androidx.compose.material3.TextButton
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClueSubmissionSheet(
    pendingClue: PendingClue,
    onTitleChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onGimbalAngleChanged: (Double) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue ->
            // Prevent swipe-down dismissal
            newValue != SheetValue.Hidden
        }
    )


    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        dragHandle = null
    ) {
        ClueSheetContent(
            clue = pendingClue,
            onTitleChange = onTitleChanged,
            onDescriptionChange = onDescriptionChanged,
            onGimbalAngleChange = onGimbalAngleChanged,
            onSubmit = onSubmit,
            onCancel = onCancel
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClueSheetContent (
    clue : PendingClue,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onGimbalAngleChange: (Double) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val descriptionFocusRequester = remember { FocusRequester() }
    val titleFocusRequester = remember { FocusRequester() }
    val configuration = LocalConfiguration.current
    val maxSheetHeight = configuration.screenHeightDp.dp * 0.7f
    var descriptionFieldValue by remember(clue.designator) {
        mutableStateOf(
            TextFieldValue(
                text = clue.description,
                selection = TextRange(clue.description.length)
            )
        )
    }

    LaunchedEffect(Unit) {
        titleFocusRequester.requestFocus()
    }

    LaunchedEffect(clue.description) {
        if (clue.description != descriptionFieldValue.text) {
            descriptionFieldValue = TextFieldValue(
                text = clue.description,
                selection = TextRange(clue.description.length)
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxSheetHeight)
            .imePadding()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { focusManager.clearFocus() }
                )
            }
    ) {
        val configuration = LocalConfiguration.current
        val maxHeight = configuration.screenHeightDp.dp * 0.7f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .padding(4.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Text(
                text = "Submit Clue",
                style = MaterialTheme.typography.titleLarge
            )

            // Image preview
            BoxWithConstraints{
                val maxPreviewWidth = maxWidth * 0.5f

                clue.preview?.let { bitmap ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Clue preview",
                            modifier = Modifier
                                .widthIn(max = maxPreviewWidth)
                                .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                                .clip(MaterialTheme.shapes.medium)
                        )
                    }
                }
            }

            // Telemetry (read-only)
            Text(
                text = String.format(
                    Locale.US,
                    "Clue location: %.5f, %.5f, alt %.0f ft",
                    clue.lat,
                    clue.lng,
                    clue.alt * 3.28084
                ),
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = String.format(
                    Locale.US,
                    "Drone location: %.5f, %.5f, alt %.0f ft",
                    clue.droneLat,
                    clue.droneLng,
                    clue.droneAlt * 3.28084
                ),
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = buildString {
                    append(
                        clue.headingDeg?.let {
                            String.format(Locale.US, "Heading %.1f°", it)
                        } ?: "Heading unavailable"
                    )
                    clue.headingSourceLabel?.let {
                        append(" (")
                        append(it)
                        append(")")
                    }
                    append("  |  ")
                    append(
                        clue.aglMeters?.let {
                            String.format(Locale.US, "AGL %.0f ft", it * 3.28084)
                        } ?: "AGL unavailable"
                    )
                },
                style = MaterialTheme.typography.bodySmall
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Gimbal angle: ${clue.gimbalAngleDeg.toInt()}°",
                    style = MaterialTheme.typography.bodyLarge
                )
                Slider(
                    value = clue.gimbalAngleDeg.toFloat(),
                    onValueChange = { onGimbalAngleChange(it.toDouble()) },
                    valueRange = -90f..0f,
                    steps = 89,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "-90° = straight down. 0° = horizon.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Title
            OutlinedTextField(
                value = clue.title,
                onValueChange = onTitleChange,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier
                    .focusRequester(titleFocusRequester)
                    .fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { descriptionFocusRequester.requestFocus() },
                )
            )

            // Description
            OutlinedTextField(
                value = descriptionFieldValue,
                onValueChange = {
                    descriptionFieldValue = it
                    onDescriptionChange(it.text)
                },
                label = { Text("Description") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(descriptionFocusRequester)
                    .heightIn(min = 120.dp),
                singleLine = false,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Default
                ),
                keyboardActions = KeyboardActions(
                    onNext = { titleFocusRequester.requestFocus() },
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    onClick = {
                        val current = descriptionFieldValue
                        val start = current.selection.min
                        val end = current.selection.max
                        val updatedText = buildString {
                            append(current.text.substring(0, start))
                            append('\n')
                            append(current.text.substring(end))
                        }
                        val updatedValue = TextFieldValue(
                            text = updatedText,
                            selection = TextRange(start + 1)
                        )
                        descriptionFieldValue = updatedValue
                        onDescriptionChange(updatedText)
                    }
                ) {
                    Text("New line")
                }

                TextButton(
                    onClick = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                ) {
                    Text("Hide keyboard")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onCancel
                ) {
                    Text("Cancel")
                }

                Button(
                    modifier = Modifier.weight(1f),
                    enabled = clue.title.isNotBlank(),
                    onClick = onSubmit
                ) {
                    Text("Submit")
                }
            }
        }
    }
}
