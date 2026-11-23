package com.hebi.quickcut.batch

import com.hebi.quickcut.media.OriginalTimestamp
import com.hebi.quickcut.media.SaveDateMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchPreviewEstimateTest {
    private fun file(id: Int, duration: Long, status: BatchFileStatus = BatchFileStatus.WAITING,
        originalDate: OriginalTimestamp? = null) = BatchFile(
        uri = "content://media/external/video/media/" + id,
        displayName = "fixture-" + id, durationMs = duration,
        hasAudio = true, status = status, originalDate = originalDate)

    @Test fun lengthsForEachSourceReflectActualTemplatePlan() {
        val template = BatchTemplate(leadingMs = 3_000L, trailingMs = 3_000L)
        assertEquals("예상 결과 · 20초 → 14초",
            BatchPreviewEstimate.fileLabel(file(1, 20_000L), template, SaveDateMode.CURRENT))
        assertTrue(BatchPreviewEstimate.fileLabel(file(2, 5_000L), template, SaveDateMode.CURRENT)
            .startsWith("저장 불가"))
    }

    @Test fun perVideoEdgesAffectOnlyThatVideoAndOnlyInEdgeTrimMode() {
        val template = BatchTemplate(leadingMs = 3_000, trailingMs = 3_000)
        val first = file(1, 20_000).copy(edgeTrim = BatchEdgeTrim(2_000, 4_500))
        val second = file(2, 20_000).copy(edgeTrim = BatchEdgeTrim(0, 1_000))
        val common = file(3, 20_000)
        val clips = listOf(first, second, common).map {
            (template.plan(it) as BatchPlan.Ready).timeline.clips.single()
        }
        assertEquals(listOf(2_000L to 15_500L, 0L to 19_000L, 3_000L to 17_000L),
            clips.map { it.startMs to it.endMs })
        assertEquals("예상 결과 · 20초 → 13.5초",
            BatchPreviewEstimate.fileLabel(first, template, SaveDateMode.CURRENT))
        assertTrue(template.plan(first.copy(durationMs = 6_550)) is BatchPlan.Skip)
        val keep = (template.copy(mode = BatchClipMode.KEEP_FIRST).plan(first) as BatchPlan.Ready)
        assertEquals(3_000L, keep.timeline.totalDurationMs)
    }

    @Test fun countsExcludeAlreadySavedFilesAndTreatShortOrUncertainSourcesAsUnavailable() {
        val template = BatchTemplate()
        val files = listOf(file(1, 20_000L), file(2, 5_000L),
            file(3, 20_000L, BatchFileStatus.SAVED), file(4, 20_000L).copy(
                savedOutputUri = "content://media/external/video/media/101", status = BatchFileStatus.SKIPPED))
        assertEquals(BatchPreviewCounts(1, 2),
            BatchPreviewEstimate.counts(files, template, SaveDateMode.CURRENT))
    }

    @Test fun sourceDateRequirementPreventsMisleadingReadyPrediction() {
        val template = BatchTemplate()
        val missing = file(1, 20_000L)
        val dated = file(2, 20_000L, originalDate = OriginalTimestamp(1_600_000_000_000L, "촬영 날짜"))
        assertEquals(BatchPreviewCounts(1, 1),
            BatchPreviewEstimate.counts(listOf(missing, dated), template, SaveDateMode.ORIGINAL))
        assertTrue(BatchPreviewEstimate.fileLabel(missing, template, SaveDateMode.ORIGINAL).startsWith("저장 불가"))
    }

    @Test fun savedOutcomeUsesCommittedDurationAndInvalidTemplateShowsNoFabricatedPreview() {
        val template = BatchTemplate()
        val saved = file(1, 20_000L, status = BatchFileStatus.SAVED).copy(outputExpectedDurationMs = 14_001L)
        assertEquals("저장 결과 · 20초 → 14.001초",
            BatchPreviewEstimate.fileLabel(saved, template, SaveDateMode.CURRENT))
        assertNull(BatchPreviewEstimate.counts(listOf(file(2, 20_000L)), null, SaveDateMode.CURRENT))
        assertTrue(BatchPreviewEstimate.fileLabel(file(2, 20_000L), null, SaveDateMode.CURRENT)
            .contains("시간 설정"))
    }
}
