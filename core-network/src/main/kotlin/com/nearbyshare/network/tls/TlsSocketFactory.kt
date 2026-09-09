package com.nearbyshare.network.tls

import com.nearbyshare.network.identity.CertificateProvider
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Builds the TLS sockets described in PROTOCOL.md §2 step 3: a device is a TLS
 * *server* on its listening socket and a TLS *client* when it initiates.
 *
 * Both directions use mutual authentication -- the listening side requires a
 * client certificate -- because the trust model is symmetric: each side pins
 * the other's fingerprint, so each side has to present one.
 *
 * Android-free: an `SSLContext` built from a [CertificateProvider] and a
 * [TofuTrustManager] behaves identically on a desktop JVM, which is what makes
 * the loopback tests possible.
 */
class TlsSocketFactory(
    private val certificateProvider: CertificateProvider,
) {

    /**
     * A **plain TCP** listening socket bound to [port] (`0` picks a free one).
     *
     * Deliberately not an `SSLServerSocket`: a listener cannot know which peer
     * will connect, so it cannot be given a useful trust manager. Each accepted
     * connection is upgraded individually by [secureAccepted] with its own
     * [TofuTrustManager], whose [TofuTrustManager.presentedFingerprint] then
     * refers to exactly one peer.
     */
    fun createListeningSocket(
        port: Int,
        backlog: Int = DEFAULT_BACKLOG,
        bindAddress: InetAddress? = null,
    ): ServerSocket = ServerSocket(port, backlog, bindAddress).apply {
        reuseAddress = true
    }

    /**
     * Connect to [host]:[port] and complete the TLS handshake, pinning against
     * [trustManager].
     *
     * @param connectTimeoutMillis TCP connect timeout.
     * @param readTimeoutMillis socket read timeout for control messages; a
     *   stalled peer must not hang a transfer forever.
     */
    fun connect(
        host: String,
        port: Int,
        trustManager: TofuTrustManager,
        connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
        readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    ): SSLSocket {
        val plain = Socket()
        try {
            // PROTOCOL.md §2 step 3: plain TCP first, then the TLS handshake.
            plain.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            plain.soTimeout = readTimeoutMillis
            plain.tcpNoDelay = true

            val context = contextWith(trustManager)
            val secured = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            secured.useClientMode = true
            secured.configure()
            secured.soTimeout = readTimeoutMillis
            secured.startHandshake()
            return secured
        } catch (e: Throwable) {
            runCatching { plain.close() }
            throw e
        }
    }

    /**
     * Complete the server-side handshake on an accepted socket, pinning against
     * [trustManager].
     *
     * `startHandshake()` is called explicitly so that a trust failure surfaces
     * here rather than on the first stream read, where it would be
     * indistinguishable from an I/O error.
     */
    fun secureAccepted(
        accepted: Socket,
        trustManager: TofuTrustManager,
        readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    ): SSLSocket {
        val context = contextWith(trustManager)
        val secured = context.socketFactory.createSocket(
            accepted,
            accepted.inetAddress?.hostAddress,
            accepted.port,
            true,
        ) as SSLSocket
        secured.useClientMode = false
        secured.needClientAuth = true
        secured.configure()
        secured.soTimeout = readTimeoutMillis
        secured.startHandshake()
        return secured
    }

    private fun contextWith(trustManager: TofuTrustManager): SSLContext =
        SSLContext.getInstance(PROTOCOL).apply {
            init(arrayOf(certificateProvider.keyManager()), arrayOf(trustManager), null)
        }

    private fun SSLSocket.configure() {
        enabledProtocols = supportedProtocols.filter { it in ALLOWED_PROTOCOLS }.toTypedArray()
        tcpNoDelay = true
    }

    companion object {
        private const val PROTOCOL = "TLS"

        /**
         * TLS 1.2 only, deliberately excluding 1.3.
         *
         * Mutual TLS (a client certificate, which this protocol always uses) is
         * where TLS 1.3 interop breaks down in practice: Windows's Schannel
         * provider -- what .NET's `SslStream` uses under the hood -- has a long
         * history of aborting a TLS 1.3 handshake that requests a client
         * certificate against a non-Windows peer, closing the raw socket instead
         * of sending an alert. That surfaces on the .NET side as exactly
         * "Received an unexpected EOF or 0 bytes from the transport stream" and
         * on the Android side as nothing at all, since the connection never gets
         * far enough to reach any of this app's own code. TLS 1.2 mutual
         * authentication has none of these issues and is universally supported,
         * so it is the floor *and* the ceiling here.
         */
        val ALLOWED_PROTOCOLS: Set<String> = setOf("TLSv1.2")

        const val DEFAULT_BACKLOG: Int = 8
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 10_000

        /**
         * Generous, because it also covers the window where the receiving
         * user is staring at the accept/reject prompt.
         */
        const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 120_000
    }
}
