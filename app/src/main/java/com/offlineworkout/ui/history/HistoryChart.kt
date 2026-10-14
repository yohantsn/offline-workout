package com.offlineworkout.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.model.HistoryChartData
import com.offlineworkout.data.model.HistoryPoint
import com.offlineworkout.ui.theme.LocalSuccessColors
import java.text.DateFormat
import java.util.Date

// T7.1: Compose Canvas chart without new dependencies. One line per metric,
// dots per point, axes with min/max captions as Compose text (never tiny
// canvas-drawn text). The textual table beside it always carries exact values.
@Composable
fun HistoryChart(
    data: HistoryChartData,
    modifier: Modifier = Modifier
) {
    val primary = data.points.mapNotNull { it.primaryValue }
    val secondary = data.points.mapNotNull { it.secondaryValue }
    val primaryScale = remember(data) { scaleOf(primary) }
    val secondaryScale = remember(data) { scaleOf(secondary) }
    val minTs = remember(data) { data.points.minOfOrNull { it.timestampWallClockMs } ?: 0L }
    val maxTs = remember(data) { data.points.maxOfOrNull { it.timestampWallClockMs } ?: 0L }
    val colors = MaterialTheme.colorScheme
    val success = LocalSuccessColors.current.success
    val peak = remember(data) { peakPrimaryPoint(data.points) }

    Column(
        modifier = modifier.semantics { contentDescription = chartDescription(data) }
    ) {
        LegendRow(data = data, secondaryScale = secondaryScale)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Labels column stretches with the canvas: no fixed spacer,
                // so large text wraps instead of truncating.
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Column(
                        verticalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Text(
                            text = formatAxisValue(primaryScale.second),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = formatAxisValue(primaryScale.first),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .heightIn(min = 200.dp)
                    ) {
                        val width = size.width
                        val height = size.height
                        // T9.2 (R-18): stroke and radius used to be raw pixels,
                        // which is 2dp on mdpi but 0.67dp on xxhdpi — the line
                        // was half the intended thickness on a high-density
                        // screen. Canvas' DrawScope is a Density, so the
                        // intent is written in dp and converted here.
                        val axisStroke = AXIS_STROKE_DP.dp.toPx()
                        val seriesStroke = SERIES_STROKE_DP.dp.toPx()
                        val pointRadius = POINT_RADIUS_DP.dp.toPx()
                        // Axes.
                        drawLine(
                            color = colors.onSurface,
                            start = Offset(0f, 0f),
                            end = Offset(0f, height),
                            strokeWidth = axisStroke
                        )
                        drawLine(
                            color = colors.onSurface,
                            start = Offset(0f, height),
                            end = Offset(width, height),
                            strokeWidth = axisStroke
                        )
                        fun xOf(timestamp: Long): Float =
                            if (maxTs <= minTs) width / 2f
                            else ((timestamp - minTs).toFloat() / (maxTs - minTs)) * width
                        fun yOf(value: Double, scale: Pair<Double, Double>): Float {
                            val (lo, hi) = scale
                            if (hi <= lo) return height / 2f
                            return height - ((value - lo) / (hi - lo)).toFloat() * height
                        }
                        if (secondary.isNotEmpty()) {
                            val seconds = data.points.filter { it.secondaryValue != null }
                            seconds.zipWithNext { a, b ->
                                drawLine(
                                    color = colors.tertiary,
                                    start = Offset(xOf(a.timestampWallClockMs), yOf(a.secondaryValue!!, secondaryScale)),
                                    end = Offset(xOf(b.timestampWallClockMs), yOf(b.secondaryValue!!, secondaryScale)),
                                    strokeWidth = seriesStroke
                                )
                            }
                            seconds.forEach { point ->
                                drawCircle(
                                    color = colors.tertiary,
                                    radius = pointRadius,
                                    center = Offset(xOf(point.timestampWallClockMs), yOf(point.secondaryValue!!, secondaryScale))
                                )
                            }
                        }
                        val primaries = data.points.filter { it.primaryValue != null }
                        primaries.zipWithNext { a, b ->
                            drawLine(
                                color = colors.primary,
                                start = Offset(xOf(a.timestampWallClockMs), yOf(a.primaryValue!!, primaryScale)),
                                end = Offset(xOf(b.timestampWallClockMs), yOf(b.primaryValue!!, primaryScale)),
                                strokeWidth = seriesStroke
                            )
                        }
                        primaries.forEach { point ->
                            drawCircle(
                                color = colors.primary,
                                radius = pointRadius,
                                center = Offset(xOf(point.timestampWallClockMs), yOf(point.primaryValue!!, primaryScale))
                            )
                        }
                        // Redesign: pico da métrica principal destacado em
                        // success (texto e tabela continuam sendo a leitura
                        // exata — o gráfico nunca é a única forma de ler).
                        if (peak?.primaryValue != null) {
                            drawCircle(
                                color = success,
                                radius = pointRadius * PEAK_RADIUS_MULTIPLIER,
                                center = Offset(
                                    xOf(peak.timestampWallClockMs),
                                    yOf(peak.primaryValue, primaryScale)
                                )
                            )
                        }
                    }
                }
                Text(
                    text = "Eixo: ${formatAxisValue(primaryScale.first)}–${formatAxisValue(primaryScale.second)} ${data.unitPrimary}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                XDateLabels(minTs = minTs, maxTs = maxTs)
            }
        }
        if (data.wasDownsampled) {
            Text(
                text = "Gráfico simplificado para ${data.points.size} de ${data.totalPointCount} pontos",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

// Short system-legible dates under the time axis; single point repeats it.
@Composable
private fun XDateLabels(minTs: Long, maxTs: Long) {
    val midTs = minTs + (maxTs - minTs) / 2
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Text(text = formatHistoryDate(minTs), style = MaterialTheme.typography.labelSmall)
        if (maxTs > minTs) {
            Text(text = formatHistoryDate(midTs), style = MaterialTheme.typography.labelSmall)
            Text(text = formatHistoryDate(maxTs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

fun formatHistoryDate(wallClockMs: Long): String =
    DateFormat.getDateInstance(
        DateFormat.SHORT,
        java.util.Locale.forLanguageTag("pt-BR")
    ).format(Date(wallClockMs))

// Ponto de pico da métrica principal (maior valor; primeiro em empate).
// Puro e unit-testado; o desenho só o realça, a leitura exata está na tabela.
fun peakPrimaryPoint(points: List<HistoryPoint>): HistoryPoint? {
    val max = points.mapNotNull { it.primaryValue }.maxOrNull() ?: return null
    return points.firstOrNull { it.primaryValue == max }
}

// Scale with padding; equal values get a minimal band. Pure and unit-tested.
fun scaleOf(values: List<Double>): Pair<Double, Double> {
    if (values.isEmpty()) return 0.0 to 1.0
    val min = values.minOrNull()!!
    val max = values.maxOrNull()!!
    if (min == max) return (min - 0.5) to (max + 0.5)
    val padding = (max - min) * 0.1
    return (min - padding) to (max + padding)
}

fun formatAxisValue(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

// Stable textual equivalent: type + units + point count. Never promises
// trend, improvement or goal.
fun chartDescription(data: HistoryChartData): String {
    if (data.points.isEmpty()) return "Gráfico sem dados"
    val count = data.points.size
    return when (data.type) {
        ExerciseType.SERIES ->
            "Gráfico de carga em ${data.unitPrimary} com $count pontos"
        ExerciseType.TIMED ->
            "Gráfico de duração em segundos com $count pontos"
        ExerciseType.DISTANCE_TIME ->
            "Gráfico de distância em metros e duração em segundos com $count pontos"
    }
}

@Composable
private fun LegendRow(data: HistoryChartData, secondaryScale: Pair<Double, Double>) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = legendPrimary(data),
            style = MaterialTheme.typography.labelLarge
        )
        data.unitSecondary?.let {
            Text(
                text = "${legendSecondary(data)}: " +
                    "${formatAxisValue(secondaryScale.first)}–${formatAxisValue(secondaryScale.second)}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun legendPrimary(data: HistoryChartData): String = when (data.type) {
    ExerciseType.SERIES -> "Carga (${data.unitPrimary})"
    ExerciseType.TIMED -> "Duração (${data.unitPrimary})"
    ExerciseType.DISTANCE_TIME -> "Distância (${data.unitPrimary})"
}

private fun legendSecondary(data: HistoryChartData): String =
    "Duração (${data.unitSecondary})"

// T9.2 (R-18): chart geometry in dp, converted per density at draw time.
// Internal so the test can assert the values without screen-reading pixels.
internal const val AXIS_STROKE_DP = 2f
internal const val SERIES_STROKE_DP = 4f
internal const val POINT_RADIUS_DP = 6f
internal const val PEAK_RADIUS_MULTIPLIER = 1.5f
