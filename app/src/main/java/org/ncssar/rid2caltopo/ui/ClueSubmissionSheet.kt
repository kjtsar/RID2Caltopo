package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.BoxWithConstraints

import PendingClue
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.SheetValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClueSubmissionSheet(
    pendingClue: PendingClue,
    onTitleChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
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
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val descriptionFocusRequester = remember { FocusRequester() }
    val titleFocusRequester = remember { FocusRequester() }
    val configuration = LocalConfiguration.current
    val maxSheetHeight = configuration.screenHeightDp.dp * 0.7f

    LaunchedEffect(clue) {
        delay(100) // avoid keyboard race condition some devices.
        titleFocusRequester.requestFocus()
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
                    "Lat %.5f, Lng %.5f, Alt %.1f m",
                    clue.lat,
                    clue.lng,
                    clue.alt
                ),
                style = MaterialTheme.typography.bodySmall
            )

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
                    onDone = { focusManager.clearFocus() }
                )
            )

            // Description
            OutlinedTextField(
                value = clue.description,
                onValueChange = onDescriptionChange,
                label = { Text("Description") },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(descriptionFocusRequester)
                    .heightIn(min = 120.dp),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onNext = { titleFocusRequester.requestFocus() },
                    onDone = {
                        focusManager.clearFocus()
                    }
                )
            )

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
