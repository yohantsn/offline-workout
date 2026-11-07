package com.offlineworkout.ui.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offlineworkout.R
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.ui.components.AppTopBar
import com.offlineworkout.ui.components.ErrorCard
import com.offlineworkout.ui.components.PrimaryButton
import com.offlineworkout.ui.draft.EditorField
import com.offlineworkout.ui.draft.ExerciseFormState
import com.offlineworkout.ui.draft.WorkoutFormState
import com.offlineworkout.viewmodel.WorkoutImportUiState

const val IMPORT_REVIEW_LIST_TAG = "import-review-list"

// Review screen for the CSV import. The draft comes from the parser; only the
// workout code and name are editable here, which is enough to fix the two
// values that can collide (code) or be empty (name). Exercises stay read-only
// in this slice and are written exactly as parsed.

@Composable
fun ImportScreen(
    state: WorkoutImportUiState,
    onPickFile: () -> Unit,
    onCodeChanged: (Int, String) -> Unit,
    onNameChanged: (Int, String) -> Unit,
    onConfirm: () -> Unit,
    onChooseAnotherFile: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.import_title),
                onBack = onClose
            )
        }
    ) { padding ->
        when (state) {
            WorkoutImportUiState.Idle -> IdleContent(
                onPickFile = onPickFile,
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
            )

            WorkoutImportUiState.Reading -> CenteredText(
                text = stringResource(R.string.import_reading),
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
            )

            is WorkoutImportUiState.Review -> ReviewContent(
                state = state,
                onCodeChanged = onCodeChanged,
                onNameChanged = onNameChanged,
                onConfirm = onConfirm,
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            is WorkoutImportUiState.Saving -> CenteredText(
                text = stringResource(R.string.import_saving),
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
            )

            is WorkoutImportUiState.Done -> DoneContent(
                count = state.count,
                onClose = onClose,
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
            )

            is WorkoutImportUiState.Failed -> FailedContent(
                state = state,
                onChooseAnotherFile = onChooseAnotherFile,
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
            )
        }
    }
}

@Composable
private fun IdleContent(onPickFile: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.import_idle_title),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = stringResource(R.string.import_idle_text),
            style = MaterialTheme.typography.bodyLarge
        )
        PrimaryButton(
            text = stringResource(R.string.import_pick_file),
            onClick = onPickFile,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ReviewContent(
    state: WorkoutImportUiState.Review,
    onCodeChanged: (Int, String) -> Unit,
    onNameChanged: (Int, String) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.testTag(IMPORT_REVIEW_LIST_TAG),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.import_review_title),
                style = MaterialTheme.typography.titleLarge
            )
        }
        if (state.warnings.isNotEmpty()) {
            item { WarningsCard(state.warnings) }
        }
        state.generalError?.let { message ->
            item {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                )
            }
        }
        itemsIndexed(state.workouts) { index, workout ->
            WorkoutCard(
                workout = workout,
                fieldErrors = state.fieldErrors[index].orEmpty(),
                onCodeChanged = { onCodeChanged(index, it) },
                onNameChanged = { onNameChanged(index, it) }
            )
        }
        item {
            PrimaryButton(
                text = pluralStringResource(
                    R.plurals.import_confirm_count,
                    state.workouts.size,
                    state.workouts.size
                ),
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WarningsCard(warnings: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.import_warnings_title),
                style = MaterialTheme.typography.titleMedium
            )
            warnings.forEach { warning ->
                Text(text = "• $warning", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun WorkoutCard(
    workout: WorkoutFormState,
    fieldErrors: Map<EditorField, String>,
    onCodeChanged: (String) -> Unit,
    onNameChanged: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        val codeError = fieldErrors[EditorField.WORKOUT_CODE]
        val nameError = fieldErrors[EditorField.WORKOUT_NAME]
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = workout.codeText,
                onValueChange = onCodeChanged,
                label = { Text(stringResource(R.string.import_code_label)) },
                isError = codeError != null,
                supportingText = if (codeError != null) { { Text(codeError) } } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = workout.nameText,
                onValueChange = onNameChanged,
                label = { Text(stringResource(R.string.import_name_label)) },
                isError = nameError != null,
                supportingText = if (nameError != null) { { Text(nameError) } } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = pluralStringResource(
                    R.plurals.list_exercise_count,
                    workout.exercises.size,
                    workout.exercises.size
                ),
                style = MaterialTheme.typography.labelLarge
            )
            workout.exercises.forEachIndexed { index, exercise ->
                if (index > 0) HorizontalDivider()
                ExerciseRow(exercise)
            }
        }
    }
}

@Composable
private fun ExerciseRow(exercise: ExerciseFormState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = exercise.nameText, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = exerciseMeta(exercise),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun exerciseMeta(exercise: ExerciseFormState): String {
    val rest = stringResource(
        R.string.import_rest_seconds,
        exercise.restSecondsText.toIntOrNull() ?: 0
    )
    val targets = when (exercise.type) {
        ExerciseType.SERIES -> stringResource(
            R.string.import_targets_series,
            exercise.targetSetsText.toIntOrNull() ?: 0,
            exercise.targetRepsText.toIntOrNull() ?: 0
        )

        ExerciseType.TIMED -> {
            val seconds = exercise.targetDurationSecondsText.toLongOrNull() ?: 0L
            if (seconds > 0 && seconds % 60 == 0L) {
                stringResource(R.string.import_targets_minutes, seconds / 60)
            } else {
                stringResource(R.string.import_targets_seconds, seconds)
            }
        }

        ExerciseType.DISTANCE_TIME -> stringResource(
            R.string.import_targets_distance_time,
            exercise.targetDurationSecondsText,
            exercise.targetDistanceMetersText
        )
    }
    return stringResource(R.string.import_exercise_meta, targets, rest)
}

@Composable
private fun DoneContent(count: Int, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val description = pluralStringResource(R.plurals.import_done_count, count, count)
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.import_done_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        PrimaryButton(
            text = stringResource(R.string.import_close),
            onClick = onClose,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun FailedContent(
    state: WorkoutImportUiState.Failed,
    onChooseAnotherFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ErrorCard(
            message = state.message,
            recoveryLabel = stringResource(R.string.import_choose_another),
            onRecovery = onChooseAnotherFile,
            modifier = Modifier.fillMaxWidth()
        )
        if (state.warnings.isNotEmpty()) {
            WarningsCard(state.warnings)
        }
    }
}

@Composable
private fun CenteredText(text: String, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.semantics {
            contentDescription = text
            liveRegion = LiveRegionMode.Polite
        }
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}
