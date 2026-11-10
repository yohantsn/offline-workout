package com.offlineworkout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlineworkout.data.importer.CsvImportResult
import com.offlineworkout.data.importer.CsvWorkoutParser
import com.offlineworkout.data.importer.ImportFileReader
import com.offlineworkout.data.model.ExerciseAggregateDraft
import com.offlineworkout.data.model.WorkoutAggregateDraft
import com.offlineworkout.data.repository.RepositoryFailure
import com.offlineworkout.data.repository.WorkoutRepository
import com.offlineworkout.ui.draft.EditorField
import com.offlineworkout.ui.draft.ExerciseFormState
import com.offlineworkout.ui.draft.WorkoutFormState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Import flow state machine: idle -> reading -> review -> saving -> done/failed.
// Parsing never writes; the batch is written by createWorkoutsWithExercises,
// which is all-or-nothing, so a rejected draft leaves the database untouched.
sealed interface WorkoutImportUiState {
    data object Idle : WorkoutImportUiState

    data object Reading : WorkoutImportUiState

    data class Review(
        val workouts: List<WorkoutFormState>,
        val warnings: List<String>,
        val fieldErrors: Map<Int, Map<EditorField, String>> = emptyMap(),
        val generalError: String? = null
    ) : WorkoutImportUiState

    data class Saving(val count: Int) : WorkoutImportUiState

    data class Done(val count: Int) : WorkoutImportUiState

    // Errors that block the whole import before any write (unparseable file,
    // read failure, repository rejection). Warnings are kept so the user can
    // still see what the parser had noticed.
    data class Failed(
        val message: String,
        val warnings: List<String> = emptyList()
    ) : WorkoutImportUiState
}

class WorkoutImportViewModel(
    private val repository: WorkoutRepository,
    private val fileReader: ImportFileReader,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val stateFlow = MutableStateFlow<WorkoutImportUiState>(WorkoutImportUiState.Idle)
    val state: StateFlow<WorkoutImportUiState> = stateFlow

    fun onFileSelected(uriString: String) {
        if (uriString.isBlank()) {
            stateFlow.value = WorkoutImportUiState.Idle
            return
        }
        stateFlow.value = WorkoutImportUiState.Reading
        viewModelScope.launch {
            val bytes = fileReader.readBytes(uriString).getOrElse { error ->
                if (error is CancellationException) throw error
                stateFlow.value = WorkoutImportUiState.Failed(
                    "Não foi possível abrir o arquivo. Escolha outro."
                )
                return@launch
            }
            val parsed = withContext(parseDispatcher) { CsvWorkoutParser.parse(bytes) }
            stateFlow.value = parsed.toState()
        }
    }

    private fun CsvImportResult.toState(): WorkoutImportUiState =
        if (canImport) {
            WorkoutImportUiState.Review(
                workouts = workouts.mapIndexed { index, draft -> draft.toFormState(index) },
                warnings = warnings
            )
        } else {
            WorkoutImportUiState.Failed(
                message = errors.ifEmpty { listOf("Nenhum treino encontrado no arquivo.") }.joinToString("\n"),
                warnings = warnings
            )
        }

    fun onCodeChanged(index: Int, value: String) = updateWorkout(index) { it.copy(codeText = value) }

    fun onNameChanged(index: Int, value: String) = updateWorkout(index) { it.copy(nameText = value) }

    private fun updateWorkout(index: Int, edit: (WorkoutFormState) -> WorkoutFormState) {
        val review = stateFlow.value as? WorkoutImportUiState.Review ?: return
        val workouts = review.workouts.toMutableList()
        val current = workouts.getOrNull(index) ?: return
        workouts[index] = edit(current)
        stateFlow.value = review.copy(workouts = workouts, generalError = null)
    }

    fun onConfirm() {
        val review = stateFlow.value as? WorkoutImportUiState.Review ?: return

        val drafts = mutableListOf<WorkoutAggregateDraft>()
        val fieldErrors = mutableMapOf<Int, Map<EditorField, String>>()
        review.workouts.forEachIndexed { index, form ->
            val (errors, draft) = form.validate()
            if (draft == null) fieldErrors[index] = errors else drafts += draft
        }
        if (fieldErrors.isNotEmpty()) {
            stateFlow.value = review.copy(
                fieldErrors = fieldErrors,
                generalError = "Corrija os campos destacados antes de importar."
            )
            return
        }

        stateFlow.value = WorkoutImportUiState.Saving(drafts.size)
        viewModelScope.launch {
            repository.createWorkoutsWithExercises(drafts).fold(
                onSuccess = { ids -> stateFlow.value = WorkoutImportUiState.Done(ids.size) },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    stateFlow.value = WorkoutImportUiState.Failed(
                        message = error.toMessage(),
                        warnings = review.warnings
                    )
                }
            )
        }
    }

    fun onChooseAnotherFile() {
        stateFlow.value = WorkoutImportUiState.Idle
    }

    private fun Throwable.toMessage(): String = when (this) {
        is RepositoryFailure.Conflict ->
            "Um código de treino desta importação já existe. Edite o código e tente de novo."
        is RepositoryFailure.Validation -> "Dados inválidos: $message"
        else -> "Não foi possível salvar os treinos. Tente novamente."
    }

    private fun WorkoutAggregateDraft.toFormState(index: Int): WorkoutFormState =
        WorkoutFormState(
            codeText = code,
            nameText = name,
            exercises = exercises.mapIndexed { exerciseIndex, exercise ->
                exercise.toFormState("$index-$exerciseIndex")
            }
        )

    private fun ExerciseAggregateDraft.toFormState(clientId: String): ExerciseFormState =
        ExerciseFormState(
            clientId = clientId,
            id = id,
            type = type,
            nameText = name,
            restSecondsText = restSeconds.toString(),
            targetSetsText = targetSets?.toString().orEmpty(),
            targetRepsText = targetReps?.toString().orEmpty(),
            targetWeightKgText = targetWeightKg?.toString().orEmpty(),
            targetDurationSecondsText = targetDurationSeconds?.toString().orEmpty(),
            targetDistanceMetersText = targetDistanceMeters?.toString().orEmpty()
        )
}
