package com.nearbyshare.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Which way a transfer went, from this device's point of view. */
enum class TransferDirection { SEND, RECEIVE }

/** How a transfer ended. */
enum class TransferOutcome { COMPLETED, REJECTED, CANCELLED, FAILED }

/** One finished transfer, as it would appear in a history list. */
data class TransferRecord(
    /** The transfer UUID from the `OFFER` (PROTOCOL.md §5). */
    val transferId: String,
    val direction: TransferDirection,
    val peerDeviceId: String,
    val peerName: String,
    val fileNames: List<String>,
    val totalBytes: Long,
    val bytesTransferred: Long,
    val outcome: TransferOutcome,
    /** Wall-clock finish time, epoch milliseconds. */
    val finishedAtMillis: Long,
    /** `ERROR.code` when [outcome] is [TransferOutcome.FAILED]. */
    val errorCode: String? = null,
)

/**
 * Persistence for completed transfers.
 *
 * **Intentionally unimplemented in the MVP.** This interface exists so that the
 * send and receive paths already report their outcomes through a seam, and
 * adding real history later is a Room-backed implementation plus one line in
 * [com.nearbyshare.data] wiring -- not a change to the transfer code.
 *
 * @see NoOpTransferHistoryRepository
 */
interface TransferHistoryRepository {

    /** All recorded transfers, newest first. */
    val records: Flow<List<TransferRecord>>

    /** Persist one finished transfer. */
    suspend fun record(record: TransferRecord)

    /** Forget a single transfer. */
    suspend fun delete(transferId: String)

    /** Forget everything. */
    suspend fun clear()
}

/**
 * The MVP implementation: accepts records and discards them.
 *
 * Swapping in a real implementation must not require touching any caller, so
 * this deliberately behaves like an empty, always-succeeding store rather than
 * throwing `NotImplementedError`.
 */
class NoOpTransferHistoryRepository : TransferHistoryRepository {

    override val records: Flow<List<TransferRecord>> = flowOf(emptyList())

    override suspend fun record(record: TransferRecord) = Unit

    override suspend fun delete(transferId: String) = Unit

    override suspend fun clear() = Unit
}
