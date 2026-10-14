package com.hebi.quickcut.batch

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.quickcut.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

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

        composeRule.onNodeWithText("처음 N초만 추출").performScrollTo().performClick()
        leading.assertIsEnabled()
        trailing.assertIsNotEnabled()

        composeRule.onNodeWithText("마지막 N초만 추출").performScrollTo().performClick()
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
        composeRule.onNodeWithText("템플릿 저장").performScrollTo().performClick()
        composeRule.onNodeWithText("템플릿을 저장했습니다.").assertExists()
    }
}
