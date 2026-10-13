package com.offlineworkout.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Success/descuido semântico: não existe no ColorScheme do Material 3.
// Fornecido via CompositionLocal, com override para dark.
data class SuccessColors(val success: Color, val successContainer: Color)

val LocalSuccessColors = staticCompositionLocalOf {
    SuccessColors(Color(0xFF059669), Color(0xFFD1FAE5))
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF), onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0D9488), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCFBF1), onSecondaryContainer = Color(0xFF115E59),
    tertiary = Color(0xFFD97706), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEF3C7), onTertiaryContainer = Color(0xFF78350F),
    error = Color(0xFFDC2626), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFF4F4F5), onSurfaceVariant = Color(0xFF52525B),
    outline = Color(0xFFD4D4D8), background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF18181B)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5B4FC), onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF3730A3), onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF5EEAD4), onSecondary = Color(0xFF115E59),
    secondaryContainer = Color(0xFF115E59), onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFFCD34D), onTertiary = Color(0xFF78350F),
    tertiaryContainer = Color(0xFF92400E), onTertiaryContainer = Color(0xFFFEF3C7),
    error = Color(0xFFFCA5A5), onError = Color(0xFF690005),
    errorContainer = Color(0xFF7F1D1D), onErrorContainer = Color(0xFFFEE2E2),
    surface = Color(0xFF18181B), onSurface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFF27272A), onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF52525B), background = Color(0xFF09090B),
    onBackground = Color(0xFFFAFAFA)
)

private val SuccessLight = SuccessColors(Color(0xFF059669), Color(0xFFD1FAE5))
private val SuccessDark = SuccessColors(Color(0xFF34D399), Color(0xFF065F46))

private val TypographyOurs = Typography(
    displayLarge = TextStyle(
        fontSize = 72.sp,
        lineHeight = 80.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum"
    ),
    displaySmall = TextStyle(
        fontSize = 36.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum"
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium
    ),
    labelMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium
    )
)

@Composable
fun OfflineWorkoutTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val successColors = if (darkTheme) SuccessDark else SuccessLight
    CompositionLocalProvider(LocalSuccessColors provides successColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = TypographyOurs,
            shapes = Shapes(
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(16.dp),
                extraLarge = RoundedCornerShape(28.dp)
            ),
            content = content
        )
    }
}
