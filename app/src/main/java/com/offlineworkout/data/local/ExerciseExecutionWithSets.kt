package com.offlineworkout.data.local

import androidx.room.Embedded
import androidx.room.Relation

// T1.1: one execution with its sets. For TIMED/DISTANCE_TIME the sets list is
// empty. Set order (setNumber ASC) is enforced by ExerciseExecutionDao, which
// sorts the relation payload after the @Transaction read.
data class ExerciseExecutionWithSets(
    @Embedded val execution: ExerciseExecutionEntity,
    @Relation(parentColumn = "id", entityColumn = "executionId")
    val sets: List<ExecutionSetEntity>
)
