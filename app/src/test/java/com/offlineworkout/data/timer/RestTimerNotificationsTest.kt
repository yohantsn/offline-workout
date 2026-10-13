package com.offlineworkout.data.timer

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// T5.1: rest_timer channel is created once with high importance, default
// sound and vibration.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestTimerNotificationsTest {

    @Test
    fun channel_uses_high_importance_sound_and_vibration() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        RestTimerNotifications.ensureChannel(context)
        // Second call is a no-op and must not throw.
        RestTimerNotifications.ensureChannel(context)

        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(RestTimerNotifications.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel!!.importance)
        assertNotNull(channel.sound)
        assertTrue(channel.shouldVibrate())
    }
}
