package com.hebi.quickcut.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hebi.quickcut.settings.QuickCutThemeMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Stage2WelcomeInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun onboardingOnlyCompletesOnLastPage() {
        var completions = 0
        var theme by mutableStateOf(QuickCutThemeMode.LIGHT)
        composeRule.setContent {
            QuickCutTheme(modeOverride = theme) {
                QuickCutWelcome(onFinish = { completions++ })
            }
        }
        composeRule.onNodeWithText("다음").assertIsDisplayed()
        Ui07aVisualEvidence.capture("ui07a_light_onboarding.png")
        composeRule.runOnIdle { theme = QuickCutThemeMode.DARK }
        composeRule.onNodeWithText("다음").assertIsDisplayed()
        Ui07aVisualEvidence.capture("ui07a_dark_onboarding.png")
        composeRule.onNodeWithText("다음").performClick()
        composeRule.onNodeWithText("필요한 도구만").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, completions) }
        composeRule.onNodeWithText("다음").performClick()
        composeRule.onNodeWithText("안전하게 저장").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, completions) }
        composeRule.onNodeWithText("시작하기").performClick()
        composeRule.runOnIdle { assertEquals(1, completions) }
    }
}
