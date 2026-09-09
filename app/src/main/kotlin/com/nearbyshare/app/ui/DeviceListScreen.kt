package com.nearbyshare.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nearbyshare.app.viewmodel.DeviceListViewModel
import com.nearbyshare.network.discovery.DiscoveryStatus
import com.nearbyshare.network.discovery.PeerDevice
import com.nearbyshare.network.discovery.PeerSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    viewModel: DeviceListViewModel,
    onOpenSettings: () -> Unit,
    onFilesPicked: (PeerDevice, List<Uri>) -> Unit,
) {
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()

    var pendingPeer by remember { mutableStateOf<PeerDevice?>(null) }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val peer = pendingPeer
        pendingPeer = null
        if (peer != null && uris.isNotEmpty()) onFilesPicked(peer, uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nearby devices") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            DiscoveryStatusRow(status, onRetry = viewModel::startSharing)

            if (peers.isEmpty()) {
                Text(
                    text = "No devices found yet. Make sure the other device has NearbyShare open, " +
                        "or add its IP address below.",
                    modifier = Modifier.padding(16.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(peers, key = { it.deviceId }) { peer ->
                    PeerRow(
                        peer = peer,
                        onSendClick = {
                            pendingPeer = peer
                            filePicker.launch(arrayOf("*/*"))
                        },
                        onRemove = { viewModel.removeManualPeer(peer.deviceId) }
                            .takeIf { peer.source == PeerSource.MANUAL },
                    )
                }
            }

            ManualPeerEntry(onAdd = viewModel::addManualPeer)
        }
    }
}

@Composable
private fun DiscoveryStatusRow(status: DiscoveryStatus, onRetry: () -> Unit) {
    val text = when (status) {
        DiscoveryStatus.Stopped -> "Discovery stopped"
        DiscoveryStatus.Starting -> "Starting discovery…"
        is DiscoveryStatus.Running -> "Visible as \"${status.publishedName}\""
        is DiscoveryStatus.Failed -> "Discovery unavailable: ${status.reason}"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status is DiscoveryStatus.Starting) {
            CircularProgressIndicator(modifier = Modifier.width(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, overflow = TextOverflow.Ellipsis, maxLines = 1, modifier = Modifier.weight(1f))
        if (status is DiscoveryStatus.Failed) {
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun PeerRow(
    peer: PeerDevice,
    onSendClick: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = peer.name)
                val subtitle = buildString {
                    append(peer.host)
                    peer.os?.let { append(" · $it") }
                    if (peer.source == PeerSource.MANUAL) append(" · manual")
                    if (!peer.isCompatible) append(" · unsupported version")
                }
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
            }
            if (onRemove != null) {
                TextButton(onClick = onRemove) { Text("Remove") }
            }
            IconButton(onClick = onSendClick, enabled = peer.isCompatible) {
                Icon(Icons.Filled.Send, contentDescription = "Send files to ${peer.name}")
            }
        }
    }
}

@Composable
private fun ManualPeerEntry(onAdd: (host: String, port: String, label: String?) -> String?) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = "Can't see a device? Enter its IP address:")
        Spacer(modifier = Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("IP address") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                error = onAdd(host, port, null)
                if (error == null) {
                    host = ""
                    port = ""
                }
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add device")
            }
        }
        error?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
    }
}
