package com.offlineworkout.data.timer

import android.content.Context
import androidx.core.content.ContextCompat

// T5.1: Android controller. Validates commands, starts/stops the Service and
// exposes the store StateFlow. Never touches Room/DAOs; the Service owns all
// store mutations — the controller only sends intents (single owner).
class AndroidRestTimerController(
    context: Context,
    private val store: RestTimerStore
) : RestTimerController {

    private val appContext = context.applicationContext

    override val state = store.state

    override fun start(request: RestRequest): RestTimerCommandResult {
        if (request.restSeconds <= 0) return RestTimerCommandResult.Invalid("restSeconds must be > 0")
        if (request.workoutId <= 0) return RestTimerCommandResult.Invalid("workoutId must be > 0")
        if (request.exerciseId <= 0) return RestTimerCommandResult.Invalid("exerciseId must be > 0")
        if (store.state.value?.status is RestTimerStatus.Running) return RestTimerCommandResult.Busy
        ContextCompat.startForegroundService(appContext, RestTimerService.startIntent(appContext, request))
        return RestTimerCommandResult.Started
    }

    override fun cancel(timerId: String): RestTimerCommandResult {
        if (store.state.value == null) return RestTimerCommandResult.Cancelled
        appContext.startService(RestTimerService.cancelIntent(appContext, timerId))
        return RestTimerCommandResult.Cancelled
    }
}
