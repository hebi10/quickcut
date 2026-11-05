package com.hebi.quickcut.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hebi.quickcut.editor.OriginalAction
import com.hebi.quickcut.media.SaveDateMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SaveOptionsAccessibilityInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun saveChoicesExposeCurrentValuesAndCallbackSelection() {
        val action = mutableStateOf(OriginalAction.KEEP)
        val date = mutableStateOf(SaveDateMode.CURRENT)
        compose.setContent {
            QuickCutTheme {
                SaveOptionsDialog(action.value, { action.value = it }, date.value,
                    { date.value = it }, true, {}, {})
            }
        }
        compose.onNodeWithContentDescription("저장 완료 후 원본 동영상 처리: 원본 유지 (기본값)").performClick()
        compose.onNode(hasText("원본 유지 (기본값)") and isSelected()).assertIsDisplayed()
        compose.onNodeWithText("원본 휴지통으로 이동").performClick()
        compose.runOnIdle { assertEquals(OriginalAction.TRASH, action.value) }
        compose.onNodeWithContentDescription("저장 날짜: 현재 날짜로 저장 (최신 영상)").performScrollTo().performClick()
        compose.onNodeWithText("원본 날짜 유지").performClick()
        compose.onNodeWithContentDescription("저장 날짜: 원본 날짜 유지").assertIsDisplayed()
    }

    @Test fun destructiveSelectionCanWaitForConfirmationBeforeChangingCurrentValue() {
        var requested: OriginalAction? = null
        compose.setContent {
            QuickCutTheme {
                SaveOptionsDialog(OriginalAction.KEEP, { requested = it }, SaveDateMode.ORIGINAL,
                    {}, true, {}, {})
            }
        }
        compose.onNodeWithContentDescription("저장 완료 후 원본 동영상 처리: 원본 유지 (기본값)").performClick()
        compose.onNodeWithText("원본 영구 삭제").performClick()
        compose.runOnIdle { assertEquals(OriginalAction.DELETE, requested) }
        compose.onNodeWithContentDescription("저장 완료 후 원본 동영상 처리: 원본 유지 (기본값)").assertIsDisplayed()
    }

    @Test fun dropdownSupportsNarrowLargeFontDisabledItemsAndClickableAnchorTag() {
        val selection = mutableStateOf(0)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                QuickCutTheme {
                    CutChoiceDropdown("선택", selection.value,
                        listOf(CutChoice(0, "현재 선택"), CutChoice(1, "사용 불가", false),
                            CutChoice(2, "좁은 화면에서도 여러 줄로 표시되는 긴 선택 항목")),
                        modifier = Modifier.width(240.dp).testTag("choice"),
                        onSelected = { selection.value = it })
                }
            }
        }
        compose.onNodeWithTag("choice").assertTextContains("현재 선택", substring = true)
            .performClick()
        compose.onNode(hasText("현재 선택") and isSelected()).assertIsDisplayed()
        compose.onNodeWithText("사용 불가").assertIsNotEnabled()
        compose.onNodeWithText("좁은 화면에서도 여러 줄로 표시되는 긴 선택 항목").performClick()
        compose.runOnIdle { assertEquals(2, selection.value) }
        compose.onNodeWithTag("choice")
            .assertTextContains("좁은 화면에서도 여러 줄로 표시되는 긴 선택 항목", substring = true)
    }

    @Test fun disabledAnchorCannotOpenMenu() {
        compose.setContent {
            QuickCutTheme {
                CutChoiceDropdown("선택", 0, listOf(CutChoice(0, "현재 선택")), enabled = false,
                    modifier = Modifier.testTag("choice"), onSelected = { error("Disabled") })
            }
        }
        compose.onNodeWithTag("choice").assertIsNotEnabled()
    }
}
