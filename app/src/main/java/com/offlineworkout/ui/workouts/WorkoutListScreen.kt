package com.offlineworkout.ui.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.viewmodel.WorkoutListUiState

// T3.1: tela stateless da lista de treinos (Material 3). Textos de estado curtos,
// sem contadores, histórico ou RPE. Botões textuais separados com alvos de 48dp
// e descrições contextuais. T4.1: ação textual Criar treino abre o editor.
// T7.1: ação textual Histórico abre o histórico do treino.
// T8.1: ação textual Backup abre a ferramenta local de backup.
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
    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = "Treinos",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = "Escolha um treino para executar ou editar.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Button(
                onClick = onCreateSelected,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .heightIn(min = 48.dp)
            ) {
                Text(text = "Criar treino")
            }
            OutlinedButton(
                onClick = onBackupSelected,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .heightIn(min = 48.dp)
            ) {
                Text(text = "Backup")
            }
            when (state) {
                WorkoutListUiState.Loading -> LoadingState(Modifier.fillMaxSize())
                WorkoutListUiState.Empty -> EmptyState(Modifier.fillMaxSize())
                is WorkoutListUiState.Error -> ErrorState(onRetry, Modifier.fillMaxSize())
                is WorkoutListUiState.Success -> WorkoutCards(
                    workouts = state.workouts,
                    onExecutionSelected = onExecutionSelected,
                    onEditSelected = onEditSelected,
                    onHistorySelected = onHistorySelected,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.semantics {
            contentDescription = "Carregando treinos"
            liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
        }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = "Carregando treinos…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Text(
            text = "Nenhum treino disponível.",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Assertive }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Não foi possível carregar os treinos.",
                style = MaterialTheme.typography.bodyLarge
            )
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .heightIn(min = 48.dp)
            ) {
                Text(text = "Tentar novamente")
            }
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
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.semantics { contentDescription = "Lista de treinos" }
    ) {
        items(workouts, key = { it.id }) { workout ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = workout.name,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        text = "Treino ${workout.code}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        TextButton(
                            onClick = { onExecutionSelected(workout.id) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(
                                text = "Executar",
                                modifier = Modifier.semantics {
                                    contentDescription = "Executar ${workout.name}"
                                }
                            )
                        }
                        TextButton(
                            onClick = { onEditSelected(workout.id) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(
                                text = "Editar",
                                modifier = Modifier.semantics {
                                    contentDescription = "Editar ${workout.name}"
                                }
                            )
                        }
                        TextButton(
                            onClick = { onHistorySelected(workout.id) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(
                                text = "Histórico",
                                modifier = Modifier.semantics {
                                    contentDescription = "Ver histórico de ${workout.name}"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
