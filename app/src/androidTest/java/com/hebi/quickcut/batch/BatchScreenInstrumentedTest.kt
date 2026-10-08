package com.hebi.quickcut.batch

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        composeRule.onNodeWithText("템플릿 · 일괄 편집").performClick()
        composeRule.onNodeWithText("일괄 편집").assertExists()
        composeRule.onNodeWithText("앞뒤 구간 삭제").assertExists()
        composeRule.onNodeWithText("현재 날짜로 저장 (최신 파일)").assertExists()
        composeRule.onNodeWithText("원본 날짜 유지").assertExists()
    }

    @Test
    fun templateCanBeSavedFromEditor() {
        composeRule.onNodeWithText("템플릿 · 일괄 편집").performClick()
        composeRule.onNodeWithText("템플릿 저장").performScrollTo().performClick()
        composeRule.onNodeWithText("템플릿을 저장했습니다.").assertExists()
    }
}
