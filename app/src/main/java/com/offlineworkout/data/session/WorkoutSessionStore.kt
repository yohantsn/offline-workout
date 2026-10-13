package com.offlineworkout.data.session

// T6.1: persisted session record. Wall-clock only; elapsed time is derived.
// Separate file/keys from RestTimerStore. Pure data, no Android imports.
data class PersistedWorkoutSession(
    val workoutId: Long,
    val status: PersistedSessionStatus,
    val accumulatedMs: Long,
    val lastResumedAtWallClockMs: Long?,
    val startedAtWallClockMs: Long?
)

enum class PersistedSessionStatus { RUNNING, PAUSED, FINISHED }

// T6.1: session persistence seam. Writes happen only on transitions
// (start/pause/resume/stop); the per-second tick stays in memory.
interface WorkoutSessionStore {
    fun load(): PersistedWorkoutSession?
    fun save(record: PersistedWorkoutSession)
}
