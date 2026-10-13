package com.offlineworkout.data.model

// T2.1: exercise discriminator moved from data/local to the public contract.
// Persisted as stable strings via data/local ExerciseTypeConverter
// (SERIES/TIMED/DISTANCE_TIME). Single definition — no duplicated enum.
enum class ExerciseType {
    SERIES,
    TIMED,
    DISTANCE_TIME
}
