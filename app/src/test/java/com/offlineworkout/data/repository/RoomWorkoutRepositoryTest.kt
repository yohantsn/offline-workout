package com.offlineworkout.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.offlineworkout.data.local.AppDatabase
import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.WorkoutDraft
import com.offlineworkout.data.model.WorkoutUpdate
import kotlinx.coroutines.CancellationException
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

// T2.1: RoomWorkoutRepository against an in-memory database.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomWorkoutRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RoomWorkoutRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = RoomWorkoutRepository(db, clock = { 1000L })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seriesInput(workoutId: Long, position: Int = 0, name: String = "Squat") = ExerciseInput(
        workoutId = workoutId, name = name, type = ExerciseType.SERIES, position = position,
        restSeconds = 90, targetSets = 3, targetReps = 10, targetWeightKg = 60.0,
        targetDurationSeconds = null, targetDistanceMeters = null
    )

    @Test
    fun create_and_observe_workout() = runTest {
        val id = repo.createWorkout(WorkoutDraft("A", "Treino A")).getOrThrow()
        val summaries = repo.observeWorkouts().first()
        assertEquals(1, summaries.size)
        assertEquals("A", summaries.single().code)
        assertEquals(1000L, summaries.single().createdAt)
        assertEquals("Treino A", repo.observeWorkout(id).first()?.name)
    }

    @Test
    fun observe_workouts_aggregates_counts_and_rest() = runTest {
        val id = repo.createWorkout(WorkoutDraft("A", "A")).getOrThrow()
        repo.createWorkout(WorkoutDraft("B", "B")).getOrThrow()
        repo.createExercise(seriesInput(id, position = 0).copy(restSeconds = 90)).getOrThrow()
        repo.createExercise(seriesInput(id, position = 1).copy(restSeconds = 30)).getOrThrow()

        val summaries = repo.observeWorkouts().first()
        val withExercises = summaries.single { it.code == "A" }
        assertEquals(2, withExercises.exerciseCount)
        assertEquals(120, withExercises.totalRestSeconds)
        val empty = summaries.single { it.code == "B" }
        assertEquals(0, empty.exerciseCount)
        assertEquals(0, empty.totalRestSeconds)
    }

    @Test
    fun observe_workout_maps_aggregates_and_null_for_missing() = runTest {
        val id = repo.createWorkout(WorkoutDraft("A", "A")).getOrThrow()
        repo.createExercise(seriesInput(id)).getOrThrow()

        val summary = repo.observeWorkout(id).first()
        assertEquals(1, summary?.exerciseCount)
        assertEquals(90, summary?.totalRestSeconds)
        assertNull(repo.observeWorkout(999L).first())
    }

    @Test
    fun blank_code_is_validation_failure() = runTest {
        // T9.2 (D-1): `code` is free text, so the rejected case is emptiness.
        val failure = repo.createWorkout(WorkoutDraft("  ", "X")).exceptionOrNull()
        assertTrue(failure is RepositoryFailure.Validation)
    }

    @Test
    fun free_codes_are_accepted_including_a_fourth_workout() = runTest {
        // T9.2 (D-1): the A/B/C seed is a particular case, not a closed set.
        listOf("A", "B", "C", "D").forEach { code ->
            repo.createWorkout(WorkoutDraft(code, "Treino $code")).getOrThrow()
        }
        val codes = repo.observeWorkouts().first().map { it.code }
        assertEquals(listOf("A", "B", "C", "D"), codes)
        // Free text beyond a single letter is accepted too.
        repo.createWorkout(WorkoutDraft("perdia-super-rapida", "Hipertrofia")).getOrThrow()
        assertEquals(5, repo.observeWorkouts().first().size)
    }

    @Test
    fun blank_name_is_validation_failure() = runTest {
        assertTrue(repo.createWorkout(WorkoutDraft("A", "  ")).exceptionOrNull() is RepositoryFailure.Validation)
    }

    @Test
    fun observe_workouts_is_ordered_alphabetically_by_code() = runTest {
        // T9.3 (R-6): single ordering source, free codes, no precedence map.
        listOf("D", "A", "C", "B", "hiper", "Costas", "costas").forEachIndexed { index, code ->
            repo.createWorkout(WorkoutDraft(code, "Treino $code")).getOrThrow()
            assertEquals(index + 1, repo.observeWorkouts().first().size)
        }
        assertEquals(
            listOf("A", "B", "C", "Costas", "D", "costas", "hiper"),
            repo.observeWorkouts().first().map { it.code }
        )
    }

    @Test
    fun ordering_compares_utf16_code_units_not_locale_dictionary() = runTest {
        // Known and accepted behaviour (T9.3): "alphabetic" is Kotlin String
        // comparison, i.e. UTF-16 code-unit order — case- and accent-sensitive,
        // not a Portuguese dictionary order. Documented so the difference is not
        // later reported as a bug; a Collator would be a product decision.
        setOf("peito", "Costas", "Rezoo", "Remoção").forEach { code ->
            repo.createWorkout(WorkoutDraft(code, "Treino $code")).getOrThrow()
        }
        assertEquals(
            listOf("Costas", "Remoção", "Rezoo", "peito"),
            repo.observeWorkouts().first().map { it.code }
        )
    }

    @Test
    fun duplicate_code_is_conflict() = runTest {
        repo.createWorkout(WorkoutDraft("A", "First")).getOrThrow()
        val failure = repo.createWorkout(WorkoutDraft("A", "Second")).exceptionOrNull()
        assertTrue(failure is RepositoryFailure.Conflict)
    }

    @Test
    fun update_and_delete_workout() = runTest {
        val id = repo.createWorkout(WorkoutDraft("A", "Old")).getOrThrow()
        repo.updateWorkout(id, WorkoutUpdate("B", "New")).getOrThrow()
        assertEquals("New", repo.observeWorkout(id).first()?.name)
        assertTrue(repo.updateWorkout(999L, WorkoutUpdate("A", "Ghost")).exceptionOrNull() is RepositoryFailure.NotFound)

        repo.deleteWorkout(id).getOrThrow()
        assertNull(repo.observeWorkout(id).first())
        assertTrue(repo.deleteWorkout(id).exceptionOrNull() is RepositoryFailure.NotFound)
    }

    @Test
    fun create_exercise_validates_type_targets() = runTest {
        val workoutId = repo.createWorkout(WorkoutDraft("A", "A")).getOrThrow()
        // SERIES must not carry duration targets.
        val bad = seriesInput(workoutId).copy(targetDurationSeconds = 60L)
        assertTrue(repo.createExercise(bad).exceptionOrNull() is RepositoryFailure.Validation)
        // Unknown workout is NotFound.
        assertTrue(repo.createExercise(seriesInput(999L)).exceptionOrNull() is RepositoryFailure.NotFound)
        // Happy path persists and observes in position order.
        repo.createExercise(seriesInput(workoutId, position = 1, name = "Second")).getOrThrow()
        repo.createExercise(seriesInput(workoutId, position = 0, name = "First")).getOrThrow()
        assertEquals(listOf("First", "Second"), repo.observeExercises(workoutId).first().map { it.name })
    }

    @Test
    fun duplicate_position_is_conflict() = runTest {
        val workoutId = repo.createWorkout(WorkoutDraft("A", "A")).getOrThrow()
        repo.createExercise(seriesInput(workoutId, position = 0)).getOrThrow()
        assertTrue(
            repo.createExercise(seriesInput(workoutId, position = 0)).exceptionOrNull() is RepositoryFailure.Conflict
        )
    }

    @Test
    fun timed_and_distance_targets_validated() = runTest {
        val workoutId = repo.createWorkout(WorkoutDraft("A", "A")).getOrThrow()
        val timedBase = ExerciseInput(
            workoutId, "Plank", ExerciseType.TIMED, 0, 30,
            null, null, null, 60L, null
        )
        timedBase.copy(targetDurationSeconds = 0L).let {
            assertTrue(repo.createExercise(it).exceptionOrNull() is RepositoryFailure.Validation)
        }
        repo.createExercise(timedBase).getOrThrow()
        val distanceBad = ExerciseInput(
            workoutId, "Run", ExerciseType.DISTANCE_TIME, 1, 0,
            null, null, null, 600L, -5.0
        )
        assertTrue(repo.createExercise(distanceBad).exceptionOrNull() is RepositoryFailure.Validation)
    }

    @Test
    fun reorder_is_transactional_and_validated() = runTest {
        val workoutId = repo.createWorkout(WorkoutDraft("A", "A")).getOrThrow()
        val first = repo.createExercise(seriesInput(workoutId, position = 0, name = "A")).getOrThrow()
        val second = repo.createExercise(seriesInput(workoutId, position = 1, name = "B")).getOrThrow()
        val third = repo.createExercise(seriesInput(workoutId, position = 2, name = "C")).getOrThrow()

        repo.reorderExercises(workoutId, listOf(third, first, second)).getOrThrow()
        assertEquals(listOf("C", "A", "B"), repo.observeExercises(workoutId).first().map { it.name })

        // Wrong membership fails and leaves positions untouched.
        val before = repo.observeExercises(workoutId).first().map { it.id to it.position }
        assertTrue(
            repo.reorderExercises(workoutId, listOf(first, second)).exceptionOrNull() is RepositoryFailure.Validation
        )
        assertTrue(
            repo.reorderExercises(workoutId, listOf(first, first, second)).exceptionOrNull() is RepositoryFailure.Validation
        )
        assertEquals(before, repo.observeExercises(workoutId).first().map { it.id to it.position })
    }

    @Test
    fun delete_exercise_cascades_and_reports_not_found() = runTest {
        val workoutId = repo.createWorkout(WorkoutDraft("A", "A")).getOrThrow()
        val exerciseId = repo.createExercise(seriesInput(workoutId)).getOrThrow()
        repo.deleteExercise(exerciseId).getOrThrow()
        assertTrue(repo.observeExercises(workoutId).first().isEmpty())
        assertTrue(repo.deleteExercise(exerciseId).exceptionOrNull() is RepositoryFailure.NotFound)
    }

    @Test(expected = CancellationException::class)
    fun cancellation_is_rethrown_not_wrapped() = runTest {
        val cancelling = RoomWorkoutRepository(db, clock = { throw CancellationException("cancelled") })
        cancelling.createWorkout(WorkoutDraft("A", "A"))
    }
}
