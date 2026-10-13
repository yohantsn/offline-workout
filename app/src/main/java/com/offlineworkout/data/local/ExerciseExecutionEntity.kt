package com.offlineworkout.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// T1.1: one execution event per exercise performance. Times are epoch
// milliseconds UTC. completedAt is null while an execution is in progress.
// durationSeconds/distanceMeters follow the exercise type (T2.2 validates).
@Entity(
    tableName = "exercise_executions",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = ["exerciseId", "performedAt", "id"],
            name = "index_exercise_executions_history",
            // T9.2 (R-1): the single-column exerciseId index was removed; this
            // composite index already covers it as its leftmost prefix.
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC]
        )
    ]
)
data class ExerciseExecutionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val exerciseId: Long,
    val performedAt: Long,
    val completedAt: Long?,
    val durationSeconds: Long?,
    val distanceMeters: Double?
)
