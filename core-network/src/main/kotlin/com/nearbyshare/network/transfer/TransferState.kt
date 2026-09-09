package com.nearbyshare.network.transfer

import com.nearbyshare.protocol.OfferedFile
import java.io.IOException

/** Which way a transfer is going, from this device's point of view. */
enum class TransferDirection { SENDING, RECEIVING }

/**
 * Everything the UI needs to render one transfer.
 *
 * A closed set of states rather than a bag of nullable fields, so a screen can
 * exhaustively `when` over it and no "transferring but also failed" combination
 * can be represented.
 */
sealed interface TransferState {

    /** The transfer this state belongs to, once one exists. */
    val transferId: String? get() = null

    /** True once the transfer can no longer change. */
    val isTerminal: Boolean get() = false

    /** Nothing in flight. */
    data object Idle : TransferState

    /** TCP connect and TLS handshake in progress. */
    data class Connecting(val peerName: String) : TransferState

    /** Sender: the `OFFER` is out, waiting for the peer's user to decide. */
    data class AwaitingAcceptance(
        override val transferId: String,
        val peerName: String,
        val files: List<OfferedFile>,
    ) : TransferState

    /** Receiver: an `OFFER` arrived and this device's user is being prompted. */
    data class AwaitingApproval(
        override val transferId: String,
        val peerDeviceId: String,
        val peerName: String,
        val files: List<OfferedFile>,
    ) : TransferState

    /** Bytes are moving. */
    data class InProgress(
        override val transferId: String,
        val direction: TransferDirection,
        val peerName: String,
        /** Zero-based index of the file currently moving. */
        val fileIndex: Int,
        val fileCount: Int,
        val fileName: String,
        val bytesTransferred: Long,
        val totalBytes: Long,
    ) : TransferState {
        /** 0f..1f, or `null` when the total is unknown or zero. */
        val fraction: Float?
            get() = if (totalBytes > 0) (bytesTransferred.toDouble() / totalBytes).toFloat() else null
    }

    /** Every file arrived and any supplied checksums matched. */
    data class Completed(
        override val transferId: String,
        val peerName: String,
        val fileNames: List<String>,
        val totalBytes: Long,
        /** Receiver only: where each file was saved. */
        val savedLocations: List<String> = emptyList(),
    ) : TransferState {
        override val isTerminal: Boolean get() = true
    }

    /** The receiving user declined. */
    data class Rejected(
        override val transferId: String,
        val peerName: String,
        val reason: String?,
    ) : TransferState {
        override val isTerminal: Boolean get() = true
    }

    /** Aborted by a `CANCEL` from either side (PROTOCOL.md §5 step 6). */
    data class Cancelled(
        override val transferId: String?,
        val byPeer: Boolean,
    ) : TransferState {
        override val isTerminal: Boolean get() = true
    }

    /** Ended in an `ERROR` (PROTOCOL.md §5 step 7). */
    data class Failed(
        override val transferId: String?,
        /** A [com.nearbyshare.protocol.Protocol.ErrorCode] value. */
        val code: String,
        val message: String?,
    ) : TransferState {
        override val isTerminal: Boolean get() = true
    }
}

/** The receiving user's answer to an `OFFER`. */
sealed interface ApprovalDecision {
    data object Accept : ApprovalDecision
    data class Reject(val reason: String? = null) : ApprovalDecision
}

/** A transfer ended in an `ERROR` (PROTOCOL.md §5 step 7). */
class TransferFailedException(
    /** A [com.nearbyshare.protocol.Protocol.ErrorCode] value. */
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** The peer declined the offer. */
class TransferRejectedException(val reason: String?) :
    IOException("Peer declined the transfer" + (reason?.let { ": $it" } ?: ""))

/** The transfer was cancelled (PROTOCOL.md §5 step 6). */
class TransferCancelledException(val byPeer: Boolean) :
    IOException(if (byPeer) "Peer cancelled the transfer" else "Transfer cancelled")
