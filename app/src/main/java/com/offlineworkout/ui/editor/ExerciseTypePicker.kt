package com.offlineworkout.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.offlineworkout.R
import com.offlineworkout.data.model.ExerciseType

// Seletor de tipo como lista de cards explicativos (não diálogo de 3
// botões): é a primeira vez que o app diz o que cada tipo significa.
// Só os cards — título e dismiss pertencem ao contêiner (diálogo).
@Composable
fun ExerciseTypePicker(
    onPick: (ExerciseType) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
