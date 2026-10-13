package com.offlineworkout.data.session

// T6.1: pure session state. Display precision is seconds (floored); computation
// and persistence use milliseconds. No Android imports.
data class WorkoutSessionState(
    val status: WorkoutSessionStatus,
    val workoutId: Long?,
    val elapsedSeconds: Long,
    val startedAtWallClockMs: Long?,
    val accumulatedMs: Long,
    val lastResumedAtWallClockMs: Long?
)

// Display helper: mm:ss below one hour, h:mm:ss at/above it. Pure.
fun formatSessionElapsed(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
