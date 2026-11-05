package com.hebi.quickcut.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the user's purple design guide. */
class PurpleThemeInstrumentedTest {
    @Test fun purpleThemeTokensMatchReference() {
        assertEquals(Color(0xFF0F0B1A), CutColors.background)
        assertEquals(Color(0xFF7C5AED), CutColors.accent)
        assertEquals(Color(0xFF6D28D9), CutColors.brandDeep)
        assertEquals(Color(0xFFF8F6FF), CutColors.primaryText)
    }

    @Test fun actionAndSecondaryTextMaintainReadableContrast() {
        fun contrast(foreground: Color, background: Color): Float {
            val high = maxOf(foreground.luminance(), background.luminance())
            val low = minOf(foreground.luminance(), background.luminance())
            return (high + 0.05f) / (low + 0.05f)
        }

        assertTrue("Primary action text must reach WCAG AA (4.5:1)",
            contrast(CutColors.onAccent, CutColors.accent) >= 4.5f)
        assertTrue("Small purple text on a dark panel must reach WCAG AA (4.5:1)",
            contrast(CutColors.secondaryAccent, CutColors.panel) >= 4.5f)
    }
}
