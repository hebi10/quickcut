package com.hebi.quickcut.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.media.ExportAssessment
import com.hebi.quickcut.media.SaveDateMode
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
        compose.onNodeWithText("저장 정보").assertExists()
        compose.onNodeWithText("기기 갤러리에 저장").assertExists()
        compose.onNodeWithText("저장 시작").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("저장 정보").assertExists()
        compose.onNodeWithText("취소").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
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
        compose.onNodeWithText("원본 영구 삭제").assertExists()
        compose.onNodeWithText("이 버튼은 처리 방식만 활성화합니다.", substring = true)
            .assertExists()
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
        compose.onNodeWithText("계속 저장").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("저장 취소").assertIsNotEnabled()
        capture("dialog_export_finalizing")
    }

    @Test
    fun diagnosticAndPrivacyDialogsShowScrolledText() {
        var showTaskError by mutableStateOf(false)
        compose.setContent {
            QuickCutTheme {
                if (showTaskError) {
                    TaskErrorDialog(
                        message = "원본 영상의 접근 권한을 확인해 주세요.",
                        onDismiss = {},
                    )
                } else {
                    PrivacyPolicyButton()
                }
            }
        }
        compose.onNodeWithText("개인정보처리방침").performClick()
        compose.onNodeWithText("닫기").assertIsDisplayed()
        capture("dialog_privacy_policy")
        compose.runOnIdle { showTaskError = true }
        compose.onNodeWithText("작업 오류").assertIsDisplayed()
        compose.onNodeWithText("오류 내용").assertExists()
        compose.onNodeWithText("확인").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("dialog_task_error")
    }

    @Test
    fun exportCancelDialogCanStopUnfinishedOutput() {
        compose.setContent {
            QuickCutTheme { ExportCancelDialog(canCancel = true, onCancel = {}, onDismiss = {}) }
        }
        compose.onNodeWithText("저장 취소").assertIsDisplayed()
        capture("dialog_export_cancel")
    }

    @Test
    fun previousEditRecoveryDialogOffersReconnect() {
        compose.setContent {
            QuickCutTheme {
                RestoreRecoveryDialog(
                    notice = "원본 영상 접근을 확인해 주세요.",
                    canReconnect = true, onRetry = {}, onReconnect = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("원본 영상 다시 연결").assertIsDisplayed()
        compose.onNodeWithText("복원 방법").assertExists()
        capture("dialog_edit_recovery")
    }

    @Test
    fun diagnosticRecordDialogIsReadable() {
        compose.setContent {
            QuickCutTheme { ExportDiagnosticButton() }
        }
        compose.onNodeWithText("최근 작업 진단").performClick()
        compose.onNodeWithText("편집·저장 진단 기록").assertIsDisplayed()
        capture("dialog_diagnostics")
    }

    @Test
    fun licenseDialogKeepsListAndCloseVisible() {
        compose.setContent {
            QuickCutTheme { OpenSourceLicensesDialog(onDismiss = {}) }
        }
        compose.onNodeWithText("오픈소스 라이선스").assertIsDisplayed()
        compose.onNodeWithText("닫기").assertIsDisplayed()
        capture("dialog_licenses")
    }

    @Test
    fun saveDialogAt200PercentKeepsSaveAndCancelAccessible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                QuickCutTheme {
                    SaveOptionsDialog(OriginalAction.KEEP, {}, SaveDateMode.CURRENT, {},
                        true, {}, {})
                }
            }
        }
        compose.onNodeWithText("저장 시작").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("취소").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("dialog_save_large_font")
    }

    @Test
    fun exportInspectionAt200PercentKeepsProgressAndCancelVisible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                QuickCutTheme { ExportInspectionDialog(progress = 37, onCancel = {}) }
            }
        }
        compose.onNodeWithText("원본 분석 37%", substring = true).assertIsDisplayed()
        compose.onNodeWithText("분석 취소").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("dialog_export_analysis_large_font")
    }

    @Test
    fun longDialogHeadingRemainsCompleteWithLargeFont() {
        val title = "영상 저장을 취소할까요?"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                QuickCutTheme {
                    Box(Modifier.width(236.dp)) { QuickCutDialogHeading(title) }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue("Title layout should be available", action(layouts))
            }
        assertTrue("Title should have text layout", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertTrue("Dialog title should not truncate", !layout.multiParagraph.didExceedMaxLines)
            assertEquals("Dialog title should keep all characters", title.length,
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        }
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
