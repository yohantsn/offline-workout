package com.offlineworkout.data.model

// T2.1: repository write inputs. The repository validates every rule before
// commit; inputs carry no Room/SQL/Android types.

// createdAt is assigned by the repository (testable clock), not by the UI.
data class WorkoutDraft(
    val code: String,
    val name: String
)

// Never changes id or createdAt.
data class WorkoutUpdate(
    val code: String,
    val name: String
)

// Creation and update share the shape; the repository validates the type/target
// combination, position, rest and the workout FK.
data class ExerciseInput(
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

data class ExecutionSetInput(
    val setNumber: Int,
    val reps: Int,
    val weightKg: Double?
)

// Atomic SERIES execution: parent + sets commit together. completedAt is null
// while in progress.
data class SeriesExecutionInput(
    val exerciseId: Long,
    val performedAt: Long,
    val completedAt: Long?,
    val sets: List<ExecutionSetInput>
)

data class TimedExecutionInput(
    val exerciseId: Long,
    val performedAt: Long,
    val completedAt: Long?,
    val durationSeconds: Long
)

data class DistanceTimeExecutionInput(
    val exerciseId: Long,
    val performedAt: Long,
    val completedAt: Long?,
    val durationSeconds: Long,
    val distanceMeters: Double
)
