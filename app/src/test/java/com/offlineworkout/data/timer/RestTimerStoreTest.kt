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

// T5.1: SharedPreferences store round-trips deadlines (never remaining
// counters) across instances, restores expiry and clears on cancel.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestTimerStoreTest {

    private class FakeClock(var wall: Long) : TimerClock {
        override fun wallClockMs(): Long = wall
        override fun elapsedRealtimeMs(): Long = wall
    }

    private val clock = FakeClock(1_000_000L)

    private fun store() = SharedPrefsRestTimerStore(
        ApplicationProvider.getApplicationContext(), clock
    )

    private fun runningRecord() = PersistedRestTimer(
        timerId = "rest-1-2-1000000",
        workoutId = 1L,
        exerciseId = 2L,
        durationSeconds = 60,
        startedAtWallClockMs = 1_000_000L,
        endsAtWallClockMs = 1_060_000L,
        status = PersistedTimerStatus.RUNNING,
        finishedAtWallClockMs = null,
        alertDelivered = false
    )

    @Before
    @After
    fun clean() {
        store().cancel()
    }

    @Test
    fun start_persists_deadline_and_emits_running() {
        val store = store()
        store.start(runningRecord())
        val snapshot = store.state.value
        assertTrue(snapshot?.status is RestTimerStatus.Running)
        assertEquals(60, snapshot?.remainingSeconds)

        // A new instance over the same prefs sees the same timer.
        val reopened = store()
        assertEquals("rest-1-2-1000000", (reopened.state.value?.status as? RestTimerStatus.Running)?.timerId)
        assertEquals(1L, reopened.state.value?.workoutId)
        assertEquals(2L, reopened.state.value?.exerciseId)
    }

    @Test
    fun remaining_is_derived_never_persisted() {
        val store = store()
        store.start(runningRecord())
        store.updateRemaining(10)
        assertEquals(10, store.state.value?.remainingSeconds)

        val prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences(SharedPrefsRestTimerStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.all.keys.containsAll(setOf("timer_id", "ends_wall", "status")))
        assertTrue(prefs.all.keys.none { it.contains("remain", ignoreCase = true) })

        // Disk still holds the deadline: a fresh instance recomputes, not replays 10.
        clock.wall = 1_030_000L
        assertEquals(30, store().state.value?.remainingSeconds)
    }

    @Test
    fun restore_expired_concludes_without_alert_flag() {
        val store = store()
        store.start(runningRecord())
        clock.wall = 1_060_001L
        val snapshot = store().restore()
        assertTrue(snapshot?.status is RestTimerStatus.Finished)
        assertEquals(false, snapshot?.alertDelivered)
        val persisted = store.loadPersisted()
        assertEquals(PersistedTimerStatus.FINISHED, persisted?.status)
        assertEquals(1_060_001L, persisted?.finishedAtWallClockMs)
    }

    @Test
    fun finish_and_deliver_persist_flags() {
        val store = store()
        store.start(runningRecord())
        store.finish(1_061_000L)
        assertTrue(store.state.value?.status is RestTimerStatus.Finished)
        store.markAlertDelivered()
        assertEquals(true, store.state.value?.alertDelivered)

        val reopened = store()
        assertTrue(reopened.state.value?.status is RestTimerStatus.Finished)
        assertEquals(true, reopened.state.value?.alertDelivered)
    }

    @Test
    fun cancel_clears_disk_and_state() {
        val store = store()
        store.start(runningRecord())
        store.cancel()
        assertNull(store.state.value)
        assertNull(store.loadPersisted())
        assertNull(store().state.value)
    }

    @Test
    fun restore_empty_returns_null() {
        assertNull(store().restore())
        assertNull(store().state.value)
    }
}
