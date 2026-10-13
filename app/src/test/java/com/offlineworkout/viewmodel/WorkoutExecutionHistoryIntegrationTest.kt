package com.offlineworkout.viewmodel

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.offlineworkout.data.local.AppDatabase
import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.SeriesExecutionInput
import com.offlineworkout.data.model.ExecutionSetInput
import com.offlineworkout.data.model.WorkoutDraft
import com.offlineworkout.data.repository.RoomExerciseHistoryRepository
import com.offlineworkout.data.repository.RoomWorkoutRepository
import com.offlineworkout.data.session.WorkoutSessionController
import com.offlineworkout.data.session.WorkoutSessionState
import com.offlineworkout.data.session.WorkoutSessionStatus
import com.offlineworkout.data.timer.RestRequest
import com.offlineworkout.data.timer.RestTimerCommandResult
import com.offlineworkout.data.timer.RestTimerController
import com.offlineworkout.data.timer.RestTimerSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// T6.1: ViewModel against a REAL history repository (in-memory Room):
// prefill flows from persisted latest values and a concluded exercise lands
// atomically. DAO-level atomicity itself is covered in T2 tests.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutExecutionHistoryIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var workouts: RoomWorkoutRepository
    private lateinit var history: RoomExerciseHistoryRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        workouts = RoomWorkoutRepository(db, clock = { 1000L })
        history = RoomExerciseHistoryRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun TestScope.setTestMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    }

    // Room delivers on real background threads while continuations resume on
    // the virtual scheduler: keep a collector subscribed (otherwise the
    // WhileSubscribed upstream stays cold) and poll advancing BOTH clocks
    // (virtual delay pumps continuations, real sleep lets Room I/O land).
    private suspend fun TestScope.awaitState(
        vm: WorkoutExecutionViewModel,
        predicate: (WorkoutExecutionUiState) -> Boolean
    ): WorkoutExecutionUiState? {
        val collectJob = backgroundScope.launch { vm.state.collect {} }
        try {
            repeat(200) {
                val current = vm.state.value
                if (predicate(current)) return current
                runCurrent()
                val pumped = vm.state.value
                if (predicate(pumped)) return pumped
                kotlinx.coroutines.delay(50)
                Thread.sleep(25)
            }
            return null
        } finally {
            collectJob.cancel()
        }
    }

    private class FakeTimer : RestTimerController {
        private val backing = MutableStateFlow<RestTimerSnapshot?>(null)
        override val state: StateFlow<RestTimerSnapshot?> = backing
        val starts = mutableListOf<RestRequest>()
        override fun start(request: RestRequest): RestTimerCommandResult {
            starts.add(request)
            return RestTimerCommandResult.Started
        }

        override fun cancel(timerId: String): RestTimerCommandResult =
            RestTimerCommandResult.Cancelled
    }

    private class FakeSession : WorkoutSessionController {
        // T9.2 (R-12): the contract is closeable, so the composition root can
        // stop the ticker; fakes have nothing to release.
        override fun close() = Unit

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
        override fun startOrResume(workoutId: Long) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
    }

    @Test
    fun prefill_comes_from_persisted_latest_and_save_lands_atomically() = runTest {
        setTestMain()
        val workoutId = workouts.createWorkout(WorkoutDraft("A", "Alpha")).getOrThrow()
        val exerciseId = workouts.createExercise(
            ExerciseInput(
                workoutId = workoutId, name = "Squat", type = ExerciseType.SERIES,
                position = 0, restSeconds = 60, targetSets = 3, targetReps = 10,
                targetWeightKg = null, targetDurationSeconds = null, targetDistanceMeters = null
            )
        ).getOrThrow()
        history.recordSeriesExecution(
            SeriesExecutionInput(
                exerciseId = exerciseId, performedAt = 1000L, completedAt = 1100L,
                sets = listOf(
                    ExecutionSetInput(setNumber = 1, reps = 8, weightKg = 62.5),
                    ExecutionSetInput(setNumber = 2, reps = 8, weightKg = 62.5)
                )
            )
        ).getOrThrow()

        val timer = FakeTimer()
        val vm = WorkoutExecutionViewModel(workoutId, workouts, history, timer, FakeSession())
        // Latest flows come from real Room background queries: wait for prefill.
        val prefilled = awaitState(vm) {
            val ready = it as? WorkoutExecutionUiState.Ready
            ready != null && ready.exercises.singleOrNull()?.seriesSets?.firstOrNull()?.weightKgText == "62.5"
        }
        assertTrue(prefilled != null)
        val form = (prefilled as WorkoutExecutionUiState.Ready).exercises.single()
        // Latest set (setNumber 2) feeds the first blank row.
        assertEquals("62.5", form.seriesSets.first().weightKgText)
        assertEquals("8", form.seriesSets.first().repsText)

        // Conclude two rows: a second atomic execution lands with both sets.
        vm.onRepsChanged(exerciseId, 1, "10")
        vm.onWeightChanged(exerciseId, 1, "65")
        vm.onCompleteSeriesSet(exerciseId, 1)
        vm.onRepsChanged(exerciseId, 2, "9")
        vm.onCompleteSeriesSet(exerciseId, 2)
        vm.onCompleteExercise(exerciseId)
        val done = awaitState(vm) {
            (it as? WorkoutExecutionUiState.Ready)?.exercises?.singleOrNull()?.isCompleted == true
        }
        assertTrue(done != null)
        assertEquals(listOf(RestRequest(workoutId, exerciseId, 60)), timer.starts.takeLast(1))

        val latest = history.observeLatestExecution(exerciseId).first()
        assertEquals(2, latest?.sets?.size)
        assertEquals(listOf(10, 9), latest?.sets?.map { it.reps })
    }
}
