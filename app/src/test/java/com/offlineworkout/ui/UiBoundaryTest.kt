package com.offlineworkout.ui

import org.junit.Assert.assertTrue
import org.junit.Test

// T3.1: ui/viewmodel reference no Room, DAO, data.local, AppContainer or Room
// implementation. The composition root (Application) and the data owner
// (AppContainer) are checked separately with their own allowlists.
class UiBoundaryTest {

    private val baseForbidden = listOf(
        "androidx.room",
        "androidx.sqlite",
        "android.database",
        "com.offlineworkout.data.local",
        "RoomWorkoutRepository",
        "RoomExerciseHistoryRepository",
        "RestTimerService",
        "BootCompletedReceiver",
        "SystemClock",
        "SharedPrefsRestTimerStore",
        "SharedPrefsWorkoutSessionStore",
        "AndroidRestTimerController",
        "DefaultWorkoutSessionController",
        "RestTimerEngine",
        // T8.1: the backup repository is a data interface; the Room
        // implementation and the file gateway must stay behind it.
        "RoomBackupRepository",
        "AndroidBackupFileGateway"
    )
    private val uiExtraForbidden = listOf("AppContainer")
    // ViewModels never touch platform Context (no permission/clock access);
    // routes may use LocalContext solely for the notification permission.
    private val viewModelExtraForbidden = listOf("android.content.Context")

    @Test
    fun mainActivity_knows_only_the_interface() {
        assertClean("com.offlineworkout.MainActivity", baseForbidden + uiExtraForbidden)
        // Wiring signature: the issue under test is explicit interface passing.
        val onCreate = Class.forName("com.offlineworkout.MainActivity").declaredMethods
            .first { it.name == "onCreate" }
        assertTrue(onCreate.parameterTypes.any { it.name == "android.os.Bundle" })
    }

    @Test
    fun navHost_receives_the_interface() {
        assertClean("com.offlineworkout.ui.navigation.AppNavHostKt", baseForbidden + uiExtraForbidden)
        val entry = Class.forName("com.offlineworkout.ui.navigation.AppNavHostKt").declaredMethods
            .first { it.name == "AppNavHost" }
        assertTrue(
            "AppNavHost must take WorkoutRepository",
            entry.parameterTypes.any { it.name == "com.offlineworkout.data.repository.WorkoutRepository" }
        )
        assertTrue(
            "AppNavHost must take WorkoutEditorConfig",
            entry.parameterTypes.any { it.name == "com.offlineworkout.data.config.WorkoutEditorConfig" }
        )
        assertTrue(
            "AppNavHost must take RestTimerController",
            entry.parameterTypes.any { it.name == "com.offlineworkout.data.timer.RestTimerController" }
        )
        assertTrue(
            "AppNavHost must take ExerciseHistoryRepository",
            entry.parameterTypes.any { it.name == "com.offlineworkout.data.repository.ExerciseHistoryRepository" }
        )
        assertTrue(
            "AppNavHost must take WorkoutSessionController",
            entry.parameterTypes.any { it.name == "com.offlineworkout.data.session.WorkoutSessionController" }
        )
    }

    @Test
    fun list_route_and_screen_are_clean() {
        assertClean("com.offlineworkout.ui.workouts.WorkoutListRouteKt", baseForbidden + uiExtraForbidden)
        assertClean("com.offlineworkout.ui.workouts.WorkoutListScreenKt", baseForbidden + uiExtraForbidden)
    }

    @Test
    fun destinations_are_clean() {
        assertClean("com.offlineworkout.ui.execution.WorkoutExecutionRouteKt", baseForbidden + uiExtraForbidden)
        assertClean("com.offlineworkout.ui.editor.WorkoutEditorRouteKt", baseForbidden + uiExtraForbidden)
        assertClean("com.offlineworkout.ui.editor.WorkoutEditorScreenKt", baseForbidden + uiExtraForbidden)
        assertClean("com.offlineworkout.ui.history.WorkoutHistoryRouteKt", baseForbidden + uiExtraForbidden)
        assertClean("com.offlineworkout.ui.history.WorkoutHistoryScreenKt", baseForbidden + uiExtraForbidden)
        assertClean("com.offlineworkout.ui.history.HistoryChartKt", baseForbidden + uiExtraForbidden)
    }

    @Test
    fun editor_viewmodel_and_config_are_clean() {
        assertClean("com.offlineworkout.viewmodel.WorkoutEditorViewModel", baseForbidden + uiExtraForbidden)
        // WorkoutEditorConfig is pure data (Int/Boolean only): no forbidden
        // reference is expressible, but the class must exist in data.config.
        val config = Class.forName("com.offlineworkout.data.config.WorkoutEditorConfig")
        assertClean(config.name, baseForbidden + uiExtraForbidden + listOf("androidx.", "android."))
    }

    @Test
    fun execution_is_clean() {
        assertClean("com.offlineworkout.ui.execution.WorkoutExecutionRouteKt", baseForbidden + uiExtraForbidden)
        assertClean("com.offlineworkout.ui.execution.WorkoutExecutionScreenKt", baseForbidden + uiExtraForbidden)
        assertClean(
            "com.offlineworkout.viewmodel.WorkoutExecutionViewModel",
            baseForbidden + uiExtraForbidden + viewModelExtraForbidden
        )
    }

    @Test
    fun list_viewmodel_is_clean() {
        assertClean(
            "com.offlineworkout.viewmodel.WorkoutListViewModel",
            baseForbidden + uiExtraForbidden + viewModelExtraForbidden
        )
    }

    @Test
    fun history_viewmodel_is_clean() {
        assertClean(
            "com.offlineworkout.viewmodel.ExerciseHistoryViewModel",
            baseForbidden + uiExtraForbidden + viewModelExtraForbidden
        )
    }

    @Test
    fun backup_ui_and_viewmodel_are_clean() {
        assertClean("com.offlineworkout.ui.backup.BackupRouteKt", baseForbidden + uiExtraForbidden)
        assertClean("com.offlineworkout.ui.backup.BackupScreenKt", baseForbidden + uiExtraForbidden)
        assertClean(
            "com.offlineworkout.viewmodel.BackupViewModel",
            baseForbidden + uiExtraForbidden + viewModelExtraForbidden
        )
        // The route receives interfaces only.
        val entry = Class.forName("com.offlineworkout.ui.backup.BackupRouteKt").declaredMethods
            .first { it.name == "BackupRoute" }
        assertTrue(
            "BackupRoute must take BackupRepository",
            entry.parameterTypes.any { it.name == "com.offlineworkout.data.repository.BackupRepository" }
        )
        assertTrue(
            "BackupRoute must take BackupFileGateway",
            entry.parameterTypes.any { it.name == "com.offlineworkout.data.backup.BackupFileGateway" }
        )
    }

    @Test
    fun application_owns_container_without_room() {
        // OfflineWorkoutApplication may reference AppContainer (its owned root)
        // but never Room, DAOs, data.local or the database directly.
        assertClean("com.offlineworkout.OfflineWorkoutApplication", baseForbidden)
    }

    private fun assertClean(className: String, forbidden: List<String>) {
        val clazz = Class.forName(className)
        val seen = mutableListOf<String>()
        (clazz.declaredMethods.flatMap { listOf(it.returnType.name) + it.parameterTypes.map { p -> p.name } } +
            clazz.declaredFields.map { it.type.name } +
            clazz.constructors.flatMap { it.parameterTypes.map { p -> p.name } } +
            listOfNotNull(clazz.superclass?.name) +
            clazz.interfaces.map { it.name })
            .forEach { typeName ->
                if (forbidden.any { typeName.contains(it) }) seen.add(typeName)
            }
        assertTrue("$className references forbidden types: $seen", seen.isEmpty())
    }
}
