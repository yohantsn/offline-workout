package com.offlineworkout.data.model

// T2.1: public views of execution history. Times are epoch milliseconds UTC.
// For TIMED/DISTANCE_TIME `sets` is empty. Persistence-independent.
data class ExecutionSetView(
    val setNumber: Int,
    val reps: Int,
    val weightKg: Double?
)

data class ExerciseExecutionView(
    val id: Long,
    val exerciseId: Long,
    val performedAt: Long,
    val completedAt: Long?,
    val durationSeconds: Long?,
    val distanceMeters: Double?,
    val sets: List<ExecutionSetView>
)
