package com.offlineworkout.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlineworkout.data.backup.BACKUP_MIME_TYPE
import com.offlineworkout.data.backup.BackupFileGateway
import com.offlineworkout.data.repository.BackupRepository
import com.offlineworkout.viewmodel.BackupEffect
import com.offlineworkout.viewmodel.BackupViewModel

// T8.1: backup entry. Wires the ViewModel (repository + gateway interfaces)
// to SAF launchers: CreateDocument for export destination, OpenDocument for
// import source. Sharing and cache cleanup are the gateway's job inside the
// ViewModel, so a failure is a state and not a silent no-op. No Room here.
@Composable
fun BackupRoute(
    repository: BackupRepository,
    fileGateway: BackupFileGateway,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val factory = remember(repository, fileGateway) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return BackupViewModel(repository, fileGateway) as T
            }
        }
    }
    val backupViewModel: BackupViewModel = viewModel(factory = factory)
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)
    ) { uri ->
        backupViewModel.onExportDestinationSelected(uri?.toString() ?: "")
    }
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        backupViewModel.onImportSelected(uri?.toString() ?: "")
    }
    LaunchedEffect(backupViewModel) {
        backupViewModel.effects.collect { effect ->
            when (effect) {
                is BackupEffect.CreateDocument -> createLauncher.launch(effect.fileName)
            }
        }
    }
    BackupScreen(
        state = backupViewModel.state.collectAsStateWithLifecycle().value,
        onExport = backupViewModel::onExportRequested,
        onShare = backupViewModel::onShareRequested,
        onImport = { openLauncher.launch(arrayOf(BACKUP_MIME_TYPE, "*/*")) },
        onConfirmReplace = backupViewModel::onConfirmReplaceAll,
        onCancelPreview = backupViewModel::onCancelPreview,
        onRetry = backupViewModel::onRetry,
        // T8.3: file errors are recovered by arming the picker again.
        onPickAnotherFile = {
            backupViewModel.onPickAnotherFileRequested()
            openLauncher.launch(arrayOf(BACKUP_MIME_TYPE, "*/*"))
        },
        onBack = onBack,
        modifier = modifier
    )
}
