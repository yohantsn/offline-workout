package com.offlineworkout.data.local

// Redesign (lista): linha agregada somente leitura dos dois queries
// observeAllAggregated/observeByIdAggregated. POJO, não entidade.
data class WorkoutAggregateRow(
    val id: Long,
    val code: String,
    val name: String,
    val createdAt: Long,
    val exerciseCount: Int,
    val totalRestSeconds: Int
)
