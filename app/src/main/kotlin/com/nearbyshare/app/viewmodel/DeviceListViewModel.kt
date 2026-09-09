package com.nearbyshare.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nearbyshare.app.AppContainer
import com.nearbyshare.network.discovery.DiscoveryStatus
import com.nearbyshare.network.discovery.PeerDevice
import com.nearbyshare.network.discovery.mergePeers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Drives [com.nearbyshare.app.ui.DeviceListScreen]. */
class DeviceListViewModel(private val container: AppContainer) : ViewModel() {

    val status: StateFlow<DiscoveryStatus> = container.discoveryService.status

    /** Discovered peers plus anything typed in by hand (PROTOCOL.md §1's manual-testing escape hatch). */
    val peers: StateFlow<List<PeerDevice>> = combine(
        container.discoveryService.peers,
        container.manualPeerStore.peers,
    ) { discovered, manual -> mergePeers(discovered, manual) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Add a manually-entered peer.
     *
     * @return an error message to show next to the input field, or `null` on success.
     */
    fun addManualPeer(host: String, port: String, label: String?): String? {
        val portNumber = port.trim().toIntOrNull()
        if (portNumber == null) return "Enter a valid port number"
        return try {
            container.manualPeerStore.add(host, portNumber, label)
            null
        } catch (e: IllegalArgumentException) {
            e.message ?: "Invalid host or port"
        }
    }

    fun removeManualPeer(deviceId: String) {
        container.manualPeerStore.remove(deviceId)
    }

    fun startSharing() {
        viewModelScope.launch {
            runCatching { container.startSharing() }
        }
    }
}
