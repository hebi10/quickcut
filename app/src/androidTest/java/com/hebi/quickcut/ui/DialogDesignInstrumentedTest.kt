package com.hebi.quickcut.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.media.ExportAssessment
import com.hebi.quickcut.media.SaveDateMode
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DialogDesignInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun saveSettingsDialogHasAccessibleActions() {
        compose.setContent {
            QuickCutTheme {
                SaveOptionsDialog(OriginalAction.KEEP, {}, SaveDateMode.CURRENT, {},
                    true, {}, {})
            }
        }
        compose.onNodeWithText("저장 설정").assertIsDisplayed()
        compose.onNodeWithText("저장 시작").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("dialog_save_settings")
    }

    @Test
    fun destructiveOriginalActionDialogShowsWarning() {
        compose.setContent {
            QuickCutTheme {
                OriginalActionEnableDialog(OriginalAction.DELETE, {}, {})
            }
        }
        compose.onNodeWithText("원본 자동 처리 활성화").assertIsDisplayed()
        compose.onNodeWithText("활성화").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("dialog_original_delete")
    }

    @Test
    fun exactExportDialogKeepsMainChoiceVisible() {
        compose.setContent {
            QuickCutTheme {
                ExportModeDialog(ExportAssessment(false, "키프레임 위치 확인"),
                    retryingFastExport = false, canSave = true,
                    onAllowReencode = {}, onSuggestedCut = null, onDismiss = {})
            }
        }
        compose.onNodeWithText("정확한 위치로 자동 저장")
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("dialog_export_method")
    }

    @Test
    fun exportCancelDialogDistinguishesFinalizingState() {
        compose.setContent {
            QuickCutTheme {
                ExportCancelDialog(canCancel = false, onCancel = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("계속 저장").assertIsDisplayed()
        capture("dialog_export_finalizing")
    }

    @Test
    fun diagnosticAndPrivacyDialogsShowScrolledText() {
        compose.setContent {
            QuickCutTheme { PrivacyPolicyButton() }
        }
        compose.onNodeWithText("개인정보처리방침").performClick()
        compose.onNodeWithText("닫기").assertIsDisplayed()
        capture("dialog_privacy_policy")
    }

    private fun capture(name: String) {
        require(name.matches(Regex("dialog_[a-z_]+")))
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(300L, 5_000L)
        val directory = File(
            checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "design-review",
        )
        check(directory.isDirectory || directory.mkdirs())
        val screenshot = File(directory, "$name.png")
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        screenshot.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()

        val destination = "/data/local/tmp/quickcut-design-review/$name.png"
        fun shell(command: String): String {
            val fd = instrumentation.uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(fd)
                .bufferedReader().use { it.readText().trim() }
        }
        shell("mkdir -p /data/local/tmp/quickcut-design-review")
        shell("cp ${screenshot.absolutePath} $destination")
        val size = shell("wc -c $destination").substringBefore(' ').toLongOrNull()
        assertTrue("Dialog screenshot must be retained", screenshot.length() > 0L &&
            size == screenshot.length())
    }
}
