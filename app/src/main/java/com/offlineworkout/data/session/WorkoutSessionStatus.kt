package com.offlineworkout.data.session

// T6.1: pure session status. No Android, Room or clock imports.
sealed interface WorkoutSessionStatus {
    data object Idle : WorkoutSessionStatus
    data class Running(val workoutId: Long) : WorkoutSessionStatus
    data class Paused(val workoutId: Long) : WorkoutSessionStatus
    data object Finished : WorkoutSessionStatus
}
