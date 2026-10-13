package com.offlineworkout.data.local

import com.offlineworkout.data.model.ExerciseType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExerciseExecutionDaoTest : LocalDatabaseTest() {

    @Test
    fun history_returns_performedAt_desc_then_id_desc() = runTest {
        val dao = db.exerciseExecutionDao()
        val exerciseId = insertExercise(insertWorkout())
        dao.insert(ExerciseExecutionEntity(exerciseId = exerciseId, performedAt = 2000L, completedAt = 2100L, durationSeconds = null, distanceMeters = null))
        val tieOld = dao.insert(ExerciseExecutionEntity(exerciseId = exerciseId, performedAt = 1000L, completedAt = 1100L, durationSeconds = null, distanceMeters = null))
        val tieNew = dao.insert(ExerciseExecutionEntity(exerciseId = exerciseId, performedAt = 1000L, completedAt = 1150L, durationSeconds = null, distanceMeters = null))

        val history = dao.getForExercise(exerciseId)
        assertEquals(3, history.size)
        // Most recent performedAt first; ties broken by id DESC.
        assertTrue(history[0].execution.performedAt == 2000L)
        assertEquals(tieNew, history[1].execution.id)
        assertEquals(tieOld, history[2].execution.id)
        assertEquals(
            history.map { it.execution.id },
            dao.observeHistory(exerciseId).first().map { it.execution.id }
        )
    }

    @Test
    fun sets_sorted_by_setNumber_asc_even_when_inserted_out_of_order() = runTest {
        val dao = db.exerciseExecutionDao()
        val exerciseId = insertExercise(insertWorkout())
        val executionId = dao.insert(
            ExerciseExecutionEntity(exerciseId = exerciseId, performedAt = 1000L, completedAt = 1100L, durationSeconds = null, distanceMeters = null)
        )
        dao.insertAllSets(
            listOf(
                ExecutionSetEntity(executionId = executionId, setNumber = 3, reps = 8, weightKg = 20.0),
                ExecutionSetEntity(executionId = executionId, setNumber = 1, reps = 10, weightKg = 20.0),
                ExecutionSetEntity(executionId = executionId, setNumber = 2, reps = 9, weightKg = null)
            )
        )

        val single = dao.getForExercise(exerciseId).single()
        assertEquals(listOf(1, 2, 3), single.sets.map { it.setNumber })
        assertEquals(listOf(1, 2, 3), dao.observeHistory(exerciseId).first().single().sets.map { it.setNumber })
    }

    @Test
    fun latest_execution_and_latest_set() = runTest {
        val dao = db.exerciseExecutionDao()
        val exerciseId = insertExercise(insertWorkout())
        val oldExecution = dao.insert(
            ExerciseExecutionEntity(exerciseId = exerciseId, performedAt = 1000L, completedAt = 1100L, durationSeconds = null, distanceMeters = null)
        )
        dao.insertAllSets(listOf(ExecutionSetEntity(executionId = oldExecution, setNumber = 1, reps = 5, weightKg = null)))
        val newExecution = dao.insert(
            ExerciseExecutionEntity(exerciseId = exerciseId, performedAt = 2000L, completedAt = 2100L, durationSeconds = null, distanceMeters = null)
        )
        dao.insertAllSets(
            listOf(
                ExecutionSetEntity(executionId = newExecution, setNumber = 1, reps = 6, weightKg = 10.0),
                ExecutionSetEntity(executionId = newExecution, setNumber = 2, reps = 6, weightKg = 12.5)
            )
        )

        assertEquals(newExecution, dao.getLatestForExercise(exerciseId)?.execution?.id)
        assertEquals(newExecution, dao.observeLatestForExercise(exerciseId).first()?.execution?.id)
        // T9.2 (R-21): the one-off getLatestSetForExercise query was removed
        // (no production caller). The latest set is still observable through the
        // relation, which is what the prefill actually uses.
        // The latest execution is the one with the greatest performedAt; its
        // last set (setNumber ASC) is the most recent series entry.
        val latest = dao.getForExercise(exerciseId).first()
        val latestSets = latest.sets
        assertEquals(2, latestSets.last().setNumber)
        assertEquals(newExecution, latestSets.last().executionId)
    }

    @Test
    fun latest_returns_null_without_history() = runTest {
        val dao = db.exerciseExecutionDao()
        val exerciseId = insertExercise(insertWorkout())
        assertNull(dao.getLatestForExercise(exerciseId))
        assertNull(dao.observeLatestForExercise(exerciseId).first())
        assertTrue(dao.getForExercise(exerciseId).all { it.sets.isEmpty() })
    }

    @Test
    fun timed_execution_has_no_sets() = runTest {
        val dao = db.exerciseExecutionDao()
        val exerciseId = insertExercise(insertWorkout(), type = ExerciseType.TIMED)
        dao.insert(
            ExerciseExecutionEntity(exerciseId = exerciseId, performedAt = 1000L, completedAt = 1060L, durationSeconds = 60L, distanceMeters = null)
        )
        val single = dao.getForExercise(exerciseId).single()
        assertEquals(60L, single.execution.durationSeconds)
        assertTrue(single.sets.isEmpty())
    }

    @Test
    fun deleteSetsForExecution_and_deleteByExercise() = runTest {
        val dao = db.exerciseExecutionDao()
        val exerciseId = insertExercise(insertWorkout())
        val executionId = dao.insert(
            ExerciseExecutionEntity(exerciseId = exerciseId, performedAt = 1000L, completedAt = 1100L, durationSeconds = null, distanceMeters = null)
        )
        dao.insertAllSets(listOf(ExecutionSetEntity(executionId = executionId, setNumber = 1, reps = 8, weightKg = null)))

        dao.deleteSetsForExecution(executionId)
        assertTrue(dao.getForExercise(exerciseId).single().sets.isEmpty())

        dao.deleteByExercise(exerciseId)
        assertTrue(dao.getForExercise(exerciseId).isEmpty())
    }
}
