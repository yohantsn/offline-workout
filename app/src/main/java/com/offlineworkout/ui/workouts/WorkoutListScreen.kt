package com.offlineworkout.ui.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.offlineworkout.R
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.ui.components.AppTopBar
import com.offlineworkout.ui.components.ErrorCard
import com.offlineworkout.ui.components.LoadingSkeleton
import com.offlineworkout.ui.components.PrimaryButton
import com.offlineworkout.viewmodel.WorkoutListUiState

// Redesign lote 3: TopAppBar com Criar/Backup, card inteiro executa,
// skeleton no loading, ErrorCard no erro, empty state com CTA.
// Assinatura inalterada (8 params do plano §4).
@Composable
fun WorkoutListScreen(
    state: WorkoutListUiState,
    onRetry: () -> Unit,
    onCreateSelected: () -> Unit,
    onBackupSelected: () -> Unit,
    onExecutionSelected: (Long) -> Unit,
    onEditSelected: (Long) -> Unit,
    onHistorySelected: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.list_title),
                actions = {
                    TextButton(
                        onClick = onCreateSelected,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Text(text = stringResource(R.string.list_create_short))
                    }
                    TextButton(
                        onClick = onBackupSelected,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Text(text = stringResource(R.string.list_backup))
                    }
                }
            )
        }
    ) { padding ->
        when (state) {
            WorkoutListUiState.Loading -> LoadingState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            )
            WorkoutListUiState.Empty -> EmptyState(
                onCreateSelected = onCreateSelected,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            )
            is WorkoutListUiState.Error -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive }
            ) {
                ErrorCard(
                    message = stringResource(R.string.list_error),
                    recoveryLabel = stringResource(R.string.list_retry),
                    onRecovery = onRetry,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            is WorkoutListUiState.Success -> WorkoutCards(
                workouts = state.workouts,
                onExecutionSelected = onExecutionSelected,
                onEditSelected = onEditSelected,
                onHistorySelected = onHistorySelected,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    val loadingDescription = stringResource(R.string.list_loading_cd)
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier.semantics {
            contentDescription = loadingDescription
            liveRegion = LiveRegionMode.Polite
        }
    ) {
        LoadingSkeleton(lines = 2, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun EmptyState(onCreateSelected: () -> Unit, modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.list_empty_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.list_empty_text),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp)
            )
            PrimaryButton(
                text = stringResource(R.string.list_create),
                onClick = onCreateSelected,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WorkoutCards(
    workouts: List<WorkoutSummary>,
    onExecutionSelected: (Long) -> Unit,
    onEditSelected: (Long) -> Unit,
    onHistorySelected: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val listDescription = stringResource(R.string.list_label)
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .padding(16.dp)
            .semantics { contentDescription = listDescription }
    ) {
        items(workouts, key = { it.id }) { workout ->
            // exerciseCount/totalRestSeconds ainda não existem no repositório
            // (proibido neste lote): contrato recebido, exibição pendente.
            WorkoutCard(
                name = workout.name,
                exerciseCount = 0,
                totalRestSeconds = 0,
                code = workout.code,
                onCardClick = { onExecutionSelected(workout.id) },
                onEditClick = { onEditSelected(workout.id) },
                onHistoryClick = { onHistorySelected(workout.id) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
