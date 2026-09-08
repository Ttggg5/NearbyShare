package com.nearbyshare.protocol

import java.io.IOException

/**
 * Base type for every failure the codec can raise while reading or writing the
 * framed-JSON control channel described in PROTOCOL.md §3.
 *
 * These extend [IOException] so that stream-processing code can catch protocol
 * and transport failures uniformly, while still being able to distinguish them
 * (and map them to the right `ERROR` code) when it wants to.
 */
sealed class ProtocolException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    /** The `ERROR.code` this failure should be reported to the peer as. */
    abstract val errorCode: String
}

/**
 * The 4-byte length prefix declared a payload larger than
 * [Protocol.MAX_PAYLOAD_BYTES]. Per PROTOCOL.md §3 the receiver must abort
 * rather than attempt the allocation.
 */
class FrameTooLargeException(
    /** The declared length, as an unsigned 32-bit value widened to [Long]. */
    val declaredLength: Long,
) : ProtocolException(
    "Control frame declares $declaredLength bytes, exceeding the " +
        "${Protocol.MAX_PAYLOAD_BYTES} byte limit",
) {
    override val errorCode: String get() = Protocol.ErrorCode.FRAME_TOO_LARGE
}

/**
 * The stream ended part-way through a frame: either mid length-prefix or before
 * the declared number of payload bytes had arrived.
 */
class TruncatedFrameException(
    val bytesExpected: Int,
    val bytesRead: Int,
    val duringLengthPrefix: Boolean,
) : ProtocolException(
    "Stream ended after $bytesRead of $bytesExpected bytes while reading the " +
        (if (duringLengthPrefix) "length prefix" else "payload"),
) {
    override val errorCode: String get() = Protocol.ErrorCode.MALFORMED_MESSAGE
}

/** The stream was closed cleanly at a frame boundary; no further messages follow. */
class EndOfStreamException : ProtocolException("Peer closed the connection at a frame boundary") {
    override val errorCode: String get() = Protocol.ErrorCode.IO_ERROR
}

/**
 * The payload was not valid UTF-8 JSON, was not a protocol envelope, or a
 * known message type was missing a required payload field.
 */
class MalformedMessageException(
    message: String,
    cause: Throwable? = null,
) : ProtocolException(message, cause) {
    override val errorCode: String get() = Protocol.ErrorCode.MALFORMED_MESSAGE
}

/**
 * The envelope declared a major protocol version this build does not
 * implement. Per PROTOCOL.md §4/§6 the receiver must reply
 * `ERROR{UNSUPPORTED_VERSION}` and close the connection.
 */
class UnsupportedProtocolVersionException(
    val declaredVersion: Int,
    /** Envelope `id` of the offending message, so the reply can echo it. */
    val messageId: String?,
) : ProtocolException(
    "Unsupported protocol version $declaredVersion (this build speaks ${Protocol.VERSION})",
) {
    override val errorCode: String get() = Protocol.ErrorCode.UNSUPPORTED_VERSION
}
