package com.offlineworkout.data.timer

import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

// T5.1: one-shot finish alert (default notification sound + single short
// vibration). No custom file, no voice, no repeat, no loop. Never bypasses
// DND/silent: failures are swallowed by design. Replaceable without touching
// the Service or the timer.
interface RestAlertPlayer {
    fun alert()
}

class SystemRestAlertPlayer(context: Context) : RestAlertPlayer {
    private val appContext = context.applicationContext

    override fun alert() {
        playDefaultSound()
        vibrateOnce()
    }

    private fun playDefaultSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
            RingtoneManager.getRingtone(appContext, uri)?.play()
        } catch (_: Exception) {
            // Silent/DND or missing sound: the timer state still completes.
        }
    }

    private fun vibrateOnce() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Vibrator::class.java)
            } ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
            // Vibrator unavailable or blocked: the timer state still completes.
        }
    }

    companion object {
        const val VIBRATION_MS = 400L
    }
}
