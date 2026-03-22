/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.ncssar.rid2caltopo.data.OrgConfigToken

// ── QR bitmap helper ──────────────────────────────────────────────────────────

/**
 * Generate a square QR-code [Bitmap] from [content].
 * Returns null if encoding fails (e.g. empty string).
 */
private fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
    if (content.isBlank()) return null
    return try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { generateQrBitmap(content) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Join QR code — scan with another device running RID2Caltopo",
            modifier = modifier
        )
    } else {
        Text(
            text = "[QR generation failed]",
            modifier = modifier,
            color = MaterialTheme.colorScheme.error
        )
    }
}

// ── Export dialog (admin) ─────────────────────────────────────────────────────

private sealed class ExportStep {
    object EnterName : ExportStep()
    object Uploading : ExportStep()
    data class ShowQr(val token: String) : ExportStep()
    data class Error(val message: String) : ExportStep()
}

/**
 * Multi-step dialog shown to the admin for generating a join QR code.
 *
 * Step 1 — Enter an org name.
 * Step 2 — Upload in progress (credentials are encrypted before upload).
 * Step 3 — Display QR code + raw token text (both copyable / shareable).
 *
 * [onDismiss] is called when the dialog should be closed.
 * [onUploadRequested] is called with (orgName, resultCallback) when the user
 * confirms; the caller is responsible for obtaining Drive auth and invoking
 * [OrgConfigManager.uploadOrgConfig].  The callback receives
 * (success, message, tokenOrNull) on the main thread.
 */
@Composable
fun OrgConfigExportDialog(
    onDismiss: () -> Unit,
    onUploadRequested: (orgName: String, callback: (Boolean, String, String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var step by remember { mutableStateOf<ExportStep>(ExportStep.EnterName) }
    var orgName by remember {
        mutableStateOf(
            org.ncssar.rid2caltopo.data.OrgConfigManager.getStoredOrgName(context)
        )
    }

    AlertDialog(
        onDismissRequest = {
            // Don't dismiss while uploading.
            if (step !is ExportStep.Uploading) onDismiss()
        },
        title = {
            Text(
                when (step) {
                    is ExportStep.EnterName -> "Generate Org Join QR"
                    is ExportStep.Uploading -> "Uploading…"
                    is ExportStep.ShowQr   -> "Org Join QR"
                    is ExportStep.Error    -> "Upload Failed"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (val s = step) {

                    is ExportStep.EnterName -> {
                        Text(
                            "Enter your SAR org name. The app will upload your current " +
                                "ridmap and credentials to Google Drive and generate a QR " +
                                "code team members can scan to receive the config. " +
                                "Credentials are encrypted before upload.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = orgName,
                            onValueChange = { orgName = it },
                            label = { Text("Org name (e.g. NCSSAR)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is ExportStep.Uploading -> {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Uploading org config to Google Drive…")
                    }

                    is ExportStep.ShowQr -> {
                        Text(
                            "Share this QR with your team. Each member scans it once " +
                                "using Menu → Join Org.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        QrCodeImage(
                            // Encode as r2c1:// URI so the OS camera dispatches
                            // it directly to this app rather than showing "no app
                            // found".  The payload is the opaque part after R2C1:.
                            content = "r2c1://" + s.token.removePrefix(OrgConfigToken.MAGIC_PREFIX),
                            modifier = Modifier.size(240.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Or share as text:",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                text = s.token,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString(s.token))
                        }) {
                            Text("Copy Token")
                        }
                    }

                    is ExportStep.Error -> {
                        Text(
                            s.message,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (val s = step) {
                is ExportStep.EnterName -> {
                    TextButton(
                        enabled = orgName.isNotBlank(),
                        onClick = {
                            step = ExportStep.Uploading
                            onUploadRequested(orgName.trim()) { success, message, token ->
                                step = if (success && token != null) {
                                    ExportStep.ShowQr(token)
                                } else {
                                    ExportStep.Error(message)
                                }
                            }
                        }
                    ) { Text("Generate") }
                }
                is ExportStep.ShowQr, is ExportStep.Error -> {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                else -> { /* no confirm button while uploading */ }
            }
        },
        dismissButton = {
            if (step !is ExportStep.Uploading) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

// ── Join dialog (member) ──────────────────────────────────────────────────────

/**
 * Single-step dialog shown to a team member for joining an org config.
 *
 * The member pastes (or types) the R2C1:… token they received from the admin.
 * The token is validated in real time; the "Join" button is only enabled when
 * the token looks valid.  On confirm, the app downloads the bundle (no sign-in
 * required) and decrypts credentials locally before applying.
 *
 * [onDismiss] is called when the dialog should close.
 * [onJoin] is called with the trimmed token string when the member confirms.
 */
@Composable
fun OrgConfigJoinDialog(
    onDismiss: () -> Unit,
    onJoin: (token: String) -> Unit
) {
    val context = LocalContext.current
    var tokenText by remember { mutableStateOf("") }
    val isValid = remember(tokenText) { OrgConfigToken.isValidToken(tokenText) }
    val decoded = remember(tokenText) { OrgConfigToken.decode(tokenText.trim()) }

    val scanner = remember(context) {
        GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join Org Config") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Scan the QR code from your org admin, or paste the join token below.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it },
                    label = { Text("Join token") },
                    placeholder = { Text("${OrgConfigToken.MAGIC_PREFIX}…") },
                    singleLine = false,
                    isError = tokenText.isNotBlank() && !isValid,
                    trailingIcon = {
                        IconButton(onClick = {
                            scanner.startScan()
                                .addOnSuccessListener { barcode ->
                                    val raw = barcode.rawValue ?: return@addOnSuccessListener
                                    // Convert r2c1://payload → R2C1:payload
                                    tokenText = if (raw.startsWith("r2c1://")) {
                                        OrgConfigToken.MAGIC_PREFIX + raw.removePrefix("r2c1://")
                                    } else raw
                                }
                                .addOnFailureListener { /* user cancelled or scan failed — leave field as-is */ }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.QrCodeScanner,
                                contentDescription = "Scan QR code"
                            )
                        }
                    },
                    supportingText = {
                        when {
                            tokenText.isBlank()        -> Text("Scan QR or paste token from your admin")
                            isValid && decoded != null -> Text("Org: ${decoded.orgName}")
                            else                       -> Text(
                                "Token not recognised",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { onJoin(tokenText.trim()) }
            ) {
                Text("Join")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
