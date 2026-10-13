package com.offlineworkout.data.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// T1.1 acceptance 12: CASCADE workout -> exercises -> executions -> sets.
@RunWith(RobolectricTestRunner::class)
class CascadeTest : LocalDatabaseTest() {

    @Test
    fun delete_workout_removes_full_subtree() = runTest {
        val workoutId = insertWorkout()
        val exerciseId = insertExercise(workoutId)
        val executionId = db.exerciseExecutionDao().insert(
            ExerciseExecutionEntity(exerciseId = exerciseId, performedAt = 1000L, completedAt = 1100L, durationSeconds = null, distanceMeters = null)
        )
        db.exerciseExecutionDao().insertAllSets(
            listOf(ExecutionSetEntity(executionId = executionId, setNumber = 1, reps = 8, weightKg = null))
        )

        db.workoutDao().delete(db.workoutDao().getById(workoutId)!!)

        assertTrue(db.exerciseDao().getForWorkout(workoutId).isEmpty())
        assertTrue(db.exerciseExecutionDao().getForExercise(exerciseId).isEmpty())
        assertTrue(db.workoutDao().observeAll().first().isEmpty())
    }

    @Test
    fun delete_exercise_removes_executions_and_sets_but_keeps_workout() = runTest {
        val workoutId = insertWorkout()
        val exerciseId = insertExercise(workoutId)
        val executionId = db.exerciseExecutionDao().insert(
            ExerciseExecutionEntity(exerciseId = exerciseId, performedAt = 1000L, completedAt = 1100L, durationSeconds = null, distanceMeters = null)
        )
        db.exerciseExecutionDao().insertAllSets(
            listOf(ExecutionSetEntity(executionId = executionId, setNumber = 1, reps = 8, weightKg = null))
        )

        db.exerciseDao().delete(db.exerciseDao().getById(exerciseId)!!)

        assertTrue(db.exerciseExecutionDao().getForExercise(exerciseId).isEmpty())
        assertTrue(db.workoutDao().getById(workoutId) != null)
    }
}
