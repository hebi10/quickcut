package com.hebi.quickcut.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.media.SaveDateMode
import org.junit.Rule
import org.junit.Test

class SaveOptionsAccessibilityInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun saveChoicesExposeIndependentGroupsAndSelectedRadioRows() {
        val action = mutableStateOf(OriginalAction.KEEP)
        val date = mutableStateOf(SaveDateMode.CURRENT)
        compose.setContent {
            QuickCutTheme {
                SaveOptionsDialog(action.value, { action.value = it }, date.value,
                    { date.value = it }, true, {}, {})
            }
        }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.SelectableGroup, Unit))
            .assertCountEquals(2)
        compose.onNodeWithText("원본 유지 (기본값)").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose.onNodeWithText("원본 휴지통으로 이동").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("원본 유지 (기본값)").assertIsNotSelected()
        compose.onNodeWithText("현재 날짜로 저장 (최신 영상)").assertIsSelected()
        compose.onNodeWithText("원본 날짜 유지").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("현재 날짜로 저장 (최신 영상)").assertIsNotSelected()
    }
}
