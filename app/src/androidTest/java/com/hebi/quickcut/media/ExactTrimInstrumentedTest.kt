package com.hebi.quickcut.media

import android.content.Context
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExactTrimInstrumentedTest {
    @Test
    fun fiftyMillisecondsBeforeKeyframeKeepsTheRequestedRedFramesAndAudio() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File.createTempFile("exact_boundary_source_", ".mp4", context.cacheDir)
        var output: Uri? = null
        try {
            instrumentation.context.assets.open("quickcut_exact_boundary.mp4").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val sourceUri = Uri.fromFile(source)
            val metadata = MediaReader.metadata(context, sourceUri)
            assertTrue("Fixture must include its AAC audio track", metadata.hasAudio)
            assertRed(centerColor(context, sourceUri, 1_950_000L), "Fixture before the keyframe")
            val blueAtKeyframe = centerColor(context, sourceUri, 2_000_000L)
            assertTrue("Fixture must switch to blue exactly at the two-second keyframe",
                Color.blue(blueAtKeyframe) > Color.red(blueAtKeyframe) + 100)

            val clip = VideoClip(
                id = 1L,
                uri = sourceUri.toString(),
                sourceDurationMs = metadata.durationMs,
                hasAudio = metadata.hasAudio,
                startMs = 1_950L,
            )
            val originalClip = clip.copy()
            val saved = VideoExporter(context).export(
                timeline = EditTimeline(listOf(clip)),
                onProgress = {},
                onFinalizing = {},
                exportMode = ExportMode.ALLOW_REENCODE,
                onOutputCreated = { output = it },
            )
            output = saved

            assertRed(
                centerColor(context, saved, 0L),
                "The first saved frame must retain red footage before the next keyframe",
            )
            val savedMetadata = MediaReader.validateExport(
                context, saved, expectedDurationMs = clip.durationMs, expectAudio = true,
            )
            assertTrue("Duration must preserve the requested interval within one 30fps frame: " +
                "requested=${clip.durationMs}ms actual=${savedMetadata.durationMs}ms",
                abs(savedMetadata.durationMs - clip.durationMs) <= 34L)
            assertTrue("The original audio track must survive", savedMetadata.hasAudio)
            assertEquals("Export must not move or mutate the original requested clip", originalClip, clip)
        } finally {
            output?.let { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }

    private fun centerColor(context: Context, uri: Uri, timeUs: Long): Int {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                160,
                90,
            ) ?: throw AssertionError("The requested frame at ${timeUs}us must decode")
            return try {
                frame.getPixel(frame.width / 2, frame.height / 2)
            } finally {
                frame.recycle()
            }
        } finally {
            retriever.release()
        }
    }

    private fun assertRed(color: Int, message: String) {
        assertTrue("$message: red=${Color.red(color)} blue=${Color.blue(color)}",
            Color.red(color) > 150 && Color.red(color) > Color.blue(color) + 100)
    }
}
