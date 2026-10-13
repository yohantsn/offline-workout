package com.offlineworkout.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

// Contraste WCAG dos pares da spec §2.1, com luminância real calculada dos
// hex. Texto normal: 4.5:1. Texto grande/gráfico: 3:1.
//
// ACHADOS REPORTADOS (não corrigidos — a spec manda não mudar a cor):
// - light onSecondary/secondary (3.75) e onTertiary/tertiary (3.19) ficam
//   ABAIXO de 4.5:1. Nos componentes novos, branco sobre secondary/tertiary
//   nunca é texto normal (só texto grande ou reforço com texto presente).
// - success sobre superfície clara (~3.4–3.8) só serve como gráfico (dots)
//   ou texto grande (StatTile 36sp SemiBold), nunca corpo de texto.
class ThemeContrastTest {

    private fun luminance(hex: Long): Double {
        fun channel(bits: Int): Double {
            val c = ((hex shr bits) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun ratio(foreground: Long, background: Long): Double {
        val l1 = luminance(foreground)
        val l2 = luminance(background)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun assertNormalText(name: String, fg: Long, bg: Long) {
        val r = ratio(fg, bg)
        assertTrue("$name contrast was $r, need >= 4.5", r >= 4.5)
    }

    private fun assertLargeOrGraphic(name: String, fg: Long, bg: Long) {
        val r = ratio(fg, bg)
        assertTrue("$name contrast was $r, need >= 3.0", r >= 3.0)
    }

    // ---- light: texto normal 4.5:1 ----

    @Test
    fun light_onPrimary_on_primary() =
        assertNormalText("light onPrimary/primary", 0xFFFFFFFF, 0xFF4F46E5)

    @Test
    fun light_onPrimaryContainer_on_primaryContainer() =
        assertNormalText("light onPrimaryContainer/primaryContainer", 0xFF1E1B4B, 0xFFE0E7FF)

    @Test
    fun light_onSecondaryContainer_on_secondaryContainer() =
        assertNormalText("light onSecondaryContainer/secondaryContainer", 0xFF115E59, 0xFFCCFBF1)

    @Test
    fun light_onTertiaryContainer_on_tertiaryContainer() =
        assertNormalText("light onTertiaryContainer/tertiaryContainer", 0xFF78350F, 0xFFFEF3C7)

    @Test
    fun light_onError_on_error() =
        assertNormalText("light onError/error", 0xFFFFFFFF, 0xFFDC2626)

    @Test
    fun light_onErrorContainer_on_errorContainer() =
        assertNormalText("light onErrorContainer/errorContainer", 0xFF7F1D1D, 0xFFFEE2E2)

    @Test
    fun light_onSurface_on_surface() =
        assertNormalText("light onSurface/surface", 0xFF18181B, 0xFFFFFFFF)

    @Test
    fun light_onSurfaceVariant_on_surfaceVariant() =
        assertNormalText("light onSurfaceVariant/surfaceVariant", 0xFF52525B, 0xFFF4F4F5)

    @Test
    fun light_onBackground_on_background() =
        assertNormalText("light onBackground/background", 0xFF18181B, 0xFFFAFAFA)

    @Test
    fun light_successText_on_successContainer() =
        assertNormalText("light success text/successContainer", 0xFF065F46, 0xFFD1FAE5)

    @Test
    fun light_onErrorContainer_on_surface() =
        // B-1: texto do botão importar contornado sobre o card claro.
        assertNormalText("light onErrorContainer/surface", 0xFF7F1D1D, 0xFFFFFFFF)

    // ---- light: 3:1 (texto grande/gráfico) + reporte < 4.5 ----

    @Test
    fun light_onSecondary_on_secondary_passes_3_but_is_reported_below_45() {
        // Trava documentada (NÃO tautologia decorativa): a spec manda manter
        // a cor, então este teste QUEBRA se alguém "consertar" o token para
        // cima (sai da faixa) ou se um uso novo exigir 4.5. Faixa travada:
        // serve como gráfico/texto grande (≥3.0), nunca como texto normal.
        val r = ratio(0xFFFFFFFF, 0xFF0D9488)
        assertTrue("expected >= 3.0, was $r", r >= 3.0)
        assertTrue("REPORTED: light onSecondary/secondary is $r (< 4.5), spec color kept", r < 4.5)
    }

    @Test
    fun light_onTertiary_on_tertiary_passes_3_but_is_reported_below_45() {
        // Idem acima: trava a cor da spec na faixa [3.0, 4.5).
        val r = ratio(0xFFFFFFFF, 0xFFD97706)
        assertTrue("expected >= 3.0, was $r", r >= 3.0)
        assertTrue("REPORTED: light onTertiary/tertiary is $r (< 4.5), spec color kept", r < 4.5)
    }

    @Test
    fun light_success_on_surface_passes_3_as_graphic_only() {
        // Dots do StepDots e valor grande do StatTile; nunca corpo de texto.
        assertLargeOrGraphic("light success/surface", 0xFF059669, 0xFFFFFFFF)
        assertLargeOrGraphic("light success/surfaceVariant", 0xFF059669, 0xFFF4F4F5)
    }

    // ---- dark: texto normal 4.5:1 ----

    @Test
    fun dark_onPrimary_on_primary() =
        assertNormalText("dark onPrimary/primary", 0xFF1E1B4B, 0xFFA5B4FC)

    @Test
    fun dark_onPrimaryContainer_on_primaryContainer() =
        assertNormalText("dark onPrimaryContainer/primaryContainer", 0xFFE0E7FF, 0xFF3730A3)

    @Test
    fun dark_onSecondary_on_secondary() =
        assertNormalText("dark onSecondary/secondary", 0xFF115E59, 0xFF5EEAD4)

    @Test
    fun dark_onSecondaryContainer_on_secondaryContainer() =
        assertNormalText("dark onSecondaryContainer/secondaryContainer", 0xFFCCFBF1, 0xFF115E59)

    @Test
    fun dark_onTertiary_on_tertiary() =
        assertNormalText("dark onTertiary/tertiary", 0xFF78350F, 0xFFFCD34D)

    @Test
    fun dark_onTertiaryContainer_on_tertiaryContainer() =
        assertNormalText("dark onTertiaryContainer/tertiaryContainer", 0xFFFEF3C7, 0xFF92400E)

    @Test
    fun dark_onError_on_error() =
        assertNormalText("dark onError/error", 0xFF690005, 0xFFFCA5A5)

    @Test
    fun dark_onErrorContainer_on_errorContainer() =
        assertNormalText("dark onErrorContainer/errorContainer", 0xFFFEE2E2, 0xFF7F1D1D)

    @Test
    fun dark_onSurface_on_surface() =
        assertNormalText("dark onSurface/surface", 0xFFFAFAFA, 0xFF18181B)

    @Test
    fun dark_onSurfaceVariant_on_surfaceVariant() =
        assertNormalText("dark onSurfaceVariant/surfaceVariant", 0xFFA1A1AA, 0xFF27272A)

    @Test
    fun dark_onBackground_on_background() =
        assertNormalText("dark onBackground/background", 0xFFFAFAFA, 0xFF09090B)

    @Test
    fun dark_success_on_surfaceVariant() =
        assertNormalText("dark success/surfaceVariant", 0xFF34D399, 0xFF27272A)

    @Test
    fun dark_onSuccessContainer_on_successContainer() =
        // B-3: título do bloco de sucesso (par cruzado entre temas).
        assertNormalText("dark onSuccessContainer/successContainer", 0xFFD1FAE5, 0xFF065F46)

    @Test
    fun dark_onErrorContainer_on_surface() =
        assertNormalText("dark onErrorContainer/surface", 0xFFFEE2E2, 0xFF18181B)

    // ---- dark: 3:1 (gráfico) ----

    @Test
    fun dark_success_on_surface_passes_3_as_graphic() =
        assertLargeOrGraphic("dark success/surface", 0xFF34D399, 0xFF18181B)
}
