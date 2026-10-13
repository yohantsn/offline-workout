package com.offlineworkout.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.offlineworkout.ui.theme.OfflineWorkoutTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// R-19: todo alvo interativo dos componentes novos tem 48dp mínimos de
// LARGURA e de altura (o baseline antigo só media altura).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xhdpi")
class ComponentTouchTargetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun assertMinSize(
        text: String,
        minWidth: Dp = 48.dp,
        minHeight: Dp = 48.dp
    ) {
        val node = composeTestRule.onNode(hasText(text) and hasClickAction())
        node.assertExists()
        val density = composeTestRule.density
        val size = node.fetchSemanticsNode().size
        val widthDp = with(density) { size.width.toDp() }
        val heightDp = with(density) { size.height.toDp() }
        assertTrue(
            "\"$text\" width was $widthDp dp (min $minWidth, density $density)",
            widthDp >= minWidth
        )
        assertTrue(
            "\"$text\" height was $heightDp dp (min $minHeight, density $density)",
            heightDp >= minHeight
        )
    }

    @Test
    fun primaryButton_is_48dp_wide_and_64dp_tall() {
        composeTestRule.setContent {
            OfflineWorkoutTheme {
                PrimaryButton(text = "Concluir série 2", onClick = {})
            }
        }
        assertMinSize("Concluir série 2", minWidth = 48.dp, minHeight = 64.dp)
    }

    @Test
    fun primaryButton_loading_is_48dp_wide_and_64dp_tall() {
        composeTestRule.setContent {
            OfflineWorkoutTheme {
                PrimaryButton(text = "Salvando", onClick = {}, loading = true)
            }
        }
        assertMinSize("Salvando", minWidth = 48.dp, minHeight = 64.dp)
    }

    @Test
    fun primaryButton_fullWidth_keeps_48dp_height() {
        composeTestRule.setContent {
            OfflineWorkoutTheme {
                PrimaryButton(
                    text = "Criar treino",
                    onClick = {},
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        assertMinSize("Criar treino", minWidth = 48.dp, minHeight = 64.dp)
    }

    @Test
    fun secondaryButton_is_48dp_wide_and_tall() {
        composeTestRule.setContent {
            OfflineWorkoutTheme {
                SecondaryButton(text = "Concluir exercício", onClick = {})
            }
        }
        assertMinSize("Concluir exercício")
    }

    @Test
    fun appTopBar_back_is_48dp_wide_and_tall() {
        var backed = 0
        composeTestRule.setContent {
            OfflineWorkoutTheme {
                AppTopBar(title = "Treinos", onBack = { backed += 1 })
            }
        }
        assertMinSize("Voltar")
        composeTestRule.onNodeWithText("Voltar").assertExists()
        assertTrue(backed == 0)
    }

    @Test
    fun restCountdown_cancel_is_48dp_wide_and_tall() {
        composeTestRule.setContent {
            OfflineWorkoutTheme {
                RestCountdown(remainingSeconds = 72, totalSeconds = 90, onCancel = {})
            }
        }
        assertMinSize("Cancelar")
        composeTestRule.onNodeWithText("1:12").assertExists()
    }

    @Test
    fun errorCard_recovery_and_secondary_are_48dp_wide_and_tall() {
        composeTestRule.setContent {
            OfflineWorkoutTheme {
                ErrorCard(
                    message = "Não foi possível carregar.",
                    recoveryLabel = "Tentar novamente",
                    onRecovery = {},
                    secondaryLabel = "Voltar",
                    onSecondary = {}
                )
            }
        }
        assertMinSize("Tentar novamente")
        assertMinSize("Voltar")
    }

    @Test
    fun numericField_is_64dp_tall() {
        composeTestRule.setContent {
            OfflineWorkoutTheme {
                NumericField(
                    value = "",
                    onValueChange = {},
                    unit = "kg",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        // Campo de texto (não tem ação de clique): mede altura mínima de 64dp.
        val node = composeTestRule.onNode(hasText("—"))
        node.assertExists()
        val density = composeTestRule.density
        val heightDp = with(density) { node.fetchSemanticsNode().size.height.toDp() }
        assertTrue(
            "NumericField height was $heightDp dp (min 64.dp, density $density)",
            heightDp >= 64.dp
        )
    }

    @Test
    fun components_render_without_crash() {
        composeTestRule.setContent {
            OfflineWorkoutTheme {
                StatTile(label = "Sessões", value = "18")
                StatTile(label = "Última carga", value = "65", unit = "kg", emphasis = StatTileEmphasis.Up)
                TypeBadge(tone = TypeBadgeTone.Series, label = "Séries")
                TypeBadge(tone = TypeBadgeTone.Time, label = "Tempo")
                TypeBadge(tone = TypeBadgeTone.DistanceTime, label = "Distância/Tempo")
                CodeChip(code = "A")
                StepDots(currentIndex = 1, total = 4, completedCount = 1)
                LoadingSkeleton(lines = 2)
            }
        }
        composeTestRule.onNodeWithText("18").assertExists()
        composeTestRule.onNodeWithText("Séries").assertExists()
        composeTestRule.onNodeWithText("A").assertExists()
        composeTestRule.waitForIdle()
    }
}
