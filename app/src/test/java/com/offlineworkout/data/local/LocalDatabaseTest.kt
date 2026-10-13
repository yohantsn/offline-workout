package com.offlineworkout.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.offlineworkout.data.model.ExerciseType
import org.junit.After
import org.junit.Before
import org.robolectric.annotation.Config

// Shared in-memory database harness for T1.2 DAO tests.
// allowMainThreadQueries keeps tests sequential without dispatcher setup;
// each test gets a fresh database.
@Config(sdk = [34])
abstract class LocalDatabaseTest {

    protected lateinit var db: AppDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    protected suspend fun insertWorkout(code: String = "A", name: String = "Workout"): Long =
        db.workoutDao().insert(WorkoutEntity(code = code, name = name, createdAt = 1L))

    protected suspend fun insertExercise(
        workoutId: Long,
        name: String = "Exercise",
        type: ExerciseType = ExerciseType.SERIES,
        position: Int = 0
    ): Long = db.exerciseDao().insert(
        ExerciseEntity(
            workoutId = workoutId,
            name = name,
            type = type,
            position = position,
            restSeconds = 60,
            targetSets = if (type == ExerciseType.SERIES) 3 else null,
            targetReps = if (type == ExerciseType.SERIES) 10 else null,
            targetWeightKg = null,
            targetDurationSeconds = if (type != ExerciseType.SERIES) 60L else null,
            targetDistanceMeters = if (type == ExerciseType.DISTANCE_TIME) 1000.0 else null
        )
    )
}
