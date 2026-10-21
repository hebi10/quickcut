package com.hebi.quickcut.media

import android.net.Uri
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
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
            val successDiagnostic = checkNotNull(ExportDiagnosticStore.latest(context))
            assertTrue(successDiagnostic.contains("단계: 저장 완료"))
            assertFalse(successDiagnostic.contains(file.name))
            assertFalse(successDiagnostic.contains(uri.toString()))
            val rejected = runCatching {
                VideoExporter(context).export(requested, onProgress = {}, onFinalizing = {}, exportMode = ExportMode.FAST_ONLY)
            }.exceptionOrNull()
            assertTrue("Fast-only must ask again before encoding: $rejected", rejected is ReencodeRequiredException)
            assertTrue(ExportDiagnosticStore.latest(context)!!.contains("ReencodeRequiredException"))
        } finally {
            saved?.let { context.contentResolver.delete(it, null, null) }
            file.delete()
        }
    }

    @Test fun fractionalKeyframeConsentPreservesExactPacketAndMedia3Coordinates(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "fractional-review-source.mp4")
        instrumentation.context.assets.open("quickcut_fractional.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        var saved: Uri? = null
        try {
            val uri = Uri.fromFile(file)
            val original = VideoClip(1, uri.toString(), MediaReader.metadata(context, uri).durationMs,
                3_000, 7_500, muted = true, hasAudio = false)
            val timeline = EditTimeline(listOf(original))
            val assessment = ExportPlanner.analyze(context, timeline, SaveDateMode.CURRENT)
            val suggestion = checkNotNull(assessment.suggestion)
            val accepted = suggestion.applyTo(timeline)!!.clips.single()
            assertTrue("Expected an actual fractional sync timestamp", accepted.startUs % 1_000L != 0L)
            assertTrue(accepted.startUs >= original.startUs)
            assertEquals(original.endUs, accepted.endUs)
            assertEquals(accepted.startUs, CompositionFactory.mediaItem(accepted).clippingConfiguration.startPositionUs)
            assertEquals(accepted.endUs, CompositionFactory.mediaItem(accepted).clippingConfiguration.endPositionUs)
            var report: ExportReport? = null
            saved = VideoExporter(context).export(EditTimeline(listOf(accepted)), {}, {},
                exportMode = ExportMode.FAST_ONLY, onReport = { report = it })
            assertEquals(ExportMethod.ORIGINAL_COPY, report!!.method)
            fun firstPacket(source: Uri, timeUs: Long): ByteArray {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, source, null)
                    val track = (0 until extractor.trackCount).first {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
                    }
                    extractor.selectTrack(track)
                    extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
                    val data = ByteBuffer.allocate(1_048_576)
                    val size = extractor.readSampleData(data, 0)
                    assertTrue(size > 0)
                    return ByteArray(size).also { data.position(0); data.get(it) }
                } finally { extractor.release() }
            }
            assertArrayEquals(firstPacket(uri, accepted.startUs), firstPacket(saved!!, 0))
            MediaReader.validateExport(context, saved!!, accepted.durationMs, false)
        } finally {
            saved?.let { context.contentResolver.delete(it, null, null) }
            file.delete()
        }
    }
}
