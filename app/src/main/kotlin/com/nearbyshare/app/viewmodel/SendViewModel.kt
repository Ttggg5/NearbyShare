package com.nearbyshare.app.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nearbyshare.app.AppContainer
import com.nearbyshare.app.service.TransferForegroundService
import com.nearbyshare.data.TransferDirection as HistoryDirection
import com.nearbyshare.data.TransferOutcome
import com.nearbyshare.data.TransferRecord
import com.nearbyshare.network.android.ContentUriFileSource
import com.nearbyshare.network.discovery.PeerDevice
import com.nearbyshare.network.transfer.FileSource
import com.nearbyshare.network.transfer.TransferSession
import com.nearbyshare.network.transfer.TransferState
import com.nearbyshare.protocol.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Drives [com.nearbyshare.app.ui.SendProgressScreen]. */
class SendViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private var session: TransferSession? = null

    /** The peer this screen is currently sending to, for the header. */
    var peerName: String = ""
        private set

    fun send(peer: PeerDevice, uris: List<Uri>) {
        peerName = peer.name
        session = null
        _state.value = TransferState.Connecting(peer.name)

        // Keeps the process (and this coroutine) alive if the user backgrounds
        // the app mid-transfer; see TransferForegroundService.
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), TransferForegroundService::class.java)
                .setAction(TransferForegroundService.ACTION_START_SHARING),
        )

        viewModelScope.launch {
            val sources = ContentUriFileSource.forUris(getApplication(), uris)
            if (sources.isEmpty()) {
                _state.value = TransferState.Failed(
                    transferId = null,
                    code = Protocol.ErrorCode.IO_ERROR,
                    message = "None of the selected files could be read",
                )
                return@launch
            }

            runCatching {
                container.sendFiles(
                    peer = peer,
                    sources = sources,
                    onSessionStarted = { started ->
                        session = started
                        viewModelScope.launch {
                            started.state.collect { transferState ->
                                _state.value = transferState
                                if (transferState.isTerminal) {
                                    recordHistory(peer, sources, transferState)
                                }
                            }
                        }
                    },
                )
            }
            // On success or failure, `session.state` (collected above) already
            // holds the terminal TransferState -- TransferSession publishes it
            // before throwing, so there is nothing further to do here.
        }
    }

    /**
     * Persist the outcome via [AppContainer.transferHistoryRepository].
     *
     * A no-op in the MVP (see [com.nearbyshare.data.NoOpTransferHistoryRepository]),
     * but the send path already reports through this seam so a future
     * persistent implementation is a storage change, not a call-site change.
     */
    private fun recordHistory(peer: PeerDevice, sources: List<FileSource>, terminal: TransferState) {
        val outcome = when (terminal) {
            is TransferState.Completed -> TransferOutcome.COMPLETED
            is TransferState.Rejected -> TransferOutcome.REJECTED
            is TransferState.Cancelled -> TransferOutcome.CANCELLED
            is TransferState.Failed -> TransferOutcome.FAILED
            else -> return
        }
        val errorCode = (terminal as? TransferState.Failed)?.code
        val transferId = terminal.transferId ?: return
        viewModelScope.launch {
            container.transferHistoryRepository.record(
                TransferRecord(
                    transferId = transferId,
                    direction = HistoryDirection.SEND,
                    peerDeviceId = peer.deviceId,
                    peerName = peer.name,
                    fileNames = sources.map { it.name },
                    totalBytes = sources.sumOf { it.size },
                    bytesTransferred = if (outcome == TransferOutcome.COMPLETED) sources.sumOf { it.size } else 0L,
                    outcome = outcome,
                    finishedAtMillis = System.currentTimeMillis(),
                    errorCode = errorCode,
                ),
            )
        }
    }

    fun cancel() {
        session?.cancel()
    }

    fun reset() {
        session = null
        _state.value = TransferState.Idle
    }
}
