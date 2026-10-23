package com.hebi.quickcut.batch

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.media.SaveDateMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchSessionStoreInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun snapshot(output: String? = null) = BatchSessionSnapshot(
        listOf(BatchFile("content://source/1", "source.mp4", 3_000L, true,
            status = BatchFileStatus.RUNNING, savedOutputUri = output,
            outputExpectedDurationMs = 2_000L, outputExpectedAudio = true)),
        null, SaveDateMode.CURRENT, BatchRunState.RUNNING)

    @Test fun diagnosticIdRoundTripsButMissingOrInvalidOptionalIdDoesNotLoseOutputHandle() {
        val base = File.createTempFile("batch_diagnostic_id", ".json", context.cacheDir).apply { delete() }
        val store = BatchSessionStore(base)
        val id = java.util.UUID.randomUUID().toString()
        val recorded = snapshot("content://media/external/video/media/123").let {
            it.copy(files = it.files.map { row -> row.copy(diagnosticId = id) })
        }
        try {
            store.save(recorded)
            assertEquals(id, store.load()!!.files.single().diagnosticId)
            for (value in listOf<String?>(null, "private source content://source/1", "123", "00000000-0-0-0-0")) {
                val data = org.json.JSONObject(base.readText())
                val row = data.getJSONArray("files").getJSONObject(0)
                if (value == null) row.remove("diagnosticId") else row.put("diagnosticId", value)
                base.writeText(data.toString())
                val restored = store.load()!!.files.single()
                assertNull(restored.diagnosticId)
                assertEquals(recorded.files.single().savedOutputUri, restored.savedOutputUri)
            }
        } finally { store.clear() }
    }

    @Test fun silentRenameFailureIsReportedAndLastCommittedJournalSurvives() {
        val base = File.createTempFile("batch_atomic_rename", ".json", context.cacheDir).apply { delete() }
        val normal = BatchSessionStore(base)
        val previous = snapshot()
        normal.save(previous)
        val failedCommit = object : AtomicFile(base) {
            override fun finishWrite(stream: FileOutputStream?) { stream?.close() }
        }
        try {
            val failure = runCatching {
                BatchSessionStore(base, failedCommit).save(snapshot("content://media/external/video/media/123"))
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(previous, normal.load())
        } finally { normal.clear() }
    }

    @Test fun syncFailureIsReportedAndLastCommittedJournalSurvives() {
        val base = File.createTempFile("batch_atomic_sync", ".json", context.cacheDir).apply { delete() }
        val normal = BatchSessionStore(base)
        val previous = snapshot()
        normal.save(previous)
        try {
            val failure = runCatching {
                BatchSessionStore(base, sync = { throw IOException("test sync failure") })
                    .save(snapshot("content://media/external/video/media/123"))
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(previous, normal.load())
        } finally { normal.clear() }
    }

    @Test fun unknownStatusAndMissingOutputFieldAreCorruptionRatherThanAbsence() {
        val base = File.createTempFile("batch_atomic_corrupt", ".json", context.cacheDir).apply { delete() }
        val store = BatchSessionStore(base)
        try {
            for (alter in listOf<(org.json.JSONObject) -> Unit>(
                { it.put("status", "FUTURE_STATE") }, { it.remove("output") })) {
                store.save(snapshot())
                val data = org.json.JSONObject(base.readText())
                alter(data.getJSONArray("files").getJSONObject(0))
                base.writeText(data.toString())
                assertTrue(runCatching { store.load() }.isFailure)
                assertTrue(base.exists())
            }
        } finally { store.clear() }
    }

    @Test fun interruptedReplacementRestoresLastCompleteSnapshot() {
        val base = File.createTempFile("batch_atomic_interrupted", ".json", context.cacheDir).apply { delete() }
        val store = BatchSessionStore(base)
        try {
            val previous = snapshot("content://media/external/video/media/123")
            store.save(previous)
            File(base.path + ".new").writeText("{incomplete")
            assertEquals(previous, store.load())
        } finally { store.clear() }
    }
}
