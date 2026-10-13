package com.offlineworkout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.data.repository.RepositoryException
import com.offlineworkout.data.repository.RepositoryFailure
import com.offlineworkout.data.repository.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

// T3.1: única abstração de estado da lista. Depende somente da interface
// WorkoutRepository e de modelos públicos — nunca de persistência concreta.
// Não chama NavController, não cria AppDatabase e não escreve dados.
sealed interface WorkoutListUiState {
    data object Loading : WorkoutListUiState
    data object Empty : WorkoutListUiState
    data class Success(val workouts: List<WorkoutSummary>) : WorkoutListUiState
    data class Error(val failure: RepositoryFailure) : WorkoutListUiState
}

sealed interface WorkoutListEffect {
    data object OpenCreate : WorkoutListEffect
    data class OpenExecution(val workoutId: Long) : WorkoutListEffect
    data class OpenEdit(val workoutId: Long) : WorkoutListEffect
    data class OpenHistory(val workoutId: Long) : WorkoutListEffect
    data object OpenBackup : WorkoutListEffect
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutListViewModel(
    private val repository: WorkoutRepository
) : ViewModel() {

    // Retry recria a coleta sem recriar o ViewModel e sem depender de recomposição.
    private val retryToken = MutableStateFlow(0)

    private val effectsChannel = Channel<WorkoutListEffect>(Channel.BUFFERED)
    val effects: Flow<WorkoutListEffect> = effectsChannel.receiveAsFlow()

    // The token is upstream of catch: an inner failure becomes an Error emission
    // but never kills the token collection, so onRetry always recollects.
    val state: StateFlow<WorkoutListUiState> = retryToken
        .flatMapLatest {
            repository.observeWorkouts()
                .map { list ->
                    if (list.isEmpty()) {
                        WorkoutListUiState.Empty
                    } else {
                        WorkoutListUiState.Success(list)
                    }
                }
                .onStart { emit(WorkoutListUiState.Loading) }
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(WorkoutListUiState.Error(e.toFailure()))
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutListUiState.Loading)

    fun onRetry() {
        retryToken.value += 1
    }

    fun onCreateSelected() {
        effectsChannel.trySend(WorkoutListEffect.OpenCreate)
    }

    fun onBackupSelected() {
        effectsChannel.trySend(WorkoutListEffect.OpenBackup)
    }

    fun onExecutionSelected(workoutId: Long) {
        if (isLoaded(workoutId)) {
            effectsChannel.trySend(WorkoutListEffect.OpenExecution(workoutId))
        }
    }

    fun onEditSelected(workoutId: Long) {
        if (isLoaded(workoutId)) {
            effectsChannel.trySend(WorkoutListEffect.OpenEdit(workoutId))
        }
    }

    fun onHistorySelected(workoutId: Long) {
        if (isLoaded(workoutId)) {
            effectsChannel.trySend(WorkoutListEffect.OpenHistory(workoutId))
        }
    }

    private fun isLoaded(workoutId: Long): Boolean =
        (state.value as? WorkoutListUiState.Success)?.workouts?.any { it.id == workoutId } == true

    // T9.3 (R-6): no ordering here. The repository is the single source and
    // already delivers the list alphabetically by `code`
    // (RoomWorkoutRepository.observeWorkouts); the old A/B/C map was a second,
    // inert ordering rule that only knew three codes.

    private fun Throwable.toFailure(): RepositoryFailure = when (this) {
        is RepositoryException -> failure
        else -> RepositoryFailure.Storage("unexpected data error")
    }
}
