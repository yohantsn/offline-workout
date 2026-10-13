package com.offlineworkout.data.local

import androidx.room.TypeConverter
import com.offlineworkout.data.model.ExerciseType

// T2.1: converter imports the public enum from data/model (single definition).

// T1.1: persists ExerciseType as its stable enum name, independent of
// declaration order or locale. Unknown persisted values fail fast.
class ExerciseTypeConverter {
    @TypeConverter
    fun fromExerciseType(value: ExerciseType): String = value.name

    @TypeConverter
    fun toExerciseType(value: String): ExerciseType = ExerciseType.valueOf(value)
}
