package com.offlineworkout.data.timer

// T5.1: pure command outcomes. Only one timer may be active: a second start
// while Running returns Busy without replacing the original.
sealed interface RestTimerCommandResult {
    data object Started : RestTimerCommandResult
    data object Finished : RestTimerCommandResult
    data object Cancelled : RestTimerCommandResult
    data object Busy : RestTimerCommandResult
    data class Invalid(val reason: String) : RestTimerCommandResult
}
