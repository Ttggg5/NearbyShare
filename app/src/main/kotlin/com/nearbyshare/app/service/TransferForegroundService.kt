package com.nearbyshare.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nearbyshare.app.AppContainer
import com.nearbyshare.app.MainActivity
import com.nearbyshare.app.NearbyShareApplication
import com.nearbyshare.network.transfer.TransferDirection
import com.nearbyshare.network.transfer.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Hosts [AppContainer.transferServer]'s accept loop for the process lifetime and
 * surfaces whatever it (or an outbound send) is doing as a foreground
 * notification, so a transfer survives the user backgrounding the app or the
 * screen turning off.
 *
 * The heavy lifting -- the TLS accept loop, [com.nearbyshare.network.transfer.TransferSession] --
 * lives in `:core-network` singletons owned by [AppContainer], not in this
 * service; the service's job is only to (a) trigger [AppContainer.startSharing]
 * once, (b) keep the process classified as foreground while either direction is
 * active, and (c) render [TransferState] as a notification.
 */
class TransferForegroundService : Service() {

    private lateinit var container: AppContainer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var sharingStarted = false
    private var observerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as NearbyShareApplication).container
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(RoleState.Idle))

        when (intent?.action) {
            ACTION_STOP_SHARING -> {
                stopSharingAndSelf()
                return START_NOT_STICKY
            }

            else -> startSharingOnce()
        }

        if (observerJob == null) observerJob = observeState()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startSharingOnce() {
        if (sharingStarted) return
        sharingStarted = true
        serviceScope.launch {
            runCatching { container.startSharing() }
        }
    }

    private fun stopSharingAndSelf() {
        runCatching { container.stopSharing() }
        sharingStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Mirrors whichever of the receive-side ([AppContainer.transferServer]) or
     * send-side ([AppContainer.outboundSession]) state changed most recently
     * into the notification. The two directions cannot both be mid-transfer at
     * once on a single device in this MVP, so "most recent write wins" never
     * has to arbitrate between two genuinely competing transfers.
     */
    private fun observeState(): Job = serviceScope.launch {
        launch {
            container.transferServer.lastState.collectLatest { state ->
                updateNotification(RoleState.Receiving(state))
            }
        }
        launch {
            container.outboundSession
                .flatMapLatest { session -> session?.state ?: flowOf(TransferState.Idle) }
                .collectLatest { state ->
                    if (state != TransferState.Idle) updateNotification(RoleState.Sending(state))
                }
        }
    }

    // ------------------------------------------------------------ Notification --

    private sealed interface RoleState {
        data object Idle : RoleState
        data class Sending(val state: TransferState) : RoleState
        data class Receiving(val state: TransferState) : RoleState
    }

    private fun updateNotification(role: RoleState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(role))
    }

    private fun buildNotification(role: RoleState): Notification {
        val (title, text) = contentFor(role)

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, TransferForegroundService::class.java).setAction(ACTION_STOP_SHARING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop sharing", stopIntent)
            .apply { progressFor(role)?.let { (max, current, indeterminate) -> setProgress(max, current, indeterminate) } }
            .build()
    }

    private fun contentFor(role: RoleState): Pair<String, String> = when (role) {
        RoleState.Idle -> "NearbyShare is ready" to "Visible to other devices on this network"

        is RoleState.Sending -> "Sending" to describe(role.state)
        is RoleState.Receiving -> "Receiving" to describe(role.state)
    }

    private fun describe(state: TransferState): String = when (state) {
        TransferState.Idle -> "Waiting"
        is TransferState.Connecting -> "Connecting to ${state.peerName}…"
        is TransferState.AwaitingAcceptance -> "Waiting for ${state.peerName} to accept"
        is TransferState.AwaitingApproval -> "${state.peerName} wants to send you a file"
        is TransferState.InProgress -> {
            val verb = if (state.direction == TransferDirection.SENDING) "Sending" else "Receiving"
            val percent = state.fraction?.let { " (${(it * 100).toInt()}%)" } ?: ""
            "$verb ${state.fileName}$percent"
        }

        is TransferState.Completed -> "Done – ${state.fileNames.size} file(s) with ${state.peerName}"
        is TransferState.Rejected -> "${state.peerName} declined the transfer"
        is TransferState.Cancelled -> "Transfer cancelled"
        is TransferState.Failed -> "Transfer failed: ${state.message ?: state.code}"
    }

    private data class Progress(val max: Int, val current: Int, val indeterminate: Boolean)

    private fun progressFor(role: RoleState): Progress? {
        val state = when (role) {
            is RoleState.Sending -> role.state
            is RoleState.Receiving -> role.state
            RoleState.Idle -> return null
        }
        val inProgress = state as? TransferState.InProgress ?: return null
        val fraction = inProgress.fraction ?: return Progress(0, 0, indeterminate = true)
        return Progress(max = 100, current = (fraction * 100).toInt(), indeterminate = false)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File transfers",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the progress of files being sent or received"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "nearbyshare_transfers"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START_SHARING = "com.nearbyshare.app.action.START_SHARING"
        const val ACTION_STOP_SHARING = "com.nearbyshare.app.action.STOP_SHARING"

        /** Start (or no-op if already running) discovery + the transfer server. */
        fun start(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java)
                .setAction(ACTION_START_SHARING)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java)
                .setAction(ACTION_STOP_SHARING)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
