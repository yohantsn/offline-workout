package com.offlineworkout.ui.history

import com.offlineworkout.ui.MissingCause
import com.offlineworkout.ui.MissingDestinationScreen
import com.offlineworkout.ui.MissingRecord
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlineworkout.R
import com.offlineworkout.data.model.ExerciseExecutionView
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.HistoryChartData
import com.offlineworkout.data.model.HistoryStats
import com.offlineworkout.ui.components.AppTopBar
import com.offlineworkout.ui.components.ErrorCard
import com.offlineworkout.ui.components.SecondaryButton
import com.offlineworkout.ui.components.StatTile
import com.offlineworkout.viewmodel.ExerciseHistoryUiState
import com.offlineworkout.viewmodel.HistoryError
import java.text.DateFormat
import java.util.Date

// T7.1 + redesign lote 5: TopAppBar, chips roláveis com +N, StatTiles
// (Sessões + última métrica), gráfico em card com período no subtítulo,
// tabela agrupada por DayHeader. Resumo + tabela sempre presentes: o gráfico
// nunca é a única forma de ler o dado. Assinatura inalterada (6 params).
@Composable
fun WorkoutHistoryScreen(
    state: ExerciseHistoryUiState,
    onSelectExercise: (Long) -> Unit,
    onClearExercise: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            val title = (state as? ExerciseHistoryUiState.Ready)?.workoutName
            AppTopBar(
                title = title ?: stringResource(R.string.history_title_fallback),
                onBack = onBack
            )
        }
    ) { padding ->
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
            is ExerciseHistoryUiState.Error -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive }
            ) {
                ErrorCard(
                    message = stringResource(R.string.history_error),
                    recoveryLabel = stringResource(R.string.history_retry),
                    onRecovery = onRetry,
                    secondaryLabel = stringResource(R.string.history_back),
                    onSecondary = onBack,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            is ExerciseHistoryUiState.Ready -> ReadyContent(
                state = state,
                onSelectExercise = onSelectExercise,
                onClearExercise = onClearExercise,
                onLoadMore = onLoadMore,
                onRetry = onRetry,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

// System-legible date/time em pt-BR FIXO (H-2): a mesma decisão do backup —
// nunca depende do locale do aparelho para não variar entre testes e uso.
// Tests assert structure, not exact locale text.
fun formatHistoryTimestamp(wallClockMs: Long): String =
    DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.SHORT,
        java.util.Locale.forLanguageTag("pt-BR")
    ).format(Date(wallClockMs))

// Só hora (H-3): nas linhas agrupadas por DayHeader o dia já está no
// cabeçalho — repetir a data em cada card é ruído.
fun formatHistoryTime(wallClockMs: Long): String =
    DateFormat.getTimeInstance(
        DateFormat.SHORT,
        java.util.Locale.forLanguageTag("pt-BR")
    ).format(Date(wallClockMs))

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
private fun ReadyContent(
    state: ExerciseHistoryUiState.Ready,
    onSelectExercise: (Long) -> Unit,
    onClearExercise: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = state.rows
    val stats = state.stats
    LazyColumn(
        modifier = modifier
            .testTag("historyList")
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "selector") {
            ExerciseChips(
                exercises = state.exercises,
                selectedId = state.selectedExerciseId,
                onSelect = onSelectExercise,
                onClear = onClearExercise
            )
        }
        item(key = "tiles") {
            // N-2: sem exercício selecionado não há de quem agregar — mostra
            // convite (wireframe §3.4), nunca tiles zerados ("Sessões 0" para
            // treino com histórico). Totais no nível do treino exigiriam
            // agregação nova no DAO + campo no VM: pendência de decisão.
            if (state.selectedExerciseId == null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.history_pick_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(R.string.history_pick_text),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                SummaryTiles(
                    stats = stats,
                    rows = rows,
                    type = state.chart?.type
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
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
        if (state.error is HistoryError.Stats) {
            item(key = "stats-warning") {
                Text(
                    text = stringResource(R.string.history_stats_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
        if (state.chart != null) {
            item(key = "chart") {
                ChartCard(chart = state.chart)
            }
        }
        if (rows.isEmpty()) {
            // N-2: "para este exercício" só quando HÁ exercício selecionado;
            // sem seleção o convite acima já ocupa o espaço.
            if (state.selectedExerciseId != null) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        } else {
            val groups = rows.groupBy { dayKey(it.performedAt) }
            groups.forEach { (_, dayRows) ->
                item(key = "day-${dayRows.first().id}") {
                    DayHeader(label = dayLabelFor(dayRows.first().performedAt))
                }
                items(dayRows, key = { it.id }) { row ->
                    HistoryRow(row = row)
                }
            }
        }
        if (state.error is HistoryError.Load) {
            item(key = "page-error") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                ) {
                    Text(
                        text = stringResource(R.string.history_page_error),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Text(text = stringResource(R.string.history_retry))
                    }
                }
            }
        }
        // T7.3 (A-5) restored: a failed page is retried through the banner, so
        // the pager is hidden while that error is on screen.
        if (state.hasMore && state.error !is HistoryError.Load) {
            item(key = "load-more") {
                SecondaryButton(
                    text = if (state.isLoadingMore) {
                        stringResource(R.string.history_loading)
                    } else {
                        stringResource(R.string.history_load_more)
                    },
                    onClick = onLoadMore,
                    enabled = !state.isLoadingMore,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// Sessões + última métrica do tipo (escopo do plano §4: sem Máxima/vs30dias,
// que exigiriam agregação nova no DAO).
@Composable
private fun SummaryTiles(
    stats: HistoryStats?,
    rows: List<ExerciseExecutionView>,
    type: ExerciseType?
) {
    val newest = rows.firstOrNull()
    val unknown = stringResource(R.string.numeric_empty)
    val lastValue: String
    val lastUnit: String?
    val lastLabel: String
    when (type) {
        ExerciseType.TIMED -> {
            val seconds = newest?.durationSeconds
            lastValue = seconds?.toString() ?: unknown
            lastUnit = if (seconds != null) "s" else null
            lastLabel = stringResource(R.string.history_last_duration)
        }
        ExerciseType.DISTANCE_TIME -> {
            val meters = newest?.distanceMeters
            lastValue = meters?.toString() ?: unknown
            lastUnit = if (meters != null) "m" else null
            lastLabel = stringResource(R.string.history_last_distance)
        }
        else -> {
            val weight = newest?.sets?.lastOrNull()?.weightKg
            lastValue = weight?.toString() ?: unknown
            lastUnit = if (weight != null) "kg" else null
            lastLabel = stringResource(R.string.history_last_load)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(
            label = stringResource(R.string.history_sessions),
            value = (stats?.count ?: rows.size).toString(),
            modifier = Modifier.weight(1f)
        )
        StatTile(
            label = lastLabel,
            value = lastValue,
            unit = lastUnit,
            modifier = Modifier.weight(1f)
        )
    }
}

// Chips roláveis: 4 primeiros + "+N" quando passa de 4; "+N" expande.
@Composable
private fun ExerciseChips(
    exercises: List<ExerciseSpec>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onClear: () -> Unit
) {
    if (exercises.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val visible = if (expanded || exercises.size <= MAX_COLLAPSED_CHIPS) {
        exercises
    } else {
        exercises.take(MAX_COLLAPSED_CHIPS)
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("exerciseChips")
    ) {
        item(key = "all") {
            val selected = selectedId == null
            ChipButton(
                text = stringResource(R.string.history_all),
                selected = selected,
                onClick = onClear
            )
        }
        items(visible, key = { it.id }) { spec ->
            val cd = stringResource(R.string.history_exercise_cd, spec.name)
            if (spec.id == selectedId) {
                Button(
                    onClick = { onSelect(spec.id) },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(
                        text = spec.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { contentDescription = cd }
                    )
                }
            } else {
                OutlinedButton(
                    onClick = { onSelect(spec.id) },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(
                        text = spec.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { contentDescription = cd }
                    )
                }
            }
        }
        if (!expanded && exercises.size > MAX_COLLAPSED_CHIPS) {
            item(key = "more") {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.history_show_more,
                            exercises.size - MAX_COLLAPSED_CHIPS
                        )
                    )
                }
            }
        }
        if (expanded && exercises.size > MAX_COLLAPSED_CHIPS) {
            item(key = "less") {
                OutlinedButton(
                    onClick = { expanded = false },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(text = stringResource(R.string.history_show_less))
                }
            }
        }
    }
}

private const val MAX_COLLAPSED_CHIPS = 4

@Composable
private fun ChipButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        ) {
            Text(text = text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        ) {
            Text(text = text)
        }
    }
}

@Composable
private fun ChartCard(chart: HistoryChartData) {
    val title = when (chart.type) {
        ExerciseType.SERIES -> stringResource(R.string.history_chart_series)
        ExerciseType.TIMED -> stringResource(R.string.history_chart_timed)
        ExerciseType.DISTANCE_TIME -> stringResource(R.string.history_chart_distance)
    }
    val points = chart.points
    val minTs = points.minOfOrNull { it.timestampWallClockMs }
    val maxTs = points.maxOfOrNull { it.timestampWallClockMs }
    val period = if (minTs == null || maxTs == null || minTs == maxTs) {
        minTs?.let { formatHistoryDate(it) } ?: ""
    } else {
        "${formatHistoryDate(minTs)} – ${formatHistoryDate(maxTs)}"
    }
    val pointsPart = if (chart.wasDownsampled) {
        stringResource(
            R.string.history_chart_points_sub,
            points.size,
            chart.totalPointCount
        )
    } else {
        stringResource(R.string.history_chart_points, points.size)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(
                    R.string.history_chart_subtitle,
                    period,
                    chart.unitPrimary,
                    pointsPart
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            HistoryChart(data = chart)
        }
    }
}

private fun dayKey(epochMs: Long): String {
    val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
    format.timeZone = java.util.TimeZone.getDefault()
    return format.format(java.util.Date(epochMs))
}

@Composable
private fun dayLabelFor(performedAt: Long): String {
    val today = remember { dayKey(System.currentTimeMillis()) }
    return if (dayKey(performedAt) == today) {
        stringResource(R.string.history_today)
    } else {
        formatHistoryDate(performedAt)
    }
}

/**
 * T7.3: the summary derives first/last and the last metric from the rows when
 * stats are unavailable, so the numbers never come from a count the screen
 * cannot show.
 */
/** Summary built only from the rows, for when the stats query is unavailable. */
@Composable
private fun derivedSummary(
    rows: List<ExerciseExecutionView>,
    type: ExerciseType?
): String? {
    if (rows.isEmpty()) return null
    val oldest = rows.minOf { it.performedAt }
    val metric = when (type) {
        ExerciseType.SERIES -> rows.first().sets.lastOrNull()?.weightKg?.let {
            stringResource(R.string.history_metric_weight, it.toString())
        }
        ExerciseType.TIMED -> rows.first().durationSeconds?.let {
            stringResource(R.string.history_metric_duration, it.toString())
        }
        ExerciseType.DISTANCE_TIME -> rows.first().distanceMeters?.let {
            stringResource(R.string.history_metric_distance, it.toString())
        }
        null -> null
    }
    val head = stringResource(
        R.string.history_summary_head,
        pluralStringResource(R.plurals.history_execution_count, rows.size, rows.size),
        formatHistoryTimestamp(oldest)
    )
    return if (metric == null) head else "$head • $metric"
}

@Composable
private fun summaryText(
    stats: HistoryStats,
    rows: List<ExerciseExecutionView>,
    type: ExerciseType?
): String {
    val first = stats.firstPerformedAtWallClockMs?.let { formatHistoryTimestamp(it) } ?: stringResource(R.string.numeric_empty)
    val head = stringResource(
        R.string.history_summary_head,
        pluralStringResource(R.plurals.history_execution_count, stats.count, stats.count),
        first
    )
    val newest = rows.firstOrNull()
    val metric = when (type) {
        ExerciseType.SERIES -> newest?.sets?.lastOrNull()?.weightKg?.let {
            stringResource(R.string.history_metric_weight, it.toString())
        }
        ExerciseType.TIMED -> newest?.durationSeconds?.let {
            stringResource(R.string.history_metric_duration, it.toString())
        }
        ExerciseType.DISTANCE_TIME -> newest?.distanceMeters?.let {
            stringResource(R.string.history_metric_distance, it.toString())
        }
        null -> null
    }
    return if (metric == null) head else "$head • $metric"
}

@Composable
private fun HistoryRow(row: ExerciseExecutionView) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = formatHistoryTime(row.performedAt),
                style = MaterialTheme.typography.titleMedium
            )
            if (row.sets.isNotEmpty()) {
                row.sets.forEach { set ->
                    // H-1: peso é opcional — nunca interpolar o Double? direto.
                    val weight = set.weightKg?.let { "$it kg" }
                        ?: stringResource(R.string.history_no_weight)
                    Text(
                        text = stringResource(
                            R.string.history_set_line,
                            set.setNumber,
                            set.reps,
                            weight
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else if (row.durationSeconds != null || row.distanceMeters != null) {
                Text(
                    text = listOfNotNull(
                        row.durationSeconds?.let {
                            stringResource(R.string.history_duration_line, it.toString())
                        },
                        row.distanceMeters?.let {
                            stringResource(R.string.history_distance_line, it.toString())
                        }
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
