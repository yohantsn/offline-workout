package com.offlineworkout.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offlineworkout.ui.theme.LocalSuccessColors

enum class StatTileEmphasis { Neutral, Up }

// Tile de estatística do histórico. Valor em numeral tabular displaySmall;
// ênfase Up pinta o valor de success (sempre acompanhado do rótulo textual).
@Composable
fun StatTile(
    label: String,
    value: String,
    unit: String? = null,
    emphasis: StatTileEmphasis = StatTileEmphasis.Neutral,
    modifier: Modifier = Modifier
) {
    val successColors = LocalSuccessColors.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.displaySmall,
                    color = if (emphasis == StatTileEmphasis.Up) {
                        successColors.success
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (unit != null) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                    )
                }
            }
        }
    }
}
