package com.offlineworkout.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.offlineworkout.R

// CTA dominante: 64dp de altura, largura total quando o chamador aplica
// fillMaxWidth. Fecha R-19: minWidth E minHeight de 48dp garantidos.
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val loadingDescription = stringResource(R.string.common_loading)
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 64.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(24.dp)
                    .semantics { contentDescription = loadingDescription },
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text)
    }
}

// Ação de apoio: tonal secondary, 48dp de altura. Também com minWidth E
// minHeight de 48dp (R-19).
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer
        ),
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
    ) {
        Text(text = text)
    }
}
