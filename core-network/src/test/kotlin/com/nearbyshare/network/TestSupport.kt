package com.nearbyshare.network

import com.nearbyshare.network.identity.CertificateProvider
import com.nearbyshare.network.transfer.FileSink
import com.nearbyshare.network.transfer.FileSource
import com.nearbyshare.network.transfer.IncomingFile
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

/**
 * Throwaway self-signed certificates for the TLS tests.
 *
 * BouncyCastle stands in for the Android Keystore, which mints the real ones on
 * device. The point of the exercise is that everything downstream of
 * [CertificateProvider] behaves identically either way.
 */
object TestCertificates {

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val serial = AtomicInteger(1)

    fun provider(commonName: String = "test-device"): CertificateProvider {
        val keyPair = generateKeyPair()
        val certificate = selfSign(keyPair, commonName)
        return object : CertificateProvider {
            override val certificateChain: Array<X509Certificate> = arrayOf(certificate)
            override val privateKey: PrivateKey = keyPair.private
        }
    }

    private fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun selfSign(keyPair: KeyPair, commonName: String): X509Certificate {
        val subject = X500Name("CN=$commonName")
        val notBefore = Date(System.currentTimeMillis() - 86_400_000L)
        val notAfter = Date(System.currentTimeMillis() + 3_650L * 86_400_000L)

        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(serial.getAndIncrement().toLong()),
            notBefore,
            notAfter,
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}

/** A pair of connected plain TCP sockets on the loopback interface. */
class SocketPair private constructor(
    val client: Socket,
    val server: Socket,
    private val listener: ServerSocket,
) : AutoCloseable {

    override fun close() {
        runCatching { client.close() }
        runCatching { server.close() }
        runCatching { listener.close() }
    }

    companion object {
        fun open(): SocketPair {
            val loopback = InetAddress.getLoopbackAddress()
            val listener = ServerSocket(0, 1, loopback)
            val client = Socket(loopback, listener.localPort)
            val server = listener.accept()
            return SocketPair(client, server, listener)
        }
    }
}

/** A [FileSource] backed by a byte array. */
class InMemoryFileSource(
    override val name: String,
    private val bytes: ByteArray,
    override val mime: String? = "application/octet-stream",
    override val sha256: String? = null,
) : FileSource {
    override val size: Long get() = bytes.size.toLong()
    override fun openStream(): InputStream = ByteArrayInputStream(bytes)
}

/** A [FileSink] that keeps committed files in memory, so tests can assert on them. */
class InMemoryFileSink(
    /** When set, [FileSink.create] throws this many bytes into the file then fails. */
    private val failWriteAfterBytes: Long? = null,
) : FileSink {

    val committed = LinkedHashMap<String, ByteArray>()
    val aborted = mutableListOf<String>()

    override fun create(name: String, declaredSize: Long, mime: String?): IncomingFile {
        val buffer = ByteArrayOutputStream()
        val limit = failWriteAfterBytes
        val stream = if (limit == null) {
            buffer
        } else {
            object : OutputStream() {
                private var written = 0L
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (written + len > limit) throw IOException("No space left on device (ENOSPC)")
                    buffer.write(b, off, len)
                    written += len
                }
            }
        }

        return object : IncomingFile {
            override val outputStream: OutputStream = stream

            override fun commit(): String {
                committed[name] = buffer.toByteArray()
                return "memory://$name"
            }

            override fun abort() {
                aborted += name
            }

            override fun close() = Unit
        }
    }
}

/** Deterministic pseudo-random payload, so a corrupted transfer is obvious. */
fun testBytes(size: Int, seed: Int = 1): ByteArray {
    val random = java.util.Random(seed.toLong())
    return ByteArray(size).also { random.nextBytes(it) }
}

/** SHA-256 of [bytes] as lowercase hex, matching PROTOCOL.md's `sha256` format. */
fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
