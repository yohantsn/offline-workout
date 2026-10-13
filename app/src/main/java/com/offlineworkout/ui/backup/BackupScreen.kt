package com.offlineworkout.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.offlineworkout.data.repository.BackupFailure
import com.offlineworkout.viewmodel.BackupOperation
import com.offlineworkout.viewmodel.BackupRecovery
import com.offlineworkout.viewmodel.BackupUiState

// T8.1: stateless backup screen. Export/share/import with mandatory preview
// and an explicit destructive confirmation dialog. No Room, no file IO here.
@Composable
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
    var showDestructiveDialog by remember { mutableStateOf(false) }
    if (showDestructiveDialog) {
        val preview = (state as? BackupUiState.Preview)?.preview
        AlertDialog(
            onDismissRequest = { showDestructiveDialog = false },
            title = { Text(text = "Substituir todos os dados locais?") },
            text = {
                Text(
                    text = "Isto substituirá todos os dados locais pelos " +
                        "${preview?.workoutCount ?: 0} treinos, " +
                        "${preview?.exerciseCount ?: 0} exercícios, " +
                        "${preview?.executionCount ?: 0} execuções do arquivo. " +
                        "Esta ação não pode ser desfeita."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDestructiveDialog = false
                        onConfirmReplace()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = "Confirmar substituição")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDestructiveDialog = false
                        onCancelPreview()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = "Cancelar")
                }
            }
        )
    }
    Scaffold(modifier = modifier) { padding ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            item(key = "header") {
                Column {
                    Text(
                        text = "Backup",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() }
                    )
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .heightIn(min = 48.dp)
                    ) {
                        Text(text = "Voltar")
                    }
                }
            }
            when (state) {
                BackupUiState.Idle -> {
                    item(key = "actions") {
                        // Idle never renders actions while Working, so both are
                        // always enabled here (the duplicate-touch guard is the
                        // ViewModel's single-operation state).
                        ActionButtons(onExport = onExport, onImport = onImport)
                    }
                }
                is BackupUiState.Working -> {
                    item(key = "working") {
                        WorkingBlock(operation = state.operation)
                    }
                }
                is BackupUiState.Preview -> {
                    val preview = state.preview
                    item(key = "preview") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Prévia da importação",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() }
                                )
                                Text(
                                    text = "Versão ${preview.schemaVersion} • " +
                                        "${preview.workoutCount} treinos • " +
                                        "${preview.exerciseCount} exercícios • " +
                                        "${preview.executionCount} execuções • " +
                                        "${preview.executionSetCount} séries",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                Text(
                                    text = "Integridade verificada",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                Text(
                                    text = "Isto substituirá todos os dados locais",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                Button(
                                    onClick = { showDestructiveDialog = true },
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text(text = "Importar e substituir")
                                }
                                OutlinedButton(
                                    onClick = onCancelPreview,
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text(text = "Cancelar")
                                }
                            }
                        }
                    }
                }
                is BackupUiState.Success -> {
                    val result = state.result
                    item(key = "success") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                            ) {
                                Text(
                                    text = when (result.operation) {
                                        BackupOperation.EXPORT -> "Backup exportado: ${result.fileName ?: ""}"
                                        BackupOperation.SHARE -> "Backup compartilhado"
                                        BackupOperation.IMPORT -> "Importação concluída: " +
                                            "${result.importedCounts?.workoutCount ?: 0} treinos, " +
                                            "${result.importedCounts?.exerciseCount ?: 0} exercícios, " +
                                            "${result.importedCounts?.executionCount ?: 0} execuções"
                                    },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (
                                    result.operation == BackupOperation.EXPORT ||
                                    result.operation == BackupOperation.SHARE
                                ) {
                                    Button(
                                        onClick = onShare,
                                        modifier = Modifier
                                            .padding(top = 8.dp)
                                            .heightIn(min = 48.dp)
                                    ) {
                                        Text(
                                            text = "Compartilhar",
                                            modifier = Modifier.semantics {
                                                contentDescription = "Compartilhar arquivo de backup"
                                            }
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = onExport,
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .heightIn(min = 48.dp)
                                ) {
                                    Text(text = "Exportar novamente")
                                }
                            }
                        }
                    }
                }
                is BackupUiState.Error -> {
                    item(key = "error") {
                        ErrorBlock(
                            failure = state.failure,
                            recovery = state.recovery,
                            onRetry = onRetry,
                            onPickAnotherFile = onPickAnotherFile
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onExport,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(text = "Exportar")
        }
        Button(
            onClick = onImport,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(text = "Importar")
        }
    }
}

@Composable
private fun WorkingBlock(operation: BackupOperation) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Processando backup"
                liveRegion = LiveRegionMode.Polite
            }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = when (operation) {
                    BackupOperation.EXPORT -> "Exportando…"
                    BackupOperation.SHARE -> "Preparando compartilhamento…"
                    BackupOperation.IMPORT -> "Importando…"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun ErrorBlock(
    failure: BackupFailure,
    recovery: BackupRecovery,
    onRetry: () -> Unit,
    onPickAnotherFile: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = errorMessage(failure),
                style = MaterialTheme.typography.bodyLarge
            )
            // T8.3: the offered action is the one that can actually recover —
            // a file error re-arms the picker, a storage error retries.
            if (recovery == BackupRecovery.PICK_ANOTHER_FILE) {
                Button(
                    onClick = onPickAnotherFile,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = "Escolher outro arquivo")
                }
            } else {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .heightIn(min = 48.dp)
                ) {
                    Text(text = "Tentar novamente")
                }
            }
        }
    }
}

private fun errorMessage(failure: BackupFailure): String = when (failure) {
    is BackupFailure.Corrupt -> "Arquivo de backup inválido."
    is BackupFailure.UnsupportedVersion -> "Versão de backup não suportada."
    is BackupFailure.ChecksumMismatch -> "Integridade inválida."
    is BackupFailure.Validation -> "Dados inválidos no backup."
    is BackupFailure.ReferentiallyInvalid -> "Backup com referências inválidas."
    is BackupFailure.Storage -> "Falha de armazenamento."
}
