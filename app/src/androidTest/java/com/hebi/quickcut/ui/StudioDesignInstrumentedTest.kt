package com.hebi.quickcut.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.QuickTrimSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class StudioDesignInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun smallPhoneHomeAndTabsStayAccessible() {
        showHome(320, 640, 1f, canRestore = true)
        composeRule.onNodeWithText("+ 영상 편집 시작하기").assertIsDisplayed()
        composeRule.onNodeWithText("전체 보기 >").assertIsDisplayed()
        composeRule.onNodeWithText("도구").performClick()
        composeRule.onNodeWithText("편집 도구").assertIsDisplayed()
        composeRule.onNodeWithText("설정").performClick()
        composeRule.onNodeWithText("마지막 사용 설정").assertExists()
    }

    @Test fun largeFontOnSmallPhoneCanReachSettings() {
        showHome(320, 720, 2f, canRestore = false)
        composeRule.onNodeWithText("설정").performClick()
        composeRule.onNodeWithText("마지막 사용 설정").assertExists()
        composeRule.onNodeWithText("설정 변경")
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    @Test fun expandedFoldCanOpenEveryEditorMode() {
        val events = mutableListOf<String>()
        showHome(840, 720, 1f, canRestore = false, events = events)
        composeRule.onNodeWithText("도구").performClick()
        listOf("빠른 자르기", "일괄 편집", "상세 편집").forEach { label ->
            composeRule.onNode(hasText(label) and hasClickAction())
                .performScrollTo().assertIsDisplayed().performClick()
        }
        composeRule.runOnIdle {
            assertEquals(listOf("quick", "batch", "detailed"), events)
        }
    }

    private fun showHome(
        width: Int, height: Int, fontScale: Float, canRestore: Boolean,
        events: MutableList<String> = mutableListOf(),
    ) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                    QuickCutTheme {
                        Box(Modifier.fillMaxSize().background(CutColors.background)) {
                            StudioStartScreen(
                                onQuick = { events += "quick" },
                                onSelect = { events += "detailed" },
                                onBatch = { events += "batch" },
                                canRestore = canRestore,
                                onRestore = { events += "restore" },
                                quickSettings = QuickTrimSettings(4_000L, 1_500L, true),
                                dateMode = SaveDateMode.ORIGINAL,
                                onPreferences = { events += "preferences" },
                                onLicenses = { events += "licenses" },
                            )
                        }
                    }
                }
            }
        }
    }
}
