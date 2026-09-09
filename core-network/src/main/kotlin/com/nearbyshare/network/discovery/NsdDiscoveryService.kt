package com.nearbyshare.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.nearbyshare.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * `NsdManager`-backed implementation of PROTOCOL.md §1.
 *
 * Advertises `_nearbyshare._tcp` with the `v`/`id`/`fp`/`port`/`os` TXT record
 * and simultaneously browses for other advertisers, exposing them as a live
 * [peers] list.
 *
 * Two Android-specific wrinkles are handled here:
 *
 * - **Resolution must be serialised.** Before API 34, calling `resolveService`
 *   again while one is in flight fails the earlier one with
 *   `FAILURE_ALREADY_ACTIVE`. Found services therefore go through a channel and
 *   are resolved one at a time.
 * - **Our own advertisement comes back to us.** Browsing sees the service this
 *   same device publishes, so resolved records whose `id` matches our own are
 *   dropped rather than shown as a peer.
 *
 * All the parsing this class does lives in [TxtRecords] so it can be unit
 * tested; what is left here is Android plumbing that needs a real device.
 */
class NsdDiscoveryService(
    context: Context,
    private val scope: CoroutineScope,
) : DiscoveryService {

    private val appContext = context.applicationContext
    private val nsdManager: NsdManager =
        appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private val _status = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Stopped)
    override val status: StateFlow<DiscoveryStatus> = _status.asStateFlow()

    private var advertisement: ServiceAdvertisement? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var resolveJob: Job? = null

    /** Serialises resolution; see the class docs. */
    private val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)

    @Synchronized
    override fun start(advertisement: ServiceAdvertisement) {
        if (registrationListener != null || discoveryListener != null) {
            stop()
        }
        this.advertisement = advertisement
        _status.value = DiscoveryStatus.Starting

        TxtRecords.oversizedKeys(advertisement.txtRecords()).takeIf { it.isNotEmpty() }?.let { keys ->
            _status.value = DiscoveryStatus.Failed("TXT entries too long: ${keys.joinToString()}")
            return
        }

        acquireMulticastLock()
        resolveJob = scope.launch { resolveLoop() }
        registerService(advertisement)
        startBrowsing()
    }

    @Synchronized
    override fun stop() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
                .onFailure { Log.w(TAG, "unregisterService failed", it) }
        }
        registrationListener = null

        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
                .onFailure { Log.w(TAG, "stopServiceDiscovery failed", it) }
        }
        discoveryListener = null

        resolveJob?.cancel()
        resolveJob = null
        releaseMulticastLock()

        _peers.value = emptyList()
        _status.value = DiscoveryStatus.Stopped
    }

    // ---------------------------------------------------------- Advertising --

    private fun registerService(advertisement: ServiceAdvertisement) {
        val info = NsdServiceInfo().apply {
            serviceName = ServiceInstanceNames.sanitize(advertisement.instanceName)
            serviceType = Protocol.SERVICE_TYPE
            port = advertisement.port
            advertisement.txtRecords().forEach { (key, value) -> setAttribute(key, value) }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // Android resolves instance-name collisions for us and hands
                // back the name it actually published (PROTOCOL.md §1).
                _status.value = DiscoveryStatus.Running(info.serviceName.orEmpty())
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                _status.value = DiscoveryStatus.Failed("Advertising failed (NSD error $errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    // ------------------------------------------------------------- Browsing --

    private fun startBrowsing() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType?.contains(SERVICE_TYPE_MATCH) != true) return
                toResolve.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val lostName = info.serviceName ?: return
                _peers.update { current -> current.filterNot { it.name == lostName } }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _status.value = DiscoveryStatus.Failed("Discovery failed (NSD error $errorCode)")
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stopServiceDiscovery failed: $errorCode")
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private suspend fun resolveLoop() {
        for (found in toResolve) {
            runCatching { resolve(found) }
                .onFailure { Log.w(TAG, "Resolve failed for ${found.serviceName}", it) }
        }
    }

    /** Resolve one service and fold it into [peers]. Suspends until it settles. */
    private suspend fun resolve(found: NsdServiceInfo) {
        val resolved = kotlinx.coroutines.suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(info))
                }
            }
            @Suppress("DEPRECATION") // registerServiceInfoCallback needs API 34.
            nsdManager.resolveService(found, listener)
        } ?: return

        val advertised = TxtRecords.parse(resolved.attributes.orEmpty()) ?: return

        // Skip our own advertisement echoing back to us.
        if (advertised.deviceId == advertisement?.deviceId) return

        val host = resolved.hostAddress() ?: return
        val peer = PeerDevice(
            deviceId = advertised.deviceId,
            name = resolved.serviceName.orEmpty().ifEmpty { host },
            host = host,
            port = advertised.port,
            fingerprint = advertised.fingerprint,
            os = advertised.os,
            protocolVersion = advertised.protocolVersion,
            source = PeerSource.DISCOVERED,
            lastSeenAtMillis = System.currentTimeMillis(),
        )
        _peers.update { current -> current.filterNot { it.deviceId == peer.deviceId } + peer }
    }

    @Suppress("DEPRECATION") // getHost() is deprecated for getHostAddresses() on API 34.
    private fun NsdServiceInfo.hostAddress(): String? {
        val address: InetAddress? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hostAddresses.firstOrNull()
        } else {
            host
        }
        return address?.hostAddress
    }

    // -------------------------------------------------------- Multicast lock --

    /**
     * mDNS traffic is multicast, and Wi-Fi hardware filters multicast when the
     * device is idle. `NsdManager` usually manages this itself, but holding the
     * lock explicitly makes discovery noticeably more reliable while the app is
     * actively browsing -- and it is why the app requests
     * `CHANGE_WIFI_MULTICAST_STATE`.
     */
    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        multicastLock = runCatching {
            wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "Could not acquire multicast lock", it) }.getOrNull()
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
                .onFailure { Log.w(TAG, "Could not release multicast lock", it) }
        }
        multicastLock = null
    }

    private companion object {
        const val TAG = "NsdDiscoveryService"
        const val MULTICAST_LOCK_TAG = "nearbyshare-mdns"

        /** `NsdManager` reports types as `_nearbyshare._tcp.` with a trailing dot. */
        const val SERVICE_TYPE_MATCH = "_nearbyshare._tcp"
    }
}
