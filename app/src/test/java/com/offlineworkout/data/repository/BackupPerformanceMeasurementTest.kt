package com.offlineworkout.data.repository

import com.offlineworkout.data.backup.MAX_BACKUP_BYTES
import com.offlineworkout.data.local.LocalDatabaseTest
import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.SeriesExecutionInput
import com.offlineworkout.data.model.ExecutionSetInput
import com.offlineworkout.data.model.WorkoutDraft
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// T9.2 (R-15/R-16/R-17): reproducible export/import timing on a documented
// dataset. There is no sleep, and the *reported* numbers are not an assertion.
// T9.3: the test previously printed times and asserted nothing, so it could not
// catch a return of the O(executions × sets) validation. The budget below is a
// deliberate regression guard with a huge margin, not a performance gate: the
// O(n×m) path on this dataset takes minutes, the linear one well under a second.
@RunWith(RobolectricTestRunner::class)
class BackupPerformanceMeasurementTest : LocalDatabaseTest() {

    private fun repo() = RoomBackupRepository(db, clock = { 1730000000000L })

    private suspend fun seed(workouts: Int, exercises: Int, executions: Int) {
        val workoutRepo = RoomWorkoutRepository(db, clock = { 1000L })
        val history = RoomExerciseHistoryRepository(db)
        val codes = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")
        repeat(workouts) { w ->
            val workoutId = workoutRepo.createWorkout(
                WorkoutDraft(codes[w % codes.size], "Treino $w com nome razoavelmente longo")
            ).getOrThrow()
            repeat(exercises) { e ->
                val exerciseId = workoutRepo.createExercise(
                    ExerciseInput(
                        workoutId, "Exercício $e do treino $w", ExerciseType.SERIES, e, 90, 3, 12, 60.5, null, null
                    )
                ).getOrThrow()
                repeat(executions) { x ->
                    history.recordSeriesExecution(
                        SeriesExecutionInput(
                            exerciseId, 1_700_000_000_000L + x * 60_000L, 1_700_000_000_000L + x * 60_000L + 30_000L,
                            listOf(
                                ExecutionSetInput(1, 12, 60.5),
                                ExecutionSetInput(2, 10, 60.5),
                                ExecutionSetInput(3, 8, 60.0)
                            )
                        )
                    ).getOrThrow()
                }
            }
        }
    }

    private fun datasetLabel(workouts: Int, exercises: Int, executions: Int): String =
        "$workouts treinos x $exercises exercícios x $executions execuções x 3 séries " +
            "(${workouts * exercises} exercícios, ${workouts * exercises * executions} execuções, " +
            "${workouts * exercises * executions * 3} séries)"

    @Test
    fun measures_export_and_import() = runTest {
        // Heavy-but-plausible dataset: 8 treinos, 64 exercícios, ~4 executions
        // a day per exercise over a year.
        val w = 8
        val e = 8
        val x = 60
        seed(w, e, x)

        val exportTimes = mutableListOf<Long>()
        var document = repo().createDocument().getOrThrow()
        repeat(5) { run ->
            val t0 = System.nanoTime()
            document = repo().createDocument().getOrThrow()
            exportTimes.add((System.nanoTime() - t0) / 1_000_000)
        }

        val previewTimes = mutableListOf<Long>()
        repeat(5) {
            val t0 = System.nanoTime()
            repo().preview(document.bytes).getOrThrow()
            previewTimes.add((System.nanoTime() - t0) / 1_000_000)
        }

        val importTimes = mutableListOf<Long>()
        repeat(5) {
            val t0 = System.nanoTime()
            repo().importReplaceAll(document.bytes).getOrThrow()
            importTimes.add((System.nanoTime() - t0) / 1_000_000)
        }

        fun median(values: List<Long>) = values.sorted()[values.size / 2]
        println(
            "PERF ${datasetLabel(w, e, x)}: " +
                "documento=${document.bytes.size} bytes " +
                "(${(document.bytes.size * 100) / MAX_BACKUP_BYTES}% do limite); " +
                "export mediana=${median(exportTimes)} ms [${exportTimes.joinToString(",")}]; " +
                "preview mediana=${median(previewTimes)} ms [${previewTimes.joinToString(",")}]; " +
                "import mediana=${median(importTimes)} ms [${importTimes.joinToString(",")}]"
        )
        assertTrue("dataset must stay under the technical limit", document.bytes.size < MAX_BACKUP_BYTES)

        // T9.3 (R-16): a generous wall-clock budget. With the grouped lookup the
        // preview of 3,840 executions × 11,520 sets takes tens of milliseconds;
        // the old filter-in-loop would take minutes on the same data, so a 30s
        // ceiling catches that regression with a margin of ~1000x while being
        // immune to ordinary machine noise.
        val previewStarted = System.nanoTime()
        repo().preview(document.bytes).getOrThrow()
        val previewMillis = (System.nanoTime() - previewStarted) / 1_000_000
        assertTrue(
            "preview of ${document.bytes.size} bytes took ${previewMillis}ms: the grouped " +
                "set lookup regressed to O(executions x sets)?",
            previewMillis < 30_000
        )
    }
}
