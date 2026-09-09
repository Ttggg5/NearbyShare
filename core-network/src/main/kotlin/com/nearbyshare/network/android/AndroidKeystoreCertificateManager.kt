package com.nearbyshare.network.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nearbyshare.network.identity.CertificateFingerprints
import com.nearbyshare.network.identity.CertificateProvider
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/**
 * The device's long-lived TLS identity, held in the Android Keystore
 * (PROTOCOL.md §2 step 1).
 *
 * `KeyGenParameterSpec` mints a self-signed certificate alongside the keypair,
 * which is exactly what this protocol wants -- there is no CA, so nothing else
 * would sign it. The private key is generated inside the keystore and never
 * leaves it: only the ability to sign is exposed, which is why the TLS stack
 * uses [com.nearbyshare.network.identity.StaticIdentityKeyManager] instead of
 * the stock `KeyManagerFactory` (that one insists on reading key bytes out of a
 * `KeyStore`, which a hardware-backed entry rightly refuses).
 *
 * Because the entry survives app restarts, [fingerprint] -- the mDNS `fp` value
 * -- stays stable, which is what makes peers' trust-on-first-use pins keep
 * working.
 */
class AndroidKeystoreCertificateManager(
    private val alias: String = DEFAULT_ALIAS,
) : CertificateProvider {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Volatile
    private var cached: KeyStore.PrivateKeyEntry? = null

    override val certificateChain: Array<X509Certificate>
        get() = entry().certificateChain.map { it as X509Certificate }.toTypedArray()

    override val privateKey: PrivateKey
        get() = entry().privateKey

    override val fingerprint: String
        get() = CertificateFingerprints.of(entry().certificate)

    /** True if an identity already exists, i.e. this is not the first run. */
    fun exists(): Boolean = keyStore.containsAlias(alias)

    /**
     * Throw away this device's identity and mint a new one.
     *
     * Every peer that has pinned the old fingerprint will refuse the next
     * connection with a "device identity changed" warning until they forget it,
     * so this is a deliberate user action, never an automatic recovery step.
     */
    @Synchronized
    fun regenerate() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        cached = null
        entry()
    }

    @Synchronized
    private fun entry(): KeyStore.PrivateKeyEntry {
        cached?.let { return it }

        val existing = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) {
            cached = existing
            return existing
        }

        generateKeyPair()
        val created = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: error("Android Keystore did not return the entry it just created for '$alias'")
        cached = created
        return created
    }

    private fun generateKeyPair() {
        val notBefore = Calendar.getInstance().apply {
            // A day of slack absorbs clock skew between two phones on the same
            // network; a certificate that is "not yet valid" on the peer would
            // be a baffling failure.
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, CERTIFICATE_VALIDITY_YEARS) }

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
            .setDigests(
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
            )
            .setCertificateSubject(X500Principal(SUBJECT))
            .setCertificateSerialNumber(BigInteger(64, SecureRandom()).abs().max(BigInteger.ONE))
            .setCertificateNotBefore(notBefore.time)
            .setCertificateNotAfter(notAfter.time)
            // No user authentication: transfers must work from a foreground
            // service while the screen is off.
            .setUserAuthenticationRequired(false)
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS: String = "nearbyshare_identity"

        /** P-256: universally supported by Android Keystore and by .NET on Windows. */
        private const val EC_CURVE = "secp256r1"
        private const val SUBJECT = "CN=NearbyShare"
        private const val CERTIFICATE_VALIDITY_YEARS = 10
    }
}
