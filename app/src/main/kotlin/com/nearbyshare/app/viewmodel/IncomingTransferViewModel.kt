package com.nearbyshare.app.viewmodel

import androidx.lifecycle.ViewModel
import com.nearbyshare.app.AppContainer
import com.nearbyshare.app.PendingIncomingTransfer
import kotlinx.coroutines.flow.StateFlow

/** Drives [com.nearbyshare.app.ui.IncomingTransferDialog]. */
class IncomingTransferViewModel(private val container: AppContainer) : ViewModel() {

    val pending: StateFlow<PendingIncomingTransfer?> = container.incomingTransferGate.pending

    fun accept() {
        pending.value?.accept()
    }

    fun reject() {
        pending.value?.reject()
    }
}
