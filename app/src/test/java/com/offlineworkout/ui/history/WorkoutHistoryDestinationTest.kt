package com.offlineworkout.ui.history

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import com.offlineworkout.data.model.ExerciseAggregateDraft
import com.offlineworkout.data.model.ExerciseExecutionView
import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.ExecutionSetView
import com.offlineworkout.data.model.HistoryQuery
import com.offlineworkout.data.model.HistoryStats
import com.offlineworkout.data.model.WorkoutAggregateDraft
import com.offlineworkout.data.model.WorkoutDraft
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.data.model.WorkoutUpdate
import com.offlineworkout.data.model.DistanceTimeExecutionInput
import com.offlineworkout.data.model.SeriesExecutionInput
import com.offlineworkout.data.model.TimedExecutionInput
import com.offlineworkout.data.repository.ExerciseHistoryRepository
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

// T7.1: history entry guard (invalid ids never touch repositories/VM) and
// wiring (valid ids load workout + selection through fakes). No Room.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutHistoryDestinationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun invalid_workout_shows_back_without_calls() {
        val workouts = GuardedWorkouts()
        val history = GuardedHistory()
        var backed = 0
        composeTestRule.setContent {
            WorkoutHistoryDestination(
                workoutId = -1L,
                exerciseId = null,
                exerciseInvalid = false,
                workoutRepository = workouts,
                historyRepository = history,
                onBack = { backed += 1 },
                onNavigateExercise = { _, _ -> }
            )
        }
        composeTestRule.onNodeWithText("Treino inválido.").assertExists()
        composeTestRule.runOnIdle {
            assertEquals(0, backed)
            assertTrue(workouts.calls.isEmpty())
            assertTrue(history.calls.isEmpty())
        }
    }

    @Test
    fun invalid_exercise_shows_back_without_history_calls() {
        val workouts = GuardedWorkouts()
        val history = GuardedHistory()
        composeTestRule.setContent {
            WorkoutHistoryDestination(
                workoutId = 7L,
                exerciseId = null,
                exerciseInvalid = true,
                workoutRepository = workouts,
                historyRepository = history,
                onBack = {},
                onNavigateExercise = { _, _ -> }
            )
        }
        composeTestRule.onNodeWithText("Exercício inválido.").assertExists()
        composeTestRule.runOnIdle { assertTrue(history.calls.isEmpty()) }
    }

    @Test
    fun no_arg_shows_exercise_selection() {
        val workouts = GuardedWorkouts()
        workouts.workout.value = WorkoutSummary(7L, "A", "Alpha", 1L)
        workouts.exercises.value = listOf(
            ExerciseSpec(
                id = 1L, workoutId = 7L, name = "Squat", type = ExerciseType.SERIES,
                position = 0, restSeconds = 60, targetSets = 3, targetReps = 10,
                targetWeightKg = null, targetDurationSeconds = null, targetDistanceMeters = null
            )
        )
        composeTestRule.setContent {
            WorkoutHistoryDestination(
                workoutId = 7L,
                exerciseId = null,
                exerciseInvalid = false,
                workoutRepository = workouts,
                historyRepository = GuardedHistory(),
                onBack = {},
                onNavigateExercise = { _, _ -> }
            )
        }
        composeTestRule.onNodeWithText("Alpha").assertExists()
        composeTestRule.onNodeWithContentDescription("Ver histórico de Squat").assertExists()
    }

    @Test
    fun valid_arg_loads_history_table() {
        val workouts = GuardedWorkouts()
        workouts.workout.value = WorkoutSummary(7L, "A", "Alpha", 1L)
        workouts.exercises.value = listOf(
            ExerciseSpec(
                id = 1L, workoutId = 7L, name = "Squat", type = ExerciseType.SERIES,
                position = 0, restSeconds = 60, targetSets = 3, targetReps = 10,
                targetWeightKg = null, targetDurationSeconds = null, targetDistanceMeters = null
            )
        )
        val history = GuardedHistory()
        history.pages[1L] = listOf(
            ExerciseExecutionView(
                id = 10L, exerciseId = 1L, performedAt = 1000L, completedAt = 1100L,
                durationSeconds = null, distanceMeters = null,
                sets = listOf(ExecutionSetView(1, 10, 60.0))
            )
        )
        composeTestRule.setContent {
            WorkoutHistoryDestination(
                workoutId = 7L,
                exerciseId = 1L,
                exerciseInvalid = false,
                workoutRepository = workouts,
                historyRepository = history,
                onBack = {},
                onNavigateExercise = { _, _ -> }
            )
        }
        composeTestRule.onNodeWithTag("historyList").performScrollToIndex(4)
        composeTestRule.onNodeWithText("Série 1: 10 reps • 60.0 kg").assertExists()
    }

    private class GuardedWorkouts : WorkoutRepository {
        val calls = mutableListOf<String>()
        val workout = MutableStateFlow<WorkoutSummary?>(null)
        val exercises = MutableStateFlow<List<ExerciseSpec>>(emptyList())

        override fun observeWorkouts(): Flow<List<WorkoutSummary>> =
            throw UnsupportedOperationException("must not be called")

        override fun observeWorkout(id: Long): Flow<WorkoutSummary?> {
            calls.add("observeWorkout")
            return workout
        }

        override fun observeExercises(workoutId: Long): Flow<List<ExerciseSpec>> {
            calls.add("observeExercises")
            return exercises
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

        override suspend fun updateWorkoutWithExercises(workoutId: Long, input: WorkoutAggregateDraft): Result<Unit> =
            throw UnsupportedOperationException("must not be called")
    }

    private class GuardedHistory : ExerciseHistoryRepository {
        val calls = mutableListOf<String>()
        val pages = mutableMapOf<Long, List<ExerciseExecutionView>>()

        override fun observeHistory(exerciseId: Long): Flow<List<ExerciseExecutionView>> =
            throw UnsupportedOperationException("must not be called")

        override fun observeLatestExecution(exerciseId: Long): Flow<ExerciseExecutionView?> =
            throw UnsupportedOperationException("must not be called")

        override fun observeLatestSet(exerciseId: Long): Flow<ExecutionSetView?> =
            throw UnsupportedOperationException("must not be called")

        override fun observeHistoryPage(query: HistoryQuery): Flow<List<ExerciseExecutionView>> {
            calls.add("observeHistoryPage")
            return flowOf(pages[query.exerciseId].orEmpty())
        }

        override fun observeHistoryWindow(
            exerciseId: Long,
            fromWallClockMs: Long?,
            toWallClockMs: Long?
        ): Flow<List<ExerciseExecutionView>> {
            calls.add("observeHistoryWindow")
            return flowOf(pages[exerciseId].orEmpty())
        }

        override fun observeHistoryStats(exerciseId: Long): Flow<HistoryStats> {
            calls.add("observeHistoryStats")
            val rows = pages[exerciseId].orEmpty()
            return flowOf(
                HistoryStats(
                    exerciseId,
                    rows.size,
                    rows.minOfOrNull { it.performedAt },
                    rows.maxOfOrNull { it.performedAt }
                )
            )
        }

        override suspend fun recordSeriesExecution(input: SeriesExecutionInput): Result<Long> =
            throw UnsupportedOperationException("must not be called")

        override suspend fun recordTimedExecution(input: TimedExecutionInput): Result<Long> =
            throw UnsupportedOperationException("must not be called")

        override suspend fun recordDistanceTimeExecution(input: DistanceTimeExecutionInput): Result<Long> =
            throw UnsupportedOperationException("must not be called")

        override suspend fun deleteExecution(executionId: Long): Result<Unit> =
            throw UnsupportedOperationException("must not be called")
    }
}
