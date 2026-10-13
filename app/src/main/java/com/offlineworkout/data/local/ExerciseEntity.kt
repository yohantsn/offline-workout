package com.offlineworkout.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.offlineworkout.data.model.ExerciseType

// T1.1: one ordered exercise row per workout. Units: kg, reps, seconds, meters.
// Only the target columns matching `type` may be non-null (validated in T2.2 repository).
// position is 0-based and unique per workout; UI orders by position ASC.
@Entity(
    tableName = "exercises",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // T9.2 (R-1): the single-column exercises(workoutId) index was removed;
        // it is the leftmost prefix of this composite index, and EXPLAIN QUERY
        // PLAN kept choosing the composite one for both query shapes.
        Index(value = ["workoutId", "position"], unique = true, name = "index_exercises_workout_position")
    ]
)
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
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
