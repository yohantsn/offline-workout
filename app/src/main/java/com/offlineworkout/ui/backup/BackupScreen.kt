package com.offlineworkout.ui.backup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlineworkout.R
import com.offlineworkout.data.repository.BackupFailure
import com.offlineworkout.data.repository.BackupPreview
import com.offlineworkout.ui.components.AppTopBar
import com.offlineworkout.ui.components.ErrorCard
import com.offlineworkout.ui.components.PrimaryButton
import com.offlineworkout.ui.theme.LocalSuccessColors
import com.offlineworkout.viewmodel.BackupOperation
import com.offlineworkout.viewmodel.BackupRecovery
import com.offlineworkout.viewmodel.BackupUiResult
import com.offlineworkout.viewmodel.BackupUiState

// Redesign lote 4: duas cards (exportar/importar), prévia em bottom sheet,
// ErrorCard no erro, linha D-2. Assinatura inalterada (plano §4).
// T8.1/T8.3 mantidos: recuperação por tipo de falha, sem Room/IO aqui.
@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
fun BackupScreen(
    state: BackupUiState,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onImport: () -> Unit,
    onConfirmReplace: () -> Unit,
    onCancelPreview: () -> Unit,
    onRetry: () -> Unit,
    onPickAnotherFile: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.backup_title),
                onBack = onBack
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                when (state) {
                    BackupUiState.Idle -> {
                        item(key = "cards") {
                            ExportImportCards(onExport = onExport, onImport = onImport)
                        }
                    }
                    is BackupUiState.Working -> {
                        item(key = "working") {
                            WorkingBlock(operation = state.operation)
                        }
                    }
                    is BackupUiState.Preview -> {
                        // A prévia vive no sheet; atrás ficam as cards (como no
                        // wireframe, esmaecidas pelo scrim).
                        item(key = "cards") {
                            ExportImportCards(onExport = onExport, onImport = onImport)
                        }
                    }
                    is BackupUiState.Success -> {
                        val result = state.result
                        item(key = "success") {
                            SuccessBlock(result = result, onShare = onShare, onExport = onExport)
                        }
                    }
                    is BackupUiState.Error -> {
                        item(key = "error") {
                            Box(
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Assertive
                                }
                            ) {
                                ErrorBlock(
                                    failure = state.failure,
                                    recovery = state.recovery,
                                    onRetry = onRetry,
                                    onPickAnotherFile = onPickAnotherFile,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
            if (state is BackupUiState.Preview) {
                PreviewSheet(
                    preview = state.preview,
                    fileName = state.fileName,
                    sizeBytes = state.sizeBytes,
                    modifiedAtEpochMs = state.modifiedAtEpochMs,
                    onConfirmReplace = onConfirmReplace,
                    onCancelPreview = onCancelPreview
                )
            }
        }
    }
}

@Composable
private fun ExportImportCards(onExport: () -> Unit, onImport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.backup_export_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    text = stringResource(R.string.backup_export_text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PrimaryButton(
                    text = stringResource(R.string.backup_export_action),
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.backup_import_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.backup_import_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                // B-1: texto em onErrorContainer (não error) sobre o card:
                // error #DC2626 sobre card claro dá 3.69:1 e reprova 4.5:1.
                OutlinedButton(
                    onClick = onImport,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(text = stringResource(R.string.backup_import_action))
                }
            }
        }
        Text(
            text = stringResource(R.string.backup_system_off),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PreviewSheet(
    preview: BackupPreview,
    fileName: String,
    sizeBytes: Long,
    modifiedAtEpochMs: Long?,
    onConfirmReplace: () -> Unit,
    onCancelPreview: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dateText = modifiedAtEpochMs?.let { formatBackupDate(it) }
        ?: stringResource(R.string.backup_date_unknown)
    ModalBottomSheet(
        onDismissRequest = onCancelPreview,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.backup_preview_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
            Surface(
                color = scheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false
                    )
                    Text(
                        text = stringResource(
                            R.string.backup_file_meta,
                            formatBytes(sizeBytes),
                            dateText
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.backup_checksum_label, preview.checksum),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
            // B-7: gap 2 dp + sem padding lateral — o suficiente para
            // "exercícios" caber a 2.0× sem truncar (requisito do projeto);
            // se ainda quebrar, grid 2×2.
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                CountBox(
                    label = pluralStringResource(
                        R.plurals.backup_count_workouts,
                        preview.workoutCount
                    ),
                    value = preview.workoutCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                CountBox(
                    label = pluralStringResource(
                        R.plurals.backup_count_exercises,
                        preview.exerciseCount
                    ),
                    value = preview.exerciseCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                CountBox(
                    label = pluralStringResource(
                        R.plurals.backup_count_sessions,
                        preview.executionCount
                    ),
                    value = preview.executionCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                CountBox(
                    label = pluralStringResource(
                        R.plurals.backup_count_sets,
                        preview.executionSetCount
                    ),
                    value = preview.executionSetCount.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = stringResource(R.string.backup_replace_warning),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.error
            )
            Text(
                text = stringResource(R.string.backup_no_undo),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )
            Button(
                onClick = onConfirmReplace,
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.error,
                    contentColor = scheme.onError
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text(text = stringResource(R.string.backup_replace_all))
            }
            // B-4: a ação segura recua — contornada, nunca preenchida ao lado
            // do destrutivo.
            OutlinedButton(
                onClick = onCancelPreview,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text(text = stringResource(R.string.backup_cancel))
            }
        }
    }
}

// Contagem compacta da prévia (wireframe §3.5: 4 caixas em fileira, número
// tabular). Screen-private: não é componente compartilhado.
// B-7: gap/padding reduzidos para caber a 2.0× sem truncar (requisito do
// projeto); se ainda quebrar, grid 2×2.
@Composable
private fun CountBox(label: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 0.dp, vertical = 10.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFeatureSettings = "tnum"
                ),
                color = scheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SuccessBlock(
    result: BackupUiResult,
    onShare: () -> Unit,
    onExport: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val success = LocalSuccessColors.current
    val shareDescription = stringResource(R.string.backup_share_cd)
    Card(
        colors = CardDefaults.cardColors(containerColor = success.successContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // B-3: bloco de sucesso em successContainer, com ✓ e título em
            // onSuccessContainer (o par success/successContainer reprova 4.5:1
            // como texto). Subtítulo: nome do arquivo (A-6: com maxLines) ou
            // contagens da importação. Tamanho do arquivo indisponível sem
            // mudar BackupUiResult (fora do escopo da revisão).
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.titleLarge,
                    color = success.onSuccessContainer
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = when (result.operation) {
                            BackupOperation.EXPORT -> stringResource(R.string.backup_exported_title)
                            BackupOperation.SHARE -> stringResource(R.string.backup_shared)
                            BackupOperation.IMPORT -> stringResource(R.string.backup_import_done_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = success.onSuccessContainer,
                        modifier = Modifier.semantics { heading() }
                    )
                    val detail = when (result.operation) {
                        BackupOperation.EXPORT, BackupOperation.SHARE -> result.fileName?.let { name ->
                            val size = result.sizeBytes?.let { formatBytes(it) }
                            if (size != null) {
                                stringResource(R.string.backup_file_meta, name, size)
                            } else {
                                name
                            }
                        }
                        BackupOperation.IMPORT -> result.importedCounts?.let {
                            stringResource(
                                R.string.backup_import_counts,
                                it.workoutCount,
                                it.exerciseCount,
                                it.executionCount
                            )
                        }
                    }
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = success.onSuccessContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false
                        )
                    }
                }
            }
            if (
                result.operation == BackupOperation.EXPORT ||
                result.operation == BackupOperation.SHARE
            ) {
                // B-2: Compartilhar mantém o tonal (ação do resultado);
                // Exportar novamente recua para contornado primary.
                Button(
                    onClick = onShare,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.secondaryContainer,
                        contentColor = scheme.onSecondaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.backup_share),
                        modifier = Modifier.semantics {
                            contentDescription = shareDescription
                        }
                    )
                }
            }
            OutlinedButton(
                onClick = onExport,
                // Colateral do B-3: sobre successContainer, o outline neutro
                // some (1.30:1 light, 1.02:1 dark) — borda e texto explícitos
                // em onSuccessContainer (6.77:1 nos dois temas).
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = success.onSuccessContainer
                ),
                border = BorderStroke(1.dp, success.onSuccessContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text(text = stringResource(R.string.backup_export_again))
            }
        }
    }
}

@Composable
private fun ErrorBlock(
    failure: BackupFailure,
    recovery: BackupRecovery,
    onRetry: () -> Unit,
    onPickAnotherFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    // T8.3: a ação oferecida é a que realmente recupera — erro de arquivo
    // rearma o picker, erro de storage tenta de novo. A causa é preservada no
    // message; a reasseguração vai DENTRO do card, acima do botão (B-8: abaixo
    // do botão ela é lida depois do dedo). Storage não afirma: a UI não sabe
    // se a escrita parcial ocorreu. supportingText em vez do slot
    // secondaryLabel: o slot renderiza um OutlinedButton, e a frase não é ação.
    val dataIntact = failure !is BackupFailure.Storage
    ErrorCard(
        message = errorMessage(failure),
        recoveryLabel = if (recovery == BackupRecovery.PICK_ANOTHER_FILE) {
            stringResource(R.string.backup_pick_another)
        } else {
            stringResource(R.string.backup_retry)
        },
        onRecovery = if (recovery == BackupRecovery.PICK_ANOTHER_FILE) {
            onPickAnotherFile
        } else {
            onRetry
        },
        supportingText = if (dataIntact) stringResource(R.string.backup_intact) else null,
        modifier = modifier
    )
}

@Composable
private fun WorkingBlock(operation: BackupOperation) {
    val workingDescription = stringResource(R.string.backup_working_cd)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = workingDescription
                liveRegion = LiveRegionMode.Polite
            }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = when (operation) {
                    BackupOperation.EXPORT -> stringResource(R.string.backup_working_export)
                    BackupOperation.SHARE -> stringResource(R.string.backup_working_share)
                    BackupOperation.IMPORT -> stringResource(R.string.backup_working_import)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

// Redesign (prévia): tamanho legível com locale explícito (o app é pt-BR;
// nunca depende do locale do aparelho para não variar entre testes e uso).
private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val locale = java.util.Locale.forLanguageTag("pt-BR")
    if (bytes < 1024 * 1024) {
        return String.format(locale, "%.1f KB", bytes / 1024.0)
    }
    return String.format(locale, "%.1f MB", bytes / (1024.0 * 1024.0))
}

// Redesign (prévia): data curta determinística (UTC, sem locale).
private fun formatBackupDate(epochMs: Long): String {
    val format = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.ROOT)
    format.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return format.format(java.util.Date(epochMs))
}

@Composable
private fun errorMessage(failure: BackupFailure): String = when (failure) {
    is BackupFailure.Corrupt -> stringResource(R.string.backup_err_corrupt)
    is BackupFailure.UnsupportedVersion -> stringResource(R.string.backup_err_version)
    is BackupFailure.ChecksumMismatch -> stringResource(R.string.backup_err_checksum)
    is BackupFailure.Validation -> stringResource(R.string.backup_err_validation)
    is BackupFailure.ReferentiallyInvalid -> stringResource(R.string.backup_err_referential)
    is BackupFailure.Storage -> stringResource(R.string.backup_err_storage)
}
