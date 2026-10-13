package com.offlineworkout.data.timer

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

// T5.1: controller validates, reports Busy and fires start/cancel intents
// without touching Room. The Service owns all store mutations.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidRestTimerControllerTest {

    private class FakeClock(var wall: Long) : TimerClock {
        override fun wallClockMs(): Long = wall
        override fun elapsedRealtimeMs(): Long = wall
    }

    private lateinit var store: SharedPrefsRestTimerStore
    private lateinit var controller: AndroidRestTimerController

    @Before
    fun setUp() {
        store = SharedPrefsRestTimerStore(ApplicationProvider.getApplicationContext(), FakeClock(1_000_000L))
        store.cancel()
        controller = AndroidRestTimerController(ApplicationProvider.getApplicationContext(), store)
    }

    @After
    fun tearDown() {
        store.cancel()
    }

    private fun nextServiceAction(): String? {
        val intent = shadowOf(ApplicationProvider.getApplicationContext() as android.app.Application).nextStartedService
        return intent?.action
    }

    @Test
    fun start_rejects_invalid_without_intent() {
        assertTrue(controller.start(RestRequest(1L, 2L, 0)) is RestTimerCommandResult.Invalid)
        assertTrue(controller.start(RestRequest(0L, 2L, 30)) is RestTimerCommandResult.Invalid)
        assertTrue(controller.start(RestRequest(1L, 0L, 30)) is RestTimerCommandResult.Invalid)
        assertNull(nextServiceAction())
        assertNull(store.state.value)
    }

    @Test
    fun start_fires_service_intent_and_reports_started() {
        val result = controller.start(RestRequest(1L, 2L, 60))
        assertTrue(result is RestTimerCommandResult.Started)
        val shadowApp = shadowOf(ApplicationProvider.getApplicationContext() as android.app.Application)
        val intent = shadowApp.nextStartedService
        assertEquals(RestTimerService.ACTION_START, intent?.action)
        assertEquals(1L, intent?.getLongExtra(RestTimerService.EXTRA_WORKOUT_ID, -1L))
        assertEquals(2L, intent?.getLongExtra(RestTimerService.EXTRA_EXERCISE_ID, -1L))
        assertEquals(60, intent?.getIntExtra(RestTimerService.EXTRA_REST_SECONDS, -1))
    }

    @Test
    fun second_start_while_running_is_busy_without_intent() {
        store.start(
            PersistedRestTimer(
                timerId = "rest-1-2-1000000", workoutId = 1L, exerciseId = 2L,
                durationSeconds = 60, startedAtWallClockMs = 1_000_000L, endsAtWallClockMs = 1_060_000L,
                status = PersistedTimerStatus.RUNNING, finishedAtWallClockMs = null, alertDelivered = false
            )
        )
        assertTrue(controller.start(RestRequest(1L, 9L, 30)) is RestTimerCommandResult.Busy)
        assertNull(nextServiceAction())
    }

    @Test
    fun cancel_idle_needs_no_service() {
        assertTrue(controller.cancel("anything") is RestTimerCommandResult.Cancelled)
        assertNull(nextServiceAction())
    }

    @Test
    fun cancel_running_asks_service_to_stop() {
        store.start(
            PersistedRestTimer(
                timerId = "rest-1-2-1000000", workoutId = 1L, exerciseId = 2L,
                durationSeconds = 60, startedAtWallClockMs = 1_000_000L, endsAtWallClockMs = 1_060_000L,
                status = PersistedTimerStatus.RUNNING, finishedAtWallClockMs = null, alertDelivered = false
            )
        )
        assertTrue(controller.cancel("rest-1-2-1000000") is RestTimerCommandResult.Cancelled)
        val shadowApp = shadowOf(ApplicationProvider.getApplicationContext() as android.app.Application)
        val intent = shadowApp.nextStartedService
        assertEquals(RestTimerService.ACTION_CANCEL, intent?.action)
        assertEquals("rest-1-2-1000000", intent?.getStringExtra(RestTimerService.EXTRA_TIMER_ID))
    }

    @Test
    fun state_is_the_store_stateflow() {
        assertTrue(controller.state === store.state)
    }
}
