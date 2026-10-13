package com.offlineworkout.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.offlineworkout.data.local.AppDatabase
import com.offlineworkout.data.local.ExerciseExecutionEntity
import com.offlineworkout.data.local.ExerciseExecutionWithSets
import com.offlineworkout.data.local.ExecutionSetEntity
import com.offlineworkout.data.model.DistanceTimeExecutionInput
import com.offlineworkout.data.model.ExerciseExecutionView
import com.offlineworkout.data.model.ExecutionSetInput
import com.offlineworkout.data.model.ExecutionSetView
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.HISTORY_MAX_ROWS
import com.offlineworkout.data.model.HistoryQuery
import com.offlineworkout.data.model.HistoryStats
import com.offlineworkout.data.model.SeriesExecutionInput
import com.offlineworkout.data.model.TimedExecutionInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

// T2.1: Room implementation of execution history. Owns type/timestamp/set
// validation, mapping and atomic parent+sets writes. Never touches
// workouts/exercises configuration (read-only FK lookups for validation).
class RoomExerciseHistoryRepository(
    private val db: AppDatabase
) : ExerciseHistoryRepository {

    override fun observeHistory(exerciseId: Long): Flow<List<ExerciseExecutionView>> =
        db.exerciseExecutionDao().observeHistory(exerciseId)
            .map { list -> list.map { it.toView() } }
            .asRepositoryFlow()

    override fun observeLatestExecution(exerciseId: Long): Flow<ExerciseExecutionView?> =
        db.exerciseExecutionDao().observeLatestForExercise(exerciseId)
            .map { it?.toView() }
            .asRepositoryFlow()

    // Satisfied by the existing DAO surface without structural change: the DAO
    // sorts every relation payload by setNumber ASC, so the last element is the
    // latest set of the latest execution (performedAt DESC, id DESC).
    override fun observeLatestSet(exerciseId: Long): Flow<ExecutionSetView?> =
        db.exerciseExecutionDao().observeLatestForExercise(exerciseId)
            .map { it?.sets?.lastOrNull()?.toView() }
            .asRepositoryFlow()

    override suspend fun recordSeriesExecution(input: SeriesExecutionInput): Result<Long> = write {
        validateTimestamps(input.performedAt, input.completedAt)?.let { throw it }
        validateSets(input.sets)?.let { throw it }
        db.withTransaction {
            val exercise = db.exerciseDao().getById(input.exerciseId)
                ?: throw RepositoryFailure.NotFound("exercise not found")
            if (exercise.type != ExerciseType.SERIES) {
                throw RepositoryFailure.Validation("exercise type is ${exercise.type} but SERIES execution recorded")
            }
            val executionId = db.exerciseExecutionDao().insert(
                ExerciseExecutionEntity(
                    exerciseId = input.exerciseId,
                    performedAt = input.performedAt,
                    completedAt = input.completedAt,
                    durationSeconds = null,
                    distanceMeters = null
                )
            )
            db.exerciseExecutionDao().insertAllSets(
                input.sets.map {
                    ExecutionSetEntity(executionId = executionId, setNumber = it.setNumber, reps = it.reps, weightKg = it.weightKg)
                }
            )
            executionId
        }
    }

    override suspend fun recordTimedExecution(input: TimedExecutionInput): Result<Long> = write {
        validateTimestamps(input.performedAt, input.completedAt)?.let { throw it }
        if (input.durationSeconds <= 0) throw RepositoryFailure.Validation("TIMED requires durationSeconds > 0")
        db.withTransaction {
            val exercise = db.exerciseDao().getById(input.exerciseId)
                ?: throw RepositoryFailure.NotFound("exercise not found")
            if (exercise.type != ExerciseType.TIMED) {
                throw RepositoryFailure.Validation("exercise type is ${exercise.type} but TIMED execution recorded")
            }
            db.exerciseExecutionDao().insert(
                ExerciseExecutionEntity(
                    exerciseId = input.exerciseId,
                    performedAt = input.performedAt,
                    completedAt = input.completedAt,
                    durationSeconds = input.durationSeconds,
                    distanceMeters = null
                )
            )
        }
    }

    override suspend fun recordDistanceTimeExecution(input: DistanceTimeExecutionInput): Result<Long> = write {
        validateTimestamps(input.performedAt, input.completedAt)?.let { throw it }
        if (input.durationSeconds <= 0) {
            throw RepositoryFailure.Validation("DISTANCE_TIME requires durationSeconds > 0")
        }
        if (input.distanceMeters < 0) {
            throw RepositoryFailure.Validation("DISTANCE_TIME requires distanceMeters >= 0")
        }
        db.withTransaction {
            val exercise = db.exerciseDao().getById(input.exerciseId)
                ?: throw RepositoryFailure.NotFound("exercise not found")
            if (exercise.type != ExerciseType.DISTANCE_TIME) {
                throw RepositoryFailure.Validation("exercise type is ${exercise.type} but DISTANCE_TIME execution recorded")
            }
            db.exerciseExecutionDao().insert(
                ExerciseExecutionEntity(
                    exerciseId = input.exerciseId,
                    performedAt = input.performedAt,
                    completedAt = input.completedAt,
                    durationSeconds = input.durationSeconds,
                    distanceMeters = input.distanceMeters
                )
            )
        }
    }

    override suspend fun deleteExecution(executionId: Long): Result<Unit> = write {
        db.withTransaction {
            val existing = db.exerciseExecutionDao().getById(executionId)
                ?: throw RepositoryFailure.NotFound("execution not found")
            db.exerciseExecutionDao().deleteSetsForExecution(executionId)
            db.exerciseExecutionDao().delete(existing)
        }
    }

    // T7.1: paged/window/stats reads. Limit normalizes into range; negative
    // offsets fail as Validation through the Flow error channel.
    override fun observeHistoryPage(query: HistoryQuery): Flow<List<ExerciseExecutionView>> {
        if (query.offset < 0) {
            return flowOfError<List<ExerciseExecutionView>>(
                RepositoryFailure.Validation("history offset must be >= 0")
            )
        }
        val limit = query.limit.coerceIn(1, HISTORY_MAX_ROWS)
        return db.exerciseExecutionDao()
            .observePage(query.exerciseId, query.fromWallClockMs, query.toWallClockMs, limit, query.offset)
            .map { list -> list.map { it.toView() } }
            .asRepositoryFlow()
    }

    override fun observeHistoryWindow(
        exerciseId: Long,
        fromWallClockMs: Long?,
        toWallClockMs: Long?
    ): Flow<List<ExerciseExecutionView>> =
        db.exerciseExecutionDao()
            .observePage(exerciseId, fromWallClockMs, toWallClockMs, HISTORY_MAX_ROWS, 0)
            .map { list -> list.map { it.toView() } }
            .asRepositoryFlow()

    override fun observeHistoryStats(exerciseId: Long): Flow<HistoryStats> =
        db.exerciseExecutionDao()
            .observeStats(exerciseId)
            .map { row ->
                HistoryStats(
                    exerciseId = exerciseId,
                    count = row.count,
                    firstPerformedAtWallClockMs = row.firstPerformedAt,
                    lastPerformedAtWallClockMs = row.lastPerformedAt
                )
            }
            .asRepositoryFlow()

    internal fun validateTimestamps(performedAt: Long, completedAt: Long?): RepositoryFailure? {
        if (performedAt <= 0) return RepositoryFailure.Validation("execution.performedAt must be positive")
        if (completedAt != null && completedAt < performedAt) {
            return RepositoryFailure.Validation("execution.completedAt must be >= performedAt")
        }
        return null
    }

    internal fun validateSets(sets: List<ExecutionSetInput>): RepositoryFailure? {
        if (sets.isEmpty()) return RepositoryFailure.Validation("SERIES execution requires at least one set")
        if (sets.map { it.setNumber }.sorted() != (1..sets.size).toList()) {
            return RepositoryFailure.Validation("setNumbers must start at 1 and be consecutive")
        }
        sets.forEach {
            if (it.reps <= 0) return RepositoryFailure.Validation("set.reps must be positive")
            if (it.weightKg != null && it.weightKg < 0) {
                return RepositoryFailure.Validation("set.weightKg must be >= 0")
            }
        }
        return null
    }

    private suspend fun <T> write(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e.toFailure())
    }

    private fun <T> Flow<T>.asRepositoryFlow(): Flow<T> = catch { e ->
        if (e is CancellationException) throw e
        throw e.toRepositoryException()
    }

    private fun <T> flowOfError(failure: RepositoryFailure): Flow<T> = flow {
        throw RepositoryException(failure)
    }

    private fun Throwable.toFailure(): RepositoryFailure = when (this) {
        is RepositoryFailure -> this
        is SQLiteConstraintException ->
            if ((message ?: "").contains("UNIQUE constraint failed")) {
                RepositoryFailure.Conflict("unique constraint violated")
            } else {
                RepositoryFailure.Validation("referenced record is invalid")
            }
        is SQLiteException -> RepositoryFailure.Storage("database unavailable")
        else -> RepositoryFailure.Storage("unexpected data error")
    }

    private fun Throwable.toRepositoryException(): RepositoryException = when (this) {
        is RepositoryException -> this
        is RepositoryFailure -> RepositoryException(this)
        is SQLiteException -> RepositoryException(RepositoryFailure.Storage("database unavailable"), this)
        else -> RepositoryException(RepositoryFailure.Storage("unexpected data error"), this)
    }
}

// Single mapping rule per type, shared by the implementation and unit-tested directly.
internal fun ExerciseExecutionWithSets.toView() = ExerciseExecutionView(
    id = execution.id,
    exerciseId = execution.exerciseId,
    performedAt = execution.performedAt,
    completedAt = execution.completedAt,
    durationSeconds = execution.durationSeconds,
    distanceMeters = execution.distanceMeters,
    sets = sets.map { it.toView() }
)

internal fun ExecutionSetEntity.toView() = ExecutionSetView(
    setNumber = setNumber,
    reps = reps,
    weightKg = weightKg
)
