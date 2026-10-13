package com.offlineworkout.data.model

// T2.1: public workout summary for UI. Persistence-independent: no Room/SQL/Android imports.
data class WorkoutSummary(
    val id: Long,
    val code: String,
    val name: String,
    val createdAt: Long,
    val exerciseCount: Int = 0,
    val totalRestSeconds: Int = 0
)
