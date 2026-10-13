package com.offlineworkout.data.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// T7.1: paged history and aggregate stats against an in-memory database.
@RunWith(RobolectricTestRunner::class)
class HistoryDaoTest : LocalDatabaseTest() {

    private suspend fun seedExerciseWithExecutions(count: Int): Long {
        val workoutId = insertWorkout()
        val exerciseId = insertExercise(workoutId)
        val dao = db.exerciseExecutionDao()
        repeat(count) { i ->
            val executionId = dao.insert(
                ExerciseExecutionEntity(
                    exerciseId = exerciseId,
                    performedAt = 1000L + i,
                    completedAt = 1100L + i,
                    durationSeconds = null,
                    distanceMeters = null
                )
            )
            dao.insertAllSets(
                listOf(ExecutionSetEntity(executionId = executionId, setNumber = 1, reps = 5 + i, weightKg = null))
            )
        }
        return exerciseId
    }

    @Test
    fun page_respects_limit_offset_and_order() = runTest {
        val exerciseId = seedExerciseWithExecutions(5)
        val dao = db.exerciseExecutionDao()

        val first = dao.getPage(exerciseId, null, null, limit = 2, offset = 0)
        assertEquals(2, first.size)
        assertEquals(1004L, first[0].execution.performedAt)
        assertEquals(1003L, first[1].execution.performedAt)

        val second = dao.getPage(exerciseId, null, null, limit = 2, offset = 2)
        assertEquals(2, second.size)
        assertEquals(1002L, second[0].execution.performedAt)
        // No overlap between pages.
        assertTrue(first.map { it.execution.id }.none { second.map { it.execution.id }.contains(it) })

        val tail = dao.getPage(exerciseId, null, null, limit = 10, offset = 4)
        assertEquals(1, tail.size)
    }

    @Test
    fun page_applies_time_window() = runTest {
        val exerciseId = seedExerciseWithExecutions(5)
        val dao = db.exerciseExecutionDao()

        val window = dao.getPage(exerciseId, fromMs = 1002L, toMs = 1003L, limit = 50, offset = 0)
        assertEquals(listOf(1003L, 1002L), window.map { it.execution.performedAt })

        val fromOnly = dao.getPage(exerciseId, fromMs = 1004L, toMs = null, limit = 50, offset = 0)
        assertEquals(listOf(1004L), fromOnly.map { it.execution.performedAt })

        val empty = dao.getPage(exerciseId, fromMs = 9999L, toMs = null, limit = 50, offset = 0)
        assertTrue(empty.isEmpty())
    }

    @Test
    fun observe_page_flow_matches() = runTest {
        val exerciseId = seedExerciseWithExecutions(3)
        val page = db.exerciseExecutionDao()
            .observePage(exerciseId, null, null, limit = 2, offset = 1)
            .first()
        assertEquals(2, page.size)
        assertEquals(1001L, page[0].execution.performedAt)
        // Sets stay ordered within the relation payload.
        assertEquals(listOf(1), page[0].sets.map { it.setNumber })
    }

    @Test
    fun stats_counts_and_bounds() = runTest {
        val exerciseId = seedExerciseWithExecutions(4)
        val row = db.exerciseExecutionDao().observeStats(exerciseId).first()
        assertEquals(4, row.count)
        assertEquals(1000L, row.firstPerformedAt)
        assertEquals(1003L, row.lastPerformedAt)
    }

    @Test
    fun stats_empty_is_zero_and_nulls() = runTest {
        val exerciseId = insertExercise(insertWorkout())
        val row = db.exerciseExecutionDao().observeStats(exerciseId).first()
        assertEquals(0, row.count)
        assertEquals(null, row.firstPerformedAt)
        assertEquals(null, row.lastPerformedAt)
    }
}
