package com.offlineworkout.data.timer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// T5.1: engine logic with fake clock/store/player. No Android, no sleeps,
// fully deterministic. Covers validation, busy, countdown, finish/alert-once,
// cancel idempotency, restore paths and remaining computation.
class RestTimerEngineTest {

    private class FakeClock(var wall: Long = 1_000_000L, var elapsed: Long = 500_000L) : TimerClock {
        override fun wallClockMs(): Long = wall
        override fun elapsedRealtimeMs(): Long = elapsed
    }

    private class FakeStore(private val clock: TimerClock) : RestTimerStore {
        private val mutable = MutableStateFlow<RestTimerSnapshot?>(null)
        override val state: StateFlow<RestTimerSnapshot?> = mutable.asStateFlow()
        var persisted: PersistedRestTimer? = null
        var cleared = 0

        override fun start(record: PersistedRestTimer) {
            persisted = record
            mutable.value = RestTimerSnapshot(
                RestTimerStatus.Running(record.timerId), record.workoutId, record.exerciseId,
                record.durationSeconds, record.durationSeconds,
                record.startedAtWallClockMs, record.endsAtWallClockMs, false
            )
        }

        override fun updateRemaining(remainingSeconds: Int) {
            val current = mutable.value
            if (current?.status is RestTimerStatus.Running) {
                mutable.value = current.copy(remainingSeconds = remainingSeconds.coerceAtLeast(0))
            }
        }

        override fun finish(finishedAtWallClockMs: Long) {
            val record = persisted ?: return
            persisted = record.copy(status = PersistedTimerStatus.FINISHED, finishedAtWallClockMs = finishedAtWallClockMs)
            val current = mutable.value
            mutable.value = RestTimerSnapshot(
                RestTimerStatus.Finished(record.timerId), record.workoutId, record.exerciseId,
                record.durationSeconds, 0,
                record.startedAtWallClockMs, record.endsAtWallClockMs, record.alertDelivered
            )
            check(current != null)
        }

        override fun markAlertDelivered() {
            persisted = persisted?.copy(alertDelivered = true)
            mutable.value?.let { mutable.value = it.copy(alertDelivered = true) }
        }

        override fun cancel() {
            cleared += 1
            persisted = null
            mutable.value = null
        }

        override fun loadPersisted(): PersistedRestTimer? = persisted

        override fun restore(): RestTimerSnapshot? {
            val record = persisted ?: run {
                mutable.value = null
                return null
            }
            return when (record.status) {
                PersistedTimerStatus.FINISHED -> {
                    mutable.value = RestTimerSnapshot(
                        RestTimerStatus.Finished(record.timerId), record.workoutId, record.exerciseId,
                        record.durationSeconds, 0,
                        record.startedAtWallClockMs, record.endsAtWallClockMs, record.alertDelivered
                    )
                    mutable.value
                }
                PersistedTimerStatus.RUNNING -> {
                    if (clock.wallClockMs() >= record.endsAtWallClockMs) {
                        finish(clock.wallClockMs())
                        mutable.value
                    } else {
                        mutable.value = RestTimerSnapshot(
                            RestTimerStatus.Running(record.timerId), record.workoutId, record.exerciseId,
                            record.durationSeconds,
                            remainingSeconds(record.endsAtWallClockMs, clock.wallClockMs()),
                            record.startedAtWallClockMs, record.endsAtWallClockMs, false
                        )
                        mutable.value
                    }
                }
            }
        }
    }

    private class FakePlayer : RestAlertPlayer {
        var alerts = 0
        override fun alert() {
            alerts += 1
        }
    }

    private class Fixture {
        val clock = FakeClock()
        val store = FakeStore(clock)
        val player = FakePlayer()
        var stops = 0
        val engine = RestTimerEngine(store, clock, player, object : RestTimerEngine.Listener {
            override fun onRequestStop() {
                stops += 1
            }
        })
    }

    private fun running(f: Fixture, restSeconds: Int = 60): String {
        val result = f.engine.start(RestRequest(workoutId = 1L, exerciseId = 2L, restSeconds = restSeconds))
        assertTrue(result is RestTimerCommandResult.Started)
        return (f.store.state.value?.status as RestTimerStatus.Running).timerId
    }

    @Test
    fun remaining_rounds_up_and_never_negative() {
        assertEquals(1, remainingSeconds(endsAtWallClockMs = 1001L, nowWallClockMs = 1000L))
        assertEquals(1, remainingSeconds(endsAtWallClockMs = 2000L, nowWallClockMs = 1000L))
        assertEquals(2, remainingSeconds(endsAtWallClockMs = 2001L, nowWallClockMs = 1000L))
        assertEquals(0, remainingSeconds(endsAtWallClockMs = 1000L, nowWallClockMs = 1000L))
        assertEquals(0, remainingSeconds(endsAtWallClockMs = 999L, nowWallClockMs = 1000L))
        assertEquals(0, remainingSeconds(endsAtWallClockMs = 0L, nowWallClockMs = 10_000L))
    }

    @Test
    fun start_rejects_invalid_requests() {
        val f = Fixture()
        assertTrue(f.engine.start(RestRequest(1L, 2L, 0)) is RestTimerCommandResult.Invalid)
        assertTrue(f.engine.start(RestRequest(1L, 2L, -5)) is RestTimerCommandResult.Invalid)
        assertTrue(f.engine.start(RestRequest(0L, 2L, 30)) is RestTimerCommandResult.Invalid)
        assertTrue(f.engine.start(RestRequest(1L, 0L, 30)) is RestTimerCommandResult.Invalid)
        assertNull(f.store.state.value)
        assertNull(f.store.persisted)
        assertEquals(0, f.stops)
    }

    @Test
    fun second_start_returns_busy_without_replacing() {
        val f = Fixture()
        val firstId = running(f)
        val again = f.engine.start(RestRequest(1L, 9L, 30))
        assertTrue(again is RestTimerCommandResult.Busy)
        assertEquals(firstId, (f.store.state.value?.status as RestTimerStatus.Running).timerId)
    }

    @Test
    fun tick_counts_down_monotonically_and_finishes_once() {
        val f = Fixture()
        running(f, restSeconds = 60)
        f.clock.elapsed += 30_000L
        f.engine.tick()
        assertEquals(30, f.store.state.value?.remainingSeconds)
        f.clock.elapsed += 29_999L
        f.engine.tick()
        assertEquals(1, f.store.state.value?.remainingSeconds)
        f.clock.elapsed += 1L
        f.engine.tick()
        val finished = f.store.state.value
        assertTrue(finished?.status is RestTimerStatus.Finished)
        assertEquals(0, finished?.remainingSeconds)
        assertEquals(1, f.player.alerts)
        assertEquals(1, f.stops)
        assertTrue(f.store.persisted?.alertDelivered == true)
        // Extra ticks never re-alert.
        f.clock.elapsed += 60_000L
        f.engine.tick()
        f.engine.complete()
        assertEquals(1, f.player.alerts)
        assertEquals(1, f.stops)
    }

    @Test
    fun cancel_is_idempotent_and_never_alerts() {
        val f = Fixture()
        // Cancel with nothing running.
        f.engine.cancel("nope")
        assertEquals(0, f.stops)
        val timerId = running(f)
        // Wrong id is ignored.
        f.engine.cancel("other-id")
        assertTrue(f.store.state.value?.status is RestTimerStatus.Running)
        assertEquals(0, f.player.alerts)
        // Right id clears and stops.
        f.engine.cancel(timerId)
        assertNull(f.store.state.value)
        assertNull(f.store.persisted)
        assertEquals(1, f.stops)
        assertEquals(0, f.player.alerts)
        // Repeat is a quiet no-op.
        f.engine.cancel(timerId)
        assertEquals(1, f.stops)
    }

    @Test
    fun cancel_after_finish_clears_without_alert() {
        val f = Fixture()
        running(f, restSeconds = 5)
        f.clock.elapsed += 5_000L
        f.engine.tick()
        assertTrue(f.store.state.value?.status is RestTimerStatus.Finished)
        val finishedId = (f.store.state.value?.status as RestTimerStatus.Finished).timerId
        f.engine.cancel(finishedId)
        assertNull(f.store.state.value)
        assertEquals(1, f.player.alerts)
    }

    @Test
    fun restore_live_timer_resumes_with_rebuilt_deadline() {
        val f = Fixture()
        val timerId = running(f, restSeconds = 60)
        // Simulate process death: new engine over the same store, clocks advanced.
        f.clock.wall += 10_000L
        f.clock.elapsed = 50_000L // monotonic clock restarted lower
        val resumed = RestTimerEngine(f.store, f.clock, f.player, object : RestTimerEngine.Listener {
            override fun onRequestStop() = Unit
        })
        val snapshot = resumed.restore()
        assertEquals(timerId, (snapshot?.status as? RestTimerStatus.Running)?.timerId)
        assertEquals(50, snapshot?.remainingSeconds)
        resumed.tick()
        assertEquals(50, f.store.state.value?.remainingSeconds)
        f.clock.elapsed += 50_000L
        resumed.tick()
        assertTrue(f.store.state.value?.status is RestTimerStatus.Finished)
        assertEquals(1, f.player.alerts)
    }

    @Test
    fun restore_expired_timer_concludes_immediately() {
        val f = Fixture()
        running(f, restSeconds = 60)
        f.clock.wall += 61_000L
        val resumed = RestTimerEngine(f.store, f.clock, f.player, object : RestTimerEngine.Listener {
            override fun onRequestStop() = Unit
        })
        val snapshot = resumed.restore()
        assertTrue(snapshot?.status is RestTimerStatus.Finished)
        assertEquals(1, f.player.alerts)
        assertTrue(f.store.persisted?.alertDelivered == true)
    }

    @Test
    fun restore_delivered_finished_stays_silent() {
        val f = Fixture()
        running(f, restSeconds = 5)
        f.clock.elapsed += 5_000L
        f.engine.tick()
        assertEquals(1, f.player.alerts)
        val resumed = RestTimerEngine(f.store, f.clock, f.player, object : RestTimerEngine.Listener {
            override fun onRequestStop() = Unit
        })
        resumed.restore()
        assertEquals(1, f.player.alerts)
    }

    @Test
    fun restore_nothing_returns_null() {
        val f = Fixture()
        assertNull(f.engine.restore())
        assertEquals(0, f.player.alerts)
    }
}
