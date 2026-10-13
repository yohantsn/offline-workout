package com.offlineworkout.data.timer

// T5.1: pure timer status. No Android, Room or clock imports.
sealed interface RestTimerStatus {
    data object Idle : RestTimerStatus
    data class Running(val timerId: String) : RestTimerStatus
    data class Finished(val timerId: String) : RestTimerStatus
}
