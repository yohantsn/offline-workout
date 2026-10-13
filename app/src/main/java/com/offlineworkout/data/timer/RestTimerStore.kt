package com.offlineworkout.data.timer

import kotlinx.coroutines.flow.StateFlow

// T5.1: persisted timer record. Only the active/last timer is stored, in
// wall-clock deadlines — never a remaining-seconds countdown.
enum class PersistedTimerStatus { RUNNING, FINISHED }

data class PersistedRestTimer(
    val timerId: String,
    val workoutId: Long,
    val exerciseId: Long,
    val durationSeconds: Int,
    val startedAtWallClockMs: Long,
    val endsAtWallClockMs: Long,
    val status: PersistedTimerStatus,
    val finishedAtWallClockMs: Long?,
    val alertDelivered: Boolean
)

// T5.1: process-scoped persisted timer state. Disk writes happen only on
// transitions (start/finish/cancel/alert flag); ticks only re-emit snapshots.
// Pure interface: no Room, no history tables.
interface RestTimerStore {
    val state: StateFlow<RestTimerSnapshot?>
    fun start(record: PersistedRestTimer)
    fun updateRemaining(remainingSeconds: Int)
    fun finish(finishedAtWallClockMs: Long)
    fun markAlertDelivered()
    fun cancel()
    fun loadPersisted(): PersistedRestTimer?
    fun restore(): RestTimerSnapshot?
}
