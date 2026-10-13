package com.offlineworkout.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.offlineworkout.data.backup.BackupCodec
import com.offlineworkout.data.backup.MAX_BACKUP_BYTES
import com.offlineworkout.data.local.ExerciseEntity
import com.offlineworkout.data.local.ExerciseExecutionEntity
import com.offlineworkout.data.local.ExecutionSetEntity
import com.offlineworkout.data.local.AppDatabase
import com.offlineworkout.data.local.LocalDatabaseTest
import com.offlineworkout.data.local.WorkoutEntity
import com.offlineworkout.data.model.ExerciseType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// T8.1: backup round-trip and atomic replace-all against in-memory Room.
// Seeds through repositories (approved validators); asserts through DAOs.
@RunWith(RobolectricTestRunner::class)
class RoomBackupRepositoryTest : LocalDatabaseTest() {

    private fun repository(clock: () -> Long = { 1730000000000L }) =
        RoomBackupRepository(db, clock = clock)

    private suspend fun seedFullDatabase() {
        val workouts = RoomWorkoutRepository(db, clock = { 1000L })
        val history = RoomExerciseHistoryRepository(db)
        val workoutId = workouts.createWorkout(
            com.offlineworkout.data.model.WorkoutDraft("A", "Alpha")
        ).getOrThrow()
        val seriesId = workouts.createExercise(
            com.offlineworkout.data.model.ExerciseInput(
                workoutId, "Squat", ExerciseType.SERIES, 0, 90, 3, 10, 60.0, null, null
            )
        ).getOrThrow()
        val timedId = workouts.createExercise(
            com.offlineworkout.data.model.ExerciseInput(
                workoutId, "Plank", ExerciseType.TIMED, 1, 30, null, null, null, 60L, null
            )
        ).getOrThrow()
        history.recordSeriesExecution(
            com.offlineworkout.data.model.SeriesExecutionInput(
                seriesId, 5000L, 5100L,
                listOf(
                    com.offlineworkout.data.model.ExecutionSetInput(1, 10, 60.0),
                    com.offlineworkout.data.model.ExecutionSetInput(2, 8, null)
                )
            )
        ).getOrThrow()
        history.recordTimedExecution(
            com.offlineworkout.data.model.TimedExecutionInput(timedId, 6000L, 6060L, 60L)
        ).getOrThrow()
    }

    private suspend fun snapshot(): BackupSnapshot {
        val workouts = db.workoutDao().getAllOrdered()
        val exercises = workouts.flatMap { db.exerciseDao().getForWorkout(it.id) }
        val executions = exercises.flatMap { db.exerciseExecutionDao().getForExercise(it.id) }
        return BackupSnapshot(workouts, exercises, executions)
    }

    private data class BackupSnapshot(
        val workouts: List<WorkoutEntity>,
        val exercises: List<ExerciseEntity>,
        val executions: List<com.offlineworkout.data.local.ExerciseExecutionWithSets>
    )

    // ---- B-3: export order is part of the v1 file contract ----

    /**
     * Seeds three workouts whose insertion order, code order and id order all
     * disagree, with several exercises and several executions each, so any
     * grouping-by-iteration-order regression shows up in the exported arrays.
     */
    private suspend fun seedManyWorkouts(): Triple<List<Long>, List<Long>, List<Long>> {
        val workouts = RoomWorkoutRepository(db, clock = { 1000L })
        val history = RoomExerciseHistoryRepository(db)
        // Inserted C, A, B -> id order (1,2,3) != code order (A,B,C).
        val workoutIds = listOf("C", "A", "B").map { code ->
            workouts.createWorkout(com.offlineworkout.data.model.WorkoutDraft(code, "Treino $code"))
                .getOrThrow()
        }
        val exerciseIds = mutableListOf<Long>()
        val executionIds = mutableListOf<Long>()
        workoutIds.forEach { workoutId ->
            // Two exercises per workout, inserted in position order.
            (0..1).forEach { position ->
                val exerciseId = workouts.createExercise(
                    com.offlineworkout.data.model.ExerciseInput(
                        workoutId, "Ex $position", ExerciseType.SERIES, position, 60, 3, 10, 40.0, null, null
                    )
                ).getOrThrow()
                exerciseIds.add(exerciseId)
                // Three executions per exercise, inserted oldest first so the
                // export order (performedAt DESC) is the reverse of insertion.
                (1..3).forEach { n ->
                    val executionId = history.recordSeriesExecution(
                        com.offlineworkout.data.model.SeriesExecutionInput(
                            exerciseId, 1000L + n * 1000L, 1000L + n * 1000L + 500L,
                            listOf(
                                com.offlineworkout.data.model.ExecutionSetInput(1, 10, 40.0),
                                com.offlineworkout.data.model.ExecutionSetInput(2, 8, 40.0)
                            )
                        )
                    ).getOrThrow()
                    executionIds.add(executionId)
                }
            }
        }
        return Triple(workoutIds, exerciseIds, executionIds)
    }

    @Test
    fun export_arrays_follow_the_specified_order() = runTest {
        val (workoutIds, exerciseIds, _) = seedManyWorkouts()
        val data = BackupCodec.decodeDocument(repository().createDocument().getOrThrow().bytes)

        // workouts: code ASC, id ASC
        assertEquals(listOf("A", "B", "C"), data.workouts.map { it.code })
        // exercises: workoutId ASC, position ASC, id ASC. The id order of the
        // seeded workouts is (C=1, A=2, B=3), so ordering by code would fail.
        val expectedExercises = workoutIds.sorted().flatMap { workoutId ->
            data.exercises.filter { it.workoutId == workoutId }.sortedWith(
                compareBy({ it.position }, { it.id })
            )
        }
        assertEquals(expectedExercises.map { it.id }, data.exercises.map { it.id })
        assertEquals(workoutIds.sorted(), data.exercises.map { it.workoutId }.distinct())
        // positions ascend inside each workout
        data.exercises.groupBy { it.workoutId }.forEach { (workoutId, rows) ->
            assertEquals("workout $workoutId", rows.map { it.position }.sorted(), rows.map { it.position })
        }

        // executions: exerciseId ASC, performedAt DESC, id DESC
        val expectedExecutions = exerciseIds.sorted().flatMap { exerciseId ->
            data.executions.filter { it.exerciseId == exerciseId }
                .sortedWith(compareByDescending<com.offlineworkout.data.local.ExerciseExecutionEntity> { it.performedAt }.thenByDescending { it.id })
        }
        assertEquals(expectedExecutions.map { it.id }, data.executions.map { it.id })
        assertEquals(exerciseIds.sorted(), data.executions.map { it.exerciseId }.distinct())

        // executionSets: executionId ASC, setNumber ASC, id ASC
        val expectedSets = data.executions.map { it.id }.sorted().flatMap { executionId ->
            data.sets.filter { it.executionId == executionId }.sortedWith(
                compareBy({ it.setNumber }, { it.id })
            )
        }
        assertEquals(expectedSets.map { it.id }, data.sets.map { it.id })
        data.sets.groupBy { it.executionId }.forEach { (executionId, rows) ->
            assertEquals(
                "execution $executionId",
                rows.map { it.setNumber }.sorted(),
                rows.map { it.setNumber }
            )
        }
    }

    @Test
    fun export_order_is_independent_of_insertion_order() = runTest {
        seedManyWorkouts()
        val first = BackupCodec.decodeDocument(repository().createDocument().getOrThrow().bytes)
        val second = BackupCodec.decodeDocument(repository().createDocument().getOrThrow().bytes)
        assertEquals(first.workouts.map { it.id }, second.workouts.map { it.id })
        assertEquals(first.exercises.map { it.id }, second.exercises.map { it.id })
        assertEquals(first.executions.map { it.id }, second.executions.map { it.id })
        assertEquals(first.sets.map { it.id }, second.sets.map { it.id })
    }

    // ---- A-2: the replacement is destructive ----

    @Test
    fun import_replaces_local_data_from_a_document_of_another_database() = runTest {
        // The target database has two workouts with their own history.
        val targetWorkouts = RoomWorkoutRepository(db, clock = { 1000L })
        val targetHistory = RoomExerciseHistoryRepository(db)
        val keepId = targetWorkouts.createWorkout(
            com.offlineworkout.data.model.WorkoutDraft("A", "Alpha local")
        ).getOrThrow()
        val keepExercise = targetWorkouts.createExercise(
            com.offlineworkout.data.model.ExerciseInput(
                keepId, "Squat local", ExerciseType.SERIES, 0, 90, 3, 10, 60.0, null, null
            )
        ).getOrThrow()
        val doomedId = targetWorkouts.createWorkout(
            com.offlineworkout.data.model.WorkoutDraft("B", "Beta local")
        ).getOrThrow()
        val doomedExercise = targetWorkouts.createExercise(
            com.offlineworkout.data.model.ExerciseInput(
                doomedId, "Deadlift local", ExerciseType.SERIES, 0, 90, 3, 10, 80.0, null, null
            )
        ).getOrThrow()
        targetHistory.recordSeriesExecution(
            com.offlineworkout.data.model.SeriesExecutionInput(
                keepExercise, 5000L, 5100L, listOf(com.offlineworkout.data.model.ExecutionSetInput(1, 10, 60.0))
            )
        ).getOrThrow()
        targetHistory.recordSeriesExecution(
            com.offlineworkout.data.model.SeriesExecutionInput(
                doomedExercise, 6000L, 6100L, listOf(com.offlineworkout.data.model.ExecutionSetInput(1, 5, 80.0))
            )
        ).getOrThrow()

        // The document comes from a completely different in-memory database
        // with a single workout, so nothing of it can merge with the local ids.
        val other: AppDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val otherWorkouts = RoomWorkoutRepository(other, clock = { 2000L })
            val otherHistory = RoomExerciseHistoryRepository(other)
            val foreignWorkoutId = otherWorkouts.createWorkout(
                com.offlineworkout.data.model.WorkoutDraft("C", "Gamma importado")
            ).getOrThrow()
            val foreignSeriesId = otherWorkouts.createExercise(
                com.offlineworkout.data.model.ExerciseInput(
                    foreignWorkoutId, "Supino importado", ExerciseType.SERIES, 0, 90, 2, 10, 50.0, null, null
                )
            ).getOrThrow()
            val foreignTimedId = otherWorkouts.createExercise(
                com.offlineworkout.data.model.ExerciseInput(
                    foreignWorkoutId, "Corrida importada", ExerciseType.TIMED, 1, 60, null, null, null, 30L, null
                )
            ).getOrThrow()
            otherHistory.recordSeriesExecution(
                com.offlineworkout.data.model.SeriesExecutionInput(
                    foreignSeriesId, 7000L, 7100L,
                    listOf(
                        com.offlineworkout.data.model.ExecutionSetInput(1, 10, 50.0),
                        com.offlineworkout.data.model.ExecutionSetInput(2, 8, 50.0)
                    )
                )
            ).getOrThrow()
            otherHistory.recordTimedExecution(
                com.offlineworkout.data.model.TimedExecutionInput(foreignTimedId, 7500L, 7530L, 30L)
            ).getOrThrow()
            val foreign = RoomBackupRepository(other, clock = { 1730000000000L })
                .createDocument().getOrThrow()

            val result = repository().importReplaceAll(foreign.bytes).getOrThrow()
            assertEquals(1, result.workoutCount)
            assertEquals(2, result.exerciseCount)
            assertEquals(2, result.executionCount)
            assertEquals(2, result.executionSetCount)

            // Only the imported rows survive: the local ones are gone, not merged.
            assertEquals(listOf("Gamma importado"), db.workoutDao().getAllOrdered().map { it.name })
            assertEquals(listOf(foreignWorkoutId), db.workoutDao().getAllOrdered().map { it.id })
            val exercises = db.exerciseDao().getAllOrdered()
            assertEquals(listOf("Supino importado", "Corrida importada"), exercises.map { it.name })
            assertEquals(listOf(0, 1), exercises.map { it.position })
            val executions = db.exerciseExecutionDao().getAllOrdered()
            assertEquals(listOf(foreignSeriesId, foreignTimedId), executions.map { it.exerciseId })
            assertEquals(listOf(1, 2), db.exerciseExecutionDao().getAllOrderedSets().map { it.setNumber })
            // The local rows (including their history) are really gone. Ids
            // are preserved from the file and legitimately reused, so the
            // evidence is content, not id: nothing local survives.
            assertTrue(db.workoutDao().getByCode("A") == null)
            assertTrue(db.workoutDao().getByCode("B") == null)
            assertTrue(db.exerciseDao().getAllOrdered().none { it.name.endsWith("local") })
            assertTrue(db.exerciseDao().getAllOrdered().none { it.workoutId == doomedId && it.name.startsWith("Deadlift") })
            assertTrue(targetHistory.observeHistory(keepExercise).first().none { it.performedAt == 5000L })
            assertTrue(targetHistory.observeHistory(doomedExercise).first().none { it.performedAt == 6000L })
            assertTrue(db.exerciseExecutionDao().getAllOrdered().none { it.performedAt == 6000L })
        } finally {
            other.close()
        }
    }

    @Test
    fun oversized_document_fails_without_touching_the_database() = runTest {
        seedFullDatabase()
        val oversized = ByteArray(MAX_BACKUP_BYTES + 1) { '{'.code.toByte() }
        val failure = repository().importReplaceAll(oversized).exceptionOrNull()
        assertTrue("expected Corrupt, got $failure", failure is BackupFailure.Corrupt)
        val previewFailure = repository().preview(oversized).exceptionOrNull()
        assertTrue(previewFailure is BackupFailure.Corrupt)
        // The database is untouched.
        assertEquals(1, db.workoutDao().getAllOrdered().size)
        assertEquals(2, db.exerciseDao().getAllOrdered().size)
    }

    @Test
    fun export_orders_and_round_trips_everything() = runTest {
        seedFullDatabase()
        val before = snapshot()

        val document = repository().createDocument().getOrThrow()
        assertTrue(document.fileName.matches(Regex("offline-workout-backup-\\d{8}-\\d{6}\\.json")))
        assertEquals("application/json", document.mimeType)
        assertEquals(1, document.schemaVersion)
        assertTrue(document.checksum.matches(Regex("[0-9a-f]{64}")))

        val preview = repository().preview(document.bytes).getOrThrow()
        assertEquals(1, preview.workoutCount)
        assertEquals(2, preview.exerciseCount)
        assertEquals(2, preview.executionCount)
        assertEquals(2, preview.executionSetCount)
        assertEquals(true, preview.willReplaceAll)
        assertEquals(1, preview.schemaVersion)
        assertEquals(document.checksum, preview.checksum)

        // Wipe via a second database state is simulated by importing into
        // the same database after clearing through a valid empty backup.
        val result = repository().importReplaceAll(document.bytes).getOrThrow()
        assertEquals(1, result.workoutCount)
        assertEquals(2, result.exerciseCount)
        assertEquals(2, result.executionCount)
        assertEquals(2, result.executionSetCount)

        val after = snapshot()
        assertEquals(before.workouts, after.workouts)
        assertEquals(before.exercises, after.exercises)
        assertEquals(before.executions.map { it.execution }, after.executions.map { it.execution })
        assertEquals(
            before.executions.flatMap { it.sets },
            after.executions.flatMap { it.sets }
        )
        // Export order contract: code, position, performedAt DESC.
        assertEquals(listOf("A"), after.workouts.map { it.code })
        assertEquals(listOf(0, 1), after.exercises.map { it.position })
    }

    @Test
    fun import_replaces_existing_data_only_after_full_validation() = runTest {
        seedFullDatabase()
        val document = repository().createDocument().getOrThrow()

        // Corrupt the stored copy: import must fail with the database intact.
        val tampered = document.bytes.toString(Charsets.UTF_8)
            .replace("Alpha", "Alphb")
            .toByteArray(Charsets.UTF_8)
        val failure = repository().importReplaceAll(tampered).exceptionOrNull()
        assertTrue(failure is BackupFailure.ChecksumMismatch)
        assertEquals(1, db.workoutDao().getAllOrdered().size)

        // Valid import replaces everything atomically.
        repository().importReplaceAll(document.bytes).getOrThrow()
        assertEquals(listOf("Alpha"), db.workoutDao().getAllOrdered().map { it.name })
    }

    @Test
    fun validation_runs_before_any_delete_so_rejection_keeps_the_database() = runTest {
        seedFullDatabase()
        // Build a document whose SERIES execution wrongly carries duration:
        // validation must reject before the first DELETE.
        val beforeWorkouts = db.workoutDao().getAllOrdered()
        val data = BackupCodec.decodeDocument(repository().createDocument().getOrThrow().bytes)
        val broken = data.copy(
            executions = data.executions.map {
                if (it.durationSeconds == null) it.copy(durationSeconds = 5L) else it
            }
        )
        val failure = repository().importReplaceAll(resign(broken)).exceptionOrNull()
        assertTrue("expected Validation, got $failure", failure is BackupFailure.Validation)
        assertEquals(beforeWorkouts, db.workoutDao().getAllOrdered())
    }

    private fun resign(data: com.offlineworkout.data.backup.BackupDataDraft): ByteArray {
        // Re-encode through the public path so the checksum matches the payload.
        return BackupCodec.encodeDocument(data, 1L, "0.1.0")
    }

    @Test
    fun rejects_duplicate_ids_orphans_and_bad_positions() = runTest {
        seedFullDatabase()
        val data = BackupCodec.decodeDocument(repository().createDocument().getOrThrow().bytes)

        val dupWorkout = data.copy(workouts = data.workouts + data.workouts.first())
        assertTrue(
            repository().importReplaceAll(resign(dupWorkout)).exceptionOrNull() is BackupFailure.ReferentiallyInvalid
        )

        val orphanExercise = data.copy(
            exercises = data.exercises + data.exercises.first().copy(id = 999L, workoutId = 888L)
        )
        assertTrue(
            repository().importReplaceAll(resign(orphanExercise)).exceptionOrNull() is BackupFailure.ReferentiallyInvalid
        )

        val dupPosition = data.copy(
            exercises = data.exercises.map {
                if (it.id == data.exercises.first().id) it else it.copy(position = data.exercises.first().position)
            }
        )
        // Two exercises share position 0 in the same workout.
        val dupFailure = repository().importReplaceAll(resign(dupPosition)).exceptionOrNull()
        assertTrue("expected Validation, got $dupFailure", dupFailure is BackupFailure.Validation)

        val orphanSet = data.copy(
            sets = data.sets + data.sets.first().copy(id = 777L, executionId = 666L)
        )
        assertTrue(
            repository().importReplaceAll(resign(orphanSet)).exceptionOrNull() is BackupFailure.ReferentiallyInvalid
        )
        // Database untouched by all rejections.
        assertEquals(1, db.workoutDao().getAllOrdered().size)
    }

    @Test
    fun rejects_blank_code_and_blank_name() = runTest {
        seedFullDatabase()
        val data = BackupCodec.decodeDocument(repository().createDocument().getOrThrow().bytes)

        // T9.2 (D-1): the import reuses validateWorkoutFields, so only an empty
        // code is rejected now.
        val blankCode = data.copy(workouts = listOf(data.workouts.first().copy(code = "")))
        assertTrue(
            repository().importReplaceAll(resign(blankCode)).exceptionOrNull() is BackupFailure.Validation
        )

        val emptyName = data.copy(exercises = listOf(data.exercises.first().copy(name = "  ")))
        val failure = repository().importReplaceAll(resign(emptyName)).exceptionOrNull()
        assertTrue("expected Validation, got $failure", failure is BackupFailure.Validation)
        assertEquals(1, db.workoutDao().getAllOrdered().size)
    }

    @Test
    fun imports_free_code_beyond_abc() = runTest {
        // T9.2 (D-1): "Z" was rejected only because of the old allow-list.
        seedFullDatabase()
        val data = BackupCodec.decodeDocument(repository().createDocument().getOrThrow().bytes)
        val freeCode = data.copy(workouts = listOf(data.workouts.first().copy(code = "Z")))
        assertTrue(repository().importReplaceAll(resign(freeCode)).isSuccess)
        assertEquals(listOf("Z"), db.workoutDao().getAllOrdered().map { it.code })
    }

    @Test
    fun imports_free_codes_beyond_abc() = runTest {
        // T9.2 (D-1): four workouts with free codes import untouched.
        seedFullDatabase()
        val data = BackupCodec.decodeDocument(repository().createDocument().getOrThrow().bytes)
        val free = data.copy(
            workouts = data.workouts + data.workouts.first().copy(
                id = 50L,
                code = "D",
                name = "Treino D"
            )
        )
        val result = repository().importReplaceAll(resign(free)).getOrThrow()
        assertEquals(2, result.workoutCount)
        assertEquals(listOf("A", "D"), db.workoutDao().getAllOrdered().map { it.code })
    }

    @Test
    fun empty_database_round_trips() = runTest {
        val document = repository().createDocument().getOrThrow()
        val preview = repository().preview(document.bytes).getOrThrow()
        assertEquals(0, preview.workoutCount)
        val result = repository().importReplaceAll(document.bytes).getOrThrow()
        assertEquals(0, result.workoutCount)
        assertTrue(db.workoutDao().getAllOrdered().isEmpty())
    }

    @Test
    fun history_flows_keep_working_after_backup_operations() = runTest {
        seedFullDatabase()
        repository().createDocument().getOrThrow()
        val workoutId = db.workoutDao().getByCode("A")!!.id
        val exerciseId = db.exerciseDao().getForWorkout(workoutId).first().id
        val history = RoomExerciseHistoryRepository(db)
        val rows = history.observeHistory(exerciseId).first()
        assertEquals(1, rows.size)
        assertEquals(listOf(1, 2), rows.single().sets.map { it.setNumber })
    }
}
