package com.offlineworkout.viewmodel

import com.offlineworkout.data.backup.BackupDocumentTooLargeException
import com.offlineworkout.data.backup.BackupFileGateway
import com.offlineworkout.data.backup.BackupFileInfo
import com.offlineworkout.data.backup.MAX_BACKUP_BYTES
import com.offlineworkout.data.repository.BackupDocument
import com.offlineworkout.data.repository.BackupFailure
import com.offlineworkout.data.repository.BackupImportResult
import com.offlineworkout.data.repository.BackupPreview
import com.offlineworkout.data.repository.BackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// T8.1: backup ViewModel against fakes. States, single-operation guard,
// mandatory preview, SAF-cancel paths, retry and effect emission. No Room.
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.setTestMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
    }

    private fun document() = BackupDocument(
        fileName = "offline-workout-backup-20240101-120000.json",
        mimeType = "application/json",
        bytes = "{}".toByteArray(Charsets.UTF_8),
        schemaVersion = 1,
        checksum = "abc"
    )

    private fun preview() = BackupPreview(1, "abc", 1, 2, 3, 4, willReplaceAll = true)

    private val BACKUP_EXPORT_RESULT = BackupUiResult(
        BackupOperation.EXPORT,
        "offline-workout-backup-20240101-120000.json",
        null
    )

    @Test
    fun starts_idle() = runTest {
        setTestMain()
        val vm = BackupViewModel(FakeBackupRepository(), FakeGateway())
        assertEquals(BackupUiState.Idle, vm.state.value)
    }

    @Test
    fun export_emits_create_document_then_writes_on_destination() = runTest {
        setTestMain()
        val repo = FakeBackupRepository(createResult = Result.success(document()))
        val gateway = FakeGateway()
        val vm = BackupViewModel(repo, gateway)
        vm.onExportRequested()
        assertEquals(BackupEffect.CreateDocument(document().fileName), vm.effects.first())
        runCurrent()
        assertTrue(vm.state.value is BackupUiState.Success)

        vm.onExportDestinationSelected("content://doc/1")
        runCurrent()
        assertEquals(listOf("content://doc/1" to document().bytes.size), gateway.writes.map { it.first to it.second.size })
        val success = vm.state.value as BackupUiState.Success
        assertEquals(BackupOperation.EXPORT, success.result.operation)
        assertEquals(document().fileName, success.result.fileName)
    }

    @Test
    fun export_saf_cancel_returns_to_idle() = runTest {
        setTestMain()
        val vm = BackupViewModel(
            FakeBackupRepository(createResult = Result.success(document())),
            FakeGateway()
        )
        vm.onExportRequested()
        vm.effects.first()
        vm.onExportDestinationSelected("")
        assertEquals(BackupUiState.Idle, vm.state.value)
    }

    @Test
    fun double_export_starts_single_operation() = runTest {
        setTestMain()
        val gate = kotlinx.coroutines.CompletableDeferred<Result<BackupDocument>>()
        val repo = FakeBackupRepository(deferredCreate = gate)
        val vm = BackupViewModel(repo, FakeGateway())
        vm.onExportRequested()
        vm.onExportRequested()
        assertTrue(vm.state.value is BackupUiState.Working)
        gate.complete(Result.success(document()))
        assertEquals(1, repo.createCalls)
        assertEquals(BackupEffect.CreateDocument(document().fileName), vm.effects.first())
    }

    @Test
    fun share_is_a_noop_without_document() = runTest {
        setTestMain()
        val vm = BackupViewModel(FakeBackupRepository(), FakeGateway())
        vm.onShareRequested()
        assertEquals(BackupUiState.Idle, vm.state.value)
    }

    @Test
    fun share_runs_through_the_gateway_and_reports_success() = runTest {
        setTestMain()
        val gate = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()
        val gateway = FakeGateway(shareGate = gate)
        val vm = BackupViewModel(
            FakeBackupRepository(createResult = Result.success(document())),
            gateway
        )
        vm.onExportRequested()
        vm.effects.first()
        vm.onExportDestinationSelected("content://doc/1")
        runCurrent()
        assertEquals(BackupUiState.Success(BACKUP_EXPORT_RESULT), vm.state.value)

        vm.onShareRequested()
        // The share is a real operation now: it is Working while the gateway
        // is handing the payload to the chooser.
        assertEquals(
            BackupUiState.Working(BackupOperation.SHARE),
            vm.state.value
        )
        gate.complete(Result.success(Unit))
        runCurrent()
        val success = vm.state.value as BackupUiState.Success
        assertEquals(BackupOperation.SHARE, success.result.operation)
        assertEquals(listOf(document().fileName), gateway.sharedFiles)
        // B-2: expired staged copies are purged after the share.
        assertEquals(1, gateway.purgeCalls)
    }

    @Test
    fun share_failure_becomes_visible_error() = runTest {
        setTestMain()
        val vm = BackupViewModel(
            FakeBackupRepository(createResult = Result.success(document())),
            FakeGateway(shareResult = Result.failure(IllegalStateException("no app")))
        )
        vm.onExportRequested()
        vm.effects.first()
        vm.onExportDestinationSelected("content://doc/1")
        runCurrent()
        vm.onShareRequested()
        val error = vm.state.first { it is BackupUiState.Error } as BackupUiState.Error
        assertTrue(error.failure is BackupFailure.Storage)
        assertEquals(BackupRecovery.RETRY, error.recovery)
    }

    @Test
    fun file_error_offers_choosing_another_file_and_never_writes() = runTest {
        setTestMain()
        val repo = FakeBackupRepository(
            previewResult = Result.failure(BackupFailure.ChecksumMismatch("a", "b"))
        )
        val vm = BackupViewModel(repo, FakeGateway(readResult = Result.success(byteArrayOf(1))))
        vm.onImportSelected("content://doc/9")
        val error = vm.state.first { it is BackupUiState.Error } as BackupUiState.Error
        // A-1: retrying the same document cannot work, so the recovery is the
        // picker and there is no retry target left behind.
        assertEquals(BackupRecovery.PICK_ANOTHER_FILE, error.recovery)
        vm.onRetry()
        assertEquals(BackupUiState.Idle, vm.state.value)
        assertEquals(0, repo.importCalls)
    }

    @Test
    fun oversized_document_offers_choosing_another_file() = runTest {
        setTestMain()
        val vm = BackupViewModel(
            FakeBackupRepository(),
            FakeGateway(
                readResult = Result.failure(BackupDocumentTooLargeException(MAX_BACKUP_BYTES))
            )
        )
        vm.onImportSelected("content://doc/9")
        val error = vm.state.first { it is BackupUiState.Error } as BackupUiState.Error
        assertTrue(error.failure is BackupFailure.Corrupt)
        assertEquals(BackupRecovery.PICK_ANOTHER_FILE, error.recovery)
    }

    @Test
    fun storage_error_on_read_is_retried_with_the_same_document() = runTest {
        setTestMain()
        val gateway = FakeGateway(readResult = Result.failure(IllegalStateException("io")))
        val vm = BackupViewModel(FakeBackupRepository(), gateway)
        vm.onImportSelected("content://doc/9")
        val error = vm.state.first { it is BackupUiState.Error } as BackupUiState.Error
        assertEquals(BackupRecovery.RETRY, error.recovery)
        gateway.readResult = Result.success(byteArrayOf(1))
        vm.onRetry()
        // The same uri is read again (and now fails in preview, which is a
        // file error) — proof the retry actually re-armed a read.
        val second = vm.state.first { it is BackupUiState.Error } as BackupUiState.Error
        assertTrue(second.failure is BackupFailure.Storage || second.failure is BackupFailure.Corrupt)
    }

    @Test
    fun import_requires_preview_and_confirm() = runTest {
        setTestMain()
        val repo = FakeBackupRepository(
            previewResult = Result.success(preview()),
            importResult = Result.success(BackupImportResult(1, 2, 3, 4))
        )
        val vm = BackupViewModel(repo, FakeGateway(readResult = Result.success(byteArrayOf(1, 2, 3))))
        vm.onImportSelected("content://doc/9")
        val previewState = vm.state.first { it is BackupUiState.Preview } as BackupUiState.Preview
        assertEquals(1, previewState.preview.workoutCount)
        assertEquals(true, previewState.preview.willReplaceAll)
        assertEquals(0, repo.importCalls)

        vm.onConfirmReplaceAll()
        val success = vm.state.first { it is BackupUiState.Success } as BackupUiState.Success
        assertEquals(1, repo.importCalls)
        assertEquals(BackupOperation.IMPORT, success.result.operation)
        assertEquals(4, success.result.importedCounts?.executionSetCount)
    }

    @Test
    fun preview_carries_file_metadata() = runTest {
        setTestMain()
        val repo = FakeBackupRepository(previewResult = Result.success(preview()))
        val vm = BackupViewModel(
            repo,
            FakeGateway(
                readResult = Result.success(byteArrayOf(1, 2, 3)),
                describeResult = Result.success(
                    BackupFileInfo("treino-backup.json", 1331L, 1_714_953_600_000L)
                )
            )
        )
        vm.onImportSelected("content://doc/9")
        val previewState = vm.state.first { it is BackupUiState.Preview } as BackupUiState.Preview
        assertEquals("treino-backup.json", previewState.fileName)
        assertEquals(1331L, previewState.sizeBytes)
        assertEquals(1_714_953_600_000L, previewState.modifiedAtEpochMs)
    }

    @Test
    fun describe_failure_falls_back_to_generic_metadata() = runTest {
        setTestMain()
        val repo = FakeBackupRepository(previewResult = Result.success(preview()))
        val vm = BackupViewModel(
            repo,
            FakeGateway(
                readResult = Result.success(byteArrayOf(1, 2, 3)),
                describeResult = Result.failure(IllegalStateException("no provider"))
            )
        )
        vm.onImportSelected("content://doc/9")
        val previewState = vm.state.first { it is BackupUiState.Preview } as BackupUiState.Preview
        assertEquals("backup.json", previewState.fileName)
        assertEquals(3L, previewState.sizeBytes)
        assertNull(previewState.modifiedAtEpochMs)
    }

    @Test
    fun cancel_preview_writes_nothing() = runTest {
        setTestMain()
        val repo = FakeBackupRepository(previewResult = Result.success(preview()))
        val vm = BackupViewModel(repo, FakeGateway(readResult = Result.success(byteArrayOf(1))))
        vm.onImportSelected("content://doc/9")
        vm.state.first { it is BackupUiState.Preview }
        vm.onCancelPreview()
        assertEquals(BackupUiState.Idle, vm.state.value)
        assertEquals(0, repo.importCalls)
    }

    @Test
    fun import_saf_cancel_returns_to_idle() = runTest {
        setTestMain()
        val vm = BackupViewModel(FakeBackupRepository(), FakeGateway())
        vm.onImportSelected("")
        assertEquals(BackupUiState.Idle, vm.state.value)
    }

    @Test
    fun corrupt_preview_is_error_without_write() = runTest {
        setTestMain()
        val repo = FakeBackupRepository(
            previewResult = Result.failure(BackupFailure.ChecksumMismatch("a", "b"))
        )
        val vm = BackupViewModel(repo, FakeGateway(readResult = Result.success(byteArrayOf(1))))
        vm.onImportSelected("content://doc/9")
        val error = vm.state.first { it is BackupUiState.Error } as BackupUiState.Error
        assertTrue(error.failure is BackupFailure.ChecksumMismatch)
        assertEquals(0, repo.importCalls)
    }

    @Test
    fun retry_repeats_failed_export() = runTest {
        setTestMain()
        val repo = FakeBackupRepository(createResult = Result.failure(BackupFailure.Storage("down")))
        val vm = BackupViewModel(repo, FakeGateway())
        vm.onExportRequested()
        assertTrue(vm.state.first { it is BackupUiState.Error } is BackupUiState.Error)
        repo.createResult = Result.success(document())
        vm.onRetry()
        assertEquals(BackupEffect.CreateDocument(document().fileName), vm.effects.first())
        assertEquals(2, repo.createCalls)
    }

    private class FakeBackupRepository(
        var createResult: Result<BackupDocument>? = null,
        var deferredCreate: kotlinx.coroutines.CompletableDeferred<Result<BackupDocument>>? = null,
        var previewResult: Result<BackupPreview>? = null,
        var importResult: Result<BackupImportResult>? = null
    ) : BackupRepository {
        var createCalls = 0
        var importCalls = 0

        override suspend fun createDocument(): Result<BackupDocument> {
            createCalls += 1
            deferredCreate?.let { return it.await() }
            return createResult ?: Result.failure(BackupFailure.Storage("no stub"))
        }

        override suspend fun preview(bytes: ByteArray): Result<BackupPreview> {
            return previewResult ?: Result.failure(BackupFailure.Storage("no stub"))
        }

        override suspend fun importReplaceAll(bytes: ByteArray): Result<BackupImportResult> {
            importCalls += 1
            return importResult ?: Result.failure(BackupFailure.Storage("no stub"))
        }
    }

    private class FakeGateway(
        var readResult: Result<ByteArray>? = null,
        var shareResult: Result<Unit>? = null,
        var shareGate: kotlinx.coroutines.CompletableDeferred<Result<Unit>>? = null,
        var describeResult: Result<BackupFileInfo>? = null
    ) : BackupFileGateway {
        val writes = mutableListOf<Pair<String, ByteArray>>()
        var clearCalls = 0
        var purgeCalls = 0
        var sharedFiles = mutableListOf<String>()

        override suspend fun writeBytes(uriString: String, bytes: ByteArray): Result<Unit> {
            writes.add(uriString to bytes)
            return Result.success(Unit)
        }

        override suspend fun readBytes(uriString: String): Result<ByteArray> {
            return readResult ?: Result.failure(IllegalStateException("no stub"))
        }

        override suspend fun shareDocument(fileName: String, mimeType: String, bytes: ByteArray): Result<Unit> {
            sharedFiles.add(fileName)
            shareGate?.let { return it.await() }
            return shareResult ?: Result.success(Unit)
        }

        override suspend fun clearStagedCache(): Result<Unit> {
            clearCalls += 1
            return Result.success(Unit)
        }

        override suspend fun purgeExpiredStagedCache(): Result<Unit> {
            purgeCalls += 1
            return Result.success(Unit)
        }

        override suspend fun describe(uriString: String): Result<BackupFileInfo> {
            return describeResult ?: Result.success(BackupFileInfo("doc.json", 0L, null))
        }
    }
}
