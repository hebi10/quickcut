package com.hebi.quickcut.ui

import androidx.activity.ComponentActivity
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
        composeRule.setContent {
            QuickCutTheme {
                QuickCutWelcome(onFinish = { completions++ })
            }
        }
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
