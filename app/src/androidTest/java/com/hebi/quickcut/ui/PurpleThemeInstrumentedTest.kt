package com.hebi.quickcut.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression tests for the user's purple design guide. */
class PurpleThemeInstrumentedTest {
    @Test fun purpleThemeTokensMatchReference() {
        assertEquals(Color(0xFF0B0810), CutColors.background)
        assertEquals(Color(0xFF8B5CF6), CutColors.accent)
        assertEquals(Color(0xFF6D28D9), CutColors.brandDeep)
        assertEquals(Color(0xFFF8F6FF), CutColors.primaryText)
    }
}
