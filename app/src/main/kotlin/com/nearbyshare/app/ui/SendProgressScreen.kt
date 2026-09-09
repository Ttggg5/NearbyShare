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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nearbyshare.app.viewmodel.SendViewModel
import com.nearbyshare.network.transfer.TransferState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendProgressScreen(
    viewModel: SendViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sending to ${viewModel.peerName}") }) },
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
                    Text(text = current.fileName)
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

                is TransferState.AwaitingAcceptance ->
                    Text(text = "Waiting for the other device to accept…")

                is TransferState.Failed -> current.message?.let { Text(text = it) }
                is TransferState.Rejected -> current.reason?.let { Text(text = it) }
                else -> Unit
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (state.isTerminal) {
                Button(onClick = onDone) { Text("Done") }
            } else {
                OutlinedButton(onClick = viewModel::cancel) { Text("Cancel") }
            }
        }
    }
}

private fun headline(state: TransferState): String = when (state) {
    TransferState.Idle -> "Preparing…"
    is TransferState.Connecting -> "Connecting to ${state.peerName}…"
    is TransferState.AwaitingAcceptance -> "Offer sent"
    is TransferState.AwaitingApproval -> "Waiting for approval"
    is TransferState.InProgress -> "Sending"
    is TransferState.Completed -> "Transfer complete"
    is TransferState.Rejected -> "Declined by ${state.peerName}"
    is TransferState.Cancelled -> if (state.byPeer) "Cancelled by the other device" else "Cancelled"
    is TransferState.Failed -> "Transfer failed"
}
