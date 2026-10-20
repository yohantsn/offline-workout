package com.offlineworkout.ui.editor

import com.offlineworkout.ui.MissingCause
import com.offlineworkout.ui.MissingDestinationScreen
import com.offlineworkout.ui.MissingRecord
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.offlineworkout.R
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.ui.components.AppTopBar
import com.offlineworkout.ui.components.ErrorCard
import com.offlineworkout.ui.components.PrimaryButton
import com.offlineworkout.ui.components.TypeBadgeTone
import com.offlineworkout.ui.draft.EditorField
import com.offlineworkout.ui.draft.ExerciseFormState
import com.offlineworkout.viewmodel.MoveDirection
import com.offlineworkout.viewmodel.WorkoutEditorMode
import com.offlineworkout.viewmodel.WorkoutEditorUiState

// T4.1 + redesign lote 6: TopAppBar com Salvar, exercícios colapsados (um
// expandido por vez), seletor de tipo em cards inline, barra inferior fixa,
// validação por campo. Sem mudança de assinatura (12 params); isDirty e
// descarte intactos. Sem persistência, sem Room.
@Composable
fun WorkoutEditorScreen(
    state: WorkoutEditorUiState,
    onCodeChanged: (String) -> Unit,
    onWorkoutNameChanged: (String) -> Unit,
    onAddExercise: (ExerciseType) -> Unit,
    onExerciseChanged: (ExerciseFormState) -> Unit,
    onMoveExercise: (String, MoveDirection) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissDiscard: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    val form = (state as? WorkoutEditorUiState.Ready)?.form
        ?: (state as? WorkoutEditorUiState.Saving)?.form
        ?: (state as? WorkoutEditorUiState.Error)?.form
    val saving = state is WorkoutEditorUiState.Saving
    Scaffold(
        modifier = modifier,
        topBar = {
            if (form != null) {
                // "Cancelar" redundante com "Voltar" removido (ED-1): o voltar
                // segue o fluxo de descarte (onCancel confirma se dirty).
                AppTopBar(
                    title = form.mode.title(),
                    onBack = onCancel,
                    actions = {
                        TextButton(
                            onClick = onSave,
                            enabled = !saving,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Text(text = stringResource(R.string.editor_save))
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (form != null) {
                Surface(shadowElevation = 2.dp) {
                    PrimaryButton(
                        text = stringResource(R.string.editor_add),
                        onClick = { showPicker = true },
                        enabled = !saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }
            }
        }
    ) { padding ->
        when (state) {
            WorkoutEditorUiState.Loading -> LoadingBlock(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            WorkoutEditorUiState.NotFound -> NotFoundBlock(
                onBack = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            is WorkoutEditorUiState.Ready -> EditorForm(
                formCode = state.form.codeText,
                formName = state.form.nameText,
                exercises = state.form.exercises,
                fieldErrors = state.form.fieldErrors,
                saving = false,
                errorMessage = null,
                showDiscardConfirmation = state.form.showDiscardConfirmation,
                showPicker = showPicker,
                onPickerVisibilityChange = { showPicker = it },
                onCodeChanged = onCodeChanged,
                onWorkoutNameChanged = onWorkoutNameChanged,
                onAddExercise = onAddExercise,
                onExerciseChanged = onExerciseChanged,
                onMoveExercise = onMoveExercise,
                onRetry = onRetry,
                onConfirmDiscard = onConfirmDiscard,
                onDismissDiscard = onDismissDiscard,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            is WorkoutEditorUiState.Saving -> EditorForm(
                formCode = state.form.codeText,
                formName = state.form.nameText,
                exercises = state.form.exercises,
                fieldErrors = state.form.fieldErrors,
                saving = true,
                errorMessage = null,
                showDiscardConfirmation = false,
                showPicker = showPicker,
                onPickerVisibilityChange = { showPicker = it },
                onCodeChanged = onCodeChanged,
                onWorkoutNameChanged = onWorkoutNameChanged,
                onAddExercise = onAddExercise,
                onExerciseChanged = onExerciseChanged,
                onMoveExercise = onMoveExercise,
                onRetry = onRetry,
                onConfirmDiscard = onConfirmDiscard,
                onDismissDiscard = onDismissDiscard,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            is WorkoutEditorUiState.Error -> EditorForm(
                formCode = state.form.codeText,
                formName = state.form.nameText,
                exercises = state.form.exercises,
                fieldErrors = state.form.fieldErrors,
                saving = false,
                errorMessage = stringResource(R.string.editor_error),
                showDiscardConfirmation = state.form.showDiscardConfirmation,
                showPicker = showPicker,
                onPickerVisibilityChange = { showPicker = it },
                onCodeChanged = onCodeChanged,
                onWorkoutNameChanged = onWorkoutNameChanged,
                onAddExercise = onAddExercise,
                onExerciseChanged = onExerciseChanged,
                onMoveExercise = onMoveExercise,
                onRetry = onRetry,
                onConfirmDiscard = onConfirmDiscard,
                onDismissDiscard = onDismissDiscard,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

@Composable
private fun WorkoutEditorMode.title(): String = when (this) {
    is WorkoutEditorMode.Create -> stringResource(R.string.editor_new)
    is WorkoutEditorMode.Edit -> stringResource(R.string.editor_edit)
}

@Composable
private fun LoadingBlock(modifier: Modifier = Modifier) {
    val loadingDescription = stringResource(R.string.editor_loading_cd)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.semantics {
            contentDescription = loadingDescription
            liveRegion = LiveRegionMode.Polite
        }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.editor_loading_text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun NotFoundBlock(onBack: () -> Unit, modifier: Modifier = Modifier) {
    // T9.2 (R-20): one shared block for "record not found".
    MissingDestinationScreen(
        record = MissingRecord.WORKOUT,
        cause = MissingCause.NOT_FOUND,
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
@Suppress("LongParameterList")
private fun EditorForm(
    formCode: String,
    formName: String,
    exercises: List<ExerciseFormState>,
    fieldErrors: Map<EditorField, String>,
    saving: Boolean,
    errorMessage: String?,
    showDiscardConfirmation: Boolean,
    showPicker: Boolean,
    onPickerVisibilityChange: (Boolean) -> Unit,
    onCodeChanged: (String) -> Unit,
    onWorkoutNameChanged: (String) -> Unit,
    onAddExercise: (ExerciseType) -> Unit,
    onExerciseChanged: (ExerciseFormState) -> Unit,
    onMoveExercise: (String, MoveDirection) -> Unit,
    onRetry: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Colapsados por padrão (null): só um expandido por vez.
    var expandedId by remember { mutableStateOf<String?>(null) }
    // T9.3 (bloqueio 1): a lista é LazyColumn com Salvar na TopAppBar (sempre
    // visível) e barra inferior fixa — com 8 exercícios nada some da tela.
    // Exercícios com key clientId: mover nunca reusa a composição de outro.
    LazyColumn(
        modifier = modifier.testTag("editorList"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (errorMessage != null) {
            item(key = "error") {
                ErrorCard(
                    message = errorMessage,
                    recoveryLabel = stringResource(R.string.editor_retry),
                    onRecovery = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive }
                )
            }
        }
        item(key = "code") {
            OutlinedTextField(
                value = formCode,
                onValueChange = onCodeChanged,
                label = { Text(text = stringResource(R.string.editor_code)) },
                isError = fieldErrors.containsKey(EditorField.WORKOUT_CODE),
                supportingText = {
                    fieldErrors[EditorField.WORKOUT_CODE]?.let { Text(text = it) }
                },
                enabled = !saving,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        }
        item(key = "name") {
            OutlinedTextField(
                value = formName,
                onValueChange = onWorkoutNameChanged,
                label = { Text(text = stringResource(R.string.editor_workout_name)) },
                isError = fieldErrors.containsKey(EditorField.WORKOUT_NAME),
                supportingText = {
                    fieldErrors[EditorField.WORKOUT_NAME]?.let { Text(text = it) }
                },
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        itemsIndexed(
            items = exercises,
            key = { _, exercise -> exercise.clientId }
        ) { index, exercise ->
            val isExpanded = expandedId == exercise.clientId
            val expandCd = if (isExpanded) {
                stringResource(R.string.editor_collapse_cd, exercise.nameText)
            } else {
                stringResource(R.string.editor_expand_cd, exercise.nameText)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("exercise-${exercise.clientId}")
            ) {
                ExerciseSummaryRow(
                    name = exercise.nameText,
                    summary = exerciseSummary(exercise),
                    tone = when (exercise.type) {
                        ExerciseType.SERIES -> TypeBadgeTone.Series
                        ExerciseType.TIMED -> TypeBadgeTone.Time
                        ExerciseType.DISTANCE_TIME -> TypeBadgeTone.DistanceTime
                    },
                    expanded = isExpanded,
                    onExpand = {
                        expandedId = if (expandedId == exercise.clientId) null else exercise.clientId
                    },
                    onMoveUp = { onMoveExercise(exercise.clientId, MoveDirection.UP) },
                    onMoveDown = { onMoveExercise(exercise.clientId, MoveDirection.DOWN) },
                    canMoveUp = enabledMove(index > 0, saving),
                    canMoveDown = enabledMove(index < exercises.lastIndex, saving),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = expandCd }
                )
                if (isExpanded) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        ExerciseEditor(
                            form = exercise,
                            onFieldChanged = onExerciseChanged,
                            fieldErrors = fieldErrors,
                            enabled = !saving,
                            onMoveUp = { onMoveExercise(exercise.clientId, MoveDirection.UP) },
                            onMoveDown = { onMoveExercise(exercise.clientId, MoveDirection.DOWN) },
                            canMoveUp = index > 0,
                            canMoveDown = index < exercises.lastIndex,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
        if (saving) {
            item(key = "saving") {
                Text(
                    text = stringResource(R.string.editor_saving),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
    }
    if (showDiscardConfirmation) {
        DiscardDialog(
            onConfirm = onConfirmDiscard,
            onDismiss = onDismissDiscard
        )
    }
    // N-1: o seletor era um item no FIM da LazyColumn — invisível com 8+
    // exercícios ("Adicionar" sem retorno). Agora é overlay (AlertDialog, não
    // sheet: o Robolectric não entrega toque à janela do sheet, e o diálogo
    // tem cliques comprovados). Aparece onde o dedo está, com scrim de foco.
    if (showPicker) {
        TypePickerDialog(
            onPick = {
                onPickerVisibilityChange(false)
                onAddExercise(it)
            },
            onDismiss = { onPickerVisibilityChange(false) }
        )
    }
}

private fun enabledMove(canMove: Boolean, saving: Boolean): Boolean = canMove && !saving

@Composable
private fun exerciseSummary(exercise: ExerciseFormState): String {
    val rest = exercise.restSecondsText.takeIf { it.isNotBlank() }?.let { "$it s" }
    val parts = when (exercise.type) {
        ExerciseType.SERIES -> listOfNotNull(
            exercise.targetSetsText.takeIf { it.isNotBlank() }?.let { sets ->
                val n = sets.toIntOrNull() ?: 2
                "$sets ${pluralStringResource(R.plurals.editor_series, n)}"
            },
            exercise.targetRepsText.takeIf { it.isNotBlank() }?.let { "$it reps" },
            exercise.targetWeightKgText.takeIf { it.isNotBlank() }?.let { "$it kg" },
            rest
        )
        ExerciseType.TIMED -> listOfNotNull(
            exercise.targetDurationSecondsText.takeIf { it.isNotBlank() }?.let { "$it s" },
            rest
        )
        ExerciseType.DISTANCE_TIME -> listOfNotNull(
            exercise.targetDistanceMetersText.takeIf { it.isNotBlank() }?.let { "$it m" },
            exercise.targetDurationSecondsText.takeIf { it.isNotBlank() }?.let { "$it s" },
            rest
        )
    }
    return parts.ifEmpty { listOf(stringResource(R.string.numeric_empty)) }
        .joinToString(" · ")
}

@Composable
private fun TypePickerDialog(onPick: (ExerciseType) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.editor_add)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ExerciseTypePicker(onPick = onPick)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text(text = stringResource(R.string.editor_close))
            }
        }
    )
}

@Composable
private fun DiscardDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.editor_discard_title)) },
        text = { Text(text = stringResource(R.string.editor_discard_text)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text(text = stringResource(R.string.editor_discard_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text(text = stringResource(R.string.editor_discard_dismiss))
            }
        }
    )
}
