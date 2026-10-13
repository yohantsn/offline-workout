package com.offlineworkout.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class TypeBadgeTone { Series, Time, DistanceTime }

// Badge de tipo de exercício. O texto é obrigatório (cor é só reforço):
// Series = primaryContainer, Time = tertiaryContainer, DistanceTime = secondaryContainer.
@Composable
fun TypeBadge(tone: TypeBadgeTone, label: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val container = when (tone) {
        TypeBadgeTone.Series -> scheme.primaryContainer
        TypeBadgeTone.Time -> scheme.tertiaryContainer
        TypeBadgeTone.DistanceTime -> scheme.secondaryContainer
    }
    val content = when (tone) {
        TypeBadgeTone.Series -> scheme.onPrimaryContainer
        TypeBadgeTone.Time -> scheme.onTertiaryContainer
        TypeBadgeTone.DistanceTime -> scheme.onSecondaryContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = CircleShape,
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
