package io.github.danielperezmartinez.titanssh.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.danielperezmartinez.titanssh.ui.KeepAliveServiceContract

/**
 * Foreground service that keeps the app's network while a tab would lose
 * something without its connection (ADR-0023): Android cuts the network of an
 * app in the background or behind a locked screen, but not of one with a
 * foreground service. It does no work of its own; the shared code starts it,
 * updates its count and stops it ([KeepAliveServiceContract]).
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val connected = intent?.getIntExtra(KeepAliveServiceContract.EXTRA_CONNECTED_TABS, 0) ?: 0
        val notification = notification(connected)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // The tabs live in the UI's process: if it dies, there is nothing to keep.
        return START_NOT_STICKY
    }

    private fun notification(connected: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sesiones abiertas", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantiene conectadas las sesiones que se perderían con la pantalla bloqueada"
                setShowBadge(false)
            },
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(
                when (connected) {
                    0 -> "Manteniendo las sesiones"
                    1 -> "1 sesión conectada"
                    else -> "$connected sesiones conectadas"
                },
            )
            .setContentText("Siguen conectadas con la pantalla bloqueada")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "sessions"
        const val NOTIFICATION_ID = 1
    }
}
