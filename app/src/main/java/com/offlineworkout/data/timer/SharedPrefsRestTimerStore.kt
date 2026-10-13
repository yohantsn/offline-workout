package com.offlineworkout.data.timer

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// T5.1: SharedPreferences-backed store (no new dependency). Remaining seconds
// are derived at emission time and never written to disk.
class SharedPrefsRestTimerStore(
    context: Context,
    private val clock: TimerClock
) : RestTimerStore {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    private val mutableState = MutableStateFlow(loadSnapshot())
    override val state: StateFlow<RestTimerSnapshot?> = mutableState.asStateFlow()

    override fun start(record: PersistedRestTimer) {
        prefs.edit {
            putString(KEY_TIMER_ID, record.timerId)
            putLong(KEY_WORKOUT_ID, record.workoutId)
            putLong(KEY_EXERCISE_ID, record.exerciseId)
            putInt(KEY_DURATION_S, record.durationSeconds)
            putLong(KEY_STARTED_WALL, record.startedAtWallClockMs)
            putLong(KEY_ENDS_WALL, record.endsAtWallClockMs)
            putString(KEY_STATUS, PersistedTimerStatus.RUNNING.name)
            putBoolean(KEY_ALERT_DELIVERED, false)
            remove(KEY_FINISHED_WALL)
        }
        mutableState.value = runningSnapshot(record, clock.wallClockMs())
    }

    override fun updateRemaining(remainingSeconds: Int) {
        val current = mutableState.value
        if (current?.status is RestTimerStatus.Running) {
            mutableState.value = current.copy(remainingSeconds = remainingSeconds.coerceAtLeast(0))
        }
    }

    override fun finish(finishedAtWallClockMs: Long) {
        val record = loadPersisted() ?: return
        prefs.edit {
            putString(KEY_STATUS, PersistedTimerStatus.FINISHED.name)
            putLong(KEY_FINISHED_WALL, finishedAtWallClockMs)
        }
        mutableState.value = RestTimerSnapshot(
            status = RestTimerStatus.Finished(record.timerId),
            workoutId = record.workoutId,
            exerciseId = record.exerciseId,
            durationSeconds = record.durationSeconds,
            remainingSeconds = 0,
            startedAtWallClockMs = record.startedAtWallClockMs,
            endsAtWallClockMs = record.endsAtWallClockMs,
            alertDelivered = record.alertDelivered
        )
    }

    override fun markAlertDelivered() {
        val record = loadPersisted() ?: return
        prefs.edit { putBoolean(KEY_ALERT_DELIVERED, true) }
        mutableState.value?.let { mutableState.value = it.copy(alertDelivered = true) }
    }

    override fun cancel() {
        prefs.edit { clear() }
        mutableState.value = null
    }

    override fun loadPersisted(): PersistedRestTimer? {
        val timerId = prefs.getString(KEY_TIMER_ID, null) ?: return null
        val status = prefs.getString(KEY_STATUS, null)?.let { name ->
            PersistedTimerStatus.values().firstOrNull { it.name == name }
        } ?: return null
        return PersistedRestTimer(
            timerId = timerId,
            workoutId = prefs.getLong(KEY_WORKOUT_ID, -1L),
            exerciseId = prefs.getLong(KEY_EXERCISE_ID, -1L),
            durationSeconds = prefs.getInt(KEY_DURATION_S, 0),
            startedAtWallClockMs = prefs.getLong(KEY_STARTED_WALL, 0L),
            endsAtWallClockMs = prefs.getLong(KEY_ENDS_WALL, 0L),
            status = status,
            finishedAtWallClockMs =
                if (prefs.contains(KEY_FINISHED_WALL)) prefs.getLong(KEY_FINISHED_WALL, 0L) else null,
            alertDelivered = prefs.getBoolean(KEY_ALERT_DELIVERED, false)
        )
    }

    override fun restore(): RestTimerSnapshot? {
        val record = loadPersisted() ?: run {
            mutableState.value = null
            return null
        }
        val now = clock.wallClockMs()
        return when (record.status) {
            PersistedTimerStatus.FINISHED -> {
                mutableState.value = finishedSnapshot(record)
                mutableState.value
            }
            PersistedTimerStatus.RUNNING -> {
                if (now >= record.endsAtWallClockMs) {
                    // Expired while dead: conclude immediately, alert pending.
                    finish(now)
                    mutableState.value
                } else {
                    mutableState.value = runningSnapshot(record, now)
                    mutableState.value
                }
            }
        }
    }

    private fun loadSnapshot(): RestTimerSnapshot? {
        val record = loadPersisted() ?: return null
        return when (record.status) {
            PersistedTimerStatus.FINISHED -> finishedSnapshot(record)
            PersistedTimerStatus.RUNNING ->
                runningSnapshot(record, clock.wallClockMs())
        }
    }

    private fun runningSnapshot(record: PersistedRestTimer, nowWallMs: Long) = RestTimerSnapshot(
        status = RestTimerStatus.Running(record.timerId),
        workoutId = record.workoutId,
        exerciseId = record.exerciseId,
        durationSeconds = record.durationSeconds,
        remainingSeconds = remainingSeconds(record.endsAtWallClockMs, nowWallMs),
        startedAtWallClockMs = record.startedAtWallClockMs,
        endsAtWallClockMs = record.endsAtWallClockMs,
        alertDelivered = record.alertDelivered
    )

    private fun finishedSnapshot(record: PersistedRestTimer) = RestTimerSnapshot(
        status = RestTimerStatus.Finished(record.timerId),
        workoutId = record.workoutId,
        exerciseId = record.exerciseId,
        durationSeconds = record.durationSeconds,
        remainingSeconds = 0,
        startedAtWallClockMs = record.startedAtWallClockMs,
        endsAtWallClockMs = record.endsAtWallClockMs,
        alertDelivered = record.alertDelivered
    )

    companion object {
        const val PREFS_NAME = "rest_timer_store"
        const val KEY_TIMER_ID = "timer_id"
        const val KEY_WORKOUT_ID = "workout_id"
        const val KEY_EXERCISE_ID = "exercise_id"
        const val KEY_DURATION_S = "duration_s"
        const val KEY_STARTED_WALL = "started_wall"
        const val KEY_ENDS_WALL = "ends_wall"
        const val KEY_STATUS = "status"
        const val KEY_FINISHED_WALL = "finished_wall"
        const val KEY_ALERT_DELIVERED = "alert_delivered"
    }
}
