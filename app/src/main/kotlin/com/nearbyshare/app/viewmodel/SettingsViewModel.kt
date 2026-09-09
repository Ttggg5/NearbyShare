package com.nearbyshare.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nearbyshare.app.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Drives [com.nearbyshare.app.ui.SettingsScreen]. */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val deviceName: StateFlow<String> = container.settings.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setDeviceName(name: String) {
        viewModelScope.launch {
            container.settings.setDeviceName(name)
            // The name is baked into the mDNS advertisement; re-publish it so a
            // rename takes effect without an app restart (and without
            // restarting the transfer server, which would abort anything
            // currently in flight).
            runCatching { container.refreshAdvertisement() }
        }
    }
}
