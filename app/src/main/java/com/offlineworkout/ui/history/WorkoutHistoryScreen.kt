package com.offlineworkout.ui.history

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.offlineworkout.data.model.ExerciseExecutionView
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.HistoryStats
import com.offlineworkout.viewmodel.ExerciseHistoryUiState
import com.offlineworkout.viewmodel.HistoryError
import java.text.DateFormat
import java.util.Date

// T7.1: stateless history screen. Summary + Canvas chart + textual table are
// always composed together so TalkBack and large text never depend on drawing
// alone. No writes, no Room, no time counting.
@Composable
@Suppress("LongParameterList")
fun WorkoutHistoryScreen(
    state: ExerciseHistoryUiState,
    onSelectExercise: (Long) -> Unit,
    onClearExercise: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(modifier = modifier) { padding ->
        when (state) {
            ExerciseHistoryUiState.Loading -> LoadingBlock(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            is ExerciseHistoryUiState.NotFound -> NotFoundBlock(
                record = state.record,
                onBack = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            is ExerciseHistoryUiState.Error -> LoadErrorBlock(
                message = "Não foi possível carregar o histórico.",
                onRetry = onRetry,
                onBack = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            is ExerciseHistoryUiState.Ready -> ReadyContent(
                state = state,
                onSelectExercise = onSelectExercise,
                onClearExercise = onClearExercise,
                onLoadMore = onLoadMore,
                onRetry = onRetry,
                onBack = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

// System-legible date/time; tests assert structure, not exact locale text.
fun formatHistoryTimestamp(wallClockMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(wallClockMs))

@Composable
private fun LoadingBlock(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.semantics {
            contentDescription = "Carregando histórico"
            liveRegion = LiveRegionMode.Polite
        }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = "Carregando histórico…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun NotFoundBlock(record: MissingRecord, onBack: () -> Unit, modifier: Modifier = Modifier) {
    // T9.2 (R-20) unified the block; T9.3 (bloqueio 7) passes the subject the
    // ViewModel reported, so a deleted workout does not read as a missing
    // exercise.
    MissingDestinationScreen(
        record = record,
        cause = MissingCause.NOT_FOUND,
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
private fun LoadErrorBlock(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Assertive }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message, style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .heightIn(min = 48.dp)
            ) {
                Text(text = "Tentar novamente")
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(min = 48.dp)
            ) {
                Text(text = "Voltar para treinos")
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: ExerciseHistoryUiState.Ready,
    onSelectExercise: (Long) -> Unit,
    onClearExercise: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = state.rows
    val stats = state.stats
    val index = state.exercises.indexOfFirst { it.id == state.selectedExerciseId }
    val selected = if (index >= 0) index else 0
    LazyColumn(
        modifier = modifier.testTag("historyList"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "title") {
            Text(
                text = state.workoutName ?: "Histórico",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .padding(16.dp)
                    .semantics { heading() }
            )
        }
        item(key = "selector") {
            if (state.exercises.isNotEmpty()) {
                ExerciseSelector(
                    exercises = state.exercises,
                    selectedId = state.exercises[selected].id,
                    onSelect = onSelectExercise,
                    onClear = onClearExercise,
                    onPrevious = { onSelectExercise(state.exercises[(selected - 1).coerceAtLeast(0)].id) },
                    onNext = {
                        onSelectExercise(
                            state.exercises[(selected + 1).coerceAtMost(state.exercises.lastIndex)].id
                        )
                    }
                )
            }
        }
        item(key = "summary") {
            // T9.2 (B-3 residual): with no stats the summary is still derived
            // from the rows, so the first date and the last metric never
            // disappear just because the stats query failed.
            val text = if (stats != null) {
                summaryText(stats, rows, state.chart?.type)
            } else {
                derivedSummary(rows, state.chart?.type)
            }
            if (text != null) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
        if (state.error is HistoryError.Stats) {
            item(key = "stats-warning") {
                Text(
                    text = "Não foi possível carregar o resumo",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
        item(key = "chart") {
            if (state.chart != null) {
                HistoryChart(data = state.chart)
            }
        }
        if (rows.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "Sem execuções para este exercício",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            items(rows, key = { it.id }) { row ->
                HistoryRow(row = row)
            }
        }
        if (state.error is HistoryError.Load) {
            item(key = "page-error") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(16.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive }
                ) {
                    Text(
                        text = "Não foi possível carregar mais itens.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(text = "Tentar novamente")
                    }
                }
            }
        }
        // T7.3 (A-5) restored: a failed page is retried through the banner, so
        // the pager is hidden while that error is on screen.
        if (state.hasMore && state.error !is HistoryError.Load) {
            item(key = "load-more") {
                Button(
                    onClick = onLoadMore,
                    enabled = !state.isLoadingMore,
                    modifier = Modifier
                        .padding(16.dp)
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = if (state.isLoadingMore) "Carregando…" else "Carregar mais")
                }
            }
        }
        item(key = "back") {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(16.dp)
                    .heightIn(min = 48.dp)
            ) {
                Text(text = "Voltar para treinos")
            }
        }
    }
}

/**
 * T7.3: the summary derives first/last and the last metric from the rows when
 * stats are unavailable, so the numbers never come from a count the screen
 * cannot show.
 */
/** Summary built only from the rows, for when the stats query is unavailable. */
private fun derivedSummary(
    rows: List<ExerciseExecutionView>,
    type: ExerciseType?
): String? {
    if (rows.isEmpty()) return null
    val oldest = rows.minOf { it.performedAt }
    val metric = when (type) {
        ExerciseType.SERIES -> rows.first().sets.lastOrNull()?.weightKg?.let { "último peso: $it kg" }
        ExerciseType.TIMED -> rows.first().durationSeconds?.let { "última duração: $it s" }
        ExerciseType.DISTANCE_TIME -> rows.first().distanceMeters?.let { "última distância: $it m" }
        null -> null
    }
    val head = "${rows.size} execuções • primeira ${formatHistoryTimestamp(oldest)}"
    return if (metric == null) head else "$head • $metric"
}

private fun summaryText(
    stats: HistoryStats,
    rows: List<ExerciseExecutionView>,
    type: ExerciseType?
): String {
    val first = stats.firstPerformedAtWallClockMs?.let { formatHistoryTimestamp(it) } ?: "—"
    val head = "${stats.count} execuções • primeira $first"
    val newest = rows.firstOrNull()
    val metric = when (type) {
        ExerciseType.SERIES -> newest?.sets?.lastOrNull()?.weightKg?.let { "último peso: $it kg" }
        ExerciseType.TIMED -> newest?.durationSeconds?.let { "última duração: $it s" }
        ExerciseType.DISTANCE_TIME -> newest?.distanceMeters?.let { "última distância: $it m" }
        null -> null
    }
    return if (metric == null) head else "$head • $metric"
}

@Composable
private fun ExerciseSelector(
    exercises: List<ExerciseSpec>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onClear: () -> Unit,
    onPrevious: (Long) -> Unit = {},
    onNext: (Long) -> Unit = {}
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        exercises.forEach { spec ->
            val isSelected = spec.id == selectedId
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                // Always enabled: the ViewModel owns the selection and ignores
                // a re-selection, so disabling here would swallow a tap.
                OutlinedButton(
                    onClick = { onSelect(spec.id) },
                    modifier = Modifier
                        .semantics { contentDescription = "Ver histórico de ${spec.name}" }
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = spec.name)
                }
                if (isSelected) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(text = "Ver todos")
                    }
                }
            }
        }
        if (exercises.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { onPrevious(0L) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = "Anterior")
                }
                TextButton(
                    onClick = { onNext(0L) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = "Próximo")
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(row: ExerciseExecutionView) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = formatHistoryTimestamp(row.performedAt),
                style = MaterialTheme.typography.titleMedium
            )
            if (row.sets.isNotEmpty()) {
                row.sets.forEach { set ->
                    Text(
                        text = "Série ${set.setNumber}: ${set.reps} reps • ${set.weightKg} kg",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else if (row.durationSeconds != null || row.distanceMeters != null) {
                Text(
                    text = listOfNotNull(
                        row.durationSeconds?.let { "Duração: $it s" },
                        row.distanceMeters?.let { "Distância: $it m" }
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
