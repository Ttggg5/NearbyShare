package com.nearbyshare.network.transfer

import com.nearbyshare.network.discovery.PeerDevice
import com.nearbyshare.network.discovery.PeerSource
import com.nearbyshare.network.tls.PeerTrustPolicy
import com.nearbyshare.network.tls.TlsSocketFactory
import com.nearbyshare.protocol.MessageChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * The outbound half of a transfer: dial a peer, complete the TLS handshake with
 * TOFU pinning, then run [TransferSession] as the sender.
 *
 * @param identity supplies this device's `HELLO` identity; a lambda rather than
 *   a value because the device name is user-editable and must not be captured
 *   at construction.
 */
class TransferClient(
    private val tls: TlsSocketFactory,
    private val trustPolicy: PeerTrustPolicy,
    private val identity: suspend () -> LocalIdentity,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Send [sources] to [peer].
     *
     * @param onSessionStarted receives the live session as soon as it exists,
     *   so a caller can observe [TransferSession.state] and cancel it.
     * @return the terminal [TransferState].
     */
    suspend fun send(
        peer: PeerDevice,
        sources: List<FileSource>,
        transferId: String = UUID.randomUUID().toString(),
        onSessionStarted: (TransferSession) -> Unit = {},
    ): TransferState = withContext(ioDispatcher) {
        val localIdentity = identity()

        // PROTOCOL.md §2 steps 2-4. For a discovered peer we pin against both
        // the advertised fp and any fingerprint already stored for its id. A
        // manually entered peer has neither a real device id nor an
        // advertisement, so its address-derived placeholder id is what gets
        // pinned -- weaker, but still catches the certificate under a given
        // address changing between runs.
        val trustManager = trustPolicy.trustManagerFor(
            peerDeviceId = peer.deviceId,
            advertisedFingerprint = peer.fingerprint.takeIf { peer.source == PeerSource.DISCOVERED },
        )

        val socket = tls.connect(peer.host, peer.port, trustManager)
        try {
            val presented = trustManager.presentedFingerprint
                ?: throw IOException("TLS handshake completed without a peer certificate")

            // First use pins; a changed certificate throws here, before a single
            // byte of the user's file is offered.
            trustPolicy.confirm(peer.deviceId, presented)

            val channel = MessageChannel(
                input = socket.inputStream,
                output = socket.outputStream,
                onClose = { runCatching { socket.close() } },
            )

            val session = TransferSession(
                channel = channel,
                localIdentity = localIdentity,
                peerNameHint = peer.name,
            )
            onSessionStarted(session)

            try {
                session.runAsSender(sources, transferId)
            } finally {
                session.close()
            }
        } finally {
            runCatching { socket.close() }
        }
    }
}
