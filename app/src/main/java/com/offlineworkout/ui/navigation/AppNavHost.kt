package com.offlineworkout.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.offlineworkout.data.backup.BackupFileGateway
import com.offlineworkout.data.config.WorkoutEditorConfig
import com.offlineworkout.data.repository.BackupRepository
import com.offlineworkout.data.repository.ExerciseHistoryRepository
import com.offlineworkout.data.repository.WorkoutRepository
import com.offlineworkout.data.session.WorkoutSessionController
import com.offlineworkout.data.timer.RestTimerController
import com.offlineworkout.ui.backup.BackupRoute
import com.offlineworkout.ui.editor.WorkoutEditDestination
import com.offlineworkout.ui.editor.WorkoutEditorRoute
import com.offlineworkout.ui.execution.WorkoutExecutionRoute
import com.offlineworkout.ui.history.WorkoutHistoryDestination
import com.offlineworkout.ui.workouts.WorkoutListRoute
import com.offlineworkout.viewmodel.WorkoutEditorMode
import com.offlineworkout.viewmodel.WorkoutListEffect
import com.offlineworkout.viewmodel.WorkoutListViewModel

// T4.1: start em workouts; criação em workouts/new; destinos argumentados por
// Long. Recebe a interface WorkoutRepository e a config pura; não conhece
// persistência, container de aplicação ou implementações concretas.
object Routes {
    const val WORKOUTS = "workouts"
    const val CREATE = "workouts/new"
    const val EXECUTE = "workouts/{workoutId}/execute"
    const val EDIT = "workouts/{workoutId}/edit"

    fun create(): String = CREATE
    fun execute(workoutId: Long): String = "workouts/$workoutId/execute"
    fun edit(workoutId: Long): String = "workouts/$workoutId/edit"

    // T8.1: technical route, no arguments.
    const val BACKUP = "backup"
}

object HistoryRoutes {
    const val HISTORY = "workouts/{workoutId}/history?exerciseId={exerciseId}"

    fun forWorkout(workoutId: Long): String = "workouts/$workoutId/history"
    fun forExercise(workoutId: Long, exerciseId: Long): String =
        "workouts/$workoutId/history?exerciseId=$exerciseId"
}

@Composable
fun AppNavHost(
    workoutRepository: WorkoutRepository,
    historyRepository: ExerciseHistoryRepository,
    editorConfig: WorkoutEditorConfig,
    timerController: RestTimerController,
    sessionController: WorkoutSessionController,
    backupRepository: BackupRepository,
    backupFileGateway: BackupFileGateway
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.WORKOUTS
    ) {
        composable(Routes.WORKOUTS) {
            val factory = remember(workoutRepository) {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return WorkoutListViewModel(workoutRepository) as T
                    }
                }
            }
            val listViewModel: WorkoutListViewModel = viewModel(factory = factory)
            LaunchedEffect(listViewModel) {
                listViewModel.effects.collect { effect ->
                    // T9.2 (R-5): launchSingleTop on every list destination, so
                    // a double tap on the same action cannot stack two entries
                    // of the same screen (two ViewModels, two back presses to
                    // leave). The history destination keeps its T7.3 popUpTo, so
                    // execution -> history -> back still returns to the origin.
                    when (effect) {
                        is WorkoutListEffect.OpenCreate ->
                            navController.navigate(Routes.create()) { listDestination() }

                        is WorkoutListEffect.OpenExecution ->
                            navController.navigate(Routes.execute(effect.workoutId)) {
                                listDestination()
                            }

                        is WorkoutListEffect.OpenEdit ->
                            navController.navigate(Routes.edit(effect.workoutId)) {
                                listDestination()
                            }

                        is WorkoutListEffect.OpenHistory ->
                            navController.navigate(HistoryRoutes.forWorkout(effect.workoutId)) {
                                listDestination()
                            }

                        is WorkoutListEffect.OpenBackup ->
                            navController.navigate(Routes.BACKUP) { listDestination() }
                    }
                }
            }
            WorkoutListRoute(viewModel = listViewModel)
        }
        composable(Routes.CREATE) {
            WorkoutEditorRoute(
                repository = workoutRepository,
                mode = WorkoutEditorMode.Create,
                config = editorConfig,
                onSaved = { navController.popBackStack() },
                onClosed = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.EXECUTE,
            arguments = listOf(navArgument("workoutId") { type = NavType.LongType })
        ) { entry ->
            WorkoutExecutionRoute(
                workoutId = entry.arguments?.getLong("workoutId", -1L) ?: -1L,
                repository = workoutRepository,
                history = historyRepository,
                timer = timerController,
                session = sessionController,
                onBack = { navController.popBackStack() },
                onFinished = { navController.popBackStack() },
                onOpenHistory = { historyWorkoutId, exerciseId ->
                    navController.navigate(HistoryRoutes.forExercise(historyWorkoutId, exerciseId))
                }
            )
        }
        composable(
            route = HistoryRoutes.HISTORY,
            arguments = listOf(
                navArgument("workoutId") { type = NavType.LongType },
                // P0: exerciseId é OPCIONAL. LongType não admite nullable
                // (crash em build), então trafega como String: ausente = tela
                // de seleção; inválido/não-numérico/≤0 = inválido com volta;
                // >0 = aquele exercício. Os três estados vêm do VALOR lido
                // (getString devolve null ausente), nunca de containsKey.
                navArgument("exerciseId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            val args = entry.arguments
            val workoutArg = args?.getLong("workoutId", -1L) ?: -1L
            val rawExercise = args?.getString("exerciseId")
            val parsedExercise = rawExercise?.toLongOrNull()
            val invalid = rawExercise != null && (parsedExercise == null || parsedExercise <= 0)
            WorkoutHistoryDestination(
                workoutId = workoutArg,
                exerciseId = parsedExercise?.takeIf { it > 0 },
                exerciseInvalid = invalid,
                workoutRepository = workoutRepository,
                historyRepository = historyRepository,
                onBack = { navController.popBackStack() },
                onNavigateExercise = { targetWorkoutId, targetExerciseId ->
                    // Replace the current history entry instead of stacking one
                    // ViewModel per visited exercise.
                    navController.navigate(HistoryRoutes.forExercise(targetWorkoutId, targetExerciseId)) {
                        popUpTo(HistoryRoutes.HISTORY) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Routes.BACKUP) {
            BackupRoute(
                repository = backupRepository,
                fileGateway = backupFileGateway,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.EDIT,
            arguments = listOf(navArgument("workoutId") { type = NavType.LongType })
        ) { entry ->
            WorkoutEditDestination(
                workoutId = entry.arguments?.getLong("workoutId", -1L) ?: -1L,
                repository = workoutRepository,
                config = editorConfig,
                onSaved = { navController.popBackStack() },
                onClosed = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/**
 * T9.2 (R-5): the one option every destination opened from the workout list
 * uses. `launchSingleTop` makes a repeated tap on the same action reuse the
 * current entry instead of pushing a second copy, which is what kept the back
 * stack from growing one screen per double tap. It is a function so the option
 * can be asserted in a unit test without a real NavController, and so no call
 * site can forget it.
 */
internal fun NavOptionsBuilder.listDestination() {
    launchSingleTop = true
}
