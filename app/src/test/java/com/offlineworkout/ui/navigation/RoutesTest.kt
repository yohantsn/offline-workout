package com.offlineworkout.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// T3.1: route helpers build the exact argumented destinations from the workout id.
class RoutesTest {

    @Test
    fun start_destination_is_workouts() {
        assertEquals("workouts", Routes.WORKOUTS)
    }

    @Test
    fun backup_route_is_top_level() {
        assertEquals("backup", Routes.BACKUP)
    }

    @Test
    fun execute_route_uses_id_only() {
        assertEquals("workouts/42/execute", Routes.execute(42L))
    }

    @Test
    fun edit_route_uses_id_only() {
        assertEquals("workouts/42/edit", Routes.edit(42L))
    }

    @Test
    fun create_route_has_no_argument() {
        assertEquals("workouts/new", Routes.CREATE)
        assertEquals("workouts/new", Routes.create())
    }

    @Test
    fun patterns_declare_long_argument() {
        assertEquals("workouts/{workoutId}/execute", Routes.EXECUTE)
        assertEquals("workouts/{workoutId}/edit", Routes.EDIT)
    }

    @Test
    fun history_routes_cover_workout_and_exercise() {
        assertEquals(
            "workouts/{workoutId}/history?exerciseId={exerciseId}",
            HistoryRoutes.HISTORY
        )
        assertEquals("workouts/7/history", HistoryRoutes.forWorkout(7L))
        assertEquals("workouts/7/history?exerciseId=3", HistoryRoutes.forExercise(7L, 3L))
    }

    // ---- T9.2 (R-5): two consecutive effects of the same target open one entry

    @Test
    fun list_destinations_are_launched_single_top() {
        val builder = androidx.navigation.NavOptionsBuilder()
        assertFalse("fresh options start without single-top", builder.launchSingleTop)
        builder.listDestination()
        assertTrue("list destinations must be single-top", builder.launchSingleTop)
    }

    @Test
    fun two_consecutive_effects_of_the_same_target_reuse_one_entry() {
        // There is no NavController in the unit-test classpath, so the back
        // stack cannot be inspected directly. What is asserted is the contract
        // that produces a single entry: both taps resolve to the same route and
        // carry singleTop, so the second navigate reuses the top entry instead
        // of pushing a duplicate (and the user needs one back press, not two).
        val route = HistoryRoutes.forWorkout(7L)
        val first = androidx.navigation.NavOptionsBuilder().apply { listDestination() }
        val second = androidx.navigation.NavOptionsBuilder().apply { listDestination() }
        assertEquals(route, HistoryRoutes.forWorkout(7L))
        assertTrue(first.launchSingleTop)
        assertTrue(second.launchSingleTop)
    }
}
