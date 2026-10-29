package com.hebi.quickcut.media

import org.junit.Assert.*
import org.junit.Test

class ExportStageTimingTest {
    @Test fun recurringStagesAccumulateRatherThanReplacingEarlierTime() {
        var now = 100L
        val timing = ExportStageTiming { now }
        now += 10; timing.stage(ExportStage.ANALYZING)
        now += 20; timing.stage(ExportStage.PROCESSING)
        now += 30; timing.stage(ExportStage.ANALYZING)
        now += 40
        val snapshot = timing.snapshot()
        assertEquals(100L, snapshot.elapsedMs)
        assertEquals(60L, snapshot.stageMs[ExportStage.ANALYZING])
        assertEquals(30L, snapshot.stageMs[ExportStage.PROCESSING])
        assertEquals(10L, snapshot.stageMs[ExportStage.PREPARING])
    }

    @Test fun finishIsIdempotentAndCompletedViewNeverChanges() {
        var now = 100L
        val timing = ExportStageTiming { now }
        now = 150; timing.stage(ExportStage.COPYING)
        now = 200
        val finished = timing.finish()
        now = 900; timing.stage(ExportStage.VALIDATING)
        assertEquals(finished, timing.finish())
        assertEquals(finished, timing.snapshot())
        assertEquals(100L, finished.elapsedMs)
        assertEquals(100L, finished.stageMs.values.sum())
    }
}
