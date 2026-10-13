package com.offlineworkout.data.timer

import android.os.SystemClock

// T5.1: clock seam. Production reads wall-clock for persistence and the
// monotonic clock for in-boot countdown; tests use a fake. Pure interface,
// implemented once for Android.
interface TimerClock {
    fun wallClockMs(): Long
    fun elapsedRealtimeMs(): Long
}

object SystemTimerClock : TimerClock {
    override fun wallClockMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}
