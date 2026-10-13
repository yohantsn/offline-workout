package com.offlineworkout.data.local

import android.database.sqlite.SQLiteConstraintException
import com.offlineworkout.data.model.ExerciseType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkoutDaoTest : LocalDatabaseTest() {

    @Test
    fun insert_getById_getByCode() = runTest {
        val dao = db.workoutDao()
        val id = dao.insert(WorkoutEntity(code = "A", name = "Treino A", createdAt = 1000L))

        assertEquals(id, dao.getById(id)?.id)
        assertEquals("Treino A", dao.getByCode("A")?.name)
        assertNull(dao.getByCode("B"))
    }

    @Test
    fun codes_A_B_C_coexist() = runTest {
        val dao = db.workoutDao()
        // T9.2 (R-21): the batch insertAll had no production caller and was
        // removed; free codes (D-1) make this the interesting case anyway.
        listOf(
            WorkoutEntity(code = "A", name = "A", createdAt = 1L),
            WorkoutEntity(code = "B", name = "B", createdAt = 2L),
            WorkoutEntity(code = "C", name = "C", createdAt = 3L),
            WorkoutEntity(code = "D", name = "D", createdAt = 4L)
        ).forEach { dao.insert(it) }
        assertEquals(4, dao.observeAll().first().size)
        assertEquals(1, dao.countByCode("B"))
        assertEquals(1, dao.countByCode("D"))
    }

    @Test(expected = SQLiteConstraintException::class)
    fun duplicate_code_fails() = runTest {
        val dao = db.workoutDao()
        dao.insert(WorkoutEntity(code = "A", name = "First", createdAt = 1L))
        dao.insert(WorkoutEntity(code = "A", name = "Second", createdAt = 2L))
    }

    @Test
    fun observeExercises_returns_position_order() = runTest {
        val dao = db.workoutDao()
        val workoutId = insertWorkout()
        insertExercise(workoutId, name = "Second", position = 1)
        insertExercise(workoutId, name = "First", position = 0)

        val names = dao.observeExercises(workoutId).first().map { it.name }
        assertEquals(listOf("First", "Second"), names)
    }

    @Test
    fun observeById_emits_null_after_delete() = runTest {
        val dao = db.workoutDao()
        val id = dao.insert(WorkoutEntity(code = "A", name = "A", createdAt = 1L))
        assertNotNull(dao.observeById(id).first())
        dao.delete(dao.getById(id)!!)
        assertNull(dao.observeById(id).first())
    }

    @Test
    fun aggregated_empty_workout_counts_zero() = runTest {
        val dao = db.workoutDao()
        val id = dao.insert(WorkoutEntity(code = "A", name = "A", createdAt = 1L))

        val all = dao.observeAllAggregated().first()
        assertEquals(1, all.size)
        assertEquals(0, all[0].exerciseCount)
        assertEquals(0, all[0].totalRestSeconds)

        val one = dao.observeByIdAggregated(id).first()
        assertNotNull(one)
        assertEquals(0, one?.exerciseCount)
        assertEquals(0, one?.totalRestSeconds)
    }

    @Test
    fun aggregated_sums_exercise_rest() = runTest {
        val dao = db.workoutDao()
        val id = dao.insert(WorkoutEntity(code = "A", name = "A", createdAt = 1L))
        db.exerciseDao().insert(
            ExerciseEntity(
                workoutId = id, name = "Supino", type = ExerciseType.SERIES,
                position = 0, restSeconds = 90, targetSets = 4, targetReps = 8,
                targetWeightKg = 60.0, targetDurationSeconds = null, targetDistanceMeters = null
            )
        )
        db.exerciseDao().insert(
            ExerciseEntity(
                workoutId = id, name = "Corrida", type = ExerciseType.TIMED,
                position = 1, restSeconds = 30, targetSets = null, targetReps = null,
                targetWeightKg = null, targetDurationSeconds = 300L, targetDistanceMeters = null
            )
        )

        val row = dao.observeByIdAggregated(id).first()
        assertNotNull(row)
        assertEquals(2, row?.exerciseCount)
        assertEquals(120, row?.totalRestSeconds)
    }

    @Test
    fun aggregated_by_id_null_for_missing_workout() = runTest {
        assertNull(db.workoutDao().observeByIdAggregated(999L).first())
    }
}
