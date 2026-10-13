package com.offlineworkout.ui.workouts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

// T3.1 + redesign lote 3: card inteiro executa (cd no card), skeleton,
// ErrorCard, empty com CTA. Cobertura de comportamento, não de nó.
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
        // T9.2 (R-13): o contrato é 48dp, não 48px. Largura E altura (R-19).
        val density = composeTestRule.density
        val size = button.fetchSemanticsNode().size
        val widthDp = with(density) { size.width.toDp() }
        val heightDp = with(density) { size.height.toDp() }
        assertTrue(
            "backup button width was $widthDp dp (density $density)",
            widthDp >= 48.dp
        )
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
    fun loading_announces_progress_with_skeleton() {
        idleScreen(WorkoutListUiState.Loading)
        composeTestRule.onNodeWithContentDescription("Carregando treinos").assertExists()
        // Skeleton no lugar do spinner: nenhum conteúdo, nenhum CTA, nenhum erro.
        composeTestRule.onAllNodesWithText("Criar treino").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Tentar novamente").assertCountEquals(0)
    }

    @Test
    fun empty_shows_message_and_create_action() {
        val created = IntArray(1)
        idleScreen(WorkoutListUiState.Empty, created = created)
        // A-1: ilustração do wireframe acima do título (com descrição textual).
        composeTestRule.onNodeWithContentDescription("Ilustração: pessoa levantando peso")
            .assertExists()
        composeTestRule.onNodeWithText("Nenhum treino ainda").assertExists()
        composeTestRule.onNodeWithText("Crie seu primeiro treino para começar a registrar séries.")
            .assertExists()
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
        composeTestRule.onNodeWithText("A").assertExists()
        composeTestRule.onNodeWithContentDescription("Executar Alpha").assertExists()
        composeTestRule.onNodeWithContentDescription("Editar Alpha").assertExists()
        composeTestRule.onNodeWithContentDescription("Ver histórico de Alpha").assertExists()

        // O card inteiro executa: o clique no cd dispara onExecutionSelected.
        composeTestRule.onNodeWithContentDescription("Executar Beta").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Editar Alpha").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Ver histórico de Beta").performScrollTo().performClick()
        composeTestRule.runOnIdle {
            assertEquals(listOf(2L), executed)
            assertEquals(listOf(1L), edited)
            assertEquals(listOf(2L), historied)
        }
    }

    @Test
    fun success_shows_exercise_count_and_rest_minutes() {
        idleScreen(
            WorkoutListUiState.Success(
                listOf(
                    WorkoutSummary(1L, "A", "Alpha", 1L, exerciseCount = 2, totalRestSeconds = 90)
                )
            )
        )
        // B-6: 90 s arredonda para "2 min", não trunca para "1 min".
        composeTestRule.onNodeWithText("2 exercícios · 2 min de descanso").assertExists()
    }

    @Test
    fun rest_seconds_below_a_minute_show_seconds() {
        idleScreen(
            WorkoutListUiState.Success(
                listOf(
                    WorkoutSummary(1L, "A", "Alpha", 1L, exerciseCount = 1, totalRestSeconds = 45)
                )
            )
        )
        composeTestRule.onNodeWithText("1 exercício · 45 s de descanso").assertExists()
    }

    @Test
    fun zero_rest_shows_no_rest_instead_of_zero() {
        idleScreen(
            WorkoutListUiState.Success(
                listOf(WorkoutSummary(1L, "A", "Alpha", 1L))
            )
        )
        composeTestRule.onNodeWithText("sem descanso", substring = true).assertExists()
    }

    @Test
    fun secondary_action_does_not_trigger_execution() {
        val executed = mutableListOf<Long>()
        val edited = mutableListOf<Long>()
        idleScreen(
            WorkoutListUiState.Success(listOf(WorkoutSummary(1L, "A", "Alpha", 1L))),
            executed = executed,
            edited = edited
        )
        // A ação filha para a propagação: editar não executa.
        composeTestRule.onNodeWithContentDescription("Editar Alpha").performClick()
        composeTestRule.runOnIdle {
            assertEquals(listOf(1L), edited)
            assertTrue("card click must not fire on secondary action", executed.isEmpty())
        }
    }

    @Test
    fun card_meets_minimum_touch_size() {
        idleScreen(
            WorkoutListUiState.Success(listOf(WorkoutSummary(1L, "A", "Alpha", 1L)))
        )
        // R-19 nos dois eixos. Só a altura é afirmada: a largura do card é
        // fillMaxWidth por desenho (afirmá-la seria tautológico).
        val card = composeTestRule.onNodeWithContentDescription("Executar Alpha")
        card.assertExists()
        val density = composeTestRule.density
        val heightDp = with(density) { card.fetchSemanticsNode().size.height.toDp() }
        assertTrue("card height was $heightDp dp", heightDp >= 48.dp)
    }
}
