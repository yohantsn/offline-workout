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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomWorkoutBatchTest {

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

    private fun seriesDraft(name: String = "Squat") = ExerciseAggregateDraft(
        id = null, name = name, type = ExerciseType.SERIES, restSeconds = 90,
        targetSets = 3, targetReps = 10, targetWeightKg = 60.0,
        targetDurationSeconds = null, targetDistanceMeters = null
    )

    private fun timedDraft(name: String = "Plank") = ExerciseAggregateDraft(
        id = null, name = name, type = ExerciseType.TIMED, restSeconds = 30,
        targetSets = null, targetReps = null, targetWeightKg = null,
        targetDurationSeconds = 60L, targetDistanceMeters = null
    )

    @Test
    fun batch_three_workouts_preserves_ids_and_order() = runTest {
        val inputs = listOf(
            WorkoutAggregateDraft("A", "Wa", listOf(seriesDraft(), timedDraft())),
            WorkoutAggregateDraft("B", "Wb", listOf(timedDraft())),
            WorkoutAggregateDraft("C", "Wc", listOf(seriesDraft()))
        )
        val ids = repo.createWorkoutsWithExercises(inputs).getOrThrow()
        assertEquals(3, ids.size)
        assertEquals(1L, ids[0])
        assertEquals(2L, ids[1])
        assertEquals(3L, ids[2])
        val workouts = repo.observeWorkouts().first()
        assertEquals(3, workouts.size)
        // exercises for first workout in order 0,1
        val ex0 = repo.observeExercises(ids[0]).first()
        assertEquals(listOf(0, 1), ex0.map { it.position })
    }

    @Test
    fun batch_invalid_draft_fails_before_any_write() = runTest {
        // A falha (targetSets = 0) e capturada na validacao pre-transacao: nada e
        // escrito, portanto o banco fica intacto justamente porque a transacao
        // nunca comecou.
        val beforeWorkouts = repo.observeWorkouts().first().size
        val beforeExercises = db.exerciseDao().getAllOrdered().size
        val inputs = listOf(
            WorkoutAggregateDraft("A", "Wa", listOf(seriesDraft())),
            WorkoutAggregateDraft("B", "Wb", listOf(timedDraft())),
            WorkoutAggregateDraft("C", "Wc", listOf(seriesDraft().copy(targetSets = 0))) // invalid
        )
        val res = repo.createWorkoutsWithExercises(inputs)
        assertTrue(res.exceptionOrNull() is RepositoryFailure.Validation)
        assertEquals(beforeWorkouts, repo.observeWorkouts().first().size)
        assertEquals(beforeExercises, db.exerciseDao().getAllOrdered().size)
    }

    @Test
    fun batch_unique_position_respected() = runTest {
        val inputs = listOf(
            WorkoutAggregateDraft("A", "Wa", listOf(seriesDraft(), timedDraft(), seriesDraft(name = "S2")))
        )
        val ids = repo.createWorkoutsWithExercises(inputs).getOrThrow()
        val ex = repo.observeExercises(ids[0]).first()
        assertEquals(listOf(0, 1, 2), ex.map { it.position })
    }

    @Test
    fun batch_empty_list() = runTest {
        val ids = repo.createWorkoutsWithExercises(emptyList()).getOrThrow()
        assertTrue(ids.isEmpty())
        assertTrue(repo.observeWorkouts().first().isEmpty())
    }

    @Test
    fun batch_single_item() = runTest {
        val ids = repo.createWorkoutsWithExercises(listOf(WorkoutAggregateDraft("A", "Wa", listOf(seriesDraft())))).getOrThrow()
        assertEquals(1, ids.size)
        assertEquals(1, repo.observeWorkouts().first().size)
    }

    @Test
    fun batch_duplicate_code_in_list_conflict() = runTest {
        // Este e o teste que prova rollback de verdade: o throw acontece dentro
        // da transacao, depois de A ter sido inserido, e o banco fica vazio.
        val inputs = listOf(
            WorkoutAggregateDraft("A", "Wa", listOf(seriesDraft())),
            WorkoutAggregateDraft("A", "Wb", listOf(seriesDraft()))
        )
        val res = repo.createWorkoutsWithExercises(inputs)
        assertTrue(res.exceptionOrNull() is RepositoryFailure.Conflict)
        assertTrue(repo.observeWorkouts().first().isEmpty())
    }

    @Test
    fun batch_existing_code_conflict() = runTest {
        repo.createWorkoutWithExercises(WorkoutAggregateDraft("A", "Existing", emptyList())).getOrThrow()
        val before = repo.observeWorkouts().first().size
        val inputs = listOf(WorkoutAggregateDraft("A", "Dup", listOf(seriesDraft())))
        val res = repo.createWorkoutsWithExercises(inputs)
        assertTrue(res.exceptionOrNull() is RepositoryFailure.Conflict)
        assertEquals(before, repo.observeWorkouts().first().size)
    }

    @Test
    fun batch_rejects_carried_exercise_id() = runTest {
        // build an ExerciseAggregateDraft with id != null
        val draftWithId = ExerciseAggregateDraft(
            id = 5L, name = "Squat", type = ExerciseType.SERIES, restSeconds = 90,
            targetSets = 3, targetReps = 10, targetWeightKg = 60.0,
            targetDurationSeconds = null, targetDistanceMeters = null
        )
        val inputs2 = listOf(WorkoutAggregateDraft("A", "Wa", listOf(draftWithId)))
        assertTrue(repo.createWorkoutsWithExercises(inputs2).exceptionOrNull() is RepositoryFailure.Validation)
        assertTrue(repo.observeWorkouts().first().isEmpty())
    }
}
