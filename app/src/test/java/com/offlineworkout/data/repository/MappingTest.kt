package com.offlineworkout.data.repository

import com.offlineworkout.data.local.ExerciseEntity
import com.offlineworkout.data.local.ExerciseExecutionEntity
import com.offlineworkout.data.local.ExerciseExecutionWithSets
import com.offlineworkout.data.local.ExecutionSetEntity
import com.offlineworkout.data.local.WorkoutAggregateRow
import com.offlineworkout.data.local.WorkoutEntity
import com.offlineworkout.data.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// T2.1: exercises the real single mapping rules (internal top-level mappers in
// the repository files) without opening a database.
class MappingTest {

    @Test
    fun workout_entity_maps_to_summary() {
        val summary = WorkoutEntity(id = 7L, code = "B", name = "Treino B", createdAt = 123L).toSummary()
        assertEquals(7L, summary.id)
        assertEquals("B", summary.code)
        assertEquals("Treino B", summary.name)
        assertEquals(123L, summary.createdAt)
    }

    @Test
    fun workout_aggregate_row_maps_to_summary_with_counts() {
        val summary = WorkoutAggregateRow(
            id = 7L, code = "B", name = "Treino B", createdAt = 123L,
            exerciseCount = 4, totalRestSeconds = 1920
        ).toSummary()
        assertEquals(7L, summary.id)
        assertEquals(4, summary.exerciseCount)
        assertEquals(1920, summary.totalRestSeconds)
    }

    @Test
    fun exercise_entity_maps_to_spec_preserving_type_and_targets() {
        val spec = ExerciseEntity(
            id = 3L, workoutId = 1L, name = "Squat", type = ExerciseType.SERIES,
            position = 0, restSeconds = 90, targetSets = 5, targetReps = 5,
            targetWeightKg = 80.0, targetDurationSeconds = null, targetDistanceMeters = null
        ).toSpec()
        assertEquals(3L, spec.id)
        assertEquals(1L, spec.workoutId)
        assertEquals(ExerciseType.SERIES, spec.type)
        assertEquals(5, spec.targetSets)
        assertEquals(5, spec.targetReps)
        assertEquals(80.0, spec.targetWeightKg)
        assertNull(spec.targetDurationSeconds)
        assertNull(spec.targetDistanceMeters)
    }

    @Test
    fun execution_relation_maps_to_view_preserving_set_order() {
        val view = ExerciseExecutionWithSets(
            execution = ExerciseExecutionEntity(
                id = 9L, exerciseId = 3L, performedAt = 1000L, completedAt = 1100L,
                durationSeconds = null, distanceMeters = null
            ),
            sets = listOf(
                ExecutionSetEntity(id = 1L, executionId = 9L, setNumber = 1, reps = 10, weightKg = 20.0),
                ExecutionSetEntity(id = 2L, executionId = 9L, setNumber = 2, reps = 8, weightKg = null)
            )
        ).toView()
        assertEquals(9L, view.id)
        assertEquals(3L, view.exerciseId)
        assertEquals(listOf(1, 2), view.sets.map { it.setNumber })
        assertEquals(20.0, view.sets.first().weightKg)
        assertNull(view.durationSeconds)
        assertNull(view.distanceMeters)
    }

    @Test
    fun exercise_type_values_are_stable() {
        assertEquals(
            listOf(ExerciseType.SERIES, ExerciseType.TIMED, ExerciseType.DISTANCE_TIME),
            ExerciseType.values().toList()
        )
    }
}
