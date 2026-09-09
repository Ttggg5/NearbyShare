package com.nearbyshare.network.discovery

import com.nearbyshare.network.identity.CertificateFingerprints
import com.nearbyshare.protocol.Protocol
import java.nio.charset.StandardCharsets

/**
 * Reading and writing the DNS-SD TXT record of PROTOCOL.md §1.
 *
 * Kept Android-free (and away from `NsdServiceInfo`) so the parsing rules --
 * which is where cross-platform bugs actually live -- are unit testable. The
 * Android layer only converts between `NsdServiceInfo` and these maps.
 */
object TxtRecords {

    /** The fields a peer must publish for us to be able to connect to it. */
    data class Advertised(
        val protocolVersion: Int,
        val deviceId: String,
        val fingerprint: String,
        val port: Int,
        val os: String?,
    )

    /**
     * Parse a peer's TXT record.
     *
     * @param attributes as handed back by `NsdServiceInfo.getAttributes()`:
     *   values may be `null` for a key published with no value.
     * @return the parsed record, or `null` if a required field is missing or
     *   malformed. An unparseable advertisement is dropped rather than shown as
     *   a broken peer -- the sender has nothing useful it could do with it.
     */
    fun parse(attributes: Map<String, ByteArray?>): Advertised? {
        val values = attributes.mapValues { (_, bytes) -> bytes?.toString(StandardCharsets.UTF_8) }

        val version = values[Protocol.Txt.VERSION]?.trim()?.toIntOrNull() ?: return null
        val deviceId = values[Protocol.Txt.DEVICE_ID]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val fingerprint = CertificateFingerprints.normalize(values[Protocol.Txt.FINGERPRINT]?.trim())
            ?: return null
        val port = values[Protocol.Txt.PORT]?.trim()?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
        val os = values[Protocol.Txt.OS]?.trim()?.takeIf { it.isNotEmpty() }

        return Advertised(
            protocolVersion = version,
            deviceId = deviceId,
            fingerprint = fingerprint,
            port = port,
            os = os,
        )
    }

    /**
     * Check that every entry fits the 255-byte DNS-SD limit for a single TXT
     * string (`key=value`).
     *
     * @return the offending keys, empty when the advertisement is publishable.
     */
    fun oversizedKeys(records: Map<String, String>): List<String> = records.filter { (key, value) ->
        "$key=$value".toByteArray(StandardCharsets.UTF_8).size > Protocol.Txt.MAX_ENTRY_BYTES
    }.keys.toList()
}

/**
 * DNS-SD service instance naming rules from PROTOCOL.md §1.
 *
 * Android's `NsdManager` will itself rename a colliding registration and hand
 * the final name back, so [deduplicate] exists for the manual/testing paths and
 * for showing the user what their name will look like before it is published.
 */
object ServiceInstanceNames {

    private const val FALLBACK = "Android device"

    /**
     * Make [raw] safe to publish: strip control characters and dots (which
     * would be read as extra DNS labels), collapse runs of whitespace, then
     * truncate to [Protocol.MAX_INSTANCE_NAME_BYTES] **bytes** without
     * splitting a character.
     *
     * Spaces are legal in a DNS-SD instance name and are deliberately kept --
     * "Pixel 8" must stay readable.
     */
    fun sanitize(raw: String): String {
        val cleaned = buildString(raw.length) {
            for (c in raw) {
                when {
                    c == '\t' || c == '\n' || c == '\r' -> append(' ')
                    c.isISOControl() -> Unit
                    c == '.' -> Unit
                    else -> append(c)
                }
            }
        }.replace(WHITESPACE_RUN, " ").trim()

        if (cleaned.isEmpty()) return FALLBACK
        return truncateToBytes(cleaned, Protocol.MAX_INSTANCE_NAME_BYTES)
    }

    /**
     * Append a numeric suffix until the name is not in [taken], keeping the
     * result inside the byte limit.
     */
    fun deduplicate(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        var suffix = 2
        while (true) {
            val marker = " ($suffix)"
            val budget = Protocol.MAX_INSTANCE_NAME_BYTES - marker.toByteArray(StandardCharsets.UTF_8).size
            val candidate = truncateToBytes(name, budget.coerceAtLeast(1)).trim() + marker
            if (candidate !in taken) return candidate
            suffix++
            // Give up rather than spin forever on a pathological network.
            if (suffix > 999) return candidate
        }
    }

    /** Truncate to at most [maxBytes] UTF-8 bytes, never mid-character. */
    fun truncateToBytes(value: String, maxBytes: Int): String {
        if (value.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return value
        var end = value.length
        while (end > 0) {
            // Step back a whole code point at a time so surrogate pairs survive.
            end -= if (end >= 2 && value[end - 1].isLowSurrogate() && value[end - 2].isHighSurrogate()) 2 else 1
            val candidate = value.substring(0, end)
            if (candidate.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return candidate.trim()
        }
        return ""
    }

    private val WHITESPACE_RUN = Regex("\\s+")
}
