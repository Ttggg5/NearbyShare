package com.nearbyshare.app.viewmodel

import androidx.lifecycle.ViewModel
import com.nearbyshare.app.AppContainer
import com.nearbyshare.network.transfer.TransferState
import kotlinx.coroutines.flow.StateFlow

/** Drives [com.nearbyshare.app.ui.ReceiveProgressScreen]. */
class ReceiveViewModel(container: AppContainer) : ViewModel() {

    /** The most recent state of whatever [com.nearbyshare.network.transfer.TransferServer] is doing. */
    val state: StateFlow<TransferState> = container.transferServer.lastState
}
