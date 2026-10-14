package com.offlineworkout.viewmodel

import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.WorkoutAggregateDraft
import com.offlineworkout.data.model.WorkoutDraft
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.data.model.WorkoutUpdate
import com.offlineworkout.data.repository.RepositoryException
import com.offlineworkout.data.repository.RepositoryFailure
import com.offlineworkout.data.repository.WorkoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// T3.1: evolved contract — Loading/Empty/Success/Error, retry, A/B/C order,
// one-shot effects validated against the loaded list. Fake repository, no Room.
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutListViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // Main must share the test scheduler; otherwise ViewModel emissions are
    // posted to a scheduler the test never drives.
    private fun TestScope.setTestMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    }

    @Test
    fun starts_loading_before_first_emission() = runTest {
        setTestMain()
        val vm = WorkoutListViewModel(FakeWorkoutRepository())
        assertEquals(WorkoutListUiState.Loading, vm.state.value)
    }

    @Test
    fun nonempty_emission_is_passed_through_without_reordering() = runTest {
        // T9.3 (R-6): the ViewModel contributes no ordering at all — the
        // repository is the single source and already delivers the list
        // alphabetically by `code`. Free codes are used so nothing here can be
        // explained by a precedence map.
        setTestMain()
        val fake = FakeWorkoutRepository()
        val vm = WorkoutListViewModel(fake)
        val delivered = listOf(
            WorkoutSummary(1L, "A", "Treino A", 1L),
            WorkoutSummary(4L, "D", "Treino D", 4L),
            WorkoutSummary(2L, "B", "Treino B", 2L),
            WorkoutSummary(3L, "C", "Treino C", 3L)
        )
        fake.workouts.value = delivered
        val state = vm.state.first { it is WorkoutListUiState.Success } as WorkoutListUiState.Success
        assertEquals(delivered, state.workouts)
    }

    @Test
    fun viewmodel_does_not_sort_whatever_the_repository_delivers() = runTest {
        setTestMain()
        val fake = FakeWorkoutRepository()
        val vm = WorkoutListViewModel(fake)
        val delivered = listOf(
            WorkoutSummary(3L, "costas", "Costas", 3L),
            WorkoutSummary(1L, "Costas", "Costas maiúsculo", 1L)
        )
        fake.workouts.value = delivered
        val state = vm.state.first { it is WorkoutListUiState.Success } as WorkoutListUiState.Success
        assertEquals(delivered, state.workouts)
    }

    @Test
    fun empty_emission_becomes_empty_not_error() = runTest {
        setTestMain()
        val vm = WorkoutListViewModel(FakeWorkoutRepository())
        assertTrue(vm.state.first { it !is WorkoutListUiState.Loading } is WorkoutListUiState.Empty)
    }

    @Test
    fun repository_failure_becomes_error() = runTest {
        setTestMain()
        val vm = WorkoutListViewModel(
            FakeWorkoutRepository(failWith = RepositoryException(RepositoryFailure.Storage("database unavailable")))
        )
        val state = vm.state.first { it is WorkoutListUiState.Error } as WorkoutListUiState.Error
        assertTrue(state.failure is RepositoryFailure.Storage)
    }

    @Test
    fun unexpected_failure_becomes_stable_storage_error() = runTest {
        setTestMain()
        val vm = WorkoutListViewModel(FakeWorkoutRepository(failWith = IllegalStateException("boom")))
        val state = vm.state.first { it is WorkoutListUiState.Error } as WorkoutListUiState.Error
        assertTrue(state.failure is RepositoryFailure.Storage)
        assertEquals("unexpected data error", state.failure.message)
    }

    @Test
    fun retry_recovers_after_failure() = runTest {
        setTestMain()
        val fake = FakeWorkoutRepository(
            failWith = RepositoryException(RepositoryFailure.Storage("database unavailable"))
        )
        val vm = WorkoutListViewModel(fake)
        val states = mutableListOf<WorkoutListUiState>()
        val job = backgroundScope.launch { vm.state.collect { states.add(it) } }
        runCurrent()
        assertTrue(states.filterIsInstance<WorkoutListUiState.Error>().isNotEmpty())

        fake.failWith = null
        fake.workouts.value = listOf(WorkoutSummary(1L, "A", "Treino A", 1L))
        vm.onRetry()
        runCurrent()
        assertTrue(states.filterIsInstance<WorkoutListUiState.Success>().isNotEmpty())
        assertEquals(
            listOf("A"),
            states.filterIsInstance<WorkoutListUiState.Success>().last().workouts.map { it.code }
        )
        job.cancel()
    }

    @Test
    fun selection_emits_effect_only_for_loaded_id() = runTest {
        setTestMain()
        val fake = FakeWorkoutRepository()
        val vm = WorkoutListViewModel(fake)
        fake.workouts.value = listOf(WorkoutSummary(7L, "A", "Treino A", 1L))
        assertTrue(vm.state.first { it is WorkoutListUiState.Success } is WorkoutListUiState.Success)

        vm.onExecutionSelected(7L)
        assertEquals(WorkoutListEffect.OpenExecution(7L), vm.effects.first())
        vm.onEditSelected(7L)
        assertEquals(WorkoutListEffect.OpenEdit(7L), vm.effects.first())

        vm.onExecutionSelected(999L)
        assertNull(withTimeoutOrNull(100) { vm.effects.first() })
        vm.onEditSelected(999L)
        assertNull(withTimeoutOrNull(100) { vm.effects.first() })
    }

    @Test
    fun history_selected_emits_open_history_for_loaded_id() = runTest {
        setTestMain()
        val fake = FakeWorkoutRepository()
        val vm = WorkoutListViewModel(fake)
        fake.workouts.value = listOf(WorkoutSummary(7L, "A", "Treino A", 1L))
        assertTrue(vm.state.first { it is WorkoutListUiState.Success } is WorkoutListUiState.Success)

        vm.onHistorySelected(7L)
        assertEquals(WorkoutListEffect.OpenHistory(7L), vm.effects.first())

        vm.onHistorySelected(999L)
        assertNull(withTimeoutOrNull(100) { vm.effects.first() })
    }

    @Test
    fun backup_selected_emits_open_backup() = runTest {
        setTestMain()
        val vm = WorkoutListViewModel(FakeWorkoutRepository())
        vm.onBackupSelected()
        assertEquals(WorkoutListEffect.OpenBackup, vm.effects.first())
    }

    @Test
    fun create_selected_emits_open_create() = runTest {
        setTestMain()
        val vm = WorkoutListViewModel(FakeWorkoutRepository())
        vm.onCreateSelected()
        assertEquals(WorkoutListEffect.OpenCreate, vm.effects.first())
    }

    @Test
    fun list_viewmodel_never_writes() = runTest {
        setTestMain()
        val fake = FakeWorkoutRepository()
        val vm = WorkoutListViewModel(fake)
        fake.workouts.value = listOf(WorkoutSummary(1L, "A", "A", 1L))
        vm.state.first { it is WorkoutListUiState.Success }
        vm.onRetry()
        vm.state.first { it is WorkoutListUiState.Success }
        vm.onExecutionSelected(1L)
        vm.effects.first()
        assertTrue(fake.writeCalls.isEmpty())
    }

    private class FakeWorkoutRepository(
        var failWith: Throwable? = null
    ) : WorkoutRepository {
        val workouts = MutableStateFlow<List<WorkoutSummary>>(emptyList())
        val writeCalls = mutableListOf<String>()

        override fun observeWorkouts(): Flow<List<WorkoutSummary>> = flow {
            failWith?.let { throw it }
            emitAll(workouts)
        }

        override fun observeWorkout(id: Long): Flow<WorkoutSummary?> =
            flow { throw UnsupportedOperationException("not used") }

        override fun observeExercises(workoutId: Long): Flow<List<ExerciseSpec>> =
            flow { throw UnsupportedOperationException("not used") }

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
        override suspend fun createWorkoutsWithExercises(inputs: List<WorkoutAggregateDraft>): Result<List<Long>> =
            throw UnsupportedOperationException("not used")

        override suspend fun updateWorkoutWithExercises(workoutId: Long, input: WorkoutAggregateDraft): Result<Unit> =
            throw UnsupportedOperationException("not used")
    }
}
