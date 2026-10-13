package com.offlineworkout.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.offlineworkout.ui.theme.OfflineWorkoutTheme

// Fecha R-14: OfflineWorkoutTheme sem parâmetro segue o sistema via
// isSystemInDarkTheme() — light com +notnight, dark com +night.
private fun captureScheme(
    rule: androidx.compose.ui.test.junit4.ComposeContentTestRule,
    content: @Composable () -> Unit
): ColorScheme {
    var captured: ColorScheme? = null
    rule.setContent {
        OfflineWorkoutTheme {
            content()
            captured = MaterialTheme.colorScheme
        }
    }
    rule.runOnIdle { checkNotNull(captured) { "scheme was not captured" } }
    return captured!!
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "notnight")
class ThemeLightModeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun default_theme_uses_light_scheme_when_system_is_light() {
        val scheme = captureScheme(composeTestRule) {}
        assertEquals(Color(0xFFFFFFFF), scheme.surface)
        assertEquals(Color(0xFF4F46E5), scheme.primary)
        assertEquals(Color(0xFF18181B), scheme.onSurface)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "night")
class ThemeDarkModeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun default_theme_uses_dark_scheme_when_system_is_dark() {
        val scheme = captureScheme(composeTestRule) {}
        assertEquals(Color(0xFF18181B), scheme.surface)
        assertEquals(Color(0xFFA5B4FC), scheme.primary)
        assertEquals(Color(0xFFFAFAFA), scheme.onSurface)
    }
}
