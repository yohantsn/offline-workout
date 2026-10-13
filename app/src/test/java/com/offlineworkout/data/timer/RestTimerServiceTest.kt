package com.offlineworkout.data.timer

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// T5.1: real Service under Robolectric — foreground notification on start,
// removal + store clear on cancel. Countdown/finish logic lives in engine
// unit tests; here only the Android shell is exercised (no real sleeps).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestTimerServiceTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun notifications() = context().getSystemService(NotificationManager::class.java)
        .activeNotifications.toList()

    private fun clearStore() {
        SharedPrefsRestTimerStore(context(), SystemTimerClock).cancel()
    }

    @Before
    @After
    fun clean() {
        clearStore()
    }

    @Test
    fun start_posts_foreground_countdown() {
        val controller = Robolectric.buildService(RestTimerService::class.java)
            .create()
            .get()
        // Drive onStartCommand directly: Robolectric does not deliver intents.
        controller.onStartCommand(
            RestTimerService.startIntent(context(), RestRequest(3L, 4L, 60)),
            0,
            1
        )
        val posted = notifications().firstOrNull { it.id == RestTimerNotifications.NOTIFICATION_ID }
        assertNotNull(posted)
        val text = posted!!.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        assertEquals("Restam 60s", text)
        controller.onDestroy()
    }

    @Test
    fun invalid_start_never_foregrounds() {
        val controller = Robolectric.buildService(RestTimerService::class.java)
            .create()
            .get()
        controller.onStartCommand(
            RestTimerService.startIntent(context(), RestRequest(3L, 4L, 0)),
            0,
            1
        )
        assertTrue(notifications().none { it.id == RestTimerNotifications.NOTIFICATION_ID })
        controller.onDestroy()
    }

    /**
     * Robolectric's ServiceController is the only way to observe stopSelf()
     * from a unit test: the controller is marked stopped once stopSelf runs.
     */
    @Test
    fun invalid_extras_stop_the_service_instead_of_lingering() {
        // T9.2 (R-4): a start intent with unusable extras used to leave the
        // service alive without startForeground, which the system answers with
        // ForegroundServiceDidNotStartInTimeException.
        val service = Robolectric.buildService(RestTimerService::class.java).create().get()
        val broken = android.content.Intent(RestTimerService.ACTION_START)
            .putExtra(RestTimerService.EXTRA_WORKOUT_ID, 3L)
        service.onStartCommand(broken, 0, 1)
        assertTrue(
            "service must stop itself after an unparseable start",
            shadowOf(service).isStoppedBySelf
        )
        assertTrue("foreground state must be released", shadowOf(service).isForegroundStopped)
        assertTrue(notifications().none { it.id == RestTimerNotifications.NOTIFICATION_ID })
        service.onDestroy()
    }

    @Test
    fun pre_existing_idle_snapshot_stops_the_service() {
        // T9.2 (R-4): after engine.restore() the snapshot is almost never null,
        // so "state == null" never fired. Any non-Running status must stop.
        // A finished timer restores as a non-null, non-Running snapshot: this
        // is the state that used to keep the service alive forever.
        val store = SharedPrefsRestTimerStore(context(), SystemTimerClock)
        store.start(
            PersistedRestTimer(
                timerId = "rest-3-4-1000",
                workoutId = 3L,
                exerciseId = 4L,
                durationSeconds = 60,
                startedAtWallClockMs = 1L,
                endsAtWallClockMs = 1L,
                status = PersistedTimerStatus.FINISHED,
                finishedAtWallClockMs = 1L,
                alertDelivered = true
            )
        )
        val restored = store.restore()
        assertNotNull("precondition: a snapshot exists", restored)
        assertTrue(
            "precondition: the snapshot is Finished, not Running",
            restored!!.status is RestTimerStatus.Finished
        )
        val service = Robolectric.buildService(RestTimerService::class.java).create().get()
        service.onStartCommand(android.content.Intent("ação desconhecida"), 0, 1)
        assertTrue(
            "an Idle snapshot must not keep the service alive",
            shadowOf(service).isStoppedBySelf
        )
        service.onDestroy()
    }

    @Test
    fun running_timer_survives_a_foreign_command() {
        // The guard must not stop a timer that really is running.
        val service = Robolectric.buildService(RestTimerService::class.java).create().get()
        service.onStartCommand(
            RestTimerService.startIntent(context(), RestRequest(3L, 4L, 60)),
            0,
            1
        )
        assertNotNull(notifications().firstOrNull { it.id == RestTimerNotifications.NOTIFICATION_ID })
        service.onStartCommand(android.content.Intent("ação desconhecida"), 0, 2)
        assertFalse(
            "a running timer must not be stopped by an unrelated command",
            shadowOf(service).isStoppedBySelf
        )
        service.onDestroy()
    }

    @Test
    fun cancel_removes_notification_and_clears_store() {
        val controller = Robolectric.buildService(RestTimerService::class.java)
            .create()
            .get()
        controller.onStartCommand(
            RestTimerService.startIntent(context(), RestRequest(3L, 4L, 60)),
            0,
            1
        )
        assertNotNull(notifications().firstOrNull { it.id == RestTimerNotifications.NOTIFICATION_ID })

        val store = SharedPrefsRestTimerStore(context(), SystemTimerClock)
        val timerId = (store.state.value?.status as RestTimerStatus.Running).timerId
        controller.onStartCommand(RestTimerService.cancelIntent(context(), timerId), 0, 2)

        assertNull(notifications().firstOrNull { it.id == RestTimerNotifications.NOTIFICATION_ID })
        // Disk truth is cleared; a fresh instance observes Idle (in-memory
        // copies held by other instances are stale by design).
        assertNull(store.loadPersisted())
        assertNull(SharedPrefsRestTimerStore(context(), SystemTimerClock).state.value)
        controller.onDestroy()
    }
}
