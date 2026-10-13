package com.offlineworkout.data.local

import androidx.room.Embedded
import androidx.room.Relation

// T1.1: workout with its exercises. @Relation order is not guaranteed;
// consumers needing guaranteed order use WorkoutDao.observeExercises
// (ORDER BY position ASC) — see T1.1 spec.
data class WorkoutWithExercises(
    @Embedded val workout: WorkoutEntity,
    @Relation(parentColumn = "id", entityColumn = "workoutId")
    val exercises: List<ExerciseEntity>
)
