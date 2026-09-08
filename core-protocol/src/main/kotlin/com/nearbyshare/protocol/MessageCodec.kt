package com.nearbyshare.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Encodes and decodes control messages, and implements the length-prefixed
 * framing of PROTOCOL.md §3:
 *
 * ```
 * [4 bytes: big-endian uint32 length N] [N bytes: UTF-8 JSON payload]
 * ```
 *
 * This object is the executable definition of the wire format. The Windows
 * implementation targets exactly the same bytes, so treat any change here as a
 * protocol change.
 */
object MessageCodec {

    /**
     * The JSON dialect used on the wire.
     *
     * - [Json.ignoreUnknownKeys]: additive fields must not break older peers
     *   (PROTOCOL.md §6).
     * - [Json.encodeDefaults] `false` + [Json.explicitNulls] `false`: optional
     *   fields are simply absent rather than serialised as `null`, which is
     *   what `System.Text.Json` on the Windows side produces and expects.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        isLenient = false
        prettyPrint = false
    }

    /** The envelope of PROTOCOL.md §4, with the payload still un-typed. */
    @Serializable
    private data class Envelope(
        val v: Int,
        val type: String,
        val id: String,
        val payload: JsonObject = JsonObject(emptyMap()),
    )

    // ---------------------------------------------------------------- JSON --

    /** Serialise [message] to its JSON envelope form. */
    fun encodeToJson(message: Message): String {
        val envelope = Envelope(
            v = message.v,
            type = message.type,
            id = message.id,
            payload = message.encodePayload(),
        )
        return json.encodeToString(Envelope.serializer(), envelope)
    }

    /**
     * Parse a JSON envelope.
     *
     * @throws MalformedMessageException if [text] is not a valid envelope, or a
     *   recognised `type` has an invalid payload.
     * @throws UnsupportedProtocolVersionException if `v` is not [Protocol.VERSION].
     */
    fun decodeFromJson(text: String): Message {
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), text)
        } catch (e: SerializationException) {
            throw MalformedMessageException("Not a valid protocol envelope: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw MalformedMessageException("Not a valid protocol envelope: ${e.message}", e)
        }

        if (envelope.v != Protocol.VERSION) {
            throw UnsupportedProtocolVersionException(envelope.v, envelope.id)
        }

        return when (MessageType.fromWireOrNull(envelope.type)) {
            MessageType.HELLO ->
                Message.Hello(envelope.id, envelope.payload(HelloPayload.serializer()), envelope.v)

            MessageType.OFFER ->
                Message.Offer(envelope.id, envelope.payload(OfferPayload.serializer()), envelope.v)

            MessageType.ACCEPT ->
                Message.Accept(envelope.id, envelope.payload(AcceptPayload.serializer()), envelope.v)

            MessageType.REJECT ->
                Message.Reject(envelope.id, envelope.payload(RejectPayload.serializer()), envelope.v)

            MessageType.PROGRESS ->
                Message.Progress(envelope.id, envelope.payload(ProgressPayload.serializer()), envelope.v)

            MessageType.DONE ->
                Message.Done(envelope.id, envelope.payload(DonePayload.serializer()), envelope.v)

            MessageType.ERROR ->
                Message.Error(envelope.id, envelope.payload(ErrorPayload.serializer()), envelope.v)

            MessageType.CANCEL ->
                Message.Cancel(envelope.id, envelope.payload(CancelPayload.serializer()), envelope.v)

            // Forward compatibility (PROTOCOL.md §4): keep the message intact
            // instead of crashing or disconnecting.
            null -> Message.Unknown(envelope.type, envelope.id, envelope.payload, envelope.v)
        }
    }

    private fun <T> Envelope.payload(serializer: KSerializer<T>): T = try {
        json.decodeFromJsonElement(serializer, payload)
    } catch (e: SerializationException) {
        throw MalformedMessageException("Invalid $type payload: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw MalformedMessageException("Invalid $type payload: ${e.message}", e)
    }

    private fun Message.encodePayload(): JsonObject = when (this) {
        is Message.Hello -> json.encodeToJsonElement(HelloPayload.serializer(), payload).jsonObject
        is Message.Offer -> json.encodeToJsonElement(OfferPayload.serializer(), payload).jsonObject
        is Message.Accept -> json.encodeToJsonElement(AcceptPayload.serializer(), payload).jsonObject
        is Message.Reject -> json.encodeToJsonElement(RejectPayload.serializer(), payload).jsonObject
        is Message.Progress -> json.encodeToJsonElement(ProgressPayload.serializer(), payload).jsonObject
        is Message.Done -> json.encodeToJsonElement(DonePayload.serializer(), payload).jsonObject
        is Message.Error -> json.encodeToJsonElement(ErrorPayload.serializer(), payload).jsonObject
        is Message.Cancel -> json.encodeToJsonElement(CancelPayload.serializer(), payload).jsonObject
        is Message.Unknown -> payload
    }

    // ------------------------------------------------------------- Framing --

    /** Encode [message] as a complete frame: 4-byte big-endian length + UTF-8 JSON. */
    fun encodeFrame(message: Message): ByteArray {
        val body = encodeToJson(message).toByteArray(StandardCharsets.UTF_8)
        if (body.size > Protocol.MAX_PAYLOAD_BYTES) {
            throw FrameTooLargeException(body.size.toLong())
        }
        val frame = ByteArray(Protocol.LENGTH_PREFIX_BYTES + body.size)
        writeLengthPrefix(frame, body.size)
        body.copyInto(frame, Protocol.LENGTH_PREFIX_BYTES)
        return frame
    }

    /** Decode exactly one frame from [frame]; the whole array must be one frame. */
    fun decodeFrame(frame: ByteArray): Message {
        if (frame.size < Protocol.LENGTH_PREFIX_BYTES) {
            throw TruncatedFrameException(
                bytesExpected = Protocol.LENGTH_PREFIX_BYTES,
                bytesRead = frame.size,
                duringLengthPrefix = true,
            )
        }
        val declared = readLengthPrefix(frame, 0)
        if (declared > Protocol.MAX_PAYLOAD_BYTES) throw FrameTooLargeException(declared)

        val available = frame.size - Protocol.LENGTH_PREFIX_BYTES
        if (available < declared) {
            throw TruncatedFrameException(
                bytesExpected = declared.toInt(),
                bytesRead = available,
                duringLengthPrefix = false,
            )
        }
        val body = frame.copyOfRange(
            Protocol.LENGTH_PREFIX_BYTES,
            Protocol.LENGTH_PREFIX_BYTES + declared.toInt(),
        )
        return decodeFromJson(body.toString(StandardCharsets.UTF_8))
    }

    /** Write [message] to [out] as a single frame. Does not flush. */
    fun writeFrame(out: OutputStream, message: Message) {
        out.write(encodeFrame(message))
    }

    /**
     * Read exactly one frame from [input].
     *
     * @throws EndOfStreamException if the peer closed cleanly at a frame boundary.
     * @throws TruncatedFrameException if the stream ended mid-frame.
     * @throws FrameTooLargeException if the declared length exceeds
     *   [Protocol.MAX_PAYLOAD_BYTES]. The caller must abort the connection
     *   (PROTOCOL.md §3) -- the declared bytes are deliberately *not* drained.
     */
    fun readFrame(input: InputStream): Message =
        readFrameOrNull(input) ?: throw EndOfStreamException()

    /** As [readFrame], but returns `null` instead of throwing on a clean EOF. */
    fun readFrameOrNull(input: InputStream): Message? {
        val prefix = ByteArray(Protocol.LENGTH_PREFIX_BYTES)
        val prefixRead = input.readAtMost(prefix, prefix.size)
        if (prefixRead == 0) return null
        if (prefixRead < prefix.size) {
            throw TruncatedFrameException(
                bytesExpected = prefix.size,
                bytesRead = prefixRead,
                duringLengthPrefix = true,
            )
        }

        val declared = readLengthPrefix(prefix, 0)
        if (declared > Protocol.MAX_PAYLOAD_BYTES) throw FrameTooLargeException(declared)

        val length = declared.toInt()
        val body = ByteArray(length)
        val bodyRead = input.readAtMost(body, length)
        if (bodyRead < length) {
            throw TruncatedFrameException(
                bytesExpected = length,
                bytesRead = bodyRead,
                duringLengthPrefix = false,
            )
        }
        return decodeFromJson(body.toString(StandardCharsets.UTF_8))
    }

    // -------------------------------------------------------------- Helpers --

    /** Big-endian uint32, widened to [Long] so the top bit is not read as a sign. */
    private fun readLengthPrefix(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun writeLengthPrefix(target: ByteArray, length: Int) {
        target[0] = (length ushr 24 and 0xFF).toByte()
        target[1] = (length ushr 16 and 0xFF).toByte()
        target[2] = (length ushr 8 and 0xFF).toByte()
        target[3] = (length and 0xFF).toByte()
    }

    /** Fill [buffer] with up to [count] bytes; returns how many were actually read. */
    private fun InputStream.readAtMost(buffer: ByteArray, count: Int): Int {
        var total = 0
        while (total < count) {
            val n = try {
                read(buffer, total, count - total)
            } catch (e: EOFException) {
                -1
            }
            if (n < 0) break
            total += n
        }
        return total
    }
}
