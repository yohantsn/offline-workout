package com.offlineworkout.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.offlineworkout.R

// Banner de descanso do modo performance. Número gigante tabular SEM animação,
// barra linear remaining/total e Cancelar com 48dp.
@Composable
fun RestCountdown(
    remainingSeconds: Int,
    totalSeconds: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val safeRemaining = remainingSeconds.coerceAtLeast(0)
    val progress = if (totalSeconds > 0) {
        (safeRemaining.toFloat() / totalSeconds).coerceIn(0f, 1f)
    } else {
        0f
    }
    val scheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.tertiaryContainer),
        shape = MaterialTheme.shapes.large,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Polite SÓ no status (anuncia a entrada do descanso). Nunca no
            // número: anunciar a cada segundo seria flood de TalkBack.
            Text(
                text = stringResource(R.string.rest_title),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onTertiaryContainer,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Text(
                text = "%d:%02d".format(safeRemaining / 60, safeRemaining % 60),
                style = MaterialTheme.typography.displayLarge,
                color = scheme.onTertiaryContainer
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = scheme.tertiary,
                trackColor = scheme.surface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.common_cancel),
                        color = scheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}
