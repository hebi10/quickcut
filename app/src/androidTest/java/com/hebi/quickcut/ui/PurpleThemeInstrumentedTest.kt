package com.hebi.quickcut.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Maintains the legacy test class name while validating both neutral color schemes. */
class PurpleThemeInstrumentedTest {
    @Test fun neutralPaletteTokensMatchDesignPlan() {
        assertEquals(Color(0xFFF6F6F4), CutPalettes.Light.background)
        assertEquals(Color(0xFF1C1E20), CutPalettes.Light.accent)
        assertEquals(Color(0xFFB45B3A), CutPalettes.Light.timelineMarker)
        assertEquals(Color(0xFF111315), CutPalettes.Dark.background)
        assertEquals(Color(0xFFF1F1EF), CutPalettes.Dark.accent)
        assertEquals(Color(0xFFE69B77), CutPalettes.Dark.timelineMarker)
    }

    @Test fun primaryAndSecondaryTextMaintainReadableContrastInBothModes() {
        fun contrast(foreground: Color, background: Color): Float {
            val high = maxOf(foreground.luminance(), background.luminance())
            val low = minOf(foreground.luminance(), background.luminance())
            return (high + 0.05f) / (low + 0.05f)
        }
        for (palette in listOf(CutPalettes.Light, CutPalettes.Dark)) {
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
