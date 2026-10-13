package com.offlineworkout.data.config

// T4.1: pure editor configuration. No UI, Room, entity or screen dependency.
// Technical values, editable per exercise at runtime: defaultRestSeconds = 0
// means "no rest" (already valid per T1/T2 rules) and is not a product
// recommendation; allowEmptyWorkout = true allows saving an empty draft.
data class WorkoutEditorConfig(
    val defaultRestSeconds: Int,
    val allowEmptyWorkout: Boolean
)
