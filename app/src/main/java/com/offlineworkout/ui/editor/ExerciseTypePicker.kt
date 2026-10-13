package com.offlineworkout.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.offlineworkout.R
import com.offlineworkout.data.model.ExerciseType

// Seletor de tipo como lista de cards explicativos (não diálogo de 3
// botões): é a primeira vez que o app diz o que cada tipo significa.
// Renderizado inline na lista (sem dialog), para o toque continuar testável.
@Composable
fun ExerciseTypePicker(
    onPick: (ExerciseType) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.editor_type_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        TypeCard(
            title = stringResource(R.string.editor_type_series),
            description = stringResource(R.string.editor_desc_series),
            onClick = { onPick(ExerciseType.SERIES) }
        )
        TypeCard(
            title = stringResource(R.string.editor_type_timed),
            description = stringResource(R.string.editor_desc_timed),
            onClick = { onPick(ExerciseType.TIMED) }
        )
        TypeCard(
            title = stringResource(R.string.editor_type_distance),
            description = stringResource(R.string.editor_desc_distance),
            onClick = { onPick(ExerciseType.DISTANCE_TIME) }
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        ) {
            Text(text = stringResource(R.string.editor_close))
        }
    }
}

@Composable
private fun TypeCard(title: String, description: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
