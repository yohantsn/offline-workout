package com.offlineworkout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.offlineworkout.data.backup.BackupFileGateway
import com.offlineworkout.data.config.WorkoutEditorConfig
import com.offlineworkout.data.repository.BackupRepository
import com.offlineworkout.data.repository.ExerciseHistoryRepository
import com.offlineworkout.data.repository.WorkoutRepository
import com.offlineworkout.data.session.WorkoutSessionController
import com.offlineworkout.data.timer.RestTimerController
import com.offlineworkout.ui.navigation.AppNavHost
import com.offlineworkout.ui.theme.OfflineWorkoutTheme

// T6.1: obtém as interfaces do container process-scoped e as passa ao
// AppNavHost. Não conhece persistência, container ou implementações concretas;
// nunca cria nem fecha o container, UI, ViewModels ou escopos de coroutines.
class MainActivity : ComponentActivity() {

    private val workoutRepository: WorkoutRepository
        get() = (application as OfflineWorkoutApplication).container.workoutRepository

    private val historyRepository: ExerciseHistoryRepository
        get() = (application as OfflineWorkoutApplication).container.exerciseHistoryRepository

    private val editorConfig: WorkoutEditorConfig
        get() = (application as OfflineWorkoutApplication).container.workoutEditorConfig

    private val restTimerController: RestTimerController
        get() = (application as OfflineWorkoutApplication).container.restTimerController

    private val sessionController: WorkoutSessionController
        get() = (application as OfflineWorkoutApplication).container.workoutSessionController

    private val backupRepository: BackupRepository
        get() = (application as OfflineWorkoutApplication).container.backupRepository

    private val backupFileGateway: BackupFileGateway
        get() = (application as OfflineWorkoutApplication).container.backupFileGateway

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = workoutRepository
        val history = historyRepository
        val config = editorConfig
        val timer = restTimerController
        val session = sessionController
        val backup = backupRepository
        val gateway = backupFileGateway
        setContent {
            OfflineWorkoutTheme {
                AppNavHost(
                    workoutRepository = repository,
                    historyRepository = history,
                    editorConfig = config,
                    timerController = timer,
                    sessionController = session,
                    backupRepository = backup,
                    backupFileGateway = gateway
                )
            }
        }
    }
}
