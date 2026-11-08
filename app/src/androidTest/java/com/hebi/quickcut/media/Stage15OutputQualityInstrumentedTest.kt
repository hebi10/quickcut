package com.hebi.quickcut.media

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.quickcut.editor.EditTimeline
import com.hebi.quickcut.editor.VideoClip
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Stage15OutputQualityInstrumentedTest {
    @Test fun supportedExplicitQualityExportsValidatedVideo() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "stage15_quality_export_source.mp4")
        instrumentation.context.assets.open("quickcut_keyframes.mp4").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        var published: Uri? = null
        try {
            val uri = Uri.fromFile(source)
            val meta = MediaReader.metadata(context, uri)
            val timeline = EditTimeline(listOf(
                VideoClip(1, uri.toString(), meta.durationMs, hasAudio = meta.hasAudio)
            ))
            val custom = ExportQualitySettings(quality = ExportQualityLevel.LOW)
            val evaluation = ExportQualityInspector.inspect(context, timeline, custom)
            if (!evaluation.supported) {
                assertTrue("Unsupported encoder must explain why", !evaluation.error.isNullOrBlank())
                return@runBlocking
            }
            val expected = requireNotNull(evaluation.plan)
            published = VideoExporter(context).export(
                timeline = timeline, onProgress = {}, onFinalizing = {},
                exportMode = ExportMode.ALLOW_REENCODE, outputQuality = custom,
            )
            val verified = MediaReader.validateExport(
                context, requireNotNull(published), meta.durationMs, meta.hasAudio
            )
            assertEquals(meta.hasAudio, verified.hasAudio)
            assertTrue(verified.durationMs >= 100L)
            assertTrue(expected.bitrate > 0)
        } finally {
            published?.let { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }

    @Test fun unsupportedUHDIsNotSilentlyConvertedToLowerResolution() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "stage15_4k_guard_source.mp4")
        instrumentation.context.assets.open("quickcut_keyframes.mp4").use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        }
        try {
            val uri = Uri.fromFile(source)
            val meta = MediaReader.metadata(context, uri)
            val timeline = EditTimeline(listOf(
                VideoClip(1, uri.toString(), meta.durationMs, hasAudio = meta.hasAudio)
            ))
            val result = ExportQualityInspector.inspect(context, timeline,
                ExportQualitySettings(resolution = ExportResolution.ULTRA_HD_2160))
            assertTrue(result.error != null || result.plan?.shortSide == 2160)
        } finally {
            source.delete()
        }
    }
}
