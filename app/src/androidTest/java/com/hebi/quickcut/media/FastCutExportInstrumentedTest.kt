package com.hebi.quickcut.media

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class FastCutExportInstrumentedTest {
    @Test fun acceptedInternalCutCopiesVideoAndRejectsAnUnapprovedExactReencode() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "fast-cut-review-source.mp4")
        instrumentation.context.assets.open("quickcut_bframes.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        var saved: Uri? = null
        try {
            val uri = Uri.fromFile(file)
            val metadata = MediaReader.metadata(context, uri)
            val clip = VideoClip(1, uri.toString(), metadata.durationMs, 0, 3_100, muted = true)
            val requested = EditTimeline(listOf(clip, clip.copy(id = 2, startMs = 3_900, endMs = 7_500)))
            val assessment = ExportPlanner.analyze(context, requested, SaveDateMode.CURRENT)
            assertFalse(assessment.fastAvailable)
            val suggestion = checkNotNull(assessment.suggestion)
            assertEquals(listOf(0L to 2_000L, 4_000L to 6_000L),
                suggestion.adjusted.clips.map { it.startMs to it.endMs })
            var report: ExportReport? = null
            saved = VideoExporter(context).export(suggestion.applyTo(requested)!!,
                onProgress = {}, onFinalizing = {}, exportMode = ExportMode.FAST_ONLY, onReport = { report = it })
            assertEquals(ExportMethod.ORIGINAL_COPY, report!!.method)
            MediaReader.validateExport(context, saved!!, 4_000, false)
            val rejected = runCatching {
                VideoExporter(context).export(requested, onProgress = {}, onFinalizing = {}, exportMode = ExportMode.FAST_ONLY)
            }.exceptionOrNull()
            assertTrue("Fast-only must ask again before encoding: $rejected", rejected is ReencodeRequiredException)
        } finally {
            saved?.let { context.contentResolver.delete(it, null, null) }
            file.delete()
        }
    }
}
