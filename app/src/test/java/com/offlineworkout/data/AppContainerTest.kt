package com.offlineworkout.data

import androidx.test.core.app.ApplicationProvider
import com.offlineworkout.OfflineWorkoutApplication
import com.offlineworkout.data.model.WorkoutDraft
import com.offlineworkout.data.repository.RepositoryFailure
import com.offlineworkout.data.repository.RoomWorkoutRepository
import com.offlineworkout.data.repository.WorkoutRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// T3.1: AppContainer owns one database/repository per process, exposes only
// interfaces, and closes idempotently. Lives in data: knowing the concrete
// repository here is explicitly allowed.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppContainerTest {

    @Test
    fun exposes_single_repository_instance() {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        try {
            assertSame(container.workoutRepository, container.workoutRepository)
            assertTrue(container.workoutRepository is RoomWorkoutRepository)
            val asInterface: WorkoutRepository = container.workoutRepository
            assertTrue(asInterface is RoomWorkoutRepository)
        } finally {
            container.close()
        }
    }

    @Test
    fun public_surface_exposes_no_database_or_dao() {
        AppContainer::class.java.methods.forEach { method ->
            val types = listOf(method.returnType.name) + method.parameterTypes.map { it.name }
            types.forEach { typeName ->
                assertTrue(
                    "container leaks $typeName via ${method.name}",
                    !typeName.contains("com.offlineworkout.data.local") &&
                        !typeName.contains("androidx.room") &&
                        !typeName.contains("androidx.sqlite")
                )
            }
        }
    }

    @Test
    fun startup_clears_the_staged_backup_share_cache() {
        // B-2: a share payload that outlived the process is dead weight, and a
        // cleanup that failed earlier is retried here. Building the container
        // is the process-scoped startup of this architecture.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val stagedDir = java.io.File(context.cacheDir, "backups").apply { mkdirs() }
        val leftover = java.io.File(stagedDir, "offline-workout-backup-20240101-120000.json")
        leftover.writeText("{\"stale\":true}")
        assertTrue(leftover.exists())

        val container = AppContainer(context)
        try {
            assertTrue("staged share cache was not cleared", !leftover.exists())
            // Idempotent: a second startup with an empty cache is a no-op.
            AppContainer(context).close()
            assertEquals(0, stagedDir.listFiles()!!.size)
        } finally {
            container.close()
        }
    }

    @Test
    fun close_stops_the_session_ticker() {
        // T9.3: the R-12 test only asserted that close() was callable; it would
        // have passed with the ticker alive. The controller now exposes whether
        // its job is running, and this asserts the transition.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = AppContainer(context)
        val controller = container.workoutSessionController
        val observable = controller as com.offlineworkout.data.session.DefaultWorkoutSessionController
        observable.startOrResume(7L)
        // The ticker is running only while the session is Running (R-3).
        assertTrue("a running session must have its ticker", observable.isTickerActive)
        controller.stop()
        controller.close()
        assertTrue("close() must stop the ticker", !observable.isTickerActive)
        // Closing twice stays a no-op.
        container.close()
    }

    @Test
    fun close_is_idempotent() {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        container.close()
        container.close()
    }

    @Test
    fun repository_is_usable_through_the_container() = runTest {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        try {
            val id = container.workoutRepository.createWorkout(WorkoutDraft("A", "Treino A")).getOrThrow()
            val summaries = container.workoutRepository.observeWorkouts()
                .first()
            assertEquals(listOf(id), summaries.map { it.id })
        } finally {
            container.close()
        }
    }

    @Test
    fun exposes_editor_config_defaults() {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        try {
            assertEquals(0, container.workoutEditorConfig.defaultRestSeconds)
            assertEquals(true, container.workoutEditorConfig.allowEmptyWorkout)
        } finally {
            container.close()
        }
    }

    @Test
    fun exposes_timer_graph_without_room_in_ui() {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        try {
            assertTrue(container.restTimerController is com.offlineworkout.data.timer.AndroidRestTimerController)
            assertTrue(container.restTimerController.state === container.restTimerStore.state)
            assertNull(container.restTimerController.state.value)
            assertTrue(container.restAlertPlayer is com.offlineworkout.data.timer.SystemRestAlertPlayer)
        } finally {
            container.close()
        }
    }

    @Test
    fun exposes_history_and_session_interfaces() {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        try {
            assertTrue(
                container.exerciseHistoryRepository is com.offlineworkout.data.repository.RoomExerciseHistoryRepository
            )
            assertTrue(
                container.workoutSessionController is com.offlineworkout.data.session.DefaultWorkoutSessionController
            )
            assertTrue(
                container.workoutSessionController.state.value.status
                    is com.offlineworkout.data.session.WorkoutSessionStatus.Idle
            )
        } finally {
            container.close()
        }
    }

    @Test
    fun application_owns_single_container() {
        val app = ApplicationProvider.getApplicationContext<OfflineWorkoutApplication>()
        assertSame("the container is process-scoped", app.container, app.container)
        // Assert the interface type at compile time instead of an always-true
        // `is` check (which is what the compiler warned about).
        val repository: WorkoutRepository = app.container.workoutRepository
        assertNotNull(repository)
    }
}
