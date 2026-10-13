package com.offlineworkout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offlineworkout.data.backup.BackupDocumentTooLargeException
import com.offlineworkout.data.backup.BackupFileGateway
import com.offlineworkout.data.backup.BackupFileInfo
import com.offlineworkout.data.repository.BackupDocument
import com.offlineworkout.data.repository.BackupFailure
import com.offlineworkout.data.repository.BackupImportResult
import com.offlineworkout.data.repository.BackupPreview
import com.offlineworkout.data.repository.BackupRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

// T8.1: local backup tool state. Export creates a document and emits a SAF
// effect; import always previews first and writes only after explicit
// confirmation. Single operation at a time (Working guard); empty uri strings
// mean user-cancelled pickers and return to Idle. No Room here.
sealed interface BackupUiState {
    data object Idle : BackupUiState
    data class Working(val operation: BackupOperation) : BackupUiState
    data class Preview(
        val preview: BackupPreview,
        val fileName: String = "",
        val sizeBytes: Long = 0L,
        val modifiedAtEpochMs: Long? = null
    ) : BackupUiState
    data class Success(val result: BackupUiResult) : BackupUiState

    // T8.3: recovery is explicit. T8.1 spec:263 splits file errors
    // (recoverable by choosing another file) from Storage errors (recoverable
    // by retrying), so the screen never offers a retry that cannot work.
    data class Error(
        val failure: BackupFailure,
        val recovery: BackupRecovery = recoveryFor(failure)
    ) : BackupUiState {
        companion object {
            fun recoveryFor(failure: BackupFailure): BackupRecovery = when (failure) {
                is BackupFailure.Corrupt,
                is BackupFailure.UnsupportedVersion,
                is BackupFailure.ChecksumMismatch,
                is BackupFailure.Validation,
                is BackupFailure.ReferentiallyInvalid -> BackupRecovery.PICK_ANOTHER_FILE
                is BackupFailure.Storage -> BackupRecovery.RETRY
            }
        }
    }
}

enum class BackupRecovery { RETRY, PICK_ANOTHER_FILE }

enum class BackupOperation { EXPORT, SHARE, IMPORT }

// Redesign (prévia): fallback quando describe falha — nome genérico,
// tamanho dos bytes lidos, data desconhecida.
private const val GENERIC_IMPORT_FILE_NAME = "backup.json"

data class BackupUiResult(
    val operation: BackupOperation,
    val fileName: String?,
    val importedCounts: BackupImportResult?
)

sealed interface BackupEffect {
    data class CreateDocument(val fileName: String) : BackupEffect
    // T9.2 (R-9): Message was never emitted; the backup screen has no
    // transient-notice state, so the case was dead weight in the contract.
}

class BackupViewModel(
    private val repository: BackupRepository,
    private val fileGateway: BackupFileGateway
) : ViewModel() {

    private val stateFlow = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state: StateFlow<BackupUiState> = stateFlow

    private val effectsChannel = Channel<BackupEffect>(Channel.BUFFERED)
    val effects: Flow<BackupEffect> = effectsChannel.receiveAsFlow()

    private var pendingDocument: BackupDocument? = null
    private var pendingImportBytes: ByteArray? = null
    private var retryExport = false
    private var retryImportUri: String? = null
    private var retryReplace = false
    private var operationGeneration = 0

    fun onExportRequested() {
        if (stateFlow.value is BackupUiState.Working) return
        val generation = ++operationGeneration
        retryExport = false
        retryImportUri = null
        retryReplace = false
        stateFlow.value = BackupUiState.Working(BackupOperation.EXPORT)
        viewModelScope.launch {
            val result = repository.createDocument()
            if (generation != operationGeneration) return@launch
            result.onSuccess { document ->
                pendingDocument = document
                stateFlow.value = BackupUiState.Success(
                    BackupUiResult(BackupOperation.EXPORT, document.fileName, null)
                )
                effectsChannel.trySend(BackupEffect.CreateDocument(document.fileName))
            }.onFailure { e ->
                if (e is CancellationException) throw e
                retryExport = true
                stateFlow.value = BackupUiState.Error(toBackupFailure(e))
            }
        }
    }

    fun onExportDestinationSelected(uriString: String) {
        if (uriString.isBlank()) {
            stateFlow.value = BackupUiState.Idle
            return
        }
        val document = pendingDocument ?: run {
            stateFlow.value = BackupUiState.Idle
            return
        }
        if (stateFlow.value is BackupUiState.Working) return
        val generation = ++operationGeneration
        stateFlow.value = BackupUiState.Working(BackupOperation.EXPORT)
        viewModelScope.launch {
            val result = fileGateway.writeBytes(uriString, document.bytes)
            if (generation != operationGeneration) return@launch
            result.onSuccess {
                stateFlow.value = BackupUiState.Success(
                    BackupUiResult(BackupOperation.EXPORT, document.fileName, null)
                )
            }.onFailure { e ->
                if (e is CancellationException) throw e
                retryExport = true
                stateFlow.value = BackupUiState.Error(BackupFailure.Storage("cannot write destination"))
            }
        }
    }

    /**
     * T8.3: the gateway performs the share, so a failure is a state the user
     * sees instead of a silent no-op, and Working(SHARE)/Success(SHARE) are
     * real states. Expired staged copies are purged afterwards; a failing
     * purge is ignored because it is retried on the next startup.
     */
    fun onShareRequested() {
        if (stateFlow.value is BackupUiState.Working) return
        val document = pendingDocument ?: return
        val generation = ++operationGeneration
        stateFlow.value = BackupUiState.Working(BackupOperation.SHARE)
        viewModelScope.launch {
            val result = fileGateway.shareDocument(
                document.fileName,
                document.mimeType,
                document.bytes
            )
            if (generation != operationGeneration) return@launch
            fileGateway.purgeExpiredStagedCache()
            result.onSuccess {
                stateFlow.value = BackupUiState.Success(
                    BackupUiResult(BackupOperation.SHARE, document.fileName, null)
                )
            }.onFailure { e ->
                if (e is CancellationException) throw e
                stateFlow.value = BackupUiState.Error(BackupFailure.Storage("cannot share backup"))
            }
        }
    }

    fun onImportSelected(uriString: String) {
        if (uriString.isBlank()) {
            stateFlow.value = BackupUiState.Idle
            return
        }
        if (stateFlow.value is BackupUiState.Working) return
        val generation = ++operationGeneration
        retryExport = false
        retryReplace = false
        stateFlow.value = BackupUiState.Working(BackupOperation.IMPORT)
        viewModelScope.launch {
            val read = fileGateway.readBytes(uriString)
            if (generation != operationGeneration) return@launch
            val bytes = read.getOrElse { e ->
                if (e is CancellationException) throw e
                if (e is BackupDocumentTooLargeException) {
                    // T8.3: re-reading the same document cannot succeed, so the
                    // recovery is "choose another file", not a retry.
                    retryImportUri = null
                    stateFlow.value = BackupUiState.Error(
                        BackupFailure.Corrupt("backup is too large to import")
                    )
                } else {
                    retryImportUri = uriString
                    stateFlow.value = BackupUiState.Error(BackupFailure.Storage("cannot read source"))
                }
                return@launch
            }
            previewBytes(bytes, generation, fileInfo(uriString, bytes))
        }
    }

    // Redesign (prévia): metadados tolerantes a falha — describe quebrado cai
    // para nome genérico + tamanho lido + data desconhecida. preview(bytes)
    // continua puro e inalterado.
    private suspend fun fileInfo(uriString: String, bytes: ByteArray): BackupFileInfo =
        fileGateway.describe(uriString).getOrElse {
            BackupFileInfo(
                fileName = GENERIC_IMPORT_FILE_NAME,
                sizeBytes = bytes.size.toLong(),
                modifiedAtEpochMs = null
            )
        }

    fun onConfirmReplaceAll() {
        val bytes = pendingImportBytes ?: return
        if (stateFlow.value is BackupUiState.Working) return
        val generation = ++operationGeneration
        stateFlow.value = BackupUiState.Working(BackupOperation.IMPORT)
        viewModelScope.launch {
            val result = repository.importReplaceAll(bytes)
            if (generation != operationGeneration) return@launch
            result.onSuccess { counts ->
                pendingImportBytes = null
                retryReplace = false
                stateFlow.value = BackupUiState.Success(
                    BackupUiResult(BackupOperation.IMPORT, null, counts)
                )
            }.onFailure { e ->
                if (e is CancellationException) throw e
                retryReplace = true
                stateFlow.value = BackupUiState.Error(toBackupFailure(e))
            }
        }
    }

    fun onCancelPreview() {
        pendingImportBytes = null
        retryReplace = false
        retryImportUri = null
        stateFlow.value = BackupUiState.Idle
    }

    fun onRetry() {
        when {
            retryExport -> {
                retryExport = false
                onExportRequested()
            }
            retryReplace -> onConfirmReplaceAll()
            retryImportUri != null -> {
                val uri = retryImportUri
                retryImportUri = null
                if (uri != null) onImportSelected(uri)
            }
            // T8.3: a file error is not retryable; the only recovery that can
            // succeed is choosing a different document.
            else -> if (stateFlow.value is BackupUiState.Error) onPickAnotherFileRequested()
        }
    }

    /**
     * T8.3: arms the SAF picker again for file errors. Returns to Idle without
     * writing; the next picked document starts a fresh import.
     */
    fun onPickAnotherFileRequested() {
        pendingImportBytes = null
        retryImportUri = null
        retryExport = false
        retryReplace = false
        stateFlow.value = BackupUiState.Idle
    }

    private fun previewBytes(bytes: ByteArray, generation: Int, info: BackupFileInfo) {
        viewModelScope.launch {
            val result = repository.preview(bytes)
            if (generation != operationGeneration) return@launch
            result.onSuccess { preview ->
                pendingImportBytes = bytes
                stateFlow.value = BackupUiState.Preview(
                    preview = preview,
                    fileName = info.fileName,
                    sizeBytes = info.sizeBytes,
                    modifiedAtEpochMs = info.modifiedAtEpochMs
                )
            }.onFailure { e ->
                if (e is CancellationException) throw e
                // T8.3: preview failures are file errors; recovery is picking
                // another document, so no retry target is kept.
                retryImportUri = null
                stateFlow.value = BackupUiState.Error(toBackupFailure(e))
            }
        }
    }

    private fun toBackupFailure(e: Throwable): BackupFailure = when (e) {
        is BackupFailure -> e
        else -> BackupFailure.Storage("unexpected data error")
    }
}
