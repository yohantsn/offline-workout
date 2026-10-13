package com.offlineworkout.ui.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.offlineworkout.data.repository.BackupFailure
import com.offlineworkout.data.repository.BackupImportResult
import com.offlineworkout.data.repository.BackupPreview
import com.offlineworkout.viewmodel.BackupOperation
import com.offlineworkout.viewmodel.BackupRecovery
import com.offlineworkout.viewmodel.BackupUiResult
import com.offlineworkout.viewmodel.BackupUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// T8.1: backup screen contract: state rendering, mandatory preview before any
// destructive write, explicit confirmation, cancel path and error/retry.
class BackupScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class Callbacks {
        var exported = 0
        var shared = 0
        var imported = 0
        var confirmed = 0
        var cancelled = 0
        var retried = 0
        var repicked = 0
        var back = 0
    }

    private fun screen(state: BackupUiState, callbacks: Callbacks = Callbacks()) {
        composeTestRule.setContent {
            BackupScreen(
                state = state,
                onExport = { callbacks.exported += 1 },
                onShare = { callbacks.shared += 1 },
                onImport = { callbacks.imported += 1 },
                onConfirmReplace = { callbacks.confirmed += 1 },
                onCancelPreview = { callbacks.cancelled += 1 },
                onRetry = { callbacks.retried += 1 },
                onPickAnotherFile = { callbacks.repicked += 1 },
                onBack = { callbacks.back += 1 }
            )
        }
    }

    private fun preview() = BackupPreview(
        schemaVersion = 1,
        checksum = "abc",
        workoutCount = 2,
        exerciseCount = 5,
        executionCount = 7,
        executionSetCount = 9,
        willReplaceAll = true
    )

    @Test
    fun idle_offers_export_and_import_only() {
        val callbacks = Callbacks()
        screen(BackupUiState.Idle, callbacks)
        composeTestRule.onNodeWithText("Backup").assertIsDisplayed()
        composeTestRule.onNodeWithText("Exportar").assertIsEnabled().performClick()
        composeTestRule.onNodeWithText("Importar").assertIsEnabled().performClick()
        assertEquals(1, callbacks.exported)
        assertEquals(1, callbacks.imported)
        assertEquals(0, callbacks.confirmed)
    }

    @Test
    fun working_announces_progress_without_actions() {
        screen(BackupUiState.Working(BackupOperation.EXPORT))
        composeTestRule.onNodeWithContentDescription("Processando backup").assertIsDisplayed()
        composeTestRule.onNodeWithText("Exportando…").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Exportar").assertCountEquals(0)
    }

    @Test
    fun preview_lists_counts_and_warns_before_writing() {
        val callbacks = Callbacks()
        screen(BackupUiState.Preview(preview()), callbacks)
        composeTestRule.onNodeWithText("Prévia da importação").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Versão 1 • 2 treinos • 5 exercícios • 7 execuções • 9 séries"
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Integridade verificada").assertIsDisplayed()
        composeTestRule.onNodeWithText("Isto substituirá todos os dados locais").assertIsDisplayed()
        // Opening the confirmation must not write anything by itself.
        composeTestRule.onNodeWithText("Importar e substituir").performClick()
        assertEquals(0, callbacks.confirmed)
        composeTestRule.onNodeWithText("Substituir todos os dados locais?").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Isto substituirá todos os dados locais pelos 2 treinos, " +
                "5 exercícios, 7 execuções do arquivo. Esta ação não pode ser desfeita."
        ).assertIsDisplayed()
    }

    @Test
    fun confirm_button_writes_exactly_once() {
        val callbacks = Callbacks()
        screen(BackupUiState.Preview(preview()), callbacks)
        composeTestRule.onNodeWithText("Importar e substituir").performClick()
        composeTestRule.onNodeWithText("Confirmar substituição").performClick()
        assertEquals(1, callbacks.confirmed)
    }

    @Test
    fun dialog_cancel_does_not_write() {
        val callbacks = Callbacks()
        screen(BackupUiState.Preview(preview()), callbacks)
        composeTestRule.onNodeWithText("Importar e substituir").performClick()
        // The dialog also offers "Cancelar"; dismissing it must never write.
        composeTestRule.onAllNodesWithText("Cancelar")[0].performClick()
        assertEquals(0, callbacks.confirmed)
    }

    @Test
    fun preview_cancel_returns_without_writing() {
        val callbacks = Callbacks()
        screen(BackupUiState.Preview(preview()), callbacks)
        composeTestRule.onAllNodesWithText("Cancelar")[0].performClick()
        assertEquals(1, callbacks.cancelled)
        assertEquals(0, callbacks.confirmed)
    }

    @Test
    fun import_success_reports_counts() {
        screen(
            BackupUiState.Success(
                BackupUiResult(
                    operation = BackupOperation.IMPORT,
                    fileName = null,
                    importedCounts = BackupImportResult(2, 5, 7, 9)
                )
            )
        )
        composeTestRule.onNodeWithText("Importação concluída: 2 treinos, 5 exercícios, 7 execuções")
            .assertIsDisplayed()
    }

    @Test
    fun export_success_offers_share_with_accessible_label() {
        val callbacks = Callbacks()
        screen(
            BackupUiState.Success(
                BackupUiResult(BackupOperation.EXPORT, "backup.json", null)
            ),
            callbacks
        )
        composeTestRule.onNodeWithText("Backup exportado: backup.json").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Compartilhar arquivo de backup")
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, callbacks.shared)
    }

    @Test
    fun file_error_offers_choosing_another_file_not_a_useless_retry() {
        val callbacks = Callbacks()
        screen(BackupUiState.Error(BackupFailure.ChecksumMismatch("a", "b")), callbacks)
        composeTestRule.onNodeWithText("Integridade inválida.").assertIsDisplayed()
        // T8.1: file errors are recovered by choosing another document.
        composeTestRule.onAllNodesWithText("Tentar novamente").assertCountEquals(0)
        composeTestRule.onNodeWithText("Escolher outro arquivo").performClick()
        assertEquals(1, callbacks.repicked)
        assertEquals(0, callbacks.retried)
    }

    @Test
    fun storage_error_is_retried() {
        val callbacks = Callbacks()
        screen(BackupUiState.Error(BackupFailure.Storage("x")), callbacks)
        composeTestRule.onNodeWithText("Falha de armazenamento.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tentar novamente").performClick()
        assertEquals(1, callbacks.retried)
        assertEquals(0, callbacks.repicked)
    }

    @Test
    fun error_recovery_is_derived_from_the_failure_kind() {
        assertEquals(
            BackupRecovery.PICK_ANOTHER_FILE,
            BackupUiState.Error(BackupFailure.Corrupt("x")).recovery
        )
        assertEquals(
            BackupRecovery.PICK_ANOTHER_FILE,
            BackupUiState.Error(BackupFailure.UnsupportedVersion(2)).recovery
        )
        assertEquals(
            BackupRecovery.PICK_ANOTHER_FILE,
            BackupUiState.Error(BackupFailure.ReferentiallyInvalid("x")).recovery
        )
        assertEquals(BackupRecovery.RETRY, BackupUiState.Error(BackupFailure.Storage("x")).recovery)
    }

    @Test
    fun share_working_state_announces_progress() {
        screen(BackupUiState.Working(BackupOperation.SHARE))
        composeTestRule.onNodeWithText("Preparando compartilhamento…").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Processando backup").assertIsDisplayed()
    }

    @Test
    fun share_success_state_is_reachable_and_keeps_the_payload() {
        val callbacks = Callbacks()
        screen(BackupUiState.Success(BackupUiResult(BackupOperation.SHARE, "backup.json", null)), callbacks)
        composeTestRule.onNodeWithText("Backup compartilhado").assertIsDisplayed()
        // After a share the payload is still available for another send.
        composeTestRule.onNodeWithContentDescription("Compartilhar arquivo de backup")
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, callbacks.shared)
    }

    @Test
    fun integrity_error_message_is_specific() {
        screen(BackupUiState.Error(BackupFailure.ChecksumMismatch("a", "b")))
        composeTestRule.onNodeWithText("Integridade inválida.").assertIsDisplayed()
    }

    @Test
    fun unsupported_version_error_is_specific() {
        screen(BackupUiState.Error(BackupFailure.UnsupportedVersion(9)))
        composeTestRule.onNodeWithText("Versão de backup não suportada.").assertIsDisplayed()
    }

    @Test
    fun corrupt_error_is_specific() {
        screen(BackupUiState.Error(BackupFailure.Corrupt("x")))
        composeTestRule.onNodeWithText("Arquivo de backup inválido.").assertIsDisplayed()
    }

    @Test
    fun referential_error_is_specific() {
        screen(BackupUiState.Error(BackupFailure.ReferentiallyInvalid("x")))
        composeTestRule.onNodeWithText("Backup com referências inválidas.").assertIsDisplayed()
    }

    @Test
    fun storage_error_is_specific() {
        screen(BackupUiState.Error(BackupFailure.Storage("x")))
        composeTestRule.onNodeWithText("Falha de armazenamento.").assertIsDisplayed()
    }

    @Test
    fun back_navigates_out() {
        val callbacks = Callbacks()
        screen(BackupUiState.Idle, callbacks)
        composeTestRule.onNodeWithText("Voltar").performClick()
        assertEquals(1, callbacks.back)
    }
}
