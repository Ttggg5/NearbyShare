package com.nearbyshare.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cross-implementation contract test (PROTOCOL.md §7).
 *
 * `src/test/resources/fixtures/` holds one hand-written JSON document per MVP
 * message type. The identical set is checked into the Windows repository, whose
 * `System.Text.Json` implementation must decode them to the same values. If a
 * field name or JSON shape ever drifts between the two codebases, these
 * assertions are what catches it.
 *
 * The fixtures are authored by hand from PROTOCOL.md §5 -- never regenerate
 * them from [MessageCodec], or the test would only be checking the codec
 * against itself.
 */
class FixtureContractTest {

    private fun fixture(name: String): String {
        val stream = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "Missing fixture: /fixtures/$name"
        }
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    private fun decode(name: String): Message = MessageCodec.decodeFromJson(fixture(name))

    @Test
    fun `hello fixture decodes`() {
        val message = decode("HELLO.json") as Message.Hello
        assertEquals("9f2c7f4e-2a0b-4a3e-8b47-0f4f2f4f4a11", message.id)
        assertEquals(1, message.v)
        assertEquals("1b7d6f60-9d2c-4a1e-9c1a-2f2f9a1e6d31", message.payload.deviceId)
        assertEquals("Pixel 8", message.payload.deviceName)
        assertEquals(1, message.payload.protocolVersion)
    }

    @Test
    fun `offer fixture decodes including the optional sha256`() {
        val message = decode("OFFER.json") as Message.Offer
        assertEquals("5c1b1e2a-9f3d-4f8a-bc21-7e6b0d5a91f4", message.id)
        assertEquals("5c1b1e2a-9f3d-4f8a-bc21-7e6b0d5a91f4", message.payload.transferId)
        assertEquals(2, message.payload.files.size)

        val first = message.payload.files[0]
        assertEquals("holiday.jpg", first.name)
        assertEquals(2_097_152L, first.size)
        assertEquals("image/jpeg", first.mime)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", first.sha256)

        val second = message.payload.files[1]
        assertEquals("notes.txt", second.name)
        assertEquals(12_345L, second.size)
        assertEquals("text/plain", second.mime)
        assertNull(second.sha256, "sha256 is optional and absent here")

        assertEquals(2_109_497L, message.payload.totalBytes)
    }

    @Test
    fun `accept fixture decodes`() {
        val message = decode("ACCEPT.json") as Message.Accept
        assertEquals("5c1b1e2a-9f3d-4f8a-bc21-7e6b0d5a91f4", message.payload.transferId)
    }

    @Test
    fun `reject fixture decodes`() {
        val message = decode("REJECT.json") as Message.Reject
        assertEquals("5c1b1e2a-9f3d-4f8a-bc21-7e6b0d5a91f4", message.payload.transferId)
        assertEquals("Declined by user", message.payload.reason)
    }

    @Test
    fun `progress fixture decodes`() {
        val message = decode("PROGRESS.json") as Message.Progress
        assertEquals("5c1b1e2a-9f3d-4f8a-bc21-7e6b0d5a91f4", message.payload.transferId)
        assertEquals(0, message.payload.fileIndex)
        assertEquals(4096L, message.payload.bytesTransferred)
        assertEquals(2_097_152L, message.payload.totalBytes)
    }

    @Test
    fun `done fixture decodes the all sentinel`() {
        val message = decode("DONE.json") as Message.Done
        assertEquals("5c1b1e2a-9f3d-4f8a-bc21-7e6b0d5a91f4", message.payload.transferId)
        assertEquals(FileIndex.All, message.payload.fileIndex)
    }

    @Test
    fun `done fixture decodes a per-file index`() {
        val message = decode("DONE_file.json") as Message.Done
        assertEquals(FileIndex.Index(0), message.payload.fileIndex)
    }

    @Test
    fun `error fixture decodes`() {
        val message = decode("ERROR.json") as Message.Error
        assertEquals("5c1b1e2a-9f3d-4f8a-bc21-7e6b0d5a91f4", message.payload.transferId)
        assertEquals(Protocol.ErrorCode.DISK_FULL, message.payload.code)
        assertEquals("Not enough free space to store holiday.jpg", message.payload.message)
    }

    @Test
    fun `cancel fixture decodes`() {
        val message = decode("CANCEL.json") as Message.Cancel
        assertEquals("5c1b1e2a-9f3d-4f8a-bc21-7e6b0d5a91f4", message.payload.transferId)
    }

    @Test
    fun `unknown type fixture decodes to an unknown message`() {
        val message = decode("UNKNOWN_TYPE.json") as Message.Unknown
        assertEquals("CLIPBOARD_OFFER", message.type)
        assertEquals("a3f1c9d2-5b6e-4c8a-9d0f-1e2b3c4d5e6f", message.id)
        assertEquals(2, message.payload.size)
    }

    @Test
    fun `there is a fixture for every mvp message type`() {
        for (type in MessageType.entries) {
            val message = decode("${type.wire}.json")
            assertEquals(type.wire, message.type, "fixture ${type.wire}.json declares the wrong type")
            assertEquals(Protocol.VERSION, message.v)
        }
    }

    @Test
    fun `re-encoding a fixture reproduces its json`() {
        // Guards against the codec quietly dropping or renaming a field: the
        // decoded-then-re-encoded document must be semantically identical to
        // the hand-written fixture (whitespace aside).
        val names = MessageType.entries.map { "${it.wire}.json" } + listOf("DONE_file.json", "UNKNOWN_TYPE.json")
        for (name in names) {
            val original = Json.parseToJsonElement(fixture(name)).jsonObject
            val reEncoded = Json.parseToJsonElement(MessageCodec.encodeToJson(decode(name))).jsonObject
            assertEquals(original, reEncoded, "re-encoding $name changed the document")
        }
    }

    @Test
    fun `every fixture survives a framing round trip`() {
        val names = MessageType.entries.map { "${it.wire}.json" } + listOf("DONE_file.json", "UNKNOWN_TYPE.json")
        for (name in names) {
            val message = decode(name)
            val frame = MessageCodec.encodeFrame(message)
            assertEquals(message, MessageCodec.decodeFrame(frame), "framing round trip of $name")
        }
    }

    @Test
    fun `fixtures are plain ascii so both implementations read them identically`() {
        val names = MessageType.entries.map { "${it.wire}.json" } + listOf("DONE_file.json", "UNKNOWN_TYPE.json")
        for (name in names) {
            val text = fixture(name)
            assertTrue(text.all { it.code < 128 }, "$name must be ASCII-only")
            assertTrue(!text.contains('\uFEFF'), "$name must not carry a byte-order mark")
        }
    }

    @Test
    fun `a fixture with an unsupported version is refused`() {
        val bumped = fixture("HELLO.json").replace("\"v\": 1", "\"v\": 2")
        val failure = assertFailsWith<UnsupportedProtocolVersionException> {
            MessageCodec.decodeFromJson(bumped)
        }
        assertEquals(2, failure.declaredVersion)
        assertNotNull(failure.messageId)
    }
}
