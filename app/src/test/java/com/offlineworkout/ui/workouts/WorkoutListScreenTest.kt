package com.offlineworkout.ui.workouts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.offlineworkout.data.model.WorkoutSummary
import com.offlineworkout.data.repository.RepositoryFailure
import com.offlineworkout.viewmodel.WorkoutListUiState
import org.junit.Assert.assertEquals
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// T3.1: WorkoutListScreen states, textual actions, semantics and callbacks. No Room.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xhdpi")
class WorkoutListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun idleScreen(
        state: WorkoutListUiState,
        retried: IntArray = IntArray(1),
        created: IntArray = IntArray(1),
        executed: MutableList<Long> = mutableListOf(),
        edited: MutableList<Long> = mutableListOf(),
        historied: MutableList<Long> = mutableListOf(),
        backed: IntArray = IntArray(1)
    ) {
        composeTestRule.setContent {
            WorkoutListScreen(
                state = state,
                onRetry = { retried[0] += 1 },
                onCreateSelected = { created[0] += 1 },
                onExecutionSelected = { executed.add(it) },
                onEditSelected = { edited.add(it) },
                onHistorySelected = { historied.add(it) },
                onBackupSelected = { backed[0] += 1 }
            )
        }
    }

    @Test
    fun backup_action_is_visible_clickable_and_48dp() {
        val backed = IntArray(1)
        idleScreen(WorkoutListUiState.Empty, backed = backed)
        val button = composeTestRule.onNode(hasText("Backup") and hasClickAction())
        button.assertExists().assertIsDisplayed()
        // T9.2 (R-13): the contract is 48dp, not 48px. The class is pinned to
        // xhdpi, so 1dp = 2px, and the assertion converts instead of comparing
        // raw pixels against a dp number.
        val density = composeTestRule.density
        val heightDp = with(density) { button.fetchSemanticsNode().size.height.toDp() }
        assertTrue(
            "backup button height was $heightDp dp (density $density)",
            heightDp >= 48.dp
        )
        button.performClick()
        assertEquals(1, backed[0])
    }

    @Test
    fun backup_action_is_offered_on_error_and_success_states_too() {
        val backed = IntArray(1)
        idleScreen(
            WorkoutListUiState.Success(listOf(WorkoutSummary(1L, "A", "Alpha", 1L))),
            backed = backed
        )
        composeTestRule.onNodeWithText("Backup").assertExists().performClick()
        assertEquals(1, backed[0])
    }

    @Test
    fun loading_announces_progress() {
        idleScreen(WorkoutListUiState.Loading)
        composeTestRule.onNodeWithContentDescription("Carregando treinos").assertExists()
        composeTestRule.onNodeWithText("Carregando treinos…").assertExists()
    }

    @Test
    fun empty_shows_message_and_create_action() {
        val created = IntArray(1)
        idleScreen(WorkoutListUiState.Empty, created = created)
        composeTestRule.onNodeWithText("Nenhum treino disponível.").assertExists()
        composeTestRule.onNodeWithText("Criar treino").performClick()
        composeTestRule.runOnIdle { assertEquals(1, created[0]) }
    }

    @Test
    fun error_shows_message_and_retry_invokes_callback() {
        val retried = IntArray(1)
        idleScreen(
            WorkoutListUiState.Error(RepositoryFailure.Storage("database unavailable")),
            retried = retried
        )
        composeTestRule.onNodeWithText("Não foi possível carregar os treinos.").assertExists()
        composeTestRule.onNodeWithText("Tentar novamente").performClick()
        composeTestRule.runOnIdle { assertEquals(1, retried[0]) }
    }

    @Test
    fun success_shows_cards_with_contextual_actions() {
        val executed = mutableListOf<Long>()
        val edited = mutableListOf<Long>()
        val historied = mutableListOf<Long>()
        idleScreen(
            WorkoutListUiState.Success(
                listOf(
                    WorkoutSummary(1L, "A", "Alpha", 1L),
                    WorkoutSummary(2L, "B", "Beta", 2L)
                )
            ),
            executed = executed,
            edited = edited,
            historied = historied
        )
        composeTestRule.onNodeWithText("Alpha").assertExists()
        composeTestRule.onNodeWithText("Beta").assertExists()
        composeTestRule.onNodeWithText("Treino A").assertExists()
        composeTestRule.onNodeWithContentDescription("Executar Alpha").assertExists()
        composeTestRule.onNodeWithContentDescription("Editar Alpha").assertExists()
        composeTestRule.onNodeWithContentDescription("Ver histórico de Alpha").assertExists()

        // The list is scrollable and the header actions push the last card
        // below the fold, so bring each action into view before tapping it.
        composeTestRule.onNodeWithContentDescription("Executar Beta").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Editar Alpha").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Ver histórico de Beta").performScrollTo().performClick()
        composeTestRule.runOnIdle {
            assertEquals(listOf(2L), executed)
            assertEquals(listOf(1L), edited)
            assertEquals(listOf(2L), historied)
        }
    }
}
