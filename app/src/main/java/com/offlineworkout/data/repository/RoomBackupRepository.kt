package com.offlineworkout.data.repository

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.offlineworkout.BuildConfig
import com.offlineworkout.data.backup.BACKUP_MIME_TYPE
import com.offlineworkout.data.backup.BACKUP_SCHEMA_VERSION
import com.offlineworkout.data.backup.BackupCodec
import com.offlineworkout.data.backup.BackupDataDraft
import com.offlineworkout.data.backup.BackupDecodeError
import com.offlineworkout.data.backup.BackupDecodeException
import com.offlineworkout.data.backup.MAX_BACKUP_BYTES
import com.offlineworkout.data.local.AppDatabase
import com.offlineworkout.data.local.ExecutionSetEntity
import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExecutionSetInput
import com.offlineworkout.data.model.ExerciseType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// T8.1: Room backup implementation. Reads/writes through DAOs inside explicit
// transactions; validation reuses the approved T1/T2 domain validators
// (internal visibility, single rule source) before the first DELETE.
// Import is total replacement, parent-first, IDs preserved, no merge/remap.
//
// T9.2 (R-15): the heavy work used to run on the caller's dispatcher, which is
// Main.immediate for BackupViewModel, so the four export queries, the full
// validation, the canonical serialization and the SHA-256 all blocked the UI
// thread. Two dispatchers are injected and used for the two different kinds of
// work, on purpose:
//   - [ioDispatcher] for the database: Room queries and the write transaction.
//   - [cpuDispatcher] for canonicalization/hashing/validation: pure CPU, no
//     blocking, and it must not sit on the IO pool's threads.
// Both default to the real dispatchers and both are injectable so a test can
// prove the work left the caller's thread without a fragile timing assertion.
class RoomBackupRepository(
    private val db: AppDatabase,
    private val workouts: RoomWorkoutRepository = RoomWorkoutRepository(db),
    private val history: RoomExerciseHistoryRepository = RoomExerciseHistoryRepository(db),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default
) : BackupRepository {

    override suspend fun createDocument(): Result<BackupDocument> = writeBackup {
        val data = withContext(ioDispatcher) {
            db.withTransaction {
                // T8.3: the export order is part of the v1 file contract (T8.1
                // spec): workouts code ASC/id ASC, exercises workoutId/position/id,
                // executions exerciseId/then performedAt DESC, sets executionId/
                // setNumber/id. Each read is one query, so the arrays are emitted
                // in exactly that order regardless of insertion history.
                BackupDataDraft(
                    workouts = db.workoutDao().getAllOrdered(),
                    exercises = db.exerciseDao().getAllOrdered(),
                    executions = db.exerciseExecutionDao().getAllOrdered(),
                    sets = db.exerciseExecutionDao().getAllOrderedSets()
                )
            }
        }
        val now = clock()
        // T9.2 (R-17): one pass produces the bytes and the checksum; the
        // dataset is no longer serialized and hashed twice.
        val encoded = withContext(cpuDispatcher) {
            BackupCodec.encodeDocumentWithChecksum(data, now, BuildConfig.VERSION_NAME)
        }
        if (encoded.bytes.size > MAX_BACKUP_BYTES) {
            // Local data above the measured limit: nothing is written and the
            // user is told the export cannot be produced, not that it failed
            // silently.
            throw BackupFailure.Storage("backup above the technical size limit")
        }
        val fileName = "offline-workout-backup-${stamp(now)}.json"
        BackupDocument(
            fileName = fileName,
            mimeType = BACKUP_MIME_TYPE,
            bytes = encoded.bytes,
            schemaVersion = BACKUP_SCHEMA_VERSION,
            checksum = encoded.checksum
        )
    }

    override suspend fun preview(bytes: ByteArray): Result<BackupPreview> = writeBackup {
        val (data, checksum) = withContext(cpuDispatcher) { parseAndValidateWithChecksum(bytes) }
        BackupPreview(
            schemaVersion = BACKUP_SCHEMA_VERSION,
            checksum = checksum,
            workoutCount = data.workouts.size,
            exerciseCount = data.exercises.size,
            executionCount = data.executions.size,
            executionSetCount = data.sets.size,
            willReplaceAll = true
        )
    }

    override suspend fun importReplaceAll(bytes: ByteArray): Result<BackupImportResult> = writeBackup {
        // Validation still completes entirely before the first DELETE, and it
        // now runs off the caller's thread.
        val data = withContext(cpuDispatcher) { parseAndValidate(bytes) }
        withContext(ioDispatcher) {
            db.withTransaction {
                db.exerciseExecutionDao().deleteAllSets()
                db.exerciseExecutionDao().deleteAllExecutions()
                db.exerciseDao().deleteAll()
                db.workoutDao().deleteAll()
                data.workouts.forEach { db.workoutDao().insert(it) }
                data.exercises.forEach { db.exerciseDao().insert(it) }
                data.executions.forEach { db.exerciseExecutionDao().insert(it) }
                db.exerciseExecutionDao().insertAllSets(data.sets)
            }
        }
        BackupImportResult(
            workoutCount = data.workouts.size,
            exerciseCount = data.exercises.size,
            executionCount = data.executions.size,
            executionSetCount = data.sets.size
        )
    }

    // ---- validation (T1/T2 rules reused, nothing written before this returns)

    private fun parseAndValidate(bytes: ByteArray): BackupDataDraft {
        val data = BackupCodec.decodeDocument(bytes)
        validateIdentities(data)
        validateWorkouts(data)
        validateExercises(data)
        validateExecutions(data)
        return data
    }

    /**
     * Same validation as [parseAndValidate], plus the checksum of the canonical
     * `data` bytes. Reusing the already parsed tree avoids a second full
     * serialization just to report the hash in the preview.
     */
    private fun parseAndValidateWithChecksum(bytes: ByteArray): Pair<BackupDataDraft, String> {
        val data = parseAndValidate(bytes)
        return data to BackupCodec.sha256Hex(BackupCodec.encodeData(data))
    }

    private fun validateIdentities(data: BackupDataDraft) {
        fun dup(ids: List<Long>, what: String) {
            if (ids.size != ids.toSet().size) {
                throw BackupFailure.ReferentiallyInvalid("duplicate $what id")
            }
            if (ids.any { it <= 0 }) {
                throw BackupFailure.Validation("$what id must be positive")
            }
        }
        dup(data.workouts.map { it.id }, "workout")
        dup(data.exercises.map { it.id }, "exercise")
        dup(data.executions.map { it.id }, "execution")
        dup(data.sets.map { it.id }, "execution set")
    }

    private fun validateWorkouts(data: BackupDataDraft) {
        data.workouts.forEach { workout ->
            workouts.validateWorkoutFields(workout.code, workout.name)?.let { throw it.toBackupFailure() }
            if (workout.createdAt <= 0) throw BackupFailure.Validation("workout.createdAt must be positive")
        }
        val codes = data.workouts.map { it.code }
        if (codes.size != codes.toSet().size) {
            throw BackupFailure.Validation("workout.code must be unique")
        }
    }

    private fun validateExercises(data: BackupDataDraft) {
        val workoutIds = data.workouts.map { it.id }.toSet()
        data.exercises.forEach { exercise ->
            if (!workoutIds.contains(exercise.workoutId)) {
                throw BackupFailure.ReferentiallyInvalid("exercise ${exercise.id} references missing workout")
            }
            workouts.validateExerciseFields(
                ExerciseInput(
                    workoutId = exercise.workoutId,
                    name = exercise.name,
                    type = exercise.type,
                    position = exercise.position,
                    restSeconds = exercise.restSeconds,
                    targetSets = exercise.targetSets,
                    targetReps = exercise.targetReps,
                    targetWeightKg = exercise.targetWeightKg,
                    targetDurationSeconds = exercise.targetDurationSeconds,
                    targetDistanceMeters = exercise.targetDistanceMeters
                )
                    )?.let { throw it.toBackupFailure() }
        }
        data.exercises.groupBy { it.workoutId }.forEach { (workoutId, rows) ->
            val positions = rows.map { it.position }
            if (positions.size != positions.toSet().size) {
                throw BackupFailure.Validation("duplicate exercise position in workout $workoutId")
            }
        }
    }

    private fun validateExecutions(data: BackupDataDraft) {
        val exerciseById = data.exercises.associateBy { it.id }
        val executionIds = data.executions.map { it.id }.toSet()
        // T9.2 (R-16): the sets of an execution used to be found with
        // `data.sets.filter { ... }` inside this loop, which is O(executions ×
        // sets). The grouping is built once; membership, order (setNumber ASC,
        // id ASC — the order the file is validated in) and every message below
        // are unchanged.
        val setsByExecution: Map<Long, List<ExecutionSetEntity>> = data.sets.groupBy { it.executionId }
        data.executions.forEach { execution ->
            val exercise = exerciseById[execution.exerciseId]
                ?: throw BackupFailure.ReferentiallyInvalid(
                    "execution ${execution.id} references missing exercise"
                )
            history.validateTimestamps(execution.performedAt, execution.completedAt)?.let { throw it.toBackupFailure() }
            val sets = setsByExecution[execution.id].orEmpty()
            when (exercise.type) {
                ExerciseType.SERIES -> {
                    if (execution.durationSeconds != null || execution.distanceMeters != null) {
                        throw BackupFailure.Validation("SERIES execution ${execution.id} must not define duration/distance")
                    }
                    history.validateSets(
                        sets.map {
                            ExecutionSetInput(setNumber = it.setNumber, reps = it.reps, weightKg = it.weightKg)
                        }
            )?.let { throw it.toBackupFailure() }
                }
                ExerciseType.TIMED -> {
                    if (sets.isNotEmpty()) {
                        throw BackupFailure.Validation("TIMED execution ${execution.id} must not define sets")
                    }
                    if (execution.durationSeconds == null || execution.durationSeconds <= 0) {
                        throw BackupFailure.Validation("TIMED execution ${execution.id} requires durationSeconds > 0")
                    }
                    if (execution.distanceMeters != null) {
                        throw BackupFailure.Validation("TIMED execution ${execution.id} must not define distance")
                    }
                }
                ExerciseType.DISTANCE_TIME -> {
                    if (sets.isNotEmpty()) {
                        throw BackupFailure.Validation("DISTANCE_TIME execution ${execution.id} must not define sets")
                    }
                    if (execution.durationSeconds == null || execution.durationSeconds <= 0) {
                        throw BackupFailure.Validation("DISTANCE_TIME execution ${execution.id} requires durationSeconds > 0")
                    }
                    if (execution.distanceMeters == null || execution.distanceMeters < 0) {
                        throw BackupFailure.Validation("DISTANCE_TIME execution ${execution.id} requires distanceMeters >= 0")
                    }
                }
            }
        }
        data.sets.forEach { set ->
            if (!executionIds.contains(set.executionId)) {
                throw BackupFailure.ReferentiallyInvalid("set ${set.id} references missing execution")
            }
        }
    }

    private fun stamp(wallClockMs: Long): String {
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(wallClockMs))
    }

    private suspend fun <T> writeBackup(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackupFailure) {
        Result.failure(e)
    } catch (e: BackupDecodeException) {
        Result.failure(e.error.toFailure())
    } catch (e: RepositoryFailure) {
        Result.failure(e.toBackupFailure())
    } catch (e: OutOfMemoryError) {
        // T8.3: a hostile or oversized document must not kill the process; the
        // database is untouched because nothing was written outside the
        // transaction, which cannot start before validation finishes.
        Result.failure(BackupFailure.Corrupt("document could not be decoded safely"))
    } catch (e: StackOverflowError) {
        Result.failure(BackupFailure.Corrupt("document nesting is too deep"))
    } catch (e: SQLiteException) {
        Result.failure(BackupFailure.Storage("database unavailable"))
    } catch (e: Exception) {
        Result.failure(BackupFailure.Storage("unexpected data error"))
    }

    private fun BackupDecodeError.toFailure(): BackupFailure = when (this) {
        is BackupDecodeError.Corrupt -> BackupFailure.Corrupt(reason)
        is BackupDecodeError.UnsupportedVersion -> BackupFailure.UnsupportedVersion(version)
        is BackupDecodeError.ChecksumMismatch -> BackupFailure.ChecksumMismatch(expected, actual)
        is BackupDecodeError.Validation -> BackupFailure.Validation(reason)
        is BackupDecodeError.ReferentiallyInvalid -> BackupFailure.ReferentiallyInvalid(reason)
        is BackupDecodeError.TooLarge -> BackupFailure.Corrupt("backup is too large to import")
    }

    // The T1/T2 validators are shared with the write repositories and signal
    // with RepositoryFailure. An import must still report a backup-shaped
    // failure, so the kind is mapped instead of being reported as Storage.
    private fun RepositoryFailure.toBackupFailure(): BackupFailure = when (this) {
        is RepositoryFailure.Validation -> BackupFailure.Validation(message.orEmpty())
        is RepositoryFailure.Conflict -> BackupFailure.Validation(message.orEmpty())
        is RepositoryFailure.NotFound -> BackupFailure.ReferentiallyInvalid(message.orEmpty())
        is RepositoryFailure.Storage -> BackupFailure.Storage(message.orEmpty())
    }
}
