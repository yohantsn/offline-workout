package com.offlineworkout.ui.backup

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
// T8.1 + redesign lote 4: duas cards, prévia em bottom sheet com as 4
// contagens e aviso destrutivo, erro com causa preservada. Contrato de
// comportamento preservado: nada escreve sem confirmação explícita,
// recuperação por tipo de falha.
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

    // Robolectric não entrega eventos de toque à janela do ModalBottomSheet
    // (repro mínimo: Button isolado na sheet recebe 0 cliques; o AlertDialog
    // antigo recebia). A fiação botão→callback é verificada invocando a ação
    // de clique do nó diretamente — o nó continua obrigado a expor a ação
    // (hasClickAction) e a estar visível.
    private fun SemanticsNodeInteraction.invokeClickAction() {
        val config = fetchSemanticsNode().config
        check(config.contains(SemanticsActions.OnClick)) { "node exposes no click action" }
        val handler = config[SemanticsActions.OnClick].action
        checkNotNull(handler) { "click action has no handler" }
        handler()
    }

    @Test
    fun idle_offers_export_and_import_only() {
        val callbacks = Callbacks()
        screen(BackupUiState.Idle, callbacks)
        composeTestRule.onNodeWithText("Backup").assertIsDisplayed()
        composeTestRule.onNodeWithText("Exportar arquivo").assertIsEnabled().performClick()
        composeTestRule.onNodeWithText("Escolher arquivo…").assertIsEnabled().performClick()
        assertEquals(1, callbacks.exported)
        assertEquals(1, callbacks.imported)
        assertEquals(0, callbacks.confirmed)
    }

    @Test
    fun idle_import_card_shows_mandatory_written_warning() {
        screen(BackupUiState.Idle)
        // O aviso é obrigatório: cor sozinha não passa em contraste nem TalkBack.
        composeTestRule.onNodeWithText(
            "Importar substitui todos os dados deste aparelho. Não há como desfazer."
        ).assertIsDisplayed()
    }

    @Test
    fun idle_shows_system_backup_off_line() {
        screen(BackupUiState.Idle)
        composeTestRule.onNodeWithText(
            "O backup do sistema está desligado — só sai deste aparelho por aqui."
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun working_announces_progress_without_actions() {
        screen(BackupUiState.Working(BackupOperation.EXPORT))
        composeTestRule.onNodeWithContentDescription("Processando backup").assertIsDisplayed()
        composeTestRule.onNodeWithText("Exportando…").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Exportar").assertCountEquals(0)
    }

    @Test
    fun preview_sheet_lists_counts_and_warns_before_writing() {
        val callbacks = Callbacks()
        screen(BackupUiState.Preview(preview()), callbacks)
        composeTestRule.onNodeWithText("Prévia da importação").assertIsDisplayed()
        // As 4 contagens em tiles: treinos/exercícios/sessões/séries.
        // A sheet é rolável: presença, não posição na dobra.
        composeTestRule.onNodeWithText("treinos").assertExists()
        composeTestRule.onNodeWithText("exercícios").assertExists()
        composeTestRule.onNodeWithText("sessões").assertExists()
        composeTestRule.onNodeWithText("séries").assertExists()
        composeTestRule.onNodeWithText("2").assertExists()
        composeTestRule.onNodeWithText("5").assertExists()
        composeTestRule.onNodeWithText("7").assertExists()
        composeTestRule.onNodeWithText("9").assertExists()
        composeTestRule.onNodeWithText("Soma de verificação: abc").assertExists()
        composeTestRule.onNodeWithText("Isto substituirá todos os dados locais").assertExists()
        composeTestRule.onNodeWithText(
            "Seu treino atual e todo o histórico serão apagados. Esta ação não pode ser desfeita."
        ).assertExists()
        // Abrir a prévia não escreve nada por si só.
        assertEquals(0, callbacks.confirmed)
    }

    @Test
    fun preview_sheet_shows_file_metadata() {
        screen(
            BackupUiState.Preview(
                preview(),
                fileName = "treino-backup.json",
                sizeBytes = 1331L,
                modifiedAtEpochMs = 1_714_992_000_000L
            )
        )
        composeTestRule.onNodeWithText("treino-backup.json").assertExists()
        composeTestRule.onNodeWithText("1,3 KB · 06/05/2024").assertExists()
        composeTestRule.onNodeWithText("Soma de verificação: abc").assertExists()
    }

    @Test
    fun preview_sheet_shows_dash_for_unknown_date() {
        screen(
            BackupUiState.Preview(
                preview(),
                fileName = "b.json",
                sizeBytes = 42L,
                modifiedAtEpochMs = null
            )
        )
        composeTestRule.onNodeWithText("b.json").assertExists()
        composeTestRule.onNodeWithText("42 B · —").assertExists()
    }

    @Test
    fun preview_sheet_shows_checksum_without_invented_filename() {
        // O contrato BackupPreview não tem nome de arquivo (mudança de
        // repositório, fora deste lote): a sheet mostra a soma que existe.
        screen(BackupUiState.Preview(preview()))
        composeTestRule.onNodeWithText("Soma de verificação: abc").assertExists()
    }

    @Test
    fun confirm_button_writes_exactly_once() {
        val callbacks = Callbacks()
        screen(BackupUiState.Preview(preview()), callbacks)
        composeTestRule.onNodeWithText("Substituir tudo")
            .assertIsDisplayed()
            .assertIsEnabled()
            .invokeClickAction()
        assertEquals(1, callbacks.confirmed)
    }

    @Test
    fun sheet_cancel_does_not_write() {
        val callbacks = Callbacks()
        screen(BackupUiState.Preview(preview()), callbacks)
        // A sheet também oferece "Cancelar"; dispensá-la nunca escreve.
        // (assertExists: a sheet posiciona o último filho fora da dobra em
        // viewports curtos; a ação de clique continua exposta e fiada.)
        composeTestRule.onNodeWithText("Cancelar")
            .assertExists()
            .invokeClickAction()
        assertEquals(0, callbacks.confirmed)
    }

    @Test
    fun preview_cancel_returns_without_writing() {
        val callbacks = Callbacks()
        screen(BackupUiState.Preview(preview()), callbacks)
        composeTestRule.onNodeWithText("Cancelar")
            .assertExists()
            .invokeClickAction()
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
    fun integrity_error_preserves_cause_and_states_data_is_intact() {
        screen(BackupUiState.Error(BackupFailure.ChecksumMismatch("a", "b")))
        composeTestRule.onNodeWithText("Integridade inválida.").assertIsDisplayed()
        // A pessoa precisa saber que o arquivo ruim não estragou o banco.
        composeTestRule.onNodeWithText(
            "Nada foi alterado — seus dados atuais continuam intactos."
        ).assertIsDisplayed()
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
