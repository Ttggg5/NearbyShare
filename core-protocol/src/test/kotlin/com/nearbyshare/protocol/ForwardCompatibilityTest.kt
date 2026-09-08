package com.nearbyshare.protocol

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The forward-compatibility rule of PROTOCOL.md §4: an unrecognised `type` must
 * not crash or abruptly disconnect. It must be answered with
 * `ERROR{code: "UNSUPPORTED_TYPE"}` echoing the offending `id`, and the
 * connection must remain usable afterwards.
 *
 * PROTOCOL.md §4 requires both implementations to have a test proving this.
 */
class ForwardCompatibilityTest {

    private val transferId = "5c1b1e2a-9f3d-4f8a-bc21-7e6b0d5a91f4"

    private fun unknownFrame(type: String, id: String): ByteArray =
        MessageCodec.encodeFrame(
            MessageCodec.decodeFromJson("""{"v":1,"type":"$type","id":"$id","payload":{"x":1}}"""),
        )

    @Test
    fun `an unrecognised type decodes instead of throwing`() {
        val message = MessageCodec.decodeFromJson(
            """{"v":1,"type":"CLIPBOARD_OFFER","id":"abc","payload":{"mime":"text/plain"}}""",
        )
        assertTrue(message is Message.Unknown, "expected Message.Unknown, got ${message::class.simpleName}")
        assertEquals("CLIPBOARD_OFFER", message.type)
        assertNull(message.messageType)
    }

    @Test
    fun `dispatch answers an unrecognised type with UNSUPPORTED_TYPE echoing the id`() {
        val unknown = MessageCodec.decodeFromJson(
            """{"v":1,"type":"CLIPBOARD_OFFER","id":"abc","payload":{}}""",
        )
        val response = assertNotNull(MessageDispatch.autoResponseFor(unknown))

        assertEquals(Protocol.VERSION, response.error.v)
        assertEquals(MessageType.ERROR.wire, response.error.type)
        assertEquals("abc", response.error.id, "the reply must echo the offending message's id")
        assertEquals(Protocol.ErrorCode.UNSUPPORTED_TYPE, response.error.payload.code)
        assertNotNull(response.error.payload.message)
        assertEquals(
            MessageDispatch.Disposition.CONTINUE,
            response.disposition,
            "the connection must stay in a valid state for further messages",
        )
    }

    @Test
    fun `the UNSUPPORTED_TYPE reply matches the json shape in PROTOCOL section 4`() {
        val unknown = MessageCodec.decodeFromJson("""{"v":1,"type":"FUTURE","id":"xyz","payload":{}}""")
        val error = MessageDispatch.autoResponseFor(unknown)!!.error

        val reEncoded = MessageCodec.decodeFromJson(MessageCodec.encodeToJson(error)) as Message.Error
        assertEquals("xyz", reEncoded.id)
        assertEquals(Protocol.ErrorCode.UNSUPPORTED_TYPE, reEncoded.payload.code)
        assertNull(reEncoded.payload.transferId, "a type-level error is not scoped to a transfer")
    }

    @Test
    fun `dispatch has nothing to add for messages this build understands`() {
        val known = listOf(
            Message.hello("d", "n"),
            Message.offer(transferId, listOf(OfferedFile("f", 1))),
            Message.accept(transferId),
            Message.reject(transferId, "no"),
            Message.progress(transferId, 0, 1, 2),
            Message.done(transferId),
            Message.error(Protocol.ErrorCode.IO_ERROR, "x", transferId),
            Message.cancel(transferId),
        )
        known.forEach { assertNull(MessageDispatch.autoResponseFor(it), "no auto-response expected for ${it.type}") }
    }

    @Test
    fun `an unsupported version is answered with UNSUPPORTED_VERSION and closes`() {
        val failure = runCatching {
            MessageCodec.decodeFromJson("""{"v":99,"type":"HELLO","id":"m1","payload":{}}""")
        }.exceptionOrNull() as UnsupportedProtocolVersionException

        val response = assertNotNull(MessageDispatch.autoResponseFor(failure))
        assertEquals("m1", response.error.id)
        assertEquals(Protocol.ErrorCode.UNSUPPORTED_VERSION, response.error.payload.code)
        assertEquals(MessageDispatch.Disposition.CLOSE, response.disposition)
    }

    @Test
    fun `framing failures close the connection`() {
        val closing = listOf(
            FrameTooLargeException(2_000_000),
            MalformedMessageException("bad json"),
            TruncatedFrameException(4, 2, true),
        )
        for (failure in closing) {
            val response = assertNotNull(MessageDispatch.autoResponseFor(failure), failure.toString())
            assertEquals(failure.errorCode, response.error.payload.code)
            assertEquals(MessageDispatch.Disposition.CLOSE, response.disposition)
        }
    }

    @Test
    fun `a clean disconnect needs no reply`() {
        assertNull(MessageDispatch.autoResponseFor(EndOfStreamException()))
    }

    @Test
    fun `the stream stays usable after an unrecognised message`() {
        // Full-loop proof: a peer sends UNKNOWN, KNOWN. We must answer the
        // first with UNSUPPORTED_TYPE and still read the second.
        val inbound = ByteArrayOutputStream().apply {
            write(unknownFrame("CLIPBOARD_OFFER", "u1"))
            MessageCodec.writeFrame(this, Message.accept(transferId))
        }.toByteArray()

        val replies = ByteArrayOutputStream()
        val channel = MessageChannel(ByteArrayInputStream(inbound), replies)

        val first = channel.receive()
        MessageDispatch.autoResponseFor(first)?.let { channel.send(it.error) }

        val second = channel.receive()
        assertEquals(Message.accept(transferId), second, "the known message after the unknown one must still arrive")
        assertNull(channel.receiveOrNull())

        val sent = MessageCodec.readFrame(ByteArrayInputStream(replies.toByteArray())) as Message.Error
        assertEquals("u1", sent.id)
        assertEquals(Protocol.ErrorCode.UNSUPPORTED_TYPE, sent.payload.code)
    }

    @Test
    fun `a message channel exchanges frames over a real stream pair`() {
        val toServer = PipedOutputStream()
        val serverIn = PipedInputStream(toServer, 64 * 1024)
        val toClient = PipedOutputStream()
        val clientIn = PipedInputStream(toClient, 64 * 1024)

        val client = MessageChannel(clientIn, toServer)
        val server = MessageChannel(serverIn, toClient)

        client.send(Message.hello("device-a", "Phone"))
        val hello = server.receive() as Message.Hello
        assertEquals("device-a", hello.payload.deviceId)

        server.send(Message.accept(transferId))
        assertEquals(Message.accept(transferId), client.receive())
    }
}
