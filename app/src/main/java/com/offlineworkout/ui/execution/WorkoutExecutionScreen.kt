package com.offlineworkout.ui.execution

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlineworkout.R
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.session.WorkoutSessionState
import com.offlineworkout.data.session.WorkoutSessionStatus
import com.offlineworkout.data.session.formatSessionElapsed
import com.offlineworkout.data.timer.RestTimerStatus
import com.offlineworkout.ui.components.AppTopBar
import com.offlineworkout.ui.components.NumericField
import com.offlineworkout.ui.components.PrimaryButton
import com.offlineworkout.ui.components.RestCountdown
import com.offlineworkout.ui.components.SecondaryButton
import com.offlineworkout.ui.components.StepDots
import com.offlineworkout.ui.components.TypeBadge
import com.offlineworkout.ui.components.TypeBadgeTone
import com.offlineworkout.ui.theme.LocalSuccessColors
import com.offlineworkout.viewmodel.ExecutionError
import com.offlineworkout.viewmodel.ExecutionExerciseState
import com.offlineworkout.viewmodel.SeriesSetInputState
import com.offlineworkout.viewmodel.WorkoutExecutionUiState
import kotlinx.coroutines.delay

// T6.1 + redesign lote 7 (modo performance): exercício atual ocupa a tela,
// strip de chips no lugar da lista de cards, StepDots no topo, CTA "Concluir
// série N" dominante, RestCountdown substituindo o CTA durante o descanso,
// total na TopAppBar. Assinatura inalterada (19 params). Stateless: nunca
// conta tempo, nunca escreve histórico. Guard de sessão estrangeira, live
// regions, Latest neutro e NotFound preservados como aprovados.
@Composable
@Suppress("LongParameterList")
fun WorkoutExecutionScreen(
    state: WorkoutExecutionUiState,
    onSelectExercise: (Long) -> Unit,
    onNextExercise: () -> Unit,
    onPreviousExercise: () -> Unit,
    onRepsChanged: (Long, Int, String) -> Unit,
    onWeightChanged: (Long, Int, String) -> Unit,
    onDurationChanged: (Long, String) -> Unit,
    onDistanceChanged: (Long, String) -> Unit,
    onCompleteSeriesSet: (Long, Int) -> Unit,
    onCompleteExercise: (Long) -> Unit,
    onStartRest: (Long) -> Unit,
    onCancelRest: () -> Unit,
    onHistorySelected: () -> Unit,
    onPauseSession: () -> Unit,
    onResumeSession: () -> Unit,
    onFinishWorkout: () -> Unit,
    onRetry: () -> Unit,
    onNoticeShown: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showFinishConfirm by remember { mutableStateOf(false) }
    val ready = state as? WorkoutExecutionUiState.Ready
    val foreignSession = ready?.session?.isForeign(ready.workout.id) == true
    Scaffold(
        modifier = modifier,
        topBar = {
            if (ready != null) {
                val total = formatSessionElapsed(ready.session.elapsedSeconds)
                AppTopBar(
                    title = ready.workout.name,
                    onBack = onBack,
                    actions = {
                        Text(
                            text = if (foreignSession) {
                                stringResource(R.string.execution_total_foreign, total)
                            } else {
                                stringResource(R.string.execution_total, total)
                            },
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFeatureSettings = "tnum"
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        when (ready.session.status) {
                            is WorkoutSessionStatus.Running -> TextButton(
                                onClick = onPauseSession,
                                enabled = !foreignSession,
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Text(text = stringResource(R.string.execution_pause))
                            }
                            is WorkoutSessionStatus.Paused -> TextButton(
                                onClick = onResumeSession,
                                enabled = !foreignSession,
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Text(text = stringResource(R.string.execution_resume))
                            }
                            else -> Unit
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (ready != null) {
                Surface(shadowElevation = 2.dp) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FooterActions(
                            state = ready,
                            foreignSession = foreignSession,
                            onCompleteSeriesSet = onCompleteSeriesSet,
                            onCompleteExercise = onCompleteExercise,
                            onStartRest = onStartRest,
                            onCancelRest = onCancelRest
                        )
                        // T9.3 (bloqueio 6): desabilitado em sessão estrangeira
                        // (nunca some — some esconderia a saída do treino).
                        Button(
                            onClick = { showFinishConfirm = true },
                            enabled = !foreignSession && ready.exercises.isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Text(text = stringResource(R.string.execution_finish))
                        }
                    }
                }
            }
        }
    ) { padding ->
        when (state) {
            WorkoutExecutionUiState.Loading -> LoadingBlock(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            WorkoutExecutionUiState.NotFound -> NotFoundBlock(
                onBack = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            is WorkoutExecutionUiState.Error -> LoadErrorBlock(
                message = state.message,
                canRetry = state.canRetry,
                onRetry = onRetry,
                onBack = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            is WorkoutExecutionUiState.Ready -> ReadyContent(
                state = state,
                foreignSession = foreignSession,
                onSelectExercise = onSelectExercise,
                onNextExercise = onNextExercise,
                onPreviousExercise = onPreviousExercise,
                onRepsChanged = onRepsChanged,
                onWeightChanged = onWeightChanged,
                onDurationChanged = onDurationChanged,
                onDistanceChanged = onDistanceChanged,
                onCompleteExercise = onCompleteExercise,
                onHistorySelected = onHistorySelected,
                onRetry = onRetry,
                onNoticeShown = onNoticeShown,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
    if (showFinishConfirm) {
        AlertDialog(
            onDismissRequest = { showFinishConfirm = false },
            title = { Text(text = stringResource(R.string.execution_finish_title)) },
            text = { Text(text = stringResource(R.string.execution_finish_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFinishConfirm = false
                        onFinishWorkout()
                    },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(text = stringResource(R.string.execution_finish_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showFinishConfirm = false },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(text = stringResource(R.string.execution_cancel))
                }
            }
        )
    }
}

/**
 * T9.3 (bloqueio 4): how long a validation notice stays on screen. Technical
 * choice, not a product decision: long enough for a screen reader to read it
 * twice, short enough that the user is not looking at a stale message.
 */
internal const val NOTICE_VISIBLE_MS = 5_000L

/** T9.2 (R-10): single source of the foreign-session sentence. */
internal const val FOREIGN_SESSION_NOTICE = "Sessão ativa em outro treino"

/** A session belongs to another workout: its actions must not touch this one. */
private fun WorkoutSessionState.isForeign(workoutId: Long): Boolean =
    (status is WorkoutSessionStatus.Running || status is WorkoutSessionStatus.Paused) &&
        this.workoutId != null && this.workoutId != workoutId

@Composable
private fun LoadingBlock(modifier: Modifier = Modifier) {
    val loadingDescription = stringResource(R.string.execution_loading_cd)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.semantics {
            contentDescription = loadingDescription
            liveRegion = LiveRegionMode.Polite
        }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.execution_loading_text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun NotFoundBlock(onBack: () -> Unit, modifier: Modifier = Modifier) {
    // T9.2 (R-20): one shared block for "record not found".
    MissingDestinationScreen(
        record = MissingRecord.WORKOUT,
        cause = MissingCause.NOT_FOUND,
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
private fun LoadErrorBlock(
    message: String,
    canRetry: Boolean,
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
            if (canRetry) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(text = stringResource(R.string.execution_retry))
                }
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text(text = stringResource(R.string.execution_back))
            }
        }
    }
}

// Rodapé: durante o descanso rodando, o RestCountdown OCUPA o lugar do CTA
// (R-23 por eliminação: durante um descanso, o descanso é a ação). Cancelar o
// descanso = pular. Nos demais estados, CTA(s) + (concluído ? banner).
@Composable
@Suppress("LongParameterList")
private fun FooterActions(
    state: WorkoutExecutionUiState.Ready,
    foreignSession: Boolean,
    onCompleteSeriesSet: (Long, Int) -> Unit,
    onCompleteExercise: (Long) -> Unit,
    onStartRest: (Long) -> Unit,
    onCancelRest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rest = state.restTimer
    if (rest != null && rest.status is RestTimerStatus.Running) {
        RestCountdown(
            remainingSeconds = rest.remainingSeconds,
            totalSeconds = rest.durationSeconds,
            onCancel = onCancelRest,
            modifier = modifier.fillMaxWidth()
        )
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (rest != null && rest.status is RestTimerStatus.Finished) {
            FinishedRestBanner()
        }
        val current = currentExercise(state) ?: return@Column
        if (!current.isCompleted) {
            when (current.spec.type) {
                ExerciseType.SERIES -> {
                    val nextSet = current.seriesSets
                        .filter { it.completedAtWallClockMs == null }
                        .minByOrNull { it.setNumber }
                    if (nextSet != null) {
                        val completeCd = stringResource(
                            R.string.execution_complete_set_cd,
                            nextSet.setNumber,
                            current.spec.name
                        )
                        PrimaryButton(
                            text = stringResource(
                                R.string.execution_complete_set,
                                nextSet.setNumber
                            ),
                            onClick = { onCompleteSeriesSet(current.spec.id, nextSet.setNumber) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = completeCd }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            text = stringResource(R.string.execution_complete_exercise),
                            onClick = { onCompleteExercise(current.spec.id) }
                        )
                        StartRestButton(
                            specRestSeconds = current.spec.restSeconds,
                            specName = current.spec.name,
                            onStartRest = { onStartRest(current.spec.id) }
                        )
                    }
                }
                else -> {
                    PrimaryButton(
                        text = stringResource(R.string.execution_complete_exercise),
                        onClick = { onCompleteExercise(current.spec.id) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    StartRestButton(
                        specRestSeconds = current.spec.restSeconds,
                        specName = current.spec.name,
                        onStartRest = { onStartRest(current.spec.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StartRestButton(specRestSeconds: Int, specName: String, onStartRest: () -> Unit) {
    val cd = stringResource(R.string.execution_start_rest_cd, specName)
    OutlinedButton(
        onClick = onStartRest,
        enabled = specRestSeconds > 0,
        modifier = Modifier
            .semantics { contentDescription = cd }
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
    ) {
        Text(text = stringResource(R.string.execution_start_rest))
    }
}

@Composable
private fun FinishedRestBanner(modifier: Modifier = Modifier) {
    // Spec §3.3: Assertive no "Descanso concluído".
    val success = LocalSuccessColors.current
    Surface(
        color = success.successContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive }
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleLarge,
                color = success.onSuccessContainer
            )
            Text(
                text = stringResource(R.string.execution_rest_done),
                style = MaterialTheme.typography.titleMedium,
                color = success.onSuccessContainer
            )
        }
    }
}

private fun currentExercise(state: WorkoutExecutionUiState.Ready): ExecutionExerciseState? {
    if (state.exercises.isEmpty()) return null
    val index = state.exercises.indexOfFirst { it.spec.id == state.selectedExerciseId }
    return if (index >= 0) state.exercises[index] else state.exercises[0]
}

@Composable
@Suppress("LongParameterList")
private fun ReadyContent(
    state: WorkoutExecutionUiState.Ready,
    foreignSession: Boolean,
    onSelectExercise: (Long) -> Unit,
    onNextExercise: () -> Unit,
    onPreviousExercise: () -> Unit,
    onRepsChanged: (Long, Int, String) -> Unit,
    onWeightChanged: (Long, Int, String) -> Unit,
    onDurationChanged: (Long, String) -> Unit,
    onDistanceChanged: (Long, String) -> Unit,
    onCompleteExercise: (Long) -> Unit,
    onHistorySelected: () -> Unit,
    onRetry: () -> Unit,
    onNoticeShown: () -> Unit,
    modifier: Modifier = Modifier
) {
    // T9.3 (bloqueio 4): notice com janela mínima de anúncio (ver lote 7 —
    // lógica intacta da Fase 9).
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(NOTICE_VISIBLE_MS)
            onNoticeShown()
        }
    }
    Column(modifier = modifier) {
        if (state.error != null) {
            ErrorBanner(
                error = state.error,
                // Only reached for a Save failure, whose retry is the write.
                onRetrySave = onCompleteExercise,
                onRetry = onRetry
            )
        }
        if (foreignSession) {
            // T9.2 (R-10): a frase mora num lugar só.
            Text(
                text = FOREIGN_SESSION_NOTICE,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        if (state.notice != null) {
            Text(
                text = state.notice,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        if (state.exercises.isEmpty()) {
            Text(
                text = stringResource(R.string.execution_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            val current = currentExercise(state)!!
            val position = state.exercises.indexOf(current)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("executionList"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "strip") {
                    ExerciseStrip(
                        exercises = state.exercises,
                        selectedId = current.spec.id,
                        onSelect = onSelectExercise,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                item(key = "current") {
                    CurrentExercise(
                        exercise = current,
                        position = position,
                        total = state.exercises.size,
                        completedCount = state.exercises.count { it.isCompleted },
                        isSaving = state.savingExerciseId == current.spec.id,
                        onRepsChanged = { set, value ->
                            onRepsChanged(current.spec.id, set, value)
                        },
                        onWeightChanged = { set, value ->
                            onWeightChanged(current.spec.id, set, value)
                        },
                        onDurationChanged = { value ->
                            onDurationChanged(current.spec.id, value)
                        },
                        onDistanceChanged = { value ->
                            onDistanceChanged(current.spec.id, value)
                        },
                        onOpenHistory = onHistorySelected,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                item(key = "pager") {
                    Pager(
                        position = position,
                        total = state.exercises.size,
                        onPrevious = onPreviousExercise,
                        onNext = onNextExercise,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ExerciseStrip(
    exercises: List<ExecutionExerciseState>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("exerciseStrip")
    ) {
        items(exercises, key = { it.spec.id }) { exercise ->
            val isSelected = exercise.spec.id == selectedId
            val label = if (exercise.isCompleted) {
                "${exercise.spec.name} ✓"
            } else {
                exercise.spec.name
            }
            if (isSelected) {
                Button(
                    onClick = { onSelect(exercise.spec.id) },
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { selected = true }
                ) {
                    Text(text = label)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelect(exercise.spec.id) },
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { selected = false }
                ) {
                    Text(text = label)
                }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun CurrentExercise(
    exercise: ExecutionExerciseState,
    position: Int,
    total: Int,
    completedCount: Int,
    isSaving: Boolean,
    onRepsChanged: (Int, String) -> Unit,
    onWeightChanged: (Int, String) -> Unit,
    onDurationChanged: (String) -> Unit,
    onDistanceChanged: (String) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spec = exercise.spec
    val currentDescription = stringResource(R.string.execution_current_cd, spec.name)
    val historyDescription = stringResource(R.string.execution_history_cd, spec.name)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = currentDescription }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(
                    R.string.execution_step,
                    position + 1,
                    total
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StepDots(
                currentIndex = position,
                total = total,
                completedCount = completedCount
            )
            Text(
                text = spec.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TypeBadge(
                    tone = when (spec.type) {
                        ExerciseType.SERIES -> TypeBadgeTone.Series
                        ExerciseType.TIMED -> TypeBadgeTone.Time
                        ExerciseType.DISTANCE_TIME -> TypeBadgeTone.DistanceTime
                    },
                    label = when (spec.type) {
                        ExerciseType.SERIES -> stringResource(R.string.editor_type_series)
                        ExerciseType.TIMED -> stringResource(R.string.editor_type_timed)
                        ExerciseType.DISTANCE_TIME -> stringResource(R.string.editor_type_distance)
                    }
                )
                if (exercise.isCompleted) {
                    // T9.3 (bloqueio 5): T6.3 A-4 approved this as assertive;
                    // the rebuild had dropped the semantics entirely.
                    Text(
                        text = stringResource(R.string.execution_done),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalSuccessColors.current.success,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Assertive
                        }
                    )
                }
                if (isSaving) {
                    Text(
                        text = stringResource(R.string.execution_saving),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedButton(
                    onClick = onOpenHistory,
                    modifier = Modifier
                        .semantics {
                            contentDescription = historyDescription
                        }
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(text = stringResource(R.string.execution_history))
                }
            }
            Text(
                text = if (spec.restSeconds > 0) {
                    stringResource(R.string.execution_rest_line, spec.restSeconds)
                } else {
                    stringResource(R.string.execution_no_rest)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!exercise.isCompleted) {
                when (spec.type) {
                    ExerciseType.SERIES -> {
                        exercise.seriesSets.forEach { set ->
                            if (set.completedAtWallClockMs != null) {
                                Text(
                                    text = collapsedSetLine(set),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LocalSuccessColors.current.success
                                )
                            } else {
                                PendingSeriesSet(
                                    set = set,
                                    onRepsChanged = onRepsChanged,
                                    onWeightChanged = onWeightChanged
                                )
                            }
                        }
                    }
                    ExerciseType.TIMED -> LabeledField(
                        label = stringResource(R.string.execution_duration_done_label),
                        value = exercise.durationSecondsText,
                        onValueChange = onDurationChanged,
                        unit = stringResource(R.string.execution_unit_s),
                        testTag = "field-duration"
                    )
                    ExerciseType.DISTANCE_TIME -> {
                        LabeledField(
                            label = stringResource(R.string.execution_duration_label),
                            value = exercise.durationSecondsText,
                            onValueChange = onDurationChanged,
                            unit = stringResource(R.string.execution_unit_s),
                            testTag = "field-duration"
                        )
                        LabeledField(
                            label = stringResource(R.string.execution_distance_label),
                            value = exercise.distanceMetersText,
                            onValueChange = onDistanceChanged,
                            unit = stringResource(R.string.execution_unit_m),
                            decimal = true,
                            testTag = "field-distance"
                        )
                    }
                }
            } else {
                // Concluído: séries colapsadas, sem campos nem CTA.
                exercise.seriesSets.forEach { set ->
                    Text(
                        text = collapsedSetLine(set),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalSuccessColors.current.success
                    )
                }
            }
        }
    }
}

@Composable
private fun collapsedSetLine(set: SeriesSetInputState): String {
    val reps = set.repsText
    val weight = set.weightKgText.takeIf { it.isNotBlank() }
    return if (weight != null) {
        stringResource(R.string.execution_done_set, reps, weight)
    } else {
        stringResource(R.string.execution_done_set_no_weight, reps)
    }
}

@Composable
private fun PendingSeriesSet(
    set: SeriesSetInputState,
    onRepsChanged: (Int, String) -> Unit,
    onWeightChanged: (Int, String) -> Unit
) {
    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.execution_set_label, set.setNumber),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledField(
                label = stringResource(R.string.execution_reps_label),
                value = set.repsText,
                onValueChange = { onRepsChanged(set.setNumber, it) },
                unit = stringResource(R.string.execution_unit_reps),
                modifier = Modifier.weight(1f),
                testTag = "field-reps-${set.setNumber}"
            )
            LabeledField(
                label = stringResource(R.string.execution_weight_label),
                value = set.weightKgText,
                onValueChange = { onWeightChanged(set.setNumber, it) },
                unit = stringResource(R.string.execution_unit_kg),
                decimal = true,
                modifier = Modifier.weight(1f),
                testTag = "field-weight-${set.setNumber}"
            )
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
    testTag: String? = null
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        NumericField(
            value = value,
            onValueChange = onValueChange,
            unit = unit,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
        )
    }
}

@Composable
private fun Pager(
    position: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        // Both pager actions stay enabled: the ViewModel owns the selection
        // and ignores a move that leaves the list, so disabling here would
        // swallow a tap the user expects to work after the list changes.
        OutlinedButton(
            onClick = onPrevious,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        ) {
            Text(text = stringResource(R.string.execution_prev))
        }
        Text(
            text = stringResource(R.string.execution_pager, position + 1, total),
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(
            onClick = onNext,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        ) {
            Text(text = stringResource(R.string.execution_next))
        }
    }
}

@Composable
private fun ErrorBanner(
    error: ExecutionError,
    onRetrySave: (Long) -> Unit,
    onRetry: () -> Unit
) {
    val (text, exerciseId) = when (error) {
        is ExecutionError.Save ->
            stringResource(R.string.execution_err_save) to error.exerciseId
        is ExecutionError.Latest ->
            stringResource(R.string.execution_err_latest) to error.exerciseId
        is ExecutionError.Timer -> stringResource(R.string.execution_err_timer) to null
    }
    // T9.2 (B-2 residual, Fase 6): a failed prefill of the previous execution
    // is information, not a blocking problem, so it is announced politely and
    // never interrupts like a save/timer failure.
    // T9.3 (bloqueio 3): a read failure must NEVER offer a retry that writes.
    // The old banner routed on `exerciseId != null`, and Latest carries one, so
    // "Tentar novamente" called onCompleteExercise — a history write — after a
    // failed read. Latest is now plain polite text with no action; only a write
    // failure repeats the write, and a timer failure repeats the timer command.
    val isReadFailure = error is ExecutionError.Latest
    val liveMode = if (isReadFailure) LiveRegionMode.Polite else LiveRegionMode.Assertive
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { liveRegion = liveMode }
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
        if (isReadFailure) return@Row
        Button(
            onClick = {
                if (exerciseId != null) onRetrySave(exerciseId) else onRetry()
            },
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        ) {
            Text(text = stringResource(R.string.execution_retry))
        }
    }
}
