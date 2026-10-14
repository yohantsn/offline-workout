package com.offlineworkout.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.offlineworkout.data.local.AppDatabase
import com.offlineworkout.data.local.ExerciseEntity
import com.offlineworkout.data.local.WorkoutAggregateRow
import com.offlineworkout.data.local.WorkoutEntity
import com.offlineworkout.data.model.ExerciseAggregateDraft
import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.WorkoutAggregateDraft
import com.offlineworkout.data.model.WorkoutDraft
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.data.model.WorkoutUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

// T2.1: Room implementation of workout/exercise configuration. Owns validation,
// mapping (entities -> public models) and transactions. Touches
// exercise_executions/execution_sets only via schema CASCADE on delete.
class RoomWorkoutRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : WorkoutRepository {

    // T9.3 (R-6): single ordering source. Alphabetic by `code`, which is
    // Kotlin String comparison (UTF-16 code unit), so it is case- and
    // accent-sensitive: "Costas" sorts before "peito". Accepted behaviour, not
    // locale collation — see the test that documents it and the README.
    override fun observeWorkouts(): Flow<List<WorkoutSummary>> =
        db.workoutDao().observeAllAggregated()
            .map { list -> list.map { it.toSummary() }.sortedBy { it.code } }
            .asRepositoryFlow()

    override fun observeWorkout(id: Long): Flow<WorkoutSummary?> =
        db.workoutDao().observeByIdAggregated(id).map { it?.toSummary() }.asRepositoryFlow()

    override fun observeExercises(workoutId: Long): Flow<List<ExerciseSpec>> =
        db.workoutDao().observeExercises(workoutId).map { list -> list.map { it.toSpec() } }.asRepositoryFlow()

    override suspend fun createWorkout(input: WorkoutDraft): Result<Long> = write {
        validateWorkoutFields(input.code, input.name)?.let { throw it }
        val createdAt = clock()
        if (createdAt <= 0) throw RepositoryFailure.Validation("workout.createdAt must be positive")
        db.withTransaction {
            if (db.workoutDao().countByCode(input.code) > 0) {
                throw RepositoryFailure.Conflict("workout.code already exists")
            }
            db.workoutDao().insert(WorkoutEntity(code = input.code, name = input.name.trim(), createdAt = createdAt))
        }
    }

    override suspend fun updateWorkout(id: Long, input: WorkoutUpdate): Result<Unit> = write {
        validateWorkoutFields(input.code, input.name)?.let { throw it }
        db.withTransaction {
            val existing = db.workoutDao().getById(id) ?: throw RepositoryFailure.NotFound("workout not found")
            if (input.code != existing.code && db.workoutDao().countByCode(input.code) > 0) {
                throw RepositoryFailure.Conflict("workout.code already exists")
            }
            db.workoutDao().update(existing.copy(code = input.code, name = input.name.trim()))
        }
    }

    override suspend fun deleteWorkout(id: Long): Result<Unit> = write {
        db.withTransaction {
            val existing = db.workoutDao().getById(id) ?: throw RepositoryFailure.NotFound("workout not found")
            db.workoutDao().delete(existing)
        }
    }

    override suspend fun createExercise(input: ExerciseInput): Result<Long> = write {
        validateExerciseFields(input)?.let { throw it }
        db.withTransaction {
            db.workoutDao().getById(input.workoutId) ?: throw RepositoryFailure.NotFound("workout not found")
            if (db.exerciseDao().getForWorkout(input.workoutId).any { it.position == input.position }) {
                throw RepositoryFailure.Conflict("exercise position already used in workout")
            }
            db.exerciseDao().insert(input.toEntity())
        }
    }

    override suspend fun updateExercise(id: Long, input: ExerciseInput): Result<Unit> = write {
        validateExerciseFields(input)?.let { throw it }
        db.withTransaction {
            db.exerciseDao().getById(id) ?: throw RepositoryFailure.NotFound("exercise not found")
            db.workoutDao().getById(input.workoutId) ?: throw RepositoryFailure.NotFound("workout not found")
            if (db.exerciseDao().getForWorkout(input.workoutId).any { it.position == input.position && it.id != id }) {
                throw RepositoryFailure.Conflict("exercise position already used in workout")
            }
            db.exerciseDao().update(input.toEntity(id = id))
        }
    }

    override suspend fun deleteExercise(id: Long): Result<Unit> = write {
        db.withTransaction {
            val existing = db.exerciseDao().getById(id) ?: throw RepositoryFailure.NotFound("exercise not found")
            db.exerciseDao().delete(existing)
        }
    }

    override suspend fun reorderExercises(workoutId: Long, orderedExerciseIds: List<Long>): Result<Unit> = write {
        db.withTransaction {
            val current = db.exerciseDao().getForWorkout(workoutId)
            if (orderedExerciseIds.size != current.size ||
                orderedExerciseIds.toSet() != current.map { it.id }.toSet()
            ) {
                throw RepositoryFailure.Validation("reorder list must contain exactly the workout exercises once")
            }
            // Two phases with negative stand-ins so UNIQUE(workoutId, position)
            // can never be violated mid-reorder.
            val tempBase = -(current.size + 1)
            orderedExerciseIds.forEachIndexed { index, exerciseId ->
                val row = current.first { it.id == exerciseId }
                db.exerciseDao().updatePositions(listOf(row.copy(position = tempBase - index)))
            }
            orderedExerciseIds.forEachIndexed { index, exerciseId ->
                val row = current.first { it.id == exerciseId }
                db.exerciseDao().updatePositions(listOf(row.copy(position = index)))
            }
        }
    }

    // T9.2 (D-1): single source of truth for workout fields. `code` is free
    // text — the old A/B/C allow-list came from a missing Tasks.md and blocked
    // the fourth workout. Uniqueness is enforced by the unique index on
    // workouts.code (surfaced as Conflict), and the backup import reuses this
    // function, so it inherits the free-code rule without reimplementing it.
    internal fun validateWorkoutFields(code: String, name: String): RepositoryFailure? {
        if (code.isBlank()) return RepositoryFailure.Validation("workout.code must not be blank")
        if (name.isBlank()) return RepositoryFailure.Validation("workout.name must not be blank")
        return null
    }

    override suspend fun createWorkoutWithExercises(input: WorkoutAggregateDraft): Result<Long> = write {
        validateWorkoutFields(input.code, input.name)?.let { throw it }
        input.exercises.forEach { validateAggregateExercise(it)?.let { e -> throw e } }
        val createdAt = clock()
        if (createdAt <= 0) throw RepositoryFailure.Validation("workout.createdAt must be positive")
        db.withTransaction {
            if (db.workoutDao().countByCode(input.code) > 0) {
                throw RepositoryFailure.Conflict("workout.code already exists")
            }
            val workoutId = db.workoutDao().insert(
                WorkoutEntity(code = input.code, name = input.name.trim(), createdAt = createdAt)
            )
            input.exercises.forEachIndexed { index, draft ->
                if (draft.id != null) throw RepositoryFailure.Validation("new exercise must not carry an id")
                db.exerciseDao().insert(draft.toEntity(workoutId = workoutId, position = index))
            }
            workoutId
        }
    }

    // T1.3: grava N agregados em uma unica transacao, tudo ou nada.
    suspend fun createWorkoutsWithExercises(inputs: List<WorkoutAggregateDraft>): Result<List<Long>> = write {
        val createdAts = inputs.map { input ->
            validateWorkoutFields(input.code, input.name)?.let { throw it }
            input.exercises.forEach { draft ->
                if (draft.id != null) throw RepositoryFailure.Validation("new exercise must not carry an id")
                validateAggregateExercise(draft)?.let { e -> throw e }
            }
            val createdAt = clock()
            if (createdAt <= 0) throw RepositoryFailure.Validation("workout.createdAt must be positive")
            createdAt
        }
        db.withTransaction {
            val ids = mutableListOf<Long>()
            inputs.forEachIndexed { wi, input ->
                if (db.workoutDao().countByCode(input.code) > 0) {
                    throw RepositoryFailure.Conflict("workout.code already exists")
                }
                val workoutId = db.workoutDao().insert(
                    WorkoutEntity(code = input.code, name = input.name.trim(), createdAt = createdAts[wi])
                )
                val tempEntities = input.exercises.mapIndexed { ei, draft ->
                    draft.toEntity(workoutId = workoutId, position = -(input.exercises.size + 1) - ei)
                }
                val insertedIds = if (tempEntities.isEmpty()) emptyList() else db.exerciseDao().insertAll(tempEntities)
                if (insertedIds.isNotEmpty()) {
                    val finalEntities = insertedIds.mapIndexed { ei, id ->
                        tempEntities[ei].copy(id = id, position = ei)
                    }
                    db.exerciseDao().updatePositions(finalEntities)
                }
                ids.add(workoutId)
            }
            ids
        }
    }

    suspend fun createWorkouts(inputs: List<WorkoutAggregateDraft>): Result<List<Long>> =
        createWorkoutsWithExercises(inputs)

    suspend fun insertWorkouts(inputs: List<WorkoutAggregateDraft>): Result<List<Long>> =
        createWorkoutsWithExercises(inputs)

    suspend fun createWorkoutAggregates(inputs: List<WorkoutAggregateDraft>): Result<List<Long>> =
        createWorkoutsWithExercises(inputs)

    override suspend fun updateWorkoutWithExercises(workoutId: Long, input: WorkoutAggregateDraft): Result<Unit> = write {
        validateWorkoutFields(input.code, input.name)?.let { throw it }
        input.exercises.forEach { validateAggregateExercise(it)?.let { e -> throw e } }
        db.withTransaction {
            val existing = db.workoutDao().getById(workoutId) ?: throw RepositoryFailure.NotFound("workout not found")
            if (input.code != existing.code && db.workoutDao().countByCode(input.code) > 0) {
                throw RepositoryFailure.Conflict("workout.code already exists")
            }
            val current = db.exerciseDao().getForWorkout(workoutId)
            val currentIds = current.map { it.id }.toSet()
            val draftIds = input.exercises.mapNotNull { it.id }
            // Every existing id exactly once: rejects unknown, duplicated,
            // foreign and omitted ids (omission is never a silent delete).
            if (draftIds.size != draftIds.toSet().size || draftIds.toSet() != currentIds) {
                throw RepositoryFailure.Validation("edit list must contain each existing exercise id exactly once")
            }
            db.workoutDao().update(existing.copy(code = input.code, name = input.name.trim()))

            val byId = current.associateBy { it.id }
            // Phase 1: temp negative positions (disjoint ranges for existing/new)
            // so UNIQUE(workoutId, position) can never collide mid-write.
            val newRows = mutableListOf<ExerciseEntity>()
            input.exercises.forEachIndexed { index, draft ->
                if (draft.id == null) {
                    newRows.add(draft.toEntity(workoutId = workoutId, position = -(100_000 + index)))
                } else {
                    val row = byId.getValue(draft.id)
                    db.exerciseDao().updatePositions(listOf(draft.applyTo(row, position = -(index + 1))))
                }
            }
            val newIds = if (newRows.isEmpty()) emptyList() else db.exerciseDao().insertAll(newRows)
            // Phase 2: final positions follow the draft order (position = index).
            val newIdQueue = ArrayDeque(newIds)
            val orderedIds = input.exercises.map { it.id ?: newIdQueue.removeFirst() }
            val reloaded = db.exerciseDao().getForWorkout(workoutId).associateBy { it.id }
            orderedIds.forEachIndexed { index, id ->
                db.exerciseDao().updatePositions(listOf(reloaded.getValue(id).copy(position = index)))
            }
        }
    }

    private fun validateAggregateExercise(draft: ExerciseAggregateDraft): RepositoryFailure? {
        if (draft.name.isBlank()) return RepositoryFailure.Validation("exercise.name must not be blank")
        if (draft.restSeconds < 0) return RepositoryFailure.Validation("exercise.restSeconds must be >= 0")
        return validateTargets(
            type = draft.type,
            targetSets = draft.targetSets,
            targetReps = draft.targetReps,
            targetWeightKg = draft.targetWeightKg,
            targetDurationSeconds = draft.targetDurationSeconds,
            targetDistanceMeters = draft.targetDistanceMeters
        )
    }

    private fun ExerciseAggregateDraft.toEntity(workoutId: Long, position: Int) = ExerciseEntity(
        workoutId = workoutId,
        name = name.trim(),
        type = type,
        position = position,
        restSeconds = restSeconds,
        targetSets = targetSets,
        targetReps = targetReps,
        targetWeightKg = targetWeightKg,
        targetDurationSeconds = targetDurationSeconds,
        targetDistanceMeters = targetDistanceMeters
    )

    private fun ExerciseAggregateDraft.applyTo(row: ExerciseEntity, position: Int) = row.copy(
        name = name.trim(),
        type = type,
        position = position,
        restSeconds = restSeconds,
        targetSets = targetSets,
        targetReps = targetReps,
        targetWeightKg = targetWeightKg,
        targetDurationSeconds = targetDurationSeconds,
        targetDistanceMeters = targetDistanceMeters
    )

    internal fun validateExerciseFields(input: ExerciseInput): RepositoryFailure? {
        if (input.name.isBlank()) return RepositoryFailure.Validation("exercise.name must not be blank")
        if (input.position < 0) return RepositoryFailure.Validation("exercise.position must be >= 0")
        if (input.restSeconds < 0) return RepositoryFailure.Validation("exercise.restSeconds must be >= 0")
        return validateExerciseInputTargets(input)
    }

    private fun validateTargets(
        type: ExerciseType,
        targetSets: Int?,
        targetReps: Int?,
        targetWeightKg: Double?,
        targetDurationSeconds: Long?,
        targetDistanceMeters: Double?
    ): RepositoryFailure? = when (type) {
        ExerciseType.SERIES -> {
            if (targetSets == null || targetSets <= 0) {
                RepositoryFailure.Validation("SERIES requires targetSets > 0")
            } else if (targetReps == null || targetReps <= 0) {
                RepositoryFailure.Validation("SERIES requires targetReps > 0")
            } else if (targetWeightKg != null && targetWeightKg < 0) {
                RepositoryFailure.Validation("exercise.targetWeightKg must be >= 0")
            } else if (targetDurationSeconds != null || targetDistanceMeters != null) {
                RepositoryFailure.Validation("SERIES must not define duration/distance targets")
            } else {
                null
            }
        }
        ExerciseType.TIMED -> {
            if (targetDurationSeconds == null || targetDurationSeconds <= 0) {
                RepositoryFailure.Validation("TIMED requires targetDurationSeconds > 0")
            } else if (targetSets != null || targetReps != null || targetWeightKg != null || targetDistanceMeters != null) {
                RepositoryFailure.Validation("TIMED must only define targetDurationSeconds")
            } else {
                null
            }
        }
        ExerciseType.DISTANCE_TIME -> {
            if (targetDurationSeconds == null || targetDurationSeconds <= 0) {
                RepositoryFailure.Validation("DISTANCE_TIME requires targetDurationSeconds > 0")
            } else if (targetDistanceMeters == null || targetDistanceMeters < 0) {
                RepositoryFailure.Validation("DISTANCE_TIME requires targetDistanceMeters >= 0")
            } else if (targetSets != null || targetReps != null || targetWeightKg != null) {
                RepositoryFailure.Validation("DISTANCE_TIME must only define duration/distance targets")
            } else {
                null
            }
        }
    }

    private fun validateExerciseInputTargets(i: ExerciseInput): RepositoryFailure? = validateTargets(
        type = i.type,
        targetSets = i.targetSets,
        targetReps = i.targetReps,
        targetWeightKg = i.targetWeightKg,
        targetDurationSeconds = i.targetDurationSeconds,
        targetDistanceMeters = i.targetDistanceMeters
    )

    private fun ExerciseInput.toEntity(id: Long = 0) = ExerciseEntity(
        id = id,
        workoutId = workoutId,
        name = name.trim(),
        type = type,
        position = position,
        restSeconds = restSeconds,
        targetSets = targetSets,
        targetReps = targetReps,
        targetWeightKg = targetWeightKg,
        targetDurationSeconds = targetDurationSeconds,
        targetDistanceMeters = targetDistanceMeters
    )

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
internal fun WorkoutEntity.toSummary() = WorkoutSummary(id = id, code = code, name = name, createdAt = createdAt)

internal fun WorkoutAggregateRow.toSummary() = WorkoutSummary(
    id = id,
    code = code,
    name = name,
    createdAt = createdAt,
    exerciseCount = exerciseCount,
    totalRestSeconds = totalRestSeconds
)

internal fun ExerciseEntity.toSpec() = ExerciseSpec(
    id = id,
    workoutId = workoutId,
    name = name,
    type = type,
    position = position,
    restSeconds = restSeconds,
    targetSets = targetSets,
    targetReps = targetReps,
    targetWeightKg = targetWeightKg,
    targetDurationSeconds = targetDurationSeconds,
    targetDistanceMeters = targetDistanceMeters
)
