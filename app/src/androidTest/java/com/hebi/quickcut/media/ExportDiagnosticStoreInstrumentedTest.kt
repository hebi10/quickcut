package com.hebi.quickcut.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.PlaybackException
import androidx.media3.transformer.ExportException
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import org.junit.Assert.*
import org.junit.Test

class ExportDiagnosticStoreInstrumentedTest {
    @Test fun preExportOperationFailuresHaveTheirOwnRecordAndNeverExposeSourceIdentifiers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = ExportDiagnosticStore.begin(context, EditTimeline())
        saved.finish("완료")
        for (stage in listOf(ExportStage.IMPORTING, ExportStage.RESTORING, ExportStage.REVIEWING)) {
            val operation = checkNotNull(MediaOperationDiagnostics.begin(context, stage, 2))
            operation.finish("실패", SecurityException("content://private/secret-source.mp4"))
            val record = checkNotNull(ExportDiagnosticStore.record(context, operation.operationId))
            assertEquals(stage, record.stage)
            assertEquals(ExportDiagnosticStatus.FAILED, record.status)
            assertEquals(2, record.facts.sourceCount)
            assertEquals("SecurityException", record.failureType)
            assertNotEquals(saved.operationId, record.operationId)
            assertFalse(record.text.contains("secret-source"))
            assertFalse(record.text.contains("content://"))
        }
    }

    @OptIn(UnstableApi::class)
    @Test fun previewFailureCodeRoundTripsWithoutBeingMistakenForTransformerEncodingFailure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val error = PlaybackException("/storage/private/secret-source.mp4", null, 4003)
        val id = checkNotNull(MediaOperationDiagnostics.recordFailure(context, ExportStage.PREVIEWING, error, 1))
        val record = checkNotNull(ExportDiagnosticCodec.decode(
            ExportDiagnosticCodec.encode(ExportDiagnosticStore.history(context))).record(id))
        assertEquals(ExportStage.PREVIEWING, record.stage)
        assertEquals("PlaybackException", record.failureType)
        assertEquals(4003, record.failureEvidence?.playbackErrorCode)
        assertNull(record.failureEvidence?.media3ErrorCode)
        assertTrue(record.text.contains("ERROR_CODE_DECODING_FAILED"))
        assertFalse(record.text.contains("ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED"))
        assertFalse(record.text.contains("secret-source"))
        assertFalse(record.text.contains("/storage/"))
    }

    @OptIn(UnstableApi::class)
    @Test fun media3FailureRetainsCodecAndErrorCodeWithoutPrivateFormatOrMessage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val timeline = EditTimeline(listOf(VideoClip(1, "content://private/secret-source.mp4", 1000)))
        val session = ExportDiagnosticStore.begin(context, timeline)
        session.stage(ExportStage.PROCESSING)
        val failure = ExportException.createForCodec(
            IllegalStateException("/storage/private/secret-message.mp4"),
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.CodecInfo("content://private/secret-format.mp4", true, true,
                "c2.android.avc.decoder"),
        )
        session.finish("실패", failure)
        val record = checkNotNull(ExportDiagnosticStore.record(context, session.operationId))
        assertTrue(record.text.contains("ERROR_CODE_DECODING_FAILED"))
        assertTrue(record.text.contains("c2.android.avc.decoder"))
        assertTrue(record.text.contains("MEDIA3"))
        val persisted = context.getSharedPreferences("export_diagnostics", Context.MODE_PRIVATE)
            .getString("history_v1", "").orEmpty()
        for (secret in listOf("secret-source", "secret-message", "secret-format", "content://", "/storage/")) {
            assertFalse(record.text.contains(secret))
            assertFalse(persisted.contains(secret))
        }
    }

    @OptIn(UnstableApi::class)
    @Test fun media3PermissionFailureAndMuxTimeoutRemainDistinguishableAfterReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((code, expected) in listOf(
            ExportException.ERROR_CODE_IO_NO_PERMISSION to "ERROR_CODE_IO_NO_PERMISSION",
            ExportException.ERROR_CODE_MUXING_TIMEOUT to "ERROR_CODE_MUXING_TIMEOUT",
        )) {
            val session = ExportDiagnosticStore.begin(context, EditTimeline())
            session.finish("실패", ExportException.createForAssetLoader(
                java.io.IOException("content://private/secret.mp4"), code))
            val restored = checkNotNull(ExportDiagnosticStore.record(context, session.operationId))
            assertTrue(restored.text.contains(expected))
            assertFalse(restored.text.contains("secret.mp4"))
        }
    }

    @Test fun corruptOptionalCodecEvidenceCannotDiscardTheFailureOrLeakPrivateStrings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = ExportDiagnosticStore.begin(context, EditTimeline())
        session.finish("실패", java.io.IOException("private-source.mp4"))
        val encoded = org.json.JSONObject(ExportDiagnosticCodec.encode(ExportDiagnosticStore.history(context)))
        val row = encoded.getJSONArray("recent").getJSONObject(0)
        row.put("engine", "content://private/engine")
        row.put("failure", org.json.JSONObject()
            .put("media3", 7002).put("codec", "/storage/private/source.mp4")
            .put("codecError", 999999))
        val restored = checkNotNull(ExportDiagnosticCodec.decode(encoded.toString()).record(session.operationId))
        assertEquals(ExportDiagnosticStatus.FAILED, restored.status)
        assertTrue(restored.text.contains("ERROR_CODE_MUXING_TIMEOUT"))
        assertFalse(restored.text.contains("source.mp4"))
        assertFalse(restored.text.contains("999999"))
        assertFalse(restored.text.contains("content://"))
    }

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
