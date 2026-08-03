package org.ncssar.rid2caltopo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.EditableRidMapping
import org.ncssar.rid2caltopo.data.RidMappingRules
import java.io.File

private data class RidMappingDraft(
    val key: Long,
    var remoteId: String,
    var ownerName: String,
    var ownerCallsign: String,
    var model: String
)

@Composable
fun RidMappingAdminDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var organization by remember { mutableStateOf(CaltopoClient.GetHomeOrgName()) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanTargetIndex by remember { mutableStateOf<Int?>(null) }
    var showScanChoices by remember { mutableStateOf(false) }
    var capturedPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    var ocrCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var nextKey by remember { mutableStateOf(1L) }
    val mappings = remember {
        mutableStateListOf<RidMappingDraft>().also { drafts ->
            CaltopoClient.GetPersistedDroneSpecs().forEach { spec ->
                val ownerFields = RidMappingRules.resolveOwnerFields(
                    ownerName = spec.ownerName,
                    ownerCallsign = spec.owner,
                    legacyOwner = spec.owner,
                    mappedId = spec.mappedId,
                    model = spec.model,
                    remoteId = spec.remoteId
                )
                drafts += RidMappingDraft(
                    key = nextKey++,
                    remoteId = spec.remoteId,
                    ownerName = ownerFields.ownerName,
                    ownerCallsign = ownerFields.ownerCallsign,
                    model = spec.model
                )
            }
        }
    }
    val barcodeScanner = remember(context) {
        GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
        )
    }
    val textRecognizer = remember {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    DisposableEffect(textRecognizer) {
        onDispose { textRecognizer.close() }
    }
    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captured ->
        val uri = capturedPhoto
        if (!captured || uri == null) return@rememberLauncherForActivityResult
        val image = runCatching { InputImage.fromFilePath(context, uri) }
            .getOrElse {
                error = "Unable to read the serial-number photo."
                return@rememberLauncherForActivityResult
            }
        textRecognizer.process(image)
            .addOnSuccessListener { recognized ->
                val candidates = extractRemoteIdCandidates(recognized.text)
                if (candidates.isEmpty()) {
                    error = "No likely Remote ID was found. Move closer, improve lighting, and try again."
                } else {
                    ocrCandidates = candidates
                }
            }
            .addOnFailureListener {
                error = "Unable to recognize the printed Remote ID: ${it.localizedMessage ?: "unknown error"}"
            }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.9f),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("RID Map Entries", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "Entries imported from the organization QR code can be reviewed or edited here. " +
                                "Organization is stored once and applied to every aircraft. " +
                                "Mapped ID is generated from owner callsign and model.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = organization,
                            onValueChange = { organization = it },
                            label = { Text("Organization designator") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        mappings.forEachIndexed { index, draft ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp)
                                ) {
                                    Text("Aircraft ${index + 1}", style = MaterialTheme.typography.titleSmall)
                                    OutlinedTextField(
                                        value = draft.remoteId,
                                        onValueChange = {
                                            mappings[index] = draft.copy(remoteId = it.uppercase())
                                        },
                                        label = { Text("Remote ID") },
                                        singleLine = true,
                                        trailingIcon = {
                                            IconButton(onClick = {
                                                scanTargetIndex = index
                                                showScanChoices = true
                                            }) {
                                                Icon(
                                                    imageVector = Icons.Filled.DocumentScanner,
                                                    contentDescription = "Scan Remote ID"
                                                )
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = draft.ownerName,
                                        onValueChange = { mappings[index] = draft.copy(ownerName = it) },
                                        label = { Text("Owner name") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = draft.ownerCallsign,
                                        onValueChange = { mappings[index] = draft.copy(ownerCallsign = it) },
                                        label = { Text("Owner callsign (for example 1SAR7)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = draft.model,
                                        onValueChange = { mappings[index] = draft.copy(model = it) },
                                        label = { Text("Model") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        "Drone designator: " + EditableRidMapping(
                                            draft.remoteId,
                                            draft.ownerName,
                                            draft.ownerCallsign,
                                            draft.model
                                        ).mappedId(),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    TextButton(onClick = { mappings.removeAt(index) }) {
                                        Text("Remove aircraft")
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        OutlinedButton(
                            onClick = {
                                mappings += RidMappingDraft(nextKey++, "", "", "", "")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Add aircraft") }
                        error?.let {
                            HorizontalDivider()
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(onClick = {
                            val values = mappings.map {
                                EditableRidMapping(it.remoteId, it.ownerName, it.ownerCallsign, it.model)
                            }
                            val errors = RidMappingRules.validate(organization, values)
                            if (errors.isEmpty()) {
                                CaltopoClient.ReplacePersistedDroneSpecs(organization, values)
                                onDismiss()
                            } else {
                                error = errors.joinToString("\n")
                            }
                        }) { Text("Save") }
                    }
                }
            }
        }
    }

    if (showScanChoices) {
        AlertDialog(
            onDismissRequest = { showScanChoices = false },
            title = { Text("Scan Remote ID") },
            text = {
                Text("Scan a barcode when present, or photograph the printed alphanumeric serial number.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showScanChoices = false
                    barcodeScanner.startScan()
                        .addOnSuccessListener { barcode ->
                            val candidate = barcode.rawValue
                                ?.let(::extractRemoteIdCandidates)
                                ?.firstOrNull()
                            val target = scanTargetIndex
                            if (candidate != null && target != null && target in mappings.indices) {
                                mappings[target] = mappings[target].copy(remoteId = candidate)
                            } else {
                                error = "The barcode did not contain a recognizable Remote ID."
                            }
                        }
                }) { Text("Scan barcode") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showScanChoices = false
                    val photo = runCatching {
                        File.createTempFile("rid_serial_", ".jpg", context.cacheDir)
                    }.getOrElse {
                        error = "Unable to prepare the camera."
                        return@TextButton
                    }
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        photo
                    )
                    capturedPhoto = uri
                    photoLauncher.launch(uri)
                }) { Text("Read printed text") }
            }
        )
    }

    if (ocrCandidates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { ocrCandidates = emptyList() },
            title = { Text("Confirm Remote ID") },
            text = {
                Column {
                    Text("Select the exact value and verify ambiguous characters such as 0/O and 1/I.")
                    Spacer(Modifier.height(8.dp))
                    ocrCandidates.take(5).forEach { candidate ->
                        TextButton(
                            onClick = {
                                val target = scanTargetIndex
                                if (target != null && target in mappings.indices) {
                                    mappings[target] = mappings[target].copy(remoteId = candidate)
                                }
                                ocrCandidates = emptyList()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(candidate)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { ocrCandidates = emptyList() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

internal fun extractRemoteIdCandidates(value: String): List<String> {
    val ignored = setOf("SERIAL", "NUMBER", "REMOTEID", "CREDENTIAL")
    return Regex("[A-Za-z0-9]{8,24}")
        .findAll(value)
        .map { it.value.uppercase() }
        .filterNot { it in ignored }
        .distinct()
        .sortedWith(
            compareByDescending<String> { it.length == 20 }
                .thenByDescending { it.length }
        )
        .toList()
}
