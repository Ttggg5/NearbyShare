package com.nearbyshare.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nearbyshare.app.viewmodel.ReceiveViewModel
import com.nearbyshare.network.transfer.TransferState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveProgressScreen(
    viewModel: ReceiveViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Receiving") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = headline(state), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))

            when (val current = state) {
                is TransferState.InProgress -> {
                    Text(text = "${current.fileName} (${current.fileIndex + 1}/${current.fileCount})")
                    Spacer(modifier = Modifier.height(8.dp))
                    val fraction = current.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                        Text(text = "${(fraction * 100).toInt()}%")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
                    }
                }

                is TransferState.Completed -> {
                    current.savedLocations.forEach { location -> Text(text = location) }
                }

                is TransferState.Failed -> current.message?.let { Text(text = it) }

                else -> Unit
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (state.isTerminal) {
                Button(onClick = onDone) { Text("Done") }
            }
        }
    }
}

private fun headline(state: TransferState): String = when (state) {
    TransferState.Idle -> "Waiting for files…"
    is TransferState.Connecting -> "Connecting…"
    is TransferState.AwaitingAcceptance -> "Waiting…"
    is TransferState.AwaitingApproval -> "Reviewing offer from ${state.peerName}…"
    is TransferState.InProgress -> "Receiving from ${state.peerName}"
    is TransferState.Completed -> "Received ${current(state)} from ${state.peerName}"
    is TransferState.Rejected -> "Declined"
    is TransferState.Cancelled -> if (state.byPeer) "Cancelled by the sender" else "Cancelled"
    is TransferState.Failed -> "Transfer failed"
}

private fun current(state: TransferState.Completed): String =
    if (state.fileNames.size == 1) state.fileNames.first() else "${state.fileNames.size} files"
