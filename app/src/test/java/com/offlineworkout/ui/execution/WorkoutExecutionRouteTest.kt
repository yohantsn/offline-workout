package com.offlineworkout.ui.execution

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.WorkoutAggregateDraft
import com.offlineworkout.data.model.WorkoutDraft
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.data.model.WorkoutUpdate
import com.offlineworkout.data.repository.ExerciseHistoryRepository
import com.offlineworkout.data.repository.WorkoutRepository
import com.offlineworkout.data.session.WorkoutSessionController
import com.offlineworkout.data.session.WorkoutSessionState
import com.offlineworkout.data.session.WorkoutSessionStatus
import com.offlineworkout.data.timer.RestRequest
import com.offlineworkout.data.timer.RestTimerCommandResult
import com.offlineworkout.data.timer.RestTimerController
import com.offlineworkout.data.timer.RestTimerSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// T5.1: execution route guard (invalid id never touches repository/timer) and
// wiring (valid id loads workout + exercises through fakes). No Room/Service.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutExecutionRouteTest {

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
    fun invalid_id_shows_back_without_calls() {
        val repo = GuardedRepository()
        val timer = GuardedTimer()
        val history = GuardedHistory()
        val session = GuardedSession()
        var backed = 0
        composeTestRule.setContent {
            WorkoutExecutionRoute(
                workoutId = -1L,
                repository = repo,
                history = history,
                timer = timer,
                session = session,
                onBack = { backed += 1 },
                onFinished = {},
                onOpenHistory = { _, _ -> }
            )
        }
        composeTestRule.onNodeWithText("Treino inválido.").assertExists()
        composeTestRule.runOnIdle {
            org.junit.Assert.assertEquals(0, backed)
            org.junit.Assert.assertTrue(repo.calls.isEmpty())
            org.junit.Assert.assertTrue(timer.calls.isEmpty())
            org.junit.Assert.assertTrue(history.calls.isEmpty())
            org.junit.Assert.assertTrue(session.calls.isEmpty())
        }
    }

    @Test
    fun valid_id_loads_exercises_with_rest_actions() {
        val repo = GuardedRepository()
        repo.workout.value = WorkoutSummary(7L, "A", "Alpha", 1L)
        repo.exercises.value = listOf(
            ExerciseSpec(
                id = 1L, workoutId = 7L, name = "Squat", type = ExerciseType.SERIES,
                position = 0, restSeconds = 60, targetSets = 3, targetReps = 10,
                targetWeightKg = null, targetDurationSeconds = null, targetDistanceMeters = null
            ),
            ExerciseSpec(
                id = 2L, workoutId = 7L, name = "Plank", type = ExerciseType.TIMED,
                position = 1, restSeconds = 0, targetSets = null, targetReps = null,
                targetWeightKg = null, targetDurationSeconds = 60L, targetDistanceMeters = null
            )
        )
        composeTestRule.setContent {
            WorkoutExecutionRoute(
                workoutId = 7L,
                repository = repo,
                history = GuardedHistory(),
                timer = GuardedTimer(),
                session = GuardedSession(),
                onBack = {},
                onFinished = {},
                onOpenHistory = { _, _ -> }
            )
        }
        composeTestRule.onNodeWithText("Alpha").assertExists()
        composeTestRule.onNodeWithContentDescription("Iniciar descanso de Squat").assertExists()
        composeTestRule.onNodeWithTag("executionList").performScrollToIndex(3)
        composeTestRule.onNodeWithText("Sem descanso").assertExists()
    }

    private class GuardedRepository : WorkoutRepository {
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

    private class GuardedTimer : RestTimerController {
        val calls = mutableListOf<String>()
        private val backing = MutableStateFlow<RestTimerSnapshot?>(null)
        override val state: StateFlow<RestTimerSnapshot?> = backing

        override fun start(request: RestRequest): RestTimerCommandResult {
            calls.add("start")
            return RestTimerCommandResult.Started
        }

        override fun cancel(timerId: String): RestTimerCommandResult {
            calls.add("cancel")
            return RestTimerCommandResult.Cancelled
        }
    }

    private class GuardedHistory : ExerciseHistoryRepository {
        val calls = mutableListOf<String>()

        override fun observeHistory(exerciseId: Long): Flow<List<com.offlineworkout.data.model.ExerciseExecutionView>> =
            throw UnsupportedOperationException("must not be called")

        override fun observeLatestExecution(exerciseId: Long): Flow<com.offlineworkout.data.model.ExerciseExecutionView?> {
            calls.add("observeLatestExecution")
            return flowOf(null)
        }

        override fun observeLatestSet(exerciseId: Long): Flow<com.offlineworkout.data.model.ExecutionSetView?> {
            calls.add("observeLatestSet")
            return flowOf(null)
        }

        override suspend fun recordSeriesExecution(input: com.offlineworkout.data.model.SeriesExecutionInput): Result<Long> =
            throw UnsupportedOperationException("must not be called")

        override suspend fun recordTimedExecution(input: com.offlineworkout.data.model.TimedExecutionInput): Result<Long> =
            throw UnsupportedOperationException("must not be called")

        override suspend fun recordDistanceTimeExecution(input: com.offlineworkout.data.model.DistanceTimeExecutionInput): Result<Long> =
            throw UnsupportedOperationException("must not be called")

        override suspend fun deleteExecution(executionId: Long): Result<Unit> =
            throw UnsupportedOperationException("must not be called")

        override fun observeHistoryPage(query: com.offlineworkout.data.model.HistoryQuery): Flow<List<com.offlineworkout.data.model.ExerciseExecutionView>> =
            throw UnsupportedOperationException("must not be called")

        override fun observeHistoryWindow(
            exerciseId: Long,
            fromWallClockMs: Long?,
            toWallClockMs: Long?
        ): Flow<List<com.offlineworkout.data.model.ExerciseExecutionView>> =
            throw UnsupportedOperationException("must not be called")

        override fun observeHistoryStats(exerciseId: Long): Flow<com.offlineworkout.data.model.HistoryStats> =
            throw UnsupportedOperationException("must not be called")
    }

    private class GuardedSession : WorkoutSessionController {
        // T9.2 (R-12): the contract is closeable, so the composition root can
        // stop the ticker; fakes have nothing to release.
        override fun close() = Unit

        val calls = mutableListOf<String>()
        private val backing = MutableStateFlow(
            WorkoutSessionState(
                status = WorkoutSessionStatus.Running(7L),
                workoutId = 7L,
                elapsedSeconds = 0L,
                startedAtWallClockMs = 1L,
                accumulatedMs = 0L,
                lastResumedAtWallClockMs = 1L
            )
        )
        override val state: StateFlow<WorkoutSessionState> = backing

        override fun startOrResume(workoutId: Long) {
            calls.add("startOrResume")
        }

        override fun pause() {
            calls.add("pause")
        }

        override fun resume() {
            calls.add("resume")
        }

        override fun stop() {
            calls.add("stop")
        }
    }
}
