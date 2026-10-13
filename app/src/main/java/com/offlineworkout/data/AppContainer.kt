package com.offlineworkout.data

import android.content.Context
import androidx.room.Room
import com.offlineworkout.data.backup.AndroidBackupFileGateway
import com.offlineworkout.data.backup.BackupFileGateway
import com.offlineworkout.data.config.WorkoutEditorConfig
import com.offlineworkout.data.local.AppDatabase
import com.offlineworkout.data.repository.BackupRepository
import com.offlineworkout.data.repository.ExerciseHistoryRepository
import com.offlineworkout.data.repository.RoomBackupRepository
import com.offlineworkout.data.repository.RoomExerciseHistoryRepository
import com.offlineworkout.data.repository.RoomWorkoutRepository
import com.offlineworkout.data.repository.WorkoutRepository
import com.offlineworkout.data.session.DefaultWorkoutSessionController
import com.offlineworkout.data.session.SharedPrefsWorkoutSessionStore
import com.offlineworkout.data.session.WorkoutSessionController
import com.offlineworkout.data.session.WorkoutSessionStore
import com.offlineworkout.data.timer.AndroidRestTimerController
import com.offlineworkout.data.timer.RestAlertPlayer
import com.offlineworkout.data.timer.RestTimerController
import com.offlineworkout.data.timer.RestTimerEngine
import com.offlineworkout.data.timer.RestTimerStore
import com.offlineworkout.data.timer.SharedPrefsRestTimerStore
import com.offlineworkout.data.timer.SystemRestAlertPlayer
import com.offlineworkout.data.timer.SystemTimerClock
import com.offlineworkout.data.timer.TimerClock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

// T3.1 (opção A): único owner process-scoped de AppDatabase e repositórios.
// Somente este container constrói Room.databaseBuilder e conhece os concretes;
// suas propriedades públicas são interfaces. Não é DI global nem service
// locator: a instância pertence à OfflineWorkoutApplication e é entregue por
// parâmetro (MainActivity -> AppNavHost -> factory). Nunca importado por ui/viewmodel.
class AppContainer(context: Context) : Closeable {

    private val database: AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "offline-workout.db"
    ).build()

    val workoutRepository: WorkoutRepository = RoomWorkoutRepository(database)
    val exerciseHistoryRepository: ExerciseHistoryRepository =
        RoomExerciseHistoryRepository(database)

    // T4.1: pure editor configuration (technical defaults, not product choices).
    val workoutEditorConfig: WorkoutEditorConfig =
        WorkoutEditorConfig(defaultRestSeconds = 0, allowEmptyWorkout = true)

    // T5.1: rest timer graph. The store is the single source of truth; the
    // engine reconciles missed timers at startup; the UI only sees the
    // controller interface. The Service pulls the same instances from here.
    val timerClock: TimerClock = SystemTimerClock
    val restAlertPlayer: RestAlertPlayer = SystemRestAlertPlayer(context.applicationContext)
    val restTimerStore: RestTimerStore =
        SharedPrefsRestTimerStore(context.applicationContext, timerClock)
    val restTimerController: RestTimerController =
        AndroidRestTimerController(context.applicationContext, restTimerStore)

    // T8.1: local backup tool. Same ownership pattern: the repository owns
    // Room access, the gateway owns files/intents, the UI sees interfaces.
    private val backupGatewayImpl = AndroidBackupFileGateway(context.applicationContext)
    val backupRepository: BackupRepository = RoomBackupRepository(database)
    val backupFileGateway: BackupFileGateway = backupGatewayImpl

    // T6.1: total session stopwatch (no foreground service). Same ownership
    // pattern: interfaces out, concretes inside.
    val workoutSessionStore: WorkoutSessionStore =
        SharedPrefsWorkoutSessionStore(context.applicationContext)
    private val sessionControllerImpl =
        DefaultWorkoutSessionController(workoutSessionStore, timerClock)
    val workoutSessionController: WorkoutSessionController = sessionControllerImpl

    private val startupTimerEngine = RestTimerEngine(
        store = restTimerStore,
        clock = timerClock,
        player = restAlertPlayer,
        listener = object : RestTimerEngine.Listener {
            override fun onRequestStop() = Unit
        }
    )

    init {
        // Conclude timers that expired while dead (alert once); re-emit live ones.
        startupTimerEngine.restore()
        // T8.3: startup purge of the staged share cache. A share payload that
        // outlived the process is dead weight, and any cleanup that failed
        // earlier is retried here. Idempotent, no database, no scope.
        backupGatewayImpl.clearStagedCacheBlocking()
    }

    private val closed = AtomicBoolean(false)

    // Idempotente: Android pode não chamar onTerminate; chamadas repetidas são no-ops.
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            database.close()
            sessionControllerImpl.close()
        }
    }
}
