package com.offlineworkout.data.timer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.offlineworkout.OfflineWorkoutApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// T5.1: exclusive owner of the running rest timer (ForegroundService,
// specialUse/workout_rest_timer). Ticks once per second on its own scope;
// ViewModels only send intents and observe the store StateFlow.
// START_NOT_STICKY: no automatic restart; store + boot receiver own restore.
// onTaskRemoved never cancels: swiping the task keeps the alert.
class RestTimerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    private lateinit var store: RestTimerStore
    private lateinit var engine: RestTimerEngine

    override fun onCreate() {
        super.onCreate()
        val container = (application as OfflineWorkoutApplication).container
        store = container.restTimerStore
        engine = RestTimerEngine(
            store = store,
            clock = container.timerClock,
            player = container.restAlertPlayer,
            listener = object : RestTimerEngine.Listener {
                override fun onRequestStop() {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        )
        RestTimerNotifications.ensureChannel(this)
        engine.restore()
        if (store.state.value?.status is RestTimerStatus.Running) {
            startForegroundWith(store.state.value!!)
            startTickLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val request = parseStart(intent) ?: run {
                    stopIfIdle()
                    return START_NOT_STICKY
                }
                when (engine.start(request)) {
                    is RestTimerCommandResult.Started -> {
                        startForegroundWith(store.state.value!!)
                        startTickLoop()
                    }
                    else -> stopIfIdle()
                }
            }
            ACTION_CANCEL -> {
                engine.cancel(intent.getStringExtra(EXTRA_TIMER_ID) ?: "")
                stopIfIdle()
            }
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tickJob?.cancel()
        tickJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun parseStart(intent: Intent): RestRequest? {
        val workoutId = intent.getLongExtra(EXTRA_WORKOUT_ID, -1L)
        val exerciseId = intent.getLongExtra(EXTRA_EXERCISE_ID, -1L)
        val restSeconds = intent.getIntExtra(EXTRA_REST_SECONDS, 0)
        if (workoutId <= 0 || exerciseId <= 0 || restSeconds <= 0) return null
        return RestRequest(workoutId, exerciseId, restSeconds)
    }

    private fun startForegroundWith(snapshot: RestTimerSnapshot) {
        val notification = RestTimerNotifications.runningNotification(this, snapshot)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                RestTimerNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                RestTimerNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            )
        } else {
            startForeground(RestTimerNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun startTickLoop() {
        if (tickJob?.isActive == true) return
        tickJob = serviceScope.launch {
            while (isActive) {
                delay(TICK_MS)
                engine.tick()
                val snapshot = store.state.value
                if (snapshot?.status is RestTimerStatus.Running) {
                    notifyManager().notify(
                        RestTimerNotifications.NOTIFICATION_ID,
                        RestTimerNotifications.runningNotification(this@RestTimerService, snapshot)
                    )
                } else {
                    break
                }
            }
        }
    }

    /**
     * T9.2 (R-4): the guard used to be "there is no snapshot at all", which is
     * almost never true after `engine.restore()` — so the service stayed alive
     * without ever calling `startForeground`, and the system killed the process
     * with `ForegroundServiceDidNotStartInTimeException`. The correct condition
     * is "the timer is not running": a null snapshot is not running either, and
     * an idle/finished/cancelled one must release the foreground state.
     */
    private fun stopIfIdle() {
        if (store.state.value?.status !is RestTimerStatus.Running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notifyManager() =
        getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager

    companion object {
        const val ACTION_START = "com.offlineworkout.data.timer.action.START_REST"
        const val ACTION_CANCEL = "com.offlineworkout.data.timer.action.CANCEL_REST"
        const val EXTRA_WORKOUT_ID = "extra_workout_id"
        const val EXTRA_EXERCISE_ID = "extra_exercise_id"
        const val EXTRA_REST_SECONDS = "extra_rest_seconds"
        const val EXTRA_TIMER_ID = "extra_timer_id"
        const val TICK_MS = 1000L

        fun startIntent(context: Context, request: RestRequest): Intent =
            Intent(context, RestTimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WORKOUT_ID, request.workoutId)
                putExtra(EXTRA_EXERCISE_ID, request.exerciseId)
                putExtra(EXTRA_REST_SECONDS, request.restSeconds)
            }

        fun cancelIntent(context: Context, timerId: String): Intent =
            Intent(context, RestTimerService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TIMER_ID, timerId)
            }
    }
}
