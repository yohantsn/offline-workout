package com.offlineworkout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlineworkout.data.model.DistanceTimeExecutionInput
import com.offlineworkout.data.model.ExerciseExecutionView
import com.offlineworkout.data.model.ExecutionSetInput
import com.offlineworkout.data.model.ExecutionSetView
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.SeriesExecutionInput
import com.offlineworkout.data.model.TimedExecutionInput
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.data.repository.ExerciseHistoryRepository
import com.offlineworkout.data.repository.RepositoryException
import com.offlineworkout.data.repository.RepositoryFailure
import com.offlineworkout.data.repository.WorkoutRepository
import com.offlineworkout.data.session.WorkoutSessionController
import com.offlineworkout.data.session.WorkoutSessionState
import com.offlineworkout.data.session.WorkoutSessionStatus
import com.offlineworkout.data.timer.RestRequest
import com.offlineworkout.data.timer.RestTimerCommandResult
import com.offlineworkout.data.timer.RestTimerController
import com.offlineworkout.data.timer.RestTimerSnapshot
import com.offlineworkout.data.timer.RestTimerStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// T6.1: single execution ViewModel. Combines workout/exercises reads, per
// selected-exercise latest prefill, local series inputs, one atomic history
// write per concluded exercise, the T5.3 rest timer and the session total.
// Never touches Room/Service/DAOs; never counts time; never writes history
// except through ExerciseHistoryRepository record* calls.
sealed interface WorkoutExecutionUiState {
    data object Loading : WorkoutExecutionUiState
    data class Ready(
        val workout: WorkoutSummary,
        val exercises: List<ExecutionExerciseState>,
        val selectedExerciseId: Long?,
        val restTimer: RestTimerSnapshot?,
        val session: WorkoutSessionState,
        val savingExerciseId: Long?,
        val error: ExecutionError?,
        val notice: String?
    ) : WorkoutExecutionUiState
    data class Error(
        val message: String,
        val canRetry: Boolean = true
    ) : WorkoutExecutionUiState
    data object NotFound : WorkoutExecutionUiState
}

data class SeriesSetInputState(
    val setNumber: Int,
    val repsText: String,
    val weightKgText: String,
    val completedAtWallClockMs: Long?
)

data class ExecutionExerciseState(
    val spec: ExerciseSpec,
    val seriesSets: List<SeriesSetInputState>,
    val durationSecondsText: String,
    val distanceMetersText: String,
    val isCompleted: Boolean,
    val completedAtWallClockMs: Long?,
    val latestExecution: ExerciseExecutionView?,
    val latestSet: ExecutionSetView?
)

sealed interface ExecutionError {
    // T9.2 (R-21): the Load case was never constructed; load failures surface as
    // WorkoutExecutionUiState.Error, which has its own retry. Removed.
    data class Save(val exerciseId: Long, val failure: RepositoryFailure) : ExecutionError
    data class Latest(val exerciseId: Long, val failure: RepositoryFailure) : ExecutionError
    data class Timer(val failure: RestTimerCommandResult.Invalid) : ExecutionError
}

sealed interface WorkoutExecutionEffect {
    data object WorkoutFinished : WorkoutExecutionEffect
    data class OpenHistory(val workoutId: Long, val exerciseId: Long) : WorkoutExecutionEffect
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutExecutionViewModel(
    private val workoutId: Long,
    private val repository: WorkoutRepository,
    private val history: ExerciseHistoryRepository,
    private val restTimer: RestTimerController,
    private val session: WorkoutSessionController
) : ViewModel() {

    private data class Loaded(
        val workout: WorkoutSummary?,
        val specs: List<ExerciseSpec>,
        val failure: RepositoryFailure?
    )

    private data class LocalExercise(
        val seriesSets: List<SeriesSetInputState>,
        val durationSecondsText: String,
        val distanceMetersText: String,
        val isCompleted: Boolean,
        val completedAtWallClockMs: Long?,
        val latestExecution: ExerciseExecutionView?,
        val latestSet: ExecutionSetView?
    )

    private data class LatestData(
        val execution: ExerciseExecutionView?,
        val set: ExecutionSetView?,
        val failure: RepositoryFailure?
    )

    private val loadToken = MutableStateFlow(0)
    private val selectedId = MutableStateFlow<Long?>(null)
    private val localEdits = MutableStateFlow<Map<Long, LocalExercise>>(emptyMap())
    private val savingId = MutableStateFlow<Long?>(null)
    private val stickyError = MutableStateFlow<ExecutionError?>(null)
    private val noticeFlow = MutableStateFlow<String?>(null)
    private var finishedEmitted = false

    // T9.2 (R-15b): last emitted entries, keyed by exercise id. Only touched
    // from buildState, which runs in the combine collector.
    private var lastExerciseStates: Map<Long, ExecutionExerciseState> = emptyMap()
    private var lastExerciseList: List<ExecutionExerciseState> = emptyList()

    private val effectsChannel = Channel<WorkoutExecutionEffect>(Channel.BUFFERED)
    val effects: Flow<WorkoutExecutionEffect> = effectsChannel.receiveAsFlow()

    val state: StateFlow<WorkoutExecutionUiState> = combine(
        loadToken.flatMapLatest {
            combine(
                repository.observeWorkout(workoutId),
                repository.observeExercises(workoutId)
            ) { workout, specs -> Loaded(workout, specs, null) }
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(
                        Loaded(
                            null, emptyList(),
                            (e as? RepositoryException)?.failure
                                ?: RepositoryFailure.Storage("unexpected data error")
                        )
                    )
                }
        },
        selectedId,
        session.state,
        restTimer.state,
        localEdits,
        savingId,
        stickyError,
        noticeFlow
    ) { args ->
        // R-11: the array form of combine erases to Array<Any?>, so each slot
        // is a checked cast with an explicit type argument instead of a blanket
        // unchecked cast on the whole array.
        val loaded = args[0] as Loaded
        val selected = args[1] as Long?
        val sessionState = args[2] as WorkoutSessionState
        val timerSnapshot = args[3] as RestTimerSnapshot?
        // The only cast the compiler cannot verify: combine erases the map's
        // value type, and the flow that produces this slot is typed
        // Map<Long, LocalExercise> a few lines below.
        @Suppress("UNCHECKED_CAST")
        val locals = args[4] as Map<Long, LocalExercise>
        val saving = args[5] as Long?
        val error = args[6] as ExecutionError?
        val notice = args[7] as String?
        buildState(loaded, selected, sessionState, timerSnapshot, locals, saving, error, notice)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutExecutionUiState.Loading)

    init {
        session.startOrResume(workoutId)
        viewModelScope.launch {
            selectedId.flatMapLatest { id ->
                if (id == null) {
                    flowOf(LatestData(null, null, null))
                } else {
                    combine(
                        history.observeLatestExecution(id),
                        history.observeLatestSet(id)
                    ) { execution, set -> LatestData(execution, set, null) }
                        .catch { e ->
                            if (e is CancellationException) throw e
                            emit(
                                LatestData(
                                    null, null,
                                    (e as? RepositoryException)?.failure
                                        ?: RepositoryFailure.Storage("unexpected data error")
                                )
                            )
                        }
                }
            }.collect { data -> applyLatest(selectedId.value, data) }
        }
    }

    fun onSelectExercise(exerciseId: Long) {
        selectedId.value = exerciseId
    }

    fun onNextExercise() {
        moveSelection(1)
    }

    fun onPreviousExercise() {
        moveSelection(-1)
    }

    fun onRepsChanged(exerciseId: Long, setNumber: Int, value: String) {
        updateSeriesRow(exerciseId, setNumber) { it.copy(repsText = value) }
    }

    fun onWeightChanged(exerciseId: Long, setNumber: Int, value: String) {
        updateSeriesRow(exerciseId, setNumber) { it.copy(weightKgText = value) }
    }

    fun onDurationChanged(exerciseId: Long, value: String) {
        updateLocal(exerciseId) { it.copy(durationSecondsText = value) }
    }

    fun onDistanceChanged(exerciseId: Long, value: String) {
        updateLocal(exerciseId) { it.copy(distanceMetersText = value) }
    }

    fun onCompleteSeriesSet(exerciseId: Long, setNumber: Int) {
        val local = localEdits.value[exerciseId]
            ?: specFor(exerciseId)?.let { defaultLocal(it) }
            ?: run {
                noticeFlow.value = "Série não encontrada"
                return
            }
        val row = local.seriesSets.firstOrNull { it.setNumber == setNumber } ?: return
        if (row.completedAtWallClockMs != null) return
        val reps = row.repsText.toIntOrNull()
        if (reps == null || reps <= 0) {
            noticeFlow.value = "Repetições deve ser inteiro > 0"
            return
        }
        val weight = row.weightKgText.ifBlank { null }?.toDoubleOrNull()
        if (row.weightKgText.isNotBlank() && (weight == null || weight < 0)) {
            noticeFlow.value = "Peso deve ser decimal >= 0"
            return
        }
        updateLocal(exerciseId) { current ->
            current.copy(
                seriesSets = current.seriesSets.map {
                    if (it.setNumber == setNumber) it.copy(completedAtWallClockMs = wallClockMs()) else it
                }
            )
        }
        startRestFor(exerciseId)
    }

    fun onCompleteExercise(exerciseId: Long) {
        if (savingId.value != null) return
        val current = (state.value as? WorkoutExecutionUiState.Ready) ?: return
        val exercise = current.exercises.firstOrNull { it.spec.id == exerciseId } ?: return
        if (exercise.isCompleted) return
        when (exercise.spec.type) {
            ExerciseType.SERIES -> saveSeries(exercise)
            ExerciseType.TIMED -> saveTimed(exercise)
            ExerciseType.DISTANCE_TIME -> saveDistanceTime(exercise)
        }
    }

    fun onStartRest(exerciseId: Long) {
        val current = (state.value as? WorkoutExecutionUiState.Ready) ?: return
        val spec = current.exercises.firstOrNull { it.spec.id == exerciseId }?.spec
        if (spec == null) {
            noticeFlow.value = "Exercício não encontrado"
            return
        }
        if (spec.restSeconds <= 0) return
        startRestTimer(spec)
    }

    fun onCancelRest() {
        val running = restTimer.state.value?.status as? RestTimerStatus.Running ?: return
        restTimer.cancel(running.timerId)
    }

    fun onHistorySelected() {
        val current = (state.value as? WorkoutExecutionUiState.Ready) ?: return
        val selected = current.selectedExerciseId ?: return
        if (current.exercises.none { it.spec.id == selected }) return
        effectsChannel.trySend(WorkoutExecutionEffect.OpenHistory(workoutId, selected))
    }

    fun onPauseSession() {
        if (rejectForeignSession()) return
        session.pause()
    }

    fun onResumeSession() {
        if (rejectForeignSession()) return
        session.resume()
    }

    fun onFinishWorkout() {
        if (finishedEmitted) return
        // T9.3 (bloqueio 6): the rest timer of the OTHER workout was left
        // running on the rejection path — the old code returned before
        // cancelling it, so leaving this screen left a stranger's countdown
        // going. Cancel first, then decide.
        (restTimer.state.value?.status as? RestTimerStatus.Running)?.let {
            restTimer.cancel(it.timerId)
        }
        if (rejectForeignSession()) return
        session.stop()
        finishedEmitted = true
        effectsChannel.trySend(WorkoutExecutionEffect.WorkoutFinished)
    }

    // R-1: never mutate another workout's session. Returns true when the
    // action was rejected (with a notice), false when the caller may proceed.
    private fun rejectForeignSession(): Boolean {
        val state = session.state.value
        val foreign = (state.status is WorkoutSessionStatus.Running || state.status is WorkoutSessionStatus.Paused) &&
            state.workoutId != null && state.workoutId != workoutId
        // T9.2 (R-10) keeps the *header* sentence in one place only; this
        // notice is a different sentence about a different consequence
        // (T9.3, bloqueio 6), so the user still learns that the tap was
        // refused instead of nothing happening.
        if (foreign) noticeFlow.value = FOREIGN_SESSION_ACTION_REFUSED
        return foreign
    }

    fun onRetry() {
        stickyError.value = null
        loadToken.value += 1
    }

    fun onNoticeShown() {
        noticeFlow.value = null
    }

    /**
     * T9.3 (bloqueio 6): the refusal message. Deliberately different from
     * `FOREIGN_SESSION_NOTICE` (the header sentence owned by the screen), so
     * the two never read as the same duplicated string.
     */
    companion object {
        const val FOREIGN_SESSION_ACTION_REFUSED =
            "Ação indisponível enquanto a sessão de outro treino estiver ativa."
    }

    /**
     * T9.2 (R-15b): does a previously emitted entry still describe this spec and
     * this local input? Everything in [ExecutionExerciseState] comes from those
     * two, so comparing them is comparing the whole entry.
     */
    private fun ExecutionExerciseState.matches(spec: ExerciseSpec, local: LocalExercise): Boolean =
        this.spec == spec &&
            this.seriesSets == local.seriesSets &&
            this.durationSecondsText == local.durationSecondsText &&
            this.distanceMetersText == local.distanceMetersText &&
            this.isCompleted == local.isCompleted &&
            this.completedAtWallClockMs == local.completedAtWallClockMs &&
            this.latestExecution == local.latestExecution &&
            this.latestSet == local.latestSet

    private fun buildState(
        loaded: Loaded,
        selected: Long?,
        sessionState: WorkoutSessionState,
        timerSnapshot: RestTimerSnapshot?,
        locals: Map<Long, LocalExercise>,
        saving: Long?,
        error: ExecutionError?,
        notice: String?
    ): WorkoutExecutionUiState {
        if (loaded.failure != null) {
            return WorkoutExecutionUiState.Error(
                message = loaded.failure.message ?: "unexpected data error",
                canRetry = true
            )
        }
        val workout = loaded.workout ?: return WorkoutExecutionUiState.NotFound
        ensureSelection(selected, loaded.specs, locals)
        val resolvedSelected = selectedId.value
        // T9.2 (R-15b): the timer snapshot changes every second for the whole
        // rest period. Rebuilding every ExecutionExerciseState on each tick gave
        // every visible ExerciseCard a new instance, so each one recomposed once
        // per second, with its OutlinedTextFields. An entry is now reused when
        // the spec and the local input behind it did not change, so a timer
        // tick (or a selection change) leaves the exercise entries identical and
        // Compose skips those cards. The state contract is unchanged: same
        // fields, same values, same Ready shape.
        val rebuilt = loaded.specs.map { spec ->
            val local = locals[spec.id] ?: defaultLocal(spec)
            val previous = lastExerciseStates[spec.id]
            if (previous != null && previous.matches(spec, local)) {
                previous
            } else {
                ExecutionExerciseState(
                    spec = spec,
                    seriesSets = local.seriesSets,
                    durationSecondsText = local.durationSecondsText,
                    distanceMetersText = local.distanceMetersText,
                    isCompleted = local.isCompleted,
                    completedAtWallClockMs = local.completedAtWallClockMs,
                    latestExecution = local.latestExecution,
                    latestSet = local.latestSet
                )
            }
        }
        // Reuse the list itself when every entry was reused, so even a
        // `remember(exercises)` upstream is not invalidated by a timer tick.
        val states = if (
            lastExerciseList.size == rebuilt.size &&
            lastExerciseList.indices.all { lastExerciseList[it] === rebuilt[it] }
        ) {
            lastExerciseList
        } else {
            rebuilt
        }
        lastExerciseList = states
        lastExerciseStates = states.associateBy { it.spec.id }
        return WorkoutExecutionUiState.Ready(
            workout = workout,
            exercises = states,
            selectedExerciseId = resolvedSelected,
            restTimer = timerSnapshot,
            session = sessionState,
            savingExerciseId = saving,
            error = error,
            notice = notice
        )
    }

    private fun ensureSelection(
        selected: Long?,
        specs: List<ExerciseSpec>,
        locals: Map<Long, LocalExercise>
    ) {
        if (specs.isEmpty()) {
            selectedId.value = null
            return
        }
        if (selected == null || specs.none { it.id == selected }) {
            selectedId.value = specs.firstOrNull { !(locals[it.id]?.isCompleted == true) }?.id
                ?: specs.first().id
        }
    }

    private fun defaultLocal(spec: ExerciseSpec): LocalExercise {
        val rows = if (spec.type == ExerciseType.SERIES) {
            val count = (spec.targetSets ?: 0).takeIf { it > 0 } ?: 1
            (1..count).map { SeriesSetInputState(it, "", "", null) }
        } else {
            emptyList()
        }
        return LocalExercise(
            seriesSets = rows,
            durationSecondsText = "",
            distanceMetersText = "",
            isCompleted = false,
            completedAtWallClockMs = null,
            latestExecution = null,
            latestSet = null
        )
    }

    private fun updateLocal(exerciseId: Long, transform: (LocalExercise) -> LocalExercise) {
        // Local edits need a base: fall back to the spec-derived default when the
        // exercise has no entry yet (e.g. edits racing the first load).
        val base = localEdits.value[exerciseId] ?: specFor(exerciseId)?.let { defaultLocal(it) } ?: return
        localEdits.value = localEdits.value + (exerciseId to transform(base))
    }

    private fun updateSeriesRow(
        exerciseId: Long,
        setNumber: Int,
        transform: (SeriesSetInputState) -> SeriesSetInputState
    ) {
        updateLocal(exerciseId) { current ->
            val row = current.seriesSets.firstOrNull { it.setNumber == setNumber } ?: return@updateLocal current
            if (row.completedAtWallClockMs != null) return@updateLocal current
            current.copy(
                seriesSets = current.seriesSets.map { if (it.setNumber == setNumber) transform(it) else it }
            )
        }
    }

    private fun specFor(exerciseId: Long): ExerciseSpec? =
        ((state.value as? WorkoutExecutionUiState.Ready)?.exercises
            ?.firstOrNull { it.spec.id == exerciseId }?.spec)

    private fun moveSelection(delta: Int) {
        val current = (state.value as? WorkoutExecutionUiState.Ready) ?: return
        if (current.exercises.isEmpty()) return
        val ids = current.exercises.map { it.spec.id }
        val index = ids.indexOf(current.selectedExerciseId).takeIf { it >= 0 } ?: 0
        selectedId.value = ids[(index + delta).coerceIn(ids.indices)]
    }

    private fun applyLatest(selected: Long?, data: LatestData) {
        if (selected == null) return
        if (data.failure != null) {
            if (stickyError.value == null) {
                stickyError.value = ExecutionError.Latest(selected, data.failure)
            }
            return
        }
        if (stickyError.value is ExecutionError.Latest) stickyError.value = null
        val base = localEdits.value[selected] ?: specFor(selected)?.let { defaultLocal(it) } ?: return
        var updated = base.copy(latestExecution = data.execution, latestSet = data.set)
        val spec = specFor(selected) ?: return
        updated = updated.copy(
            seriesSets = fillSeriesBlanks(updated.seriesSets, spec, data.set),
            durationSecondsText = updated.durationSecondsText.ifBlank {
                data.execution?.durationSeconds?.toString()
                    ?: spec.targetDurationSeconds?.toString() ?: ""
            },
            distanceMetersText = updated.distanceMetersText.ifBlank {
                data.execution?.distanceMeters?.toString()
                    ?: spec.targetDistanceMeters?.toString() ?: ""
            }
        )
        localEdits.value = localEdits.value + (selected to updated)
    }

    private fun fillSeriesBlanks(
        rows: List<SeriesSetInputState>,
        spec: ExerciseSpec,
        latestSet: ExecutionSetView?
    ): List<SeriesSetInputState> {
        if (spec.type != ExerciseType.SERIES) return rows
        var repsFilled = false
        var weightFilled = false
        return rows.map { row ->
            if (row.completedAtWallClockMs != null) return@map row
            var next = row
            if (!repsFilled && next.repsText.isBlank()) {
                val reps = latestSet?.reps?.toString() ?: spec.targetReps?.toString() ?: ""
                if (reps.isNotBlank()) {
                    next = next.copy(repsText = reps)
                    repsFilled = true
                }
            }
            if (!weightFilled && next.weightKgText.isBlank()) {
                val weight = latestSet?.weightKg?.toString() ?: ""
                if (weight.isNotBlank()) {
                    next = next.copy(weightKgText = weight)
                    weightFilled = true
                }
            }
            next
        }
    }

    private fun saveSeries(exercise: ExecutionExerciseState) {
        val completed = exercise.seriesSets.filter { it.completedAtWallClockMs != null }
        if (completed.isEmpty()) {
            noticeFlow.value = "Conclua ao menos uma série válida"
            return
        }
        val sets = completed.sortedBy { it.setNumber }.mapIndexed { index, row ->
            ExecutionSetInput(
                setNumber = index + 1,
                reps = row.repsText.toIntOrNull() ?: 0,
                weightKg = row.weightKgText.ifBlank { null }?.toDoubleOrNull()
            )
        }
        val performedAt = completed.mapNotNull { it.completedAtWallClockMs }.minOrNull()
            ?: wallClockMs()
        savingId.value = exercise.spec.id
        viewModelScope.launch {
            val result = history.recordSeriesExecution(
                SeriesExecutionInput(
                    exerciseId = exercise.spec.id,
                    performedAt = performedAt,
                    completedAt = wallClockMs(),
                    sets = sets
                )
            )
            onSaveResult(exercise, result)
        }
    }

    private fun saveTimed(exercise: ExecutionExerciseState) {
        val texts = localEdits.value[exercise.spec.id]
        val duration = texts?.durationSecondsText?.toLongOrNull()
        if (duration == null || duration <= 0) {
            noticeFlow.value = "Duração deve ser inteiro > 0"
            return
        }
        savingId.value = exercise.spec.id
        viewModelScope.launch {
            val result = history.recordTimedExecution(
                TimedExecutionInput(
                    exerciseId = exercise.spec.id,
                    performedAt = wallClockMs(),
                    completedAt = wallClockMs(),
                    durationSeconds = duration
                )
            )
            onSaveResult(exercise, result)
        }
    }

    private fun saveDistanceTime(exercise: ExecutionExerciseState) {
        val texts = localEdits.value[exercise.spec.id]
        val duration = texts?.durationSecondsText?.toLongOrNull()
        val distanceText = texts?.distanceMetersText ?: ""
        if (duration == null || duration <= 0) {
            noticeFlow.value = "Duração deve ser inteiro > 0"
            return
        }
        if (distanceText.isBlank()) {
            noticeFlow.value = "Informe a distância"
            return
        }
        val distance = distanceText.toDoubleOrNull()
        if (distance == null || distance < 0) {
            noticeFlow.value = "Distância deve ser decimal >= 0"
            return
        }
        savingId.value = exercise.spec.id
        viewModelScope.launch {
            val result = history.recordDistanceTimeExecution(
                DistanceTimeExecutionInput(
                    exerciseId = exercise.spec.id,
                    performedAt = wallClockMs(),
                    completedAt = wallClockMs(),
                    durationSeconds = duration,
                    distanceMeters = distance
                )
            )
            onSaveResult(exercise, result)
        }
    }

    private fun onSaveResult(exercise: ExecutionExerciseState, result: Result<Long>) {
        savingId.value = null
        result.onSuccess {
            stickyError.value = null
            updateLocal(exercise.spec.id) { current ->
                current.copy(isCompleted = true, completedAtWallClockMs = wallClockMs())
            }
            advanceSelection(exercise.spec.id)
            startRestFor(exercise.spec.id)
        }.onFailure { e ->
            val failure = (e as? RepositoryFailure)
                ?: RepositoryFailure.Storage("unexpected data error")
            stickyError.value = ExecutionError.Save(exercise.spec.id, failure)
        }
    }

    private fun advanceSelection(completedId: Long) {
        val current = (state.value as? WorkoutExecutionUiState.Ready) ?: return
        val completedIds = current.exercises
            .filter { it.spec.id == completedId || it.isCompleted }
            .map { it.spec.id }
            .toSet()
        val next = current.exercises.firstOrNull { it.spec.id !in completedIds }?.spec?.id
        if (next != null) selectedId.value = next
    }

    private fun startRestFor(exerciseId: Long) {
        val current = (state.value as? WorkoutExecutionUiState.Ready) ?: return
        val spec = current.exercises.firstOrNull { it.spec.id == exerciseId }?.spec ?: return
        startRestTimer(spec, clearNoticeOnStart = false)
    }

    private fun startRestTimer(spec: ExerciseSpec, clearNoticeOnStart: Boolean = true) {
        if (spec.restSeconds <= 0) return
        when (val result = restTimer.start(RestRequest(workoutId, spec.id, spec.restSeconds))) {
            is RestTimerCommandResult.Started,
            is RestTimerCommandResult.Finished,
            is RestTimerCommandResult.Cancelled -> if (clearNoticeOnStart) noticeFlow.value = null
            is RestTimerCommandResult.Busy ->
                noticeFlow.value = "Já há um descanso em andamento"
            is RestTimerCommandResult.Invalid ->
                stickyError.value = ExecutionError.Timer(result)
        }
    }

    private fun wallClockMs(): Long = System.currentTimeMillis()
}
