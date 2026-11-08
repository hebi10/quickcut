package com.hebi.quickcut.segments

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.media.ExportMode
import com.hebi.quickcut.media.MediaReader
import com.hebi.quickcut.media.VideoExporter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Stage14MultiSegmentExportInstrumentedTest {
    @Test fun threeRangesProduceThreeIndependentlyVerifiedMp4Files() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "stage14_three_ranges.mp4")
        instrumentation.context.assets.open("quickcut_keyframes.mp4").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        val outputs = mutableListOf<Uri>()
        try {
            val sourceUri = Uri.fromFile(source)
            val metadata = MediaReader.metadata(context, sourceUri)
            val step = minOf(metadata.durationMs / 4, 1_000L)
            assertTrue("Need three independent valid test ranges", step >= 100L)
            val ranges = (0L..2L).map { n ->
                ExtractionRange(n + 1, n * step, (n + 1) * step)
            }
            for ((index, range) in ranges.withIndex()) {
                val timeline = ExtractionPlanner.timeline(
                    sourceUri.toString(), metadata.durationMs, metadata.hasAudio, range
                )
                val output = VideoExporter(context).export(
                    timeline = timeline,
                    onProgress = {},
                    onFinalizing = {},
                    exportMode = ExportMode.ALLOW_REENCODE,
                    outputBaseName = ExtractionPlanner.outputName("three_ranges.mp4", index + 1),
                )
                outputs += output
                val verified = MediaReader.validateExport(
                    context, output, range.durationMs, metadata.hasAudio
                )
                assertTrue(verified.durationMs > 0L)
            }
            assertEquals(3, outputs.size)
            assertEquals(3, outputs.map { it.toString() }.distinct().size)
            assertFalse(source.length() == 0L)
        } finally {
            outputs.forEach { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }

    @Test fun journalPreservesCompletedRangeAndReencodeRetryState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ExtractionSessionStore(context, "stage14_recovery_test.json")
        store.clear()
        try {
            val completed = ExtractionItem(
                ExtractionRange(1, 0, 1_000),
                status = ExtractionStatus.SAVED,
                outputUri = "content://media/external/video/media/123",
            )
            val failed = ExtractionItem(
                ExtractionRange(2, 1_000, 2_000),
                status = ExtractionStatus.FAILED,
                reencodeNeeded = true,
                message = "Reencode approval needed",
            )
            val source = ExtractionSession(
                sourceUri = "content://source/video/1",
                sourceName = "sample.mp4",
                durationMs = 3_000,
                hasAudio = true,
                items = listOf(completed, failed),
            )
            store.save(source)
            assertEquals(source, store.load())
        } finally {
            store.clear()
        }
    }
}
