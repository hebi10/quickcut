package com.hebi.quickcut.ui

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.media.FastCutSuggestion
import org.junit.Assert.*
import org.junit.Test

class FastCutSuggestionTextTest {
    @Test fun displaysRequestedRecommendedAndAdditionalTimeToTheMillisecond() {
        val timeline = EditTimeline(listOf(VideoClip(1, "video", 12_000_000, 10_801_033, 10_811_100)))
        val review = FastCutSuggestion.create(timeline, listOf(10_802_000L to 10_810_000L))!!
        val text = fastCutSuggestionText(review)
        listOf("03:00:01.033", "03:00:02.000", "03:00:11.100", "03:00:10.000",
            "0.967초", "1.1초", "2.067초").forEach { assertTrue("Missing $it in $text", text.contains(it)) }
    }
}
