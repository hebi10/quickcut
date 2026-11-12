package com.hebi.quickcut.ui

import androidx.activity.ComponentActivity
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
        compose.setContent {
            QuickCutTheme {
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
        val bitmap = instrument.uiAutomation.takeScreenshot()
            ?: error("Android 실제 내보내기 UI 캡처에 실패했습니다.")
        try {
            File(directory, name).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        check(File(directory, name).length() > 0L)
    }

}
