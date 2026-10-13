package com.offlineworkout.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// T7.1: mapping executions to chart points and deterministic min/max
// downsampling. Pure JVM, no Android/Room.
class HistoryChartTest {

    private fun seriesExecution(
        id: Long,
        performedAt: Long,
        sets: List<ExecutionSetView>
    ) = ExerciseExecutionView(
        id = id,
        exerciseId = 9L,
        performedAt = performedAt,
        completedAt = performedAt + 100L,
        durationSeconds = null,
        distanceMeters = null,
        sets = sets
    )

    private fun timedExecution(id: Long, performedAt: Long, duration: Long?) = ExerciseExecutionView(
        id = id,
        exerciseId = 9L,
        performedAt = performedAt,
        completedAt = null,
        durationSeconds = duration,
        distanceMeters = null,
        sets = emptyList()
    )

    private fun distanceExecution(id: Long, performedAt: Long, distance: Double?, duration: Long?) =
        ExerciseExecutionView(
            id = id,
            exerciseId = 9L,
            performedAt = performedAt,
            completedAt = null,
            durationSeconds = duration,
            distanceMeters = distance,
            sets = emptyList()
        )

    @Test
    fun series_maps_weighted_sets_chronologically() {
        val points = toHistoryPoints(
            9L,
            ExerciseType.SERIES,
            listOf(
                seriesExecution(
                    2L, 2000L,
                    listOf(
                        ExecutionSetView(1, 10, 60.0),
                        ExecutionSetView(2, 8, null)
                    )
                ),
                seriesExecution(1L, 1000L, listOf(ExecutionSetView(1, 10, 50.0)))
            )
        )
        // Weightless set excluded; chronological ASC.
        assertEquals(2, points.size)
        assertEquals(1000L, points[0].timestampWallClockMs)
        assertEquals(50.0, points[0].primaryValue)
        assertEquals(1, points[0].setNumber)
        assertEquals(10, points[0].reps)
        assertEquals(2000L, points[1].timestampWallClockMs)
        assertEquals(60.0, points[1].primaryValue)
        assertNull(points[0].secondaryValue)
    }

    @Test
    fun timed_maps_duration_and_skips_null() {
        val points = toHistoryPoints(
            9L,
            ExerciseType.TIMED,
            listOf(
                timedExecution(1L, 1000L, 60L),
                timedExecution(2L, 2000L, null)
            )
        )
        assertEquals(1, points.size)
        assertEquals(60.0, points[0].primaryValue)
        assertNull(points[0].secondaryValue)
        assertNull(points[0].setNumber)
    }

    @Test
    fun distance_maps_both_metrics() {
        val points = toHistoryPoints(
            9L,
            ExerciseType.DISTANCE_TIME,
            listOf(distanceExecution(1L, 1000L, 1000.0, 600L))
        )
        assertEquals(1, points.size)
        assertEquals(1000.0, points[0].primaryValue)
        assertEquals(600.0, points[0].secondaryValue)
    }

    @Test
    fun units_per_type() {
        assertEquals("kg" to null, chartUnits(ExerciseType.SERIES))
        assertEquals("s" to null, chartUnits(ExerciseType.TIMED))
        assertEquals("m" to "s", chartUnits(ExerciseType.DISTANCE_TIME))
    }

    @Test
    fun downsample_passthrough_up_to_limit() {
        val points = (1..240).map {
            HistoryPoint(it.toLong(), 9L, null, it.toDouble(), null, null)
        }
        val (drawn, downsampled) = MinMaxDownsampler.downsample(points)
        assertEquals(240, drawn.size)
        assertEquals(false, downsampled)
        assertEquals((1L..240L).toList(), drawn.map { it.timestampWallClockMs })
    }

    @Test
    fun downsample_empty_and_single() {
        assertEquals(emptyList<HistoryPoint>() to false, MinMaxDownsampler.downsample(emptyList()))
        val single = listOf(HistoryPoint(5L, 9L, 1, 80.0, null, 10))
        val (drawn, downsampled) = MinMaxDownsampler.downsample(single)
        assertEquals(single, drawn)
        assertEquals(false, downsampled)
    }

    @Test
    fun downsample_preserves_peaks_and_order() {
        // 300 ascending points with a spike at index 150.
        val points = (1..300).map { i ->
            HistoryPoint(i.toLong(), 9L, null, if (i == 150) 999.0 else i.toDouble(), null, null)
        }
        val (drawn, downsampled) = MinMaxDownsampler.downsample(points)
        assertEquals(true, downsampled)
        assertTrue(drawn.size <= 240)
        // Peak preserved.
        assertTrue(drawn.any { it.primaryValue == 999.0 })
        // Temporal order stable.
        assertEquals(drawn.map { it.timestampWallClockMs }, drawn.map { it.timestampWallClockMs }.sorted())
        // Endpoints preserved.
        assertEquals(1L, drawn.first().timestampWallClockMs)
        assertEquals(300L, drawn.last().timestampWallClockMs)
    }

    @Test
    fun downsample_two_points_kept() {
        val points = listOf(
            HistoryPoint(1L, 9L, null, 10.0, null, null),
            HistoryPoint(2L, 9L, null, 20.0, null, null)
        )
        val (drawn, downsampled) = MinMaxDownsampler.downsample(points)
        assertEquals(2, drawn.size)
        assertEquals(false, downsampled)
    }
}
