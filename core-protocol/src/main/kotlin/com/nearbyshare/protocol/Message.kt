package com.nearbyshare.protocol

import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * A decoded protocol message: the envelope of PROTOCOL.md §4 plus its typed
 * `payload`.
 *
 * `type` is an open string set, so anything this build does not recognise is
 * preserved as [Unknown] rather than rejected -- see the forward-compatibility
 * rule in PROTOCOL.md §4 and [MessageDispatch.autoResponseFor].
 */
sealed interface Message {

    /** Envelope `v`: the protocol version the message was constructed under. */
    val v: Int

    /** Envelope `id`: the transfer UUID, or a per-message UUID for `HELLO`. */
    val id: String

    /** Envelope `type`, as it appears on the wire. */
    val type: String

    data class Hello(
        override val id: String,
        val payload: HelloPayload,
        override val v: Int = Protocol.VERSION,
    ) : Message {
        override val type: String get() = MessageType.HELLO.wire
    }

    data class Offer(
        override val id: String,
        val payload: OfferPayload,
        override val v: Int = Protocol.VERSION,
    ) : Message {
        override val type: String get() = MessageType.OFFER.wire
    }

    data class Accept(
        override val id: String,
        val payload: AcceptPayload,
        override val v: Int = Protocol.VERSION,
    ) : Message {
        override val type: String get() = MessageType.ACCEPT.wire
    }

    data class Reject(
        override val id: String,
        val payload: RejectPayload,
        override val v: Int = Protocol.VERSION,
    ) : Message {
        override val type: String get() = MessageType.REJECT.wire
    }

    data class Progress(
        override val id: String,
        val payload: ProgressPayload,
        override val v: Int = Protocol.VERSION,
    ) : Message {
        override val type: String get() = MessageType.PROGRESS.wire
    }

    data class Done(
        override val id: String,
        val payload: DonePayload,
        override val v: Int = Protocol.VERSION,
    ) : Message {
        override val type: String get() = MessageType.DONE.wire
    }

    data class Error(
        override val id: String,
        val payload: ErrorPayload,
        override val v: Int = Protocol.VERSION,
    ) : Message {
        override val type: String get() = MessageType.ERROR.wire
    }

    data class Cancel(
        override val id: String,
        val payload: CancelPayload,
        override val v: Int = Protocol.VERSION,
    ) : Message {
        override val type: String get() = MessageType.CANCEL.wire
    }

    /**
     * A well-formed envelope carrying a `type` this build does not implement.
     *
     * The raw payload is retained verbatim so the message can be re-encoded
     * byte-for-equivalent, and so that a future build can interpret it without
     * a wire change.
     */
    data class Unknown(
        override val type: String,
        override val id: String,
        val payload: JsonObject,
        override val v: Int = Protocol.VERSION,
    ) : Message

    companion object {
        /** Convenience builders that fill in `v` and generate envelope ids. */

        fun hello(deviceId: String, deviceName: String): Hello = Hello(
            id = newId(),
            payload = HelloPayload(
                deviceId = deviceId,
                deviceName = deviceName,
                protocolVersion = Protocol.VERSION,
            ),
        )

        fun offer(transferId: String, files: List<OfferedFile>): Offer = Offer(
            id = transferId,
            payload = OfferPayload(transferId = transferId, files = files),
        )

        fun accept(transferId: String): Accept =
            Accept(id = transferId, payload = AcceptPayload(transferId))

        fun reject(transferId: String, reason: String? = null): Reject =
            Reject(id = transferId, payload = RejectPayload(transferId, reason))

        fun progress(
            transferId: String,
            fileIndex: Int,
            bytesTransferred: Long,
            totalBytes: Long,
        ): Progress = Progress(
            id = transferId,
            payload = ProgressPayload(transferId, fileIndex, bytesTransferred, totalBytes),
        )

        fun done(transferId: String, fileIndex: FileIndex = FileIndex.All): Done =
            Done(id = transferId, payload = DonePayload(transferId, fileIndex))

        fun error(
            code: String,
            message: String? = null,
            transferId: String? = null,
            envelopeId: String = transferId ?: newId(),
        ): Error = Error(
            id = envelopeId,
            payload = ErrorPayload(transferId = transferId, code = code, message = message),
        )

        fun cancel(transferId: String): Cancel =
            Cancel(id = transferId, payload = CancelPayload(transferId))

        /** A fresh random envelope id (UUID v4, lowercase, hyphenated). */
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** The [MessageType] of a known message, or `null` for [Message.Unknown]. */
val Message.messageType: MessageType?
    get() = MessageType.fromWireOrNull(type)

/** The transfer this message belongs to, or `null` for `HELLO` / connection-scoped `ERROR`. */
val Message.transferId: String?
    get() = when (this) {
        is Message.Hello -> null
        is Message.Offer -> payload.transferId
        is Message.Accept -> payload.transferId
        is Message.Reject -> payload.transferId
        is Message.Progress -> payload.transferId
        is Message.Done -> payload.transferId
        is Message.Error -> payload.transferId
        is Message.Cancel -> payload.transferId
        is Message.Unknown -> null
    }
