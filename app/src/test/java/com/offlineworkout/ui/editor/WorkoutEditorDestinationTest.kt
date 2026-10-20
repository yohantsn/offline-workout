package com.offlineworkout.ui.editor

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.offlineworkout.data.config.WorkoutEditorConfig
import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.WorkoutAggregateDraft
import com.offlineworkout.data.model.WorkoutDraft
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.data.model.WorkoutUpdate
import com.offlineworkout.data.repository.WorkoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// T4.1: edit entry guard (invalid id never touches the repository) and the
// create/edit wiring through a fake repository. No Room.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutEditorDestinationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val config = WorkoutEditorConfig(defaultRestSeconds = 0, allowEmptyWorkout = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun invalid_id_shows_back_without_repository_call() {
        val fake = GuardedFake()
        var backed = 0
        composeTestRule.setContent {
            WorkoutEditDestination(
                workoutId = -1L,
                repository = fake,
                config = config,
                onSaved = {},
                onClosed = {},
                onBack = { backed += 1 }
            )
        }
        composeTestRule.onNodeWithText("Treino inválido.").assertExists()
        composeTestRule.onNodeWithText("Voltar para treinos").performClick()
        composeTestRule.runOnIdle {
            assertEquals(1, backed)
            assertTrue(fake.calls.isEmpty())
        }
    }

    @Test
    fun valid_id_loads_editor_with_title() {
        val fake = GuardedFake()
        fake.workout.value = WorkoutSummary(7L, "A", "Alpha", 1L)
        composeTestRule.setContent {
            WorkoutEditDestination(
                workoutId = 7L,
                repository = fake,
                config = config,
                onSaved = {},
                onClosed = {},
                onBack = {}
            )
        }
        composeTestRule.onNodeWithText("Editar treino").assertExists()
        composeTestRule.runOnIdle { assertTrue(fake.calls.contains("observeWorkout")) }
    }

    @Test
    fun create_mode_opens_blank_editor() {
        val fake = GuardedFake()
        composeTestRule.setContent {
            WorkoutEditorRoute(
                repository = fake,
                mode = com.offlineworkout.viewmodel.WorkoutEditorMode.Create,
                config = config,
                onSaved = {},
                onClosed = {}
            )
        }
        composeTestRule.onNodeWithText("Novo treino").assertExists()
        composeTestRule.runOnIdle { assertTrue(fake.calls.isEmpty()) }
    }

    private class GuardedFake : WorkoutRepository {
        val calls = mutableListOf<String>()
        val workout = MutableStateFlow<WorkoutSummary?>(null)
        val exercises = MutableStateFlow<List<ExerciseSpec>>(emptyList())

        override fun observeWorkouts(): Flow<List<WorkoutSummary>> =
            throw UnsupportedOperationException("must not be called")

        override fun observeWorkout(id: Long): Flow<WorkoutSummary?> {
            calls.add("observeWorkout")
            return flowOf(workout.value)
        }

        override fun observeExercises(workoutId: Long): Flow<List<ExerciseSpec>> {
            calls.add("observeExercises")
            return flowOf(exercises.value)
        }

        override suspend fun createWorkout(input: WorkoutDraft): Result<Long> =
            throw UnsupportedOperationException("must not be called")

        override suspend fun updateWorkout(id: Long, input: WorkoutUpdate): Result<Unit> =
            throw UnsupportedOperationException("must not be called")

        override suspend fun deleteWorkout(id: Long): Result<Unit> =
            throw UnsupportedOperationException("must not be called")

        override suspend fun createExercise(input: ExerciseInput): Result<Long> =
            throw UnsupportedOperationException("must not be called")

        override suspend fun updateExercise(id: Long, input: ExerciseInput): Result<Unit> =
            throw UnsupportedOperationException("must not be called")

        override suspend fun deleteExercise(id: Long): Result<Unit> =
            throw UnsupportedOperationException("must not be called")

        override suspend fun reorderExercises(workoutId: Long, orderedExerciseIds: List<Long>): Result<Unit> =
            throw UnsupportedOperationException("must not be called")

        override suspend fun createWorkoutWithExercises(input: WorkoutAggregateDraft): Result<Long> =
            throw UnsupportedOperationException("must not be called")

        override suspend fun createWorkoutsWithExercises(inputs: List<WorkoutAggregateDraft>): Result<List<Long>> =
            throw UnsupportedOperationException("not used")

        override suspend fun updateWorkoutWithExercises(workoutId: Long, input: WorkoutAggregateDraft): Result<Unit> =
            throw UnsupportedOperationException("must not be called")
    }
}
