package com.offlineworkout.data.local

import android.database.sqlite.SQLiteConstraintException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkoutDaoTest : LocalDatabaseTest() {

    @Test
    fun insert_getById_getByCode() = runTest {
        val dao = db.workoutDao()
        val id = dao.insert(WorkoutEntity(code = "A", name = "Treino A", createdAt = 1000L))

        assertEquals(id, dao.getById(id)?.id)
        assertEquals("Treino A", dao.getByCode("A")?.name)
        assertNull(dao.getByCode("B"))
    }

    @Test
    fun codes_A_B_C_coexist() = runTest {
        val dao = db.workoutDao()
        // T9.2 (R-21): the batch insertAll had no production caller and was
        // removed; free codes (D-1) make this the interesting case anyway.
        listOf(
            WorkoutEntity(code = "A", name = "A", createdAt = 1L),
            WorkoutEntity(code = "B", name = "B", createdAt = 2L),
            WorkoutEntity(code = "C", name = "C", createdAt = 3L),
            WorkoutEntity(code = "D", name = "D", createdAt = 4L)
        ).forEach { dao.insert(it) }
        assertEquals(4, dao.observeAll().first().size)
        assertEquals(1, dao.countByCode("B"))
        assertEquals(1, dao.countByCode("D"))
    }

    @Test(expected = SQLiteConstraintException::class)
    fun duplicate_code_fails() = runTest {
        val dao = db.workoutDao()
        dao.insert(WorkoutEntity(code = "A", name = "First", createdAt = 1L))
        dao.insert(WorkoutEntity(code = "A", name = "Second", createdAt = 2L))
    }

    @Test
    fun observeExercises_returns_position_order() = runTest {
        val dao = db.workoutDao()
        val workoutId = insertWorkout()
        insertExercise(workoutId, name = "Second", position = 1)
        insertExercise(workoutId, name = "First", position = 0)

        val names = dao.observeExercises(workoutId).first().map { it.name }
        assertEquals(listOf("First", "Second"), names)
    }

    @Test
    fun observeById_emits_null_after_delete() = runTest {
        val dao = db.workoutDao()
        val id = dao.insert(WorkoutEntity(code = "A", name = "A", createdAt = 1L))
        assertNotNull(dao.observeById(id).first())
        dao.delete(dao.getById(id)!!)
        assertNull(dao.observeById(id).first())
    }
}
