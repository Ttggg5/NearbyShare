package com.nearbyshare.protocol

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Framing rules from PROTOCOL.md §3: a 4-byte big-endian length prefix, a 1 MiB
 * payload ceiling, and hard failures on truncated or malformed input.
 */
class MessageFramingTest {

    private val transferId = "5c1b1e2a-9f3d-4f8a-bc21-7e6b0d5a91f4"

    private fun frameOf(json: String): ByteArray {
        val body = json.toByteArray(StandardCharsets.UTF_8)
        return lengthPrefix(body.size) + body
    }

    private fun lengthPrefix(length: Int): ByteArray = byteArrayOf(
        (length ushr 24 and 0xFF).toByte(),
        (length ushr 16 and 0xFF).toByte(),
        (length ushr 8 and 0xFF).toByte(),
        (length and 0xFF).toByte(),
    )

    @Test
    fun `length prefix is four big-endian bytes of the utf8 payload length`() {
        val message = Message.accept(transferId)
        val frame = MessageCodec.encodeFrame(message)
        val body = MessageCodec.encodeToJson(message).toByteArray(StandardCharsets.UTF_8)

        assertEquals(4 + body.size, frame.size)
        assertEquals(
            body.size,
            (frame[0].toInt() and 0xFF shl 24) or
                (frame[1].toInt() and 0xFF shl 16) or
                (frame[2].toInt() and 0xFF shl 8) or
                (frame[3].toInt() and 0xFF),
        )
        assertTrue(frame.drop(4).toByteArray().contentEquals(body))
    }

    @Test
    fun `length prefix counts bytes not characters for multi-byte names`() {
        // A device name with non-ASCII characters: the prefix must count UTF-8
        // bytes, otherwise the Windows side would read a short frame.
        val message = Message.hello(deviceId = "id", deviceName = "Ünïcôde 手機 📱")
        val frame = MessageCodec.encodeFrame(message)
        val json = MessageCodec.encodeToJson(message)

        assertTrue(json.toByteArray(StandardCharsets.UTF_8).size > json.length)
        assertEquals(4 + json.toByteArray(StandardCharsets.UTF_8).size, frame.size)
        assertEquals(message, MessageCodec.decodeFrame(frame))
    }

    @Test
    fun `back to back frames decode in order from one stream`() {
        val sent = listOf(
            Message.hello("d", "n"),
            Message.offer(transferId, listOf(OfferedFile("f.bin", 5))),
            Message.accept(transferId),
            Message.done(transferId),
        )
        val buffer = ByteArrayOutputStream()
        sent.forEach { MessageCodec.writeFrame(buffer, it) }

        val input = ByteArrayInputStream(buffer.toByteArray())
        assertEquals(sent, sent.indices.map { MessageCodec.readFrame(input) })
        assertNull(MessageCodec.readFrameOrNull(input), "clean EOF at a frame boundary")
    }

    @Test
    fun `clean eof at a frame boundary throws end of stream`() {
        val empty = ByteArrayInputStream(ByteArray(0))
        assertFailsWith<EndOfStreamException> { MessageCodec.readFrame(empty) }
    }

    @Test
    fun `partial length prefix is reported as truncated`() {
        val partial = ByteArrayInputStream(byteArrayOf(0x00, 0x00))
        val failure = assertFailsWith<TruncatedFrameException> { MessageCodec.readFrame(partial) }
        assertTrue(failure.duringLengthPrefix)
        assertEquals(4, failure.bytesExpected)
        assertEquals(2, failure.bytesRead)
    }

    @Test
    fun `payload shorter than the declared length is reported as truncated`() {
        val json = MessageCodec.encodeToJson(Message.accept(transferId))
        val body = json.toByteArray(StandardCharsets.UTF_8)
        val truncated = lengthPrefix(body.size) + body.copyOfRange(0, body.size - 5)

        val failure = assertFailsWith<TruncatedFrameException> {
            MessageCodec.readFrame(ByteArrayInputStream(truncated))
        }
        assertTrue(!failure.duringLengthPrefix)
        assertEquals(body.size, failure.bytesExpected)
        assertEquals(body.size - 5, failure.bytesRead)
    }

    @Test
    fun `a frame delivered in dribs and drabs is still reassembled`() {
        // Mimics a real socket handing back one byte at a time.
        val frame = MessageCodec.encodeFrame(Message.accept(transferId))
        val trickle = object : InputStream() {
            private var position = 0
            override fun read(): Int =
                if (position >= frame.size) -1 else frame[position++].toInt() and 0xFF

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position >= frame.size) return -1
                b[off] = frame[position++]
                return 1
            }
        }
        assertEquals(Message.accept(transferId), MessageCodec.readFrame(trickle))
    }

    @Test
    fun `a length prefix above one mebibyte is rejected without allocating`() {
        val oversized = lengthPrefix(Protocol.MAX_PAYLOAD_BYTES + 1)
        val failure = assertFailsWith<FrameTooLargeException> {
            MessageCodec.readFrame(ByteArrayInputStream(oversized))
        }
        assertEquals((Protocol.MAX_PAYLOAD_BYTES + 1).toLong(), failure.declaredLength)
    }

    @Test
    fun `exactly one mebibyte is still accepted`() {
        // Padding chosen so the encoded envelope is exactly at the ceiling.
        val overhead = MessageCodec.encodeToJson(Message.reject(transferId, ""))
            .toByteArray(StandardCharsets.UTF_8).size
        val padding = "x".repeat(Protocol.MAX_PAYLOAD_BYTES - overhead)
        val message = Message.reject(transferId, padding)

        val frame = MessageCodec.encodeFrame(message)
        assertEquals(Protocol.LENGTH_PREFIX_BYTES + Protocol.MAX_PAYLOAD_BYTES, frame.size)
        assertEquals(message, MessageCodec.readFrame(ByteArrayInputStream(frame)))
    }

    @Test
    fun `a payload one byte over the ceiling is refused on encode`() {
        val overhead = MessageCodec.encodeToJson(Message.reject(transferId, ""))
            .toByteArray(StandardCharsets.UTF_8).size
        val padding = "x".repeat(Protocol.MAX_PAYLOAD_BYTES - overhead + 1)
        assertFailsWith<FrameTooLargeException> {
            MessageCodec.encodeFrame(Message.reject(transferId, padding))
        }
    }

    @Test
    fun `an unsigned length prefix with the high bit set is not read as negative`() {
        // 0xFFFFFFFF must be seen as 4294967295, not -1.
        val hostile = byteArrayOf(-1, -1, -1, -1)
        val failure = assertFailsWith<FrameTooLargeException> {
            MessageCodec.readFrame(ByteArrayInputStream(hostile))
        }
        assertEquals(4_294_967_295L, failure.declaredLength)
    }

    @Test
    fun `malformed json is rejected`() {
        assertFailsWith<MalformedMessageException> {
            MessageCodec.readFrame(ByteArrayInputStream(frameOf("{ this is not json")))
        }
    }

    @Test
    fun `a json value that is not an object is rejected`() {
        assertFailsWith<MalformedMessageException> {
            MessageCodec.decodeFromJson("[1, 2, 3]")
        }
        assertFailsWith<MalformedMessageException> {
            MessageCodec.decodeFromJson("\"HELLO\"")
        }
    }

    @Test
    fun `an envelope missing a required field is rejected`() {
        assertFailsWith<MalformedMessageException> {
            MessageCodec.decodeFromJson("""{"v":1,"id":"x","payload":{}}""")
        }
        assertFailsWith<MalformedMessageException> {
            MessageCodec.decodeFromJson("""{"type":"ACCEPT","id":"x","payload":{}}""")
        }
        assertFailsWith<MalformedMessageException> {
            MessageCodec.decodeFromJson("""{"v":1,"type":"ACCEPT","payload":{}}""")
        }
    }

    @Test
    fun `a known type with an invalid payload is rejected`() {
        assertFailsWith<MalformedMessageException> {
            MessageCodec.decodeFromJson("""{"v":1,"type":"ACCEPT","id":"x","payload":{}}""")
        }
        assertFailsWith<MalformedMessageException> {
            MessageCodec.decodeFromJson(
                """{"v":1,"type":"PROGRESS","id":"x","payload":{"transferId":"x","fileIndex":"nope","bytesTransferred":1,"totalBytes":2}}""",
            )
        }
        assertFailsWith<MalformedMessageException> {
            MessageCodec.decodeFromJson(
                """{"v":1,"type":"DONE","id":"x","payload":{"transferId":"x","fileIndex":"everything"}}""",
            )
        }
        assertFailsWith<MalformedMessageException> {
            MessageCodec.decodeFromJson(
                """{"v":1,"type":"DONE","id":"x","payload":{"transferId":"x","fileIndex":[0]}}""",
            )
        }
    }

    @Test
    fun `unknown payload fields are ignored so additive changes do not break older peers`() {
        val message = MessageCodec.decodeFromJson(
            """{"v":1,"type":"ACCEPT","id":"x","payload":{"transferId":"t","futureField":true},"futureEnvelopeField":7}""",
        )
        assertEquals(Message.Accept("x", AcceptPayload("t")), message)
    }

    @Test
    fun `decodeFrame rejects an array shorter than the length prefix`() {
        val failure = assertFailsWith<TruncatedFrameException> {
            MessageCodec.decodeFrame(byteArrayOf(0x00, 0x01))
        }
        assertTrue(failure.duringLengthPrefix)
    }

    @Test
    fun `every protocol exception carries an error code`() {
        val failures = listOf(
            FrameTooLargeException(1) to Protocol.ErrorCode.FRAME_TOO_LARGE,
            TruncatedFrameException(4, 1, true) to Protocol.ErrorCode.MALFORMED_MESSAGE,
            MalformedMessageException("x") to Protocol.ErrorCode.MALFORMED_MESSAGE,
            UnsupportedProtocolVersionException(9, "x") to Protocol.ErrorCode.UNSUPPORTED_VERSION,
            EndOfStreamException() to Protocol.ErrorCode.IO_ERROR,
        )
        failures.forEach { (failure, code) -> assertEquals(code, failure.errorCode) }
    }
}
