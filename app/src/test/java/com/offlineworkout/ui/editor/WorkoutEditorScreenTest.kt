package com.offlineworkout.ui.editor

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.data.repository.RepositoryFailure
import com.offlineworkout.viewmodel.EditorField
import com.offlineworkout.viewmodel.ExerciseFormState
import com.offlineworkout.viewmodel.WorkoutEditorFormState
import com.offlineworkout.viewmodel.WorkoutEditorMode
import com.offlineworkout.viewmodel.WorkoutEditorUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// T4.1: editor screen states, discriminated fields, move affordances, discard
// dialog and callbacks. Stateless: states are constructed directly. No Room.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutEditorScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val noop1: (String) -> Unit = {}
    private val noopExercise: (ExerciseFormState) -> Unit = {}
    private val noopMove: (String, com.offlineworkout.viewmodel.MoveDirection) -> Unit = { _, _ -> }
    private val noopType: (ExerciseType) -> Unit = {}
    private val noop: () -> Unit = {}

    private fun blankForm() = WorkoutEditorFormState(
        mode = WorkoutEditorMode.Create,
        codeText = "",
        nameText = "",
        exercises = emptyList(),
        fieldErrors = emptyMap(),
        isDirty = false
    )

    private fun seriesExercise(clientId: String = "new-1") = ExerciseFormState(
        clientId = clientId, id = null, type = ExerciseType.SERIES,
        nameText = "Squat", restSecondsText = "90",
        targetSetsText = "3", targetRepsText = "10", targetWeightKgText = "",
        targetDurationSecondsText = "", targetDistanceMetersText = ""
    )

    private fun idleScreen(
        state: WorkoutEditorUiState,
        onAddExercise: (ExerciseType) -> Unit = noopType,
        onSave: () -> Unit = noop,
        onConfirmDiscard: () -> Unit = noop,
        onRetry: () -> Unit = noop
    ) {
        composeTestRule.setContent {
            WorkoutEditorScreen(
                state = state,
                onCodeChanged = noop1,
                onWorkoutNameChanged = noop1,
                onAddExercise = onAddExercise,
                onExerciseChanged = noopExercise,
                onMoveExercise = noopMove,
                onSave = onSave,
                onCancel = noop,
                onConfirmDiscard = onConfirmDiscard,
                onDismissDiscard = noop,
                onRetry = onRetry,
                onBack = noop
            )
        }
    }

    @Test
    fun loading_state() {
        idleScreen(WorkoutEditorUiState.Loading)
        composeTestRule.onNodeWithText("Carregando treino…").assertExists()
    }

    @Test
    fun notfound_state() {
        idleScreen(WorkoutEditorUiState.NotFound)
        composeTestRule.onNodeWithText("Treino não encontrado.").assertExists()
        composeTestRule.onNodeWithText("Voltar para treinos").assertExists()
    }

    @Test
    fun create_form_shows_title_fields_and_actions() {
        idleScreen(WorkoutEditorUiState.Ready(blankForm()))
        composeTestRule.onNodeWithText("Novo treino").assertExists()
        composeTestRule.onNodeWithText("Código").assertExists()
        composeTestRule.onNodeWithText("Adicionar exercício").assertExists()
        // ED-1: "Cancelar" redundante com "Voltar" saiu da barra (o voltar
        // segue o fluxo de descarte). Só Salvar como ação.
        composeTestRule.onNodeWithText("Salvar").assertExists()
        composeTestRule.onAllNodesWithText("Cancelar").assertCountEquals(0)
    }

    @Test
    fun add_selector_offers_three_types() {
        val added = mutableListOf<ExerciseType>()
        idleScreen(WorkoutEditorUiState.Ready(blankForm()), onAddExercise = { added.add(it) })
        composeTestRule.onNodeWithText("Adicionar exercício").performClick()
        composeTestRule.onNodeWithText("Séries").performClick()
        composeTestRule.runOnIdle { assertEquals(listOf(ExerciseType.SERIES), added) }
    }

    @Test
    fun series_card_shows_discriminated_fields_with_units() {
        idleScreen(WorkoutEditorUiState.Ready(blankForm().copy(exercises = listOf(seriesExercise()))))
        // Colapsado por padrão: expandir primeiro; mover fica no resumo.
        composeTestRule.onNodeWithContentDescription("Expandir Squat").performClick()
        composeTestRule.onNodeWithText("Séries alvo").assertExists()
        composeTestRule.onNodeWithText("Repetições alvo (reps)").assertExists()
        composeTestRule.onNodeWithText("Peso alvo (kg, opcional)").assertExists()
        composeTestRule.onNodeWithText("Descanso (s)").assertExists()
        composeTestRule.onNodeWithContentDescription("Mover Squat para cima").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Mover Squat para baixo").assertIsNotEnabled()
    }

    @Test
    fun only_one_exercise_expanded_at_a_time() {
        val two = listOf(
            seriesExercise(clientId = "new-1").copy(nameText = "Primeiro"),
            seriesExercise(clientId = "new-2").copy(nameText = "Segundo")
        )
        idleScreen(WorkoutEditorUiState.Ready(blankForm().copy(exercises = two)))
        // Nada expandido: nenhum campo à vista.
        composeTestRule.onAllNodesWithText("Séries alvo", substring = true).assertCountEquals(0)
        composeTestRule.onNodeWithContentDescription("Expandir Primeiro").performClick()
        composeTestRule.onAllNodesWithText("Séries alvo", substring = true).assertCountEquals(1)
        // O segundo card está fora da dobra: rola até ele antes de tocar.
        composeTestRule.onNodeWithTag("editorList").performScrollToNode(hasText("Segundo"))
        // Abrir o segundo colapsa o primeiro.
        composeTestRule.onNodeWithContentDescription("Expandir Segundo").performClick()
        composeTestRule.onAllNodesWithText("Séries alvo", substring = true).assertCountEquals(1)
        composeTestRule.onNodeWithContentDescription("Recolher Segundo").assertExists()
    }

    @Test
    fun timed_card_shows_only_duration() {
        val timed = seriesExercise().copy(
            type = ExerciseType.TIMED, targetSetsText = "", targetRepsText = "",
            targetDurationSecondsText = "60"
        )
        idleScreen(WorkoutEditorUiState.Ready(blankForm().copy(exercises = listOf(timed))))
        composeTestRule.onNodeWithContentDescription("Expandir Squat").performClick()
        composeTestRule.onNodeWithText("Duração alvo (s)").assertExists()
        composeTestRule.onAllNodesWithText("Séries alvo", substring = true).assertCountEquals(0)
    }

    @Test
    fun distance_card_shows_distance_and_duration() {
        val distance = seriesExercise().copy(
            type = ExerciseType.DISTANCE_TIME, targetSetsText = "", targetRepsText = "",
            targetDurationSecondsText = "600", targetDistanceMetersText = "1000"
        )
        idleScreen(WorkoutEditorUiState.Ready(blankForm().copy(exercises = listOf(distance))))
        composeTestRule.onNodeWithContentDescription("Expandir Squat").performClick()
        composeTestRule.onNodeWithText("Distância alvo (m)").assertExists()
        composeTestRule.onNodeWithText("Duração alvo (s)").assertExists()
    }

    @Test
    fun field_errors_are_shown_inline() {
        val form = blankForm().copy(
            fieldErrors = mapOf(
                EditorField.WORKOUT_CODE to "Informe o código",
                EditorField.TYPE_FIELDS to "Séries deve ser inteiro > 0"
            ),
            exercises = listOf(seriesExercise().copy(targetSetsText = ""))
        )
        idleScreen(WorkoutEditorUiState.Ready(form))
        composeTestRule.onNodeWithText("Informe o código").assertExists()
        composeTestRule.onNodeWithContentDescription("Expandir Squat").performClick()
        composeTestRule.onNodeWithText("Séries deve ser inteiro > 0").assertExists()
    }

    @Test
    fun exercise_field_errors_show_below_each_field() {
        val form = blankForm().copy(
            fieldErrors = mapOf(
                EditorField.EXERCISE_NAME to "Informe o nome do exercício",
                EditorField.REST_SECONDS to "Descanso deve ser inteiro >= 0"
            ),
            exercises = listOf(seriesExercise())
        )
        idleScreen(WorkoutEditorUiState.Ready(form))
        composeTestRule.onNodeWithContentDescription("Expandir Squat").performClick()
        // Borda error + mensagem abaixo de cada campo, não bloco no topo.
        composeTestRule.onNodeWithText("Informe o nome do exercício").assertExists()
        composeTestRule.onNodeWithText("Descanso deve ser inteiro >= 0").assertExists()
    }

    @Test
    fun move_buttons_disable_at_the_extremes() {
        val two = listOf(
            seriesExercise(clientId = "new-1").copy(nameText = "Primeiro"),
            seriesExercise(clientId = "new-2").copy(nameText = "Segundo")
        )
        idleScreen(WorkoutEditorUiState.Ready(blankForm().copy(exercises = two)))
        // O segundo card está fora da dobra: rola até ele antes de afirmar.
        composeTestRule.onNodeWithTag("editorList").performScrollToNode(hasText("Segundo"))
        // Primeiro não sobe, último não desce; os demais habilitados.
        composeTestRule.onNodeWithContentDescription("Mover Primeiro para cima").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Mover Primeiro para baixo").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Mover Segundo para cima").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Mover Segundo para baixo").assertIsNotEnabled()
    }

    @Test
    fun saving_disables_save_and_announces() {
        var saved = 0
        idleScreen(
            WorkoutEditorUiState.Saving(blankForm().copy(codeText = "A", nameText = "X")),
            onSave = { saved += 1 }
        )
        composeTestRule.onNodeWithText("Salvando…").assertExists()
        composeTestRule.onNodeWithText("Salvar").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Salvar").performClick()
        composeTestRule.runOnIdle { assertEquals(0, saved) }
    }

    @Test
    fun error_keeps_form_and_offers_retry() {
        var retried = 0
        idleScreen(
            WorkoutEditorUiState.Error(
                blankForm().copy(codeText = "A", nameText = "Kept"),
                RepositoryFailure.Storage("database unavailable")
            ),
            onRetry = { retried += 1 }
        )
        composeTestRule.onNodeWithText("Não foi possível salvar. Verifique os campos.").assertExists()
        composeTestRule.onNodeWithText("Tentar novamente").performClick()
        composeTestRule.runOnIdle { assertEquals(1, retried) }
    }

    @Test
    fun discard_dialog_confirms_without_writing() {
        var discarded = 0
        idleScreen(
            WorkoutEditorUiState.Ready(blankForm().copy(showDiscardConfirmation = true)),
            onConfirmDiscard = { discarded += 1 }
        )
        composeTestRule.onNodeWithText("Descartar alterações?").assertExists()
        composeTestRule.onNodeWithText("Descartar").performClick()
        composeTestRule.onNodeWithText("Continuar editando").assertExists()
        composeTestRule.runOnIdle { assertEquals(1, discarded) }
    }

    @Test
    fun long_form_scrolls_and_save_stays_reachable() {
        // T9.3 (bloqueio 1): with a long form the actions used to be below the
        // viewport with no scrollable container, so "Salvar" was unreachable.
        // phase-4/architecture/index.md:387 required this case.
        val many = (1..8).map { seriesExercise(clientId = "new-$it").copy(nameText = "Exercício $it") }
        var saved = 0
        idleScreen(
            WorkoutEditorUiState.Ready(
                blankForm().copy(
                    codeText = "A",
                    nameText = "Treino longo",
                    exercises = many
                )
            ),
            onSave = { saved += 1 }
        )
        // Salvar mora na TopAppBar (sempre visível): sem rolagem até ele.
        composeTestRule.onNodeWithTag("exercise-new-1").assertExists()
        composeTestRule.onNodeWithText("Salvar").assertIsDisplayed().performClick()
        composeTestRule.runOnIdle { assertEquals(1, saved) }
        // Every exercise is reachable by scrolling, not only the first.
        composeTestRule.onNodeWithTag("editorList").performScrollToNode(hasText("Exercício 8"))
        composeTestRule.onNodeWithTag("exercise-new-8").assertExists()
    }

    @Test
    fun code_field_accepts_text_input() {
        var code = ""
        composeTestRule.setContent {
            WorkoutEditorScreen(
                state = WorkoutEditorUiState.Ready(blankForm().copy(codeText = code)),
                onCodeChanged = { code = it },
                onWorkoutNameChanged = noop1,
                onAddExercise = noopType,
                onExerciseChanged = noopExercise,
                onMoveExercise = noopMove,
                onSave = noop,
                onCancel = noop,
                onConfirmDiscard = noop,
                onDismissDiscard = noop,
                onRetry = noop,
                onBack = noop
            )
        }
        // The label node doubles as the text field entry point.
        composeTestRule.onNodeWithText("Código").performTextInput("A")
        composeTestRule.runOnIdle { assertEquals("A", code) }
    }
}
