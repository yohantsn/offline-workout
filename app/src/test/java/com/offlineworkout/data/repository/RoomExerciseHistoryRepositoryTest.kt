package com.offlineworkout.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.offlineworkout.data.local.AppDatabase
import com.offlineworkout.data.model.DistanceTimeExecutionInput
import com.offlineworkout.data.model.ExecutionSetInput
import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.SeriesExecutionInput
import com.offlineworkout.data.model.TimedExecutionInput
import com.offlineworkout.data.model.WorkoutDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// T2.1: RoomExerciseHistoryRepository against an in-memory database.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomExerciseHistoryRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var workouts: RoomWorkoutRepository
    private lateinit var repo: RoomExerciseHistoryRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        workouts = RoomWorkoutRepository(db, clock = { 1000L })
        repo = RoomExerciseHistoryRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seriesExercise(): Long {
        val workoutId = workouts.createWorkout(WorkoutDraft("A", "A")).getOrThrow()
        return workouts.createExercise(
            ExerciseInput(
                workoutId, "Squat", ExerciseType.SERIES, 0, 90, 3, 10, 60.0, null, null
            )
        ).getOrThrow()
    }

    private suspend fun timedExercise(): Long {
        val workoutId = workouts.createWorkout(WorkoutDraft("B", "B")).getOrThrow()
        return workouts.createExercise(
            ExerciseInput(
                workoutId, "Plank", ExerciseType.TIMED, 0, 30, null, null, null, 60L, null
            )
        ).getOrThrow()
    }

    @Test
    fun record_series_persists_execution_and_sets_atomically() = runTest {
        val exerciseId = seriesExercise()
        val id = repo.recordSeriesExecution(
            SeriesExecutionInput(
                exerciseId, performedAt = 2000L, completedAt = 2100L,
                sets = listOf(
                    ExecutionSetInput(1, reps = 10, weightKg = 60.0),
                    ExecutionSetInput(2, reps = 8, weightKg = null)
                )
            )
        ).getOrThrow()

        val history = repo.observeHistory(exerciseId).first()
        assertEquals(1, history.size)
        assertEquals(id, history.single().id)
        assertEquals(listOf(1, 2), history.single().sets.map { it.setNumber })
        assertEquals(id, repo.observeLatestExecution(exerciseId).first()?.id)
        val latestSet = repo.observeLatestSet(exerciseId).first()
        assertEquals(2, latestSet?.setNumber)
        assertNull(latestSet?.weightKg)
    }

    @Test
    fun invalid_series_leaves_no_partial_execution() = runTest {
        val exerciseId = seriesExercise()
        // Non-consecutive set numbers fail before any insert.
        val failure = repo.recordSeriesExecution(
            SeriesExecutionInput(
                exerciseId, performedAt = 2000L, completedAt = null,
                sets = listOf(ExecutionSetInput(1, 10, null), ExecutionSetInput(3, 8, null))
            )
        ).exceptionOrNull()
        assertTrue(failure is RepositoryFailure.Validation)
        assertTrue(repo.observeHistory(exerciseId).first().isEmpty())

        // Zero reps also fail with nothing persisted.
        val badReps = repo.recordSeriesExecution(
            SeriesExecutionInput(
                exerciseId, performedAt = 2000L, completedAt = null,
                sets = listOf(ExecutionSetInput(1, 0, null))
            )
        ).exceptionOrNull()
        assertTrue(badReps is RepositoryFailure.Validation)
        assertTrue(repo.observeHistory(exerciseId).first().isEmpty())
    }

    @Test
    fun series_on_wrong_type_is_rejected() = runTest {
        val timedId = timedExercise()
        val failure = repo.recordSeriesExecution(
            SeriesExecutionInput(
                timedId, performedAt = 1000L, completedAt = null,
                sets = listOf(ExecutionSetInput(1, 10, null))
            )
        ).exceptionOrNull()
        assertTrue(failure is RepositoryFailure.Validation)
    }

    @Test
    fun timed_and_distance_rules() = runTest {
        val timedId = timedExercise()
        assertTrue(
            repo.recordTimedExecution(TimedExecutionInput(timedId, 1000L, null, 0L))
                .exceptionOrNull() is RepositoryFailure.Validation
        )
        val timedExec = repo.recordTimedExecution(TimedExecutionInput(timedId, 1000L, 1060L, 60L)).getOrThrow()
        assertEquals(timedExec, repo.observeLatestExecution(timedId).first()?.id)
        // TIMED has no sets: latest set is null.
        assertNull(repo.observeLatestSet(timedId).first())

        val workoutId = workouts.createWorkout(WorkoutDraft("C", "C")).getOrThrow()
        val runId = workouts.createExercise(
            ExerciseInput(workoutId, "Run", ExerciseType.DISTANCE_TIME, 0, 0, null, null, null, 600L, 1000.0)
        ).getOrThrow()
        assertTrue(
            repo.recordDistanceTimeExecution(DistanceTimeExecutionInput(runId, 1000L, null, 600L, -1.0))
                .exceptionOrNull() is RepositoryFailure.Validation
        )
        repo.recordDistanceTimeExecution(DistanceTimeExecutionInput(runId, 1000L, 1600L, 600L, 1000.0)).getOrThrow()
        assertEquals(1000.0, repo.observeLatestExecution(runId).first()?.distanceMeters)
    }

    @Test
    fun bad_timestamps_and_missing_exercise() = runTest {
        val exerciseId = seriesExercise()
        val badOrder = repo.recordTimedExecution(TimedExecutionInput(exerciseId, 2000L, 1000L, 60L))
        assertTrue(badOrder.exceptionOrNull() is RepositoryFailure.Validation)
        val missing = repo.recordSeriesExecution(
            SeriesExecutionInput(
                999L, performedAt = 1000L, completedAt = null,
                sets = listOf(ExecutionSetInput(1, 10, null))
            )
        )
        assertTrue(missing.exceptionOrNull() is RepositoryFailure.NotFound)
    }

    @Test
    fun history_ordering_and_delete_execution() = runTest {
        val exerciseId = seriesExercise()
        val first = repo.recordSeriesExecution(
            SeriesExecutionInput(
                exerciseId, 1000L, 1100L, listOf(ExecutionSetInput(1, 10, null))
            )
        ).getOrThrow()
        val second = repo.recordSeriesExecution(
            SeriesExecutionInput(
                exerciseId, 2000L, 2100L, listOf(ExecutionSetInput(1, 8, 20.0))
            )
        ).getOrThrow()

        assertEquals(listOf(second, first), repo.observeHistory(exerciseId).first().map { it.id })
        assertTrue(repo.deleteExecution(999L).exceptionOrNull() is RepositoryFailure.NotFound)
        repo.deleteExecution(first).getOrThrow()
        assertEquals(listOf(second), repo.observeHistory(exerciseId).first().map { it.id })
    }
}
