package com.offlineworkout.data.session

import com.offlineworkout.data.timer.TimerClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// T6.1: session controller with fake store/clock and the test scheduler —
// deterministic, no real sleeps. Covers start/restore, pause/resume/stop,
// other-workout guard, ticking and display formatting.
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutSessionControllerTest {

    private class FakeClock(var wall: Long = 1_000_000L) : TimerClock {
        override fun wallClockMs(): Long = wall
        override fun elapsedRealtimeMs(): Long = wall
    }

    private class FakeStore(var record: PersistedWorkoutSession? = null) : WorkoutSessionStore {
        var saves = 0
        override fun load(): PersistedWorkoutSession? = record
        override fun save(record: PersistedWorkoutSession) {
            saves += 1
            this.record = record
        }
    }

    private fun TestScope.controller(
        store: FakeStore = FakeStore(),
        clock: FakeClock = FakeClock()
    ): DefaultWorkoutSessionController {
        // Detached scope on the shared scheduler: the infinite tick loop must
        // not be a child of runTest, or the test would wait for it forever.
        // advanceTimeBy/runCurrent still drive it through the shared scheduler.
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        return DefaultWorkoutSessionController(store, clock, scope, tickMs = 1000L)
    }

    // Virtual time drives ticks; the fake wall clock must advance together,
    // otherwise transitions and ticks disagree (as on a real device).
    // NOTE: never advance by an exact multiple of the 1000ms tick period
    // (1000/2000/5000): advanceTimeBy livelocks on exact-period alignment
    // with a periodic delay loop in kotlinx-coroutines-test 1.9.0.
    private fun TestScope.advanceSession(ms: Long, clock: FakeClock) {
        clock.wall += ms
        advanceTimeBy(ms)
    }

    @Test
    fun starts_idle_and_begins_on_start() = runTest {
        val store = FakeStore()
        val controller = controller(store)
        assertTrue(controller.state.value.status is WorkoutSessionStatus.Idle)
        assertNull(controller.state.value.workoutId)

        controller.startOrResume(7L)
        val running = controller.state.value
        assertTrue(running.status is WorkoutSessionStatus.Running)
        assertEquals(7L, running.workoutId)
        assertEquals(0L, running.elapsedSeconds)
        assertEquals(1, store.saves)
        controller.close()
    }

    @Test
    fun ticks_each_second_without_persisting() = runTest {
        val clock = FakeClock()
        val store = FakeStore()
        val controller = controller(store, clock)
        controller.startOrResume(7L)
        val savesAfterStart = store.saves
        advanceSession(3500L, clock)
        assertEquals(3L, controller.state.value.elapsedSeconds)
        assertEquals(savesAfterStart, store.saves)
        controller.close()
    }

    @Test
    fun idle_does_not_tick() = runTest {
        // T9.2 (R-3): the ticker used to wake every second for the whole
        // process life. With no session the elapsed value must not move, and
        // starting a session must bring the ticks back.
        val clock = FakeClock()
        val store = FakeStore()
        val controller = controller(store, clock)

        advanceSession(10_000L, clock)
        assertEquals(
            "an idle session must not accumulate elapsed time",
            0L,
            controller.state.value.elapsedSeconds
        )

        controller.startOrResume(7L)
        advanceSession(3500L, clock)
        assertEquals("ticking resumes once the session runs", 3L, controller.state.value.elapsedSeconds)

        // Stopping leaves Running, so the loop exits again.
        controller.stop()
        val afterStop = controller.state.value.elapsedSeconds
        advanceSession(5000L, clock)
        assertEquals("a stopped session must not tick", afterStop, controller.state.value.elapsedSeconds)
        controller.close()
    }

    @Test
    fun pause_resume_stop_cycle() = runTest {
        val clock = FakeClock()
        val store = FakeStore()
        val controller = controller(store, clock)
        controller.startOrResume(7L)
        advanceSession(2500L, clock)
        controller.pause()
        val paused = controller.state.value
        assertTrue(paused.status is WorkoutSessionStatus.Paused)
        assertEquals(2L, paused.elapsedSeconds)
        advanceSession(5100L, clock)
        assertEquals(2L, controller.state.value.elapsedSeconds)

        controller.resume()
        assertTrue(controller.state.value.status is WorkoutSessionStatus.Running)
        advanceSession(1500L, clock)
        assertEquals(4L, controller.state.value.elapsedSeconds)

        controller.stop()
        val finished = controller.state.value
        assertTrue(finished.status is WorkoutSessionStatus.Finished)
        assertEquals(4L, finished.elapsedSeconds)
        assertEquals(PersistedSessionStatus.FINISHED, store.record?.status)
        controller.close()
    }

    @Test
    fun restores_same_workout_without_overwriting() = runTest {
        val clock = FakeClock()
        val store = FakeStore(
            PersistedWorkoutSession(
                workoutId = 7L,
                status = PersistedSessionStatus.RUNNING,
                accumulatedMs = 30_000L,
                lastResumedAtWallClockMs = 1_000_000L,
                startedAtWallClockMs = 970_000L
            )
        )
        val controller = controller(store, clock)
        // Restored immediately from wall clock, before any tick.
        assertEquals(30L, controller.state.value.elapsedSeconds)
        clock.wall += 2000L
        controller.startOrResume(7L)
        assertEquals(32L, controller.state.value.elapsedSeconds)
        runCurrent()
        advanceSession(1500L, clock)
        assertEquals(33L, controller.state.value.elapsedSeconds)
        assertEquals(7L, controller.state.value.workoutId)
        controller.close()
    }

    @Test
    fun other_workout_is_never_overwritten() = runTest {
        val store = FakeStore(
            PersistedWorkoutSession(
                workoutId = 9L,
                status = PersistedSessionStatus.RUNNING,
                accumulatedMs = 10_000L,
                lastResumedAtWallClockMs = 1_000_000L,
                startedAtWallClockMs = 990_000L
            )
        )
        val savesBefore = store.saves
        val controller = controller(store)
        controller.startOrResume(7L)
        assertEquals(9L, controller.state.value.workoutId)
        assertTrue(controller.state.value.status is WorkoutSessionStatus.Running)
        assertEquals(savesBefore, store.saves)
        controller.close()
    }

    @Test
    fun finished_session_starts_fresh() = runTest {
        val store = FakeStore(
            PersistedWorkoutSession(
                workoutId = 9L,
                status = PersistedSessionStatus.FINISHED,
                accumulatedMs = 60_000L,
                lastResumedAtWallClockMs = null,
                startedAtWallClockMs = 940_000L
            )
        )
        val controller = controller(store)
        controller.startOrResume(7L)
        assertEquals(7L, controller.state.value.workoutId)
        assertEquals(0L, controller.state.value.elapsedSeconds)
        assertTrue(controller.state.value.status is WorkoutSessionStatus.Running)
        controller.close()
    }

    @Test
    fun pause_resume_without_session_are_noops() = runTest {
        val store = FakeStore()
        val controller = controller(store)
        controller.pause()
        controller.resume()
        controller.stop()
        assertTrue(controller.state.value.status is WorkoutSessionStatus.Finished)
        assertEquals(0, store.saves)
        controller.close()
    }

    @Test
    fun format_helpers() {
        assertEquals("00:00", formatSessionElapsed(0L))
        assertEquals("00:07", formatSessionElapsed(7L))
        assertEquals("01:05", formatSessionElapsed(65L))
        assertEquals("10:00", formatSessionElapsed(600L))
        assertEquals("1:00:00", formatSessionElapsed(3600L))
        assertEquals("1:02:03", formatSessionElapsed(3723L))
        assertEquals("00:00", formatSessionElapsed(-5L))
    }

    @Test
    fun close_releases_owned_scope_only() = runTest {
        val external = StandardTestDispatcher(testScheduler)
        // External scope: close() must not cancel the caller's scope.
        val controller = DefaultWorkoutSessionController(
            FakeStore(), FakeClock(), TestScope(external), tickMs = 1000L
        )
        controller.startOrResume(7L)
        controller.close()
        runCurrent()
        // Caller scope still alive: a new controller on it still ticks.
        val clock2 = FakeClock()
        val second = DefaultWorkoutSessionController(FakeStore(), clock2, TestScope(external))
        second.startOrResume(7L)
        clock2.wall += 2000L
        advanceTimeBy(2500L)
        assertEquals(2L, second.state.value.elapsedSeconds)
        second.close()
    }
}
