package com.offlineworkout.data.local

import androidx.room.Embedded
import androidx.room.Relation

// T1.1: exercise with its raw execution history. Consumers needing guaranteed
// history order use ExerciseExecutionDao history queries
// (ORDER BY performedAt DESC, id DESC) — see T1.1 spec.
data class ExerciseWithExecutions(
    @Embedded val exercise: ExerciseEntity,
    @Relation(parentColumn = "id", entityColumn = "exerciseId")
    val executions: List<ExerciseExecutionEntity>
)
