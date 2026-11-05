package com.hebi.quickcut.ui

import androidx.activity.ComponentActivity
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
        compose.onNodeWithText("취소").assertDoesNotExist()
    }
}
