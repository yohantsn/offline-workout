package com.offlineworkout.ui.history

import com.offlineworkout.data.model.HistoryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Pico da métrica principal: função pura do destaque do gráfico.
class HistoryPeakTest {

    private fun point(ts: Long, primary: Double?) =
        HistoryPoint(ts, 1L, 1, primary, null, 10)

    @Test
    fun peak_is_max_primary() {
        val points = listOf(point(1L, 50.0), point(2L, 70.0), point(3L, 60.0))
        assertEquals(70.0, peakPrimaryPoint(points)?.primaryValue)
    }

    @Test
    fun peak_tie_returns_first() {
        val points = listOf(point(1L, 70.0), point(2L, 70.0))
        assertEquals(1L, peakPrimaryPoint(points)?.timestampWallClockMs)
    }

    @Test
    fun peak_null_without_primary_values() {
        assertNull(peakPrimaryPoint(listOf(point(1L, null))))
        assertNull(peakPrimaryPoint(emptyList()))
    }
}
