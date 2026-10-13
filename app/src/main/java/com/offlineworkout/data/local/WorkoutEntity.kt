package com.offlineworkout.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// T1.1: a workout (codes A/B/C supported by schema; product content blocked).
// createdAt is epoch milliseconds UTC and must be positive (validated in T2.2 repository).
@Entity(
    tableName = "workouts",
    indices = [Index(value = ["code"], unique = true)]
)
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val code: String,
    val name: String,
    val createdAt: Long
)
