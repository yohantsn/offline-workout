package com.offlineworkout.data.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.offlineworkout.MainActivity

// T5.1: single rest_timer channel (IMPORTANCE_HIGH, default sound, vibration)
// plus notification builders. Channel settings apply only on first creation;
// the system keeps user changes afterwards.
object RestTimerNotifications {

    const val CHANNEL_ID = "rest_timer"
    const val NOTIFICATION_ID = 1001
    const val REQUEST_CODE_CANCEL = 2001
    const val REQUEST_CODE_OPEN = 2002

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Temporizador de descanso",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerta de término do descanso entre exercícios"
            enableVibration(true)
            setSound(soundUri, audioAttributes)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun runningNotification(context: Context, snapshot: RestTimerSnapshot): Notification {
        val cancel = PendingIntent.getService(
            context,
            REQUEST_CODE_CANCEL,
            RestTimerService.cancelIntent(context, (snapshot.status as? RestTimerStatus.Running)?.timerId ?: ""),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Descanso em andamento")
            .setContentText("Restam ${snapshot.remainingSeconds}s")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancel)
            .build()
    }

    // Plain status notification for boot restore (no foreground service).
    fun statusNotification(context: Context, snapshot: RestTimerSnapshot): Notification {
        val open = PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Descanso em andamento")
            .setContentText("Restam ${snapshot.remainingSeconds}s")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
}
