package com.offlineworkout.data.session

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// T6.1: session store round-trips across instances and starts empty.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutSessionStoreTest {

    private fun store() = SharedPrefsWorkoutSessionStore(
        ApplicationProvider.getApplicationContext()
    )

    private fun clear() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences(
                SharedPrefsWorkoutSessionStore.PREFS_NAME,
                android.content.Context.MODE_PRIVATE
            ).edit().clear().commit()
    }

    @Before
    @After
    fun clean() {
        clear()
    }

    @Test
    fun starts_empty() {
        assertNull(store().load())
    }

    @Test
    fun round_trips_record() {
        val record = PersistedWorkoutSession(
            workoutId = 7L,
            status = PersistedSessionStatus.RUNNING,
            accumulatedMs = 12_000L,
            lastResumedAtWallClockMs = 1_000_000L,
            startedAtWallClockMs = 988_000L
        )
        store().save(record)
        assertEquals(record, store().load())
    }

    @Test
    fun absent_marks_read_as_null() {
        store().save(
            PersistedWorkoutSession(
                workoutId = 7L,
                status = PersistedSessionStatus.PAUSED,
                accumulatedMs = 5_000L,
                lastResumedAtWallClockMs = null,
                startedAtWallClockMs = 995_000L
            )
        )
        val loaded = store().load()
        assertNull(loaded?.lastResumedAtWallClockMs)
        assertEquals(5_000L, loaded?.accumulatedMs)
    }
}
