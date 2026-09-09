package com.nearbyshare.network.tls

import com.nearbyshare.network.TestCertificates
import com.nearbyshare.network.identity.CertificateProvider
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The TLS half of PROTOCOL.md §2, over real loopback sockets.
 *
 * Two [CertificateProvider]s stand in for two devices; a real handshake runs
 * between them, and the pinning outcomes are asserted on the actual
 * certificates that were exchanged.
 */
class TlsLoopbackTest {

    private val executor = Executors.newCachedThreadPool()

    /**
     * Run one handshake between a client and a server.
     *
     * @return the client-side trust manager (which saw the server's
     *   certificate) and the server-side one (which saw the client's).
     */
    private fun handshake(
        clientProvider: CertificateProvider,
        serverProvider: CertificateProvider,
        clientTrust: TofuTrustManager,
        serverTrust: TofuTrustManager,
    ): Pair<Throwable?, Throwable?> {
        val loopback = InetAddress.getLoopbackAddress()
        val listener = TlsSocketFactory(serverProvider).createListeningSocket(0, bindAddress = loopback)

        val serverSide = executor.submit<Throwable?> {
            try {
                val accepted: Socket = listener.accept()
                TlsSocketFactory(serverProvider)
                    .secureAccepted(accepted, serverTrust, readTimeoutMillis = 5_000)
                    .use { socket ->
                        // Touching the stream makes sure the handshake really
                        // completed rather than being deferred.
                        socket.outputStream.write(1)
                        socket.outputStream.flush()
                    }
                null
            } catch (e: Throwable) {
                e
            }
        }

        val clientSide = try {
            TlsSocketFactory(clientProvider)
                .connect(
                    host = loopback.hostAddress,
                    port = listener.localPort,
                    trustManager = clientTrust,
                    readTimeoutMillis = 5_000,
                ).use { socket -> socket.inputStream.read() }
            null
        } catch (e: Throwable) {
            e
        }

        val serverFailure = serverSide.get(15, TimeUnit.SECONDS)
        runCatching { listener.close() }
        return clientSide to serverFailure
    }

    @Test
    fun `two unknown peers complete a mutual handshake and each sees the other's fingerprint`() {
        val client = TestCertificates.provider("client")
        val server = TestCertificates.provider("server")
        val clientTrust = TofuTrustManager()
        val serverTrust = TofuTrustManager()

        val (clientFailure, serverFailure) = handshake(client, server, clientTrust, serverTrust)
        assertEquals(null, clientFailure?.toString(), "client handshake failed")
        assertEquals(null, serverFailure?.toString(), "server handshake failed")

        assertEquals(
            server.fingerprint,
            clientTrust.presentedFingerprint,
            "the client must record the server's certificate",
        )
        assertEquals(
            client.fingerprint,
            serverTrust.presentedFingerprint,
            "mutual TLS: the server must also receive and record a client certificate",
        )
        assertNotNull(clientTrust.presentedCertificate)
    }

    @Test
    fun `a handshake matching the pinned fingerprint succeeds`() {
        val client = TestCertificates.provider("client")
        val server = TestCertificates.provider("server")

        val clientTrust = TofuTrustManager(
            peerDeviceId = "server-device",
            pinnedFingerprint = server.fingerprint,
            advertisedFingerprint = server.fingerprint,
        )
        val (clientFailure, _) = handshake(client, server, clientTrust, TofuTrustManager())

        assertEquals(null, clientFailure?.toString())
        assertEquals(server.fingerprint, clientTrust.presentedFingerprint)
    }

    @Test
    fun `a peer whose certificate changed is refused at the handshake`() {
        val client = TestCertificates.provider("client")
        val server = TestCertificates.provider("server")
        val stalePin = TestCertificates.provider("the-device-we-remember").fingerprint

        val clientTrust = TofuTrustManager(
            peerDeviceId = "server-device",
            pinnedFingerprint = stalePin,
        )
        val (clientFailure, _) = handshake(client, server, clientTrust, TofuTrustManager())

        val failure = assertNotNull(clientFailure, "a changed certificate must fail the handshake")
        assertTrue(
            failure is SSLHandshakeException || failure is IOException,
            "expected a handshake failure, got ${failure::class.simpleName}",
        )
        assertTrue(
            generateSequence(failure) { it.cause }.any { it is PeerIdentityChangedException },
            "the cause chain must name the identity change, got: ${describe(failure)}",
        )
    }

    @Test
    fun `a certificate that does not match the mDNS advertisement is refused`() {
        val client = TestCertificates.provider("client")
        val server = TestCertificates.provider("server")
        val someoneElse = TestCertificates.provider("someone-else").fingerprint

        val clientTrust = TofuTrustManager(
            peerDeviceId = "server-device",
            advertisedFingerprint = someoneElse,
        )
        val (clientFailure, _) = handshake(client, server, clientTrust, TofuTrustManager())

        val failure = assertNotNull(clientFailure, "an impostor at the advertised address must be refused")
        assertTrue(
            generateSequence(failure) { it.cause }.any { it is AdvertisedFingerprintMismatchException },
            "the cause chain must name the advertisement mismatch, got: ${describe(failure)}",
        )
    }

    @Test
    fun `the negotiated protocol is TLS 1_2 or better`() {
        val client = TestCertificates.provider("client")
        val server = TestCertificates.provider("server")
        val loopback = InetAddress.getLoopbackAddress()
        val listener = TlsSocketFactory(server).createListeningSocket(0, bindAddress = loopback)

        val serverSide = executor.submit<String?> {
            runCatching {
                TlsSocketFactory(server)
                    .secureAccepted(listener.accept(), TofuTrustManager(), readTimeoutMillis = 5_000)
                    .use { it.outputStream.write(1); it.session.protocol }
            }.getOrNull()
        }

        val negotiated = TlsSocketFactory(client).connect(
            host = loopback.hostAddress,
            port = listener.localPort,
            trustManager = TofuTrustManager(),
            readTimeoutMillis = 5_000,
        ).use { socket ->
            socket.inputStream.read()
            socket.session.protocol
        }
        serverSide.get(15, TimeUnit.SECONDS)
        runCatching { listener.close() }

        assertTrue(
            negotiated in TlsSocketFactory.ALLOWED_PROTOCOLS,
            "negotiated $negotiated, expected one of ${TlsSocketFactory.ALLOWED_PROTOCOLS}",
        )
    }

    @Test
    fun `an anonymous peer is refused because mutual TLS is required`() {
        // A trust manager handed an empty chain must not shrug it off.
        val trustManager = TofuTrustManager()
        try {
            trustManager.checkClientTrusted(emptyArray(), "EC")
            fail("an empty certificate chain must be rejected")
        } catch (expected: java.security.cert.CertificateException) {
            assertTrue(expected.message.orEmpty().contains("no certificate"))
        }
    }

    private fun describe(failure: Throwable): String =
        generateSequence(failure) { it.cause }.joinToString(" <- ") { it::class.simpleName.orEmpty() }
}
