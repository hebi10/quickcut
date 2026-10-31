package com.hebi.quickcut.editor

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class EditorLoadPolicyTest {
    private val saved = EditTimeline(listOf(
        VideoClip(7L, "saved", 10_000L, muted = true).withRangeUs(123_456L, 4_567_890L),
        VideoClip(9L, "other", 8_000L, 1_000L, 6_000L, hasAudio = false),
        VideoClip(8L, "saved", 10_000L, 6_000L, 9_000L),
    ))

    @Test fun unreadableSourceKeepsCompleteTimelineForRetry() {
        val failure = runCatching { validateSavedTimeline(saved) { throw IOException() } }.exceptionOrNull()
        assertTrue(failure is SavedSourceUnavailableException)
        assertEquals("saved", (failure as SavedSourceUnavailableException).sourceUri)
        validateSavedTimeline(saved) { uri -> SavedSourceMetadata(if (uri == "saved") 10_000L else 8_000L, true) }
        assertEquals(listOf(7L, 9L, 8L), saved.clips.map { it.id })
        assertEquals(123_456L, saved.clips.first().startUs)
    }

    @Test fun invalidReplacementNeverChangesSavedTimeline() {
        assertThrows(IllegalArgumentException::class.java) {
            reconnectSavedTimeline(saved, "saved", "replacement", SavedSourceMetadata(2_000L, true))
        }
        assertEquals(listOf("saved", "other", "saved"), saved.clips.map { it.uri })
        assertThrows(IllegalArgumentException::class.java) {
            reconnectSavedTimeline(saved, "saved", "replacement", SavedSourceMetadata(10_000L, false))
        }
    }

    @Test fun replacementMustCoverEveryExactBoundaryDespiteDurationTolerance() {
        assertThrows(IllegalArgumentException::class.java) {
            reconnectSavedTimeline(saved, "saved", "replacement", SavedSourceMetadata(8_999L, true))
        }
    }

    @Test fun validReplacementPreservesEveryEditAndOtherSources() {
        val reconnected = reconnectSavedTimeline(saved, "saved", "replacement", SavedSourceMetadata(10_000L, true))
        assertEquals(saved.clips.map { if (it.uri == "saved") it.copy(uri = "replacement") else it }, reconnected.clips)
        assertEquals(saved.totalDurationUs, reconnected.totalDurationUs)
        assertEquals(listOf("saved", "other", "saved"), saved.clips.map { it.uri })
    }

    @Test fun validationChecksAllRepeatedSourceClips() {
        val conflicting = saved.copy(clips = saved.clips + VideoClip(10L, "saved", 20_000L))
        assertThrows(SavedSourceUnavailableException::class.java) {
            validateSavedTimeline(conflicting) { uri -> SavedSourceMetadata(if (uri == "saved") 10_000L else 8_000L, true) }
        }
    }

    @Test fun earlierUnreadableSourceCannotHideIncompatibleExistingReplacementGroup() {
        val incompatibleClips = listOf(
            // The duration tolerance alone allows this source, but its saved tail does not fit.
            VideoClip(13L, "replacement", 11_000L, 9_000L, 11_000L),
            // Its selected range fits, but its saved source duration is incompatible.
            VideoClip(13L, "replacement", 20_000L, 1_000L, 2_000L),
        )
        incompatibleClips.forEach { existingReplacement ->
            val old = EditTimeline(listOf(
                VideoClip(11L, "earlier-unreadable", 10_000L),
                VideoClip(12L, "saved", 10_000L, 1_000L, 8_000L),
                existingReplacement,
            ))
            val firstFailure = assertThrows(SavedSourceUnavailableException::class.java) {
                validateSavedTimeline(old) { throw IOException() }
            }
            assertEquals("earlier-unreadable", firstFailure.sourceUri)
            // Reconnect must reject before returning a candidate that the ViewModel can persist,
            // independently of validation short-circuiting on the earlier inaccessible source.
            assertThrows(IllegalArgumentException::class.java) {
                reconnectSavedTimeline(old, "saved", "replacement", SavedSourceMetadata(10_000L, true))
            }
            assertEquals("saved", old.clips[1].uri)
            assertEquals(existingReplacement, old.clips[2])
        }
    }

    @Test fun compatibleReplacementGroupCanReconnectBeforeAnotherUnreadableSource() {
        val old = EditTimeline(listOf(
            VideoClip(11L, "earlier-unreadable", 10_000L, muted = true).withRangeUs(123_456L, 4_567_890L),
            VideoClip(12L, "saved", 10_000L, 1_000L, 8_000L),
            VideoClip(13L, "replacement", 10_000L, 2_000L, 9_000L),
        ))
        val candidate = reconnectSavedTimeline(old, "saved", "replacement", SavedSourceMetadata(10_000L, true))
        val nextFailure = assertThrows(SavedSourceUnavailableException::class.java) {
            validateSavedTimeline(candidate) { uri ->
                if (uri == "earlier-unreadable") throw IOException()
                SavedSourceMetadata(10_000L, true)
            }
        }
        assertEquals("earlier-unreadable", nextFailure.sourceUri)
        val completed = reconnectSavedTimeline(candidate, nextFailure.sourceUri, "another-replacement",
            SavedSourceMetadata(10_000L, true))
        validateSavedTimeline(completed) { SavedSourceMetadata(10_000L, true) }
        assertEquals(old.clips.map {
            when (it.uri) {
                "saved" -> it.copy(uri = "replacement")
                "earlier-unreadable" -> it.copy(uri = "another-replacement")
                else -> it
            }
        }, completed.clips)
    }

    @Test fun cancelledAndOlderLoadsCannotApplyToANewerSession() {
        val loads = EditorLoadGeneration()
        val old = loads.begin()
        assertTrue(loads.isCurrent(old))
        loads.cancel()
        assertFalse(loads.isCurrent(old))
        val current = loads.begin()
        assertFalse(loads.isCurrent(old))
        assertTrue(loads.isCurrent(current))
        loads.cancel()
        assertFalse(loads.isCurrent(current))
    }
}
