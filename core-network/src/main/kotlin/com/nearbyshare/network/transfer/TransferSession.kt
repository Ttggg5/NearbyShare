package com.nearbyshare.network.transfer

import com.nearbyshare.network.tls.PeerIdentityMismatchException
import com.nearbyshare.protocol.FileIndex
import com.nearbyshare.protocol.HelloPayload
import com.nearbyshare.protocol.Message
import com.nearbyshare.protocol.MessageChannel
import com.nearbyshare.protocol.MessageDispatch
import com.nearbyshare.protocol.OfferPayload
import com.nearbyshare.protocol.OfferedFile
import com.nearbyshare.protocol.Protocol
import com.nearbyshare.protocol.ProtocolException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** This device's protocol identity, as sent in `HELLO`. */
data class LocalIdentity(
    val deviceId: String,
    val deviceName: String,
)

/** How often a `PROGRESS` message is put on the wire. */
data class ProgressPolicy(
    val everyBytes: Long = 512 * 1024,
    val everyMillis: Long = 250,
)

/**
 * Drives one transfer over one already-connected [MessageChannel]: the message
 * exchange of PROTOCOL.md §5 plus the raw byte streaming that follows it.
 *
 * One session per connection. [state] is what the UI observes; the `runAs…`
 * methods return the terminal state and also throw, so a caller can use either
 * style.
 *
 * ### Why the sender does not emit PROGRESS mid-file
 *
 * Control frames and raw file bytes share one TLS stream, and raw bytes carry
 * no framing (PROTOCOL.md §3). A `PROGRESS` frame written by the *sender*
 * between the first and last byte of a file would therefore be read by the
 * receiver as file content. The sender drives its own UI from bytes written
 * locally and emits `PROGRESS` only at file boundaries; live `PROGRESS` during
 * streaming comes from the *receiver*, whose direction of the connection is
 * idle. See PROTOCOL.md §5 step 4.
 */
class TransferSession(
    private val channel: MessageChannel,
    private val localIdentity: LocalIdentity,
    private val peerNameHint: String = "",
    private val progressPolicy: ProgressPolicy = ProgressPolicy(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)

    /** The live state of this transfer, for the UI to collect. */
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)

    /** Owns the CANCEL emitter and the send-side peer watcher. */
    private val sessionScope = CoroutineScope(ioDispatcher + SupervisorJob())

    @Volatile
    private var activeTransferId: String? = null

    @Volatile
    private var peerCancelled = false

    /**
     * Ask for the transfer to stop (PROTOCOL.md §5 step 6).
     *
     * Returns immediately and is safe to call from the main thread. The flag is
     * set synchronously so the transfer loop bails at its next chunk boundary;
     * the `CANCEL` frame itself goes out on a background coroutine, because
     * while a file is streaming the channel's write lock is held for the whole
     * file and sending inline would block the caller until it finished.
     */
    fun cancel() {
        if (!cancelRequested.compareAndSet(false, true)) return
        val id = activeTransferId ?: return
        sessionScope.launch { runCatching { channel.send(Message.cancel(id)) } }
    }

    /** Release the session's background coroutines. Does not close the channel. */
    fun close() {
        sessionScope.cancel()
    }

    // ------------------------------------------------------------- Sending --

    /**
     * Run the sending half of PROTOCOL.md §5: `HELLO`, `OFFER`, wait for the
     * verdict, stream the bytes, `DONE`.
     *
     * @throws TransferRejectedException if the peer's user declined.
     * @throws TransferCancelledException if either side cancelled.
     * @throws TransferFailedException on a protocol or I/O failure.
     */
    suspend fun runAsSender(
        sources: List<FileSource>,
        transferId: String = UUID.randomUUID().toString(),
    ): TransferState = withContext(ioDispatcher) {
        require(sources.isNotEmpty()) { "Nothing to send" }
        activeTransferId = transferId

        try {
            // PROTOCOL.md §2 step 5: HELLO is the first framed message, from
            // the connecting side.
            channel.send(
                Message.hello(
                    deviceId = localIdentity.deviceId,
                    deviceName = localIdentity.deviceName,
                ),
            )

            val offered = sources.map { OfferedFile(it.name, it.size, it.mime, it.sha256) }
            channel.send(Message.offer(transferId, offered))
            _state.value = TransferState.AwaitingAcceptance(transferId, peerNameHint, offered)

            awaitVerdict(transferId)
            checkNotCancelled(transferId)

            streamFiles(sources, transferId)

            channel.send(Message.done(transferId, FileIndex.All))
            terminal(
                TransferState.Completed(
                    transferId = transferId,
                    peerName = peerNameHint,
                    fileNames = sources.map { it.name },
                    totalBytes = sources.sumOf { it.size },
                ),
            )
        } catch (e: Throwable) {
            throw failWith(transferId, e)
        }
    }

    /** Block until the peer accepts, rejects, cancels or errors. */
    private fun awaitVerdict(transferId: String) {
        while (true) {
            when (val reply = channel.receive()) {
                is Message.Accept -> {
                    requireSameTransfer(transferId, reply.payload.transferId)
                    return
                }

                is Message.Reject -> {
                    requireSameTransfer(transferId, reply.payload.transferId)
                    throw TransferRejectedException(reply.payload.reason)
                }

                is Message.Cancel -> {
                    peerCancelled = true
                    throw TransferCancelledException(byPeer = true)
                }

                is Message.Error -> throw TransferFailedException(
                    code = reply.payload.code,
                    message = reply.payload.message ?: "Peer reported ${reply.payload.code}",
                )

                // A peer that greets back, or reports progress early, is not
                // doing anything harmful -- keep waiting for the verdict.
                is Message.Hello, is Message.Progress -> Unit

                // PROTOCOL.md §4: answer, then carry on reading.
                is Message.Unknown -> MessageDispatch.autoResponseFor(reply)
                    ?.let { channel.send(it.error) }

                is Message.Offer, is Message.Done -> throw TransferFailedException(
                    code = Protocol.ErrorCode.PROTOCOL_VIOLATION,
                    message = "Unexpected ${reply.type} while waiting for ACCEPT or REJECT",
                )
            }
        }
    }

    private fun streamFiles(sources: List<FileSource>, transferId: String) {
        val totalBytes = sources.sumOf { it.size }
        var totalSent = 0L

        // While we stream we cannot read, so a watcher on the other direction
        // picks up a CANCEL or ERROR from the receiver. It unblocks when the
        // socket closes, which the owning client always does.
        val watcher = startPeerWatcher()
        try {
            for ((index, source) in sources.withIndex()) {
                checkNotCancelled(transferId)
                publishProgress(transferId, TransferDirection.SENDING, index, sources.size, source.name, totalSent, totalBytes)
                // Safe here: we are at a file boundary, not inside raw bytes.
                sendProgressMessage(transferId, index, totalSent, totalBytes)

                var sentFromFile = 0L
                source.openStream().use { input ->
                    channel.withRawOutput { out ->
                        val buffer = ByteArray(CHUNK_BYTES)
                        while (sentFromFile < source.size) {
                            checkNotCancelled(transferId)
                            val want = minOf(buffer.size.toLong(), source.size - sentFromFile).toInt()
                            val read = input.read(buffer, 0, want)
                            if (read < 0) {
                                throw TransferFailedException(
                                    code = Protocol.ErrorCode.IO_ERROR,
                                    message = "${source.name} ended after $sentFromFile of ${source.size} bytes",
                                )
                            }
                            out.write(buffer, 0, read)
                            sentFromFile += read
                            totalSent += read
                            publishProgress(
                                transferId, TransferDirection.SENDING, index, sources.size,
                                source.name, totalSent, totalBytes,
                            )
                        }
                    }
                }
            }
        } finally {
            watcher.cancel()
        }
    }

    /**
     * Reads control frames while the send loop is writing raw bytes, so a
     * `CANCEL` from the receiver takes effect promptly.
     */
    private fun startPeerWatcher(): Job =
        sessionScope.launch {
            try {
                while (true) {
                    when (val message = channel.receiveOrNull() ?: break) {
                        is Message.Cancel -> {
                            peerCancelled = true
                            cancelRequested.set(true)
                            break
                        }

                        is Message.Error -> {
                            peerCancelled = true
                            cancelRequested.set(true)
                            break
                        }

                        else -> Unit // PROGRESS and anything else is informational here.
                    }
                }
            } catch (_: IOException) {
                // The socket closed or timed out; the send loop reports the
                // real failure.
            }
        }

    // ----------------------------------------------------------- Receiving --

    /**
     * Run the receiving half of PROTOCOL.md §5.
     *
     * @param onPeerIdentified called with the peer's `HELLO` before anything
     *   else. This is where trust-on-first-use pinning happens (PROTOCOL.md §2
     *   steps 4-5); throwing from it aborts the connection with an `ERROR`.
     * @param approve asks the user. Suspends for as long as the prompt is up.
     */
    suspend fun runAsReceiver(
        sink: FileSink,
        onPeerIdentified: suspend (HelloPayload) -> Unit = {},
        approve: suspend (OfferPayload, HelloPayload) -> ApprovalDecision,
    ): TransferState = withContext(ioDispatcher) {
        var transferId: String? = null
        try {
            val hello = awaitHello()
            onPeerIdentified(hello)

            val offer = awaitOffer()
            transferId = offer.transferId
            activeTransferId = transferId

            _state.value = TransferState.AwaitingApproval(
                transferId = transferId,
                peerDeviceId = hello.deviceId,
                peerName = hello.deviceName,
                files = offer.files,
            )

            when (val decision = approve(offer, hello)) {
                is ApprovalDecision.Reject -> {
                    channel.send(Message.reject(transferId, decision.reason))
                    return@withContext terminal(
                        TransferState.Rejected(transferId, hello.deviceName, decision.reason),
                    )
                }

                ApprovalDecision.Accept -> channel.send(Message.accept(transferId))
            }

            checkNotCancelled(transferId)
            val saved = receiveFiles(offer, hello.deviceName, sink)
            awaitDone(transferId)

            terminal(
                TransferState.Completed(
                    transferId = transferId,
                    peerName = hello.deviceName,
                    fileNames = offer.files.map { it.name },
                    totalBytes = offer.totalBytes,
                    savedLocations = saved,
                ),
            )
        } catch (e: Throwable) {
            throw failWith(transferId, e)
        }
    }

    private fun awaitHello(): HelloPayload {
        while (true) {
            when (val message = channel.receive()) {
                is Message.Hello -> return message.payload
                is Message.Cancel -> throw TransferCancelledException(byPeer = true)
                is Message.Error -> throw TransferFailedException(
                    message.payload.code,
                    message.payload.message ?: "Peer reported ${message.payload.code}",
                )

                is Message.Unknown -> MessageDispatch.autoResponseFor(message)
                    ?.let { channel.send(it.error) }

                else -> throw TransferFailedException(
                    code = Protocol.ErrorCode.PROTOCOL_VIOLATION,
                    message = "Expected HELLO as the first message, got ${message.type}",
                )
            }
        }
    }

    private fun awaitOffer(): OfferPayload {
        while (true) {
            when (val message = channel.receive()) {
                is Message.Offer -> {
                    validate(message.payload)
                    return message.payload
                }

                is Message.Cancel -> throw TransferCancelledException(byPeer = true)
                is Message.Error -> throw TransferFailedException(
                    message.payload.code,
                    message.payload.message ?: "Peer reported ${message.payload.code}",
                )

                is Message.Hello, is Message.Progress -> Unit
                is Message.Unknown -> MessageDispatch.autoResponseFor(message)
                    ?.let { channel.send(it.error) }

                else -> throw TransferFailedException(
                    code = Protocol.ErrorCode.PROTOCOL_VIOLATION,
                    message = "Expected OFFER, got ${message.type}",
                )
            }
        }
    }

    /** Reject offers we could not honour before the user is even asked. */
    private fun validate(offer: OfferPayload) {
        if (offer.files.isEmpty()) {
            throw TransferFailedException(
                Protocol.ErrorCode.PROTOCOL_VIOLATION,
                "OFFER contained no files",
            )
        }
        offer.files.forEach { file ->
            if (file.size < 0) {
                throw TransferFailedException(
                    Protocol.ErrorCode.PROTOCOL_VIOLATION,
                    "File ${file.name} declares a negative size",
                )
            }
        }
    }

    private fun receiveFiles(
        offer: OfferPayload,
        peerName: String,
        sink: FileSink,
    ): List<String> {
        val transferId = offer.transferId
        val totalBytes = offer.totalBytes
        var totalReceived = 0L
        val saved = mutableListOf<String>()

        // PROTOCOL.md §5 step 4: the files arrive back-to-back with no framing
        // between them, so their declared sizes are the only delimiters.
        for ((index, file) in offer.files.withIndex()) {
            val safeName = sanitizeIncomingFileName(file.name)
            val incoming = sink.create(safeName, file.size, file.mime)
            var committed = false
            try {
                val digest = file.sha256?.let { MessageDigest.getInstance("SHA-256") }
                totalReceived = copyExactly(
                    input = channel.rawIn,
                    file = file,
                    index = index,
                    fileCount = offer.files.size,
                    incoming = incoming,
                    digest = digest,
                    transferId = transferId,
                    peerName = peerName,
                    startingTotal = totalReceived,
                    grandTotal = totalBytes,
                )
                verifyChecksum(file, digest, transferId)

                incoming.outputStream.flush()
                saved += incoming.commit()
                committed = true
            } finally {
                if (!committed) runCatching { incoming.abort() }
                runCatching { incoming.close() }
            }
        }
        return saved
    }

    private fun copyExactly(
        input: InputStream,
        file: OfferedFile,
        index: Int,
        fileCount: Int,
        incoming: IncomingFile,
        digest: MessageDigest?,
        transferId: String,
        peerName: String,
        startingTotal: Long,
        grandTotal: Long,
    ): Long {
        val buffer = ByteArray(CHUNK_BYTES)
        var received = 0L
        var runningTotal = startingTotal
        var lastMessageAt = clock()
        var lastMessageBytes = runningTotal

        while (received < file.size) {
            checkNotCancelled(transferId)
            val want = minOf(buffer.size.toLong(), file.size - received).toInt()
            val read = input.read(buffer, 0, want)
            if (read < 0) {
                // A short read here is how a sender-side CANCEL or crash
                // reaches us: there is no frame to parse, only a closed stream.
                throw if (cancelRequested.get() || peerCancelled) {
                    TransferCancelledException(byPeer = true)
                } else {
                    TransferFailedException(
                        Protocol.ErrorCode.IO_ERROR,
                        "Connection ended after $received of ${file.size} bytes of ${file.name}",
                    )
                }
            }

            try {
                incoming.outputStream.write(buffer, 0, read)
            } catch (e: IOException) {
                throw TransferFailedException(
                    code = if (isOutOfSpace(e)) Protocol.ErrorCode.DISK_FULL else Protocol.ErrorCode.IO_ERROR,
                    message = "Could not write ${file.name}: ${e.message}",
                    cause = e,
                )
            }
            digest?.update(buffer, 0, read)

            received += read
            runningTotal += read
            publishProgress(
                transferId, TransferDirection.RECEIVING, index, fileCount,
                file.name, runningTotal, grandTotal, peerName,
            )

            // Safe in this direction: the sender is not streaming towards us on
            // the same half of the connection.
            val now = clock()
            if (runningTotal - lastMessageBytes >= progressPolicy.everyBytes ||
                now - lastMessageAt >= progressPolicy.everyMillis
            ) {
                sendProgressMessage(transferId, index, runningTotal, grandTotal)
                lastMessageAt = now
                lastMessageBytes = runningTotal
            }
        }
        return runningTotal
    }

    private fun verifyChecksum(file: OfferedFile, digest: MessageDigest?, transferId: String) {
        val expected = file.sha256 ?: return
        val actual = digest?.digest()?.joinToString("") { "%02x".format(it) } ?: return
        if (!actual.equals(expected, ignoreCase = true)) {
            val error = TransferFailedException(
                Protocol.ErrorCode.CHECKSUM_MISMATCH,
                "Checksum mismatch for ${file.name}",
            )
            runCatching {
                channel.send(
                    Message.error(error.code, error.message, transferId),
                )
            }
            throw error
        }
    }

    private fun awaitDone(transferId: String) {
        while (true) {
            when (val message = channel.receive()) {
                is Message.Done -> {
                    requireSameTransfer(transferId, message.payload.transferId)
                    return
                }

                is Message.Cancel -> throw TransferCancelledException(byPeer = true)
                is Message.Error -> throw TransferFailedException(
                    message.payload.code,
                    message.payload.message ?: "Peer reported ${message.payload.code}",
                )

                is Message.Progress -> Unit
                is Message.Unknown -> MessageDispatch.autoResponseFor(message)
                    ?.let { channel.send(it.error) }

                else -> throw TransferFailedException(
                    code = Protocol.ErrorCode.PROTOCOL_VIOLATION,
                    message = "Expected DONE, got ${message.type}",
                )
            }
        }
    }

    // -------------------------------------------------------------- Shared --

    private fun publishProgress(
        transferId: String,
        direction: TransferDirection,
        fileIndex: Int,
        fileCount: Int,
        fileName: String,
        bytesTransferred: Long,
        totalBytes: Long,
        peerName: String = peerNameHint,
    ) {
        _state.value = TransferState.InProgress(
            transferId = transferId,
            direction = direction,
            peerName = peerName,
            fileIndex = fileIndex,
            fileCount = fileCount,
            fileName = fileName,
            bytesTransferred = bytesTransferred,
            totalBytes = totalBytes,
        )
    }

    private fun sendProgressMessage(
        transferId: String,
        fileIndex: Int,
        bytesTransferred: Long,
        totalBytes: Long,
    ) {
        runCatching {
            channel.send(Message.progress(transferId, fileIndex, bytesTransferred, totalBytes))
        }
    }

    private fun checkNotCancelled(transferId: String) {
        if (cancelRequested.get() || peerCancelled) {
            activeTransferId = transferId
            throw TransferCancelledException(byPeer = peerCancelled)
        }
    }

    private fun requireSameTransfer(expected: String, actual: String) {
        if (expected != actual) {
            throw TransferFailedException(
                code = Protocol.ErrorCode.PROTOCOL_VIOLATION,
                message = "Message referenced transfer $actual, expected $expected",
            )
        }
    }

    private fun terminal(state: TransferState): TransferState {
        _state.value = state
        return state
    }

    /**
     * Turn whatever went wrong into a terminal state, tell the peer, and return
     * the exception to rethrow.
     */
    private fun failWith(transferId: String?, cause: Throwable): Throwable {
        when (cause) {
            is TransferRejectedException -> {
                _state.value = TransferState.Rejected(transferId.orEmpty(), peerNameHint, cause.reason)
            }

            is TransferCancelledException -> {
                _state.value = TransferState.Cancelled(transferId, cause.byPeer)
            }

            is TransferFailedException -> {
                _state.value = TransferState.Failed(transferId, cause.code, cause.message)
                notifyPeer(transferId, cause.code, cause.message)
            }

            is PeerIdentityMismatchException -> {
                _state.value = TransferState.Failed(
                    transferId, Protocol.ErrorCode.IDENTITY_MISMATCH, cause.message,
                )
                notifyPeer(transferId, Protocol.ErrorCode.IDENTITY_MISMATCH, cause.message)
            }

            is ProtocolException -> {
                _state.value = TransferState.Failed(transferId, cause.errorCode, cause.message)
                MessageDispatch.autoResponseFor(cause)?.let { response ->
                    runCatching { channel.send(response.error) }
                }
            }

            else -> {
                _state.value = TransferState.Failed(
                    transferId, Protocol.ErrorCode.INTERNAL_ERROR, cause.message,
                )
                notifyPeer(transferId, Protocol.ErrorCode.INTERNAL_ERROR, cause.message)
            }
        }
        return cause
    }

    private fun notifyPeer(transferId: String?, code: String, message: String?) {
        runCatching { channel.send(Message.error(code, message, transferId)) }
    }

    private fun isOutOfSpace(e: IOException): Boolean =
        e.message?.contains("space", ignoreCase = true) == true ||
            e.message?.contains("ENOSPC", ignoreCase = true) == true

    companion object {
        /**
         * 64 KiB: large enough that per-chunk overhead disappears, small enough
         * that progress stays smooth and a cancel takes effect promptly.
         */
        const val CHUNK_BYTES: Int = 64 * 1024
    }
}
