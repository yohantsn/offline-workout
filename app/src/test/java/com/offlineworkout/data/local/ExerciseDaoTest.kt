package com.offlineworkout.data.local

import android.database.sqlite.SQLiteConstraintException
import com.offlineworkout.data.model.ExerciseType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExerciseDaoTest : LocalDatabaseTest() {

    @Test
    fun getForWorkout_returns_position_order() = runTest {
        val dao = db.exerciseDao()
        val workoutId = insertWorkout()
        insertExercise(workoutId, name = "C", position = 2)
        insertExercise(workoutId, name = "A", position = 0)
        insertExercise(workoutId, name = "B", position = 1)

        assertEquals(listOf("A", "B", "C"), dao.getForWorkout(workoutId).map { it.name })
        assertEquals(listOf("A", "B", "C"), dao.observeForWorkout(workoutId).first().map { it.name })
    }

    @Test(expected = SQLiteConstraintException::class)
    fun duplicate_position_in_same_workout_fails() = runTest {
        val workoutId = insertWorkout()
        insertExercise(workoutId, position = 0)
        insertExercise(workoutId, position = 0)
    }

    @Test
    fun same_position_in_different_workouts_coexists() = runTest {
        val first = insertWorkout(code = "A")
        val second = insertWorkout(code = "B")
        insertExercise(first, position = 0)
        insertExercise(second, position = 0)

        assertEquals(1, db.exerciseDao().countForWorkout(first))
        assertEquals(1, db.exerciseDao().countForWorkout(second))
    }

    @Test
    fun count_and_maxPosition() = runTest {
        val dao = db.exerciseDao()
        val workoutId = insertWorkout()
        assertEquals(0, dao.countForWorkout(workoutId))
        assertNull(dao.maxPositionForWorkout(workoutId))

        insertExercise(workoutId, position = 0)
        insertExercise(workoutId, position = 2)
        assertEquals(2, dao.countForWorkout(workoutId))
        assertEquals(2, dao.maxPositionForWorkout(workoutId))
    }

    @Test
    fun exercise_type_persists_as_string() = runTest {
        val dao = db.exerciseDao()
        val workoutId = insertWorkout()
        val id = insertExercise(workoutId, type = ExerciseType.DISTANCE_TIME)
        assertEquals(ExerciseType.DISTANCE_TIME, dao.getById(id)?.type)
    }
}
