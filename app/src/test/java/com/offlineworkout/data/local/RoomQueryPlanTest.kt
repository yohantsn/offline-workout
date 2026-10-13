package com.offlineworkout.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T9.2 (R-1): the three single-column indexes were redundant — each one is the
 * leftmost prefix of a composite index that already exists:
 *   exercises(workoutId, position)            covers exercises(workoutId)
 *   exercise_executions(exerciseId, performedAt DESC, id DESC) covers exerciseId
 *   execution_sets(executionId, setNumber)    covers executionId
 *
 * This test prints `EXPLAIN QUERY PLAN` for the queries of the three DAOs so the
 * claim is evidence, not assertion-by-comment, and it asserts that the planner
 * keeps using an index (never a full scan) after the change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomQueryPlanTest {

    private lateinit var db: AppDatabase

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

    private fun plansFor(query: String, vararg args: Any): List<String> {
        val cursor = db.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN $query",
            args
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(c.getString(c.getColumnIndexOrThrow("detail")))
                }
            }
        }
    }

    @Test
    fun prints_query_plans_and_asserts_indexed_access() {
        val queries = listOf(
            "SELECT * FROM workouts ORDER BY code ASC, id ASC" to emptyArray(),
            "SELECT * FROM workouts WHERE code = ?" to arrayOf<Any>("A"),
            "SELECT * FROM exercises WHERE workoutId = ? ORDER BY position ASC" to arrayOf<Any>(1L),
            "SELECT * FROM exercises ORDER BY workoutId ASC, position ASC, id ASC" to emptyArray(),
            "SELECT * FROM exercise_executions WHERE exerciseId = ? ORDER BY performedAt DESC, id DESC" to
                arrayOf<Any>(1L),
            "SELECT * FROM exercise_executions ORDER BY exerciseId ASC, performedAt DESC, id DESC" to
                emptyArray(),
            "SELECT * FROM execution_sets WHERE executionId = ? ORDER BY setNumber ASC" to
                arrayOf<Any>(1L),
            "SELECT * FROM execution_sets ORDER BY executionId ASC, setNumber ASC, id ASC" to
                emptyArray()
        )
        val lines = mutableListOf<String>()
        queries.forEach { (query, args) ->
            val plan = plansFor(query, *args)
            plan.forEach { lines.add("QUERY PLAN: $query -> $it") }
            assertTrue(
                "query must not degrade to a full scan: $query -> $plan",
                plan.none { it.contains("SCAN") && !it.contains("USING") }
            )
        }
        lines.forEach { println("R1 $it") }
    }
}
