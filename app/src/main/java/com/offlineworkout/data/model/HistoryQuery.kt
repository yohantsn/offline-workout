package com.offlineworkout.data.model

// T7.1: paged history query contract. limit is mandatory; implementations
// normalize it into 1..HISTORY_MAX_ROWS. offset < 0 is a Validation failure.
// from/to are optional wall-clock bounds (inclusive). Pure, no Room imports.
const val HISTORY_MAX_ROWS = 500
const val HISTORY_PAGE_SIZE = 50

data class HistoryQuery(
    val exerciseId: Long,
    val fromWallClockMs: Long? = null,
    val toWallClockMs: Long? = null,
    val limit: Int,
    val offset: Int = 0
)

data class HistoryStats(
    val exerciseId: Long,
    val count: Int,
    val firstPerformedAtWallClockMs: Long?,
    val lastPerformedAtWallClockMs: Long?
)
