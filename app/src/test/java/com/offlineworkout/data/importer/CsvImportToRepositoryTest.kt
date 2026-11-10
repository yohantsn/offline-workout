package com.offlineworkout.data.importer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.offlineworkout.data.local.AppDatabase
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.repository.RoomWorkoutRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The parser is only useful if its output survives the repository's own
 * validation. This test parses the real spreadsheet and writes it, so a draft
 * that violates the per-type rules (for example a TIMED exercise carrying a set
 * count) fails here instead of in production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CsvImportToRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RoomWorkoutRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = RoomWorkoutRepository(db, clock = { 1000L })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun realFile(): ByteArray =
        javaClass.getResourceAsStream("/importer/aquecimento.csv")!!.readBytes()

    @Test
    fun the_real_export_is_accepted_by_the_repository_unchanged() = runTest {
        val parsed = CsvWorkoutParser.parse(realFile())
        assertTrue(parsed.errors.toString(), parsed.canImport)

        val ids = repo.createWorkoutsWithExercises(parsed.workouts).getOrThrow()
        assertEquals(1, ids.size)

        val workout = repo.observeWorkouts().first().single()
        assertEquals("AQUECIMENTO", workout.code)
        assertEquals("AQUECIMENTO/CARDIO/MOBILIDADE", workout.name)

        val exercises = repo.observeExercises(ids.single()).first()
        assertEquals(listOf(0, 1, 2, 3), exercises.map { it.position })
        assertEquals(
            listOf(
                ExerciseType.TIMED,
                ExerciseType.SERIES,
                ExerciseType.SERIES,
                ExerciseType.SERIES
            ),
            exercises.map { it.type }
        )

        val cardio = exercises.first()
        assertEquals(300L, cardio.targetDurationSeconds)
        assertEquals(null, cardio.targetSets)
        assertEquals(0, cardio.restSeconds)

        exercises.drop(1).forEach { exercise ->
            assertEquals(exercise.name, 2, exercise.targetSets)
            assertEquals(exercise.name, 10, exercise.targetReps)
            assertEquals(exercise.name, 20, exercise.restSeconds)
            assertEquals(exercise.name, null, exercise.targetWeightKg)
        }
    }
}
