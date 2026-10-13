package com.offlineworkout.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * T9.2 (R-20): the single source for "this destination cannot be shown".
 *
 * Before this there were five different strings for two conditions spread over
 * five places, and the two blocks that render on an invalid route had no
 * `liveRegion` at all — TalkBack said nothing, because navigating to a screen
 * with no announcement is silent.
 *
 * Two conditions, one text each, one announcement:
 *  - [MissingRecord.INVALID] the id in the route is not usable at all
 *    (non-positive, or an exercise id that does not belong to the workout).
 *  - [MissingRecord.NOT_FOUND] the id is valid but no record exists, or it was
 *    deleted while the screen was open.
 *
 * The block is assertive (an unexpected destination is worth interrupting for),
 * exposes a single 48dp "Voltar para treinos" action as the only focusable
 * element, and never signals the condition with color alone: the text says it.
 */
enum class MissingRecord { WORKOUT, EXERCISE }

@Composable
fun MissingDestinationScreen(
    record: MissingRecord,
    cause: MissingCause,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Assertive }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = missingDestinationText(record, cause),
                style = MaterialTheme.typography.bodyLarge
            )
            Button(
                onClick = onBack,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .heightIn(min = 48.dp)
            ) {
                Text(text = "Voltar para treinos")
            }
        }
    }
}

/** The one place where these words are written. */
fun missingDestinationText(record: MissingRecord, cause: MissingCause): String {
    val subject = when (record) {
        MissingRecord.WORKOUT -> "Treino"
        MissingRecord.EXERCISE -> "Exercício"
    }
    return when (cause) {
        MissingCause.INVALID -> "$subject inválido."
        MissingCause.NOT_FOUND -> "$subject não encontrado."
    }
}

enum class MissingCause { INVALID, NOT_FOUND }
