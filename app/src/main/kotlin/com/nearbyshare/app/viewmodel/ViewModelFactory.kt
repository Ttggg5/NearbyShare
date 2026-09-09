package com.nearbyshare.app.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nearbyshare.app.AppContainer

/**
 * Hands every ViewModel the [AppContainer] singleton, in place of a generated
 * DI graph -- see [com.nearbyshare.app.AppContainer]'s doc comment.
 */
class ViewModelFactory(
    private val application: Application,
    private val container: AppContainer,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(DeviceListViewModel::class.java) ->
            DeviceListViewModel(container) as T

        modelClass.isAssignableFrom(SendViewModel::class.java) ->
            SendViewModel(application, container) as T

        modelClass.isAssignableFrom(IncomingTransferViewModel::class.java) ->
            IncomingTransferViewModel(container) as T

        modelClass.isAssignableFrom(ReceiveViewModel::class.java) ->
            ReceiveViewModel(container) as T

        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(container) as T

        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
