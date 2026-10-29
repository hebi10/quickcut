package com.hebi.quickcut.media

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import org.junit.Assert.*
import org.junit.Test

class ExportDiagnosticStoreInstrumentedTest {
    @Test fun persistedFailureSurvivesSuccessAndNeverIncludesSourceOrExceptionMessage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val timeline = EditTimeline(listOf(VideoClip(1, "content://private/secret-source.mp4", 1000)))
        val failed = ExportDiagnosticStore.begin(context, timeline)
        failed.stage(ExportStage.VALIDATING)
        failed.finish("실패", java.io.IOException("/storage/private/private-error.mp4"))
        repeat(ExportDiagnosticHistory.RECENT_LIMIT + 1) {
            ExportDiagnosticStore.begin(context, timeline).finish("완료")
        }
        val history = ExportDiagnosticStore.history(context)
        assertFalse(history.recent.any { it.operationId == failed.operationId })
        val record = checkNotNull(ExportDiagnosticStore.record(context, failed.operationId))
        assertTrue(history.failures.any { it.operationId == failed.operationId })
        assertEquals(ExportDiagnosticStatus.FAILED, record.status)
        assertTrue(record.text.contains("IOException"))
        assertFalse(record.text.contains("secret-source"))
        assertFalse(record.text.contains("private-error"))
        assertFalse(record.text.contains("content://"))
        val persisted = context.getSharedPreferences("export_diagnostics", Context.MODE_PRIVATE)
            .getString("history_v1", "").orEmpty()
        assertFalse(persisted.contains("secret-source"))
        assertFalse(persisted.contains("private-error"))
        assertFalse(persisted.contains("content://"))
        val before = record.text
        failed.finish("완료")
        assertEquals(before, ExportDiagnosticStore.record(context, failed.operationId)!!.text)
    }

    @Test fun corruptHistoryCannotPreventANewDiagnostic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("export_diagnostics", Context.MODE_PRIVATE)
        val previous = preferences.getString("history_v1", null)
        try {
            preferences.edit().putString("history_v1", "{broken").commit()
            assertTrue(ExportDiagnosticStore.history(context).recent.isEmpty())
            val session = ExportDiagnosticStore.begin(context, EditTimeline())
            session.finish("취소")
            assertEquals(ExportDiagnosticStatus.CANCELLED,
                ExportDiagnosticStore.record(context, session.operationId)!!.status)
        } finally {
            preferences.edit().putString("history_v1", previous).commit()
        }
    }
}
