package com.pesacast.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pesacast.android.R
import com.pesacast.android.transport.TransportManager
import com.pesacast.android.transport.TransportState
import com.pesacast.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the BLE transport alive when the app is backgrounded
 * or the screen is off. A persistent notification is shown as required by Android.
 *
 * The activity binds to no interface; all control is done via start-command actions.
 */
class MirrorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var transport: TransportManager
    private lateinit var nm: NotificationManager

    // MARK: - Lifecycle

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        transport = TransportManager.getInstance(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Promote to foreground immediately so Android doesn't kill us
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_transport_starting)))

        transport.start()

        // Keep the notification text in sync with connection state
        scope.launch {
            transport.combinedState.collect { state ->
                nm.notify(NOTIFICATION_ID, buildNotification(statusText(state)))
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        transport.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // MARK: - Notification

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, MirrorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.notif_action_stop), stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun statusText(state: TransportState) = when (state) {
        is TransportState.Connected    -> getString(R.string.notif_status_connected)
        is TransportState.Connecting   -> getString(R.string.notif_status_connecting)
        is TransportState.Disconnected -> getString(R.string.notif_status_disconnected)
    }

    // MARK: - Companion

    companion object {
        const val ACTION_STOP = "com.pesacast.android.action.STOP"
        const val CHANNEL_ID = "pesacast_transport"
        const val NOTIFICATION_ID = 1001

        fun startIntent(context: Context) = Intent(context, MirrorService::class.java)
        fun stopIntent(context: Context) =
            Intent(context, MirrorService::class.java).apply { action = ACTION_STOP }
    }
}
