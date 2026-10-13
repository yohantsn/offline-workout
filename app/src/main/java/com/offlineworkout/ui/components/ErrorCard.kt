package com.offlineworkout.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Estado de erro com recuperação: preserva a causa (message, vinda do
// chamador) e oferece 1 ação de recuperação por tipo (R-22) + opcional
// secundária. supportingText vai dentro do card, acima do botão (ex.:
// reasseguração de segurança). Botões em largura total (C-1): todo o resto
// das telas é full-width.
@Composable
fun ErrorCard(
    message: String,
    recoveryLabel: String,
    onRecovery: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    supportingText: String? = null,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.errorContainer),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onErrorContainer
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onErrorContainer
                )
            }
            Button(
                onClick = onRecovery,
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.error,
                    contentColor = scheme.onError
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text(text = recoveryLabel)
            }
            if (secondaryLabel != null && onSecondary != null) {
                OutlinedButton(
                    onClick = onSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(text = secondaryLabel, color = scheme.onErrorContainer)
                }
            }
        }
    }
}
