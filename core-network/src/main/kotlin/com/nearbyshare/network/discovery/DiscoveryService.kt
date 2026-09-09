package com.nearbyshare.network.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** Whether we are currently advertising and browsing, and why not if not. */
sealed interface DiscoveryStatus {
    data object Stopped : DiscoveryStatus
    data object Starting : DiscoveryStatus

    /** Advertising under [publishedName] -- which may differ from the requested
     *  name if the platform resolved a collision (PROTOCOL.md §1). */
    data class Running(val publishedName: String) : DiscoveryStatus

    data class Failed(val reason: String) : DiscoveryStatus
}

/**
 * Advertises this device and browses for others on `_nearbyshare._tcp.local.`
 * (PROTOCOL.md §1).
 *
 * An interface so the UI can be driven by a fake, and so the Android
 * `NsdManager` implementation stays the only thing that needs a device.
 */
interface DiscoveryService {

    /** Live list of peers currently being advertised on the network. */
    val peers: StateFlow<List<PeerDevice>>

    val status: StateFlow<DiscoveryStatus>

    /** Start advertising [advertisement] and browsing for peers. Idempotent. */
    fun start(advertisement: ServiceAdvertisement)

    /** Stop advertising and browsing, and clear [peers]. Idempotent. */
    fun stop()
}

/**
 * Peers entered by hand as `host:port` on the device list.
 *
 * This is the escape hatch that makes a transfer testable before mDNS is
 * trusted to work across a given network -- plenty of home and corporate
 * Wi-Fi setups block multicast, and "discovery is broken" and "transfers are
 * broken" are otherwise indistinguishable.
 *
 * A manual peer has no TXT record, so no advertised fingerprint to cross-check
 * against; the first connection pins whatever certificate the peer presents.
 */
class ManualPeerStore {

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    /**
     * Add (or replace) a manual entry for [host]:[port].
     *
     * @throws IllegalArgumentException if the host is blank or the port is out
     *   of range -- the caller should surface this next to the input field.
     */
    fun add(host: String, port: Int, label: String? = null): PeerDevice {
        val cleanHost = host.trim()
        require(cleanHost.isNotEmpty()) { "Enter an IP address or hostname" }
        require(port in 1..65_535) { "Port must be between 1 and 65535" }

        val peer = PeerDevice(
            deviceId = manualDeviceId(cleanHost, port),
            name = label?.takeIf { it.isNotBlank() } ?: "$cleanHost:$port",
            host = cleanHost,
            port = port,
            fingerprint = null,
            os = null,
            source = PeerSource.MANUAL,
        )
        _peers.update { existing -> existing.filterNot { it.deviceId == peer.deviceId } + peer }
        return peer
    }

    fun remove(deviceId: String) {
        _peers.update { existing -> existing.filterNot { it.deviceId == deviceId } }
    }

    fun clear() {
        _peers.value = emptyList()
    }

    private companion object {
        /**
         * A stable placeholder id derived from the address, so re-adding the
         * same host does not create a duplicate row -- and so any fingerprint
         * pinned against it survives an app restart.
         */
        fun manualDeviceId(host: String, port: Int): String =
            "manual:" + UUID.nameUUIDFromBytes("$host:$port".toByteArray())
    }
}

/**
 * The peer list the UI actually renders: everything discovered over mDNS, plus
 * anything typed in by hand, with manual entries superseded by a discovered
 * peer at the same address.
 */
fun mergePeers(discovered: List<PeerDevice>, manual: List<PeerDevice>): List<PeerDevice> {
    val discoveredAddresses = discovered.map { it.host to it.port }.toSet()
    val extras = manual.filterNot { (it.host to it.port) in discoveredAddresses }
    return (discovered + extras).sortedWith(
        compareBy({ it.source != PeerSource.DISCOVERED }, { it.name.lowercase() }),
    )
}
