package com.hebi.quickcut.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.media.ExportAssessment
import com.hebi.quickcut.media.FastCutSuggestion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FastCutConsentInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val requested = EditTimeline(listOf(VideoClip(1, "content://video/1", 8_000, 3_000, 7_500)))
    private val suggestion = FastCutSuggestion.create(requested, listOf(4_000L to 6_000L))!!
    private var timeline = requested
    private var exact = false
    private var dismissed = false

    private fun open() {
        compose.setContent {
            QuickCutTheme {
                ExportModeDialog(ExportAssessment(false, "키프레임 위치 확인", suggestion),
                    retryingFastExport = false, canSave = true,
                    onAllowReencode = { exact = true },
                    onSuggestedCut = { timeline = suggestion.applyTo(timeline)!! },
                    onDismiss = { dismissed = true })
            }
        }
        compose.onNodeWithText("총 2.5초가 추가로 삭제됩니다.", substring = true).assertExists()
        compose.runOnIdle { assertEquals(requested, timeline) }
    }

    @Test fun onlyExplicitFastConsentChangesTheReviewedRange() {
        open()
        compose.onNodeWithText("추천 위치로 고속 저장").performClick()
        compose.runOnIdle {
            assertEquals(4_000L, timeline.clips.single().startMs)
            assertEquals(6_000L, timeline.clips.single().endMs)
            assertFalse(exact)
        }
    }

    @Test fun exactSaveKeepsRequestedTimes() {
        open()
        compose.onNodeWithText("정확한 위치로 자동 저장").performClick()
        compose.runOnIdle { assertTrue(exact); assertEquals(requested, timeline) }
    }

    @Test fun dismissDoesNotChangeTimesOrBeginSaving() {
        open()
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(exact); assertEquals(requested, timeline) }
    }
}
