package com.offlineworkout.data.model

// T4.1: single atomic aggregate for create and edit. Position is never sent by
// the UI: the repository derives it from the list index. id is null for new
// exercises and preserved for existing ones. No removal representation.
data class ExerciseAggregateDraft(
    val id: Long?,
    val name: String,
    val type: ExerciseType,
    val restSeconds: Int,
    val targetSets: Int?,
    val targetReps: Int?,
    val targetWeightKg: Double?,
    val targetDurationSeconds: Long?,
    val targetDistanceMeters: Double?
)

data class WorkoutAggregateDraft(
    val code: String,
    val name: String,
    val exercises: List<ExerciseAggregateDraft>
)
