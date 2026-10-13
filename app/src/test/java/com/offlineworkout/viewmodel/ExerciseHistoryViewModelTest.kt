package com.offlineworkout.viewmodel

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
import com.offlineworkout.data.repository.ExerciseHistoryRepository
import com.offlineworkout.data.repository.RepositoryException
import com.offlineworkout.data.repository.RepositoryFailure
import com.offlineworkout.data.repository.WorkoutRepository
import com.offlineworkout.data.model.DistanceTimeExecutionInput
import com.offlineworkout.data.model.SeriesExecutionInput
import com.offlineworkout.data.model.TimedExecutionInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// T7.1: history ViewModel against fakes: selection, paging without dupes,
// stats warning, retry, NotFound and navigation effects. No Room.
@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseHistoryViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.setTestMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    }

    private fun seriesSpec(id: Long, name: String, position: Int) = ExerciseSpec(
        id = id, workoutId = 7L, name = name, type = ExerciseType.SERIES,
        position = position, restSeconds = 60, targetSets = 3, targetReps = 10,
        targetWeightKg = null, targetDurationSeconds = null, targetDistanceMeters = null
    )

    private fun execution(id: Long, performedAt: Long, sets: Int = 1) = ExerciseExecutionView(
        id = id,
        exerciseId = 1L,
        performedAt = performedAt,
        completedAt = performedAt + 10L,
        durationSeconds = null,
        distanceMeters = null,
        sets = (1..sets).map { ExecutionSetView(it, reps = 10, weightKg = 50.0) }
    )

    private fun ready(vm: ExerciseHistoryViewModel): ExerciseHistoryUiState.Ready {
        val state = vm.state.value
        assertTrue("expected Ready, got $state", state is ExerciseHistoryUiState.Ready)
        return state as ExerciseHistoryUiState.Ready
    }

    @Test
    fun selection_view_without_initial_id() = runTest {
        setTestMain()
        val workouts = FakeHistoryWorkoutRepository()
        workouts.workout.value = WorkoutSummary(7L, "A", "Alpha", 1L)
        workouts.exercises.value = listOf(seriesSpec(1L, "Squat", 0))
        val vm = ExerciseHistoryViewModel(7L, null, workouts, FakeHistoryRepository())
        val state = vm.state.first { it is ExerciseHistoryUiState.Ready } as ExerciseHistoryUiState.Ready
        assertNull(state.selectedExerciseId)
        assertTrue(state.rows.isEmpty())
        assertNull(state.chart)
        assertNull(state.stats)
    }

    @Test
    fun initial_id_loads_page_chart_and_stats() = runTest {
        setTestMain()
        val workouts = FakeHistoryWorkoutRepository()
        workouts.workout.value = WorkoutSummary(7L, "A", "Alpha", 1L)
        workouts.exercises.value = listOf(seriesSpec(1L, "Squat", 0))
        val history = FakeHistoryRepository()
        history.pages[1L] = listOf(execution(11L, 2000L), execution(10L, 1000L))
        history.stats[1L] = HistoryStats(1L, 2, 1000L, 2000L)
        val vm = ExerciseHistoryViewModel(7L, 1L, workouts, history)
        val state = vm.state.first {
            it is ExerciseHistoryUiState.Ready && it.rows.size == 2 && it.stats != null
        } as ExerciseHistoryUiState.Ready
        assertEquals(1L, state.selectedExerciseId)
        assertEquals(listOf(11L, 10L), state.rows.map { it.id })
        assertEquals(2, state.stats?.count)
        assertTrue(state.chart != null)
        assertEquals(ExerciseType.SERIES, state.chart?.type)
        assertEquals("kg", state.chart?.unitPrimary)
        assertEquals(false, state.chart?.wasDownsampled)
        // Page query used limit 50 offset 0.
        assertEquals(0, history.pageQueries.single().offset)
        assertEquals(50, history.pageQueries.single().limit)
    }

    @Test
    fun select_resets_and_navigates() = runTest {
        setTestMain()
        val workouts = FakeHistoryWorkoutRepository()
        workouts.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        workouts.exercises.value = listOf(seriesSpec(1L, "One", 0), seriesSpec(2L, "Two", 1))
        val history = FakeHistoryRepository()
        history.pages[2L] = listOf(execution(20L, 3000L))
        val vm = ExerciseHistoryViewModel(7L, null, workouts, history)
        vm.state.first { it is ExerciseHistoryUiState.Ready }
        vm.onSelectExercise(2L)
        assertEquals(
            ExerciseHistoryEffect.OpenHistory(7L, 2L),
            vm.effects.first()
        )
        val state = vm.state.first {
            it is ExerciseHistoryUiState.Ready && it.selectedExerciseId == 2L && it.rows.size == 1
        } as ExerciseHistoryUiState.Ready
        assertEquals(listOf(20L), state.rows.map { it.id })
    }

    @Test
    fun stale_selection_results_are_discarded() = runTest {
        setTestMain()
        val workouts = FakeHistoryWorkoutRepository()
        workouts.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        workouts.exercises.value = listOf(
            seriesSpec(1L, "One", 0),
            ExerciseSpec(
                id = 2L, workoutId = 7L, name = "Two", type = ExerciseType.TIMED,
                position = 1, restSeconds = 0, targetSets = null, targetReps = null,
                targetWeightKg = null, targetDurationSeconds = 60L, targetDistanceMeters = null
            )
        )
        val history = FakeHistoryRepository()
        // Window data resolves immediately per selection (different chart units).
        history.pages[1L] = listOf(
            ExerciseExecutionView(
                id = 10L, exerciseId = 1L, performedAt = 1000L, completedAt = 1100L,
                durationSeconds = null, distanceMeters = null,
                sets = listOf(ExecutionSetView(1, 10, 60.0))
            )
        )
        history.pages[2L] = listOf(
            ExerciseExecutionView(
                id = 20L, exerciseId = 2L, performedAt = 2000L, completedAt = 2060L,
                durationSeconds = 60L, distanceMeters = null, sets = emptyList()
            )
        )
        val gate1 = kotlinx.coroutines.CompletableDeferred<List<ExerciseExecutionView>>()
        val gate2 = kotlinx.coroutines.CompletableDeferred<List<ExerciseExecutionView>>()
        history.pageGates[1L] = gate1
        history.pageGates[2L] = gate2
        val vm = ExerciseHistoryViewModel(7L, null, workouts, history)
        vm.state.first { it is ExerciseHistoryUiState.Ready }

        vm.onSelectExercise(1L)
        runCurrent()
        vm.onSelectExercise(2L)
        runCurrent()
        // Latest selection resolves first.
        gate2.complete(history.pages[2L]!!)
        runCurrent()
        vm.state.first {
            it is ExerciseHistoryUiState.Ready && it.selectedExerciseId == 2L && it.rows.size == 1
        }
        // Drain every coroutine that is runnable, then read the settled state:
        // the page and the chart are two independent emissions and the order in
        // which they land is not the contract under test.
        runCurrent()
        runCurrent()
        java.io.File("/tmp/histstate.txt").writeText("DBG showingTwo=" + vm.state.value.toString().take(400))
        val showingTwo = ready(vm)
        assertEquals(2L, showingTwo.selectedExerciseId)
        assertEquals(listOf(20L), showingTwo.rows.map { it.id })
        assertEquals("s", showingTwo.chart?.unitPrimary)

        // Stale selection resolves late: rows, chart and units must stay on 2.
        gate1.complete(history.pages[1L]!!)
        runCurrent()
        val stillTwo = ready(vm)
        assertEquals(2L, stillTwo.selectedExerciseId)
        assertEquals(listOf(20L), stillTwo.rows.map { it.id })
        assertEquals("s", stillTwo.chart?.unitPrimary)
    }

    @Test
    fun stale_window_and_stats_results_are_discarded() = runTest {
        // T9.2 (B-2 residual): the window/stats guards were untested because the
        // fake had no gates for them. Exercise 2's window and stats resolve only
        // after exercise 1 was selected, and the screen must keep describing 1.
        setTestMain()
        val workouts = FakeHistoryWorkoutRepository()
        workouts.workout.value = WorkoutSummary(7L, "A", "Alpha", 1L)
        workouts.exercises.value = listOf(seriesSpec(1L, "One", 0), seriesSpec(2L, "Two", 1))
        val history = FakeHistoryRepository()
        history.pages[1L] = listOf(execution(10L, 1000L))
        history.pages[2L] = listOf(
            ExerciseExecutionView(
                id = 20L, exerciseId = 2L, performedAt = 2000L, completedAt = 2060L,
                durationSeconds = 60L, distanceMeters = null,
                sets = listOf(ExecutionSetView(1, 10, 60.0))
            )
        )
        val windowGate = kotlinx.coroutines.CompletableDeferred<List<ExerciseExecutionView>>()
        val statsGate = kotlinx.coroutines.CompletableDeferred<HistoryStats>()
        history.windowGates[2L] = windowGate
        history.statsGates[2L] = statsGate
        val vm = ExerciseHistoryViewModel(7L, 1L, workouts, history)
        val showingOne = vm.state.first {
            it is ExerciseHistoryUiState.Ready && it.selectedExerciseId == 1L && it.rows.size == 1
        } as ExerciseHistoryUiState.Ready
        assertNotNull("the settled selection must have a chart", showingOne.chart)

        // Exercise 2 is selected: its chart/stats stay pending.
        vm.onSelectExercise(2L)
        runCurrent()
        // Exercise 1 wins the selection while 2 is still in flight.
        vm.onSelectExercise(1L)
        runCurrent()
        val backToOne = ready(vm)
        assertEquals(1L, backToOne.selectedExerciseId)

        // The stale window and stats of exercise 2 land late.
        windowGate.complete(history.pages[2L]!!)
        statsGate.complete(HistoryStats(2L, 99, 1L, 1L))
        runCurrent()

        val stillOne = ready(vm)
        assertEquals(1L, stillOne.selectedExerciseId)
        assertEquals(listOf(10L), stillOne.rows.map { it.id })
        assertNotNull("the chart of the current exercise must survive", stillOne.chart)
        assertEquals(1L, stillOne.chart?.points?.first()?.exerciseId)
        assertNotEquals(
            "stale stats of the previous exercise must not reach the screen",
            99,
            stillOne.stats?.count
        )
    }

    @Test
    fun stale_reload_does_not_overwrite_a_newer_selection() = runTest {
        // T9.2 (R-8): loadAll suspends on the workout read before it writes.
        // A reload that started before a selection must not overwrite the newer
        // selection with a stale NotFound when its read finally resolves.
        setTestMain()
        val workouts = FakeHistoryWorkoutRepository()
        workouts.workout.value = WorkoutSummary(7L, "A", "Alpha", 1L)
        workouts.exercises.value = listOf(seriesSpec(1L, "One", 0), seriesSpec(2L, "Two", 1))
        val history = FakeHistoryRepository()
        history.pages[1L] = listOf(
            ExerciseExecutionView(
                id = 10L, exerciseId = 1L, performedAt = 1000L, completedAt = 1060L,
                durationSeconds = 60L, distanceMeters = null, sets = emptyList()
            )
        )
        history.pages[2L] = listOf(
            ExerciseExecutionView(
                id = 20L, exerciseId = 2L, performedAt = 2000L, completedAt = 2060L,
                durationSeconds = 60L, distanceMeters = null, sets = emptyList()
            )
        )
        val vm = ExerciseHistoryViewModel(7L, 1L, workouts, history)
        val first = vm.state.first {
            it is ExerciseHistoryUiState.Ready && it.selectedExerciseId == 1L
        } as ExerciseHistoryUiState.Ready
        assertEquals(1L, first.selectedExerciseId)

        // Start a reload and hold its workout read suspended.
        val gate = kotlinx.coroutines.CompletableDeferred<WorkoutSummary?>()
        workouts.gatedWorkout = gate
        vm.onRetry()
        runCurrent()

        // The user selects the other exercise while the reload is in flight.
        vm.onSelectExercise(2L)
        runCurrent()
        val afterSelection = vm.state.first {
            it is ExerciseHistoryUiState.Ready && it.selectedExerciseId == 2L
        } as ExerciseHistoryUiState.Ready
        assertEquals(2L, afterSelection.selectedExerciseId)

        // The stale reload resolves with no workout: without the generation
        // guard it would paint NotFound over the current selection.
        gate.complete(null)
        runCurrent()

        val final = ready(vm)
        assertEquals(2L, final.selectedExerciseId)
        assertEquals(listOf(20L), final.rows.map { it.id })
    }

    @Test
    fun unknown_selection_is_ignored() = runTest {
        setTestMain()
        val workouts = FakeHistoryWorkoutRepository()
        workouts.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        workouts.exercises.value = listOf(seriesSpec(1L, "One", 0))
        val vm = ExerciseHistoryViewModel(7L, 1L, workouts, FakeHistoryRepository())
        vm.state.first { it is ExerciseHistoryUiState.Ready }
        vm.onSelectExercise(999L)
        runCurrent()
        assertEquals(1L, ready(vm).selectedExerciseId)
    }

    @Test
    fun foreign_initial_id_is_not_found_without_history_calls() = runTest {
        setTestMain()
        val workouts = FakeHistoryWorkoutRepository()
        workouts.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        workouts.exercises.value = listOf(seriesSpec(1L, "One", 0))
        val history = FakeHistoryRepository()
        val vm = ExerciseHistoryViewModel(7L, 999L, workouts, history)
        assertTrue(vm.state.first { it is ExerciseHistoryUiState.NotFound } is ExerciseHistoryUiState.NotFound)
        assertTrue(history.pageQueries.isEmpty())
    }

    @Test
    fun missing_workout_is_not_found() = runTest {
        setTestMain()
        val vm = ExerciseHistoryViewModel(7L, null, FakeHistoryWorkoutRepository(), FakeHistoryRepository())
        assertTrue(vm.state.first { it is ExerciseHistoryUiState.NotFound } is ExerciseHistoryUiState.NotFound)
    }

    @Test
    fun load_more_appends_without_dupes_and_tracks_has_more() = runTest {
        setTestMain()
        val workouts = FakeHistoryWorkoutRepository()
        workouts.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        workouts.exercises.value = listOf(seriesSpec(1L, "Squat", 0))
        val history = FakeHistoryRepository()
        history.pageProvider = { query ->
            when (query.offset) {
                0 -> (1..50).map { execution(100L + it, 10_000L + it) }
                else -> listOf(execution(200L, 20_000L))
            }
        }
        val vm = ExerciseHistoryViewModel(7L, 1L, workouts, history)
        var state = vm.state.first {
            it is ExerciseHistoryUiState.Ready && it.rows.size == 50 && it.hasMore
        } as ExerciseHistoryUiState.Ready
        assertEquals(false, state.isLoadingMore)

        vm.onLoadMore()
        state = vm.state.first {
            it is ExerciseHistoryUiState.Ready && it.rows.size == 51 && !it.hasMore
        } as ExerciseHistoryUiState.Ready
        assertEquals(1, state.rows.count { it.id == 200L })
        assertEquals(listOf(0, 50), history.pageQueries.map { it.offset })
    }

    @Test
    fun first_page_failure_is_blocking_error_with_retry() = runTest {
        setTestMain()
        val workouts = FakeHistoryWorkoutRepository()
        workouts.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        workouts.exercises.value = listOf(seriesSpec(1L, "Squat", 0))
        val history = FakeHistoryRepository()
        history.pageError = RepositoryException(RepositoryFailure.Storage("down"))
        val vm = ExerciseHistoryViewModel(7L, 1L, workouts, history)
        val error = vm.state.first { it is ExerciseHistoryUiState.Error } as ExerciseHistoryUiState.Error
        assertTrue(error.failure is RepositoryFailure.Storage)

        history.pageError = null
        history.pages[1L] = listOf(execution(10L, 1000L))
        vm.onRetry()
        val ready = vm.state.first {
            it is ExerciseHistoryUiState.Ready && it.rows.size == 1
        } as ExerciseHistoryUiState.Ready
        assertEquals(listOf(10L), ready.rows.map { it.id })
    }

    @Test
    fun stats_failure_is_non_blocking_warning() = runTest {
        setTestMain()
        val workouts = FakeHistoryWorkoutRepository()
        workouts.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        workouts.exercises.value = listOf(seriesSpec(1L, "Squat", 0))
        val history = FakeHistoryRepository()
        history.pages[1L] = listOf(execution(10L, 1000L))
        history.statsError = RepositoryException(RepositoryFailure.Storage("down"))
        val vm = ExerciseHistoryViewModel(7L, 1L, workouts, history)
        val state = vm.state.first {
            it is ExerciseHistoryUiState.Ready && it.rows.size == 1 && it.error is HistoryError.Stats
        } as ExerciseHistoryUiState.Ready
        assertEquals(1, state.rows.size)
    }

    @Test
    fun empty_history_renders_empty_ready() = runTest {
        setTestMain()
        val workouts = FakeHistoryWorkoutRepository()
        workouts.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        workouts.exercises.value = listOf(seriesSpec(1L, "Squat", 0))
        val vm = ExerciseHistoryViewModel(7L, 1L, workouts, FakeHistoryRepository())
        val state = vm.state.first {
            it is ExerciseHistoryUiState.Ready && it.selectedExerciseId == 1L && it.stats != null
        } as ExerciseHistoryUiState.Ready
        assertTrue(state.rows.isEmpty())
        assertNull(state.chart)
        assertEquals(0, state.stats?.count)
        assertEquals(false, state.hasMore)
    }

    @Test
    fun back_emits_effect() = runTest {
        setTestMain()
        val vm = ExerciseHistoryViewModel(
            7L, null, FakeHistoryWorkoutRepository(), FakeHistoryRepository()
        )
        vm.onBack()
        assertEquals(ExerciseHistoryEffect.Back, vm.effects.first())
    }

    @Test
    fun clear_returns_to_selection() = runTest {
        setTestMain()
        val workouts = FakeHistoryWorkoutRepository()
        workouts.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        workouts.exercises.value = listOf(seriesSpec(1L, "One", 0))
        val history = FakeHistoryRepository()
        history.pages[1L] = listOf(execution(10L, 1000L))
        val vm = ExerciseHistoryViewModel(7L, 1L, workouts, history)
        vm.state.first { it is ExerciseHistoryUiState.Ready && (it as ExerciseHistoryUiState.Ready).rows.isNotEmpty() }
        vm.onClearExercise()
        val cleared = ready(vm)
        assertNull(cleared.selectedExerciseId)
        assertTrue(cleared.rows.isEmpty())
    }

    // ---- Fakes ----

    private class FakeHistoryWorkoutRepository : WorkoutRepository {
        val workout = MutableStateFlow<WorkoutSummary?>(null)
        val exercises = MutableStateFlow<List<ExerciseSpec>>(emptyList())
        // T9.2 (R-8): lets a test hold the initial load suspended.
        var gatedWorkout: kotlinx.coroutines.CompletableDeferred<WorkoutSummary?>? = null

        override fun observeWorkouts(): Flow<List<WorkoutSummary>> =
            throw UnsupportedOperationException("not used")

        override fun observeWorkout(id: Long): Flow<WorkoutSummary?> {
            gatedWorkout?.let { gate ->
                return kotlinx.coroutines.flow.flow { emit(gate.await()) }
            }
            return workout
        }
        override fun observeExercises(workoutId: Long): Flow<List<ExerciseSpec>> = exercises

        override suspend fun createWorkout(input: WorkoutDraft): Result<Long> =
            throw UnsupportedOperationException("not used")

        override suspend fun updateWorkout(id: Long, input: WorkoutUpdate): Result<Unit> =
            throw UnsupportedOperationException("not used")

        override suspend fun deleteWorkout(id: Long): Result<Unit> =
            throw UnsupportedOperationException("not used")

        override suspend fun createExercise(input: ExerciseInput): Result<Long> =
            throw UnsupportedOperationException("not used")

        override suspend fun updateExercise(id: Long, input: ExerciseInput): Result<Unit> =
            throw UnsupportedOperationException("not used")

        override suspend fun deleteExercise(id: Long): Result<Unit> =
            throw UnsupportedOperationException("not used")

        override suspend fun reorderExercises(workoutId: Long, orderedExerciseIds: List<Long>): Result<Unit> =
            throw UnsupportedOperationException("not used")

        override suspend fun createWorkoutWithExercises(input: WorkoutAggregateDraft): Result<Long> =
            throw UnsupportedOperationException("not used")

        override suspend fun updateWorkoutWithExercises(workoutId: Long, input: WorkoutAggregateDraft): Result<Unit> =
            throw UnsupportedOperationException("not used")
    }

    private class FakeHistoryRepository : ExerciseHistoryRepository {
        val pages = mutableMapOf<Long, List<ExerciseExecutionView>>()
        val stats = mutableMapOf<Long, HistoryStats>()
        var pageProvider: ((HistoryQuery) -> List<ExerciseExecutionView>)? = null
        val pageGates = mutableMapOf<Long, kotlinx.coroutines.CompletableDeferred<List<ExerciseExecutionView>>>()
        // T9.2 (B-2 residual): window and stats get gates too, so the stale
        // result guards of loadChartStats can be exercised, not just the page one.
        val windowGates = mutableMapOf<Long, kotlinx.coroutines.CompletableDeferred<List<ExerciseExecutionView>>>()
        val statsGates = mutableMapOf<Long, kotlinx.coroutines.CompletableDeferred<HistoryStats>>()
        var pageError: Throwable? = null
        var statsError: Throwable? = null
        val pageQueries = mutableListOf<HistoryQuery>()

        override fun observeHistory(exerciseId: Long): Flow<List<ExerciseExecutionView>> =
            throw UnsupportedOperationException("not used")

        override fun observeLatestExecution(exerciseId: Long): Flow<ExerciseExecutionView?> =
            throw UnsupportedOperationException("not used")

        override fun observeLatestSet(exerciseId: Long): Flow<com.offlineworkout.data.model.ExecutionSetView?> =
            throw UnsupportedOperationException("not used")

        override fun observeHistoryPage(query: HistoryQuery): Flow<List<ExerciseExecutionView>> {
            pageQueries.add(query)
            pageError?.let { throw it }
            val gate = pageGates[query.exerciseId]
            if (gate != null) return kotlinx.coroutines.flow.flow { emit(gate.await()) }
            return kotlinx.coroutines.flow.flowOf(
                pageProvider?.invoke(query) ?: pages[query.exerciseId].orEmpty()
            )
        }

        override fun observeHistoryWindow(
            exerciseId: Long,
            fromWallClockMs: Long?,
            toWallClockMs: Long?
        ): Flow<List<ExerciseExecutionView>> {
            windowGates[exerciseId]?.let { gate ->
                return kotlinx.coroutines.flow.flow { emit(gate.await()) }
            }
            return kotlinx.coroutines.flow.flowOf(pages[exerciseId].orEmpty())
        }

        override fun observeHistoryStats(exerciseId: Long): Flow<HistoryStats> {
            statsError?.let { throw it }
            statsGates[exerciseId]?.let { gate ->
                return kotlinx.coroutines.flow.flow { emit(gate.await()) }
            }
            return kotlinx.coroutines.flow.flowOf(
                stats[exerciseId] ?: HistoryStats(exerciseId, 0, null, null)
            )
        }

        override suspend fun recordSeriesExecution(input: SeriesExecutionInput): Result<Long> =
            throw UnsupportedOperationException("not used")

        override suspend fun recordTimedExecution(input: TimedExecutionInput): Result<Long> =
            throw UnsupportedOperationException("not used")

        override suspend fun recordDistanceTimeExecution(input: DistanceTimeExecutionInput): Result<Long> =
            throw UnsupportedOperationException("not used")

        override suspend fun deleteExecution(executionId: Long): Result<Unit> =
            throw UnsupportedOperationException("not used")
    }
}
