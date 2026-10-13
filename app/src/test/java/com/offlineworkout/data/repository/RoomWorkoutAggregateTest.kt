package com.offlineworkout.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.offlineworkout.data.local.AppDatabase
import com.offlineworkout.data.model.ExerciseAggregateDraft
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.WorkoutAggregateDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// T4.1: aggregate create/update against an in-memory database: atomicity,
// order, id rules and rollback.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomWorkoutAggregateTest {

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

    private fun seriesDraft(id: Long? = null, name: String = "Squat") = ExerciseAggregateDraft(
        id = id, name = name, type = ExerciseType.SERIES, restSeconds = 90,
        targetSets = 3, targetReps = 10, targetWeightKg = 60.0,
        targetDurationSeconds = null, targetDistanceMeters = null
    )

    private fun timedDraft(id: Long? = null, name: String = "Plank") = ExerciseAggregateDraft(
        id = id, name = name, type = ExerciseType.TIMED, restSeconds = 30,
        targetSets = null, targetReps = null, targetWeightKg = null,
        targetDurationSeconds = 60L, targetDistanceMeters = null
    )

    private fun distanceDraft(id: Long? = null, name: String = "Run") = ExerciseAggregateDraft(
        id = id, name = name, type = ExerciseType.DISTANCE_TIME, restSeconds = 0,
        targetSets = null, targetReps = null, targetWeightKg = null,
        targetDurationSeconds = 600L, targetDistanceMeters = 1000.0
    )

    @Test
    fun create_empty_workout() = runTest {
        val id = repo.createWorkoutWithExercises(WorkoutAggregateDraft("A", "Empty", emptyList())).getOrThrow()
        assertEquals("Empty", repo.observeWorkout(id).first()?.name)
        assertTrue(repo.observeExercises(id).first().isEmpty())
    }

    @Test
    fun create_with_three_types_preserves_order() = runTest {
        val id = repo.createWorkoutWithExercises(
            WorkoutAggregateDraft("B", "Mixed", listOf(timedDraft(), seriesDraft(), distanceDraft()))
        ).getOrThrow()
        val specs = repo.observeExercises(id).first()
        assertEquals(
            listOf(ExerciseType.TIMED, ExerciseType.SERIES, ExerciseType.DISTANCE_TIME),
            specs.map { it.type }
        )
        assertEquals(listOf(0, 1, 2), specs.map { it.position })
        assertEquals(60L, specs[0].targetDurationSeconds)
        assertEquals(60.0, specs[1].targetWeightKg)
        assertEquals(1000.0, specs[2].targetDistanceMeters)
    }

    @Test
    fun create_validates_everything_before_any_insert() = runTest {
        val bad = WorkoutAggregateDraft(
            "A", "Bad", listOf(seriesDraft(), timedDraft().copy(targetDurationSeconds = 0L))
        )
        assertTrue(repo.createWorkoutWithExercises(bad).exceptionOrNull() is RepositoryFailure.Validation)
        assertTrue(repo.observeWorkouts().first().isEmpty())

        // T9.2 (D-1): `code` is free text; the rejected case is emptiness.
        val badCode = WorkoutAggregateDraft("", "Bad", emptyList())
        assertTrue(repo.createWorkoutWithExercises(badCode).exceptionOrNull() is RepositoryFailure.Validation)
        assertTrue(repo.observeWorkouts().first().isEmpty())
    }

    @Test
    fun create_rejects_carried_id_and_negative_rest() = runTest {
        val withId = WorkoutAggregateDraft("A", "X", listOf(seriesDraft(id = 5L)))
        assertTrue(repo.createWorkoutWithExercises(withId).exceptionOrNull() is RepositoryFailure.Validation)

        val badRest = WorkoutAggregateDraft("A", "X", listOf(seriesDraft().copy(restSeconds = -1)))
        assertTrue(repo.createWorkoutWithExercises(badRest).exceptionOrNull() is RepositoryFailure.Validation)
        assertTrue(repo.observeWorkouts().first().isEmpty())
    }

    @Test
    fun edit_updates_adds_and_reorders() = runTest {
        val id = repo.createWorkoutWithExercises(
            WorkoutAggregateDraft("A", "Old", listOf(seriesDraft(name = "One"), timedDraft(name = "Two")))
        ).getOrThrow()
        val before = repo.observeExercises(id).first()
        assertEquals(2, before.size)

        repo.updateWorkoutWithExercises(
            id,
            WorkoutAggregateDraft(
                "A", "New",
                listOf(
                    timedDraft(id = before[1].id, name = "Two"),
                    distanceDraft(name = "Three"),
                    seriesDraft(id = before[0].id, name = "One")
                )
            )
        ).getOrThrow()

        assertEquals("New", repo.observeWorkout(id).first()?.name)
        val after = repo.observeExercises(id).first()
        assertEquals(listOf("Two", "Three", "One"), after.map { it.name })
        assertEquals(listOf(0, 1, 2), after.map { it.position })
        // Existing ids preserved, exactly one new row.
        assertEquals(before[1].id, after[0].id)
        assertEquals(before[0].id, after[2].id)
        assertEquals(3, after.size)
    }

    @Test
    fun edit_rejects_bad_id_lists() = runTest {
        val id = repo.createWorkoutWithExercises(
            WorkoutAggregateDraft("A", "W", listOf(seriesDraft(), timedDraft()))
        ).getOrThrow()
        val current = repo.observeExercises(id).first()
        val other = repo.createWorkoutWithExercises(
            WorkoutAggregateDraft("B", "Other", listOf(seriesDraft()))
        ).getOrThrow()
        val foreignId = repo.observeExercises(other).first().single().id

        // Duplicate id.
        assertTrue(
            repo.updateWorkoutWithExercises(
                id, WorkoutAggregateDraft("A", "W", listOf(seriesDraft(id = current[0].id), seriesDraft(id = current[0].id)))
            ).exceptionOrNull() is RepositoryFailure.Validation
        )
        // Unknown id.
        assertTrue(
            repo.updateWorkoutWithExercises(
                id, WorkoutAggregateDraft("A", "W", listOf(seriesDraft(id = 999_999L)))
            ).exceptionOrNull() is RepositoryFailure.Validation
        )
        // Foreign id from another workout.
        assertTrue(
            repo.updateWorkoutWithExercises(
                id, WorkoutAggregateDraft("A", "W", listOf(seriesDraft(id = foreignId)))
            ).exceptionOrNull() is RepositoryFailure.Validation
        )
        // Incomplete list (silent delete attempt).
        assertTrue(
            repo.updateWorkoutWithExercises(
                id, WorkoutAggregateDraft("A", "W", listOf(seriesDraft(id = current[0].id)))
            ).exceptionOrNull() is RepositoryFailure.Validation
        )
        // Nothing changed by the rejected edits.
        assertEquals(current.map { it.id }, repo.observeExercises(id).first().map { it.id })
        assertEquals("W", repo.observeWorkout(id).first()?.name)
    }

    @Test
    fun edit_failure_rolls_back_workout_and_exercises() = runTest {
        val id = repo.createWorkoutWithExercises(
            WorkoutAggregateDraft("A", "Stable", listOf(seriesDraft(name = "Keep")))
        ).getOrThrow()
        val exerciseId = repo.observeExercises(id).first().single().id

        // Valid workout fields but an invalid exercise: whole aggregate rolls back.
        val failure = repo.updateWorkoutWithExercises(
            id,
            WorkoutAggregateDraft(
                "B", "Changed",
                listOf(seriesDraft(id = exerciseId, name = "Keep").copy(targetSets = 0))
            )
        ).exceptionOrNull()
        assertTrue(failure is RepositoryFailure.Validation)
        assertEquals("Stable", repo.observeWorkout(id).first()?.name)
        assertEquals("A", repo.observeWorkout(id).first()?.code)
        val specs = repo.observeExercises(id).first()
        assertEquals(listOf(exerciseId), specs.map { it.id })
        assertEquals(3, specs.single().targetSets)
    }

    @Test
    fun edit_missing_workout_is_not_found() = runTest {
        assertTrue(
            repo.updateWorkoutWithExercises(999L, WorkoutAggregateDraft("A", "X", emptyList()))
                .exceptionOrNull() is RepositoryFailure.NotFound
        )
    }
}
