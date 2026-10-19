package com.hebi.quickcut.batch

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.quickcut.MainActivity
import com.hebi.quickcut.QuickCutApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun minuteSecondInputExplainsTheRemovedDurationAndRejectsInvalidSeconds() {
        val model = (composeRule.activity.application as QuickCutApplication).batchModel
        val previous = composeRule.runOnIdle {
            Triple(model.draftMode, model.draftLeadingSeconds, model.draftTrailingSeconds)
        }
        try {
            composeRule.onNodeWithText("일괄 편집 · 여러 영상").performClick()
            composeRule.onNodeWithText("앞뒤 구간 삭제").performScrollTo().performClick()
            composeRule.onNodeWithTag("batch-leading-minutes").performScrollTo()
                .performTextReplacement("1")
            composeRule.onNodeWithTag("batch-leading-seconds").performScrollTo()
                .performTextReplacement("5.5")
            composeRule.onNodeWithTag("batch-trailing-minutes").performScrollTo()
                .performTextReplacement("0")
            composeRule.onNodeWithTag("batch-trailing-seconds").performScrollTo()
                .performTextReplacement("3")
            composeRule.onNodeWithText("각 영상의 앞 1분 5.5초와 뒤 3초를 삭제합니다.")
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
        composeRule.onNodeWithText("일괄 편집 · 여러 영상").performClick()
        composeRule.onNodeWithText("일괄 편집").assertExists()
        composeRule.onNodeWithText("앞뒤 구간 삭제").assertExists()
        composeRule.onNodeWithText("템플릿·저장 옵션 펼치기").performScrollTo().performClick()
        composeRule.onNodeWithText("현재 날짜로 저장 (최신 파일)").assertExists()
        composeRule.onNodeWithText("원본 날짜 유지").assertExists()
    }


    @Test
    fun disabledDurationInputsFollowSelectedClipMode() {
        composeRule.onNodeWithText("일괄 편집 · 여러 영상").performClick()
        composeRule.onNodeWithText("앞뒤 구간 삭제").performScrollTo().performClick()
        val leading = composeRule.onNodeWithTag("batch-leading-seconds")
        val trailing = composeRule.onNodeWithTag("batch-trailing-seconds")

        leading.assertIsEnabled()
        trailing.assertIsEnabled()

        composeRule.onNodeWithText("처음 구간만 남기기").performScrollTo().performClick()
        leading.assertIsEnabled()
        trailing.assertIsNotEnabled()

        composeRule.onNodeWithText("마지막 구간만 남기기").performScrollTo().performClick()
        leading.assertIsNotEnabled()
        trailing.assertIsEnabled()

        composeRule.onNodeWithText("처음 + 마지막 구간 연결").performScrollTo().performClick()
        leading.assertIsEnabled()
        trailing.assertIsEnabled()

        composeRule.onNodeWithText("앞뒤 구간 삭제").performScrollTo().performClick()
        leading.assertIsEnabled()
        trailing.assertIsEnabled()
    }

    @Test
    fun templateCanBeSavedFromEditor() {
        composeRule.onNodeWithText("일괄 편집 · 여러 영상").performClick()
        composeRule.onNodeWithText("템플릿·저장 옵션 펼치기").performScrollTo().performClick()
        composeRule.onNodeWithText("직접 설정").performScrollTo().performClick()
        composeRule.onNodeWithText("템플릿 저장").performScrollTo().performClick()
        composeRule.onNodeWithText("템플릿을 저장했습니다.").assertExists()
    }
}
