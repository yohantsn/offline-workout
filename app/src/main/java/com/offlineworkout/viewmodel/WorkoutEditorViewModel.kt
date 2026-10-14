package com.offlineworkout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlineworkout.data.config.WorkoutEditorConfig
import com.offlineworkout.data.model.ExerciseAggregateDraft
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.WorkoutAggregateDraft
import com.offlineworkout.data.repository.RepositoryException
import com.offlineworkout.data.repository.RepositoryFailure
import com.offlineworkout.data.repository.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

// Re-export the extracted pure draft types so existing callers (and tests) keep
// their viewmodel.* import paths. The implementation now lives in ui.draft.
typealias EditorField = com.offlineworkout.ui.draft.EditorField
typealias ExerciseFormState = com.offlineworkout.ui.draft.ExerciseFormState
// T4.1: single editor for Create and Edit. Only the WorkoutRepository interface,
// public models and the pure config cross the boundary — never persistence.
enum class MoveDirection { UP, DOWN }

sealed interface WorkoutEditorMode {
    data object Create : WorkoutEditorMode
    data class Edit(val workoutId: Long) : WorkoutEditorMode
}

sealed interface WorkoutEditorUiState {
    data object Loading : WorkoutEditorUiState
    data class Ready(val form: WorkoutEditorFormState) : WorkoutEditorUiState
    data class Saving(val form: WorkoutEditorFormState) : WorkoutEditorUiState
    data class Error(
        val form: WorkoutEditorFormState,
        val failure: RepositoryFailure
    ) : WorkoutEditorUiState
    data object NotFound : WorkoutEditorUiState
}

data class WorkoutEditorFormState(
    val mode: WorkoutEditorMode,
    val codeText: String,
    val nameText: String,
    val exercises: List<ExerciseFormState>,
    val fieldErrors: Map<EditorField, String>,
    val isDirty: Boolean,
    val showDiscardConfirmation: Boolean = false
)

sealed interface WorkoutEditorEffect {
    data class Saved(val workoutId: Long) : WorkoutEditorEffect
    data object Closed : WorkoutEditorEffect
}

class WorkoutEditorViewModel(
    private val repository: WorkoutRepository,
    private val mode: WorkoutEditorMode,
    private val config: WorkoutEditorConfig
) : ViewModel() {

    private val stateFlow = MutableStateFlow<WorkoutEditorUiState>(initialState())
    val state: StateFlow<WorkoutEditorUiState> = stateFlow

    private val effectsChannel = Channel<WorkoutEditorEffect>(Channel.BUFFERED)
    val effects: Flow<WorkoutEditorEffect> = effectsChannel.receiveAsFlow()

    private var initialForm: WorkoutEditorFormState? = null
    private var clientSeq = 0
    private var lastOperation: LastOperation? = null
    private var lastDraft: WorkoutAggregateDraft? = null

    private enum class LastOperation { LOAD, SAVE }

    init {
        if (mode is WorkoutEditorMode.Create) {
            initialForm = blankForm()
        } else {
            load()
        }
    }

    fun onCodeChanged(value: String) {
        mutate { it.copy(codeText = value) }
    }

    fun onWorkoutNameChanged(value: String) {
        mutate { it.copy(nameText = value) }
    }

    fun onAddExercise(type: ExerciseType) {
        mutate { form ->
            form.copy(
                exercises = form.exercises + ExerciseFormState(
                    clientId = nextClientId(),
                    id = null,
                    type = type,
                    nameText = "",
                    restSecondsText = config.defaultRestSeconds.toString(),
                    targetSetsText = "",
                    targetRepsText = "",
                    targetWeightKgText = "",
                    targetDurationSecondsText = "",
                    targetDistanceMetersText = ""
                )
            )
        }
    }

    fun onExerciseChanged(exercise: ExerciseFormState) {
        mutate { form ->
            val old = form.exercises.firstOrNull { it.clientId == exercise.clientId } ?: return@mutate form
            val cleaned = if (exercise.type != old.type) exercise.withClearedInapplicable() else exercise
            form.copy(exercises = form.exercises.map { if (it.clientId == exercise.clientId) cleaned else it })
        }
    }

    fun onMoveExercise(clientId: String, direction: MoveDirection) {
        mutate { form ->
            val index = form.exercises.indexOfFirst { it.clientId == clientId }
            val target = if (direction == MoveDirection.UP) index - 1 else index + 1
            if (index < 0 || target < 0 || target >= form.exercises.size) return@mutate form
            val reordered = form.exercises.toMutableList()
            val item = reordered.removeAt(index)
            reordered.add(target, item)
            form.copy(exercises = reordered)
        }
    }

    fun onSave() {
        val form = currentForm() ?: return
        if (stateFlow.value is WorkoutEditorUiState.Saving) return
        val (errors, draft) = validate(form)
        if (draft == null) {
            setStateForm(form.copy(fieldErrors = errors))
            return
        }
        if (draft.exercises.isEmpty() && !config.allowEmptyWorkout) {
            lastOperation = LastOperation.SAVE
            lastDraft = draft
            stateFlow.value = WorkoutEditorUiState.Error(
                form,
                RepositoryFailure.Validation("workout needs at least one exercise")
            )
            return
        }
        executeSave(form.copy(fieldErrors = emptyMap()), draft)
    }

    fun onCancel() {
        if (stateFlow.value is WorkoutEditorUiState.Saving) return
        val form = currentForm()
        if (form == null || !form.isDirty) {
            effectsChannel.trySend(WorkoutEditorEffect.Closed)
        } else {
            setStateForm(form.copy(showDiscardConfirmation = true))
        }
    }

    fun onConfirmDiscard() {
        effectsChannel.trySend(WorkoutEditorEffect.Closed)
    }

    fun onDismissDiscard() {
        val form = currentForm() ?: return
        setStateForm(form.copy(showDiscardConfirmation = false))
    }

    fun onRetry() {
        val current = stateFlow.value
        if (current is WorkoutEditorUiState.NotFound) {
            load()
            return
        }
        if (current !is WorkoutEditorUiState.Error) return
        when (lastOperation) {
            LastOperation.LOAD -> load()
            LastOperation.SAVE -> {
                val draft = lastDraft ?: return
                executeSave(current.form.copy(fieldErrors = emptyMap()), draft)
            }
            null -> Unit
        }
    }

    private fun initialState(): WorkoutEditorUiState =
        if (mode is WorkoutEditorMode.Create) {
            WorkoutEditorUiState.Ready(blankForm())
        } else {
            WorkoutEditorUiState.Loading
        }

    private fun blankForm() = WorkoutEditorFormState(
        mode = mode,
        codeText = "",
        nameText = "",
        exercises = emptyList(),
        fieldErrors = emptyMap(),
        isDirty = false
    )

    private fun load() {
        val edit = mode as? WorkoutEditorMode.Edit ?: return
        lastOperation = LastOperation.LOAD
        stateFlow.value = WorkoutEditorUiState.Loading
        viewModelScope.launch {
            try {
                val workout = repository.observeWorkout(edit.workoutId).first()
                if (workout == null) {
                    stateFlow.value = WorkoutEditorUiState.NotFound
                    return@launch
                }
                val specs = repository.observeExercises(edit.workoutId).first()
                val form = WorkoutEditorFormState(
                    mode = mode,
                    codeText = workout.code,
                    nameText = workout.name,
                    exercises = specs.map { spec ->
                        ExerciseFormState(
                            clientId = "existing-${spec.id}",
                            id = spec.id,
                            type = spec.type,
                            nameText = spec.name,
                            restSecondsText = spec.restSeconds.toString(),
                            targetSetsText = spec.targetSets?.toString() ?: "",
                            targetRepsText = spec.targetReps?.toString() ?: "",
                            targetWeightKgText = spec.targetWeightKg?.toString() ?: "",
                            targetDurationSecondsText = spec.targetDurationSeconds?.toString() ?: "",
                            targetDistanceMetersText = spec.targetDistanceMeters?.toString() ?: ""
                        )
                    },
                    fieldErrors = emptyMap(),
                    isDirty = false
                )
                initialForm = form
                stateFlow.value = WorkoutEditorUiState.Ready(form)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stateFlow.value = WorkoutEditorUiState.Error(
                    blankForm(),
                    (e as? RepositoryException)?.failure
                        ?: RepositoryFailure.Storage("unexpected data error")
                )
            }
        }
    }

    private fun executeSave(form: WorkoutEditorFormState, draft: WorkoutAggregateDraft) {
        stateFlow.value = WorkoutEditorUiState.Saving(form)
        lastOperation = LastOperation.SAVE
        lastDraft = draft
        viewModelScope.launch {
            val result: Result<Long> = when (mode) {
                is WorkoutEditorMode.Create -> repository.createWorkoutWithExercises(draft)
                is WorkoutEditorMode.Edit ->
                    repository.updateWorkoutWithExercises(mode.workoutId, draft).map { mode.workoutId }
            }
            result.onSuccess { savedId ->
                stateFlow.value = WorkoutEditorUiState.Ready(form)
                effectsChannel.trySend(WorkoutEditorEffect.Saved(savedId))
            }.onFailure { e ->
                val failure = (e as? RepositoryFailure)
                    ?: RepositoryFailure.Storage("unexpected data error")
                val withFieldError = if (failure is RepositoryFailure.Conflict) {
                    form.copy(
                        fieldErrors = mapOf(
                            EditorField.WORKOUT_CODE to (failure.message ?: "conflict")
                        )
                    )
                } else {
                    form
                }
                stateFlow.value = WorkoutEditorUiState.Error(withFieldError, failure)
            }
        }
    }

    private fun currentForm(): WorkoutEditorFormState? = when (val s = stateFlow.value) {
        is WorkoutEditorUiState.Ready -> s.form
        is WorkoutEditorUiState.Error -> s.form
        is WorkoutEditorUiState.Saving -> s.form
        else -> null
    }

    private fun setStateForm(form: WorkoutEditorFormState) {
        when (stateFlow.value) {
            is WorkoutEditorUiState.Ready -> stateFlow.value = WorkoutEditorUiState.Ready(form)
            is WorkoutEditorUiState.Error ->
                stateFlow.value = WorkoutEditorUiState.Error(
                    form,
                    (stateFlow.value as WorkoutEditorUiState.Error).failure
                )
            else -> Unit
        }
    }

    private fun mutate(transform: (WorkoutEditorFormState) -> WorkoutEditorFormState) {
        val current = stateFlow.value
        if (current is WorkoutEditorUiState.Saving ||
            current is WorkoutEditorUiState.Loading ||
            current is WorkoutEditorUiState.NotFound
        ) {
            return
        }
        val form = currentForm() ?: return
        val updated = transform(form)
        val base = initialForm
        setStateForm(updated.copy(isDirty = base?.let { !updated.contentEquals(it) } ?: true))
    }

    private fun WorkoutEditorFormState.contentEquals(other: WorkoutEditorFormState): Boolean =
        mode == other.mode &&
            codeText == other.codeText &&
            nameText == other.nameText &&
            exercises == other.exercises

    private fun nextClientId(): String {
        clientSeq += 1
        return "new-$clientSeq"
    }

    private fun ExerciseFormState.withClearedInapplicable(): ExerciseFormState = when (type) {
        ExerciseType.SERIES -> copy(targetDurationSecondsText = "", targetDistanceMetersText = "")
        ExerciseType.TIMED -> copy(
            targetSetsText = "",
            targetRepsText = "",
            targetWeightKgText = "",
            targetDistanceMetersText = ""
        )
        ExerciseType.DISTANCE_TIME -> copy(targetSetsText = "", targetRepsText = "", targetWeightKgText = "")
    }

    private fun validate(form: WorkoutEditorFormState): Pair<Map<EditorField, String>, WorkoutAggregateDraft?> {
        // Delega a lógica pure extraída em ui.draft, sem duplicar a validação por tipo.
        val draftForm = com.offlineworkout.ui.draft.WorkoutFormState(
            codeText = form.codeText,
            nameText = form.nameText,
            exercises = form.exercises,
            fieldErrors = form.fieldErrors
        )
        return draftForm.validate()
    }
}
