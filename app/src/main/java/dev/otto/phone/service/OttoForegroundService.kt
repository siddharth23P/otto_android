package dev.otto.phone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import dev.otto.phone.transport.EventBus
import dev.otto.phone.ui.MainActivity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Alive while a turn runs, so Doze and the OOM killer leave the agent alone. */
class OttoForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_CANCEL) {
            // Stop in the notification: the chat cancels the turn, and the final error takes the notification
            // down as any stopped turn does. A tap on a stale notification started this service for nothing.
            EventBus.emit(buildJsonObject { put("type", "cancel_request") })
            if (wakeLock == null) stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Otto is working", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Otto is working on your request")
            .setContentText(intent?.getStringExtra(EXTRA_TEXT) ?: "")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            // A tap brings back the chat the turn is running in.
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop",
                PendingIntent.getService(this, 0, Intent(this, OttoForegroundService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            ).build())
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "otto:turn").also { it.acquire(30 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "otto.turn"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "dev.otto.phone.STOP"
        /** The notification's Stop: asks for the running turn to be cancelled. [ACTION_STOP] only stops this service. */
        const val ACTION_CANCEL = "dev.otto.phone.CANCEL"
        const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            context.startForegroundService(Intent(context, OttoForegroundService::class.java).putExtra(EXTRA_TEXT, text))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OttoForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
