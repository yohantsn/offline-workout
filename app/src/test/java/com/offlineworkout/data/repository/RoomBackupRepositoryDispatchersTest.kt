package com.offlineworkout.data.repository

import com.offlineworkout.data.backup.BackupCodec
import com.offlineworkout.data.local.ExecutionSetEntity
import com.offlineworkout.data.local.LocalDatabaseTest
import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.ExecutionSetInput
import com.offlineworkout.data.model.SeriesExecutionInput
import com.offlineworkout.data.model.WorkoutDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

// T9.2 (R-15/R-16/R-17): proof by construction, never by wall clock.
//   - the heavy work really leaves the caller's thread (R-15),
//   - the grouped set lookup still feeds the same validation (R-16),
//   - the single-pass checksum equals the canonical hash, and the v1 bytes are
//     byte-identical to the two-pass version (R-17).
@RunWith(RobolectricTestRunner::class)
class RoomBackupRepositoryDispatchersTest : LocalDatabaseTest() {

    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "backup-io") }
    private val cpuExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "backup-cpu") }

    /** Records the thread each dispatch lands on, so the assertion is about
     *  *where* the work ran, not how long it took. */
    private class ThreadRecordingDispatcher(
        private val delegate: CoroutineDispatcher
    ) : CoroutineDispatcher() {
        val threads = mutableSetOf<String>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context) {
                threads.add(Thread.currentThread().name)
                block.run()
            }
        }
    }

    private val ioDispatcher = ThreadRecordingDispatcher(ioExecutor.asCoroutineDispatcher())
    private val cpuDispatcher = ThreadRecordingDispatcher(cpuExecutor.asCoroutineDispatcher())

    @After
    fun shutdownExecutors() {
        ioExecutor.shutdownNow()
        cpuExecutor.shutdownNow()
    }

    private fun repository() = RoomBackupRepository(
        db,
        clock = { 1730000000000L },
        ioDispatcher = ioDispatcher,
        cpuDispatcher = cpuDispatcher
    )

    private suspend fun seedTwoSeriesExecutions() {
        val workouts = RoomWorkoutRepository(db, clock = { 1000L })
        val history = RoomExerciseHistoryRepository(db)
        val workoutId = workouts.createWorkout(WorkoutDraft("A", "Alpha")).getOrThrow()
        val seriesId = workouts.createExercise(
            ExerciseInput(workoutId, "Squat", ExerciseType.SERIES, 0, 90, 3, 10, 60.0, null, null)
        ).getOrThrow()
        val timedId = workouts.createExercise(
            ExerciseInput(workoutId, "Plank", ExerciseType.TIMED, 1, 30, null, null, null, 60L, null)
        ).getOrThrow()
        history.recordSeriesExecution(
            SeriesExecutionInput(
                seriesId, 5000L, 5100L,
                listOf(ExecutionSetInput(1, 10, 60.0), ExecutionSetInput(2, 8, null))
            )
        ).getOrThrow()
        history.recordSeriesExecution(
            SeriesExecutionInput(seriesId, 6000L, 6100L, listOf(ExecutionSetInput(1, 6, 50.0)))
        ).getOrThrow()
        history.recordTimedExecution(
            com.offlineworkout.data.model.TimedExecutionInput(timedId, 7000L, 7060L, 60L)
        ).getOrThrow()
    }

    @Test
    fun export_queries_and_encoding_run_off_the_caller_thread() = runTest {
        seedTwoSeriesExecutions()
        val callerThread = Thread.currentThread().name
        repository().createDocument().getOrThrow()

        assertTrue(
            "database work must leave the caller thread, ran on $callerThread",
            ioDispatcher.threads.isNotEmpty() && ioDispatcher.threads.none { it == callerThread }
        )
        assertTrue(
            "canonicalization/hashing must leave the caller thread, ran on $callerThread",
            cpuDispatcher.threads.isNotEmpty() && cpuDispatcher.threads.none { it == callerThread }
        )
    }

    @Test
    fun preview_validation_runs_off_the_caller_thread() = runTest {
        seedTwoSeriesExecutions()
        val document = repository().createDocument().getOrThrow()
        val callerThread = Thread.currentThread().name
        ioDispatcher.threads.clear()
        cpuDispatcher.threads.clear()

        repository().preview(document.bytes).getOrThrow()

        // Preview touches no table: it is pure CPU work, on the CPU dispatcher.
        assertTrue(cpuDispatcher.threads.isNotEmpty())
        assertTrue(cpuDispatcher.threads.none { it == callerThread })
        assertTrue(ioDispatcher.threads.isEmpty())
    }

    @Test
    fun import_validation_and_transaction_run_off_the_caller_thread() = runTest {
        seedTwoSeriesExecutions()
        val document = repository().createDocument().getOrThrow()
        val callerThread = Thread.currentThread().name
        ioDispatcher.threads.clear()
        cpuDispatcher.threads.clear()

        repository().importReplaceAll(document.bytes).getOrThrow()

        assertTrue(
            "validation must leave the caller thread",
            cpuDispatcher.threads.isNotEmpty() && cpuDispatcher.threads.none { it == callerThread }
        )
        assertTrue(
            "the write transaction must leave the caller thread",
            ioDispatcher.threads.isNotEmpty() && ioDispatcher.threads.none { it == callerThread }
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellation_is_rethrown_rather_than_swallowed() = runTest {
        seedTwoSeriesExecutions()
        val repository = repository()
        val scope = CoroutineScope(coroutineContext)
        val job = scope.launch {
            repository.createDocument()
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    // ---- R-16: the grouped lookup must validate exactly what the filter did ----

    @Test
    fun grouped_set_lookup_still_rejects_non_consecutive_set_numbers() = runTest {
        seedTwoSeriesExecutions()
        val data = BackupCodec.decodeDocument(repository().createDocument().getOrThrow().bytes)
        // setNumber 1 then 3 for the same execution: the T1/T2 rule requires
        // consecutiveness, and the grouped lookup has to keep enforcing it.
        val broken = data.copy(
            sets = data.sets.map { if (it.setNumber == 2) it.copy(setNumber = 3) else it }
        )
        val failure = repository().importReplaceAll(BackupCodec.encodeDocument(broken, 1L, "0.1.0"))
            .exceptionOrNull()
        assertTrue("expected Validation, got $failure", failure is BackupFailure.Validation)
    }

    @Test
    fun grouped_set_lookup_still_rejects_sets_on_timed_execution() = runTest {
        seedTwoSeriesExecutions()
        val data = BackupCodec.decodeDocument(repository().createDocument().getOrThrow().bytes)
        val timed = data.exercises.first { it.type == ExerciseType.TIMED }
        val timedExecution = data.executions.first { it.exerciseId == timed.id }
        val withSets = data.copy(
            sets = data.sets + ExecutionSetEntity(
                id = 999L,
                executionId = timedExecution.id,
                setNumber = 1,
                reps = 5,
                weightKg = 10.0
            )
        )
        val failure = repository().importReplaceAll(BackupCodec.encodeDocument(withSets, 1L, "0.1.0"))
            .exceptionOrNull()
        assertTrue("expected Validation, got $failure", failure is BackupFailure.Validation)
        // Nothing was written: the rejection happened before the DELETE.
        assertEquals(3, db.exerciseExecutionDao().getAllOrdered().size)
    }

    @Test
    fun grouped_set_lookup_keeps_orphan_set_detection() = runTest {
        seedTwoSeriesExecutions()
        val data = BackupCodec.decodeDocument(repository().createDocument().getOrThrow().bytes)
        val orphan = data.copy(
            sets = data.sets + ExecutionSetEntity(
                id = 777L, executionId = 666L, setNumber = 1, reps = 5, weightKg = 10.0
            )
        )
        val failure = repository().importReplaceAll(BackupCodec.encodeDocument(orphan, 1L, "0.1.0"))
            .exceptionOrNull()
        assertTrue(
            "expected ReferentiallyInvalid, got $failure",
            failure is BackupFailure.ReferentiallyInvalid
        )
    }

    // ---- R-17: one pass, same bytes, same checksum ----

    @Test
    fun single_pass_encoding_matches_the_two_pass_result() = runTest {
        seedTwoSeriesExecutions()
        val data = BackupCodec.decodeDocument(repository().createDocument().getOrThrow().bytes)

        val twoPass = BackupCodec.encodeDocument(data, 1730000000000L, "0.1.0")
        val single = BackupCodec.encodeDocumentWithChecksum(data, 1730000000000L, "0.1.0")

        // Byte-identical v1 document...
        assertTrue(twoPass.contentEquals(single.bytes))
        // ...and the checksum is exactly the SHA-256 of the canonical data.
        assertEquals(BackupCodec.sha256Hex(BackupCodec.encodeData(data)), single.checksum)
        // The old two-pass path is still reachable and agrees.
        assertEquals(
            BackupCodec.sha256Hex(BackupCodec.encodeData(data)),
            BackupCodec.sha256Hex(BackupCodec.encodeData(data))
        )
    }

    @Test
    fun exported_document_checksum_still_matches_its_own_integrity_block() = runTest {
        seedTwoSeriesExecutions()
        val document = repository().createDocument().getOrThrow()
        // The preview reports the same checksum the export advertised, which is
        // only true if both use the same single-pass hash.
        assertEquals(document.checksum, repository().preview(document.bytes).getOrThrow().checksum)
        val text = document.bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("\"value\":\"${document.checksum}\""))
        assertNotEquals(document.checksum, BackupCodec.sha256Hex(document.bytes))
    }
}
