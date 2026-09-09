package com.nearbyshare.network.identity

import com.nearbyshare.network.TestCertificates
import org.junit.Test
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `fp` format of PROTOCOL.md §1: SHA-256 of the DER encoding, lowercase hex,
 * no separators. Both implementations pin on this string, so its spelling is
 * part of the wire contract.
 */
class CertificateFingerprintsTest {

    @Test
    fun `fingerprint is the lowercase hex sha256 of the der encoding`() {
        val provider = TestCertificates.provider("fingerprint-test")
        val certificate = provider.certificateChain[0]

        val expected = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, CertificateFingerprints.of(certificate))
        assertEquals(expected, provider.fingerprint)
    }

    @Test
    fun `fingerprint is 64 lowercase hex characters with no separators`() {
        val fingerprint = TestCertificates.provider().fingerprint

        assertEquals(64, fingerprint.length)
        assertEquals(CertificateFingerprints.LENGTH, fingerprint.length)
        assertTrue(fingerprint.all { it in "0123456789abcdef" }, "got: $fingerprint")
        assertFalse(fingerprint.contains(':'))
    }

    @Test
    fun `two devices get different fingerprints`() {
        assertNotEquals(
            TestCertificates.provider("a").fingerprint,
            TestCertificates.provider("b").fingerprint,
        )
    }

    @Test
    fun `the same certificate always fingerprints the same`() {
        val provider = TestCertificates.provider()
        assertEquals(provider.fingerprint, provider.fingerprint)
        assertEquals(
            CertificateFingerprints.of(provider.certificateChain[0]),
            CertificateFingerprints.ofDer(provider.certificateChain[0].encoded),
        )
    }

    @Test
    fun `ofChain uses the leaf certificate`() {
        val leaf = TestCertificates.provider("leaf").certificateChain[0]
        val other = TestCertificates.provider("other").certificateChain[0]

        assertEquals(
            CertificateFingerprints.of(leaf),
            CertificateFingerprints.ofChain(arrayOf(leaf, other)),
        )
    }

    @Test
    fun `normalize accepts the formats other tooling produces`() {
        val canonical = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        // Windows' GetCertHashString returns uppercase; openssl prints colons.
        assertEquals(canonical, CertificateFingerprints.normalize(canonical.uppercase()))
        assertEquals(canonical, CertificateFingerprints.normalize(canonical.chunked(2).joinToString(":")))
        assertEquals(canonical, CertificateFingerprints.normalize(canonical.chunked(2).joinToString(" ")))
        assertEquals(canonical, CertificateFingerprints.normalize(canonical))
    }

    @Test
    fun `normalize rejects anything that is not a sha256 hex digest`() {
        assertNull(CertificateFingerprints.normalize(null))
        assertNull(CertificateFingerprints.normalize(""))
        assertNull(CertificateFingerprints.normalize("abc"), "too short")
        assertNull(CertificateFingerprints.normalize("f".repeat(65)), "too long")
        assertNull(CertificateFingerprints.normalize("g".repeat(64)), "not hex")
        assertNull(CertificateFingerprints.normalize("../".repeat(21) + "a"))
    }

    @Test
    fun `matches compares equal fingerprints and rejects everything else`() {
        val a = TestCertificates.provider("a").fingerprint
        val b = TestCertificates.provider("b").fingerprint

        assertTrue(CertificateFingerprints.matches(a, a))
        assertTrue(CertificateFingerprints.matches(a, a.uppercase().lowercase()))
        assertFalse(CertificateFingerprints.matches(a, b))
        assertFalse(CertificateFingerprints.matches(a, null))
        assertFalse(CertificateFingerprints.matches(null, a))
        assertFalse(CertificateFingerprints.matches(a, a.dropLast(1)))
        assertFalse(CertificateFingerprints.matches(a, a.dropLast(1) + "0"))
    }

    @Test
    fun `abbreviate keeps both ends recognisable`() {
        val fingerprint = "0123456789abcdef".repeat(4)
        val short = CertificateFingerprints.abbreviate(fingerprint)

        assertTrue(short.startsWith("01234567"))
        assertTrue(short.endsWith("cdef"))
        assertTrue(short.length < fingerprint.length)
        assertEquals("abc", CertificateFingerprints.abbreviate("abc"))
    }
}
