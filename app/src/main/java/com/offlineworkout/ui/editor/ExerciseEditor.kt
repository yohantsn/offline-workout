package com.offlineworkout.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offlineworkout.R
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.ui.draft.EditorField
import com.offlineworkout.ui.draft.ExerciseFormState

// Formulário do exercício expandido. Erro por campo (borda + mensagem
// abaixo); erros de escopo de exercício (EXERCISE_NAME/REST_SECONDS/
// TYPE_FIELDS) vêm num mapa global do formulário, então todo exercício
// expandido os exibe — precedência do comportamento anterior, sem o qual a
// informação se perderia sem mudança de contrato no ViewModel.
@Composable
fun ExerciseEditor(
    form: ExerciseFormState,
    onFieldChanged: (ExerciseFormState) -> Unit,
    fieldErrors: Map<EditorField, String>,
    enabled: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp)
    ) {
        OutlinedTextField(
            value = form.nameText,
            onValueChange = { onFieldChanged(form.copy(nameText = it)) },
            label = { Text(text = stringResource(R.string.editor_exercise_name)) },
            isError = fieldErrors.containsKey(EditorField.EXERCISE_NAME),
            supportingText = {
                fieldErrors[EditorField.EXERCISE_NAME]?.let { Text(text = it) }
            },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = form.restSecondsText,
            onValueChange = { onFieldChanged(form.copy(restSecondsText = it)) },
            label = { Text(text = stringResource(R.string.editor_rest)) },
            isError = fieldErrors.containsKey(EditorField.REST_SECONDS),
            supportingText = {
                fieldErrors[EditorField.REST_SECONDS]?.let { Text(text = it) }
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        when (form.type) {
            ExerciseType.SERIES -> {
                NumberField(
                    value = form.targetSetsText,
                    onValueChange = { onFieldChanged(form.copy(targetSetsText = it)) },
                    label = stringResource(R.string.editor_sets),
                    enabled = enabled,
                    decimal = false
                )
                NumberField(
                    value = form.targetRepsText,
                    onValueChange = { onFieldChanged(form.copy(targetRepsText = it)) },
                    label = stringResource(R.string.editor_reps),
                    enabled = enabled,
                    decimal = false
                )
                NumberField(
                    value = form.targetWeightKgText,
                    onValueChange = { onFieldChanged(form.copy(targetWeightKgText = it)) },
                    label = stringResource(R.string.editor_weight),
                    enabled = enabled,
                    decimal = true
                )
            }
            ExerciseType.TIMED -> NumberField(
                value = form.targetDurationSecondsText,
                onValueChange = { onFieldChanged(form.copy(targetDurationSecondsText = it)) },
                label = stringResource(R.string.editor_duration),
                enabled = enabled,
                decimal = false
            )
            ExerciseType.DISTANCE_TIME -> {
                NumberField(
                    value = form.targetDistanceMetersText,
                    onValueChange = { onFieldChanged(form.copy(targetDistanceMetersText = it)) },
                    label = stringResource(R.string.editor_distance),
                    enabled = enabled,
                    decimal = true
                )
                NumberField(
                    value = form.targetDurationSecondsText,
                    onValueChange = { onFieldChanged(form.copy(targetDurationSecondsText = it)) },
                    label = stringResource(R.string.editor_duration),
                    enabled = enabled,
                    decimal = false
                )
            }
        }
        fieldErrors[EditorField.TYPE_FIELDS]?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    decimal: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    )
}
