package com.offlineworkout.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.offlineworkout.R
import com.offlineworkout.ui.components.TypeBadge
import com.offlineworkout.ui.components.TypeBadgeTone

// Linha-resumo do exercício colapsado: o card inteiro expande (onExpand); os
// botões de mover são clicáveis filhos e param a propagação. 48 dp nos dois
// eixos (R-19). O movimento mora aqui — o ExerciseEditor recebe os params por
// contrato mas não os duplica, para não haver duas ações iguais visíveis.
@Composable
fun ExerciseSummaryRow(
    name: String,
    summary: String,
    tone: TypeBadgeTone,
    onExpand: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    modifier: Modifier = Modifier
) {
    val upDescription = stringResource(R.string.editor_move_up_cd, name)
    val downDescription = stringResource(R.string.editor_move_down_cd, name)
    Card(
        onClick = onExpand,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = upDescription }
                ) {
                    Text(text = stringResource(R.string.editor_move_up))
                }
                TextButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = downDescription }
                ) {
                    Text(text = stringResource(R.string.editor_move_down))
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TypeBadge(
                tone = tone,
                label = when (tone) {
                    TypeBadgeTone.Series -> stringResource(R.string.editor_type_series)
                    TypeBadgeTone.Time -> stringResource(R.string.editor_type_timed)
                    TypeBadgeTone.DistanceTime -> stringResource(R.string.editor_type_distance)
                }
            )
        }
    }
}
