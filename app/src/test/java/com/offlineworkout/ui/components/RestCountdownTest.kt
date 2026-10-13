package com.offlineworkout.ui.components

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.offlineworkout.ui.theme.OfflineWorkoutTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// RestCountdown: o status anuncia a entrada (Polite); o número nunca é live
// region (anunciar a cada segundo seria flood).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xhdpi")
class RestCountdownTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun status_announces_entrance_politely_but_number_does_not() {
        composeTestRule.setContent {
            OfflineWorkoutTheme {
                RestCountdown(remainingSeconds = 45, totalSeconds = 60, onCancel = {})
            }
        }
        composeTestRule.onNodeWithText("Descanso").assertIsDisplayed()
        composeTestRule.onNodeWithText("0:45").assertIsDisplayed()
        val selfPolite = SemanticsMatcher("self is polite live region") { node ->
            var ok = false
            for ((key, value) in node.config) {
                if (key == SemanticsProperties.LiveRegion) ok = value == LiveRegionMode.Polite
            }
            ok
        }
        // O status carrega a região polite no próprio nó...
        composeTestRule.onNode(
            hasText("Descanso") and selfPolite
        ).assertExists()
        // ...o número, em si mesmo, não.
        val selfLive = SemanticsMatcher("self has live region") { node ->
            var found = false
            for ((key, _) in node.config) {
                if (key == SemanticsProperties.LiveRegion) found = true
            }
            found
        }
        composeTestRule.onNode(
            hasText("0:45") and selfLive
        ).assertDoesNotExist()
    }
}
