package com.offlineworkout.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// T1.1: execution + sets DAO. Abstract class (not interface) so the public
// history/latest readers can sort each relation payload by setNumber ASC:
// @Relation does not guarantee order, but the T1.1 contract requires it.
// Raw @Transaction queries stay protected; atomic series writes arrive with
// the T2.2 repository (DAO surface already supports them).
@Dao
abstract class ExerciseExecutionDao {

    @Insert
    abstract suspend fun insert(execution: ExerciseExecutionEntity): Long

    @Insert
    abstract suspend fun insertAllSets(sets: List<ExecutionSetEntity>)

    @Delete
    abstract suspend fun delete(execution: ExerciseExecutionEntity)

    @Query("DELETE FROM execution_sets WHERE executionId = :executionId")
    abstract suspend fun deleteSetsForExecution(executionId: Long)

    @Query("SELECT * FROM exercise_executions WHERE id = :id")
    abstract suspend fun getById(id: Long): ExerciseExecutionEntity?

    @Transaction
    @Query("SELECT * FROM exercise_executions WHERE exerciseId = :exerciseId ORDER BY performedAt DESC, id DESC")
    protected abstract suspend fun getRawForExercise(exerciseId: Long): List<ExerciseExecutionWithSets>

    suspend fun getForExercise(exerciseId: Long): List<ExerciseExecutionWithSets> =
        getRawForExercise(exerciseId).map { it.sortedSets() }

    @Transaction
    @Query("SELECT * FROM exercise_executions WHERE exerciseId = :exerciseId ORDER BY performedAt DESC, id DESC")
    protected abstract fun observeRawHistory(exerciseId: Long): Flow<List<ExerciseExecutionWithSets>>

    fun observeHistory(exerciseId: Long): Flow<List<ExerciseExecutionWithSets>> =
        observeRawHistory(exerciseId).map { list -> list.map { it.sortedSets() } }

    @Transaction
    @Query("SELECT * FROM exercise_executions WHERE exerciseId = :exerciseId ORDER BY performedAt DESC, id DESC LIMIT 1")
    protected abstract suspend fun getRawLatestForExercise(exerciseId: Long): ExerciseExecutionWithSets?

    suspend fun getLatestForExercise(exerciseId: Long): ExerciseExecutionWithSets? =
        getRawLatestForExercise(exerciseId)?.sortedSets()

    @Transaction
    @Query("SELECT * FROM exercise_executions WHERE exerciseId = :exerciseId ORDER BY performedAt DESC, id DESC LIMIT 1")
    protected abstract fun observeRawLatestForExercise(exerciseId: Long): Flow<ExerciseExecutionWithSets?>

    fun observeLatestForExercise(exerciseId: Long): Flow<ExerciseExecutionWithSets?> =
        observeRawLatestForExercise(exerciseId).map { it?.sortedSets() }


    @Query("DELETE FROM exercise_executions WHERE exerciseId = :exerciseId")
    abstract suspend fun deleteByExercise(exerciseId: Long)

    // T8.3: backup export order (T8.1 spec). These two reads are the whole
    // file: exercises by exerciseId ASC, performedAt DESC, id DESC, and their
    // sets by executionId ASC, setNumber ASC, id ASC. They are independent of
    // the per-exercise history readers above, whose order is scoped by id.
    @Query("SELECT * FROM exercise_executions ORDER BY exerciseId ASC, performedAt DESC, id DESC")
    abstract suspend fun getAllOrdered(): List<ExerciseExecutionEntity>

    @Query("SELECT * FROM execution_sets ORDER BY executionId ASC, setNumber ASC, id ASC")
    abstract suspend fun getAllOrderedSets(): List<ExecutionSetEntity>

    // T8.1: full wipes for replace-all import (child table first).
    @Query("DELETE FROM execution_sets")
    abstract suspend fun deleteAllSets()

    @Query("DELETE FROM exercise_executions")
    abstract suspend fun deleteAllExecutions()

    // T7.1: paged history with optional wall-clock window. NULL bounds disable
    // that side of the filter; ordering matches the unpaged history contract.
    @Transaction
    @Query(
        "SELECT * FROM exercise_executions WHERE exerciseId = :exerciseId " +
            "AND (:fromMs IS NULL OR performedAt >= :fromMs) " +
            "AND (:toMs IS NULL OR performedAt <= :toMs) " +
            "ORDER BY performedAt DESC, id DESC LIMIT :limit OFFSET :offset"
    )
    protected abstract suspend fun getRawPage(
        exerciseId: Long,
        fromMs: Long?,
        toMs: Long?,
        limit: Int,
        offset: Int
    ): List<ExerciseExecutionWithSets>

    suspend fun getPage(
        exerciseId: Long,
        fromMs: Long?,
        toMs: Long?,
        limit: Int,
        offset: Int
    ): List<ExerciseExecutionWithSets> =
        getRawPage(exerciseId, fromMs, toMs, limit, offset).map { it.sortedSets() }

    @Transaction
    @Query(
        "SELECT * FROM exercise_executions WHERE exerciseId = :exerciseId " +
            "AND (:fromMs IS NULL OR performedAt >= :fromMs) " +
            "AND (:toMs IS NULL OR performedAt <= :toMs) " +
            "ORDER BY performedAt DESC, id DESC LIMIT :limit OFFSET :offset"
    )
    protected abstract fun observeRawPage(
        exerciseId: Long,
        fromMs: Long?,
        toMs: Long?,
        limit: Int,
        offset: Int
    ): Flow<List<ExerciseExecutionWithSets>>

    fun observePage(
        exerciseId: Long,
        fromMs: Long?,
        toMs: Long?,
        limit: Int,
        offset: Int
    ): Flow<List<ExerciseExecutionWithSets>> =
        observeRawPage(exerciseId, fromMs, toMs, limit, offset).map { list -> list.map { it.sortedSets() } }

    // T7.1: single-row aggregate; the existing (exerciseId, performedAt, id)
    // index serves COUNT/MIN/MAX, so no new index is needed.
    @Query(
        "SELECT COUNT(*) AS count, MIN(performedAt) AS firstPerformedAt, " +
            "MAX(performedAt) AS lastPerformedAt FROM exercise_executions " +
            "WHERE exerciseId = :exerciseId"
    )
    abstract fun observeStats(exerciseId: Long): Flow<ExerciseHistoryStatsRow>

    private fun ExerciseExecutionWithSets.sortedSets(): ExerciseExecutionWithSets =
        copy(sets = sets.sortedBy { it.setNumber })
}
