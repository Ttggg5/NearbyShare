package com.nearbyshare.network.identity

import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate

/**
 * The certificate fingerprint format of PROTOCOL.md §1: the SHA-256 digest of
 * the certificate's DER encoding, as **lowercase hex with no separators**.
 *
 * This is the value published in the mDNS `fp` TXT record and the value both
 * sides pin on, so its exact spelling is part of the wire contract. Windows'
 * `X509Certificate2.GetCertHashString(HashAlgorithmName.SHA256)` produces the
 * same digest in uppercase -- hence [normalize].
 */
object CertificateFingerprints {

    private const val HEX_DIGITS = "0123456789abcdef"

    /** Length of a SHA-256 fingerprint in hex characters. */
    const val LENGTH: Int = 64

    /** SHA-256 of [certificate]'s DER encoding, lowercase hex. */
    fun of(certificate: Certificate): String = ofDer(certificate.encoded)

    /** SHA-256 of a DER-encoded certificate, lowercase hex. */
    fun ofDer(der: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(der))

    /** The fingerprint of the leaf (index 0) of a presented chain. */
    fun ofChain(chain: Array<out X509Certificate>): String {
        require(chain.isNotEmpty()) { "Peer presented an empty certificate chain" }
        return of(chain[0])
    }

    /**
     * Canonicalise a fingerprint received from a peer or read from storage:
     * lowercased, with any `:`, `-` or whitespace separators removed.
     *
     * @return the normalised value, or `null` if [raw] is not a 64-character
     *   hex string once normalised.
     */
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = buildString(raw.length) {
            for (c in raw) {
                when {
                    c == ':' || c == '-' || c == ' ' || c == '\t' -> Unit
                    else -> append(c.lowercaseChar())
                }
            }
        }
        if (cleaned.length != LENGTH) return null
        if (!cleaned.all { it in HEX_DIGITS }) return null
        return cleaned
    }

    /**
     * Constant-time equality for two normalised fingerprints.
     *
     * Fingerprints are public values, so this is belt-and-braces rather than
     * strictly necessary -- but comparing secrets and near-secrets in constant
     * time is a habit worth keeping.
     */
    fun matches(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /** Short form for logs and UI, e.g. `a1b2c3d4…9f0e`. */
    fun abbreviate(fingerprint: String): String =
        if (fingerprint.length <= 12) fingerprint
        else "${fingerprint.take(8)}…${fingerprint.takeLast(4)}"

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_DIGITS[v ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(out)
    }
}
