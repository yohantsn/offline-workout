package com.offlineworkout.viewmodel

import com.offlineworkout.ui.MissingRecord
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlineworkout.data.model.ExerciseExecutionView
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.HISTORY_PAGE_SIZE
import com.offlineworkout.data.model.HistoryChartData
import com.offlineworkout.data.model.HistoryQuery
import com.offlineworkout.data.model.HistoryStats
import com.offlineworkout.data.model.MinMaxDownsampler
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.data.model.chartUnits
import com.offlineworkout.data.model.toHistoryPoints
import com.offlineworkout.data.repository.ExerciseHistoryRepository
import com.offlineworkout.data.repository.RepositoryException
import com.offlineworkout.data.repository.RepositoryFailure
import com.offlineworkout.data.repository.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

// T7.1: single read-only history ViewModel. Selects one exercise of the
// workout, pages its executions (50/page, newest first), fetches stats +
// chart window once per selection, and never writes. Only interfaces.
sealed interface ExerciseHistoryUiState {
    data object Loading : ExerciseHistoryUiState
    data class Ready(
        val workoutName: String?,
        val exercises: List<ExerciseSpec>,
        val selectedExerciseId: Long?,
        val rows: List<ExerciseExecutionView>,
        val chart: HistoryChartData?,
        val stats: HistoryStats?,
        val hasMore: Boolean,
        val isLoadingMore: Boolean,
        val isDownsampled: Boolean,
        val error: HistoryError?
    ) : ExerciseHistoryUiState
    data class Error(
        val failure: RepositoryFailure,
        val canRetry: Boolean = true
    ) : ExerciseHistoryUiState
    /**
     * T9.3 (bloqueio 7): NotFound is emitted for two different situations — the
     * workout does not exist, or the exercise does not belong to it. The state
     * now carries which one, so the screen stops accusing the exercise when the
     * workout is what is gone.
     */
    data class NotFound(val record: MissingRecord) : ExerciseHistoryUiState
}

sealed interface HistoryError {
    data class Load(val failure: RepositoryFailure) : HistoryError
    data class Stats(val failure: RepositoryFailure) : HistoryError
}

sealed interface ExerciseHistoryEffect {
    data class OpenHistory(val workoutId: Long, val exerciseId: Long) : ExerciseHistoryEffect
    data object Back : ExerciseHistoryEffect
}

class ExerciseHistoryViewModel(
    private val workoutId: Long,
    initialExerciseId: Long?,
    private val workoutRepository: WorkoutRepository,
    private val historyRepository: ExerciseHistoryRepository
) : ViewModel() {

    private val stateFlow = MutableStateFlow<ExerciseHistoryUiState>(ExerciseHistoryUiState.Loading)
    val state: StateFlow<ExerciseHistoryUiState> = stateFlow

    private val effectsChannel = Channel<ExerciseHistoryEffect>(Channel.BUFFERED)
    val effects: Flow<ExerciseHistoryEffect> = effectsChannel.receiveAsFlow()

    private var cachedWorkout: WorkoutSummary? = null
    private var cachedSpecs: List<ExerciseSpec> = emptyList()
    private var selectedId: Long? = null
    private var rowsAcc: List<ExerciseExecutionView> = emptyList()
    private var offset = 0
    private var hasMoreState = false
    private var failedPageOffset: Int? = null
    private var loadingMore = false
    private var pageError: HistoryError.Load? = null
    private var statsWarning: HistoryError.Stats? = null
    private var chartState: HistoryChartData? = null
    private var statsState: HistoryStats? = null
    // R-1: selection generation. Every select()/clear bumps it; loads capture
    // it and discard results (or errors) from a previous selection instead of
    // writing rows/chart/stats of another exercise into the current state.
    private var selectionGeneration = 0

    init {
        loadAll(initialExerciseId)
    }

    fun onSelectExercise(exerciseId: Long) {
        if (cachedSpecs.none { it.id == exerciseId }) return
        if (exerciseId == selectedId) return
        select(exerciseId, navigate = true)
    }

    fun onClearExercise() {
        selectedId = null
        selectionGeneration += 1
        rowsAcc = emptyList()
        offset = 0
        hasMoreState = false
        failedPageOffset = null
        loadingMore = false
        pageError = null
        statsWarning = null
        chartState = null
        statsState = null
        stateFlow.value = ExerciseHistoryUiState.Ready(
            workoutName = cachedWorkout?.name,
            exercises = cachedSpecs,
            selectedExerciseId = null,
            rows = emptyList(),
            chart = null,
            stats = null,
            hasMore = false,
            isLoadingMore = false,
            isDownsampled = false,
            error = null
        )
    }

    fun onLoadMore() {
        val current = stateFlow.value as? ExerciseHistoryUiState.Ready ?: return
        if (loadingMore || !current.hasMore || current.selectedExerciseId == null) return
        loadingMore = true
        stateFlow.value = current.copy(isLoadingMore = true)
        loadPage(offset, append = true)
    }

    fun onRetry() {
        when (val current = stateFlow.value) {
            is ExerciseHistoryUiState.Error -> loadAll(selectedId)
            is ExerciseHistoryUiState.Ready -> {
                when (current.error) {
                    is HistoryError.Load -> loadPage(failedPageOffset ?: offset, append = rowsAcc.isNotEmpty())
                    is HistoryError.Stats -> loadChartStats()
                    null -> loadAll(selectedId)
                }
            }
            else -> Unit
        }
    }

    fun onBack() {
        effectsChannel.trySend(ExerciseHistoryEffect.Back)
    }

    private fun loadAll(initialExerciseId: Long?) {
        stateFlow.value = ExerciseHistoryUiState.Loading
        // T9.2 (R-8): the initial load suspends twice (workout, then
        // exercises). A selection that happens in between must not be undone by
        // a stale NotFound/Ready/Error written by this load, exactly like
        // loadPage and loadChartStats do.
        val generation = selectionGeneration
        viewModelScope.launch {
            try {
                val workout = workoutRepository.observeWorkout(workoutId).first()
                if (generation != selectionGeneration) return@launch
                if (workout == null) {
                    stateFlow.value =
                        ExerciseHistoryUiState.NotFound(MissingRecord.WORKOUT)
                    return@launch
                }
                cachedWorkout = workout
                cachedSpecs = workoutRepository.observeExercises(workoutId).first()
                if (generation != selectionGeneration) return@launch
                val initial = initialExerciseId
                    ?.takeIf { id -> id > 0 && cachedSpecs.any { it.id == id } }
                if (initialExerciseId != null && initial == null) {
                    stateFlow.value =
                        ExerciseHistoryUiState.NotFound(MissingRecord.EXERCISE)
                    return@launch
                }
                if (initial != null) {
                    select(initial, navigate = false)
                } else {
                    stateFlow.value = ExerciseHistoryUiState.Ready(
                        workoutName = workout.name,
                        exercises = cachedSpecs,
                        selectedExerciseId = null,
                        rows = emptyList(),
                        chart = null,
                        stats = null,
                        hasMore = false,
                        isLoadingMore = false,
                        isDownsampled = false,
                        error = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != selectionGeneration) return@launch
                stateFlow.value = ExerciseHistoryUiState.Error(
                    failure = (e as? RepositoryException)?.failure
                        ?: RepositoryFailure.Storage("unexpected data error")
                )
            }
        }
    }

    private fun select(exerciseId: Long, navigate: Boolean) {
        selectedId = exerciseId
        selectionGeneration += 1
        val generation = selectionGeneration
        rowsAcc = emptyList()
        offset = 0
        hasMoreState = false
        failedPageOffset = null
        loadingMore = false
        pageError = null
        statsWarning = null
        chartState = null
        statsState = null
        if (navigate) {
            effectsChannel.trySend(ExerciseHistoryEffect.OpenHistory(workoutId, exerciseId))
        }
        // T9.2 (R-7): the generation captured above is now passed explicitly
        // instead of being a dead local that both loads re-derived by default.
        loadPage(0, append = false, generation = generation)
        loadChartStats(generation = generation)
    }

    private fun loadPage(pageOffset: Int, append: Boolean, generation: Int = selectionGeneration) {
        val exerciseId = selectedId ?: return
        viewModelScope.launch {
            try {
                val page = historyRepository.observeHistoryPage(
                    HistoryQuery(
                        exerciseId = exerciseId,
                        limit = HISTORY_PAGE_SIZE,
                        offset = pageOffset
                    )
                ).first()
                if (generation != selectionGeneration) return@launch
                failedPageOffset = null
                rowsAcc = if (append) {
                    (rowsAcc + page).distinctBy { it.id }
                } else {
                    page
                }
                offset = pageOffset + page.size
                hasMoreState = page.size >= HISTORY_PAGE_SIZE
                loadingMore = false
                pageError = null
                emitReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != selectionGeneration) return@launch
                loadingMore = false
                val failure = (e as? RepositoryException)?.failure
                    ?: RepositoryFailure.Storage("unexpected data error")
                if (rowsAcc.isEmpty()) {
                    stateFlow.value = ExerciseHistoryUiState.Error(failure = failure)
                } else {
                    failedPageOffset = pageOffset
                    pageError = HistoryError.Load(failure)
                    emitReady()
                }
            }
        }
    }

    private fun loadChartStats(generation: Int = selectionGeneration) {
        val exerciseId = selectedId ?: return
        val spec = cachedSpecs.firstOrNull { it.id == exerciseId } ?: return
        viewModelScope.launch {
            try {
                val stats = historyRepository.observeHistoryStats(exerciseId).first()
                val window = historyRepository.observeHistoryWindow(exerciseId, null, null).first()
                if (generation != selectionGeneration) return@launch
                val chart = buildChart(spec.type, exerciseId, window)
                chartState = chart
                statsState = stats
                statsWarning = null
                // Never clobber a blocking page failure that landed first.
                if (stateFlow.value is ExerciseHistoryUiState.Ready) emitReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != selectionGeneration) return@launch
                statsWarning = HistoryError.Stats(
                    (e as? RepositoryException)?.failure
                        ?: RepositoryFailure.Storage("unexpected data error")
                )
                if (stateFlow.value is ExerciseHistoryUiState.Ready) emitReady()
            }
        }
    }

    private fun buildChart(
        type: ExerciseType,
        exerciseId: Long,
        window: List<ExerciseExecutionView>
    ): HistoryChartData? {
        val points = toHistoryPoints(exerciseId, type, window)
        if (points.isEmpty()) return null
        val (drawn, downsampled) = MinMaxDownsampler.downsample(points)
        val (unitPrimary, unitSecondary) = chartUnits(type)
        return HistoryChartData(
            type = type,
            unitPrimary = unitPrimary,
            unitSecondary = unitSecondary,
            points = drawn,
            wasDownsampled = downsampled,
            totalPointCount = points.size
        )
    }

    private fun emitReady() {
        stateFlow.value = ExerciseHistoryUiState.Ready(
            workoutName = cachedWorkout?.name,
            exercises = cachedSpecs,
            selectedExerciseId = selectedId,
            rows = rowsAcc,
            chart = chartState,
            stats = statsState,
            hasMore = hasMoreState,
            isLoadingMore = loadingMore,
            isDownsampled = chartState?.wasDownsampled == true,
            error = pageError ?: statsWarning
        )
    }
}
