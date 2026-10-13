package com.offlineworkout.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// Substitui CircularProgressIndicator solto: esqueletos com a ALTURA DO CARD
// real (144 dp, raio large), para não haver salto de layout ao carregar.
// `lines` = quantos cards reservar (lista loading = 2 cards).
@Composable
fun LoadingSkeleton(lines: Int = 2, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(lines.coerceAtLeast(1)) { index ->
            Box(
                modifier = Modifier
                    .testTag("loadingCard$index")
                    .fillMaxWidth()
                    .height(CARD_SKELETON_HEIGHT_DP.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}

// Altura do card real da lista (conteúdo + paddings), para a reserva cobrir.
internal const val CARD_SKELETON_HEIGHT_DP = 144
