package com.hebi.quickcut.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Maintains the legacy test class name while validating both neutral color schemes. */
class PurpleThemeInstrumentedTest {
    @Test fun neutralPaletteTokensMatchDesignPlan() {
        assertEquals(Color(0xFFF7F7F7), CutPalettes.Light.background)
        assertEquals(Color(0xFF1C1C1C), CutPalettes.Light.accent)
        assertEquals(Color(0xFFB45B3A), CutPalettes.Light.timelineMarker)
        assertEquals(Color(0xFF121212), CutPalettes.Dark.background)
        assertEquals(Color(0xFFF1F1F1), CutPalettes.Dark.accent)
        assertEquals(Color(0xFFE69B77), CutPalettes.Dark.timelineMarker)
    }

    @Test fun primaryAndSecondaryTextMaintainReadableContrastInBothModes() {
        fun contrast(foreground: Color, background: Color): Float {
            val high = maxOf(foreground.luminance(), background.luminance())
            val low = minOf(foreground.luminance(), background.luminance())
            return (high + 0.05f) / (low + 0.05f)
        }
        for (palette in listOf(CutPalettes.Light, CutPalettes.Dark)) {
            // Neutral surfaces stay grayscale; only intentional status and playhead
            // indicators may use semantic colors.
            for (neutral in listOf(
                palette.background, palette.surface, palette.panel, palette.border,
                palette.primaryText, palette.secondaryText, palette.mutedText,
                palette.accent, palette.onAccent, palette.accentMuted,
                palette.secondaryAccent, palette.brandDeep, palette.brandLight,
            )) {
                val delta = maxOf(
                    kotlin.math.abs(neutral.red - neutral.green),
                    kotlin.math.abs(neutral.green - neutral.blue),
                )
                assertTrue("UI-07-A surface/text token has unwanted color tint: $neutral",
                    delta <= 0.001f)
            }
            assertTrue("Secondary text on panel must meet 4.5:1",
                contrast(palette.secondaryText, palette.panel) >= 4.5f)
            assertTrue("Muted text on surface must meet 4.5:1",
                contrast(palette.mutedText, palette.surface) >= 4.5f)
            assertTrue("Primary action text must meet 4.5:1",
                contrast(palette.onAccent, palette.accent) >= 4.5f)
            assertTrue("Primary text on canvas must meet 4.5:1",
                contrast(palette.primaryText, palette.background) >= 4.5f)
            assertTrue("Secondary text on surface must meet 4.5:1",
                contrast(palette.secondaryText, palette.surface) >= 4.5f)
            assertTrue("Selected label on panel must meet 4.5:1",
                contrast(palette.secondaryAccent, palette.panel) >= 4.5f)
        }
    }
}
