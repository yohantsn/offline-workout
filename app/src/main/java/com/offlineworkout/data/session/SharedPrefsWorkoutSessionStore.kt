package com.offlineworkout.data.session

import android.content.Context
import androidx.core.content.edit

// T6.1: SharedPreferences-backed session store (no new dependency).
class SharedPrefsWorkoutSessionStore(context: Context) : WorkoutSessionStore {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    override fun load(): PersistedWorkoutSession? {
        val workoutId = prefs.getLong(KEY_WORKOUT_ID, -1L)
        if (workoutId < 0) return null
        val status = prefs.getString(KEY_STATUS, null)?.let { name ->
            PersistedSessionStatus.values().firstOrNull { it.name == name }
        } ?: return null
        return PersistedWorkoutSession(
            workoutId = workoutId,
            status = status,
            accumulatedMs = prefs.getLong(KEY_ACCUMULATED_MS, 0L),
            lastResumedAtWallClockMs =
                if (prefs.contains(KEY_LAST_RESUMED_WALL)) prefs.getLong(KEY_LAST_RESUMED_WALL, 0L) else null,
            startedAtWallClockMs =
                if (prefs.contains(KEY_STARTED_WALL)) prefs.getLong(KEY_STARTED_WALL, 0L) else null
        )
    }

    override fun save(record: PersistedWorkoutSession) {
        prefs.edit {
            putLong(KEY_WORKOUT_ID, record.workoutId)
            putString(KEY_STATUS, record.status.name)
            putLong(KEY_ACCUMULATED_MS, record.accumulatedMs)
            if (record.lastResumedAtWallClockMs != null) {
                putLong(KEY_LAST_RESUMED_WALL, record.lastResumedAtWallClockMs)
            } else {
                remove(KEY_LAST_RESUMED_WALL)
            }
            if (record.startedAtWallClockMs != null) {
                putLong(KEY_STARTED_WALL, record.startedAtWallClockMs)
            } else {
                remove(KEY_STARTED_WALL)
            }
        }
    }

    companion object {
        const val PREFS_NAME = "workout_session"
        const val KEY_WORKOUT_ID = "workout_id"
        const val KEY_STATUS = "status"
        const val KEY_ACCUMULATED_MS = "accumulated_ms"
        const val KEY_LAST_RESUMED_WALL = "last_resumed_wall"
        const val KEY_STARTED_WALL = "started_wall"
    }
}
