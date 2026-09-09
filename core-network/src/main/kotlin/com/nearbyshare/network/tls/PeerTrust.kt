package com.nearbyshare.network.tls

import com.nearbyshare.data.SettingsKey
import com.nearbyshare.data.SettingsRepository
import com.nearbyshare.network.identity.CertificateFingerprints
import java.security.cert.CertificateException
import java.util.concurrent.ConcurrentHashMap

/** Base type for every trust-on-first-use failure (PROTOCOL.md §2 step 4). */
sealed class PeerTrustException(message: String) : CertificateException(message)

/**
 * A peer we have seen before presented a *different* certificate.
 *
 * This is the SSH "host key changed" case: it is a security event, not a
 * recoverable error. The connection is aborted, the stored fingerprint is left
 * untouched, and the user is shown the mismatch so they can decide whether the
 * peer legitimately reinstalled or something is impersonating it.
 */
class PeerIdentityChangedException(
    val peerDeviceId: String,
    val storedFingerprint: String,
    val presentedFingerprint: String,
) : PeerTrustException(
    "Device $peerDeviceId presented certificate ${CertificateFingerprints.abbreviate(presentedFingerprint)} " +
        "but was previously known as ${CertificateFingerprints.abbreviate(storedFingerprint)}",
)

/**
 * The certificate presented during the handshake did not match the `fp` the
 * peer advertised over mDNS -- someone else answered on that address.
 */
class AdvertisedFingerprintMismatchException(
    val peerDeviceId: String?,
    val advertisedFingerprint: String,
    val presentedFingerprint: String,
) : PeerTrustException(
    "Certificate ${CertificateFingerprints.abbreviate(presentedFingerprint)} does not match the advertised " +
        "fingerprint ${CertificateFingerprints.abbreviate(advertisedFingerprint)}" +
        (peerDeviceId?.let { " for device $it" } ?: ""),
)

/** The peer's `HELLO.deviceId` was not the device we expected (PROTOCOL.md §2 step 5). */
class PeerIdentityMismatchException(
    val expectedDeviceId: String,
    val actualDeviceId: String,
) : PeerTrustException(
    "Expected to be talking to device $expectedDeviceId but it identified as $actualDeviceId",
)

/** How a peer's certificate related to what we already knew about it. */
enum class TrustOutcome {
    /** First time we have seen this device id; its fingerprint is now pinned. */
    FIRST_USE,

    /** The fingerprint matched the one already pinned for this device id. */
    KNOWN,
}

/**
 * Persistent record of which certificate belongs to which peer device id.
 *
 * Suspending because the production implementation is disk-backed; the trust
 * manager itself never touches it, since JSSE calls trust managers on the
 * handshake thread with no way to suspend. Pinned values are loaded *before*
 * the handshake and persisted *after* it.
 */
interface KnownPeerStore {

    /** The pinned fingerprint for [deviceId], or `null` if it is unknown. */
    suspend fun fingerprintOf(deviceId: String): String?

    /** Pin [fingerprint] as the identity of [deviceId]. */
    suspend fun remember(deviceId: String, fingerprint: String)

    /** Drop the pin for [deviceId], so the next connection is a first use again. */
    suspend fun forget(deviceId: String)
}

/** Non-persistent store, for tests and for a "forget all devices" session mode. */
class InMemoryKnownPeerStore(
    initial: Map<String, String> = emptyMap(),
) : KnownPeerStore {

    private val pins = ConcurrentHashMap<String, String>(initial)

    override suspend fun fingerprintOf(deviceId: String): String? = pins[deviceId]

    override suspend fun remember(deviceId: String, fingerprint: String) {
        pins[deviceId] = fingerprint
    }

    override suspend fun forget(deviceId: String) {
        pins.remove(deviceId)
    }
}

/**
 * [KnownPeerStore] backed by the app's settings store, one key per peer.
 *
 * Reuses [SettingsRepository] rather than introducing a second storage
 * mechanism; the key namespace keeps peer pins from colliding with settings.
 */
class SettingsKnownPeerStore(
    private val settings: SettingsRepository,
) : KnownPeerStore {

    override suspend fun fingerprintOf(deviceId: String): String? =
        settings.get(keyFor(deviceId)).takeIf { it.isNotEmpty() }

    override suspend fun remember(deviceId: String, fingerprint: String) {
        settings.set(keyFor(deviceId), fingerprint)
    }

    override suspend fun forget(deviceId: String) {
        settings.remove(keyFor(deviceId))
    }

    private fun keyFor(deviceId: String) = SettingsKey.StringKey("$KEY_PREFIX$deviceId")

    private companion object {
        const val KEY_PREFIX = "peer_fingerprint_"
    }
}

/**
 * The trust-on-first-use policy of PROTOCOL.md §2 step 4.
 *
 * Split into two halves because the two halves happen at different times:
 *
 * - [trustManagerFor] runs *during* the handshake, synchronously, pinning
 *   against what we already knew (and against the mDNS advertisement) for a
 *   peer whose identity we knew before dialling.
 * - [confirm] runs *after* `HELLO`, when even an inbound connection has finally
 *   told us which device id it claims to be, and is what actually writes a
 *   first-use pin.
 */
class PeerTrustPolicy(
    private val knownPeers: KnownPeerStore,
) {

    /**
     * Build the trust manager for one connection.
     *
     * @param peerDeviceId the device id we expect, or `null` for an inbound
     *   connection whose peer has not identified itself yet.
     * @param advertisedFingerprint the `fp` from the peer's mDNS TXT record, if
     *   we have one.
     */
    suspend fun trustManagerFor(
        peerDeviceId: String?,
        advertisedFingerprint: String? = null,
    ): TofuTrustManager = TofuTrustManager(
        peerDeviceId = peerDeviceId,
        pinnedFingerprint = peerDeviceId?.let { knownPeers.fingerprintOf(it) },
        advertisedFingerprint = CertificateFingerprints.normalize(advertisedFingerprint),
    )

    /**
     * Reconcile the certificate actually presented with what we know about
     * [peerDeviceId], pinning it if this is the first contact.
     *
     * @throws PeerIdentityChangedException if a different certificate was
     *   already pinned for this device id. The stored pin is *not* overwritten.
     */
    suspend fun confirm(peerDeviceId: String, presentedFingerprint: String): TrustOutcome {
        val presented = CertificateFingerprints.normalize(presentedFingerprint)
            ?: throw AdvertisedFingerprintMismatchException(peerDeviceId, "", presentedFingerprint)

        return when (val stored = knownPeers.fingerprintOf(peerDeviceId)) {
            null -> {
                knownPeers.remember(peerDeviceId, presented)
                TrustOutcome.FIRST_USE
            }

            else -> {
                if (!CertificateFingerprints.matches(stored, presented)) {
                    throw PeerIdentityChangedException(peerDeviceId, stored, presented)
                }
                TrustOutcome.KNOWN
            }
        }
    }

    /** Drop the pin for [peerDeviceId] -- the user's "trust it anyway" escape hatch. */
    suspend fun forget(peerDeviceId: String) = knownPeers.forget(peerDeviceId)
}
