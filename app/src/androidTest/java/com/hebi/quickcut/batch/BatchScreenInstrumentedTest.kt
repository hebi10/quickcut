package com.hebi.quickcut.batch

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.quickcut.MainActivity
import com.hebi.quickcut.QuickCutApplication
import com.hebi.quickcut.settings.QuickCutSettings
import com.hebi.quickcut.settings.QuickCutThemeMode
import com.hebi.quickcut.ui.Ui07aVisualEvidence
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // Onboarding and the tools tab are now part of the real first-run flow.
    private fun openBatchEditor() {
        if (composeRule.onAllNodesWithText("다음").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("다음").performClick()
            composeRule.onNodeWithText("다음").performClick()
            composeRule.onNodeWithText("시작하기").performClick()
        }
        if (composeRule.onAllNodesWithText("도구").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("도구").performClick()
        }
        composeRule.onNodeWithText("일괄 편집").performScrollTo().performClick()
    }

    @Test
    fun minuteSecondInputExplainsTheRemovedDurationAndRejectsInvalidSeconds() {
        val model = (composeRule.activity.application as QuickCutApplication).batchModel
        val previous = composeRule.runOnIdle {
            Triple(model.draftMode, model.draftLeadingSeconds, model.draftTrailingSeconds)
        }
        try {
            openBatchEditor()
            choose("batch-mode", "앞뒤 구간 자르기")
            composeRule.onNodeWithTag("batch-leading-minutes").performScrollTo()
                .performTextReplacement("1")
            composeRule.onNodeWithTag("batch-leading-seconds").performScrollTo()
                .performTextReplacement("5.5")
            composeRule.onNodeWithTag("batch-trailing-minutes").performScrollTo()
                .performTextReplacement("0")
            composeRule.onNodeWithTag("batch-trailing-seconds").performScrollTo()
                .performTextReplacement("3")
            composeRule.onNodeWithText("각 영상의 앞 1분 5.5초와 뒤 3초를 자릅니다.")
                .performScrollTo().assertExists()
            composeRule.onNodeWithTag("batch-leading-seconds").performScrollTo()
                .performTextReplacement("60")
            composeRule.onNodeWithText("초는 0~59.999로 입력해 주세요.")
                .performScrollTo().assertExists()
        } finally {
            composeRule.runOnIdle {
                model.draftMode = previous.first
                model.draftLeadingSeconds = previous.second
                model.draftTrailingSeconds = previous.third
            }
        }
    }

    @Test
    fun launchBatchEditorAndShowTimestampChoices() {
        val settings = QuickCutSettings(composeRule.activity)
        val previousTheme = settings.loadThemeMode()
        try {
            settings.saveThemeMode(QuickCutThemeMode.LIGHT)
            openBatchEditor()
            composeRule.onNodeWithText("일괄 편집").assertExists()
            composeRule.onNodeWithTag("batch-mode").assertExists()
            composeRule.onNodeWithTag("ui04b-template-summary").assertExists()
            captureUi04b("ui04b_batch_setup.png")
            waitForTheme(expectLight = true)
            Ui07aVisualEvidence.capture("ui07a_light_batch.png")
            settings.saveThemeMode(QuickCutThemeMode.DARK)
            waitForTheme(expectLight = false)
            composeRule.onNodeWithTag("ui04b-template-summary").performScrollTo().assertExists()
            Ui07aVisualEvidence.capture("ui07a_dark_batch.png")
            composeRule.onNodeWithText("템플릿·저장 옵션 펼치기").performScrollTo().performClick()
            choose("batch-date", "현재 날짜로 저장 (최신 파일)")
            choose("batch-date", "원본 날짜 유지")
        } finally {
            settings.saveThemeMode(previousTheme)
        }
    }

    private fun waitForTheme(expectLight: Boolean) {
        // Android 15+ can ignore statusBarColor. Use the actual Compose content
        // but cap screenshots to avoid exhausting emulator graphics memory.
        var lastBrightness: Int? = null
        repeat(8) { attempt ->
            composeRule.waitForIdle()
            if (attempt > 0) Thread.sleep(250L)
            val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
            val brightness = try {
                val color = image.getPixel(
                    (image.width / 25).coerceIn(0, image.width - 1),
                    (image.height / 10).coerceIn(0, image.height - 1),
                )
                (AndroidColor.red(color) +
                    AndroidColor.green(color) + AndroidColor.blue(color)) / 3
            } finally {
                image.recycle()
            }
            lastBrightness = brightness
            if (if (expectLight) brightness >= 170 else brightness <= 110) return
        }
        throw AssertionError(
            "Batch theme did not render: expectedLight=$expectLight, " +
                "lastBrightness=$lastBrightness"
        )
    }


    @Test
    fun disabledDurationInputsFollowSelectedClipMode() {
        openBatchEditor()
        choose("batch-mode", "앞뒤 구간 자르기")
        val leading = composeRule.onNodeWithTag("batch-leading-seconds")
        val trailing = composeRule.onNodeWithTag("batch-trailing-seconds")

        leading.assertIsEnabled()
        trailing.assertIsEnabled()

        choose("batch-mode", "처음 구간만 남기기")
        leading.assertIsEnabled()
        trailing.assertIsNotEnabled()

        choose("batch-mode", "마지막 구간만 남기기")
        leading.assertIsNotEnabled()
        trailing.assertIsEnabled()

        choose("batch-mode", "처음 + 마지막 구간 연결")
        leading.assertIsEnabled()
        trailing.assertIsEnabled()

        choose("batch-mode", "앞뒤 구간 자르기")
        leading.assertIsEnabled()
        trailing.assertIsEnabled()
    }

    @Test
    fun dropdownOriginalHandlingStillRequiresConfirmation() {
        val model = (composeRule.activity.application as QuickCutApplication).batchModel
        val previous = composeRule.runOnIdle { model.originalAction }
        try {
            composeRule.runOnIdle { model.originalAction = com.hebi.quickcut.editor.OriginalAction.KEEP }
            openBatchEditor()
            composeRule.onNodeWithText("템플릿·저장 옵션 펼치기").performScrollTo().performClick()
            choose("batch-original-action", "저장 성공한 원본만 휴지통으로 이동")
            composeRule.runOnIdle { org.junit.Assert.assertEquals(com.hebi.quickcut.editor.OriginalAction.KEEP, model.originalAction) }
            composeRule.onNodeWithText("활성화").performClick()
            composeRule.runOnIdle { org.junit.Assert.assertEquals(com.hebi.quickcut.editor.OriginalAction.TRASH, model.originalAction) }
        } finally { composeRule.runOnIdle { model.originalAction = previous } }
    }

    @Test
    fun templateCanBeSavedFromEditor() {
        openBatchEditor()
        composeRule.onNodeWithText("템플릿·저장 옵션 펼치기").performScrollTo().performClick()
        composeRule.onNodeWithTag("batch-template-manager").performScrollTo().performClick()
        composeRule.onNodeWithTag("batch-template-dialog").assertExists()
        captureUi04b("ui04b_batch_templates.png")
        composeRule.onNodeWithTag("batch-template-direct").performClick()
        composeRule.onNodeWithText("새 템플릿으로 저장").performScrollTo().performClick()
        composeRule.onNodeWithText("템플릿을 저장했습니다.").assertExists()
    }
    @Test
    fun modalSupportsMultipleTemplatesAndRemovingAnUnselectedRowPreservesDraft() {
        val model = (composeRule.activity.application as QuickCutApplication).batchModel
        val context = composeRule.activity
        val originalIds = model.templates.map { it.id }.toSet()
        val previousName = model.draftName
        val previousMode = model.draftMode
        val previousLeading = model.draftLeadingSeconds
        val previousTrailing = model.draftTrailingSeconds
        val previousSelection = model.selectedTemplateId
        try {
            composeRule.runOnIdle {
                model.draftMode = BatchClipMode.REMOVE_EDGES
                model.draftLeadingSeconds = "1"
                model.draftTrailingSeconds = "1"
                model.draftName = "UI template A"
                model.saveTemplate(context, asNew = true)
                model.draftName = "UI template B"
                model.saveTemplate(context, asNew = true)
                model.clearNotification()
            }
            val added = model.templates.filter { it.id !in originalIds }
            org.junit.Assert.assertEquals(2, added.size)
            openBatchEditor()
            composeRule.onNodeWithTag("batch-template-manager").performScrollTo().performClick()
            composeRule.onNodeWithTag("batch-template-list")
                .performScrollToNode(hasTestTag("batch-template-${added.first().id}-choose"))
            composeRule.onNodeWithTag("batch-template-${added.first().id}-choose").performClick()
            composeRule.runOnIdle { model.draftName = "unsaved draft" }
            composeRule.onNodeWithTag("batch-template-list")
                .performScrollToNode(hasTestTag("batch-template-${added.last().id}-remove"))
            composeRule.onNodeWithTag("batch-template-${added.last().id}-remove").performClick()
            composeRule.onNodeWithText("확인").performClick()
            composeRule.onNodeWithTag("batch-template-list").assertExists()
            composeRule.runOnIdle {
                org.junit.Assert.assertEquals(added.first().id, model.selectedTemplateId)
                org.junit.Assert.assertEquals("unsaved draft", model.draftName)
                org.junit.Assert.assertFalse(model.templates.any { it.id == added.last().id })
            }
            composeRule.runOnIdle { model.draftName = "" }
            composeRule.onNodeWithTag("batch-template-list")
                .performScrollToNode(hasText("선택 템플릿 수정 저장"))
            composeRule.onNodeWithText("선택 템플릿 수정 저장").performClick()
            composeRule.onNodeWithText("확인").performClick()
            composeRule.onNodeWithTag("batch-template-list")
                .performScrollToNode(hasTestTag("batch-template-name"))
            composeRule.onNodeWithTag("batch-template-name").performTextReplacement("updated template")
            composeRule.onNodeWithTag("batch-template-list")
                .performScrollToNode(hasText("선택 템플릿 수정 저장"))
            composeRule.onNodeWithText("선택 템플릿 수정 저장").performClick()
            composeRule.onNodeWithText("확인").performClick()
            composeRule.runOnIdle {
                org.junit.Assert.assertEquals("updated template", model.templates.first { it.id == added.first().id }.name)
            }
        } finally {
            composeRule.runOnIdle {
                model.templates.filter { it.id !in originalIds }.forEach { model.removeTemplate(context, it.id) }
                model.chooseTemplate(previousSelection)
                model.draftMode = previousMode
                model.draftLeadingSeconds = previousLeading
                model.draftTrailingSeconds = previousTrailing
                model.draftName = previousName
                model.clearNotification()
            }
        }
    }

    private fun captureUi04b(name: String) {
        require(name in setOf("ui04b_batch_setup.png", "ui04b_batch_templates.png"))
        composeRule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "design-review")
        check(folder.isDirectory || folder.mkdirs())
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300L, 5_000L)
        val bitmap = checkNotNull(automation.takeScreenshot()) {
            "Android 시스템 화면 캡처에 실패했습니다."
        }
        val file = File(folder, name)
        try {
            file.outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        check(file.length() > 0L) { "UI-04-B 캡처 생성 실패: $name" }
    }

    private fun choose(tag: String, label: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeRule.onNodeWithTag("cut-choice-option-$label").performClick()
    }
}
