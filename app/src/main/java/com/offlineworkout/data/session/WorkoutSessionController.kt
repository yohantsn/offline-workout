package com.offlineworkout.data.session

import kotlinx.coroutines.flow.StateFlow

// T6.1: total-workout stopwatch. Informational only: no foreground service,
// no sound, no session entity written. The controller alone ticks once per
// second; ViewModels never poll.
interface WorkoutSessionController : java.io.Closeable {
    val state: StateFlow<WorkoutSessionState>
    fun startOrResume(workoutId: Long)
    fun pause()
    fun resume()
    fun stop()

    // T9.2 (R-12): closing stops the 1 Hz ticker and releases the owned scope.
    // The composition root calls it from AppContainer.close().
    override fun close()
}
