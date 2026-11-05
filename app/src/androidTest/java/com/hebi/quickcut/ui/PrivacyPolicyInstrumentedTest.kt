package com.hebi.quickcut.ui

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.QuickTrimSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PrivacyPolicyInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun privacyCanBeReadOfflineAndClosedFromHome() {
        val arguments = InstrumentationRegistry.getArguments()
        val configuration = compose.activity.resources.configuration
        val expectedWidth = arguments.getString("quickcutExpectedWidthDp")
        val expectedFont = arguments.getString("quickcutExpectedFontScale")
        if (expectedWidth != null || expectedFont != null) {
            assertEquals("Actual privacy Activity width", requireNotNull(expectedWidth).toInt(),
                configuration.screenWidthDp)
            assertEquals("Actual privacy Activity font scale", requireNotNull(expectedFont).toFloat(),
                configuration.fontScale, 0.01f)
            assertEquals("Actual privacy Activity density", 320, configuration.densityDpi)
        }
        Log.i("PrivacyPolicyTest", "stage=${arguments.getString("quickcutGeometryStage") ?: "default"} " +
            "screenWidthDp=${configuration.screenWidthDp} fontScale=${configuration.fontScale} " +
            "densityDpi=${configuration.densityDpi}")
        compose.setContent {
            QuickCutTheme {
                StudioStartScreen(
                    onQuick = {}, onSelect = {}, onBatch = {}, canRestore = false,
                    onRestore = {}, quickSettings = QuickTrimSettings(),
                    dateMode = SaveDateMode.ORIGINAL, onPreferences = {}, onLicenses = {},
                )
            }
        }

        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("개인정보처리방침").performScrollTo().performClick()
        compose.onNodeWithText("시행일: 2026년 11월 2일", substring = true).assertIsDisplayed()
        compose.onNodeWithText("asdlkj0104@gmail.com", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("https://github.com/hebi10/quickcut/releases", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("닫기").assertIsDisplayed().assertIsEnabled().performClick()

        compose.onNodeWithText("시행일: 2026년 11월 2일", substring = true).assertDoesNotExist()
        compose.onNodeWithText("개인정보처리방침").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("오픈소스 라이선스").performScrollTo().assertIsDisplayed()
    }
}
