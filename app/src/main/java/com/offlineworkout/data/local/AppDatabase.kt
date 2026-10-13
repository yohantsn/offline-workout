package com.offlineworkout.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

// T1.1: first real schema (version 1). Replaces the T0.2 LocalPersistence
// placeholder. Fresh installs create v1 via Room; the first future change
// must be an explicit Migration(1, 2) — no fallbackToDestructiveMigration.
// Schema JSON is exported to app/schemas (room.schemaLocation KSP arg).
@Database(
    entities = [
        WorkoutEntity::class,
        ExerciseEntity::class,
        ExerciseExecutionEntity::class,
        ExecutionSetEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(ExerciseTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun exerciseExecutionDao(): ExerciseExecutionDao
}
