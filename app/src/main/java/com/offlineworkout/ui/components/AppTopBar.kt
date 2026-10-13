package com.offlineworkout.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.offlineworkout.R

// Barra de topo de todas as telas. O título carrega semantics de cabeçalho;
// voltar é textual ("Voltar", nunca só ícone) e só existe com onBack != null.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier
) {
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() }
            )
        },
        // Altura mínima em vez de travada: cresce com fonte grande/títulos
        // longos em vez de estourar.
        modifier = modifier.heightIn(min = 64.dp),
        navigationIcon = {
            if (onBack != null) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(text = stringResource(R.string.common_back))
                }
            }
        },
        actions = actions
    )
}
