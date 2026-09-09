package com.nearbyshare.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.nearbyshare.app.PendingIncomingTransfer
import com.nearbyshare.protocol.OfferedFile

/**
 * The accept/reject prompt for an inbound `OFFER` (PROTOCOL.md §5 step 3).
 *
 * Shown as an overlay regardless of which screen is on top, since a transfer
 * offer can arrive at any time while the app (and its foreground service) is
 * running.
 */
@Composable
fun IncomingTransferDialog(
    pending: PendingIncomingTransfer,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val request = pending.request
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Incoming files from ${request.peerName}") },
        text = {
            Column {
                Text(text = describeFiles(request.files))
                Text(text = formatBytes(request.totalBytes))
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Accept") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Decline") } },
    )
}

private fun describeFiles(files: List<OfferedFile>): String =
    if (files.size == 1) files.first().name else "${files.size} files: ${files.joinToString { it.name }}"

private fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "$bytes B" else "%.1f %s".format(value, units[unitIndex])
}
