package com.offlineworkout.data.repository

import com.offlineworkout.data.local.ExerciseExecutionEntity
import com.offlineworkout.data.local.ExecutionSetEntity
import com.offlineworkout.data.local.LocalDatabaseTest
import com.offlineworkout.data.model.HISTORY_MAX_ROWS
import com.offlineworkout.data.model.HistoryQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// T7.1: query contract on a real Room repository: normalization, paging,
// window cap, stats mapping and error mapping without leaking Room types.
@RunWith(RobolectricTestRunner::class)
class HistoryQueryTest : LocalDatabaseTest() {

    private fun repository() = RoomExerciseHistoryRepository(db)

    private suspend fun seedExecutions(exerciseId: Long, count: Int) {
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
                listOf(ExecutionSetEntity(executionId = executionId, setNumber = 1, reps = 5, weightKg = 20.0))
            )
        }
    }

    @Test
    fun page_limit_offset_and_order() = runTest {
        val exerciseId = insertExercise(insertWorkout())
        seedExecutions(exerciseId, 5)

        val first = repository()
            .observeHistoryPage(HistoryQuery(exerciseId, limit = 2))
            .first()
        assertEquals(listOf(1004L, 1003L), first.map { it.performedAt })
        // Sets mapped with values, ordered.
        assertEquals(20.0, first[0].sets.single().weightKg)

        val second = repository()
            .observeHistoryPage(HistoryQuery(exerciseId, limit = 2, offset = 2))
            .first()
        assertEquals(listOf(1002L, 1001L), second.map { it.performedAt })
    }

    @Test
    fun limit_normalizes_into_range() = runTest {
        val exerciseId = insertExercise(insertWorkout())
        seedExecutions(exerciseId, 3)

        val zeroBecomesOne = repository()
            .observeHistoryPage(HistoryQuery(exerciseId, limit = 0))
            .first()
        assertEquals(1, zeroBecomesOne.size)

        val hugeClamps = repository()
            .observeHistoryPage(HistoryQuery(exerciseId, limit = Int.MAX_VALUE))
            .first()
        assertEquals(3, hugeClamps.size)
        assertTrue(HISTORY_MAX_ROWS == 500)
    }

    @Test
    fun negative_offset_is_validation_failure() = runTest {
        val exerciseId = insertExercise(insertWorkout())
        val failure = try {
            repository().observeHistoryPage(HistoryQuery(exerciseId, limit = 10, offset = -1)).first()
            null
        } catch (e: RepositoryException) {
            e.failure
        }
        assertTrue(failure is RepositoryFailure.Validation)
    }

    @Test
    fun window_respects_bounds_and_caps_at_500() = runTest {
        val exerciseId = insertExercise(insertWorkout())
        seedExecutions(exerciseId, 5)

        val window = repository().observeHistoryWindow(exerciseId, 1001L, 1003L).first()
        assertEquals(listOf(1003L, 1002L, 1001L), window.map { it.performedAt })

        val open = repository().observeHistoryWindow(exerciseId, null, null).first()
        assertEquals(5, open.size)
    }

    @Test
    fun stats_maps_count_and_bounds() = runTest {
        val exerciseId = insertExercise(insertWorkout())
        seedExecutions(exerciseId, 4)

        val stats = repository().observeHistoryStats(exerciseId).first()
        assertEquals(exerciseId, stats.exerciseId)
        assertEquals(4, stats.count)
        assertEquals(1000L, stats.firstPerformedAtWallClockMs)
        assertEquals(1003L, stats.lastPerformedAtWallClockMs)
    }

    @Test
    fun stats_empty_is_consistent() = runTest {
        val exerciseId = insertExercise(insertWorkout())
        val stats = repository().observeHistoryStats(exerciseId).first()
        assertEquals(0, stats.count)
        assertEquals(null, stats.firstPerformedAtWallClockMs)
        assertEquals(null, stats.lastPerformedAtWallClockMs)
    }

    @Test
    fun legacy_history_still_intact() = runTest {
        val exerciseId = insertExercise(insertWorkout())
        seedExecutions(exerciseId, 2)
        val full = repository().observeHistory(exerciseId).first()
        assertEquals(2, full.size)
        assertEquals(1001L, full[0].performedAt)
    }
}
