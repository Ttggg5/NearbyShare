package com.nearbyshare.network.identity

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * This device's long-lived TLS identity (PROTOCOL.md §2 step 1).
 *
 * One self-signed certificate/keypair is generated on first run and persisted,
 * so [fingerprint] -- the value advertised as the mDNS `fp` TXT record -- stays
 * stable across restarts.
 *
 * The interface is Android-free so the whole TLS stack can be exercised on a
 * plain JVM with throwaway certificates; the production implementation is
 * [com.nearbyshare.network.android.AndroidKeystoreCertificateManager].
 */
interface CertificateProvider {

    /** The device's own certificate chain, leaf first. */
    val certificateChain: Array<X509Certificate>

    /** The private key matching [certificateChain]`[0]`. */
    val privateKey: PrivateKey

    /** SHA-256 of the leaf certificate, lowercase hex (PROTOCOL.md §1 `fp`). */
    val fingerprint: String
        get() = CertificateFingerprints.ofChain(certificateChain)

    /** A key manager that always presents this identity, as client and as server. */
    fun keyManager(): X509ExtendedKeyManager = StaticIdentityKeyManager(this)
}

/**
 * A key manager with exactly one identity and no selection logic.
 *
 * The stock `KeyManagerFactory` expects a `KeyStore` it can read a private key
 * out of, which is precisely what a hardware-backed Android Keystore entry
 * refuses to allow. Presenting the key entry directly sidesteps that: the
 * `PrivateKey` handle signs without the bytes ever leaving the keystore.
 */
class StaticIdentityKeyManager(
    private val provider: CertificateProvider,
    private val alias: String = DEFAULT_ALIAS,
) : X509ExtendedKeyManager() {

    private val aliases = arrayOf(alias)

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = aliases

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String = alias

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = aliases

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String = alias

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String = alias

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String = alias

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = provider.certificateChain

    override fun getPrivateKey(alias: String?): PrivateKey = provider.privateKey

    companion object {
        const val DEFAULT_ALIAS: String = "nearbyshare"
    }
}
