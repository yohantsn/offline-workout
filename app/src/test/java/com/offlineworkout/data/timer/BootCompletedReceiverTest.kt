package com.offlineworkout.data.timer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

// T5.1: boot receiver reconciles without starting services or looping:
// expired timers conclude, live ones get a status notification, empty
// stores and foreign actions are quiet no-ops.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootCompletedReceiverTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun seedRunning(endsAtWall: Long) {
        SharedPrefsRestTimerStore(context(), object : TimerClock {
            override fun wallClockMs(): Long = 1_000_000L
            override fun elapsedRealtimeMs(): Long = 1_000_000L
        }).start(
            PersistedRestTimer(
                timerId = "rest-1-2-1000000", workoutId = 1L, exerciseId = 2L,
                durationSeconds = 60, startedAtWallClockMs = 1_000_000L, endsAtWallClockMs = endsAtWall,
                status = PersistedTimerStatus.RUNNING, finishedAtWallClockMs = null, alertDelivered = false
            )
        )
    }

    private fun bootIntent() = Intent(Intent.ACTION_BOOT_COMPLETED)

    @Before
    @After
    fun clean() {
        SharedPrefsRestTimerStore(context(), SystemTimerClock).cancel()
    }

    @Test
    fun ignores_foreign_actions() {
        BootCompletedReceiver().onReceive(context(), Intent(Intent.ACTION_BATTERY_LOW))
        assertNull(
            shadowOf(context() as android.app.Application).nextStartedService
        )
    }

    @Test
    fun empty_store_is_quiet() {
        BootCompletedReceiver().onReceive(context(), bootIntent())
        assertNull(
            shadowOf(context() as android.app.Application).nextStartedService
        )
        assertTrue(
            context().getSystemService(NotificationManager::class.java)
                .activeNotifications.none { it.id == RestTimerNotifications.NOTIFICATION_ID }
        )
    }

    @Test
    fun live_timer_gets_status_notification_without_service() {
        // endsAt far in the future relative to the real test clock.
        seedRunning(endsAtWall = System.currentTimeMillis() + 600_000L)
        BootCompletedReceiver().onReceive(context(), bootIntent())

        assertNull(
            shadowOf(context() as android.app.Application).nextStartedService
        )
        val posted = context().getSystemService(NotificationManager::class.java)
            .activeNotifications.firstOrNull { it.id == RestTimerNotifications.NOTIFICATION_ID }
        assertTrue(posted != null)
        // Store still holds the running timer for the next app start.
        val persisted = SharedPrefsRestTimerStore(context(), SystemTimerClock).loadPersisted()
        assertEquals(PersistedTimerStatus.RUNNING, persisted?.status)
    }

    @Test
    fun expired_timer_concludes_without_service() {
        seedRunning(endsAtWall = 1L)
        BootCompletedReceiver().onReceive(context(), bootIntent())

        assertNull(
            shadowOf(context() as android.app.Application).nextStartedService
        )
        val store = SharedPrefsRestTimerStore(context(), SystemTimerClock)
        assertEquals(PersistedTimerStatus.FINISHED, store.loadPersisted()?.status)
        assertEquals(true, store.loadPersisted()?.alertDelivered)
    }
}
