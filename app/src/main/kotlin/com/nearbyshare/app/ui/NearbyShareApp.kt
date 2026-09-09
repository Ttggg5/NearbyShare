package com.nearbyshare.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nearbyshare.app.viewmodel.DeviceListViewModel
import com.nearbyshare.app.viewmodel.IncomingTransferViewModel
import com.nearbyshare.app.viewmodel.ReceiveViewModel
import com.nearbyshare.app.viewmodel.SendViewModel
import com.nearbyshare.app.viewmodel.SettingsViewModel
import com.nearbyshare.network.discovery.PeerDevice

/**
 * The app's screens. Navigation is a plain state switch rather than
 * Navigation-Compose: with only four destinations and one that is really an
 * overlay ([IncomingTransferDialog]), a `when` is less machinery to get right
 * than a nav graph.
 */
private sealed interface Screen {
    data object DeviceList : Screen
    data object Settings : Screen
    data object Send : Screen
    data object Receive : Screen
}

@Composable
fun NearbyShareApp(factory: ViewModelProvider.Factory) {
    val deviceListViewModel: DeviceListViewModel = viewModel(factory = factory)
    val sendViewModel: SendViewModel = viewModel(factory = factory)
    val incomingViewModel: IncomingTransferViewModel = viewModel(factory = factory)
    val receiveViewModel: ReceiveViewModel = viewModel(factory = factory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)

    var screen by remember { mutableStateOf<Screen>(Screen.DeviceList) }

    when (screen) {
        Screen.DeviceList -> DeviceListScreen(
            viewModel = deviceListViewModel,
            onOpenSettings = { screen = Screen.Settings },
            onFilesPicked = { peer: PeerDevice, uris: List<Uri> ->
                sendViewModel.send(peer, uris)
                screen = Screen.Send
            },
        )

        Screen.Settings -> SettingsScreen(
            viewModel = settingsViewModel,
            onBack = { screen = Screen.DeviceList },
        )

        Screen.Send -> SendProgressScreen(
            viewModel = sendViewModel,
            onDone = {
                sendViewModel.reset()
                screen = Screen.DeviceList
            },
        )

        Screen.Receive -> ReceiveProgressScreen(
            viewModel = receiveViewModel,
            onDone = { screen = Screen.DeviceList },
        )
    }

    // An inbound OFFER can arrive on any screen; show it as an overlay on top
    // of whatever is currently displayed (PROTOCOL.md §5 step 3).
    val pending by incomingViewModel.pending.collectAsStateWithLifecycle()
    pending?.let { request ->
        IncomingTransferDialog(
            pending = request,
            onAccept = {
                incomingViewModel.accept()
                screen = Screen.Receive
            },
            onReject = incomingViewModel::reject,
        )
    }
}
