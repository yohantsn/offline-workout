package com.offlineworkout.ui.imports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlineworkout.data.importer.ImportFileReader
import com.offlineworkout.data.repository.WorkoutRepository
import com.offlineworkout.viewmodel.WorkoutImportViewModel

// SAF launcher mirrors the backup route: OpenDocument gives a content URI and
// the ViewModel owns reading, parsing and the transaction. Nothing reaches Room
// before the user confirms the review.
private val IMPORT_MIME_TYPES = arrayOf(
    "text/csv",
    "text/comma-separated-values",
    "application/csv",
    "text/plain",
    "*/*"
)

@Composable
fun ImportRoute(
    repository: WorkoutRepository,
    fileReader: ImportFileReader,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val factory = remember(repository, fileReader) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WorkoutImportViewModel(repository, fileReader) as T
            }
        }
    }
    val importViewModel: WorkoutImportViewModel = viewModel(factory = factory)
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        importViewModel.onFileSelected(uri?.toString().orEmpty())
    }
    ImportScreen(
        state = importViewModel.state.collectAsStateWithLifecycle().value,
        onPickFile = { pickFile.launch(IMPORT_MIME_TYPES) },
        onCodeChanged = importViewModel::onCodeChanged,
        onNameChanged = importViewModel::onNameChanged,
        onConfirm = importViewModel::onConfirm,
        onChooseAnotherFile = {
            importViewModel.onChooseAnotherFile()
            pickFile.launch(IMPORT_MIME_TYPES)
        },
        onClose = onBack,
        modifier = modifier
    )
}
