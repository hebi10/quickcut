package com.hebi.quickcut.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.settings.QuickTrimSettings
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Preserves actual Compose renderings for visual comparison with the design reference. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class Stage7ReferenceScreensInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun homeAndProjectTabAreCaptured() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp))) {
                QuickCutTheme {
                    StudioStartScreen(
                        onQuick = {}, onSelect = {}, onBatch = {},
                        canRestore = false, onRestore = {},
                        quickSettings = QuickTrimSettings(0L, 0L, false),
                        dateMode = SaveDateMode.ORIGINAL,
                        onPreferences = {}, onLicenses = {},
                    )
                }
            }
        }
        compose.onNodeWithText("+ 영상 편집 시작하기").assertIsDisplayed()
        capture("stage7_home.png")
        compose.onNodeWithText("프로젝트").performClick()
        compose.onNodeWithText("프로젝트 없음").assertIsDisplayed()
        capture("stage7_projects_empty.png")
    }

    @Test fun onboardingIsCaptured() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp))) {
                QuickCutTheme {
                    Box(Modifier.fillMaxSize().background(LocalCutPalette.current.background)) {
                        QuickCutWelcome(onFinish = {})
                    }
                }
            }
        }
        compose.onNodeWithTag("quickcut-onboarding-artwork").assertIsDisplayed()
        compose.onNodeWithText("다음").assertIsDisplayed()
        capture("stage7_onboarding.png")
    }

    @Test fun onboardingIllustrationAndNextActionRemainVisibleWithLargeFont() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.6f)) {
                    QuickCutTheme {
                        QuickCutWelcome(onFinish = {})
                    }
                }
            }
        }
        compose.onNodeWithTag("quickcut-onboarding-artwork").assertIsDisplayed()
        compose.onNodeWithText("다음").assertIsDisplayed()
    }

    private fun capture(name: String) {
        require(name in setOf("stage7_home.png", "stage7_projects_empty.png", "stage7_onboarding.png"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "design-review")
        check(dir.isDirectory || dir.mkdirs())
        val local = File(dir, name)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        local.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        check(local.length() > 0L) { "Reference screenshot was not preserved: $name" }
    }
}
