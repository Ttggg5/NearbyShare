package com.nearbyshare.network.discovery

import com.nearbyshare.network.transfer.sanitizeIncomingFileName
import com.nearbyshare.protocol.Protocol
import org.junit.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The mDNS advertisement rules of PROTOCOL.md §1. */
class TxtRecordsTest {

    private val fingerprint = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private fun bytes(vararg pairs: Pair<String, String?>): Map<String, ByteArray?> =
        pairs.associate { (key, value) -> key to value?.toByteArray(StandardCharsets.UTF_8) }

    @Test
    fun `an advertisement publishes exactly the five spec keys`() {
        val records = ServiceAdvertisement(
            instanceName = "Pixel 8",
            deviceId = "1b7d6f60-9d2c-4a1e-9c1a-2f2f9a1e6d31",
            fingerprint = fingerprint,
            port = 45123,
        ).txtRecords()

        assertEquals(setOf("v", "id", "fp", "port", "os"), records.keys)
        assertEquals("1", records["v"])
        assertEquals("1b7d6f60-9d2c-4a1e-9c1a-2f2f9a1e6d31", records["id"])
        assertEquals(fingerprint, records["fp"])
        assertEquals("45123", records["port"])
        assertEquals("android", records["os"])
    }

    @Test
    fun `a published advertisement fits inside the dns-sd entry limit`() {
        val records = ServiceAdvertisement(
            instanceName = "A".repeat(63),
            deviceId = "1b7d6f60-9d2c-4a1e-9c1a-2f2f9a1e6d31",
            fingerprint = fingerprint,
            port = 65535,
        ).txtRecords()

        assertEquals(emptyList(), TxtRecords.oversizedKeys(records))
        records.forEach { (key, value) ->
            assertTrue("$key=$value".length <= Protocol.Txt.MAX_ENTRY_BYTES)
        }
    }

    @Test
    fun `oversized entries are reported rather than published`() {
        val records = mapOf("v" to "1", "junk" to "x".repeat(300))
        assertEquals(listOf("junk"), TxtRecords.oversizedKeys(records))
    }

    @Test
    fun `a well formed txt record round trips`() {
        val advertisement = ServiceAdvertisement(
            instanceName = "Desktop",
            deviceId = "abc-123",
            fingerprint = fingerprint,
            port = 5000,
            os = Protocol.Txt.OS_WINDOWS,
        )
        val parsed = TxtRecords.parse(
            advertisement.txtRecords().mapValues { it.value.toByteArray(StandardCharsets.UTF_8) },
        )

        assertEquals(1, parsed?.protocolVersion)
        assertEquals("abc-123", parsed?.deviceId)
        assertEquals(fingerprint, parsed?.fingerprint)
        assertEquals(5000, parsed?.port)
        assertEquals("windows", parsed?.os)
    }

    @Test
    fun `an uppercase fingerprint from the windows side is normalised`() {
        val parsed = TxtRecords.parse(
            bytes("v" to "1", "id" to "abc", "fp" to fingerprint.uppercase(), "port" to "5000", "os" to "windows"),
        )
        assertEquals(fingerprint, parsed?.fingerprint, "fp must be canonicalised to lowercase hex")
    }

    @Test
    fun `records missing a required field are dropped`() {
        assertNull(TxtRecords.parse(bytes("id" to "abc", "fp" to fingerprint, "port" to "5000")), "no v")
        assertNull(TxtRecords.parse(bytes("v" to "1", "fp" to fingerprint, "port" to "5000")), "no id")
        assertNull(TxtRecords.parse(bytes("v" to "1", "id" to "abc", "port" to "5000")), "no fp")
        assertNull(TxtRecords.parse(bytes("v" to "1", "id" to "abc", "fp" to fingerprint)), "no port")
        assertNull(TxtRecords.parse(emptyMap()), "nothing at all")
    }

    @Test
    fun `records with malformed values are dropped`() {
        assertNull(TxtRecords.parse(bytes("v" to "one", "id" to "a", "fp" to fingerprint, "port" to "1")))
        assertNull(TxtRecords.parse(bytes("v" to "1", "id" to "a", "fp" to "short", "port" to "1")))
        assertNull(TxtRecords.parse(bytes("v" to "1", "id" to "a", "fp" to fingerprint, "port" to "0")))
        assertNull(TxtRecords.parse(bytes("v" to "1", "id" to "a", "fp" to fingerprint, "port" to "70000")))
        assertNull(TxtRecords.parse(bytes("v" to "1", "id" to "", "fp" to fingerprint, "port" to "1")))
        assertNull(TxtRecords.parse(bytes("v" to "1", "id" to null, "fp" to fingerprint, "port" to "1")))
    }

    @Test
    fun `os is optional`() {
        val parsed = TxtRecords.parse(bytes("v" to "1", "id" to "a", "fp" to fingerprint, "port" to "1"))
        assertEquals("a", parsed?.deviceId)
        assertNull(parsed?.os)
    }

    @Test
    fun `a future protocol version parses so the peer can be shown as incompatible`() {
        val parsed = TxtRecords.parse(bytes("v" to "2", "id" to "a", "fp" to fingerprint, "port" to "1"))
        assertEquals(2, parsed?.protocolVersion)

        val peer = PeerDevice("a", "Future", "10.0.0.5", 1, fingerprint, protocolVersion = 2)
        assertTrue(!peer.isCompatible)
    }
}

/** Instance-name sanitisation, also from PROTOCOL.md §1. */
class ServiceInstanceNamesTest {

    @Test
    fun `an ordinary name is left alone`() {
        assertEquals("Pixel 8", ServiceInstanceNames.sanitize("Pixel 8"))
        assertEquals("Sam's laptop", ServiceInstanceNames.sanitize("Sam's laptop"))
    }

    @Test
    fun `dots and control characters are stripped`() {
        assertEquals("localdevice", ServiceInstanceNames.sanitize("local.device"))
        assertEquals("ab", ServiceInstanceNames.sanitize("a\u0000b"))
    }

    @Test
    fun `whitespace is collapsed and trimmed`() {
        assertEquals("My Phone", ServiceInstanceNames.sanitize("  My    Phone  "))
        assertEquals("My Phone", ServiceInstanceNames.sanitize("My\tPhone"))
    }

    @Test
    fun `an empty name falls back to something usable`() {
        assertTrue(ServiceInstanceNames.sanitize("").isNotEmpty())
        assertTrue(ServiceInstanceNames.sanitize("   ").isNotEmpty())
        assertTrue(ServiceInstanceNames.sanitize("...").isNotEmpty())
    }

    @Test
    fun `names are truncated to 63 bytes not 63 characters`() {
        val emoji = "📱".repeat(40) // 4 UTF-8 bytes each
        val sanitized = ServiceInstanceNames.sanitize(emoji)

        val byteLength = sanitized.toByteArray(StandardCharsets.UTF_8).size
        assertTrue(byteLength <= Protocol.MAX_INSTANCE_NAME_BYTES, "was $byteLength bytes")
        assertTrue(sanitized.isNotEmpty())
        // A surrogate pair must never be split in half: a lone surrogate would
        // encode as a replacement character and change the published name.
        assertTrue(sanitized.none { it.isSurrogate() && !it.isHighSurrogate() && !it.isLowSurrogate() })
        assertEquals(
            sanitized,
            String(sanitized.toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
            "truncation must not leave a half character behind",
        )
    }

    @Test
    fun `a long ascii name is truncated to the limit`() {
        val sanitized = ServiceInstanceNames.sanitize("A".repeat(200))
        assertEquals(Protocol.MAX_INSTANCE_NAME_BYTES, sanitized.toByteArray(StandardCharsets.UTF_8).size)
    }

    @Test
    fun `a colliding name gets a numeric suffix`() {
        val taken = setOf("Pixel 8")
        val deduped = ServiceInstanceNames.deduplicate("Pixel 8", taken)

        assertNotEquals("Pixel 8", deduped)
        assertTrue(deduped.startsWith("Pixel 8"))
        assertTrue(deduped !in taken)
    }

    @Test
    fun `deduplication keeps going until the name is free`() {
        val taken = setOf("Phone", "Phone (2)", "Phone (3)")
        assertEquals("Phone (4)", ServiceInstanceNames.deduplicate("Phone", taken))
    }

    @Test
    fun `a free name is not suffixed`() {
        assertEquals("Phone", ServiceInstanceNames.deduplicate("Phone", setOf("Other")))
    }

    @Test
    fun `a deduplicated name still fits the byte limit`() {
        val long = "A".repeat(Protocol.MAX_INSTANCE_NAME_BYTES)
        val deduped = ServiceInstanceNames.deduplicate(long, setOf(long))
        assertTrue(
            deduped.toByteArray(StandardCharsets.UTF_8).size <= Protocol.MAX_INSTANCE_NAME_BYTES,
            "was ${deduped.toByteArray(StandardCharsets.UTF_8).size} bytes: $deduped",
        )
    }
}

/** The manual-IP escape hatch on the device list. */
class ManualPeerStoreTest {

    @Test
    fun `a manual peer is added with the address as its label`() {
        val store = ManualPeerStore()
        val peer = store.add("192.168.1.42", 45123)

        assertEquals("192.168.1.42", peer.host)
        assertEquals(45123, peer.port)
        assertEquals(PeerSource.MANUAL, peer.source)
        assertNull(peer.fingerprint, "a manual peer has no advertisement to cross-check")
        assertEquals(listOf(peer), store.peers.value)
    }

    @Test
    fun `re-adding the same address replaces rather than duplicates`() {
        val store = ManualPeerStore()
        store.add("10.0.0.5", 5000)
        store.add("10.0.0.5", 5000, label = "Desk PC")

        assertEquals(1, store.peers.value.size)
        assertEquals("Desk PC", store.peers.value.single().name)
    }

    @Test
    fun `the same address always gets the same placeholder id`() {
        assertEquals(
            ManualPeerStore().add("10.0.0.5", 5000).deviceId,
            ManualPeerStore().add("10.0.0.5", 5000).deviceId,
        )
        assertNotEquals(
            ManualPeerStore().add("10.0.0.5", 5000).deviceId,
            ManualPeerStore().add("10.0.0.5", 5001).deviceId,
        )
    }

    @Test
    fun `bad input is rejected with a message worth showing`() {
        val store = ManualPeerStore()
        assertFailsWith<IllegalArgumentException> { store.add("   ", 5000) }
        assertFailsWith<IllegalArgumentException> { store.add("10.0.0.5", 0) }
        assertFailsWith<IllegalArgumentException> { store.add("10.0.0.5", 70_000) }
        assertTrue(store.peers.value.isEmpty())
    }

    @Test
    fun `peers can be removed`() {
        val store = ManualPeerStore()
        val peer = store.add("10.0.0.5", 5000)
        store.remove(peer.deviceId)
        assertTrue(store.peers.value.isEmpty())
    }

    @Test
    fun `a discovered peer supersedes a manual entry at the same address`() {
        val discovered = PeerDevice("real-id", "Desk PC", "10.0.0.5", 5000, source = PeerSource.DISCOVERED)
        val manual = ManualPeerStore().add("10.0.0.5", 5000)

        val merged = mergePeers(listOf(discovered), listOf(manual))
        assertEquals(listOf(discovered), merged)
    }

    @Test
    fun `manual entries at other addresses are kept and sorted after discovered ones`() {
        val discovered = PeerDevice("real-id", "Zed", "10.0.0.5", 5000, source = PeerSource.DISCOVERED)
        val manual = ManualPeerStore().add("10.0.0.9", 5000, label = "Alpha")

        val merged = mergePeers(listOf(discovered), listOf(manual))
        assertEquals(listOf("Zed", "Alpha"), merged.map { it.name })
    }
}

/** Incoming file names are attacker-controlled input. */
class IncomingFileNameTest {

    @Test
    fun `an ordinary name survives untouched`() {
        assertEquals("holiday.jpg", sanitizeIncomingFileName("holiday.jpg"))
        assertEquals("my report v2.pdf", sanitizeIncomingFileName("my report v2.pdf"))
    }

    @Test
    fun `path traversal is stripped down to the final segment`() {
        assertEquals("passwd", sanitizeIncomingFileName("../../../etc/passwd"))
        assertEquals("shell.sh", sanitizeIncomingFileName("/etc/init.d/shell.sh"))
        assertEquals("evil.exe", sanitizeIncomingFileName("..\\..\\Windows\\evil.exe"))
        assertEquals("file.txt", sanitizeIncomingFileName("dir/sub/file.txt"))
    }

    @Test
    fun `names that reduce to nothing fall back`() {
        assertEquals("received_file", sanitizeIncomingFileName(""))
        assertEquals("received_file", sanitizeIncomingFileName("../.."))
        assertEquals("received_file", sanitizeIncomingFileName("/"))
        assertEquals("received_file", sanitizeIncomingFileName("..."))
    }

    @Test
    fun `control characters are removed`() {
        assertEquals("abc.txt", sanitizeIncomingFileName("a\u0000b\u0007c.txt"))
    }

    @Test
    fun `an absurdly long name is truncated but keeps its extension`() {
        val name = "n".repeat(500) + ".jpg"
        val sanitized = sanitizeIncomingFileName(name)

        assertTrue(sanitized.length <= 200, "was ${sanitized.length}")
        assertTrue(sanitized.endsWith(".jpg"), "the extension must survive: $sanitized")
    }
}
