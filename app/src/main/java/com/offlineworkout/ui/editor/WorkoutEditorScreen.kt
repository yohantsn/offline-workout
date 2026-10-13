package com.offlineworkout.ui.editor

import com.offlineworkout.ui.MissingCause
import com.offlineworkout.ui.MissingDestinationScreen
import com.offlineworkout.ui.MissingRecord
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.viewmodel.EditorField
import com.offlineworkout.viewmodel.ExerciseFormState
import com.offlineworkout.viewmodel.MoveDirection
import com.offlineworkout.viewmodel.WorkoutEditorMode
import com.offlineworkout.viewmodel.WorkoutEditorUiState

// T4.1: stateless editor screen. Receives state + callbacks; no persistence.
// Units in labels (s, m, kg, reps); 48dp targets; live regions for
// saving/error/notfound/discard. No remove control (blocked in T4.1).
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
    Scaffold(modifier = modifier) { padding ->
        when (state) {
            WorkoutEditorUiState.Loading -> LoadingBlock(
                text = "Carregando treino…",
                description = "Carregando treino",
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
                title = state.form.mode.title(),
                formCode = state.form.codeText,
                formName = state.form.nameText,
                exercises = state.form.exercises,
                fieldErrors = state.form.fieldErrors,
                saving = false,
                errorMessage = null,
                showDiscardConfirmation = state.form.showDiscardConfirmation,
                onCodeChanged = onCodeChanged,
                onWorkoutNameChanged = onWorkoutNameChanged,
                onAddExercise = onAddExercise,
                onExerciseChanged = onExerciseChanged,
                onMoveExercise = onMoveExercise,
                onSave = onSave,
                onCancel = onCancel,
                onConfirmDiscard = onConfirmDiscard,
                onDismissDiscard = onDismissDiscard,
                onRetry = onRetry,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            is WorkoutEditorUiState.Saving -> EditorForm(
                title = state.form.mode.title(),
                formCode = state.form.codeText,
                formName = state.form.nameText,
                exercises = state.form.exercises,
                fieldErrors = state.form.fieldErrors,
                saving = true,
                errorMessage = null,
                showDiscardConfirmation = false,
                onCodeChanged = onCodeChanged,
                onWorkoutNameChanged = onWorkoutNameChanged,
                onAddExercise = onAddExercise,
                onExerciseChanged = onExerciseChanged,
                onMoveExercise = onMoveExercise,
                onSave = onSave,
                onCancel = onCancel,
                onConfirmDiscard = onConfirmDiscard,
                onDismissDiscard = onDismissDiscard,
                onRetry = onRetry,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            is WorkoutEditorUiState.Error -> EditorForm(
                title = errorTitle(state),
                formCode = state.form.codeText,
                formName = state.form.nameText,
                exercises = state.form.exercises,
                fieldErrors = state.form.fieldErrors,
                saving = false,
                errorMessage = "Não foi possível salvar. Verifique os campos.",
                showDiscardConfirmation = state.form.showDiscardConfirmation,
                onCodeChanged = onCodeChanged,
                onWorkoutNameChanged = onWorkoutNameChanged,
                onAddExercise = onAddExercise,
                onExerciseChanged = onExerciseChanged,
                onMoveExercise = onMoveExercise,
                onSave = onSave,
                onCancel = onCancel,
                onConfirmDiscard = onConfirmDiscard,
                onDismissDiscard = onDismissDiscard,
                onRetry = onRetry,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

private fun WorkoutEditorMode.title(): String = when (this) {
    is WorkoutEditorMode.Create -> "Novo treino"
    is WorkoutEditorMode.Edit -> "Editar treino"
}

private fun errorTitle(state: WorkoutEditorUiState.Error): String =
    state.form.mode.title()

@Composable
private fun LoadingBlock(text: String, description: String, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.semantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = text,
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
    title: String,
    formCode: String,
    formName: String,
    exercises: List<ExerciseFormState>,
    fieldErrors: Map<EditorField, String>,
    saving: Boolean,
    errorMessage: String?,
    showDiscardConfirmation: Boolean,
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
    modifier: Modifier = Modifier
) {
    var showAddMenu by remember { mutableStateOf(false) }
    // T9.3 (bloqueio 1): the form is a LazyColumn, like every other list in the
    // app. With a few exercises the content is taller than the viewport and
    // "Salvar" was unreachable: no gesture, no anchoring, no focus could scroll
    // a plain Column. Exercises are keyed by clientId so moving one never reuses
    // the state of another.
    LazyColumn(
        modifier = modifier.testTag("editorList"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "title") {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .padding(16.dp)
                    .semantics { heading() }
            )
        }
        if (errorMessage != null) {
            item(key = "error") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive }
                ) {
                    Text(text = errorMessage, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(text = "Tentar novamente")
                    }
                }
            }
        }
        item(key = "code") {
            OutlinedTextField(
                value = formCode,
                onValueChange = onCodeChanged,
                label = { Text(text = "Código") },
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
                label = { Text(text = "Nome do treino") },
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
        // T9.3 (bloqueio 1): keyed by clientId, so reordering never reuses the
        // composition of another exercise.
        itemsIndexed(
            items = exercises,
            key = { _, exercise -> exercise.clientId }
        ) { index, exercise ->
            ExerciseCard(
                exercise = exercise,
                isFirst = index == 0,
                isLast = index == exercises.lastIndex,
                typeError = fieldErrors[EditorField.TYPE_FIELDS],
                enabled = !saving,
                onChanged = onExerciseChanged,
                onMove = onMoveExercise
            )
        }
        item(key = "add") {
            Button(
                onClick = { showAddMenu = true },
                enabled = !saving,
                modifier = Modifier
                    .padding(16.dp)
                    .heightIn(min = 48.dp)
            ) {
                Text(text = "Adicionar exercício")
            }
        }
        if (saving) {
            item(key = "saving") {
                Text(
                    text = "Salvando…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
        item(key = "actions") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Button(
                    onClick = onSave,
                    enabled = !saving,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = "Salvar")
                }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !saving,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = "Cancelar")
                }
            }
        }
    }
    if (showAddMenu) {
        AddExerciseDialog(
            onPick = {
                showAddMenu = false
                onAddExercise(it)
            },
            onDismiss = { showAddMenu = false }
        )
    }
    if (showDiscardConfirmation) {
        DiscardDialog(
            onConfirm = onConfirmDiscard,
            onDismiss = onDismissDiscard
        )
    }
}

@Composable
private fun AddExerciseDialog(
    onPick: (ExerciseType) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Adicionar exercício") },
        text = {
            Column {
                Button(
                    onClick = { onPick(ExerciseType.SERIES) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = "Séries")
                }
                Button(
                    onClick = { onPick(ExerciseType.TIMED) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = "Tempo")
                }
                Button(
                    onClick = { onPick(ExerciseType.DISTANCE_TIME) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = "Distância e tempo")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text = "Fechar")
            }
        }
    )
}

@Composable
private fun DiscardDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Descartar alterações?") },
        text = { Text(text = "As alterações deste treino serão perdidas.") },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text = "Descartar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text = "Continuar editando")
            }
        }
    )
}

@Composable
private fun ExerciseCard(
    exercise: ExerciseFormState,
    isFirst: Boolean,
    isLast: Boolean,
    typeError: String?,
    enabled: Boolean,
    onChanged: (ExerciseFormState) -> Unit,
    onMove: (String, MoveDirection) -> Unit
) {
    // T7.1/T9.3: the list is keyed by clientId, and the card remembers its own
    // key so a move never reuses another row's state.
    val clientKey = remember(exercise.clientId) { exercise.clientId }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("exercise-$clientKey")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = exercise.nameText,
                onValueChange = { onChanged(exercise.copy(nameText = it)) },
                label = { Text(text = "Nome do exercício") },
                enabled = enabled,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = exercise.restSecondsText,
                onValueChange = { onChanged(exercise.copy(restSecondsText = it)) },
                label = { Text(text = "Descanso (s)") },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
            when (exercise.type) {
                ExerciseType.SERIES -> {
                    OutlinedTextField(
                        value = exercise.targetSetsText,
                        onValueChange = { onChanged(exercise.copy(targetSetsText = it)) },
                        label = { Text(text = "Séries alvo") },
                        enabled = enabled,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = exercise.targetRepsText,
                        onValueChange = { onChanged(exercise.copy(targetRepsText = it)) },
                        label = { Text(text = "Repetições alvo (reps)") },
                        enabled = enabled,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = exercise.targetWeightKgText,
                        onValueChange = { onChanged(exercise.copy(targetWeightKgText = it)) },
                        label = { Text(text = "Peso alvo (kg, opcional)") },
                        enabled = enabled,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
                ExerciseType.TIMED -> OutlinedTextField(
                    value = exercise.targetDurationSecondsText,
                    onValueChange = { onChanged(exercise.copy(targetDurationSecondsText = it)) },
                    label = { Text(text = "Duração alvo (s)") },
                    enabled = enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                ExerciseType.DISTANCE_TIME -> {
                    OutlinedTextField(
                        value = exercise.targetDistanceMetersText,
                        onValueChange = { onChanged(exercise.copy(targetDistanceMetersText = it)) },
                        label = { Text(text = "Distância alvo (m)") },
                        enabled = enabled,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = exercise.targetDurationSecondsText,
                        onValueChange = { onChanged(exercise.copy(targetDurationSecondsText = it)) },
                        label = { Text(text = "Duração alvo (s)") },
                        enabled = enabled,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }
            typeError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive }
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                OutlinedButton(
                    onClick = { onMove(exercise.clientId, MoveDirection.UP) },
                    enabled = enabled && !isFirst,
                    modifier = Modifier
                        .semantics { contentDescription = "Mover ${exercise.nameText} para cima" }
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = "Subir")
                }
                OutlinedButton(
                    onClick = { onMove(exercise.clientId, MoveDirection.DOWN) },
                    enabled = enabled && !isLast,
                    modifier = Modifier
                        .semantics { contentDescription = "Mover ${exercise.nameText} para baixo" }
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = "Descer")
                }
            }
        }
    }
}
