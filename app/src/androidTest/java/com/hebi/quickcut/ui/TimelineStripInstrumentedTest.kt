package com.hebi.quickcut.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.editor.formatTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineStripInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun longTimelineOnlyComposesNearbyClipsAndKeepsEditingAfterScrollAndZoom() {
        var timeline by mutableStateOf(EditTimeline((1L..100L).map { id ->
            val duration = when (id) { 1L -> 1_000L; 100L -> 10_900L; else -> 2_000L }
            VideoClip(id, "content://missing-thumbnail/$id", duration)
        }))
        var selectedId by mutableStateOf<Long?>(null)
        var trimStarted = false
        var trimEnded = false
        composeRule.setContent {
            QuickCutTheme {
                TimelineStrip(timeline, selectedId, playheadMs = 0L,
                    onSelect = { selectedId = it },
                    onMove = { id, delta -> timeline = timeline.move(id, delta) },
                    onTrim = { id, start, end -> timeline = timeline.updateRange(id, start, end) },
                    onTrimStart = { trimStarted = true }, onTrimEnd = { trimEnded = true })
            }
        }
        composeRule.onNodeWithText("00:01.0").assertIsDisplayed()
        composeRule.onNodeWithText("00:10.9").assertDoesNotExist()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(99)
        composeRule.onNodeWithText("00:10.9").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(100L, selectedId) }
        composeRule.onNodeWithText("+").performClick()
        composeRule.onNodeWithText("2배").assertIsDisplayed()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(99)
        composeRule.onNode(hasClickAction() and hasText("00:10.9")).performTouchInput {
            down(Offset(4f, centerY))
            moveBy(Offset(40f, 0f), delayMillis = 100L)
            up()
        }
        composeRule.runOnIdle {
            assertTrue(trimStarted)
            assertTrue(trimEnded)
            assertTrue(timeline.clips.last().startMs > 0L)
        }
        val duration = composeRule.runOnIdle { formatTime(timeline.clips.last().durationMs) }
        composeRule.onNode(hasClickAction() and hasText(duration)).performTouchInput {
            down(center)
            advanceEventTime(600L)
            moveBy(Offset(-width * 0.7f, 0f), delayMillis = 100L)
            up()
        }
        composeRule.runOnIdle {
            assertEquals(100L, timeline.clips[98].id)
            assertEquals(100L, selectedId)
        }
    }
}
