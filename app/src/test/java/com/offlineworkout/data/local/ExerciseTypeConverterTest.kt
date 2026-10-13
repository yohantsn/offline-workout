package com.offlineworkout.data.local

import com.offlineworkout.data.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseTypeConverterTest {

    private val converter = ExerciseTypeConverter()

    @Test
    fun persists_stable_names() {
        assertEquals("SERIES", converter.fromExerciseType(ExerciseType.SERIES))
        assertEquals("TIMED", converter.fromExerciseType(ExerciseType.TIMED))
        assertEquals("DISTANCE_TIME", converter.fromExerciseType(ExerciseType.DISTANCE_TIME))
    }

    @Test
    fun roundTrips_all_values() {
        ExerciseType.values().forEach { type ->
            assertEquals(type, converter.toExerciseType(converter.fromExerciseType(type)))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknown_value_fails_fast() {
        converter.toExerciseType("CARDIO")
    }
}
