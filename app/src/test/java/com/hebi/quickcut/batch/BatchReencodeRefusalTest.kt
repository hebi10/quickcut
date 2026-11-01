package com.hebi.quickcut.batch

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import org.junit.Assert.*
import org.junit.Test

class BatchReencodeRefusalTest {
    private val requested = EditTimeline(listOf(VideoClip(1, "content://video/1", 20_000, 3_000, 17_000)))
    @Test fun sameRequestSurvivesCosmeticClipIdentityChanges() {
        val refusal = BatchReencodeRefusal.from(requested, "ORIGINAL", 123L)
        assertTrue(refusal.matches(requested.copy(clips = requested.clips.map { it.copy(id = 99) }), "ORIGINAL", 123L))
    }
    @Test fun changedRangeMuteSourceMetadataAndDateCanBeAssessedAgain() {
        val refusal = BatchReencodeRefusal.from(requested, "ORIGINAL", 123L)
        val clip = requested.clips.single()
        listOf(clip.copy(uri = "content://video/2"), clip.copy(sourceDurationMs = 21_000),
            clip.copy(startMs = 2_000), clip.copy(endMs = 16_000), clip.copy(muted = true),
            clip.copy(hasAudio = false), clip.withRangeUs(3_000_001, 17_000_000)).forEach {
            assertFalse(refusal.matches(EditTimeline(listOf(it)), "ORIGINAL", 123L))
        }
        assertFalse(refusal.matches(requested, "CURRENT", 123L))
        assertFalse(refusal.matches(requested, "ORIGINAL", 456L))
    }
}
