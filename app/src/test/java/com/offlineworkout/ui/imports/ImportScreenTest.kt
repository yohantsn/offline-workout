package com.offlineworkout.ui.imports

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.offlineworkout.data.model.ExerciseType
import com.offlineworkout.ui.draft.ExerciseFormState
import com.offlineworkout.ui.draft.WorkoutFormState
import com.offlineworkout.viewmodel.WorkoutImportUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xhdpi")
class ImportScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun exercise(name: String = "AGACHAMENTO") = ExerciseFormState(
        clientId = "0-0",
        id = null,
        type = ExerciseType.SERIES,
        nameText = name,
        restSecondsText = "20",
        targetSetsText = "2",
        targetRepsText = "10",
        targetWeightKgText = "",
        targetDurationSecondsText = "",
        targetDistanceMetersText = ""
    )

    private fun review(warnings: List<String> = listOf("Seção 'A': numeração pula 02.")) =
        WorkoutImportUiState.Review(
            workouts = listOf(
                WorkoutFormState("AQUECIMENTO", "AQUECIMENTO/CARDIO", listOf(exercise())),
                WorkoutFormState("TREINOA", "TREINO A", listOf(exercise("SUPINO")))
            ),
            warnings = warnings
        )

    @Test
    fun idle_offers_the_file_picker() {
        var picks = 0
        composeTestRule.setContent {
            ImportScreen(
                state = WorkoutImportUiState.Idle,
                onPickFile = { picks += 1 },
                onCodeChanged = { _, _ -> },
                onNameChanged = { _, _ -> },
                onConfirm = {},
                onChooseAnotherFile = {},
                onClose = {}
            )
        }

        composeTestRule.onNodeWithText("Importar de planilha").assertIsDisplayed()
        composeTestRule.onNode(hasText("Escolher arquivo") and hasClickAction()).performClick()
        assertEquals(1, picks)
    }

    @Test
    fun review_shows_editable_codes_warnings_and_the_confirm_button() {
        var confirms = 0
        composeTestRule.setContent {
            ImportScreen(
                state = review(),
                onPickFile = {},
                onCodeChanged = { _, _ -> },
                onNameChanged = { _, _ -> },
                onConfirm = { confirms += 1 },
                onChooseAnotherFile = {},
                onClose = {}
            )
        }

        composeTestRule.onNodeWithText("Revisar importação").assertIsDisplayed()
        composeTestRule.onNodeWithText("AQUECIMENTO").assertIsDisplayed()
        composeTestRule.onNodeWithText("numeração pula 02", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag(IMPORT_REVIEW_LIST_TAG)
            .performScrollToNode(hasText("Importar 2 treinos"))
        composeTestRule.onNode(hasText("Importar 2 treinos") and hasClickAction()).performClick()
        assertEquals(1, confirms)
    }

    @Test
    fun a_failed_parse_offers_another_file() {
        var picks = 0
        composeTestRule.setContent {
            ImportScreen(
                state = WorkoutImportUiState.Failed(message = "Linha 3: AGACHAMENTO precisa de séries."),
                onPickFile = {},
                onCodeChanged = { _, _ -> },
                onNameChanged = { _, _ -> },
                onConfirm = {},
                onChooseAnotherFile = { picks += 1 },
                onClose = {}
            )
        }

        composeTestRule.onNodeWithText("Linha 3: AGACHAMENTO precisa de séries.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Escolher outro arquivo").performClick()
        assertEquals(1, picks)
    }
}
