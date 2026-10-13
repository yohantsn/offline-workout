package com.offlineworkout.data.timer

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// T5.1: boot restore. Reconciles the persisted timer exactly once per boot:
// expired timers conclude with a final alert, live timers get a plain status
// notification. Never starts a foreground service (blocked from background),
// never loops, never throws out of onReceive.
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val appContext = context.applicationContext
            val clock = SystemTimerClock
            val store = SharedPrefsRestTimerStore(appContext, clock)
            val engine = RestTimerEngine(
                store = store,
                clock = clock,
                player = SystemRestAlertPlayer(appContext),
                listener = object : RestTimerEngine.Listener {
                    override fun onRequestStop() = Unit
                }
            )
            val snapshot = engine.restore() ?: return
            if (snapshot.status !is RestTimerStatus.Running) return
            RestTimerNotifications.ensureChannel(appContext)
            val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(
                RestTimerNotifications.NOTIFICATION_ID,
                RestTimerNotifications.statusNotification(appContext, snapshot)
            )
        } catch (_: Exception) {
            // Boot path must never crash the receiver nor schedule retries.
        }
    }
}
