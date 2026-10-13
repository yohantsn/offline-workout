package com.offlineworkout.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.offlineworkout.R
import com.offlineworkout.ui.theme.LocalSuccessColors

// Indicador de progresso entre exercícios: concluído = success, atual =
// primary largo, pendente = outline. Estado nunca é só cor: a fileira tem
// contentDescription "Exercício X de N".
@Composable
fun StepDots(currentIndex: Int, total: Int, completedCount: Int, modifier: Modifier = Modifier) {
    if (total <= 0) {
        Box(modifier = modifier)
        return
    }
    val description = stringResource(
        R.string.step_dots_description,
        (currentIndex + 1).coerceIn(1, total),
        total
    )
    val scheme = MaterialTheme.colorScheme
    val success = LocalSuccessColors.current.success
    Row(
        modifier = modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(total) { index ->
            val dotColor = when {
                index < completedCount -> success
                index == currentIndex -> scheme.primary
                else -> scheme.outline
            }
            val dotWidth = if (index == currentIndex) 20.dp else 8.dp
            Box(
                modifier = Modifier
                    .size(dotWidth, 8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}
