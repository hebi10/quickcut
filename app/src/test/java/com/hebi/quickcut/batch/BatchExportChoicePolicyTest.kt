package com.hebi.quickcut.batch

import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.FastCutSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BatchExportChoicePolicyTest {
    private fun file(id: Int, withSuggestion: Boolean = true): BatchReviewedFile {
        val requested = EditTimeline(listOf(
            VideoClip(1L, "content://video/$id", 20_000L, 3_000L, 17_000L),
        ))
        return BatchReviewedFile(
            uri = "content://video/$id", displayName = "$id.mp4", requested = requested,
            suggestion = if (withSuggestion) {
                FastCutSuggestion.create(requested, listOf(4_000L to 16_000L))
            } else null,
        )
    }

    @Test fun mixedChoicesKeepExactRangeUseReviewedRecommendationAndSkipExcludedFile() {
        val files = listOf(file(1), file(2), file(3))
        val result = BatchExportChoicePolicy.resolve(files, mapOf(
            files[0].uri to BatchExportChoice.EXACT,
            files[1].uri to BatchExportChoice.RECOMMENDED,
            files[2].uri to BatchExportChoice.SKIP,
        ))!!
        assertEquals(files.map { it.uri }, result.map { it.uri })
        assertSame(files[0].requested, result[0].timeline)
        assertEquals(ExportMode.ALLOW_REENCODE, result[0].exportMode)
        assertSame(files[1].suggestion!!.adjusted, result[1].timeline)
        assertEquals(ExportMode.FAST_ONLY, result[1].exportMode)
        assertNull(result[2].timeline)
        assertNull(result[2].exportMode)
        assertEquals("사용자가 제외한 영상입니다.", result[2].skipReason)
    }

    @Test fun exactIsDefaultAndUnavailableFilesAreExplicitlySkipped() {
        val available = file(1)
        val unavailable = BatchReviewedFile("content://video/2", "2.mp4",
            requested = null, unavailableReason = "원본 날짜 없음")
        val choices = BatchExportChoicePolicy.defaults(listOf(available, unavailable))
        assertEquals(mapOf(available.uri to BatchExportChoice.EXACT,
            unavailable.uri to BatchExportChoice.SKIP), choices)
        val result = BatchExportChoicePolicy.resolve(listOf(available, unavailable), choices)!!
        assertEquals("원본 날짜 없음", result[1].skipReason)
    }

    @Test fun rejectsMissingForeignAndDuplicateFileSelections() {
        val file = file(1)
        assertNull(BatchExportChoicePolicy.resolve(listOf(file), emptyMap()))
        assertNull(BatchExportChoicePolicy.resolve(listOf(file), mapOf(
            file.uri to BatchExportChoice.EXACT,
            "content://foreign" to BatchExportChoice.SKIP,
        )))
        assertNull(BatchExportChoicePolicy.resolve(listOf(file, file),
            mapOf(file.uri to BatchExportChoice.EXACT)))
    }

    @Test fun recommendationsRequireTheExactReviewedFileAndRequestedRange() {
        val first = file(1)
        val other = file(2)
        val requested = first.requested!!
        val changed = requested.copy(clips = requested.clips.map { it.copy(startMs = 2_000L) })
        listOf(
            first.copy(suggestion = null),
            first.copy(suggestion = other.suggestion),
            first.copy(requested = changed),
            first.copy(uri = other.uri),
        ).forEach { invalid ->
            assertNull(BatchExportChoicePolicy.resolve(listOf(invalid),
                mapOf(invalid.uri to BatchExportChoice.RECOMMENDED)))
        }
    }

    @Test fun unsupportedExactOrRecommendedChoicesNeverBecomeImplicitSkips() {
        val unavailable = BatchReviewedFile("content://video/1", "1.mp4",
            requested = null, unavailableReason = "영상 정보를 읽을 수 없습니다.")
        listOf(BatchExportChoice.EXACT, BatchExportChoice.RECOMMENDED).forEach { choice ->
            assertNull(BatchExportChoicePolicy.resolve(listOf(unavailable),
                mapOf(unavailable.uri to choice)))
        }
        val unsupported = file(1, withSuggestion = false)
        assertNull(BatchExportChoicePolicy.resolve(listOf(unsupported),
            mapOf(unsupported.uri to BatchExportChoice.RECOMMENDED)))
    }

    @Test fun resolvedChoicesCannotApplyAfterSourceMetadataAudioOrRangeChanges() {
        val file = file(1)
        val requested = file.requested!!
        for (choice in listOf(BatchExportChoice.EXACT, BatchExportChoice.RECOMMENDED)) {
            val result = BatchExportChoicePolicy.resolve(listOf(file), mapOf(file.uri to choice))!!.single()
            assertSame(result.timeline, result.timelineFor(requested))
            val clip = requested.clips.single()
            listOf(
                clip.copy(uri = "content://video/replaced"),
                clip.copy(sourceDurationMs = 21_000L),
                clip.copy(startMs = 2_000L),
                clip.copy(endMs = 16_000L),
                clip.copy(muted = true),
                clip.copy(hasAudio = false),
            ).forEach { changed ->
                assertNull(result.timelineFor(EditTimeline(listOf(changed))))
            }
        }
    }

    @Test fun joinedClipsUseOneReviewedSuggestionWithoutReconstructingBoundaries() {
        val first = file(1).requested!!.clips.single()
        val requested = EditTimeline(listOf(first.copy(startMs = 0L, endMs = 5_000L),
            first.copy(id = 2L, startMs = 15_000L, endMs = 20_000L)))
        val suggestion = FastCutSuggestion.create(requested,
            listOf(0L to 4_000L, 16_000L to 20_000L))!!
        val reviewed = BatchReviewedFile(first.uri, "joined.mp4", requested, suggestion)
        val result = BatchExportChoicePolicy.resolve(listOf(reviewed),
            mapOf(first.uri to BatchExportChoice.RECOMMENDED))!!.single()
        assertSame(suggestion.adjusted, result.timeline)
        assertEquals(2, result.timeline!!.clips.size)
    }

    @Test fun displayedMillisecondsCannotErasePreciseRecommendedOrExactRanges() {
        val original = file(1)
        val requested = EditTimeline(listOf(original.requested!!.clips.single()
            .withRangeUs(3_000_111L, 16_999_999L)))
        val suggestion = FastCutSuggestion.createUs(requested,
            listOf(3_000_333L to 16_999_777L))!!
        val reviewed = original.copy(requested = requested, suggestion = suggestion)
        val exact = BatchExportChoicePolicy.resolve(listOf(reviewed),
            mapOf(reviewed.uri to BatchExportChoice.EXACT))!!.single()
        assertSame(requested, exact.timeline)
        assertEquals(3_000_111L, exact.timeline!!.clips.single().startUs)
        assertEquals(16_999_999L, exact.timeline.clips.single().endUs)
        val recommended = BatchExportChoicePolicy.resolve(listOf(reviewed),
            mapOf(reviewed.uri to BatchExportChoice.RECOMMENDED))!!.single()
        assertEquals(3_000_333L, recommended.timeline!!.clips.single().startUs)
        assertEquals(16_999_777L, recommended.timeline.clips.single().endUs)
        val changedButSameDisplay = EditTimeline(listOf(requested.clips.single()
            .withRangeUs(3_000_222L, 16_999_999L)))
        assertEquals(requested.clips.single().startMs, changedButSameDisplay.clips.single().startMs)
        assertNull(exact.timelineFor(changedButSameDisplay))
        assertNull(recommended.timelineFor(changedButSameDisplay))
    }
}
