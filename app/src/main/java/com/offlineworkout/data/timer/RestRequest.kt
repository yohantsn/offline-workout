package com.offlineworkout.data.timer

// T5.1: pure start request. restSeconds is the business unit (seconds).
data class RestRequest(
    val workoutId: Long,
    val exerciseId: Long,
    val restSeconds: Int
)
