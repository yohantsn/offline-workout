package com.offlineworkout.ui.history

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// T9.2 (R-18): the chart used to draw its lines with raw pixel widths, so the
// same "4f" was 4dp on mdpi and about 1.3dp on xxhdpi — the stroke was thinner
// the denser the screen. The thickness is now declared in dp and converted at
// draw time, so this asserts the conversion at more than one density.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryChartStrokeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun pxAt(density: Float, dp: Float): Float = with(Density(density)) { dp.dp.toPx() }

    @Test
    fun stroke_widths_are_declared_in_dp() {
        assertEquals(2f, AXIS_STROKE_DP, 0f)
        assertEquals(4f, SERIES_STROKE_DP, 0f)
        assertEquals(6f, POINT_RADIUS_DP, 0f)
    }

    @Test
    fun strokes_scale_with_density() {
        // mdpi: 1dp = 1px
        assertEquals(2f, pxAt(1f, AXIS_STROKE_DP), 0.001f)
        assertEquals(4f, pxAt(1f, SERIES_STROKE_DP), 0.001f)
        // xhdpi: 1dp = 2px
        assertEquals(4f, pxAt(2f, AXIS_STROKE_DP), 0.001f)
        assertEquals(8f, pxAt(2f, SERIES_STROKE_DP), 0.001f)
        // xxhdpi: 1dp = 3px — the density that used to shrink the line
        assertEquals(6f, pxAt(3f, AXIS_STROKE_DP), 0.001f)
        assertEquals(12f, pxAt(3f, SERIES_STROKE_DP), 0.001f)
    }

    @Test
    fun the_thick_series_line_stays_legible_against_the_axis() {
        // The series line must remain visibly thicker than the axis at every
        // density; that relationship is what raw pixels broke.
        listOf(1f, 2f, 3f).forEach { density ->
            val axis = pxAt(density, AXIS_STROKE_DP)
            val series = pxAt(density, SERIES_STROKE_DP)
            assertEquals(
                "series line must be twice the axis at density $density",
                2f,
                series / axis,
                0.001f
            )
        }
    }
}
