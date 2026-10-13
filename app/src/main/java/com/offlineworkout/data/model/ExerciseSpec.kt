package com.offlineworkout.data.model

// T2.1: public ordered exercise with its planned targets. Units: kg, reps,
// seconds, meters. Only the target fields matching `type` may be non-null
// (validated by the repository). Persistence-independent.
data class ExerciseSpec(
    val id: Long,
    val workoutId: Long,
    val name: String,
    val type: ExerciseType,
    val position: Int,
    val restSeconds: Int,
    val targetSets: Int?,
    val targetReps: Int?,
    val targetWeightKg: Double?,
    val targetDurationSeconds: Long?,
    val targetDistanceMeters: Double?
)
