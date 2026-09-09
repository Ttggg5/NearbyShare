package com.nearbyshare.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.nearbyshare.app.service.TransferForegroundService
import com.nearbyshare.app.ui.NearbyShareApp
import com.nearbyshare.app.ui.theme.NearbyShareTheme
import com.nearbyshare.app.viewmodel.ViewModelFactory

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as NearbyShareApplication).container

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Sharing still works without a visible notification; nothing to react to. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()
        // Starts advertising + the TLS accept loop and keeps the process alive
        // in the background; see TransferForegroundService.
        TransferForegroundService.start(this)

        setContent {
            NearbyShareTheme {
                NearbyShareApp(factory = ViewModelFactory(application, container))
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
