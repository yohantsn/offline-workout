package com.offlineworkout.ui.history

import com.offlineworkout.ui.MissingCause
import com.offlineworkout.ui.MissingDestinationScreen
import com.offlineworkout.ui.MissingRecord
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlineworkout.data.repository.ExerciseHistoryRepository
import com.offlineworkout.data.repository.WorkoutRepository
import com.offlineworkout.viewmodel.ExerciseHistoryEffect
import com.offlineworkout.viewmodel.ExerciseHistoryViewModel

// T7.1: history entry. Invalid ids never create a ViewModel nor query any
// repository. Valid ids wire the single ViewModel (interfaces only).
@Composable
fun WorkoutHistoryDestination(
    workoutId: Long,
    exerciseId: Long?,
    exerciseInvalid: Boolean,
    workoutRepository: WorkoutRepository,
    historyRepository: ExerciseHistoryRepository,
    onBack: () -> Unit,
    onNavigateExercise: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (workoutId <= 0 || exerciseInvalid) {
        Scaffold(modifier = modifier) { padding ->
            if (workoutId <= 0) {
                InvalidHistoryDestination(
                    record = MissingRecord.WORKOUT,
                    onBack = onBack,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            } else {
                InvalidHistoryDestination(
                    record = MissingRecord.EXERCISE,
                    onBack = onBack,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }
        }
        return
    }
    val factory = remember(workoutRepository, historyRepository, workoutId, exerciseId) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ExerciseHistoryViewModel(
                    workoutId,
                    exerciseId,
                    workoutRepository,
                    historyRepository
                ) as T
            }
        }
    }
    val historyViewModel: ExerciseHistoryViewModel = viewModel(factory = factory)
    LaunchedEffect(historyViewModel) {
        historyViewModel.effects.collect { effect ->
            when (effect) {
                is ExerciseHistoryEffect.OpenHistory ->
                    onNavigateExercise(effect.workoutId, effect.exerciseId)
                is ExerciseHistoryEffect.Back -> onBack()
            }
        }
    }
    WorkoutHistoryScreen(
        state = historyViewModel.state.collectAsStateWithLifecycle().value,
        onSelectExercise = historyViewModel::onSelectExercise,
        onClearExercise = historyViewModel::onClearExercise,
        onLoadMore = historyViewModel::onLoadMore,
        onRetry = historyViewModel::onRetry,
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
fun InvalidHistoryDestination(
    record: MissingRecord,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // T9.2 (R-20): shared block, so this one is announced like every other.
    MissingDestinationScreen(
        record = record,
        cause = MissingCause.INVALID,
        onBack = onBack,
        modifier = modifier
    )
}
