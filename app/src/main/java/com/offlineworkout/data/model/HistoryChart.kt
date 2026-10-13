package com.offlineworkout.data.model

// T7.1: presentation models for the evolution chart. No Room, no PR/1RM/RPE,
// no volume/progression math. Pure and unit-testable without Android.
const val MAX_CHART_POINTS = 240
private const val DOWNSAMPLE_BUCKETS = 60

data class HistoryPoint(
    val timestampWallClockMs: Long,
    val exerciseId: Long,
    val setNumber: Int?,
    val primaryValue: Double?,
    val secondaryValue: Double?,
    val reps: Int?
)

data class HistoryChartData(
    val type: ExerciseType,
    val unitPrimary: String,
    val unitSecondary: String?,
    val points: List<HistoryPoint>,
    val wasDownsampled: Boolean,
    // Total points considered before downsampling, so the UI can write
    // "N de M pontos" with N = points.size.
    val totalPointCount: Int
)

// Maps executions to plottable points, chronological ASC:
// SERIES → one point per set with non-null weight (reps stay textual);
// TIMED → one point per execution with duration; DISTANCE_TIME → one point
// per execution with distance (+duration as secondary). Rows without a
// primary metric are skipped here but stay in the table.
fun toHistoryPoints(
    exerciseId: Long,
    type: ExerciseType,
    executions: List<ExerciseExecutionView>
): List<HistoryPoint> {
    val points = when (type) {
        ExerciseType.SERIES -> executions.flatMap { execution ->
            execution.sets
                .filter { it.weightKg != null }
                .map { set ->
                    HistoryPoint(
                        timestampWallClockMs = execution.performedAt,
                        exerciseId = exerciseId,
                        setNumber = set.setNumber,
                        primaryValue = set.weightKg,
                        secondaryValue = null,
                        reps = set.reps
                    )
                }
        }
        ExerciseType.TIMED -> executions.mapNotNull { execution ->
            val duration = execution.durationSeconds ?: return@mapNotNull null
            HistoryPoint(
                timestampWallClockMs = execution.performedAt,
                exerciseId = exerciseId,
                setNumber = null,
                primaryValue = duration.toDouble(),
                secondaryValue = null,
                reps = null
            )
        }
        ExerciseType.DISTANCE_TIME -> executions.mapNotNull { execution ->
            val distance = execution.distanceMeters ?: return@mapNotNull null
            HistoryPoint(
                timestampWallClockMs = execution.performedAt,
                exerciseId = exerciseId,
                setNumber = null,
                primaryValue = distance,
                secondaryValue = execution.durationSeconds?.toDouble(),
                reps = null
            )
        }
    }
    return points.sortedWith(
        compareBy({ it.timestampWallClockMs }, { it.setNumber ?: Int.MAX_VALUE })
    )
}

fun chartUnits(type: ExerciseType): Pair<String, String?> = when (type) {
    ExerciseType.SERIES -> "kg" to null
    ExerciseType.TIMED -> "s" to null
    ExerciseType.DISTANCE_TIME -> "m" to "s"
}

// Deterministic min/max downsampling for the presentation layer only
// (never the table nor the database): sort, split into at most 60 buckets,
// keep first + last + primary min/max per bucket, re-sort. Output is capped
// at 240 points and preserves peaks and temporal order.
object MinMaxDownsampler {

    fun downsample(points: List<HistoryPoint>): Pair<List<HistoryPoint>, Boolean> {
        if (points.size <= MAX_CHART_POINTS) {
            return points.sortedWith(
                compareBy({ it.timestampWallClockMs }, { it.setNumber ?: Int.MAX_VALUE })
            ) to false
        }
        val bucketSize = (points.size + DOWNSAMPLE_BUCKETS - 1) / DOWNSAMPLE_BUCKETS
        val kept = LinkedHashSet<HistoryPoint>()
        points.chunked(bucketSize).forEach { bucket ->
            if (bucket.isEmpty()) return@forEach
            kept.add(bucket.first())
            kept.add(bucket.last())
            bucket.filter { it.primaryValue != null }.minByOrNull { it.primaryValue!! }?.let(kept::add)
            bucket.filter { it.primaryValue != null }.maxByOrNull { it.primaryValue!! }?.let(kept::add)
        }
        val result = kept.sortedWith(
            compareBy({ it.timestampWallClockMs }, { it.setNumber ?: Int.MAX_VALUE })
        )
        return result to true
    }
}
