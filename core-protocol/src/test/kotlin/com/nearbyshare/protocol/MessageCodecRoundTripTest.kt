package com.nearbyshare.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trips every MVP message type through JSON and through the framed form,
 * and pins the exact wire field names from PROTOCOL.md §5.
 */
class MessageCodecRoundTripTest {

    private val transferId = "5c1b1e2a-9f3d-4f8a-bc21-7e6b0d5a91f4"

    private fun assertRoundTrips(message: Message) {
        val json = MessageCodec.encodeToJson(message)
        assertEquals(message, MessageCodec.decodeFromJson(json), "JSON round trip of $message")

        val frame = MessageCodec.encodeFrame(message)
        assertEquals(message, MessageCodec.decodeFrame(frame), "frame round trip of $message")

        val streamed = MessageCodec.readFrame(frame.inputStream())
        assertEquals(message, streamed, "stream round trip of $message")
    }

    private fun envelopeOf(message: Message): JsonObject =
        Json.parseToJsonElement(MessageCodec.encodeToJson(message)).jsonObject

    private fun payloadOf(message: Message): JsonObject =
        envelopeOf(message)["payload"]!!.jsonObject

    @Test
    fun `hello round trips`() {
        val message = Message.hello(
            deviceId = "1b7d6f60-9d2c-4a1e-9c1a-2f2f9a1e6d31",
            deviceName = "Pixel 8",
        )
        assertRoundTrips(message)

        val payload = payloadOf(message)
        assertEquals(
            setOf("deviceId", "deviceName", "protocolVersion"),
            payload.keys,
            "HELLO payload keys must match PROTOCOL.md §5",
        )
        assertEquals(1, payload["protocolVersion"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `offer round trips including optional file fields`() {
        val message = Message.offer(
            transferId = transferId,
            files = listOf(
                OfferedFile("a.bin", 10, "application/octet-stream", "a".repeat(64)),
                OfferedFile("b.txt", 0, "text/plain"),
                OfferedFile("c.dat", 1234),
            ),
        )
        assertRoundTrips(message)

        val payload = payloadOf(message)
        assertEquals(setOf("transferId", "files"), payload.keys)

        val files: JsonArray = payload["files"]!!.jsonArray
        val second = files[1].jsonObject
        assertEquals(setOf("name", "size", "mime"), second.keys, "absent sha256 must be omitted, not null")
        val third = files[2].jsonObject
        assertEquals(setOf("name", "size"), third.keys, "absent mime and sha256 must both be omitted")
    }

    @Test
    fun `offer total bytes sums declared file sizes`() {
        val offer = Message.offer(
            transferId = transferId,
            files = listOf(OfferedFile("a", 100), OfferedFile("b", 23)),
        )
        assertEquals(123L, offer.payload.totalBytes)
    }

    @Test
    fun `accept round trips`() {
        val message = Message.accept(transferId)
        assertRoundTrips(message)
        assertEquals(setOf("transferId"), payloadOf(message).keys)
    }

    @Test
    fun `reject round trips with and without a reason`() {
        val withReason = Message.reject(transferId, "Declined by user")
        assertRoundTrips(withReason)
        assertEquals(setOf("transferId", "reason"), payloadOf(withReason).keys)

        val withoutReason = Message.reject(transferId)
        assertRoundTrips(withoutReason)
        assertEquals(setOf("transferId"), payloadOf(withoutReason).keys)
        assertNull(withoutReason.payload.reason)
    }

    @Test
    fun `progress round trips`() {
        val message = Message.progress(
            transferId = transferId,
            fileIndex = 2,
            bytesTransferred = 4096L,
            totalBytes = 2_097_152L,
        )
        assertRoundTrips(message)
        assertEquals(
            setOf("transferId", "fileIndex", "bytesTransferred", "totalBytes"),
            payloadOf(message).keys,
        )
    }

    @Test
    fun `progress survives sizes beyond 32 bits`() {
        val huge = 8L * 1024 * 1024 * 1024 // 8 GiB
        val message = Message.progress(transferId, 0, huge - 1, huge)
        assertRoundTrips(message)
        assertEquals(huge, (MessageCodec.decodeFromJson(MessageCodec.encodeToJson(message)) as Message.Progress)
            .payload.totalBytes)
    }

    @Test
    fun `done encodes an integer file index as a json number`() {
        val message = Message.done(transferId, FileIndex.Index(3))
        assertRoundTrips(message)

        val fileIndex = payloadOf(message)["fileIndex"]!!.jsonPrimitive
        assertTrue(!fileIndex.isString, "integer fileIndex must be a JSON number")
        assertEquals("3", fileIndex.content)
    }

    @Test
    fun `done encodes the all sentinel as the json string all`() {
        val message = Message.done(transferId, FileIndex.All)
        assertRoundTrips(message)

        val fileIndex = payloadOf(message)["fileIndex"]!!.jsonPrimitive
        assertTrue(fileIndex.isString, "the all sentinel must be a JSON string")
        assertEquals("all", fileIndex.content)
    }

    @Test
    fun `done defaults to the all sentinel`() {
        assertEquals(FileIndex.All, Message.done(transferId).payload.fileIndex)
    }

    @Test
    fun `error round trips with and without a transfer id`() {
        val scoped = Message.error(
            code = Protocol.ErrorCode.DISK_FULL,
            message = "Not enough free space",
            transferId = transferId,
        )
        assertRoundTrips(scoped)
        assertEquals(setOf("transferId", "code", "message"), payloadOf(scoped).keys)
        assertEquals(transferId, scoped.id, "a transfer-scoped ERROR echoes the transfer id as the envelope id")

        val connectionScoped = Message.error(code = Protocol.ErrorCode.UNSUPPORTED_VERSION)
        assertRoundTrips(connectionScoped)
        assertEquals(
            setOf("code"),
            payloadOf(connectionScoped).keys,
            "absent transferId and message must be omitted, not null",
        )
    }

    @Test
    fun `cancel round trips`() {
        val message = Message.cancel(transferId)
        assertRoundTrips(message)
        assertEquals(setOf("transferId"), payloadOf(message).keys)
    }

    @Test
    fun `unknown message round trips with its payload preserved verbatim`() {
        val original = """{"v":1,"type":"CLIPBOARD_OFFER","id":"abc","payload":{"mime":"text/plain","length":42}}"""
        val decoded = MessageCodec.decodeFromJson(original) as Message.Unknown

        assertEquals("CLIPBOARD_OFFER", decoded.type)
        assertEquals(JsonPrimitive(42), decoded.payload["length"])

        assertRoundTrips(decoded)
    }

    @Test
    fun `every envelope carries v type id and payload`() {
        val messages = listOf(
            Message.hello("d", "n"),
            Message.offer(transferId, listOf(OfferedFile("f", 1))),
            Message.accept(transferId),
            Message.reject(transferId, "no"),
            Message.progress(transferId, 0, 1, 2),
            Message.done(transferId),
            Message.error(Protocol.ErrorCode.IO_ERROR, "x", transferId),
            Message.cancel(transferId),
        )
        val seenTypes = mutableSetOf<String>()
        for (message in messages) {
            val envelope = envelopeOf(message)
            assertEquals(setOf("v", "type", "id", "payload"), envelope.keys, "envelope shape of ${message.type}")
            assertEquals(1, envelope["v"]!!.jsonPrimitive.content.toInt())
            assertEquals(message.type, envelope["type"]!!.jsonPrimitive.content)
            seenTypes += message.type
        }
        assertEquals(
            MessageType.entries.map { it.wire }.toSet(),
            seenTypes,
            "all eight MVP message types must be covered",
        )
    }

    @Test
    fun `message type lookup covers exactly the mvp set`() {
        for (type in MessageType.entries) {
            assertEquals(type, MessageType.fromWireOrNull(type.wire))
        }
        assertNull(MessageType.fromWireOrNull("CLIPBOARD_OFFER"))
        assertNull(MessageType.fromWireOrNull("hello"), "type matching is case sensitive")
    }

    @Test
    fun `transfer id accessor reflects payload scope`() {
        assertNull(Message.hello("d", "n").transferId)
        assertEquals(transferId, Message.accept(transferId).transferId)
        assertNull(Message.error(Protocol.ErrorCode.UNSUPPORTED_VERSION).transferId)
    }
}
