package com.offlineworkout.viewmodel

import com.offlineworkout.data.config.WorkoutEditorConfig
import com.offlineworkout.data.model.ExerciseAggregateDraft
import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.WorkoutAggregateDraft
import com.offlineworkout.data.model.WorkoutDraft
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.data.model.WorkoutUpdate
import com.offlineworkout.data.repository.RepositoryException
import com.offlineworkout.data.repository.RepositoryFailure
import com.offlineworkout.data.repository.WorkoutRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// T4.1: single editor ViewModel for Create/Edit. Fake repository, no Room.
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutEditorViewModelTest {

    private val config = WorkoutEditorConfig(defaultRestSeconds = 0, allowEmptyWorkout = true)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.setTestMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    }

    private fun readyForm(vm: WorkoutEditorViewModel): WorkoutEditorFormState {
        val state = vm.state.value
        assertTrue("expected Ready, got $state", state is WorkoutEditorUiState.Ready)
        return (state as WorkoutEditorUiState.Ready).form
    }

    @Test
    fun create_starts_ready_blank_and_clean() = runTest {
        setTestMain()
        val vm = WorkoutEditorViewModel(FakeEditorRepository(), WorkoutEditorMode.Create, config)
        runCurrent()
        val form = readyForm(vm)
        assertEquals("", form.codeText)
        assertTrue(form.exercises.isEmpty())
        assertFalse(form.isDirty)
    }

    @Test
    fun edit_loads_workout_and_exercises_in_order() = runTest {
        setTestMain()
        val fake = FakeEditorRepository()
        fake.workout.value = WorkoutSummary(7L, "B", "Back Day", 1L)
        fake.exercises.value = listOf(
            spec(1L, "Pull-up", 0),
            spec(2L, "Row", 1)
        )
        fake.loadGate = CompletableDeferred<Unit>()
        val vm = WorkoutEditorViewModel(fake, WorkoutEditorMode.Edit(7L), config)
        runCurrent()
        assertEquals(WorkoutEditorUiState.Loading, vm.state.value)
        fake.loadGate!!.complete(Unit)
        runCurrent()
        val form = readyForm(vm)
        assertEquals("B", form.codeText)
        assertEquals("Back Day", form.nameText)
        assertEquals(listOf(1L, 2L), form.exercises.map { it.id })
        assertFalse(form.isDirty)
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun edit_unknown_id_is_not_found_without_writes() = runTest {
        setTestMain()
        val fake = FakeEditorRepository()
        fake.workout.value = null
        val vm = WorkoutEditorViewModel(fake, WorkoutEditorMode.Edit(999L), config)
        runCurrent()
        assertTrue(vm.state.value is WorkoutEditorUiState.NotFound)
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun load_failure_retries_without_writing() = runTest {
        setTestMain()
        val fake = FakeEditorRepository()
        fake.loadError = RepositoryException(RepositoryFailure.Storage("database unavailable"))
        val vm = WorkoutEditorViewModel(fake, WorkoutEditorMode.Edit(7L), config)
        runCurrent()
        assertTrue(vm.state.value is WorkoutEditorUiState.Error)
        vm.onRetry()
        runCurrent()
        fake.loadError = null
        fake.workout.value = WorkoutSummary(7L, "A", "A", 1L)
        vm.onRetry()
        runCurrent()
        readyForm(vm)
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun add_each_type_creates_defaults() = runTest {
        setTestMain()
        val vm = WorkoutEditorViewModel(FakeEditorRepository(), WorkoutEditorMode.Create, config)
        runCurrent()
        vm.onAddExercise(ExerciseType.SERIES)
        vm.onAddExercise(ExerciseType.TIMED)
        vm.onAddExercise(ExerciseType.DISTANCE_TIME)
        val form = readyForm(vm)
        assertEquals(3, form.exercises.size)
        assertTrue(form.exercises.all { it.id == null && it.restSecondsText == "0" })
        assertEquals(
            listOf(ExerciseType.SERIES, ExerciseType.TIMED, ExerciseType.DISTANCE_TIME),
            form.exercises.map { it.type }
        )
        assertTrue(form.isDirty)
    }

    @Test
    fun type_change_clears_inapplicable_fields() = runTest {
        setTestMain()
        val vm = WorkoutEditorViewModel(FakeEditorRepository(), WorkoutEditorMode.Create, config)
        runCurrent()
        vm.onAddExercise(ExerciseType.TIMED)
        var form = readyForm(vm)
        vm.onExerciseChanged(form.exercises.single().copy(targetDurationSecondsText = "60"))
        form = readyForm(vm)
        assertEquals("60", form.exercises.single().targetDurationSecondsText)
        vm.onExerciseChanged(form.exercises.single().copy(type = ExerciseType.SERIES))
        form = readyForm(vm)
        val changed = form.exercises.single()
        assertEquals(ExerciseType.SERIES, changed.type)
        assertEquals("", changed.targetDurationSecondsText)
    }

    @Test
    fun local_validation_blocks_save_with_field_errors() = runTest {
        setTestMain()
        val fake = FakeEditorRepository()
        val vm = WorkoutEditorViewModel(fake, WorkoutEditorMode.Create, config)
        runCurrent()
        // T9.2 (D-1): `code` is free text, so the blocked case is emptiness.
        vm.onCodeChanged("")
        vm.onAddExercise(ExerciseType.SERIES)
        vm.onSave()
        runCurrent()
        val form = readyForm(vm)
        assertTrue(form.fieldErrors.containsKey(EditorField.WORKOUT_CODE))
        assertTrue(form.fieldErrors.containsKey(EditorField.WORKOUT_NAME))
        assertTrue(form.fieldErrors.containsKey(EditorField.TYPE_FIELDS))
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun move_reorders_locally_with_edge_noops() = runTest {
        setTestMain()
        val vm = WorkoutEditorViewModel(FakeEditorRepository(), WorkoutEditorMode.Create, config)
        runCurrent()
        vm.onAddExercise(ExerciseType.SERIES)
        vm.onAddExercise(ExerciseType.TIMED)
        var form = readyForm(vm)
        val (first, second) = form.exercises
        vm.onMoveExercise(first.clientId, MoveDirection.UP)
        assertEquals(first.clientId, readyForm(vm).exercises.first().clientId)
        vm.onMoveExercise(first.clientId, MoveDirection.DOWN)
        form = readyForm(vm)
        assertEquals(listOf(second.clientId, first.clientId), form.exercises.map { it.clientId })
        vm.onMoveExercise(first.clientId, MoveDirection.DOWN)
        assertEquals(listOf(second.clientId, first.clientId), readyForm(vm).exercises.map { it.clientId })
    }

    @Test
    fun cancel_untouched_closes_cancel_dirty_confirms() = runTest {
        setTestMain()
        val fake = FakeEditorRepository()
        val vm = WorkoutEditorViewModel(fake, WorkoutEditorMode.Create, config)
        runCurrent()
        vm.onCancel()
        assertEquals(WorkoutEditorEffect.Closed, vm.effects.first())

        vm.onCodeChanged("A")
        vm.onCancel()
        assertTrue(readyForm(vm).showDiscardConfirmation)
        assertNull(withTimeoutOrNull(100) { vm.effects.first() })
        vm.onDismissDiscard()
        assertFalse(readyForm(vm).showDiscardConfirmation)
        vm.onCancel()
        vm.onConfirmDiscard()
        assertEquals(WorkoutEditorEffect.Closed, vm.effects.first())
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun save_sends_single_aggregate_and_emits_saved_once() = runTest {
        setTestMain()
        val fake = FakeEditorRepository(createResult = Result.success(42L))
        val vm = WorkoutEditorViewModel(fake, WorkoutEditorMode.Create, config)
        runCurrent()
        vm.onCodeChanged("A")
        vm.onWorkoutNameChanged("Treino A")
        vm.onAddExercise(ExerciseType.SERIES)
        val added = readyForm(vm).exercises.single()
        vm.onExerciseChanged(
            added.copy(nameText = "Squat", restSecondsText = "90", targetSetsText = "3", targetRepsText = "10")
        )
        vm.onSave()
        assertEquals(WorkoutEditorEffect.Saved(42L), vm.effects.first())
        assertEquals(1, fake.writes.size)
        val draft = fake.writes.single()
        assertEquals("A", draft.code)
        assertEquals("Treino A", draft.name)
        assertEquals(1, draft.exercises.size)
        assertEquals(null, draft.exercises.single().id)
        assertEquals("Squat", draft.exercises.single().name)
    }

    @Test
    fun save_failure_keeps_form_and_shows_error() = runTest {
        setTestMain()
        val fake = FakeEditorRepository(
            createResult = Result.failure(RepositoryFailure.Conflict("workout.code already exists"))
        )
        val vm = WorkoutEditorViewModel(fake, WorkoutEditorMode.Create, config)
        runCurrent()
        vm.onCodeChanged("A")
        vm.onWorkoutNameChanged("X")
        vm.onSave()
        runCurrent()
        val state = vm.state.value
        assertTrue(state is WorkoutEditorUiState.Error)
        val error = state as WorkoutEditorUiState.Error
        assertTrue(error.failure is RepositoryFailure.Conflict)
        assertTrue(error.form.fieldErrors.containsKey(EditorField.WORKOUT_CODE))
        assertEquals("A", error.form.codeText)
    }

    @Test
    fun concurrent_saves_are_ignored_while_saving() = runTest {
        setTestMain()
        val gate = CompletableDeferred<Result<Long>>()
        val fake = FakeEditorRepository(deferredCreate = gate)
        val vm = WorkoutEditorViewModel(fake, WorkoutEditorMode.Create, config)
        runCurrent()
        vm.onCodeChanged("A")
        vm.onWorkoutNameChanged("X")
        vm.onSave()
        runCurrent()
        assertTrue(vm.state.value is WorkoutEditorUiState.Saving)
        vm.onSave()
        vm.onCancel()
        runCurrent()
        assertEquals(1, fake.writes.size)
        gate.complete(Result.success(1L))
        assertEquals(WorkoutEditorEffect.Saved(1L), vm.effects.first())
        assertEquals(1, fake.writes.size)
    }

    @Test
    fun empty_workout_allowed_by_config() = runTest {
        setTestMain()
        val fake = FakeEditorRepository(createResult = Result.success(5L))
        val vm = WorkoutEditorViewModel(fake, WorkoutEditorMode.Create, config)
        runCurrent()
        vm.onCodeChanged("C")
        vm.onWorkoutNameChanged("Draft")
        vm.onSave()
        assertEquals(WorkoutEditorEffect.Saved(5L), vm.effects.first())
        assertTrue(fake.writes.single().exercises.isEmpty())
    }

    @Test
    fun empty_workout_blocked_when_config_denies() = runTest {
        setTestMain()
        val strict = config.copy(allowEmptyWorkout = false)
        val fake = FakeEditorRepository()
        val vm = WorkoutEditorViewModel(fake, WorkoutEditorMode.Create, strict)
        runCurrent()
        vm.onCodeChanged("C")
        vm.onWorkoutNameChanged("Draft")
        vm.onSave()
        runCurrent()
        assertTrue(vm.state.value is WorkoutEditorUiState.Error)
        assertTrue(fake.writes.isEmpty())
    }

    private fun spec(id: Long, name: String, position: Int) =
        com.offlineworkout.data.model.ExerciseSpec(
            id = id, workoutId = 7L, name = name, type = ExerciseType.SERIES,
            position = position, restSeconds = 60, targetSets = 3, targetReps = 10,
            targetWeightKg = null, targetDurationSeconds = null, targetDistanceMeters = null
        )

    private class FakeEditorRepository(
        var createResult: Result<Long>? = null,
        var deferredCreate: CompletableDeferred<Result<Long>>? = null,
        var loadError: Throwable? = null,
        var loadGate: CompletableDeferred<Unit>? = null
    ) : WorkoutRepository {
        val workout = MutableStateFlow<WorkoutSummary?>(null)
        val exercises = MutableStateFlow<List<com.offlineworkout.data.model.ExerciseSpec>>(emptyList())
        val writes = mutableListOf<WorkoutAggregateDraft>()

        override fun observeWorkouts(): Flow<List<WorkoutSummary>> =
            kotlinx.coroutines.flow.flow { throw UnsupportedOperationException("not used") }

        override fun observeWorkout(id: Long): Flow<WorkoutSummary?> = kotlinx.coroutines.flow.flow {
            loadGate?.await()
            loadError?.let { throw it }
            emit(workout.value)
        }

        override fun observeExercises(workoutId: Long): Flow<List<com.offlineworkout.data.model.ExerciseSpec>> =
            kotlinx.coroutines.flow.flow {
                loadGate?.await()
                loadError?.let { throw it }
                emit(exercises.value)
            }

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

        override suspend fun createWorkoutWithExercises(input: WorkoutAggregateDraft): Result<Long> {
            writes.add(input)
            deferredCreate?.let { return it.await() }
            return createResult ?: Result.success(99L)
        }

        override suspend fun updateWorkoutWithExercises(workoutId: Long, input: WorkoutAggregateDraft): Result<Unit> {
            writes.add(input)
            return Result.success(Unit)
        }
    }
}
