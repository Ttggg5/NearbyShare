package com.nearbyshare.protocol

/**
 * The protocol-level rules that decide whether an incoming message needs an
 * automatic `ERROR` reply, and whether the connection can survive it.
 *
 * This is deliberately separate from [MessageCodec]: the codec only decides
 * what the bytes *mean*, this decides how a conforming peer *responds*. It is
 * pure and side-effect free so both [com.nearbyshare.protocol] users -- the
 * send and the receive side -- can share it and test it directly.
 */
object MessageDispatch {

    /** What a peer should do after an automatic error reply has been sent. */
    enum class Disposition {
        /** Reply and keep reading: the connection is still in a valid state. */
        CONTINUE,

        /** Reply, then close the connection cleanly. */
        CLOSE,
    }

    data class AutoResponse(
        val error: Message.Error,
        val disposition: Disposition,
    )

    /**
     * The automatic reply required for [message], or `null` if the message is
     * one this build understands and the caller should handle normally.
     *
     * Implements the forward-compatibility rule of PROTOCOL.md §4: an
     * unrecognised `type` must be answered with
     * `ERROR{code: "UNSUPPORTED_TYPE"}` echoing the offending message's `id`,
     * and must leave the connection usable rather than crashing or
     * disconnecting abruptly.
     */
    fun autoResponseFor(message: Message): AutoResponse? = when (message) {
        is Message.Unknown -> AutoResponse(
            error = Message.error(
                code = Protocol.ErrorCode.UNSUPPORTED_TYPE,
                message = "Unsupported message type: ${message.type}",
                transferId = null,
                envelopeId = message.id,
            ),
            disposition = Disposition.CONTINUE,
        )

        else -> null
    }

    /**
     * The `ERROR` reply for a decode failure, plus whether the connection can
     * continue.
     *
     * A framing or version failure always closes the connection: after a bad
     * length prefix or an unreadable envelope the stream is no longer known to
     * be positioned at a frame boundary.
     */
    fun autoResponseFor(failure: ProtocolException): AutoResponse? = when (failure) {
        is UnsupportedProtocolVersionException -> AutoResponse(
            error = Message.error(
                code = Protocol.ErrorCode.UNSUPPORTED_VERSION,
                message = failure.message,
                transferId = null,
                envelopeId = failure.messageId ?: Message.newId(),
            ),
            disposition = Disposition.CLOSE,
        )

        is FrameTooLargeException, is MalformedMessageException, is TruncatedFrameException ->
            AutoResponse(
                error = Message.error(code = failure.errorCode, message = failure.message),
                disposition = Disposition.CLOSE,
            )

        // The peer is already gone; there is nobody to reply to.
        is EndOfStreamException -> null
    }
}
