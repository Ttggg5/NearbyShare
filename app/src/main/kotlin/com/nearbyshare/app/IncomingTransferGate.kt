package com.nearbyshare.app

import com.nearbyshare.network.transfer.ApprovalDecision
import com.nearbyshare.network.transfer.IncomingTransferRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One inbound `OFFER` waiting for the user to accept or reject it. */
data class PendingIncomingTransfer(
    val request: IncomingTransferRequest,
    private val decision: CompletableDeferred<ApprovalDecision>,
) {
    fun accept() {
        decision.complete(ApprovalDecision.Accept)
    }

    fun reject(reason: String? = null) {
        decision.complete(ApprovalDecision.Reject(reason))
    }
}

/**
 * Bridges [com.nearbyshare.network.transfer.TransferServer]'s accept-side
 * coroutine -- which is not running on the main thread and has no notion of
 * Activities or Composables -- to the UI's accept/reject prompt.
 *
 * [TransferServer][com.nearbyshare.network.transfer.TransferServer] calls
 * [awaitDecision] and suspends; [IncomingTransferViewModel] observes [pending]
 * and calls [PendingIncomingTransfer.accept]/[PendingIncomingTransfer.reject]
 * once the user answers.
 */
class IncomingTransferGate {

    private val _pending = MutableStateFlow<PendingIncomingTransfer?>(null)
    val pending: StateFlow<PendingIncomingTransfer?> = _pending.asStateFlow()

    suspend fun awaitDecision(request: IncomingTransferRequest): ApprovalDecision {
        val deferred = CompletableDeferred<ApprovalDecision>()
        val pendingTransfer = PendingIncomingTransfer(request, deferred)
        _pending.value = pendingTransfer
        try {
            return deferred.await()
        } finally {
            // Only clear it if nothing newer has already taken its place.
            _pending.compareAndSet(pendingTransfer, null)
        }
    }
}
