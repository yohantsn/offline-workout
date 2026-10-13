package com.offlineworkout.data.timer

// T5.1: pure snapshot. remainingSeconds is derived from the wall-clock
// deadline (ceiling, never negative); the countdown itself is never persisted.
// Times are wall-clock milliseconds UTC, except the in-boot monotonic target
// owned by the Service/engine.
data class RestTimerSnapshot(
    val status: RestTimerStatus,
    val workoutId: Long,
    val exerciseId: Long,
    val durationSeconds: Int,
    val remainingSeconds: Int,
    val startedAtWallClockMs: Long,
    val endsAtWallClockMs: Long,
    val alertDelivered: Boolean
)

// Pure remaining computation shared by store restore and tests.
fun remainingSeconds(endsAtWallClockMs: Long, nowWallClockMs: Long): Int =
    ((endsAtWallClockMs - nowWallClockMs + 999) / 1000).coerceAtLeast(0).toInt()
