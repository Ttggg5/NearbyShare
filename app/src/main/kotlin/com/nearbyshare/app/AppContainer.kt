package com.nearbyshare.app

import android.content.Context
import com.nearbyshare.data.DataStoreSettingsRepository
import com.nearbyshare.data.DeviceSettingsRepository
import com.nearbyshare.data.NoOpTransferHistoryRepository
import com.nearbyshare.data.TransferHistoryRepository
import com.nearbyshare.network.android.AndroidKeystoreCertificateManager
import com.nearbyshare.network.android.MediaStoreDownloadsFileSink
import com.nearbyshare.network.discovery.DiscoveryService
import com.nearbyshare.network.discovery.ManualPeerStore
import com.nearbyshare.network.discovery.NsdDiscoveryService
import com.nearbyshare.network.discovery.PeerDevice
import com.nearbyshare.network.discovery.ServiceAdvertisement
import com.nearbyshare.network.identity.CertificateProvider
import com.nearbyshare.network.tls.PeerTrustPolicy
import com.nearbyshare.network.tls.SettingsKnownPeerStore
import com.nearbyshare.network.tls.TlsSocketFactory
import com.nearbyshare.network.transfer.FileSource
import com.nearbyshare.network.transfer.LocalIdentity
import com.nearbyshare.network.transfer.TransferApprover
import com.nearbyshare.network.transfer.TransferClient
import com.nearbyshare.network.transfer.TransferServer
import com.nearbyshare.network.transfer.TransferSession
import com.nearbyshare.network.transfer.TransferState
import com.nearbyshare.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Hand-rolled service locator wiring the `:core-network`/`:core-data` singletons
 * to the UI, in place of an annotation-processor DI framework.
 *
 * One instance lives for the process lifetime, held by [NearbyShareApplication].
 * Everything here is a `by lazy` so construction order does not matter and
 * nothing touches the Android Keystore or DataStore before it is first needed.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val settings: DeviceSettingsRepository by lazy { DataStoreSettingsRepository.create(appContext) }

    val certificateProvider: CertificateProvider by lazy { AndroidKeystoreCertificateManager() }

    private val knownPeerStore by lazy { SettingsKnownPeerStore(settings) }

    val trustPolicy: PeerTrustPolicy by lazy { PeerTrustPolicy(knownPeerStore) }

    private val tlsSocketFactory: TlsSocketFactory by lazy { TlsSocketFactory(certificateProvider) }

    /** MVP history persistence is intentionally deferred; see PROTOCOL.md / TransferHistoryRepository. */
    val transferHistoryRepository: TransferHistoryRepository by lazy { NoOpTransferHistoryRepository() }

    /** The manual `host:port` escape hatch described in PROTOCOL.md §1. */
    val manualPeerStore: ManualPeerStore by lazy { ManualPeerStore() }

    /** Owns discovery's background browsing/resolution coroutines for the process lifetime. */
    private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val discoveryService: DiscoveryService by lazy { NsdDiscoveryService(appContext, discoveryScope) }

    /** Bridges an inbound `OFFER` (seen on [TransferServer]'s accept thread) to the UI. */
    val incomingTransferGate: IncomingTransferGate by lazy { IncomingTransferGate() }

    private val _outboundSession = MutableStateFlow<TransferSession?>(null)

    /** The in-flight outbound transfer, if any -- for the foreground service's notification. */
    val outboundSession: StateFlow<TransferSession?> = _outboundSession.asStateFlow()

    suspend fun localIdentity(): LocalIdentity =
        LocalIdentity(deviceId = settings.deviceId(), deviceName = settings.deviceName())

    val transferClient: TransferClient by lazy {
        TransferClient(
            tls = tlsSocketFactory,
            trustPolicy = trustPolicy,
            identity = { localIdentity() },
        )
    }

    val transferServer: TransferServer by lazy {
        TransferServer(
            tls = tlsSocketFactory,
            trustPolicy = trustPolicy,
            identity = { localIdentity() },
            sinkFactory = { MediaStoreDownloadsFileSink(appContext) },
            approver = TransferApprover { request -> incomingTransferGate.awaitDecision(request) },
        )
    }

    /**
     * Bind [transferServer]'s listening socket and start advertising it over mDNS.
     *
     * PROTOCOL.md §1: discovery must not start until the server's port is known,
     * since the port is part of what gets advertised.
     */
    suspend fun startSharing() {
        transferServer.start()
        refreshAdvertisement()
    }

    /**
     * Re-publish the mDNS advertisement (e.g. after the device name changes)
     * without restarting [transferServer].
     *
     * Deliberately separate from [startSharing]: restarting the server would
     * close its listening socket and abort any transfer currently in flight,
     * just because the user edited a text field in Settings.
     */
    suspend fun refreshAdvertisement() = withContext(Dispatchers.IO) {
        val port = transferServer.port
        if (port <= 0) return@withContext
        discoveryService.start(
            ServiceAdvertisement(
                instanceName = settings.deviceName(),
                deviceId = settings.deviceId(),
                fingerprint = certificateProvider.fingerprint,
                port = port,
                os = Protocol.Txt.OS_ANDROID,
            ),
        )
    }

    fun stopSharing() {
        discoveryService.stop()
        transferServer.stop()
    }

    /**
     * Send [sources] to [peer], publishing the live [TransferSession] via
     * [outboundSession] for as long as it is running.
     */
    suspend fun sendFiles(
        peer: PeerDevice,
        sources: List<FileSource>,
        onSessionStarted: (TransferSession) -> Unit = {},
    ): TransferState {
        try {
            return transferClient.send(
                peer = peer,
                sources = sources,
                onSessionStarted = { session ->
                    _outboundSession.value = session
                    onSessionStarted(session)
                },
            )
        } finally {
            _outboundSession.value = null
        }
    }
}
