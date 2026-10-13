package com.offlineworkout.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// T1.1: child rows of a SERIES execution only. setNumber starts at 1 and is
// unique per execution. Never created for TIMED or DISTANCE_TIME (T2.2 validates).
@Entity(
    tableName = "execution_sets",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseExecutionEntity::class,
            parentColumns = ["id"],
            childColumns = ["executionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // T9.2 (R-1): the single-column executionId index was removed; this
        // composite index already covers it as its leftmost prefix.
        Index(value = ["executionId", "setNumber"], unique = true, name = "index_execution_sets_execution_set")
    ]
)
data class ExecutionSetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val executionId: Long,
    val setNumber: Int,
    val reps: Int,
    val weightKg: Double?
)
