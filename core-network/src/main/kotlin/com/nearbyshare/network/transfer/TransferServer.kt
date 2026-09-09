package com.nearbyshare.network.transfer

import com.nearbyshare.network.tls.PeerTrustException
import com.nearbyshare.network.tls.PeerTrustPolicy
import com.nearbyshare.network.tls.TlsSocketFactory
import com.nearbyshare.protocol.HelloPayload
import com.nearbyshare.protocol.MessageChannel
import com.nearbyshare.protocol.OfferPayload
import com.nearbyshare.protocol.OfferedFile
import com.nearbyshare.protocol.Protocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.net.ssl.SSLSocket

/** An inbound `OFFER` waiting on the user's answer. */
data class IncomingTransferRequest(
    val transferId: String,
    val peerDeviceId: String,
    val peerName: String,
    val files: List<OfferedFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

/** Asks the user whether to accept an inbound transfer. */
fun interface TransferApprover {
    /** Suspends for as long as the prompt is on screen. */
    suspend fun approve(request: IncomingTransferRequest): ApprovalDecision
}

/**
 * The inbound half of a transfer: listen for TLS connections and run
 * [TransferSession] as the receiver for each one.
 *
 * The listening socket is opened on an ephemeral port by default; [port] is
 * what gets published in the mDNS `port` TXT record (PROTOCOL.md §1), so
 * discovery must not be started until [start] has returned.
 */
class TransferServer(
    private val tls: TlsSocketFactory,
    private val trustPolicy: PeerTrustPolicy,
    private val identity: suspend () -> LocalIdentity,
    private val sinkFactory: () -> FileSink,
    private val approver: TransferApprover,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private var acceptJob: Job? = null

    private val _activeSession = MutableStateFlow<TransferSession?>(null)

    /** The transfer currently being received, if any. */
    val activeSession: StateFlow<TransferSession?> = _activeSession.asStateFlow()

    private val _lastState = MutableStateFlow<TransferState>(TransferState.Idle)

    /** The most recent receive-side state, for the notification and the UI. */
    val lastState: StateFlow<TransferState> = _lastState.asStateFlow()

    /** The bound TCP port, or `-1` while stopped. */
    @Volatile
    var port: Int = -1
        private set

    /**
     * Bind the listening socket and start accepting.
     *
     * @param requestedPort `0` (the default) lets the OS pick a free port.
     * @return the bound port, ready to advertise.
     */
    suspend fun start(requestedPort: Int = 0): Int = withContext(ioDispatcher) {
        stop()
        val socket = tls.createListeningSocket(requestedPort)
        serverSocket = socket
        port = socket.localPort

        val serverScope = CoroutineScope(ioDispatcher + SupervisorJob())
        scope = serverScope
        acceptJob = serverScope.launch { acceptLoop(socket, serverScope) }
        port
    }

    /** Stop listening and abandon any in-flight receive. */
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        serverSocket?.let { socket -> runCatching { socket.close() } }
        serverSocket = null
        scope?.cancel()
        scope = null
        _activeSession.value = null
        port = -1
    }

    private suspend fun acceptLoop(socket: ServerSocket, serverScope: CoroutineScope) {
        while (serverScope.isActive && !socket.isClosed) {
            val accepted = try {
                socket.accept()
            } catch (e: SocketException) {
                // Expected when stop() closes the socket out from under us.
                break
            } catch (e: IOException) {
                break
            }
            // One coroutine per connection: a peer stalling at its own accept
            // prompt must not stop us hearing from anyone else.
            serverScope.launch { handleConnection(accepted) }
        }
    }

    /**
     * Upgrade one accepted plain socket to TLS with its own trust manager, then
     * run the receive side of PROTOCOL.md §5 over it.
     */
    private suspend fun handleConnection(accepted: Socket) {
        var session: TransferSession? = null
        try {
            val trustManager = trustPolicy.trustManagerFor(peerDeviceId = null)
            val secured: SSLSocket = tls.secureAccepted(accepted, trustManager)
            val presented = trustManager.presentedFingerprint
                ?: throw IOException("Peer completed the handshake without a certificate")

            val channel = MessageChannel(
                input = secured.inputStream,
                output = secured.outputStream,
                onClose = { runCatching { secured.close() } },
            )

            val localIdentity = identity()
            val transferSession = TransferSession(
                channel = channel,
                localIdentity = localIdentity,
            )
            session = transferSession
            _activeSession.value = transferSession

            val stateMirror = CoroutineScope(ioDispatcher).launch {
                transferSession.state.collect { _lastState.value = it }
            }

            try {
                transferSession.runAsReceiver(
                    sink = sinkFactory(),
                    onPeerIdentified = { hello: HelloPayload ->
                        // PROTOCOL.md §2 steps 4-5: only now do we know which
                        // device this connection claims to be, so this is where
                        // the fingerprint gets pinned or checked.
                        trustPolicy.confirm(hello.deviceId, presented)
                    },
                    approve = { offer: OfferPayload, hello: HelloPayload ->
                        approver.approve(
                            IncomingTransferRequest(
                                transferId = offer.transferId,
                                peerDeviceId = hello.deviceId,
                                peerName = hello.deviceName,
                                files = offer.files,
                            ),
                        )
                    },
                )
            } finally {
                stateMirror.cancel()
            }
        } catch (e: Throwable) {
            // A failure inside TransferSession has already published a terminal
            // state and told the peer. Anything reaching here happened earlier
            // -- a handshake or trust failure -- and still needs surfacing.
            if (!_lastState.value.isTerminal) {
                _lastState.value = TransferState.Failed(
                    transferId = null,
                    code = errorCodeFor(e),
                    message = e.message ?: e::class.simpleName,
                )
            }
        } finally {
            session?.close()
            _activeSession.value = null
            runCatching { accepted.close() }
        }
    }

    private fun errorCodeFor(e: Throwable): String = when (e) {
        is PeerTrustException -> Protocol.ErrorCode.IDENTITY_MISMATCH
        is IOException -> Protocol.ErrorCode.IO_ERROR
        else -> Protocol.ErrorCode.INTERNAL_ERROR
    }
}
