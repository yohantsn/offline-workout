package com.offlineworkout.data.repository

import com.offlineworkout.data.model.DistanceTimeExecutionInput
import com.offlineworkout.data.model.ExerciseExecutionView
import com.offlineworkout.data.model.ExecutionSetView
import com.offlineworkout.data.model.HistoryQuery
import com.offlineworkout.data.model.HistoryStats
import com.offlineworkout.data.model.SeriesExecutionInput
import com.offlineworkout.data.model.TimedExecutionInput
import kotlinx.coroutines.flow.Flow

// T2.1: public contract for history reads and execution writes. Never creates,
// updates or deletes workouts/exercises. No Room/DAO/SQLite/data.local imports.
// T7.1: adds paged/window/stats reads; the T2.3 methods stay intact.
interface ExerciseHistoryRepository {
    fun observeHistory(exerciseId: Long): Flow<List<ExerciseExecutionView>>
    fun observeLatestExecution(exerciseId: Long): Flow<ExerciseExecutionView?>
    fun observeLatestSet(exerciseId: Long): Flow<ExecutionSetView?>

    suspend fun recordSeriesExecution(input: SeriesExecutionInput): Result<Long>
    suspend fun recordTimedExecution(input: TimedExecutionInput): Result<Long>
    suspend fun recordDistanceTimeExecution(input: DistanceTimeExecutionInput): Result<Long>
    suspend fun deleteExecution(executionId: Long): Result<Unit>

    fun observeHistoryPage(query: HistoryQuery): Flow<List<ExerciseExecutionView>>
    fun observeHistoryWindow(
        exerciseId: Long,
        fromWallClockMs: Long?,
        toWallClockMs: Long?
    ): Flow<List<ExerciseExecutionView>>
    fun observeHistoryStats(exerciseId: Long): Flow<HistoryStats>
}
