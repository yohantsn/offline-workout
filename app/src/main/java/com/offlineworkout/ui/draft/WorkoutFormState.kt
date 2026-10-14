package com.offlineworkout.ui.draft

import com.offlineworkout.data.model.ExerciseAggregateDraft
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.WorkoutAggregateDraft

enum class EditorField {
    WORKOUT_CODE,
    WORKOUT_NAME,
    EXERCISE_NAME,
    REST_SECONDS,
    TYPE_FIELDS
}

data class ExerciseFormState(
    val clientId: String,
    val id: Long?,
    val type: ExerciseType,
    val nameText: String,
    val restSecondsText: String,
    val targetSetsText: String,
    val targetRepsText: String,
    val targetWeightKgText: String,
    val targetDurationSecondsText: String,
    val targetDistanceMetersText: String
)

data class WorkoutFormState(
    val codeText: String,
    val nameText: String,
    val exercises: List<ExerciseFormState>,
    val fieldErrors: Map<EditorField, String> = emptyMap()
) {
    fun validate(): Pair<Map<EditorField, String>, WorkoutAggregateDraft?> {
        val errors = mutableMapOf<EditorField, String>()
        if (codeText.isBlank()) {
            errors.putIfAbsent(EditorField.WORKOUT_CODE, "Informe o código")
        }
        if (nameText.isBlank()) {
            errors.putIfAbsent(EditorField.WORKOUT_NAME, "Informe o nome")
        }
        val drafts = exercises.map { validateExercise(it, errors) }
        if (errors.isNotEmpty() || drafts.any { it == null }) {
            return errors to null
        }
        return errors to WorkoutAggregateDraft(
            code = codeText,
            name = nameText.trim(),
            exercises = drafts.filterNotNull()
        )
    }

    private fun validateExercise(
        ex: ExerciseFormState,
        errors: MutableMap<EditorField, String>
    ): ExerciseAggregateDraft? {
        var ok = true
        if (ex.nameText.isBlank()) {
            errors.putIfAbsent(EditorField.EXERCISE_NAME, "Informe o nome do exercício")
            ok = false
        }
        val rest = ex.restSecondsText.toIntOrNull()
        if (rest == null || rest < 0) {
            errors.putIfAbsent(EditorField.REST_SECONDS, "Descanso deve ser inteiro >= 0")
            ok = false
        }
        val (targetsOk, sets, reps, weight, duration, distance) = parseTargets(ex, errors)
        if (!targetsOk) ok = false
        if (!ok) return null
        return ExerciseAggregateDraft(
            id = ex.id,
            name = ex.nameText.trim(),
            type = ex.type,
            restSeconds = rest!!,
            targetSets = sets,
            targetReps = reps,
            targetWeightKg = weight,
            targetDurationSeconds = duration,
            targetDistanceMeters = distance
        )
    }

    private data class ParsedTargets(
        val ok: Boolean,
        val sets: Int?,
        val reps: Int?,
        val weightKg: Double?,
        val durationSeconds: Long?,
        val distanceMeters: Double?
    )

    private fun parseTargets(
        ex: ExerciseFormState,
        errors: MutableMap<EditorField, String>
    ): ParsedTargets {
        fun typeError(message: String): ParsedTargets {
            errors.putIfAbsent(EditorField.TYPE_FIELDS, message)
            return ParsedTargets(false, null, null, null, null, null)
        }
        return when (ex.type) {
            ExerciseType.SERIES -> {
                val sets = ex.targetSetsText.toIntOrNull()
                if (sets == null || sets <= 0) return typeError("Séries deve ser inteiro > 0")
                val reps = ex.targetRepsText.toIntOrNull()
                if (reps == null || reps <= 0) return typeError("Repetições deve ser inteiro > 0")
                val weight = if (ex.targetWeightKgText.isBlank()) {
                    null
                } else {
                    ex.targetWeightKgText.toDoubleOrNull()
                        .takeIf { it != null && it >= 0 }
                        ?: return typeError("Peso deve ser decimal >= 0")
                }
                ParsedTargets(true, sets, reps, weight, null, null)
            }
            ExerciseType.TIMED -> {
                val duration = ex.targetDurationSecondsText.toLongOrNull()
                if (duration == null || duration <= 0) return typeError("Duração deve ser inteiro > 0")
                ParsedTargets(true, null, null, null, duration, null)
            }
            ExerciseType.DISTANCE_TIME -> {
                val duration = ex.targetDurationSecondsText.toLongOrNull()
                if (duration == null || duration <= 0) return typeError("Duração deve ser inteiro > 0")
                if (ex.targetDistanceMetersText.isBlank()) return typeError("Informe a distância")
                val distance = ex.targetDistanceMetersText.toDoubleOrNull()
                if (distance == null || distance < 0) return typeError("Distância deve ser decimal >= 0")
                ParsedTargets(true, null, null, null, duration, distance)
            }
        }
    }
}
