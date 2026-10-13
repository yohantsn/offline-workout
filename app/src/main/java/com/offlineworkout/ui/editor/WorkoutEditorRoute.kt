package com.offlineworkout.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlineworkout.data.config.WorkoutEditorConfig
import com.offlineworkout.data.repository.WorkoutRepository
import com.offlineworkout.ui.execution.InvalidWorkoutDestination
import com.offlineworkout.viewmodel.WorkoutEditorEffect
import com.offlineworkout.viewmodel.WorkoutEditorMode
import com.offlineworkout.viewmodel.WorkoutEditorViewModel

// T4.1: editor route. Factory receives only the interface, mode and pure
// config — never persistence, container or concrete implementations. Effects
// are consumed once by the caller (NavHost pops back to the list).
@Composable
fun WorkoutEditorRoute(
    repository: WorkoutRepository,
    mode: WorkoutEditorMode,
    config: WorkoutEditorConfig,
    onSaved: () -> Unit,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val factory = remember(repository, mode, config) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WorkoutEditorViewModel(repository, mode, config) as T
            }
        }
    }
    val editorViewModel: WorkoutEditorViewModel = viewModel(factory = factory)
    LaunchedEffect(editorViewModel) {
        editorViewModel.effects.collect { effect ->
            when (effect) {
                is WorkoutEditorEffect.Saved -> onSaved()
                is WorkoutEditorEffect.Closed -> onClosed()
            }
        }
    }
    WorkoutEditorScreen(
        state = editorViewModel.state.collectAsStateWithLifecycle().value,
        onCodeChanged = editorViewModel::onCodeChanged,
        onWorkoutNameChanged = editorViewModel::onWorkoutNameChanged,
        onAddExercise = editorViewModel::onAddExercise,
        onExerciseChanged = editorViewModel::onExerciseChanged,
        onMoveExercise = editorViewModel::onMoveExercise,
        onSave = editorViewModel::onSave,
        onCancel = editorViewModel::onCancel,
        onConfirmDiscard = editorViewModel::onConfirmDiscard,
        onDismissDiscard = editorViewModel::onDismissDiscard,
        onRetry = editorViewModel::onRetry,
        onBack = onClosed,
        modifier = modifier
    )
}

// T4.1: edit entry guard. Invalid ids never reach the ViewModel and therefore
// never query the repository; they offer a way back to the list.
@Composable
fun WorkoutEditDestination(
    workoutId: Long,
    repository: WorkoutRepository,
    config: WorkoutEditorConfig,
    onSaved: () -> Unit,
    onClosed: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (workoutId <= 0) {
        InvalidWorkoutDestination(onBack = onBack, modifier = modifier)
    } else {
        WorkoutEditorRoute(
            repository = repository,
            mode = WorkoutEditorMode.Edit(workoutId),
            config = config,
            onSaved = onSaved,
            onClosed = onClosed,
            modifier = modifier
        )
    }
}
