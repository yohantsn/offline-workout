package com.offlineworkout.ui.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlineworkout.R
import com.offlineworkout.ui.components.CodeChip

// Card da lista: o card INTEIRO executa (contentDescription "Executar {name}"),
// com fundo `surface` da spec (§2.1). Editar/Histórico são clicáveis filhos e
// param a propagação do clique. Minutos ARREDONDADOS (90 s vira "2 min"): a
// linha decide "vale iniciar agora?" e truncar subestima até 59 s.
@Composable
fun WorkoutCard(
    name: String,
    exerciseCount: Int,
    totalRestSeconds: Int,
    code: String? = null,
    onCardClick: () -> Unit,
    onEditClick: () -> Unit,
    onHistoryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val executeDescription = stringResource(R.string.list_execute_cd, name)
    val editDescription = stringResource(R.string.list_edit_cd, name)
    val historyDescription = stringResource(R.string.list_history_cd, name)
    val metaLine = stringResource(
        R.string.list_card_meta,
        pluralStringResource(R.plurals.list_exercise_count, exerciseCount, exerciseCount),
        when {
            totalRestSeconds >= 60 -> stringResource(
                R.string.list_rest_min,
                (totalRestSeconds + 30) / 60
            )
            totalRestSeconds > 0 -> stringResource(R.string.list_rest_seconds, totalRestSeconds)
            else -> stringResource(R.string.list_rest_none)
        }
    )
    Card(
        onClick = onCardClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.semantics {
            contentDescription = executeDescription
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                if (code != null) {
                    CodeChip(code = code)
                }
            }
            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                TextButton(
                    onClick = onEditClick,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.list_edit),
                        modifier = Modifier.semantics {
                            contentDescription = editDescription
                        }
                    )
                }
                TextButton(
                    onClick = onHistoryClick,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.list_history),
                        modifier = Modifier.semantics {
                            contentDescription = historyDescription
                        }
                    )
                }
            }
        }
    }
}
