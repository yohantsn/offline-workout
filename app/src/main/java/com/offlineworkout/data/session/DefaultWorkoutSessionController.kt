package com.offlineworkout.data.session

import com.offlineworkout.data.timer.TimerClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// T6.1: process-scoped total stopwatch. Recomputes elapsedSeconds once per
// second on its own scope; persists only on transitions. Never starts a
// foreground service and never writes history. Scope and tick are injectable
// so tests run deterministically without real sleeps.
class DefaultWorkoutSessionController(
    private val store: WorkoutSessionStore,
    private val clock: TimerClock,
    externalScope: CoroutineScope? = null,
    private val tickMs: Long = 1000L
) : WorkoutSessionController {

    private val ownsScope = externalScope == null
    private val ownedScope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mutableState = MutableStateFlow(restore())
    override val state: StateFlow<WorkoutSessionState> = mutableState.asStateFlow()

    private var tickerJob: Job? = null

    init {
        // T9.2 (R-3): the ticker used to run for the whole process life, waking
        // up every second even with no session at all. It now runs only while
        // the session is Running, and startOrResume/resume re-enter it.
        startTicker()
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = ownedScope.launch {
            while (isActive) {
                val current = mutableState.value
                if (current.status !is WorkoutSessionStatus.Running) {
                    // Leave the loop instead of spinning; a later start/resume
                    // calls startTicker() again.
                    break
                }
                delay(tickMs)
                mutableState.value = tick(mutableState.value, clock.wallClockMs())
            }
        }
    }

    override fun startOrResume(workoutId: Long) {
        val current = store.load()
        when {
            // No session or a finished one: start fresh.
            current == null || current.status == PersistedSessionStatus.FINISHED ->
                begin(workoutId, clock.wallClockMs())
            // Another workout owns the session: never overwrite.
            current.workoutId != workoutId -> {
                mutableState.value = fromRecord(current, clock.wallClockMs())
            }
            // Same workout: resume tracking from persisted values.
            else -> mutableState.value = fromRecord(current, clock.wallClockMs())
        }
    }

    override fun pause() {
        val record = store.load() ?: return
        if (record.status != PersistedSessionStatus.RUNNING) return
        val now = clock.wallClockMs()
        val accumulated = record.accumulatedMs + maxOf(0L, now - (record.lastResumedAtWallClockMs ?: now))
        val updated = record.copy(
            status = PersistedSessionStatus.PAUSED,
            accumulatedMs = accumulated,
            lastResumedAtWallClockMs = null
        )
        store.save(updated)
        mutableState.value = fromRecord(updated, now)
    }

    override fun resume() {
        val record = store.load() ?: return
        if (record.status != PersistedSessionStatus.PAUSED) return
        val now = clock.wallClockMs()
        val updated = record.copy(status = PersistedSessionStatus.RUNNING, lastResumedAtWallClockMs = now)
        store.save(updated)
        mutableState.value = fromRecord(updated, now)
        // R-3: resuming re-enters the ticker loop that pause/idle left.
        startTicker()
    }

    override fun stop() {
        val record = store.load() ?: run {
            mutableState.value = finishedState()
            return
        }
        val now = clock.wallClockMs()
        val accumulated = if (record.status == PersistedSessionStatus.RUNNING) {
            record.accumulatedMs + maxOf(0L, now - (record.lastResumedAtWallClockMs ?: now))
        } else {
            record.accumulatedMs
        }
        store.save(
            record.copy(
                status = PersistedSessionStatus.FINISHED,
                accumulatedMs = accumulated,
                lastResumedAtWallClockMs = null
            )
        )
        mutableState.value = finishedState(accumulated)
    }

    private fun begin(workoutId: Long, now: Long) {
        val record = PersistedWorkoutSession(
            workoutId = workoutId,
            status = PersistedSessionStatus.RUNNING,
            accumulatedMs = 0L,
            lastResumedAtWallClockMs = now,
            startedAtWallClockMs = now
        )
        store.save(record)
        mutableState.value = fromRecord(record, now)
        // R-3: a fresh session is Running, so the ticker (re)starts.
        startTicker()
    }

    private fun restore(): WorkoutSessionState {
        val record = store.load() ?: return idleState()
        return fromRecord(record, clock.wallClockMs())
    }

    private fun tick(previous: WorkoutSessionState, nowWallMs: Long): WorkoutSessionState =
        when (previous.status) {
            is WorkoutSessionStatus.Running -> previous.copy(
                elapsedSeconds = elapsedSeconds(
                    previous.accumulatedMs,
                    previous.lastResumedAtWallClockMs,
                    nowWallMs
                )
            )
            else -> previous
        }

    private fun fromRecord(record: PersistedWorkoutSession, nowWallMs: Long): WorkoutSessionState {
        val status = when (record.status) {
            PersistedSessionStatus.RUNNING -> WorkoutSessionStatus.Running(record.workoutId)
            PersistedSessionStatus.PAUSED -> WorkoutSessionStatus.Paused(record.workoutId)
            PersistedSessionStatus.FINISHED -> WorkoutSessionStatus.Finished
        }
        return WorkoutSessionState(
            status = status,
            workoutId = record.workoutId,
            elapsedSeconds = elapsedSeconds(record.accumulatedMs, record.lastResumedAtWallClockMs, nowWallMs),
            startedAtWallClockMs = record.startedAtWallClockMs,
            accumulatedMs = record.accumulatedMs,
            lastResumedAtWallClockMs = record.lastResumedAtWallClockMs
        )
    }

    private fun elapsedSeconds(accumulatedMs: Long, lastResumedWallMs: Long?, nowWallMs: Long): Long {
        val live = if (lastResumedWallMs != null) maxOf(0L, nowWallMs - lastResumedWallMs) else 0L
        return (accumulatedMs + live) / 1000
    }

    private fun idleState() = WorkoutSessionState(
        status = WorkoutSessionStatus.Idle,
        workoutId = null,
        elapsedSeconds = 0L,
        startedAtWallClockMs = null,
        accumulatedMs = 0L,
        lastResumedAtWallClockMs = null
    )

    private fun finishedState(accumulatedMs: Long = 0L) = WorkoutSessionState(
        status = WorkoutSessionStatus.Finished,
        workoutId = null,
        elapsedSeconds = accumulatedMs / 1000,
        startedAtWallClockMs = null,
        accumulatedMs = accumulatedMs,
        lastResumedAtWallClockMs = null
    )

    /**
     * T9.3: observable for the R-12 regression test. `tickerJob` was private,
     * so the test could only assert that `close()` was callable — it would have
     * passed with the ticker still alive.
     */
    internal val isTickerActive: Boolean get() = tickerJob?.isActive == true

    override fun close() {
        tickerJob?.cancel()
        tickerJob = null
        if (ownsScope) ownedScope.cancel()
    }
}
