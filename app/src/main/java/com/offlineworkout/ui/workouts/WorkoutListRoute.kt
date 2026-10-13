package com.offlineworkout.ui.workouts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlineworkout.viewmodel.WorkoutListViewModel

// T3.1: rota da lista. Obtém estado do ViewModel com lifecycle e desenha a
// tela stateless. Efeitos de navegação são coletados pelo AppNavHost.
@Composable
fun WorkoutListRoute(
    viewModel: WorkoutListViewModel,
    modifier: Modifier = Modifier
) {
    WorkoutListScreen(
        state = viewModel.state.collectAsStateWithLifecycle().value,
        onRetry = viewModel::onRetry,
        onCreateSelected = viewModel::onCreateSelected,
        onBackupSelected = viewModel::onBackupSelected,
        onExecutionSelected = viewModel::onExecutionSelected,
        onEditSelected = viewModel::onEditSelected,
        onHistorySelected = viewModel::onHistorySelected,
        modifier = modifier
    )
}
