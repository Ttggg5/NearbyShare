package com.nearbyshare.network.tls

import com.nearbyshare.network.TestCertificates
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The trust-on-first-use rules of PROTOCOL.md §2 step 4, exercised at the
 * policy level (no sockets involved).
 */
class PeerTrustPolicyTest {

    private val peerId = "1b7d6f60-9d2c-4a1e-9c1a-2f2f9a1e6d31"

    @Test
    fun `first contact pins the fingerprint`() = runTest {
        val store = InMemoryKnownPeerStore()
        val policy = PeerTrustPolicy(store)
        val fingerprint = TestCertificates.provider().fingerprint

        assertNull(store.fingerprintOf(peerId), "nothing pinned before first contact")
        assertEquals(TrustOutcome.FIRST_USE, policy.confirm(peerId, fingerprint))
        assertEquals(fingerprint, store.fingerprintOf(peerId))
    }

    @Test
    fun `a returning peer with the same certificate is recognised`() = runTest {
        val store = InMemoryKnownPeerStore()
        val policy = PeerTrustPolicy(store)
        val fingerprint = TestCertificates.provider().fingerprint

        policy.confirm(peerId, fingerprint)
        assertEquals(TrustOutcome.KNOWN, policy.confirm(peerId, fingerprint))
        assertEquals(TrustOutcome.KNOWN, policy.confirm(peerId, fingerprint.uppercase()))
    }

    @Test
    fun `a changed certificate is refused and the stored pin is left alone`() = runTest {
        val store = InMemoryKnownPeerStore()
        val policy = PeerTrustPolicy(store)
        val original = TestCertificates.provider("original").fingerprint
        val impostor = TestCertificates.provider("impostor").fingerprint

        policy.confirm(peerId, original)

        val failure = assertFailsWith<PeerIdentityChangedException> {
            policy.confirm(peerId, impostor)
        }
        assertEquals(peerId, failure.peerDeviceId)
        assertEquals(original, failure.storedFingerprint)
        assertEquals(impostor, failure.presentedFingerprint)

        assertEquals(
            original,
            store.fingerprintOf(peerId),
            "a mismatch must never silently overwrite the pin",
        )
    }

    @Test
    fun `forgetting a peer makes the next connection a first use again`() = runTest {
        val store = InMemoryKnownPeerStore()
        val policy = PeerTrustPolicy(store)
        val original = TestCertificates.provider("original").fingerprint
        val replacement = TestCertificates.provider("reinstalled").fingerprint

        policy.confirm(peerId, original)
        policy.forget(peerId)

        assertEquals(TrustOutcome.FIRST_USE, policy.confirm(peerId, replacement))
        assertEquals(replacement, store.fingerprintOf(peerId))
    }

    @Test
    fun `pins are keyed by device id not shared between peers`() = runTest {
        val store = InMemoryKnownPeerStore()
        val policy = PeerTrustPolicy(store)
        val first = TestCertificates.provider("first").fingerprint
        val second = TestCertificates.provider("second").fingerprint

        policy.confirm("device-a", first)
        assertEquals(TrustOutcome.FIRST_USE, policy.confirm("device-b", second))
        assertEquals(first, store.fingerprintOf("device-a"))
        assertEquals(second, store.fingerprintOf("device-b"))
    }

    @Test
    fun `the trust manager is preloaded with the pin for a known peer`() = runTest {
        val fingerprint = TestCertificates.provider().fingerprint
        val policy = PeerTrustPolicy(InMemoryKnownPeerStore(mapOf(peerId to fingerprint)))

        val trustManager = policy.trustManagerFor(peerId, advertisedFingerprint = fingerprint)
        assertEquals(peerId, trustManager.peerDeviceId)
        assertNull(trustManager.presentedFingerprint, "nothing presented until a handshake happens")
    }

    @Test
    fun `an inbound connection gets a trust manager with nothing pinned yet`() = runTest {
        val policy = PeerTrustPolicy(InMemoryKnownPeerStore())
        val trustManager = policy.trustManagerFor(peerDeviceId = null)
        assertNull(trustManager.peerDeviceId)
    }
}
