package com.offlineworkout.ui.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.offlineworkout.data.backup.BackupFileGateway
import com.offlineworkout.data.backup.BackupFileInfo
import com.offlineworkout.data.config.WorkoutEditorConfig
import com.offlineworkout.data.model.DistanceTimeExecutionInput
import com.offlineworkout.data.model.ExerciseExecutionView
import com.offlineworkout.data.model.ExerciseInput
import com.offlineworkout.data.model.ExerciseSpec
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.ExecutionSetView
import com.offlineworkout.data.model.HistoryQuery
import com.offlineworkout.data.model.HistoryStats
import com.offlineworkout.data.model.SeriesExecutionInput
import com.offlineworkout.data.model.TimedExecutionInput
import com.offlineworkout.data.model.WorkoutAggregateDraft
import com.offlineworkout.data.model.WorkoutDraft
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.data.model.WorkoutUpdate
import com.offlineworkout.data.repository.BackupDocument
import com.offlineworkout.data.repository.BackupImportResult
import com.offlineworkout.data.repository.BackupPreview
import com.offlineworkout.data.repository.BackupRepository
import com.offlineworkout.data.repository.ExerciseHistoryRepository
import com.offlineworkout.data.repository.WorkoutRepository
import com.offlineworkout.data.session.WorkoutSessionController
import com.offlineworkout.data.session.WorkoutSessionState
import com.offlineworkout.data.session.WorkoutSessionStatus
import com.offlineworkout.data.timer.RestRequest
import com.offlineworkout.data.timer.RestTimerCommandResult
import com.offlineworkout.data.timer.RestTimerController
import com.offlineworkout.data.timer.RestTimerSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// P0: tocar "Histórico" navegava para workouts/{id}/history SEM a query e o
// destino exigia exerciseId obrigatório — IllegalArgumentException e crash.
// O argumento agora é opcional: ausente = seleção de exercício.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun history_without_query_opens_selection_instead_of_crashing() {
        val(summary, spec) =
            WorkoutSummary(1L, "A", "Alpha", 1L) to ExerciseSpec(
                id = 9L, workoutId = 1L, name = "Squat", type = ExerciseType.SERIES,
                position = 0, restSeconds = 60, targetSets = 3, targetReps = 10,
                targetWeightKg = null, targetDurationSeconds = null, targetDistanceMeters = null
            )
        composeTestRule.setContent {
            AppNavHost(
                workoutRepository = FakeWorkouts(summary, spec),
                historyRepository = EmptyHistory(),
                editorConfig = WorkoutEditorConfig(60, false),
                timerController = IdleTimer(),
                sessionController = IdleSession(),
                backupRepository = NoBackup(),
                backupFileGateway = NoGateway()
            )
        }
        // Lista → "Histórico" do card (rota SEM query — o caminho do crash).
        composeTestRule.onNodeWithContentDescription("Ver histórico de Alpha").performClick()
        // Seleção de exercício: nem crash, nem "inválido".
        composeTestRule.onNodeWithText("Todos").assertExists()
        composeTestRule.onNodeWithContentDescription("Ver histórico de Squat").assertExists()
    }

    private class FakeWorkouts(
        private val summary: WorkoutSummary,
        private val spec: ExerciseSpec
    ) : WorkoutRepository {
        override fun observeWorkouts(): Flow<List<WorkoutSummary>> =
            MutableStateFlow(listOf(summary))

        override fun observeWorkout(id: Long): Flow<WorkoutSummary?> =
            MutableStateFlow(summary.takeIf { it.id == id })

        override fun observeExercises(workoutId: Long): Flow<List<ExerciseSpec>> =
            MutableStateFlow(listOf(spec))

        override suspend fun createWorkout(input: WorkoutDraft): Result<Long> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun updateWorkout(id: Long, input: WorkoutUpdate): Result<Unit> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun deleteWorkout(id: Long): Result<Unit> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun createExercise(input: ExerciseInput): Result<Long> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun updateExercise(id: Long, input: ExerciseInput): Result<Unit> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun deleteExercise(id: Long): Result<Unit> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun reorderExercises(workoutId: Long, orderedExerciseIds: List<Long>): Result<Unit> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun createWorkoutWithExercises(input: WorkoutAggregateDraft): Result<Long> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun updateWorkoutWithExercises(workoutId: Long, input: WorkoutAggregateDraft): Result<Unit> =
            Result.failure(UnsupportedOperationException("must not be called"))
    }

    private class EmptyHistory : ExerciseHistoryRepository {
        override fun observeHistory(exerciseId: Long): Flow<List<ExerciseExecutionView>> = emptyFlow()
        override fun observeLatestExecution(exerciseId: Long): Flow<ExerciseExecutionView?> = emptyFlow()
        override fun observeLatestSet(exerciseId: Long): Flow<ExecutionSetView?> = emptyFlow()
        override suspend fun recordSeriesExecution(input: SeriesExecutionInput): Result<Long> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun recordTimedExecution(input: TimedExecutionInput): Result<Long> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun recordDistanceTimeExecution(input: DistanceTimeExecutionInput): Result<Long> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun deleteExecution(executionId: Long): Result<Unit> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override fun observeHistoryPage(query: HistoryQuery): Flow<List<ExerciseExecutionView>> = emptyFlow()
        override fun observeHistoryWindow(
            exerciseId: Long,
            fromWallClockMs: Long?,
            toWallClockMs: Long?
        ): Flow<List<ExerciseExecutionView>> = emptyFlow()

        override fun observeHistoryStats(exerciseId: Long): Flow<HistoryStats> = emptyFlow()
    }

    private class IdleTimer : RestTimerController {
        override val state: StateFlow<RestTimerSnapshot?> = MutableStateFlow(null)
        override fun start(request: RestRequest): RestTimerCommandResult =
            RestTimerCommandResult.Cancelled

        override fun cancel(timerId: String): RestTimerCommandResult =
            RestTimerCommandResult.Cancelled
    }

    private class IdleSession : WorkoutSessionController {
        override val state: StateFlow<WorkoutSessionState> = MutableStateFlow(
            WorkoutSessionState(WorkoutSessionStatus.Idle, null, 0L, 0L, 0L, 0L)
        )

        override fun startOrResume(workoutId: Long) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    private class NoBackup : BackupRepository {
        override suspend fun createDocument(): Result<BackupDocument> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun preview(bytes: ByteArray): Result<BackupPreview> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun importReplaceAll(bytes: ByteArray): Result<BackupImportResult> =
            Result.failure(UnsupportedOperationException("must not be called"))
    }

    private class NoGateway : BackupFileGateway {
        override suspend fun writeBytes(uriString: String, bytes: ByteArray): Result<Unit> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun readBytes(uriString: String): Result<ByteArray> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun shareDocument(fileName: String, mimeType: String, bytes: ByteArray): Result<Unit> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun clearStagedCache(): Result<Unit> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun purgeExpiredStagedCache(): Result<Unit> =
            Result.failure(UnsupportedOperationException("must not be called"))

        override suspend fun describe(uriString: String): Result<BackupFileInfo> =
            Result.failure(UnsupportedOperationException("must not be called"))
    }
}
