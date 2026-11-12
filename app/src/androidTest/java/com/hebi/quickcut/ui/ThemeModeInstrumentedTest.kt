package com.hebi.quickcut.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.settings.QuickCutThemeMode
import com.hebi.quickcut.settings.QuickTrimSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeModeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun lightAndDarkHomeRenderRealDistinctScreenshots() {
        var mode by mutableStateOf(QuickCutThemeMode.LIGHT)
        compose.setContent {
            QuickCutTheme(modeOverride = mode) {
                StudioStartScreen(
                    onQuick = {}, onSelect = {}, onBatch = {},
                    canRestore = false, onRestore = {},
                    quickSettings = QuickTrimSettings(), dateMode = SaveDateMode.ORIGINAL,
                    onPreferences = {}, onLicenses = {},
                    themeMode = mode, onThemeModeChange = { mode = it },
                )
            }
        }
        compose.onNodeWithText("+ 영상 편집 시작하기").assertIsDisplayed()
        capture("theme_light_home.png", light = true)
        captureUi03aTabs("light", light = true)
        compose.runOnIdle { mode = QuickCutThemeMode.DARK }
        compose.onNodeWithText("+ 영상 편집 시작하기").assertIsDisplayed()
        capture("theme_dark_home.png", light = false)
        captureUi03aTabs("dark", light = false)
    }

    @Test fun selectorSwitchesFromSystemToExplicitModes() {
        var mode by mutableStateOf(QuickCutThemeMode.SYSTEM)
        compose.setContent {
            QuickCutTheme(modeOverride = mode) {
                StudioStartScreen(
                    onQuick = {}, onSelect = {}, onBatch = {},
                    canRestore = false, onRestore = {},
                    quickSettings = QuickTrimSettings(), dateMode = SaveDateMode.ORIGINAL,
                    onPreferences = {}, onLicenses = {},
                    themeMode = mode, onThemeModeChange = { mode = it },
                )
            }
        }
        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("시스템 설정").assertIsDisplayed().performClick()
        compose.onNodeWithText("다크").performClick()
        compose.runOnIdle { assertEquals(QuickCutThemeMode.DARK, mode) }
        compose.onNodeWithText("다크").assertIsDisplayed().performClick()
        compose.onNodeWithText("라이트").performClick()
        compose.runOnIdle { assertEquals(QuickCutThemeMode.LIGHT, mode) }
    }

    @Test fun storedThemeModeDoesNotChangeSavedOriginalHandling() {
        val settings = QuickCutSettings(InstrumentationRegistry.getInstrumentation().targetContext)
        val previous = settings.loadThemeMode()
        val original = settings.loadOriginalAction()
        val quick = settings.loadQuick()
        val date = settings.loadSaveDate()
        val batch = settings.loadBatch()
        try {
            settings.saveThemeMode(QuickCutThemeMode.DARK)
            assertEquals(QuickCutThemeMode.DARK, settings.loadThemeMode())
            settings.saveThemeMode(QuickCutThemeMode.LIGHT)
            assertEquals(QuickCutThemeMode.LIGHT, settings.loadThemeMode())
            settings.saveThemeMode(QuickCutThemeMode.SYSTEM)
            assertEquals(QuickCutThemeMode.SYSTEM, settings.loadThemeMode())
            assertEquals(original, settings.loadOriginalAction())
            assertEquals(quick, settings.loadQuick())
            assertEquals(date, settings.loadSaveDate())
            assertEquals(batch, settings.loadBatch())
        } finally {
            settings.saveThemeMode(previous)
        }
    }

    private fun captureUi03aTabs(theme: String, light: Boolean) {
        capture("ui03a_" + theme + "_home.png", light)

        compose.onNodeWithText("프로젝트").performClick()
        compose.onNodeWithText("프로젝트 없음").assertIsDisplayed()
        capture("ui03a_" + theme + "_projects.png", light)

        compose.onNodeWithText("도구").performClick()
        compose.onNodeWithText("편집 도구").assertIsDisplayed()
        capture("ui03a_" + theme + "_tools.png", light)

        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithText("마지막 사용 설정").assertExists()
        capture("ui03a_" + theme + "_settings.png", light)

        compose.onNodeWithText("홈").performClick()
    }

    private fun capture(name: String, light: Boolean) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val pixel = bitmap.getPixel(8, 8)
        val brightness = (AndroidColor.red(pixel) + AndroidColor.green(pixel) +
            AndroidColor.blue(pixel)) / 3
        check(if (light) brightness > 180 else brightness < 80) {
            "Real Android theme screenshot did not match selected mode: $name, brightness=$brightness"
        }
        val folder = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "design-review",
        )
        check(folder.isDirectory || folder.mkdirs())
        val file = File(folder, name)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        check(file.length() > 100)
        bitmap.recycle()
    }
}
