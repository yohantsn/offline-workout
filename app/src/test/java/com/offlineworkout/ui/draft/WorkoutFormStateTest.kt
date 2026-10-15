package com.offlineworkout.ui.draft

import com.offlineworkout.data.model.ExerciseAggregateDraft
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.WorkoutAggregateDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutFormStateTest {

    private fun exercise(type: ExerciseType): ExerciseFormState =
        ExerciseFormState(
            clientId = "c",
            id = null,
            type = type,
            nameText = "",
            restSecondsText = "",
            targetSetsText = "",
            targetRepsText = "",
            targetWeightKgText = "",
            targetDurationSecondsText = "",
            targetDistanceMetersText = ""
        )

    @Test
    fun emptyState_reports_code_and_name_errors() {
        val state = WorkoutFormState("", "", emptyList())
        val (errors, draft) = state.validate()
        assertNull(draft)
        assertTrue(errors.containsKey(EditorField.WORKOUT_CODE))
        assertTrue(errors.containsKey(EditorField.WORKOUT_NAME))
    }

    @Test
    fun series_valid_builds_draft() {
        val ex = exercise(ExerciseType.SERIES).copy(
            nameText = "Squat", restSecondsText = "90",
            targetSetsText = "3", targetRepsText = "10", targetWeightKgText = "80"
        )
        val state = WorkoutFormState("A", "Treino A", listOf(ex))
        val (errors, draft) = state.validate()
        assertTrue(errors.isEmpty())
        val d = draft!!
        assertEquals(1, d.exercises.size)
        val ed = d.exercises.single() as ExerciseAggregateDraft
        assertEquals(3, ed.targetSets)
        assertEquals(10, ed.targetReps)
        assertEquals(80.0, ed.targetWeightKg!!, 0.0)
    }

    @Test
    fun series_bad_sets_and_reps_report_type_fields() {
        val ex = exercise(ExerciseType.SERIES).copy(
            nameText = "Squat", restSecondsText = "0",
            targetSetsText = "0", targetRepsText = "0"
        )
        val state = WorkoutFormState("A", "Treino A", listOf(ex))
        val (errors, draft) = state.validate()
        assertNull(draft)
        assertTrue(errors.containsKey(EditorField.TYPE_FIELDS))
    }

    @Test
    fun timed_valid_builds_draft() {
        val ex = exercise(ExerciseType.TIMED).copy(
            nameText = "Plank", restSecondsText = "30",
            targetDurationSecondsText = "60"
        )
        val state = WorkoutFormState("B", "Treino B", listOf(ex))
        val (errors, draft) = state.validate()
        assertTrue(errors.isEmpty())
        assertEquals(60L, draft!!.exercises.single().targetDurationSeconds)
    }

    @Test
    fun timed_bad_duration_reports_type_fields() {
        val ex = exercise(ExerciseType.TIMED).copy(
            nameText = "Plank", restSecondsText = "30",
            targetDurationSecondsText = "0"
        )
        val state = WorkoutFormState("B", "Treino B", listOf(ex))
        val (errors, draft) = state.validate()
        assertNull(draft)
        assertTrue(errors.containsKey(EditorField.TYPE_FIELDS))
    }

    @Test
    fun distance_time_valid_builds_draft() {
        val ex = exercise(ExerciseType.DISTANCE_TIME).copy(
            nameText = "Run", restSecondsText = "60",
            targetDurationSecondsText = "300", targetDistanceMetersText = "1000"
        )
        val state = WorkoutFormState("C", "Treino C", listOf(ex))
        val (errors, draft) = state.validate()
        assertTrue(errors.isEmpty())
        val ed = draft!!.exercises.single()
        assertEquals(300L, ed.targetDurationSeconds)
        assertEquals(1000.0, ed.targetDistanceMeters!!, 0.0)
    }

    @Test
    fun distance_time_missing_distance_reports_type_fields() {
        val ex = exercise(ExerciseType.DISTANCE_TIME).copy(
            nameText = "Run", restSecondsText = "60",
            targetDurationSecondsText = "300", targetDistanceMetersText = ""
        )
        val state = WorkoutFormState("C", "Treino C", listOf(ex))
        val (errors, draft) = state.validate()
        assertNull(draft)
        assertTrue(errors.containsKey(EditorField.TYPE_FIELDS))
    }

    @Test
    fun rest_seconds_negative_reports_rest_seconds() {
        val ex = exercise(ExerciseType.TIMED).copy(
            nameText = "Plank", restSecondsText = "-1",
            targetDurationSecondsText = "60"
        )
        val state = WorkoutFormState("D", "Treino D", listOf(ex))
        val (errors, draft) = state.validate()
        assertNull(draft)
        assertTrue(errors.containsKey(EditorField.REST_SECONDS))
    }
}
