package com.offlineworkout.data.timer

import kotlinx.coroutines.flow.StateFlow

// T5.1: interface consumed by the ViewModel. Implementations start/stop the
// Service; the snapshot StateFlow is the only tick channel (no polling,
// no per-second broadcasts). Null state means no active or finished timer.
interface RestTimerController {
    val state: StateFlow<RestTimerSnapshot?>
    fun start(request: RestRequest): RestTimerCommandResult
    fun cancel(timerId: String): RestTimerCommandResult
}
