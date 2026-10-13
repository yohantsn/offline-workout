package com.offlineworkout.data.timer

import java.util.concurrent.atomic.AtomicBoolean

// T5.1: timer brain shared by the Service, the boot receiver and app-start
// restore. Owns the in-boot monotonic target, the single-finish guard and the
// alert-once rule; the store stays the source of truth (disk + StateFlow).
// Pure except for the injected seams: fully unit-testable with fakes.
class RestTimerEngine(
    private val store: RestTimerStore,
    private val clock: TimerClock,
    private val player: RestAlertPlayer,
    private val listener: Listener
) {

    interface Listener {
        fun onRequestStop()
    }

    // Monotonic deadline for the current boot; rebuilt from the wall-clock
    // deadline on start/restore (elapsedRealtime resets on reboot).
    private var targetElapsedMs: Long? = null
    private val finishOnce = AtomicBoolean(false)

    fun start(request: RestRequest): RestTimerCommandResult {
        if (request.restSeconds <= 0) return RestTimerCommandResult.Invalid("restSeconds must be > 0")
        if (request.workoutId <= 0) return RestTimerCommandResult.Invalid("workoutId must be > 0")
        if (request.exerciseId <= 0) return RestTimerCommandResult.Invalid("exerciseId must be > 0")
        if (store.state.value?.status is RestTimerStatus.Running) return RestTimerCommandResult.Busy
        val startedWall = clock.wallClockMs()
        val endsWall = startedWall + request.restSeconds * 1000L
        val timerId = "rest-${request.workoutId}-${request.exerciseId}-$startedWall"
        finishOnce.set(false)
        targetElapsedMs = clock.elapsedRealtimeMs() + request.restSeconds * 1000L
        store.start(
            PersistedRestTimer(
                timerId = timerId,
                workoutId = request.workoutId,
                exerciseId = request.exerciseId,
                durationSeconds = request.restSeconds,
                startedAtWallClockMs = startedWall,
                endsAtWallClockMs = endsWall,
                status = PersistedTimerStatus.RUNNING,
                finishedAtWallClockMs = null,
                alertDelivered = false
            )
        )
        return RestTimerCommandResult.Started
    }

    // Called once per second by the Service loop. Never persists the counter.
    fun tick() {
        val snapshot = store.state.value ?: return
        if (snapshot.status !is RestTimerStatus.Running) return
        val target = targetElapsedMs ?: run {
            // Fresh engine over an already-running store: rebuild from wall.
            val rebuilt = clock.elapsedRealtimeMs() +
                maxOf(0L, snapshot.endsAtWallClockMs - clock.wallClockMs())
            targetElapsedMs = rebuilt
            rebuilt
        }
        val remaining = ((target - clock.elapsedRealtimeMs() + 999) / 1000).coerceAtLeast(0).toInt()
        if (remaining <= 0) {
            complete()
        } else {
            store.updateRemaining(remaining)
        }
    }

    // Single Running -> Finished transition with exactly-once alert.
    fun complete() {
        if (!finishOnce.compareAndSet(false, true)) return
        val snapshot = store.state.value ?: return
        if (snapshot.status !is RestTimerStatus.Running) return
        store.finish(clock.wallClockMs())
        if (!snapshot.alertDelivered) {
            player.alert()
            store.markAlertDelivered()
        }
        listener.onRequestStop()
    }

    // Idempotent: unknown ids and non-running states are no-ops.
    fun cancel(timerId: String) {
        val snapshot = store.state.value ?: return
        val runningId = (snapshot.status as? RestTimerStatus.Running)?.timerId
        if (runningId != null && runningId != timerId) return
        store.cancel()
        listener.onRequestStop()
    }

    // Reconciles disk after process death / reboot / app start. Expired
    // Running timers conclude immediately (alert pending); finished-but-
    // undelivered timers alert one last time; delivered timers stay silent.
    fun restore(): RestTimerSnapshot? {
        finishOnce.set(false)
        val snapshot = store.restore() ?: return null
        when (val status = snapshot.status) {
            is RestTimerStatus.Running -> {
                targetElapsedMs = clock.elapsedRealtimeMs() +
                    maxOf(0L, snapshot.endsAtWallClockMs - clock.wallClockMs())
            }
            is RestTimerStatus.Finished -> {
                if (!snapshot.alertDelivered) {
                    player.alert()
                    store.markAlertDelivered()
                    finishOnce.set(true)
                }
            }
            is RestTimerStatus.Idle -> Unit
        }
        return store.state.value
    }
}
