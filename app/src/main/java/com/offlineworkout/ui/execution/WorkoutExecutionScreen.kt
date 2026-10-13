package com.offlineworkout.ui.execution

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.session.WorkoutSessionState
import com.offlineworkout.data.session.WorkoutSessionStatus
import com.offlineworkout.data.session.formatSessionElapsed
import com.offlineworkout.data.timer.RestTimerSnapshot
import com.offlineworkout.data.timer.RestTimerStatus
import com.offlineworkout.viewmodel.ExecutionError
import com.offlineworkout.viewmodel.ExecutionExerciseState
import com.offlineworkout.viewmodel.SeriesSetInputState
import com.offlineworkout.viewmodel.WorkoutExecutionUiState

// T6.1: stateless execution screen. Receives the immutable UiState plus
// callbacks; never counts time, never writes history. All actions are textual
// with 48dp targets; live regions announce countdown/completion/errors.
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
    Scaffold(modifier = modifier) { padding ->
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
                onSelectExercise = onSelectExercise,
                onNextExercise = onNextExercise,
                onPreviousExercise = onPreviousExercise,
                onRepsChanged = onRepsChanged,
                onWeightChanged = onWeightChanged,
                onDurationChanged = onDurationChanged,
                onDistanceChanged = onDistanceChanged,
                onCompleteSeriesSet = onCompleteSeriesSet,
                onCompleteExercise = onCompleteExercise,
                onStartRest = onStartRest,
                onCancelRest = onCancelRest,
                onHistorySelected = onHistorySelected,
                onPauseSession = onPauseSession,
                onResumeSession = onResumeSession,
                onFinishWorkout = onFinishWorkout,
                onRetry = onRetry,
                onNoticeShown = onNoticeShown,
                onBack = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

@Composable
private fun LoadingBlock(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.semantics {
            contentDescription = "Carregando treino"
            liveRegion = LiveRegionMode.Polite
        }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = "Carregando treino…",
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
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = "Tentar novamente")
                }
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
@Suppress("LongParameterList")
private fun ReadyContent(
    state: WorkoutExecutionUiState.Ready,
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
    val foreignSession = state.session.isForeign(state.workout.id)
    // T9.3 (bloqueio 4): the notice used to be acknowledged in the same frame
    // that showed it, so a node born and removed in one frame is never
    // announced — polite live region or not. It now stays for
    // NOTICE_VISIBLE_MS before being dismissed, which is also the smallest
    // window in which a screen reader can be relied on to read it out.
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
        if (state.exercises.isEmpty()) {
            Header(
                workoutName = state.workout.name,
                session = state.session,
                foreignSession = state.session.isForeign(state.workout.id),
                notice = state.notice,
                onPauseSession = onPauseSession,
                onResumeSession = onResumeSession,
                onBack = onBack
            )
            Text(
                text = "Treino sem exercícios",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            val index = state.exercises.indexOfFirst { it.spec.id == state.selectedExerciseId }
            val selected = if (index >= 0) index else 0
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("executionList"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "header") {
                    Header(
                        workoutName = state.workout.name,
                        session = state.session,
                        foreignSession = state.session.isForeign(state.workout.id),
                        notice = state.notice,
                        onPauseSession = onPauseSession,
                        onResumeSession = onResumeSession,
                        onBack = onBack
                    )
                }
                item(key = "rest") {
                    RestBanner(snapshot = state.restTimer, onCancel = onCancelRest)
                }
                items(state.exercises, key = { it.spec.id }) { exercise ->
                    ExerciseCard(
                        exercise = exercise,
                        isCurrent = exercise.spec.id == state.exercises[selected].spec.id,
                        isSaving = state.savingExerciseId == exercise.spec.id,
                        onSelect = { onSelectExercise(exercise.spec.id) },
                        onRepsChanged = { set, value -> onRepsChanged(exercise.spec.id, set, value) },
                        onWeightChanged = { set, value -> onWeightChanged(exercise.spec.id, set, value) },
                        onDurationChanged = { value -> onDurationChanged(exercise.spec.id, value) },
                        onDistanceChanged = { value -> onDistanceChanged(exercise.spec.id, value) },
                        onCompleteSeriesSet = { set -> onCompleteSeriesSet(exercise.spec.id, set) },
                        onCompleteExercise = { onCompleteExercise(exercise.spec.id) },
                        onStartRest = { onStartRest(exercise.spec.id) },
                        onOpenHistory = onHistorySelected
                    )
                }
                item(key = "pager") {
                    Pager(
                        position = selected,
                        total = state.exercises.size,
                        onPrevious = onPreviousExercise,
                        onNext = onNextExercise
                    )
                }
            }
        }
        // Pinned, not in the list: the two actions that must always be
        // reachable without scrolling the exercise list.
        //
        // T9.3 (bloqueio 6): all THREE session actions receive
        // enabled = !foreignSession, as T6.3 required. The finish button used to
        // stay enabled, so the tap opened a dialog promising to end a workout
        // that then did nothing.
        Button(
            onClick = { showFinishConfirm = true },
            enabled = !foreignSession && state.exercises.isNotEmpty(),
            modifier = Modifier
                .padding(16.dp)
                .heightIn(min = 48.dp)
        ) {
            Text(text = "Finalizar treino")
        }
    }
    if (showFinishConfirm) {
        AlertDialog(
            onDismissRequest = { showFinishConfirm = false },
            title = { Text(text = "Finalizar treino?") },
            text = { Text(text = "O treino será encerrado e o cronômetro total finalizado.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFinishConfirm = false
                        onFinishWorkout()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = "Finalizar")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showFinishConfirm = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = "Cancelar")
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
private fun Header(
    workoutName: String,
    session: WorkoutSessionState,
    foreignSession: Boolean,
    notice: String?,
    onPauseSession: () -> Unit,
    onResumeSession: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = workoutName,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() }
        )
        if (foreignSession) {
            // T9.2 (R-10): this is the only place the sentence is written. The
            // ViewModel used to set the same text as a notice, so the screen
            // showed it twice for a foreign session.
            Text(
                text = FOREIGN_SESSION_NOTICE,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Text(
                text = "Tempo total (outro treino): ${formatSessionElapsed(session.elapsedSeconds)}",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                text = "Tempo total: ${formatSessionElapsed(session.elapsedSeconds)}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (notice != null) {
            Text(
                text = notice,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            when (session.status) {
                is WorkoutSessionStatus.Running -> Button(
                    onClick = onPauseSession,
                    enabled = !foreignSession,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = "Pausar")
                }
                is WorkoutSessionStatus.Paused -> Button(
                    onClick = onResumeSession,
                    enabled = !foreignSession,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = "Retomar")
                }
                else -> Unit
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(text = "Voltar")
            }
        }
    }
}

@Composable
private fun RestBanner(snapshot: RestTimerSnapshot?, onCancel: () -> Unit) {
    if (snapshot == null) return
    val running = snapshot.status is RestTimerStatus.Running
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Text(
            text = if (running) "Restam ${snapshot.remainingSeconds}s" else "Descanso concluído",
            style = MaterialTheme.typography.bodyMedium
        )
        if (running) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .semantics { contentDescription = "Cancelar descanso em andamento" }
                    .heightIn(min = 48.dp)
            ) {
                Text(text = "Cancelar")
            }
        }
    }
}

@Composable
private fun Pager(position: Int, total: Int, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // Both pager actions stay enabled: the ViewModel owns the selection
        // and ignores a move that leaves the list, so disabling here would
        // swallow a tap the user expects to work after the list changes.
        OutlinedButton(
            onClick = onPrevious,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(text = "Anterior")
        }
        Text(
            text = "${position + 1} de $total",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(
            onClick = onNext,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(text = "Próximo")
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
            "Não foi possível salvar. Verifique os campos." to error.exerciseId
        is ExecutionError.Latest ->
            "Não foi possível ler o histórico anterior." to error.exerciseId
        is ExecutionError.Timer -> "Descanso indisponível." to null
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
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(text = "Tentar novamente")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
private fun ExerciseCard(
    exercise: ExecutionExerciseState,
    isCurrent: Boolean,
    isSaving: Boolean,
    onSelect: () -> Unit,
    onRepsChanged: (Int, String) -> Unit,
    onWeightChanged: (Int, String) -> Unit,
    onDurationChanged: (String) -> Unit,
    onDistanceChanged: (String) -> Unit,
    onCompleteSeriesSet: (Int) -> Unit,
    onCompleteExercise: () -> Unit,
    onStartRest: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val spec = exercise.spec
    Card(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics {
                if (isCurrent) contentDescription = "Exercício atual: ${spec.name}"
            }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = spec.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() }
                )
                if (exercise.isCompleted) {
                    // T9.3 (bloqueio 5): T6.3 A-4 approved this as assertive;
                    // the rebuild had dropped the semantics entirely.
                    Text(
                        text = "Concluído",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Assertive
                        }
                    )
                }
                if (isSaving) {
                    Text(text = "Salvando…", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(
                    onClick = onOpenHistory,
                    enabled = isCurrent,
                    modifier = Modifier
                        .semantics { contentDescription = "Ver histórico de ${spec.name}" }
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = "Histórico")
                }
            }
            Text(
                text = if (spec.restSeconds > 0) "Descanso: ${spec.restSeconds} s" else "Sem descanso",
                style = MaterialTheme.typography.bodyMedium
            )
            when (spec.type) {
                ExerciseType.SERIES -> {
                    exercise.seriesSets.forEach { set ->
                        SeriesRow(
                            set = set,
                            exerciseName = spec.name,
                            onRepsChanged = { value -> onRepsChanged(set.setNumber, value) },
                            onWeightChanged = { value -> onWeightChanged(set.setNumber, value) },
                            onComplete = { onCompleteSeriesSet(set.setNumber) }
                        )
                    }
                }
                ExerciseType.TIMED -> OutlinedTextField(
                    value = exercise.durationSecondsText,
                    onValueChange = onDurationChanged,
                    label = { Text(text = "Duração realizada (s)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                ExerciseType.DISTANCE_TIME -> {
                    OutlinedTextField(
                        value = exercise.durationSecondsText,
                        onValueChange = onDurationChanged,
                        label = { Text(text = "Duração (s)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = exercise.distanceMetersText,
                        onValueChange = onDistanceChanged,
                        label = { Text(text = "Distância (m)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Button(
                    onClick = onCompleteExercise,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = "Concluir exercício")
                }
                OutlinedButton(
                    onClick = onStartRest,
                    enabled = spec.restSeconds > 0,
                    modifier = Modifier
                        .semantics {
                            contentDescription = "Iniciar descanso de ${spec.name}"
                        }
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = "Descanso")
                }
            }
        }
    }
}

@Composable
private fun SeriesRow(
    set: SeriesSetInputState,
    exerciseName: String,
    onRepsChanged: (String) -> Unit,
    onWeightChanged: (String) -> Unit,
    onComplete: () -> Unit
) {
    val done = set.completedAtWallClockMs != null
    Column(modifier = Modifier.padding(top = 8.dp)) {
        if (done) {
            Text(
                text = "Série ${set.setNumber}: ${set.repsText} reps • ${set.weightKgText} kg",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = set.repsText,
                    onValueChange = onRepsChanged,
                    label = { Text(text = "Repetições (reps)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = set.weightKgText,
                    onValueChange = onWeightChanged,
                    label = { Text(text = "Peso (kg, opcional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onComplete,
                    modifier = Modifier
                        .semantics {
                            contentDescription = "Concluir série ${set.setNumber} de $exerciseName"
                        }
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = "OK")
                }
            }
        }
    }
}
