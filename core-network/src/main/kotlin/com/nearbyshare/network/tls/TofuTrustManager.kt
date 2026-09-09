package com.nearbyshare.network.tls

import com.nearbyshare.network.identity.CertificateFingerprints
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The trust manager implementing PROTOCOL.md §2 step 4.
 *
 * Chain-of-trust validation is deliberately *not* performed: both peers use
 * long-lived self-signed certificates and there is no CA to validate against.
 * Security comes from pinning the SHA-256 fingerprint instead:
 *
 * 1. If a fingerprint is already pinned for this peer id, the presented
 *    certificate must match it, or the handshake fails with
 *    [PeerIdentityChangedException].
 * 2. If the peer advertised an `fp` over mDNS, the presented certificate must
 *    match that too, or the handshake fails with
 *    [AdvertisedFingerprintMismatchException] -- someone else answered on that
 *    address.
 * 3. Otherwise the certificate is accepted and recorded in
 *    [presentedFingerprint]. Writing the first-use pin is *not* done here: for
 *    an inbound connection the peer's device id is still unknown until `HELLO`
 *    arrives, so [PeerTrustPolicy.confirm] does it once the identity is known.
 *
 * Certificate validity dates are also not enforced. These certificates are
 * self-issued with a decade-long window and a device with a wrong clock is a
 * far more likely cause of a date failure than an attack, while the pin is what
 * actually carries the security property.
 *
 * One instance belongs to exactly one connection.
 */
class TofuTrustManager(
    /** The device id we expect, or `null` for a not-yet-identified inbound peer. */
    val peerDeviceId: String? = null,
    /** The fingerprint already pinned for [peerDeviceId], if any. */
    pinnedFingerprint: String? = null,
    /** The `fp` this peer advertised over mDNS, if we saw one. */
    advertisedFingerprint: String? = null,
) : X509ExtendedTrustManager() {

    private val pinned: String? = CertificateFingerprints.normalize(pinnedFingerprint)
    private val advertised: String? = CertificateFingerprints.normalize(advertisedFingerprint)

    /**
     * The fingerprint the peer actually presented, available once the handshake
     * has begun. `null` before then.
     */
    @Volatile
    var presentedFingerprint: String? = null
        private set

    /** The peer's leaf certificate, available once the handshake has begun. */
    @Volatile
    var presentedCertificate: X509Certificate? = null
        private set

    private fun check(chain: Array<out X509Certificate>?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Peer presented no certificate; mutual TLS is required")
        }
        val leaf = chain[0]
        val presented = CertificateFingerprints.of(leaf)

        pinned?.let {
            if (!CertificateFingerprints.matches(it, presented)) {
                throw PeerIdentityChangedException(
                    peerDeviceId = peerDeviceId ?: "<unknown>",
                    storedFingerprint = it,
                    presentedFingerprint = presented,
                )
            }
        }

        advertised?.let {
            if (!CertificateFingerprints.matches(it, presented)) {
                throw AdvertisedFingerprintMismatchException(
                    peerDeviceId = peerDeviceId,
                    advertisedFingerprint = it,
                    presentedFingerprint = presented,
                )
            }
        }

        presentedCertificate = leaf
        presentedFingerprint = presented
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = check(chain)

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = check(chain)

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = check(chain)

    /** No CAs are trusted: validation is by pinned fingerprint only. */
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
