package com.offlineworkout.data.local

// T7.1: single-row aggregate for history stats. COUNT always yields a row
// (zero when empty); MIN/MAX are null without executions.
data class ExerciseHistoryStatsRow(
    val count: Int,
    val firstPerformedAt: Long?,
    val lastPerformedAt: Long?
)
