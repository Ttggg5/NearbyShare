package com.nearbyshare.network.transfer

import com.nearbyshare.network.InMemoryFileSink
import com.nearbyshare.network.InMemoryFileSource
import com.nearbyshare.network.SocketPair
import com.nearbyshare.network.sha256Hex
import com.nearbyshare.network.testBytes
import com.nearbyshare.protocol.Message
import com.nearbyshare.protocol.MessageChannel
import com.nearbyshare.protocol.MessageCodec
import com.nearbyshare.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.Socket
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The MVP flow of PROTOCOL.md §5, end to end over a real loopback socket pair.
 *
 * TLS is deliberately left out here -- [com.nearbyshare.network.tls.TlsLoopbackTest]
 * covers that -- so these tests are about the message exchange and the raw byte
 * streaming, which is where the interesting failure modes live.
 */
class TransferSessionTest {

    private val senderIdentity = LocalIdentity("device-sender", "Sender Phone")
    private val receiverIdentity = LocalIdentity("device-receiver", "Receiver Phone")

    private fun channelOf(socket: Socket) =
        MessageChannel(socket.inputStream, socket.outputStream) { socket.close() }

    /** Run a sender and a receiver against each other and return both outcomes. */
    private fun exchange(
        sources: List<FileSource>,
        sink: InMemoryFileSink,
        decision: (OfferSeen) -> ApprovalDecision = { ApprovalDecision.Accept },
        transferId: String = "5c1b1e2a-9f3d-4f8a-bc21-7e6b0d5a91f4",
    ): Outcome = SocketPair.open().use { pair ->
        runBlocking {
            val senderSession = TransferSession(
                channel = channelOf(pair.client),
                localIdentity = senderIdentity,
                peerNameHint = "Receiver Phone",
            )
            val receiverSession = TransferSession(
                channel = channelOf(pair.server),
                localIdentity = receiverIdentity,
            )

            val receiving = async(Dispatchers.IO) {
                runCatching {
                    receiverSession.runAsReceiver(
                        sink = sink,
                        approve = { offer, hello -> decision(OfferSeen(offer.transferId, offer.files.map { it.name }, hello.deviceId, hello.deviceName)) },
                    )
                }
            }
            val sending = async(Dispatchers.IO) {
                runCatching { senderSession.runAsSender(sources, transferId) }
            }

            val sent = sending.await()
            val received = receiving.await()
            senderSession.close()
            receiverSession.close()
            Outcome(sent, received, senderSession.state.value, receiverSession.state.value)
        }
    }

    data class OfferSeen(
        val transferId: String,
        val fileNames: List<String>,
        val peerDeviceId: String,
        val peerDeviceName: String,
    )

    data class Outcome(
        val sent: Result<TransferState>,
        val received: Result<TransferState>,
        val senderState: TransferState,
        val receiverState: TransferState,
    )

    @Test
    fun `a single accepted file arrives byte for byte`() {
        val payload = testBytes(300_000)
        val sink = InMemoryFileSink()

        val outcome = exchange(listOf(InMemoryFileSource("photo.jpg", payload, "image/jpeg")), sink)

        assertTrue(outcome.sent.isSuccess, "send failed: ${outcome.sent.exceptionOrNull()}")
        assertTrue(outcome.received.isSuccess, "receive failed: ${outcome.received.exceptionOrNull()}")
        assertContentEquals(payload, sink.committed["photo.jpg"])
        assertEquals(listOf("photo.jpg"), sink.committed.keys.toList())

        val completed = assertIs<TransferState.Completed>(outcome.receiverState)
        assertEquals(listOf("photo.jpg"), completed.fileNames)
        assertEquals("Sender Phone", completed.peerName, "the receiver names the peer from HELLO")
        assertEquals(listOf("memory://photo.jpg"), completed.savedLocations)

        assertIs<TransferState.Completed>(outcome.senderState)
    }

    @Test
    fun `several files stream back to back in offer order`() {
        val first = testBytes(70_000, seed = 1)
        val second = testBytes(1, seed = 2)
        val third = testBytes(200_000, seed = 3)
        val sink = InMemoryFileSink()

        val outcome = exchange(
            listOf(
                InMemoryFileSource("a.bin", first),
                InMemoryFileSource("b.bin", second),
                InMemoryFileSource("c.bin", third),
            ),
            sink,
        )

        assertTrue(outcome.sent.isSuccess, "send failed: ${outcome.sent.exceptionOrNull()}")
        assertEquals(listOf("a.bin", "b.bin", "c.bin"), sink.committed.keys.toList())
        assertContentEquals(first, sink.committed["a.bin"])
        assertContentEquals(second, sink.committed["b.bin"])
        assertContentEquals(third, sink.committed["c.bin"])
    }

    @Test
    fun `a zero byte file is still delivered`() {
        val sink = InMemoryFileSink()
        val outcome = exchange(listOf(InMemoryFileSource("empty.txt", ByteArray(0), "text/plain")), sink)

        assertTrue(outcome.sent.isSuccess, "send failed: ${outcome.sent.exceptionOrNull()}")
        assertContentEquals(ByteArray(0), sink.committed["empty.txt"])
    }

    @Test
    fun `a rejected offer moves no bytes and leaves nothing behind`() {
        val sink = InMemoryFileSink()
        val outcome = exchange(
            listOf(InMemoryFileSource("secret.txt", testBytes(1000))),
            sink,
            decision = { ApprovalDecision.Reject("Not right now") },
        )

        val failure = outcome.sent.exceptionOrNull()
        assertIs<TransferRejectedException>(failure)
        assertEquals("Not right now", failure.reason)

        assertTrue(sink.committed.isEmpty(), "a rejected transfer must write nothing")
        val rejected = assertIs<TransferState.Rejected>(outcome.receiverState)
        assertEquals("Not right now", rejected.reason)
        assertIs<TransferState.Rejected>(outcome.senderState)
    }

    @Test
    fun `the receiver is told who is calling before it prompts`() {
        var seen: OfferSeen? = null
        exchange(
            listOf(InMemoryFileSource("f.bin", testBytes(10))),
            InMemoryFileSink(),
            decision = { offer -> seen = offer; ApprovalDecision.Accept },
        )

        val prompt = requireNotNull(seen)
        assertEquals("device-sender", prompt.peerDeviceId)
        assertEquals("Sender Phone", prompt.peerDeviceName)
        assertEquals(listOf("f.bin"), prompt.fileNames)
    }

    @Test
    fun `a matching checksum passes verification`() {
        val payload = testBytes(50_000)
        val sink = InMemoryFileSink()

        val outcome = exchange(
            listOf(InMemoryFileSource("verified.bin", payload, sha256 = sha256Hex(payload))),
            sink,
        )

        assertTrue(outcome.sent.isSuccess, "send failed: ${outcome.sent.exceptionOrNull()}")
        assertContentEquals(payload, sink.committed["verified.bin"])
    }

    @Test
    fun `a wrong checksum fails the transfer and discards the file`() {
        val payload = testBytes(50_000)
        val sink = InMemoryFileSink()

        val outcome = exchange(
            listOf(InMemoryFileSource("tampered.bin", payload, sha256 = "0".repeat(64))),
            sink,
        )

        val failure = assertIs<TransferFailedException>(outcome.received.exceptionOrNull())
        assertEquals(Protocol.ErrorCode.CHECKSUM_MISMATCH, failure.code)

        assertTrue(sink.committed.isEmpty(), "a file that failed verification must not be committed")
        assertEquals(listOf("tampered.bin"), sink.aborted, "the partial file must be discarded")
    }

    @Test
    fun `a write failure is reported as DISK_FULL and the partial file is discarded`() {
        val sink = InMemoryFileSink(failWriteAfterBytes = 4_096)

        val outcome = exchange(listOf(InMemoryFileSource("big.bin", testBytes(200_000))), sink)

        val failure = assertIs<TransferFailedException>(outcome.received.exceptionOrNull())
        assertEquals(Protocol.ErrorCode.DISK_FULL, failure.code)
        assertTrue(sink.committed.isEmpty())
        assertEquals(listOf("big.bin"), sink.aborted)

        val failed = assertIs<TransferState.Failed>(outcome.receiverState)
        assertEquals(Protocol.ErrorCode.DISK_FULL, failed.code)
    }

    @Test
    fun `a path traversal file name cannot escape the download folder`() {
        val payload = testBytes(64)
        val sink = InMemoryFileSink()

        val outcome = exchange(
            listOf(InMemoryFileSource("../../../etc/passwd", payload)),
            sink,
        )

        assertTrue(outcome.sent.isSuccess, "send failed: ${outcome.sent.exceptionOrNull()}")
        assertEquals(listOf("passwd"), sink.committed.keys.toList())
    }

    @Test
    fun `the sender cancelling stops the transfer and the receiver keeps nothing`() {
        val sink = InMemoryFileSink()
        SocketPair.open().use { pair ->
            runBlocking {
                val senderSession = TransferSession(channelOf(pair.client), senderIdentity)
                val receiverSession = TransferSession(channelOf(pair.server), receiverIdentity)

                val receiving = async(Dispatchers.IO) {
                    runCatching {
                        receiverSession.runAsReceiver(sink, approve = { _, _ -> ApprovalDecision.Accept })
                    }
                }
                val sending = async(Dispatchers.IO) {
                    runCatching {
                        senderSession.runAsSender(
                            listOf(InMemoryFileSource("large.bin", testBytes(8 * 1024 * 1024))),
                            "t-cancel",
                        )
                    }
                }

                // Let the offer be accepted and the stream get going first.
                while (senderSession.state.value !is TransferState.InProgress) {
                    kotlinx.coroutines.delay(5)
                }
                senderSession.cancel()

                val sent = sending.await()
                val received = receiving.await()
                senderSession.close()
                receiverSession.close()

                assertIs<TransferCancelledException>(sent.exceptionOrNull())
                assertTrue(received.isFailure, "the receiver must not report success after a cancel")
                assertTrue(sink.committed.isEmpty(), "a cancelled file must never be committed")
                assertEquals(listOf("large.bin"), sink.aborted)
                assertIs<TransferState.Cancelled>(senderSession.state.value)
            }
        }
    }

    @Test
    fun `an unrecognised message before the verdict is answered and the flow continues`() {
        // PROTOCOL.md §4, proven inside the real flow rather than in isolation:
        // a future message type arriving while the sender waits for ACCEPT must
        // draw an UNSUPPORTED_TYPE reply and must not break the transfer.
        val payload = testBytes(20_000)
        val sink = InMemoryFileSink()

        SocketPair.open().use { pair ->
            runBlocking {
                val senderSession = TransferSession(channelOf(pair.client), senderIdentity)
                val peer = MessageChannel(pair.server.inputStream, pair.server.outputStream)

                val sending = async(Dispatchers.IO) {
                    runCatching {
                        senderSession.runAsSender(
                            listOf(InMemoryFileSource("f.bin", payload)),
                            "t-forward-compat",
                        )
                    }
                }

                val hello = peer.receive()
                assertIs<Message.Hello>(hello)
                val offer = peer.receive()
                assertIs<Message.Offer>(offer)

                // A message type from a future version of the protocol.
                peer.send(
                    MessageCodec.decodeFromJson(
                        """{"v":1,"type":"CLIPBOARD_OFFER","id":"future-1","payload":{"mime":"text/plain"}}""",
                    ),
                )

                val reply = assertIs<Message.Error>(peer.receive())
                assertEquals(Protocol.ErrorCode.UNSUPPORTED_TYPE, reply.payload.code)
                assertEquals("future-1", reply.id, "the reply must echo the offending id")

                // The connection is still usable: accept and finish normally.
                peer.send(Message.accept("t-forward-compat"))
                val received = ByteArray(payload.size)
                var read = 0
                while (read < received.size) {
                    val n = peer.rawIn.read(received, read, received.size - read)
                    if (n < 0) break
                    read += n
                }
                val done = assertIs<Message.Done>(peer.receive())
                assertEquals("t-forward-compat", done.payload.transferId)

                assertTrue(sending.await().isSuccess)
                assertContentEquals(payload, received)
            }
        }
        assertTrue(sink.committed.isEmpty())
    }

    @Test
    fun `an offer with no files is refused before the user is asked`() {
        SocketPair.open().use { pair ->
            runBlocking {
                val receiverSession = TransferSession(channelOf(pair.server), receiverIdentity)
                val peer = MessageChannel(pair.client.inputStream, pair.client.outputStream)

                val receiving = async(Dispatchers.IO) {
                    runCatching {
                        receiverSession.runAsReceiver(
                            InMemoryFileSink(),
                            approve = { _, _ -> error("the user must not be prompted for an empty offer") },
                        )
                    }
                }

                peer.send(Message.hello("device-sender", "Sender Phone"))
                peer.send(Message.offer("t-empty", emptyList()))

                val failure = assertIs<TransferFailedException>(receiving.await().exceptionOrNull())
                assertEquals(Protocol.ErrorCode.PROTOCOL_VIOLATION, failure.code)
                receiverSession.close()
            }
        }
    }

    @Test
    fun `the first message must be HELLO`() {
        SocketPair.open().use { pair ->
            runBlocking {
                val receiverSession = TransferSession(channelOf(pair.server), receiverIdentity)
                val peer = MessageChannel(pair.client.inputStream, pair.client.outputStream)

                val receiving = async(Dispatchers.IO) {
                    runCatching {
                        receiverSession.runAsReceiver(
                            InMemoryFileSink(),
                            approve = { _, _ -> ApprovalDecision.Accept },
                        )
                    }
                }

                peer.send(Message.offer("t-no-hello", emptyList()))

                val failure = assertIs<TransferFailedException>(receiving.await().exceptionOrNull())
                assertEquals(Protocol.ErrorCode.PROTOCOL_VIOLATION, failure.code)
                assertTrue(failure.message.orEmpty().contains("HELLO"))
                receiverSession.close()
            }
        }
    }

    @Test
    fun `the peer identity hook can veto a connection`() {
        SocketPair.open().use { pair ->
            runBlocking {
                val receiverSession = TransferSession(channelOf(pair.server), receiverIdentity)
                val peer = MessageChannel(pair.client.inputStream, pair.client.outputStream)

                val receiving = async(Dispatchers.IO) {
                    runCatching {
                        receiverSession.runAsReceiver(
                            sink = InMemoryFileSink(),
                            onPeerIdentified = {
                                throw com.nearbyshare.network.tls.PeerIdentityChangedException(
                                    it.deviceId, "a".repeat(64), "b".repeat(64),
                                )
                            },
                            approve = { _, _ -> error("must not reach the prompt") },
                        )
                    }
                }

                peer.send(Message.hello("device-sender", "Sender Phone"))

                assertTrue(receiving.await().isFailure)
                val failed = assertIs<TransferState.Failed>(receiverSession.state.value)
                assertEquals(Protocol.ErrorCode.INTERNAL_ERROR, failed.code)
                receiverSession.close()
            }
        }
    }

    @Test
    fun `progress is published as bytes move and ends at the total`() {
        val payload = testBytes(400_000)
        val seen = mutableListOf<TransferState.InProgress>()
        val sink = InMemoryFileSink()

        SocketPair.open().use { pair ->
            runBlocking {
                val senderSession = TransferSession(channelOf(pair.client), senderIdentity)
                val receiverSession = TransferSession(channelOf(pair.server), receiverIdentity)

                val watching = async(Dispatchers.IO) {
                    while (true) {
                        val state = receiverSession.state.value
                        if (state is TransferState.InProgress) seen += state
                        if (state.isTerminal) break
                        kotlinx.coroutines.delay(1)
                    }
                }
                val receiving = async(Dispatchers.IO) {
                    runCatching { receiverSession.runAsReceiver(sink, approve = { _, _ -> ApprovalDecision.Accept }) }
                }
                val sending = async(Dispatchers.IO) {
                    runCatching {
                        senderSession.runAsSender(listOf(InMemoryFileSource("p.bin", payload)), "t-progress")
                    }
                }

                assertTrue(sending.await().isSuccess)
                assertTrue(receiving.await().isSuccess)
                watching.await()
                senderSession.close()
                receiverSession.close()
            }
        }

        assertTrue(seen.isNotEmpty(), "the receiver must publish progress")
        assertTrue(seen.all { it.direction == TransferDirection.RECEIVING })
        assertTrue(seen.all { it.totalBytes == payload.size.toLong() })
        assertTrue(
            seen.zipWithNext().all { (a, b) -> b.bytesTransferred >= a.bytesTransferred },
            "progress must not go backwards",
        )
        assertEquals(payload.size.toLong(), seen.last().bytesTransferred)
        assertEquals(1f, seen.last().fraction)
    }
}
