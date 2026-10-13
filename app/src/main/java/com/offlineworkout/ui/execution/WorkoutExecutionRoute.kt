package com.offlineworkout.ui.execution

import com.offlineworkout.ui.MissingCause
import com.offlineworkout.ui.MissingDestinationScreen
import com.offlineworkout.ui.MissingRecord
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlineworkout.data.repository.ExerciseHistoryRepository
import com.offlineworkout.data.repository.WorkoutRepository
import com.offlineworkout.data.session.WorkoutSessionController
import com.offlineworkout.data.timer.RestTimerController
import com.offlineworkout.viewmodel.WorkoutExecutionEffect
import com.offlineworkout.viewmodel.WorkoutExecutionViewModel

// T6.1: execution entry. Invalid ids never touch repositories/VM (shared
// guard). Valid ids wire the single ViewModel (repository + history + rest
// timer + session) and the stateless screen. POST_NOTIFICATIONS is requested
// before starting a rest — the timer starts regardless, per the denied
// permission fallback rule. No Room/DAOs/Service here, only interfaces.
@Composable
@Suppress("LongParameterList")
fun WorkoutExecutionRoute(
    workoutId: Long,
    repository: WorkoutRepository,
    history: ExerciseHistoryRepository,
    timer: RestTimerController,
    session: WorkoutSessionController,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    onOpenHistory: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (workoutId <= 0) {
        Scaffold(modifier = modifier) { padding ->
            InvalidWorkoutDestination(
                onBack = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
        return
    }
    val factory = remember(repository, history, timer, session, workoutId) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WorkoutExecutionViewModel(workoutId, repository, history, timer, session) as T
            }
        }
    }
    val executionViewModel: WorkoutExecutionViewModel = viewModel(factory = factory)
    LaunchedEffect(executionViewModel) {
        executionViewModel.effects.collect { effect ->
            when (effect) {
                is WorkoutExecutionEffect.WorkoutFinished -> onFinished()
                is WorkoutExecutionEffect.OpenHistory -> onOpenHistory(effect.workoutId, effect.exerciseId)
            }
        }
    }
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Result only affects future alerts; the timer starts regardless.
    }
    WorkoutExecutionScreen(
        state = executionViewModel.state.collectAsStateWithLifecycle().value,
        onSelectExercise = executionViewModel::onSelectExercise,
        onNextExercise = executionViewModel::onNextExercise,
        onPreviousExercise = executionViewModel::onPreviousExercise,
        onRepsChanged = executionViewModel::onRepsChanged,
        onWeightChanged = executionViewModel::onWeightChanged,
        onDurationChanged = executionViewModel::onDurationChanged,
        onDistanceChanged = executionViewModel::onDistanceChanged,
        onCompleteSeriesSet = executionViewModel::onCompleteSeriesSet,
        onCompleteExercise = executionViewModel::onCompleteExercise,
        onStartRest = { exerciseId ->
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            executionViewModel.onStartRest(exerciseId)
        },
        onCancelRest = executionViewModel::onCancelRest,
        onHistorySelected = executionViewModel::onHistorySelected,
        onPauseSession = executionViewModel::onPauseSession,
        onResumeSession = executionViewModel::onResumeSession,
        onFinishWorkout = executionViewModel::onFinishWorkout,
        onRetry = executionViewModel::onRetry,
        onNoticeShown = executionViewModel::onNoticeShown,
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
fun InvalidWorkoutDestination(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // T9.2 (R-20): shared block, so this one is announced like every other.
    MissingDestinationScreen(
        record = MissingRecord.WORKOUT,
        cause = MissingCause.INVALID,
        onBack = onBack,
        modifier = modifier
    )
}
