package com.nearbyshare.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Typed `payload` bodies for the MVP message set (PROTOCOL.md §5).
 *
 * The property names below are the *wire* names and are part of the
 * cross-implementation contract with the Windows client. Do not rename them
 * without updating PROTOCOL.md, the JSON fixtures under
 * `src/test/resources/fixtures/`, and the C# side in the same change set.
 */

/** `HELLO` payload: `{ "deviceId", "deviceName", "protocolVersion" }`. */
@Serializable
data class HelloPayload(
    val deviceId: String,
    val deviceName: String,
    val protocolVersion: Int,
)

/** One entry of `OFFER.files`: `{ "name", "size", "mime", "sha256" }`. */
@Serializable
data class OfferedFile(
    val name: String,
    val size: Long,
    val mime: String? = null,
    /** Optional; when present the receiver verifies it after writing to disk. */
    val sha256: String? = null,
)

/** `OFFER` payload: `{ "transferId", "files": [...] }`. */
@Serializable
data class OfferPayload(
    val transferId: String,
    val files: List<OfferedFile>,
) {
    /** Total number of raw bytes that will be streamed if this offer is accepted. */
    val totalBytes: Long get() = files.sumOf { it.size }
}

/** `ACCEPT` payload: `{ "transferId" }`. */
@Serializable
data class AcceptPayload(
    val transferId: String,
)

/** `REJECT` payload: `{ "transferId", "reason" }`. */
@Serializable
data class RejectPayload(
    val transferId: String,
    val reason: String? = null,
)

/** `PROGRESS` payload: `{ "transferId", "fileIndex", "bytesTransferred", "totalBytes" }`. */
@Serializable
data class ProgressPayload(
    val transferId: String,
    val fileIndex: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
)

/**
 * `fileIndex` in a `DONE` payload is a union of an integer index and the
 * sentinel string `"all"` (PROTOCOL.md §5).
 */
@Serializable(with = FileIndexSerializer::class)
sealed interface FileIndex {
    /** A single zero-based file index. */
    data class Index(val value: Int) : FileIndex

    /** Every file in the offer is complete: encodes as the string `"all"`. */
    data object All : FileIndex

    companion object {
        /** Wire sentinel used for [All]. */
        const val ALL_SENTINEL: String = "all"
    }
}

/** Encodes [FileIndex] as either a JSON number or the JSON string `"all"`. */
object FileIndexSerializer : KSerializer<FileIndex> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.nearbyshare.protocol.FileIndex", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: FileIndex) {
        val json = encoder as? JsonEncoder
            ?: throw MalformedMessageException("FileIndex can only be encoded to JSON")
        when (value) {
            is FileIndex.Index -> json.encodeJsonElement(JsonPrimitive(value.value))
            FileIndex.All -> json.encodeJsonElement(JsonPrimitive(FileIndex.ALL_SENTINEL))
        }
    }

    override fun deserialize(decoder: Decoder): FileIndex {
        val json = decoder as? JsonDecoder
            ?: throw MalformedMessageException("FileIndex can only be decoded from JSON")
        val element = json.decodeJsonElement()
        val primitive = element as? JsonPrimitive
            ?: throw MalformedMessageException("fileIndex must be a number or \"all\", got: $element")
        if (primitive.isString) {
            if (primitive.content == FileIndex.ALL_SENTINEL) return FileIndex.All
            throw MalformedMessageException(
                "fileIndex string must be \"${FileIndex.ALL_SENTINEL}\", got: \"${primitive.content}\"",
            )
        }
        val index = primitive.intOrNull
            ?: throw MalformedMessageException("fileIndex must be an integer, got: ${primitive.content}")
        if (index < 0) throw MalformedMessageException("fileIndex must not be negative, got: $index")
        return FileIndex.Index(index)
    }
}

/** `DONE` payload: `{ "transferId", "fileIndex" }` where `fileIndex` may be `"all"`. */
@Serializable
data class DonePayload(
    val transferId: String,
    val fileIndex: FileIndex,
)

/**
 * `ERROR` payload: `{ "transferId", "code", "message" }`.
 *
 * `transferId` is absent for connection-scoped failures that happen before any
 * transfer exists (e.g. `UNSUPPORTED_VERSION` on the very first message).
 */
@Serializable
data class ErrorPayload(
    val transferId: String? = null,
    val code: String,
    val message: String? = null,
)

/** `CANCEL` payload: `{ "transferId" }`. */
@Serializable
data class CancelPayload(
    val transferId: String,
)
