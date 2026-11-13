package com.hebi.quickcut.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hebi.quickcut.settings.QuickCutThemeMode
import android.graphics.Bitmap
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
class ExportStage5InstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun progressScreenShowsRealStageAndCancelableAction() {
        var cancels = 0
        var theme by mutableStateOf(QuickCutThemeMode.LIGHT)
        compose.setContent {
            QuickCutTheme(modeOverride = theme) {
                CenterStatus(
                    title = "영상 저장 중",
                    subtitle = "원본 처리 단계입니다.",
                    progress = 45,
                    onCancel = { cancels++ },
                )
            }
        }
        compose.onNodeWithText("45%").assertIsDisplayed()
        compose.onNodeWithTag("ui05b-export-stage").assertIsDisplayed()
        compose.onNodeWithTag("ui05b-export-progress").assertIsDisplayed()
        capture("ui05b_export_progress.png")
        Ui07aVisualEvidence.capture("ui07a_light_progress.png")
        compose.runOnIdle { theme = QuickCutThemeMode.DARK }
        compose.onNodeWithText("45%").assertIsDisplayed()
        Ui07aVisualEvidence.capture("ui07a_dark_progress.png")
        compose.onNodeWithText("취소").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
    }

    @Test fun finalizingStateHasNoCancelAction() {
        compose.setContent {
            QuickCutTheme {
                CenterStatus("저장 확인 중", "완료를 확인하고 있습니다.", null, null)
            }
        }
        compose.onNodeWithText("처리 중").assertIsDisplayed()
        compose.onNodeWithTag("ui05b-export-progress").assertIsDisplayed()
        compose.onNodeWithText("취소").assertDoesNotExist()
        capture("ui05b_export_finalizing.png")
    }

    private fun capture(name: String) {
        require(name in setOf("ui05b_export_progress.png", "ui05b_export_finalizing.png"))
        compose.waitForIdle()
        val instrument = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrument.targetContext.filesDir, "design-review")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = stableScreenshot(name)
        try {
            File(directory, name).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        check(File(directory, name).length() > 0L)
    }

    private fun stableScreenshot(name: String): Bitmap {
        val instrument = InstrumentationRegistry.getInstrumentation()
        var colorsSeen = 0
        for (attempt in 0 until 8) {
            compose.waitForIdle()
            Thread.sleep(if (attempt == 0) 350L else 220L)
            val screenshot = instrument.uiAutomation.takeScreenshot() ?: continue
            if (screenshot.width < 200 || screenshot.height < 200) {
                screenshot.recycle()
                continue
            }
            val visibleColors = HashSet<Int>()
            for (row in 9..42) for (column in 4..28) {
                val pixel = screenshot.getPixel(
                    screenshot.width * column / 32,
                    screenshot.height * row / 50,
                ) and 0x00FFFFFF
                visibleColors.add(pixel)
            }
            colorsSeen = visibleColors.size
            if (colorsSeen >= 3) return screenshot
            screenshot.recycle()
        }
        error("Blank Android export UI screenshot: $name (visibleColors=$colorsSeen)")
    }


}
