package com.offlineworkout.viewmodel

import com.offlineworkout.data.importer.ImportFileReader
import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.WorkoutAggregateDraft
import com.offlineworkout.data.model.WorkoutDraft
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.data.model.WorkoutUpdate
import com.offlineworkout.data.repository.RepositoryFailure
import com.offlineworkout.data.repository.WorkoutRepository
import com.offlineworkout.ui.draft.EditorField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutImportViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.setTestMain() = Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

    private fun dispatcher(scope: TestScope) = UnconfinedTestDispatcher(scope.testScheduler)

    private fun realCsv(): ByteArray =
        javaClass.getResourceAsStream("/importer/aquecimento.csv")!!.readBytes()

    @Test
    fun selecting_a_valid_csv_reaches_review_with_the_parsed_workout() = runTest {
        setTestMain()
        val repository = FakeRepository()
        val vm = WorkoutImportViewModel(
            repository,
            FakeReader(Result.success(realCsv())),
            dispatcher(this)
        )

        vm.onFileSelected("content://doc/1")

        val review = vm.state.first { it is WorkoutImportUiState.Review } as WorkoutImportUiState.Review
        val workout = review.workouts.single()
        assertEquals("AQUECIMENTO", workout.codeText)
        assertEquals("AQUECIMENTO/CARDIO/MOBILIDADE", workout.nameText)
        assertEquals(4, workout.exercises.size)
        assertTrue(review.warnings.any { it.contains("02") })
        assertTrue(repository.batches.isEmpty())
    }

    @Test
    fun confirming_writes_one_batch_and_reaches_done() = runTest {
        setTestMain()
        val repository = FakeRepository()
        val vm = WorkoutImportViewModel(repository, FakeReader(Result.success(realCsv())), dispatcher(this))

        vm.onFileSelected("content://doc/1")
        vm.onConfirm()

        assertEquals(1, repository.batches.size)
        assertEquals(1, repository.batches.single().size)
        val draft = repository.batches.single().single()
        assertEquals("AQUECIMENTO", draft.code)
        assertEquals(4, draft.exercises.size)
        assertEquals(300L, draft.exercises.first().targetDurationSeconds)
        assertEquals(WorkoutImportUiState.Done(1), vm.state.value)
    }

    @Test
    fun the_edited_code_is_what_gets_written() = runTest {
        setTestMain()
        val repository = FakeRepository()
        val vm = WorkoutImportViewModel(repository, FakeReader(Result.success(realCsv())), dispatcher(this))

        vm.onFileSelected("content://doc/1")
        vm.onCodeChanged(0, "AQUECIMENTO-2")
        vm.onNameChanged(0, "Aquecimento revisado")
        vm.onConfirm()

        val draft = repository.batches.single().single()
        assertEquals("AQUECIMENTO-2", draft.code)
        assertEquals("Aquecimento revisado", draft.name)
    }

    @Test
    fun a_blank_code_blocks_the_write_and_marks_the_field() = runTest {
        setTestMain()
        val repository = FakeRepository()
        val vm = WorkoutImportViewModel(repository, FakeReader(Result.success(realCsv())), dispatcher(this))

        vm.onFileSelected("content://doc/1")
        vm.onCodeChanged(0, "   ")
        vm.onConfirm()

        val review = vm.state.value as WorkoutImportUiState.Review
        assertTrue(review.fieldErrors.getValue(0).containsKey(EditorField.WORKOUT_CODE))
        assertTrue(review.generalError != null)
        assertTrue(repository.batches.isEmpty())
    }

    @Test
    fun a_repository_conflict_becomes_a_fix_and_retry_message() = runTest {
        setTestMain()
        val repository = FakeRepository(
            writeFailure = RepositoryFailure.Conflict("workout.code already exists")
        )
        val vm = WorkoutImportViewModel(repository, FakeReader(Result.success(realCsv())), dispatcher(this))

        vm.onFileSelected("content://doc/1")
        vm.onConfirm()

        val failed = vm.state.first { it is WorkoutImportUiState.Failed } as WorkoutImportUiState.Failed
        assertTrue(failed.message.contains("já existe"))
        // The parsed warnings survive the failure so the user still sees them.
        assertTrue(failed.warnings.any { it.contains("02") })
    }

    @Test
    fun an_unreadable_file_fails_without_writing() = runTest {
        setTestMain()
        val repository = FakeRepository()
        val vm = WorkoutImportViewModel(
            repository,
            FakeReader(Result.failure(IllegalStateException("denied"))),
            dispatcher(this)
        )

        vm.onFileSelected("content://doc/1")

        assertTrue(vm.state.value is WorkoutImportUiState.Failed)
        assertTrue(repository.batches.isEmpty())
    }

    @Test
    fun cancelling_the_picker_returns_to_idle() = runTest {
        setTestMain()
        val vm = WorkoutImportViewModel(FakeRepository(), FakeReader(Result.success(realCsv())), dispatcher(this))

        vm.onFileSelected("")

        assertEquals(WorkoutImportUiState.Idle, vm.state.value)
    }

    @Test
    fun a_csv_that_does_not_parse_blocks_the_import_before_any_write() = runTest {
        setTestMain()
        val repository = FakeRepository()
        val broken = """
            ,TREINO
            ,EXERCICIOS,,,,,SERIES,,REP,,DESCANSO
            ,01,AGACHAMENTO,,,,,,,
        """.trimIndent().toByteArray()
        val vm = WorkoutImportViewModel(repository, FakeReader(Result.success(broken)), dispatcher(this))

        vm.onFileSelected("content://doc/1")

        assertTrue(vm.state.value is WorkoutImportUiState.Failed)
        assertTrue(repository.batches.isEmpty())
    }

    @Test
    fun choosing_another_file_after_a_failure_returns_to_idle() = runTest {
        setTestMain()
        val vm = WorkoutImportViewModel(
            FakeRepository(),
            FakeReader(Result.failure(IllegalStateException("denied"))),
            dispatcher(this)
        )

        vm.onFileSelected("content://doc/1")
        vm.onChooseAnotherFile()

        assertEquals(WorkoutImportUiState.Idle, vm.state.value)
    }

    private class FakeReader(private val result: Result<ByteArray>) : ImportFileReader {
        override suspend fun readBytes(uriString: String): Result<ByteArray> = result
    }

    private class FakeRepository(
        private val writeFailure: Throwable? = null
    ) : WorkoutRepository {
        val batches = mutableListOf<List<WorkoutAggregateDraft>>()

        override fun observeWorkouts(): Flow<List<WorkoutSummary>> =
            throw UnsupportedOperationException("not used")

        override fun observeWorkout(id: Long): Flow<WorkoutSummary?> =
            throw UnsupportedOperationException("not used")

        override fun observeExercises(workoutId: Long): Flow<List<ExerciseSpec>> =
            throw UnsupportedOperationException("not used")

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

        override suspend fun createWorkoutsWithExercises(
            inputs: List<WorkoutAggregateDraft>
        ): Result<List<Long>> {
            writeFailure?.let { return Result.failure(it) }
            batches += inputs
            return Result.success(inputs.indices.map { it + 1L })
        }

        override suspend fun updateWorkoutWithExercises(
            workoutId: Long,
            input: WorkoutAggregateDraft
        ): Result<Unit> = throw UnsupportedOperationException("not used")
    }
}
