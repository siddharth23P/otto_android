package dev.otto.phone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/** Alive while a turn runs, so Doze and the OOM killer leave the agent alone. */
class OttoForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Otto is working", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Otto is working on your request")
            .setContentText(intent?.getStringExtra(EXTRA_TEXT) ?: "")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
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
        const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            context.startForegroundService(Intent(context, OttoForegroundService::class.java).putExtra(EXTRA_TEXT, text))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OttoForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
